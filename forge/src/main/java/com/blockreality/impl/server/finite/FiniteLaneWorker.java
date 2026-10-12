package com.blockreality.impl.server.finite;

import com.blockreality.core.bsi.BsiResponse;
import com.blockreality.core.finite.FiniteBasis;
import com.blockreality.core.finite.FiniteBlobRef;
import com.blockreality.core.finite.FiniteCheckpointRef;
import com.blockreality.core.finite.FiniteDomainId;
import com.blockreality.core.finite.FiniteLane;
import com.blockreality.core.finite.FiniteRegistry;
import com.blockreality.core.finite.FiniteRoot;
import com.blockreality.core.finite.FiniteSession;
import com.blockreality.core.finite.FiniteSource;
import com.blockreality.core.finite.FiniteStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The single daemon lane that owns every {@link FiniteSession}/JNA call for finite work
 * (design §5.1): the server thread only posts immutable messages here, never joins a
 * worker future, and never opens or closes a native session itself. All engine calls —
 * open, vocabulary declare, world declare, checkpoint import — run on this lane.
 *
 * <p>Session revocation is generation-based: ops carry the generation minted on the
 * server thread, the lane drops work stamped with a revoked generation, and a result is
 * re-checked against the live generation before it is published. If a native call never
 * returns the lane is a daemon — nothing here can be killed, only revoked.
 */
public final class FiniteLaneWorker implements AutoCloseable {

    /** One opened engine lane plus the close path; both run on the worker thread. */
    public record Lane(FiniteLane lane, AutoCloseable closer) {
        public Lane { Objects.requireNonNull(lane); Objects.requireNonNull(closer); }
    }

    /** Opens one native lane. Called exclusively on the worker thread. */
    @FunctionalInterface
    public interface LaneFactory { Lane open() throws Exception; }

    /** What the worker reads from the runtime — suppliers so a re-fold is picked up. */
    public interface Env {
        FiniteRegistry registry();
        FiniteStore store();
        long generation();
    }

    /** One authority-sync job: restore the session to exactly the committed root. */
    public record Sync(FiniteDomainId domain, FiniteSource source, FiniteBasis basis,
                       FiniteRoot root, FiniteCheckpointRef checkpoint, long generation) {
        public Sync { Objects.requireNonNull(domain); if (generation < 0) throw new IllegalArgumentException(); }
    }

    public enum Kind { SYNCED, REFUSED, STALE, UNAVAILABLE, FAILED }
    public record LaneEvent(Kind kind, FiniteDomainId domain, FiniteBasis basis, String detail) {
        public LaneEvent { Objects.requireNonNull(kind); Objects.requireNonNull(domain); }
    }

    private static final int MAX_INFLIGHT = 64, MAX_LANES = 8, MAX_EVENTS = 4096;

    private final LaneFactory factory;
    private final Env env;
    private final Consumer<String> log;
    private final ExecutorService worker;
    private final AtomicInteger inflight = new AtomicInteger();
    private final ConcurrentLinkedQueue<LaneEvent> events = new ConcurrentLinkedQueue<>();
    private final Map<String, Sync> pendingSync = new ConcurrentHashMap<>();
    private final Map<String, Holder> lanes = new HashMap<>();          // worker thread only
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile Lane utility;                                      // worker thread only

    private static final class Holder {
        final Lane lane; FiniteSession session; FiniteBasis basis; String refusal = "";
        Holder(Lane lane) { this.lane = lane; }
    }

