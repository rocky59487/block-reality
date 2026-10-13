package com.blockreality.impl.server.finite;

import com.blockreality.core.bsi.BsiCorotFracture;
import com.blockreality.core.bsi.BsiContract;
import com.blockreality.core.diagnostics.PipelineProfile;
import com.blockreality.core.engine.GameNativeLoader;
import com.blockreality.core.engine.InProcessEngine;
import com.blockreality.core.finite.FiniteDomain;
import com.blockreality.core.finite.FiniteDomainId;
import com.blockreality.core.finite.FiniteFractureDriver;
import com.blockreality.core.finite.FiniteLane;
import com.blockreality.core.finite.FiniteRegistry;
import com.blockreality.core.finite.FiniteStore;
import com.blockreality.core.transaction.AtomicConstructionCoordinator;
import com.blockreality.core.transaction.ConstructionTransaction.Entry;
import com.blockreality.core.transaction.ConstructionTransaction.Phase;
import com.blockreality.core.transaction.ConstructionTransaction.Receipt;
import com.blockreality.core.transaction.ConstructionTransaction.Request;
import com.blockreality.core.transaction.FileTransactionJournal;
import com.blockreality.impl.BRConfig;
import com.blockreality.impl.BlockRealityMod;
import com.blockreality.impl.server.construction.ConstructionService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * The W4-B finite-fracture Forge service (FINITE_FRACTURE_TRANSACTION §9): the world
 * side of {@link FiniteFractureDriver} — recovery routing for mixed fracture intents,
 * startup fold-forward + world republish, and the explicit test-mode fracture entry.
 * It owns no journal, store or lane of its own: the journal is the construction
 * journal, the store/registry/generation are borrowed from the {@link FiniteRuntime}
 * {@code ForgeFiniteService} opened one priority step earlier, and every native call
 * is confined to the private lane the host opens per transaction (doc §9.5).
 *
 * <p>Lifecycle, deliberately staggered behind construction + finite:
 *
 * <ul>
 *   <li>{@code aboutToStart} NORMAL — after {@code ForgeFiniteService} (HIGH): installs
 *       the fracture-aware pending router (doc §9.6) before construction recovery runs.</li>
 *   <li>{@code starting} NORMAL — after both services' recovery: binds the driver to the
 *       post-recovery registry, runs {@code driver.recover} (fold-forward + republish).</li>
 *   <li>{@code stopped} — revokes and closes the driver.</li>
 * </ul>
 *
 * The Minecraft-free kernel {@link Core} carries every decision; tests construct it
 * directly with a recording world and a scripted lane — the Forge shell here is file-
 * asserted the same way {@code ForgeFiniteService}'s is.
 */
public final class FiniteFractureService {

    private static final Map<MinecraftServer, FiniteFractureService> SERVERS = new ConcurrentHashMap<>();

    /** The synthetic actor a server-initiated test fracture is stamped with (doc §9.7). */
    static final UUID FRACTURE_ACTOR =
            UUID.nameUUIDFromBytes("blockreality/fracture-system".getBytes(StandardCharsets.UTF_8));
    static final int FRACTURE_FORMAT = 1;
    // Checkpoint budgets are recorded durably in each committed slot and replayed verbatim
    // on import — allocation is BYTES (checkpoint_codec allocate()/n*width), so a real
    // corot checkpoint needs MiB-scale headroom, not a few thousand bytes.
    static final long FRACTURE_BYTE_BUDGET = 1L << 24,
            FRACTURE_ALLOCATION_BUDGET = 1L << 24, FRACTURE_ELEMENT_BUDGET = 1L << 20;

    private final MinecraftServer server;
    private final UUID session = UUID.randomUUID();
    private volatile Core core;
    private volatile String failure = "";

    private FiniteFractureService(MinecraftServer server) { this.server = server; }

