package com.blockreality.impl.server.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.finite.ArtifactBinding;
import com.blockreality.core.finite.FiniteBasis;
import com.blockreality.core.finite.FiniteBlobRef;
import com.blockreality.core.finite.FiniteCheckpointRef;
import com.blockreality.core.finite.FiniteCodec;
import com.blockreality.core.finite.FiniteDomain;
import com.blockreality.core.finite.FiniteDomainId;
import com.blockreality.core.finite.FiniteRegistry;
import com.blockreality.core.finite.FiniteRoot;
import com.blockreality.core.finite.FiniteSource;
import com.blockreality.core.finite.FiniteStore;
import com.blockreality.core.finite.FiniteCodec.Operation;
import com.blockreality.core.transaction.AtomicConstructionCoordinator;
import com.blockreality.core.transaction.ConstructionTransaction;
import com.blockreality.core.transaction.ConstructionTransaction.Change;
import com.blockreality.core.transaction.ConstructionTransaction.Entry;
import com.blockreality.core.transaction.ConstructionTransaction.Phase;
import com.blockreality.core.transaction.ConstructionTransaction.Receipt;
import com.blockreality.core.transaction.ConstructionTransaction.Request;
import com.blockreality.core.transaction.FileTransactionJournal;
import com.blockreality.core.transaction.ManufacturedPiece;
import com.blockreality.core.transaction.ManufacturedRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/**
 * The finite runtime kernel: shared-journal authority, finite transaction bridge and the
 * owner of the {@link FiniteLaneWorker} (design §4–§6). It deliberately contains no
 * Minecraft types — world observation enters as immutable {@link DomainObservation}
 * values and every native call happens on the lane.
 *
 * <p>Authority rules:
 *
 * <ul>
 *   <li>The store sits beside the construction journal ({@code blockreality/finite}) and is
 *       opened through {@link FiniteStore#openAdjacentTo}; the journal itself is the shared
 *       {@link FileTransactionJournal} instance owned by the construction service — it is
 *       never reopened here.
 *   <li>Every finite mutation is a regular construction transaction: the
 *       {@code FiniteRegistry.prepare*} proposal becomes a journal {@code Intent} decided by
 *       the shared {@link AtomicConstructionCoordinator}, and committed entries publish
 *       through {@code FiniteRegistry.applyCommitted}.
 *   <li>{@code request.baseRevision} is the finite order — the {@code finite/order}
 *       participant is the revision image the coordinator CAS-checks, so a stale proposal
 *       records a durable {@code STALE_REVISION} refusal.
 *   <li>Adoption is gated on an explicit test-domain set; survival dimensions are refused.
 * </ul>
 */
@ParametersAreNonnullByDefault
public final class FiniteRuntime implements AutoCloseable {