    public FiniteLaneWorker(String name, LaneFactory factory, Env env, Consumer<String> log) {
        this.factory = Objects.requireNonNull(factory);
        this.env = Objects.requireNonNull(env);
        this.log = Objects.requireNonNull(log);
        this.worker = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "br-finite-lane-" + name);
            thread.setDaemon(true);
            return thread;
        });
    }

    // ---------------------------------------------------------------- submissions (any thread)

    /** Post a latest-wins authority sync for one domain; queue admission is bounded. */
    public void sync(Sync job) {
        Objects.requireNonNull(job);
        if (!running.get()) { emit(new LaneEvent(Kind.UNAVAILABLE, job.domain(), null, "lane worker is closed")); return; }
        pendingSync.put(job.domain().dimension(), job);
        if (!offer(() -> drainSync(job.domain().dimension()))) pendingSync.remove(job.domain().dimension(), job);
    }

    /**
     * One raw lane request (e.g. minting a vocabulary reply). The returned future completes
     * on the worker thread — callers on the server thread must never {@code join}/{@code get}.
     */
    public CompletableFuture<BsiResponse> call(String headerJson, byte[] payload, long generation) {
        var result = new CompletableFuture<BsiResponse>();
        Objects.requireNonNull(headerJson);
        if (!running.get()) { result.completeExceptionally(new IllegalStateException("finite lane is closed")); return result; }
        if (!offer(() -> runCall(headerJson, payload, generation, result)))
            result.completeExceptionally(new IllegalStateException("finite lane admission full"));
        return result;
    }

    /** Close every lane/session on the worker thread; the caller bumps the generation first. */
    public void reset() {
        pendingSync.clear();
        offer(this::closeLanes);
    }

    private boolean offer(Runnable task) {
        if (!running.get() || inflight.incrementAndGet() > MAX_INFLIGHT) {
            inflight.decrementAndGet();
            return false;
        }
        try {
            worker.execute(() -> { try { task.run(); } finally { inflight.decrementAndGet(); } });
        } catch (RuntimeException rejected) {
            inflight.decrementAndGet();
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- worker thread

    private void drainSync(String dimension) {
        Sync job = pendingSync.remove(dimension);
        if (job == null || !running.get()) return;
        runSync(job);
    }

    private void runSync(Sync job) {
        try {
            if (job.generation() != env.generation()) {
                emit(new LaneEvent(Kind.STALE, job.domain(), null, "revoked session generation")); return;
            }
            FiniteSource source = job.source();
            FiniteBasis basis = job.basis();
            FiniteCheckpointRef checkpoint = job.checkpoint();
            if (source == null) {
                // Derive-at-run: the current committed authority is read inside the lane.
                FiniteRegistry registry = env.registry();
                Optional<FiniteRoot> root = registry.root(job.domain());
                if (root.isEmpty()) {
                    emit(new LaneEvent(Kind.REFUSED, job.domain(), null, "no committed finite root")); return;
                }
                source = registry.source(job.domain());
                basis = registry.basis(job.domain(), job.generation());
                checkpoint = checkpointRef(root.get(), basis);
            } else {
                // Snapshot path: refuse to host a stale world image.
                Optional<FiniteRoot> current = env.registry().root(job.domain());
                if (!current.equals(Optional.ofNullable(job.root()))) {
                    emit(new LaneEvent(Kind.STALE, job.domain(), null, "committed finite root moved")); return;
                }
            }
            String dimension = job.domain().dimension();
            Holder holder = lanes.get(dimension);
            if (holder == null) {
                if (lanes.size() >= MAX_LANES) {
                    emit(new LaneEvent(Kind.UNAVAILABLE, job.domain(), null, "finite lane capacity")); return;
                }
                Lane opened;
                try { opened = factory.open(); }
                catch (Exception failure) {
                    emit(new LaneEvent(Kind.UNAVAILABLE, job.domain(), null, String.valueOf(failure.getMessage()))); return;
                }
                if (opened == null || !opened.lane().ready()) {
                    if (opened != null) try { opened.closer().close(); } catch (Exception ignored) { }
                    emit(new LaneEvent(Kind.UNAVAILABLE, job.domain(), null, "finite engine lane is not ready"));
                    return;
                }
                holder = new Holder(opened);
                lanes.put(dimension, holder);
            }
            if (holder.session != null) holder.session.close();
            FiniteSession session;
            try {
                session = FiniteSession.attach(holder.lane.lane(), job.domain(), source, job.generation());
            } catch (IllegalArgumentException failure) {
                emit(new LaneEvent(Kind.UNAVAILABLE, job.domain(), null, String.valueOf(failure.getMessage()))); return;
            }
            if (!session.declareVocabulary() || !session.declareWorld(basis.worldRevision())) {
                refuse(holder, job, session); return;
            }
            if (checkpoint != null) {
                byte[] blob = env.store().read(checkpoint.blob()).payload();
                if (session.importCheckpoint(checkpoint, blob) == null) { refuse(holder, job, session); return; }
            }
            if (job.generation() != env.generation()) {
                session.close();
                emit(new LaneEvent(Kind.STALE, job.domain(), null, "generation revoked during sync")); return;
            }
            holder.session = session;
            holder.basis = basis;
            holder.refusal = "";
            emit(new LaneEvent(Kind.SYNCED, job.domain(), basis, "step=" + session.step()));
        } catch (Exception failure) {
            emit(new LaneEvent(Kind.FAILED, job.domain(), null, String.valueOf(failure.getMessage())));
        }
    }

    private void refuse(Holder holder, Sync job, FiniteSession session) {
        holder.session = session;
        holder.basis = null;
        holder.refusal = session.refusal();
        emit(new LaneEvent(Kind.REFUSED, job.domain(), null, session.refusal()));
    }

    private void runCall(String headerJson, byte[] payload, long generation,
                         CompletableFuture<BsiResponse> result) {
        try {
            if (generation != env.generation()) {
                result.completeExceptionally(new IllegalStateException("finite session generation revoked")); return;
            }
            Lane lane = utilityLane();
            if (lane == null || !lane.lane().ready()) {
                result.completeExceptionally(new IllegalStateException("finite engine lane is not ready")); return;
            }
            BsiResponse response = lane.lane().request(headerJson, payload);
            if (generation != env.generation()) {
                result.completeExceptionally(new IllegalStateException("finite session generation revoked")); return;
            }
            result.complete(response);
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    private Lane utilityLane() throws Exception {
        if (utility == null) utility = factory.open();
        return utility;
    }

    private void closeLanes() {
        for (Holder holder : lanes.values()) {
            if (holder.session != null) holder.session.close();
            try { holder.lane.closer().close(); } catch (Exception ignored) { }
        }
        lanes.clear();
        if (utility != null) { try { utility.closer().close(); } catch (Exception ignored) { } utility = null; }
    }

    private static FiniteCheckpointRef checkpointRef(FiniteRoot root, FiniteBasis basis) {
        FiniteRoot.Checkpoint slot = root.checkpoint();
        if (slot == null) return null;
        return new FiniteCheckpointRef(slot.engineBuild(), slot.contractSha256(), slot.format(),
                basis, slot.blob(), slot.byteBudget(), slot.allocationBudget(), slot.elementBudget());
    }

    private void emit(LaneEvent event) {
        events.add(event);
        if (events.size() > MAX_EVENTS) events.poll();   // diagnostics are bounded, never blocking
        log.accept("finite lane " + event.kind() + " " + event.domain().dimension() + " " + event.detail());
    }

    // ---------------------------------------------------------------- drain + test seams

    /** Drain at most {@code max} completed lane events. Server-thread cheap; never blocks. */
    public int drainEvents(int max, Consumer<LaneEvent> sink) {
        int drained = 0;
        LaneEvent event;
        while (drained < max && (event = events.poll()) != null) { sink.accept(event); drained++; }
        return drained;
    }

    /** Test seam: completes once every submitted task has run. Never called from the server tick. */
    public boolean awaitIdle(long millis) throws InterruptedException {
        var latch = new CountDownLatch(1);
        if (!offer(latch::countDown)) return inflight.get() == 0 && pendingSync.isEmpty();
        boolean idle = latch.await(millis, TimeUnit.MILLISECONDS);
        return idle && pendingSync.isEmpty();
    }

    @Override public void close() {
        if (!running.compareAndSet(true, false)) return;
        pendingSync.clear();
        // Queued-but-not-started tasks are abandoned deliberately; a parked native call
        // cannot be interrupted, so shutdown() is used instead of shutdownNow(). The close
        // itself bypasses offer(): the lane is closing, not admitting.
        try {
            worker.execute(this::closeLanes);
        } catch (RuntimeException rejected) {
            closeLanes();
        }
        worker.shutdown();
    }
}