    // ---------------------------------------------------------------- Forge lifecycle

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void aboutToStart(ServerAboutToStartEvent event) {
        FiniteFractureService service = new FiniteFractureService(event.getServer());
        if (SERVERS.putIfAbsent(event.getServer(), service) != null)
            throw new IllegalStateException("Fracture service already owns a core");
        service.openCore();
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void starting(ServerStartingEvent event) {
        FiniteFractureService service = SERVERS.get(event.getServer());
        if (service == null || service.core == null) return;
        try {
            service.core.recover();
        } catch (Exception problem) {
            service.failure = "Finite fracture recovery failed";
            BlockRealityMod.LOG.error(service.failure, problem);
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        FiniteFractureService service = SERVERS.remove(event.getServer());
        if (service != null) service.closeCore();
    }

    // ---------------------------------------------------------------- core wiring

    private void openCore() {
        ConstructionService construction = ConstructionService.of(server);
        FiniteRuntime runtime = ForgeFiniteService.runtime(server);
        if (construction == null || construction.journal() == null) {
            failure = "Construction journal is unavailable; fracture service not opened";
            BlockRealityMod.LOG.error(failure);
            return;
        }
        // The fracture-aware router must be installed before ServerStarting recovery:
        // a pending mixed fracture must never reach the pure-finite host (doc §9.6).
        construction.setPendingRouter(pending -> {
            if (pending == null) return null;
            if (FiniteJournalHost.isFracture(pending)) {
                Core current = core;
                if (current == null)
                    throw new IOException("Pending fracture transaction with no fracture service");
                return current.recoveryHost(pending);
            }
            FiniteRuntime current = runtime == null ? ForgeFiniteService.runtime(server) : runtime;
            if (current == null) {
                if (FiniteJournalHost.isFinite(pending))
                    throw new IOException("Pending finite transaction with no finite runtime");
                return null;
            }
            return current.recoveryHost(pending);
        });
        if (runtime == null) {
            failure = "Finite runtime is unavailable; fracture service not opened";
            BlockRealityMod.LOG.error(failure);
            return;
        }
        core = new Core(runtime, construction.journal(), runtime.store(), this::levelWorld,
                laneSource(log()), this::checkThread, this::entryGuard,
                admittedDimensions(), log(), session);
    }

    private void closeCore() {
        Core current = core;
        core = null;
        if (current != null) current.close();
        if (failure.isEmpty()) failure = "Fracture service closed";
    }

    private ForgeFiniteFractureHost.World levelWorld(String dimension) throws IOException {
        ResourceLocation location = ResourceLocation.tryParse(dimension);
        if (location == null) throw new IOException("Fracture dimension is malformed " + dimension);
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
        if (level == null) throw new IOException("Fracture dimension is not loaded " + dimension);
        return new LevelFractureWorld(level);
    }

    private static ForgeFiniteFractureHost.LaneSource laneSource(Consumer<String> log) {
        return () -> {
            // Vocabulary stays undeclared at open: the finite session replays the durable
            // bsi.vocab.declare itself and requires the reply to reproduce the stored mapping.
            InProcessEngine engine = GameNativeLoader.openEngine(BRConfig.INSTANCE.enginePath.get(),
                    FMLPaths.GAMEDIR.get().resolve("blockreality/engine"),
                    BRConfig.INSTANCE.numThreads.get(), log, PipelineProfile.disabled(), false);
            return new ForgeFiniteFractureHost.OpenedLane(FiniteLane.inProcess(engine), engine,
                    engine.engineName() + "/" + engine.engineVersion(), BsiContract.sha256());
        };
    }

    private static Set<String> admittedDimensions() {
        var admitted = new HashSet<String>();
        for (String dimension : BRConfig.INSTANCE.finiteTestDimensions.get()) admitted.add(dimension);
        return Set.copyOf(admitted);
    }

    private void checkThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Fracture service requires the server thread");
    }

    /** Admission for fracture entry points: server thread plus construction + finite available. */
    private void entryGuard() {
        checkThread();
        ConstructionService construction = ConstructionService.of(server);
        FiniteRuntime runtime = ForgeFiniteService.runtime(server);
        if (construction == null || !construction.availableInstance()
                || runtime == null || !runtime.available())
            throw new IllegalStateException("Fracture service is not available");
    }

    private Consumer<String> log() {
        return message -> BlockRealityMod.LOG.info("[fracture] {}", message);
    }

    // ---------------------------------------------------------------- public API

    public static @Nullable FiniteFractureService of(MinecraftServer server) { return SERVERS.get(server); }

    public static @Nonnull String failure(MinecraftServer server) {
        FiniteFractureService service = SERVERS.get(server);
        if (service == null) return "Fracture service has not started";
        Core current = service.core;
        if (!service.failure.isEmpty()) return service.failure;
        return current == null ? "Fracture service not opened" : current.failure();
    }

    /**
     * The explicit test-mode fracture entry (doc §9.7): one durable fracture
     * transaction on the shared journal for an adopted finite domain. There is no
     * default trigger — nothing calls this except the explicit test/debug path.
     *
     * @param requestId caller-controlled request id; the same id replays the durable
     *                  decision without a second world mutation
     */
    public static @Nonnull Receipt fracture(ServerLevel level, FiniteDomainId domain,
                                            List<BsiCorotFracture.Rule> rules, int budget,
                                            UUID requestId) throws IOException {
        Objects.requireNonNull(level); Objects.requireNonNull(domain);
        FiniteFractureService service = requireService(level.getServer());
        return service.core.fracture(domain, rules, budget, requestId);
    }

    private static FiniteFractureService requireService(MinecraftServer server) {
        FiniteFractureService service = SERVERS.get(server);
        if (service == null || service.core == null)
            throw new IllegalStateException("Fracture service is not available");
        service.entryGuard();
        return service;
    }

    // ---------------------------------------------------------------- MC-free kernel

    /**
     * Every fracture decision with no Minecraft types: the pending router, the
     * startup recovery pass and the {@link #fracture} transaction entry. Tests wire
     * it to a recording world and a scripted lane exactly like
     * {@code ForgeFiniteRuntimeTest} wires the runtime.
     */
    static final class Core implements AutoCloseable {

        private final FiniteRuntime runtime;
        private final FileTransactionJournal journal;
        private final FiniteStore store;
        private final ForgeFiniteFractureHost.Worlds worlds;
        private final ForgeFiniteFractureHost.LaneSource lanes;
        private final Runnable threadGuard;
        private final Runnable entryGuard;
        private final Set<String> admittedDimensions;
        private final Consumer<String> log;
        private final UUID session;
        private volatile FiniteFractureDriver driver;
        private volatile boolean armed;
        private volatile String failure = "";
        private volatile boolean closed;

        Core(FiniteRuntime runtime, FileTransactionJournal journal, FiniteStore store,
             ForgeFiniteFractureHost.Worlds worlds,
             ForgeFiniteFractureHost.LaneSource lanes,
             Runnable threadGuard, Runnable entryGuard,
             Set<String> admittedDimensions, Consumer<String> log, UUID session) {
            this.runtime = Objects.requireNonNull(runtime);
            this.journal = Objects.requireNonNull(journal);
            this.store = Objects.requireNonNull(store);
            this.worlds = Objects.requireNonNull(worlds);
            this.lanes = Objects.requireNonNull(lanes);
            this.threadGuard = Objects.requireNonNull(threadGuard);
            this.entryGuard = Objects.requireNonNull(entryGuard);
            this.admittedDimensions = Set.copyOf(Objects.requireNonNull(admittedDimensions));
            this.log = Objects.requireNonNull(log);
            this.session = Objects.requireNonNull(session);
        }

        /** Host-side access guard: server thread, armed, no failure, not closed (doc §9.6). */
        Runnable accessGuard() {
            return () -> {
                threadGuard.run();
                if (closed || !armed || !failure.isEmpty())
                    throw new IllegalStateException("Fracture service is not armed");
            };
        }

        private FiniteFractureDriver driver() {
            FiniteFractureDriver current = driver;
            if (current == null) {
                current = new FiniteFractureDriver(journal, store, runtime.registry());
                driver = current;
            }
            return current;
        }

        /**
         * The pending-router answer for a fracture intent (doc §9.6): a fresh host
         * bound to the durable entry inside the driver's {@code Publication} — the
         * identical fold + delegation {@code driver.recover} uses.
         */
        AtomicConstructionCoordinator.Host recoveryHost(Entry pending) throws IOException {
            if (pending == null || !FiniteJournalHost.isFracture(pending)) return null;
            threadGuard.run();
            if (closed || !failure.isEmpty())
                throw new IOException("Pending fracture transaction with no fracture service");
            armed = true;
            var host = new ForgeFiniteFractureHost(runtime, journal, worlds, lanes,
                    accessGuard(), log);
            host.bindRecovery(pending);
            return driver().recoveryHost(host);
        }

        /**
         * Startup recovery (doc §9.3): normally construction recovery already rolled a
         * dangling fracture PREPARED back through the routed host, so the driver's own
         * coordinator sees no pending and this fold-forwards committed entries plus
         * republishes world state — both idempotent. If a fracture PREPARED is still
         * dangling (construction recovery never ran), the host binds the durable intent
         * first so the driver's rollback can restore its before-images (doc §9.6).
         */
        void recover() throws IOException {
            armed = true;
            var host = new ForgeFiniteFractureHost(runtime, journal, worlds, lanes,
                    accessGuard(), log);
            java.util.Optional<UUID> pendingId = journal.pending();
            if (pendingId.isPresent()) {
                Entry pending = journal.verify(pendingId.get())
                        .orElseThrow(() -> new IOException("Prepared fracture intent disappeared"));
                if (pending.phase() == Phase.PREPARED && FiniteJournalHost.isFracture(pending))
                    host.bindRecovery(pending);
            }
            driver().recover(host);
        }

        /** The admission gate: dimension must be configured and the domain adopted. */
        void checkAdmission(FiniteDomainId domain) throws IOException {
            entryGuard.run();
            if (closed || !failure.isEmpty())
                throw new IOException("Fracture service is not available");
            if (!admittedDimensions.contains(domain.dimension()))
                throw new IOException("Fracture is not admitted in dimension " + domain.dimension());
            if (runtime.registry().domain(domain.dimension()).isEmpty()
                    || !runtime.registry().domain(domain.dimension()).get().id().equals(domain))
                throw new IOException("Fracture domain is not adopted " + domain.dimension());
            if (runtime.registry().root(domain).isEmpty())
                throw new IOException("Fracture domain has no committed root");
        }

        /**
         * One durable fracture transaction on the shared journal — the named §5.2
         * synchronous path: the server thread runs {@code driver.execute} while every
         * native call executes on the host's private lane thread (doc §9.5).
         */
        Receipt fracture(FiniteDomainId domain, List<BsiCorotFracture.Rule> rules, int budget,
                         UUID requestId) throws IOException {
            Objects.requireNonNull(domain); Objects.requireNonNull(rules); Objects.requireNonNull(requestId);
            checkAdmission(domain);
            armed = true;
            var host = new ForgeFiniteFractureHost(runtime, journal, worlds, lanes,
                    accessGuard(), log);
            try {
                host.bindDomain(domain);
                var committed = runtime.registry().root(domain).orElseThrow();
                var source = runtime.registry().source(domain);
                // Replay by key: a durable entry under this request id is the identity —
                // rebuilding the request from the moved-on basis would forge a foreign
                // request and collide instead of replaying (doc §9.7). The stored
                // request is re-presented exactly; a durable intent must still name
                // this domain's root before its decision is returned.
                var existing = journal.read(requestId);
                Request request;
                if (existing.isPresent()) {
                    Entry stored = existing.get();
                    request = stored.request();
                    if (!request.actor().equals(FRACTURE_ACTOR)
                            || !request.domain().equals(journal.domain()))
                        throw new IOException("Fracture request id is bound to a foreign transaction");
                    if (stored.intent() != null) {
                        boolean namesDomain = false;
                        for (var change : stored.intent().changes())
                            if (change.resource().equals(FiniteRegistry.rootKey(domain.bsiDomain())))
                                namesDomain = true;
                        if (!namesDomain)
                            throw new IOException("Fracture request id is bound to another domain");
                    }
                } else {
                    request = new Request(requestId, FRACTURE_ACTOR, session, journal.domain(),
                            committed.worldRevision(),
                            FiniteFractureDriver.intentHash(domain, committed, source, requestId,
                                    FRACTURE_ACTOR, "server/fracture", FRACTURE_FORMAT,
                                    FRACTURE_BYTE_BUDGET, FRACTURE_ALLOCATION_BUDGET,
                                    FRACTURE_ELEMENT_BUDGET, rules, budget));
                }
                var outcome = driver().execute(request, domain, rules, budget, FRACTURE_FORMAT,
                        FRACTURE_BYTE_BUDGET, FRACTURE_ALLOCATION_BUDGET, FRACTURE_ELEMENT_BUDGET,
                        "server/fracture", host);
                if (outcome.session() != null) outcome.session().close();
                if (outcome.receipt().phase() == Phase.REJECTED)
                    log.accept("fracture rejected " + outcome.receipt().reason()
                            + " — " + driver().lastRejection());
                // Re-establish the authority session asynchronously — the server thread
                // never joins the posted sync (doc §9.5).
                runtime.syncAll();
                return outcome.receipt();
            } finally {
                host.closeLane();
            }
        }

        @Nonnull String failure() { return failure; }

        void fail(String detail) { failure = detail == null ? "fracture service failed" : detail; }

        @Override public void close() {
            closed = true;
            FiniteFractureDriver current = driver;
            driver = null;
            if (current != null) current.close();
        }
    }
}