    /** System provenance for finite transactions; journals keep the real UUID. */
    private static final UUID SYSTEM_ACTOR =
            UUID.nameUUIDFromBytes("blockreality/finite-system".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final String ORDER_KEY = "finite/order";

    /** Non-physical observation state for one adopted domain. */
    public enum Observation { ADOPTED, WAITING_CHUNKS, SOURCED }

    /** World observation entering the runtime; chunks the host could not read are named, never filled. */
    public record DomainObservation(Set<BlockKey> supports, int missingChunks) {
        public DomainObservation {
            supports = Set.copyOf(Objects.requireNonNull(supports));
            if (missingChunks < 0) throw new IllegalArgumentException("negative missing count");
        }
        public boolean complete() { return missingChunks == 0; }
        public static DomainObservation waiting(int missing) { return new DomainObservation(Set.of(), missing); }
        public static DomainObservation observed(Set<BlockKey> supports) {
            return new DomainObservation(supports, 0);
        }
    }

    /** The commitSource answer: either a durable journal decision or an honest waiting state. */
    public sealed interface CommitOutcome {
        record Decided(Receipt receipt) implements CommitOutcome { }
        record Waiting(String dimension, int missingChunks) implements CommitOutcome { }
    }

    private final FileTransactionJournal journal;
    private final FiniteStore store;
    private final AtomicConstructionCoordinator coordinator;
    private final ManufacturedRegistry metadata;
    private final UUID session;
    private final Runnable threadGuard;
    private final Runnable entryGuard;
    private final Set<String> admittedDomains;
    private final Consumer<String> log;

    private volatile FiniteRegistry registry;
    private volatile long order;
    private volatile long generation;
    private volatile String failure = "";
    private volatile boolean closed, ready;
    private volatile FiniteLaneWorker lane;

    /** Server-thread transaction context: armed while a finite host is inside the coordinator. */
    private boolean busy, armed;
    private final Map<String, Observation> observations = new HashMap<>();
    private final Map<String, FiniteLaneWorker.LaneEvent> laneStatus = new HashMap<>();

    private FiniteRuntime(FileTransactionJournal journal, FiniteStore store,
                          AtomicConstructionCoordinator coordinator, ManufacturedRegistry metadata,
                          UUID session, Runnable threadGuard,
                          Runnable entryGuard, Set<String> admittedDomains, Consumer<String> log) {
        this.journal = journal;
        this.store = store;
        this.coordinator = coordinator;
        this.metadata = metadata;
        this.session = session;
        this.threadGuard = threadGuard;
        this.entryGuard = entryGuard;
        this.admittedDomains = Set.copyOf(admittedDomains);
        this.log = log;
    }

    /**
     * Open the finite runtime beside the construction journal. The store takes its own
     * {@code finite/.lock}; the journal instance and world lock stay owned by the caller.
     * Fold-forward runs here; a pending finite record is decided by the construction
     * recovery path through {@link #recoveryHost}.
     */
    public static FiniteRuntime open(FileTransactionJournal journal, Path journalDirectory,
                                     AtomicConstructionCoordinator coordinator,
                                     ManufacturedRegistry metadata, UUID session,
                                     FiniteLaneWorker.LaneFactory laneFactory,
                                     Runnable threadGuard, Runnable entryGuard,
                                     Set<String> admittedDomains, Consumer<String> log) throws IOException {
        Objects.requireNonNull(journal);
        Objects.requireNonNull(journalDirectory);
        Objects.requireNonNull(coordinator);
        Objects.requireNonNull(metadata);
        FiniteStore store = FiniteStore.openAdjacentTo(journalDirectory);
        boolean ok = false;
        try {
            var runtime = new FiniteRuntime(journal, store, coordinator, metadata, session,
                    threadGuard, entryGuard, admittedDomains, log);
            runtime.registry = FiniteRegistry.load(journal, store);
            runtime.order = scanFiniteOrder(journal);
            runtime.lane = new FiniteLaneWorker("finite", laneFactory, new FiniteLaneWorker.Env() {
                @Override public FiniteRegistry registry() { return runtime.registry; }
                @Override public FiniteStore store() { return store; }
                @Override public long generation() { return runtime.generation; }
            }, log);
            for (FiniteDomain domain : runtime.registry.domains())
                runtime.observations.put(domain.id().dimension(),
                        runtime.registry.root(domain.id()).isPresent() ? Observation.SOURCED : Observation.ADOPTED);
            ok = true;
            return runtime;
        } finally {
            if (!ok) store.close();
        }
    }

    // ---------------------------------------------------------------- lifecycle/state

    /** Startup recovery finished; finite transactions may enter. */
    public void markReady() { ready = true; }

    public boolean ready() { return ready; }
    public boolean available() { return ready && !closed && failure.isEmpty(); }
    public @Nonnull String failure() { return failure; }
    public boolean closed() { return closed; }
    public long order() { return order; }
    public long sessionGeneration() { return generation; }
    public @Nonnull FiniteStore store() { return store; }
    public @Nonnull FiniteLaneWorker lane() { return lane; }

    FiniteRegistry registry() { return registry; }
    Runnable threadGuard() { return threadGuard; }
    boolean armed() { return armed; }

    private void fail(Throwable problem) {
        failure = "Finite runtime requires recovery; finite admission stopped";
        log.accept("finite runtime failed: " + problem);
    }

    /** Revoke all lane sessions (world unload, engine reset); durable state is untouched. */
    public synchronized void reset() {
        if (closed) return;
        generation++;
        laneStatus.clear();
        if (lane != null) lane.reset();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        ready = false;
        generation++;
        if (lane != null) lane.close();
        try { store.close(); } catch (IOException problem) { log.accept("finite store close failed: " + problem); }
    }

    // ---------------------------------------------------------------- recovery wiring

    /**
     * The pending-entry router used by the construction recovery path. Returns a finite
     * recovery host bound to the pending entry, or {@code null} when the entry is not a
     * finite transaction (it belongs to the construction host instead).
     */
    public @Nullable AtomicConstructionCoordinator.Host recoveryHost(Entry pending) throws IOException {
        if (pending == null || !FiniteJournalHost.isFinite(pending)) return null;
        armed = true;
        try {
            return new FiniteJournalHost(this, pending);
        } catch (IOException failure) {
            fail(failure);
            throw failure;
        }
    }

    /** Re-fold the committed finite authority after a journal recovery decision. */
    synchronized void republish() throws IOException {
        registry = FiniteRegistry.load(journal, store);
        order = scanFiniteOrder(journal);
        observations.clear();
        for (FiniteDomain domain : registry.domains())
            observations.put(domain.id().dimension(),
                    registry.root(domain.id()).isPresent() ? Observation.SOURCED : Observation.ADOPTED);
        armed = false;
    }

    /**
     * Resync the request clock after a committed fracture. The fracture driver's publish
     * folds {@code FiniteRegistry.applyCommitted} directly (doc §5.2) — it never passes
     * through {@link #committed}, so without this the next finite transaction's
     * {@code baseRevision} is stale and the coordinator's CAS rejects it as
     * PARTICIPANT_CONFLICT. Only advances: a replayed older entry cannot regress the clock.
     */
    void committedFracture(UUID requestId) throws IOException {
        Entry entry = journal.verify(requestId).orElseThrow(
                () -> new IOException("Committed finite record missing"));
        if (entry.phase() != Phase.COMMITTED) return;
        Long committed = orderOf(entry);
        if (committed != null && committed > order) order = committed;
    }

    /** Called by {@link FiniteJournalHost#publish} once the coordinator has decided COMMITTED. */
    void committed(Receipt receipt) throws IOException {
        try {
            registry.applyCommitted(journal, receipt.request().id());
        } catch (IOException | RuntimeException problem) {
            fail(problem);
            throw problem;
        }
        Long committed = orderOf(journal.verify(receipt.request().id()).orElseThrow(
                () -> new IOException("Committed finite record missing")));
        if (committed != null) order = committed;
    }

    // ---------------------------------------------------------------- finite transactions

    /**
     * One finite operation through the shared coordinator. The request's
     * {@code planHash} must bind the operation and {@code baseRevision} must equal the
     * current finite order; replay of an identical committed/terminal request returns its
     * stored receipt without re-deciding.
     */
    public Receipt submit(Request request, Function<Request, FiniteRegistry.Prepared> proposer)
            throws IOException {
        entryGuard.run();
        Objects.requireNonNull(request);
        Objects.requireNonNull(proposer);
        if (closed || !failure.isEmpty()) throw new IllegalStateException("Finite runtime is not available");
        Optional<Entry> previous;
        try { previous = journal.verify(request.id()); }
        catch (IOException problem) { fail(problem); throw problem; }
        if (previous.isPresent() && previous.get().request().equals(request)
                && previous.get().phase() != Phase.PREPARED) return previous.get().receipt();
        if (busy) throw new IllegalStateException("Reentrant finite transaction refused");
        busy = true;
        armed = true;
        try {
            var host = new FiniteJournalHost(this);
            return coordinator.execute(request, req -> {
                FiniteRegistry.Prepared prepared;
                try {
                    prepared = proposer.apply(req);
                } catch (RuntimeException invalid) {
                    throw new AtomicConstructionCoordinator.ValidationRefused();
                }
                host.bind(Entry.prepared(prepared.intent()));
                return prepared.intent();
            }, host);
        } catch (AtomicConstructionCoordinator.RecoveryRequired problem) {
            fail(problem);
            throw problem;
        } finally {
            busy = false;
            armed = false;
        }
    }

    private Request request(Operation op, UUID id) {
        return new Request(id, SYSTEM_ACTOR, session, journal.domain(), order, FiniteRegistry.planHash(op));
    }

    /**
     * Adopt one dimension as a finite test domain (DOMAIN_ADOPT): a CreateDomain finite
     * transaction mints the persisted BSI domain and artifact namespace. Adoption is
     * idempotent per dimension and refused outside the admitted test-domain set.
     */
    public @Nonnull FiniteDomainId adoptDomain(String dimension) throws IOException {
        Optional<FiniteDomain> existing = registry.domain(dimension);
        if (existing.isPresent()) return existing.get().id();
        if (!admittedDomains.contains(dimension))
            throw new IllegalStateException("Dimension is not an admitted finite test domain: " + dimension);
        var op = new Operation.CreateDomain(dimension);
        Receipt receipt = submit(request(op, UUID.randomUUID()),
                req -> registry.prepareCreateDomain(req, dimension));
        if (receipt.phase() != Phase.COMMITTED)
            throw new IOException("Finite domain adoption refused: " + receipt.reason());
        var adopted = registry.domain(dimension).orElseThrow(
                () -> new IOException("Committed finite domain missing after publish"));
        observations.put(dimension, Observation.ADOPTED);
        return adopted.id();
    }

    /**
     * BIND: mint monotonic positive owner ids for the supplied plans (manufactured piece
     * UUIDs via {@link #piecePlans}, or one-time legacy adoptions). The receipt carries the
     * request id; minted bindings are read back through {@link #bindingsBornBy}.
     */
    public Receipt bindArtifacts(FiniteDomainId domain, List<Operation.BindingPlan> plans)
            throws IOException {
        Objects.requireNonNull(domain);
        var op = new Operation.Bind(plans);
        Receipt receipt = submit(request(op, UUID.randomUUID()),
                req -> registry.prepareBind(req, domain, plans));
        if (receipt.phase() == Phase.COMMITTED)
            observations.put(domain.dimension(), Observation.ADOPTED);
        return receipt;
    }

    /** Resolve manufactured piece UUIDs into binding plans; unknown/foreign/retired pieces refuse. */
    public @Nonnull List<Operation.BindingPlan> piecePlans(FiniteDomainId domain, List<UUID> pieceIds) {
        Objects.requireNonNull(domain);
        var plans = new ArrayList<Operation.BindingPlan>();
        for (UUID id : Objects.requireNonNull(pieceIds)) {
            var piece = metadata.piece(id).orElseThrow(
                    () -> new IllegalArgumentException("Unknown manufactured piece " + id));
            if (!piece.dimension().equals(domain.dimension()) || piece.status() == ManufacturedPiece.Status.RETIRED)
                throw new IllegalArgumentException("Piece cannot enter the finite domain " + id);
            plans.add(Operation.BindingPlan.manufactured(id, piece.declaration(), piece.cells()));
        }
        return List.copyOf(plans);
    }

    /**
     * Enumerate binding plans for every unbound, non-retired manufactured piece committed in
     * this domain's dimension (finite bootstrap). Deterministic order: birth order, then id,
     * so owner minting is replay-stable. A piece without a declared wire axis can never enter
     * a finite domain — the whole enumeration refuses rather than silently skipping it.
     */
    public @Nonnull List<Operation.BindingPlan> unboundPlans(FiniteDomainId domain) {
        Objects.requireNonNull(domain);
        var bound = new HashSet<UUID>();
        for (ArtifactBinding binding : registry.activeBindings(domain.artifactNamespace()))
            if (binding.pieceId() != null) bound.add(binding.pieceId());
        var pieces = new TreeMap<UUID, ManufacturedPiece>();
        for (BlockKey cell : metadata.ownedCells(domain.dimension())) {
            UUID owner = metadata.owner(domain.dimension(), cell).orElseThrow(
                    () -> new IllegalStateException("Manufactured coverage names no owner " + cell));
            var piece = metadata.piece(owner).orElseThrow(
                    () -> new IllegalStateException("Manufactured owner is not a piece " + owner));
            if (!piece.dimension().equals(domain.dimension())) continue;
            pieces.putIfAbsent(owner, piece);
        }
        var sorted = new ArrayList<>(pieces.values());
        sorted.sort(Comparator.comparingLong(ManufacturedPiece::birthOrder)
                .thenComparing(piece -> piece.id().toString()));
        var plans = new ArrayList<Operation.BindingPlan>();
        for (ManufacturedPiece piece : sorted) {
            if (bound.contains(piece.id()) || piece.status() == ManufacturedPiece.Status.RETIRED) continue;
            if (piece.declaration().axis() < 0)
                throw new IllegalArgumentException("Piece has no declared wire axis " + piece.id());
            plans.add(Operation.BindingPlan.manufactured(piece.id(), piece.declaration(), piece.cells()));
        }
        return List.copyOf(plans);
    }

    /**
     * Mark a domain waiting on world observation — the same record {@link #commitSource}
     * writes for an incomplete observation, without requiring a source proposal.
     */
    public void markWaiting(FiniteDomainId domain, int missingChunks) {
        Objects.requireNonNull(domain);
        if (missingChunks < 1) throw new IllegalArgumentException("waiting needs a positive count");
        observations.put(domain.dimension(), Observation.WAITING_CHUNKS);
    }

    /** RETIRE: permanently drop the named owner ids; minted ids are never reused. */
    public Receipt retireArtifacts(FiniteDomainId domain, Set<Long> ownerIds) throws IOException {
        Objects.requireNonNull(domain);
        var op = new Operation.Retire(ownerIds);
        return submit(request(op, UUID.randomUUID()), req -> registry.prepareRetire(req, domain, ownerIds));
    }

    /** OBSERVE: bump the non-physical observation epoch after a chunk rescan. */
    public Receipt observeDomain(FiniteDomainId domain) throws IOException {
        Objects.requireNonNull(domain);
        var op = new Operation.Observe();
        return submit(request(op, UUID.randomUUID()), req -> registry.prepareObserve(req, domain));
    }

    /**
     * Commit a new finite source at the next world revision (CommitRoot). The object is
     * published to the store <em>before</em> the journal may name it; on COMMITTED the
     * HEAD cache is refreshed and the lane is posted a sync — the caller never waits on it.
     *
     * <p>An incomplete observation produces {@link CommitOutcome.Waiting} — the domain is
     * marked {@link Observation#WAITING_CHUNKS}, no source is built, nothing is journaled.
     */
    public CommitOutcome commitSource(FiniteDomainId domain, FiniteDomainSource.Spec spec,
                                      DomainObservation observation) throws IOException {
        Objects.requireNonNull(domain);
        Objects.requireNonNull(spec);
        Objects.requireNonNull(observation);
        if (!observation.complete()) {
            observations.put(domain.dimension(), Observation.WAITING_CHUNKS);
            return new CommitOutcome.Waiting(domain.dimension(), observation.missingChunks());
        }
        FiniteRoot committed = registry.root(domain).orElse(null);
        long nextRevision = committed == null ? 0 : committed.worldRevision() + 1;
        long nextSequence = committed == null ? 0 : committed.historySequence() + 1;
        FiniteSource source = FiniteDomainSource.build(domain,
                registry.activeBindings(domain.artifactNamespace()), observation.supports(), spec, nextRevision);
        UUID requestId = UUID.randomUUID();
        FiniteBlobRef sourceRef = store.publish(requestId, FiniteCodec.KIND_SOURCE, source.serialize());
        var next = new FiniteRoot.Next(nextRevision, nextSequence, sourceRef, null);
        var op = new Operation.CommitRoot(next);
        Receipt receipt = submit(request(op, requestId),
                req -> registry.prepareCommitRoot(req, domain, next));
        if (receipt.phase() == Phase.COMMITTED) {
            writeHead(domain, requestId);
            postSync(domain, source);
            observations.put(domain.dimension(), Observation.SOURCED);
        }
        return new CommitOutcome.Decided(receipt);
    }

    /**
     * W3 history-only commit (design §7): same worldRevision, historySequence+1, the
     * committed source and a mandatory checkpoint slot minted earlier by the lane.
     */
    public Receipt commitHistory(FiniteDomainId domain, FiniteRoot.Checkpoint checkpoint)
            throws IOException {
        Objects.requireNonNull(domain);
        Objects.requireNonNull(checkpoint);
        FiniteRoot committed = registry.root(domain).orElseThrow(
                () -> new IllegalStateException("Finite domain has no committed root"));
        var next = new FiniteRoot.Next(committed.worldRevision(), committed.historySequence() + 1,
                committed.source(), checkpoint);
        var op = new Operation.CommitHistory(next);
        Receipt receipt = submit(request(op, UUID.randomUUID()),
                req -> registry.prepareCommitHistory(req, domain, next));
        if (receipt.phase() == Phase.COMMITTED) {
            writeHead(domain, receipt.request().id());
            postSync(domain, null);
        }
        return receipt;
    }

    // ---------------------------------------------------------------- lane + publication

    /** Post an authority sync; {@code snapshot} may be null to derive committed state on the lane. */
    private void postSync(FiniteDomainId domain, FiniteSource snapshot) {
        try {
            FiniteRoot root = registry.root(domain).orElse(null);
            if (root == null) return;
            FiniteBasis basis = registry.basis(domain, generation);
            FiniteCheckpointRef checkpoint = checkpointRef(root, basis);
            lane.sync(new FiniteLaneWorker.Sync(domain, snapshot, basis, root, checkpoint, generation));
        } catch (IOException problem) {
            fail(problem);
        }
    }

    private static FiniteCheckpointRef checkpointRef(FiniteRoot root, FiniteBasis basis) {
        FiniteRoot.Checkpoint slot = root.checkpoint();
        if (slot == null) return null;
        return new FiniteCheckpointRef(slot.engineBuild(), slot.contractSha256(), slot.format(),
                basis, slot.blob(), slot.byteBudget(), slot.allocationBudget(), slot.elementBudget());
    }

    /** Refresh the on-disk HEAD cache from the committed root (a cache — the journal is authority). */
    private void writeHead(FiniteDomainId domain, UUID requestId) throws IOException {
        Entry entry = journal.verify(requestId).orElseThrow(
                () -> new IOException("Committed finite record missing"));
        String rootHash = null;
        for (Change change : entry.intent().changes())
            if (change.resource().startsWith("finite/root/")) rootHash = sha256(change.after().bytes());
        if (rootHash == null) throw new IOException("Committed finite root image missing");
        try {
            store.writeHead(registry.basis(domain, generation), rootHash);
        } catch (IOException problem) {
            fail(problem);
            throw problem;
        }
    }

    /** Drain bounded lane events — cheap, nonblocking; call from the server tick. */
    public int drainLaneEvents(int max) {
        return lane.drainEvents(max, event -> laneStatus.put(event.domain().dimension(), event));
    }

    /** Re-establish lane authority for every domain holding a committed root. */
    public void syncAll() {
        for (FiniteDomain domain : registry.domains()) {
            try {
                if (registry.root(domain.id()).isPresent())
                    lane.sync(new FiniteLaneWorker.Sync(domain.id(), null, null, null, null, generation));
            } catch (RuntimeException failure) {
                fail(failure);
            }
        }
    }

    // ---------------------------------------------------------------- queries

    public @Nonnull Optional<FiniteDomain> domain(String dimension) { return registry.domain(dimension); }
    public @Nonnull Optional<ArtifactBinding> binding(UUID artifactNamespace, long ownerId) {
        return registry.binding(artifactNamespace, ownerId);
    }
    public @Nonnull List<ArtifactBinding> activeBindings(FiniteDomainId domain) {
        return registry.activeBindings(domain.artifactNamespace());
    }
    /** The bindings this transaction minted — the request id is the birth transaction. */
    public @Nonnull List<ArtifactBinding> bindingsBornBy(FiniteDomainId domain, UUID transaction) {
        return registry.activeBindings(domain.artifactNamespace()).stream()
                .filter(binding -> binding.birthTransaction().equals(transaction)).toList();
    }
    public @Nonnull Optional<FiniteRoot> root(FiniteDomainId domain) { return registry.root(domain); }
    /** The committed basis stamped with the live session generation — for future host revalidation. */
    public @Nonnull Optional<FiniteBasis> basis(String dimension) throws IOException {
        var domain = registry.domain(dimension);
        if (domain.isEmpty() || registry.root(domain.get().id()).isEmpty()) return Optional.empty();
        return Optional.of(registry.basis(domain.get().id(), generation));
    }
    public @Nonnull Optional<FiniteSource> source(FiniteDomainId domain) throws IOException {
        if (registry.root(domain).isEmpty()) return Optional.empty();
        return Optional.of(registry.source(domain));
    }
    public @Nonnull Observation observation(String dimension) {
        return observations.getOrDefault(dimension, Observation.WAITING_CHUNKS);
    }
    public @Nullable FiniteLaneWorker.LaneEvent laneStatus(String dimension) {
        return laneStatus.get(dimension);
    }

    // ---------------------------------------------------------------- internals

    private static long scanFiniteOrder(FileTransactionJournal journal) throws IOException {
        long order = 0;
        for (UUID id : journal.entryIds()) {
            Entry entry = journal.read(id).orElseThrow(() -> new IOException("Journal record disappeared"));
            if (entry.phase() != Phase.COMMITTED) continue;
            Long found = orderOf(entry);
            if (found != null) order = Math.max(order, found);
        }
        return order;
    }

    /** The {@code finite/order} after-image of one entry, or null when it is not a finite tx. */
    private static Long orderOf(Entry entry) {
        if (entry.intent() == null) return null;
        for (Change change : entry.intent().changes())
            if (ORDER_KEY.equals(change.resource())) return numberValue(change.after());
        return null;
    }

    private static long numberValue(ConstructionTransaction.Value value) {
        if (value == null || !value.present() || value.bytes().length != 8)
            throw new IllegalArgumentException("Invalid finite order image");
        var buffer = java.nio.ByteBuffer.wrap(value.bytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        long n = buffer.getLong();
        if (n < 0) throw new IllegalArgumentException("Invalid finite order image");
        return n;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
