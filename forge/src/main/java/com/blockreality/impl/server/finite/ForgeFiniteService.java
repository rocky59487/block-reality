package com.blockreality.impl.server.finite;

import com.blockreality.core.diagnostics.PipelineProfile;
import com.blockreality.core.engine.GameNativeLoader;
import com.blockreality.core.engine.InProcessEngine;
import com.blockreality.core.finite.FiniteDomainId;
import com.blockreality.core.finite.FiniteLane;
import com.blockreality.core.transaction.ConstructionTransaction.Receipt;
import com.blockreality.impl.BRConfig;
import com.blockreality.impl.BlockRealityMod;
import com.blockreality.impl.server.construction.ConstructionService;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The Forge-facing finite service (design §4): owns the {@link FiniteRuntime} lifecycle on
 * the same server timing as {@link ConstructionService} — the runtime opens on
 * ServerAboutToStart (one priority step after construction owns the journal), becomes
 * transaction-ready on ServerStarting after the shared recovery pass, and is revoked and
 * closed on ServerStopped. Its finite lane factory loads the real in-process engine only
 * on the worker thread; a missing library leaves the service explicitly unavailable.
 */
public final class ForgeFiniteService {

    private static final Map<MinecraftServer, ForgeFiniteService> SERVERS = new ConcurrentHashMap<>();

    private final MinecraftServer server;
    private final UUID session = UUID.randomUUID();
    private volatile FiniteRuntime runtime;
    private volatile String failure = "";

    private ForgeFiniteService(MinecraftServer server) { this.server = server; }

    // ---------------------------------------------------------------- Forge lifecycle

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void aboutToStart(ServerAboutToStartEvent event) {
        ForgeFiniteService service = new ForgeFiniteService(event.getServer());
        if (SERVERS.putIfAbsent(event.getServer(), service) != null)
            throw new IllegalStateException("Finite service already owns a runtime");
        service.openRuntime();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void starting(ServerStartingEvent event) {
        ForgeFiniteService service = SERVERS.get(event.getServer());
        if (service == null) return;
        ConstructionService construction = ConstructionService.of(event.getServer());
        FiniteRuntime runtime = service.runtime;
        if (construction != null && construction.availableInstance()
                && runtime != null && runtime.failure().isEmpty()) {
            runtime.markReady();
            runtime.syncAll();
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        ForgeFiniteService service = SERVERS.remove(event.getServer());
        if (service != null) service.closeRuntime();
    }

    /** Cheap per-tick drain of completed lane events; never blocks and never touches native. */
    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ForgeFiniteService service = SERVERS.get(event.getServer());
        FiniteRuntime runtime = service == null ? null : service.runtime;
        if (runtime != null && runtime.available()) runtime.drainLaneEvents(64);
    }

    // ---------------------------------------------------------------- runtime wiring

    private void openRuntime() {
        ConstructionService construction = ConstructionService.of(server);
        if (construction == null || construction.journal() == null || construction.journalDirectory() == null
                || construction.coordinator() == null || construction.metadata() == null) {
            failure = "Construction journal is unavailable; finite runtime not opened";
            BlockRealityMod.LOG.error(failure);
            return;
        }
        // Recovery routing must exist before ServerStarting even if the store fails to open:
        // a pending finite record then fails construction closed instead of being misread.
        construction.setPendingRouter(pending -> {
            FiniteRuntime current = runtime;
            if (current == null) {
                if (FiniteJournalHost.isFinite(pending))
                    throw new IOException("Pending finite transaction with no finite runtime");
                return null;
            }
            return current.recoveryHost(pending);
        });
        try {
            runtime = FiniteRuntime.open(construction.journal(), construction.journalDirectory(),
                    construction.coordinator(), construction.metadata(), session,
                    laneFactory(log()), this::checkThread, this::entryGuard,
                    admittedDimensions(), log());
        } catch (Exception problem) {
            failure = "Finite runtime could not open; finite admission stopped";
            BlockRealityMod.LOG.error(failure, problem);
        }
    }

    private void closeRuntime() {
        FiniteRuntime current = runtime;
        runtime = null;
        if (current != null) current.close();
        if (failure.isEmpty()) failure = "Finite service closed";
    }

    private static FiniteLaneWorker.LaneFactory laneFactory(Consumer<String> log) {
        return () -> {
            InProcessEngine engine = GameNativeLoader.openEngine(BRConfig.INSTANCE.enginePath.get(),
                    FMLPaths.GAMEDIR.get().resolve("blockreality/engine"),
                    BRConfig.INSTANCE.numThreads.get(), log, PipelineProfile.disabled());
            return new FiniteLaneWorker.Lane(FiniteLane.inProcess(engine), engine);
        };
    }

    private static java.util.Set<String> admittedDimensions() {
        var admitted = new HashSet<String>();
        for (String dimension : BRConfig.INSTANCE.finiteTestDimensions.get()) admitted.add(dimension);
        return java.util.Set.copyOf(admitted);
    }

    private void checkThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Finite service requires the server thread");
    }

    /** Admission for finite entry points: server thread plus both services available. */
    private void entryGuard() {
        checkThread();
        ConstructionService construction = ConstructionService.of(server);
        FiniteRuntime current = runtime;
        if (construction == null || !construction.availableInstance()
                || current == null || !current.available())
            throw new IllegalStateException("Finite service is not available");
    }

    private Consumer<String> log() {
        return message -> BlockRealityMod.LOG.info("[finite] {}", message);
    }

    // ---------------------------------------------------------------- public API

    public static @Nullable ForgeFiniteService of(MinecraftServer server) { return SERVERS.get(server); }

    public static @Nullable FiniteRuntime runtime(MinecraftServer server) {
        ForgeFiniteService service = SERVERS.get(server);
        return service == null ? null : service.runtime;
    }

    public static boolean available(MinecraftServer server) {
        FiniteRuntime runtime = runtime(server);
        return runtime != null && runtime.available();
    }

    public static @Nonnull String failure(MinecraftServer server) {
        ForgeFiniteService service = SERVERS.get(server);
        if (service == null) return "Finite service has not started";
        FiniteRuntime runtime = service.runtime;
        if (!service.failure.isEmpty()) return service.failure;
        return runtime == null ? "Finite runtime not opened" : runtime.failure();
    }

    /**
     * The explicit test-domain adoption entry point (design §5): {@code DOMAIN_ADOPT} as a
     * regular construction transaction. Refuses dimensions outside the configured
     * test-domain set and every non-server-thread caller.
     */
    public static @Nonnull FiniteDomainId adoptDomain(ServerLevel level) throws IOException {
        FiniteRuntime runtime = requireRuntime(level.getServer());
        return runtime.adoptDomain(level.dimension().location().toString());
    }

    /** Mint finite owner ids for manufactured pieces inside an adopted test domain. */
    public static @Nonnull Receipt bindArtifacts(ServerLevel level, FiniteDomainId domain,
                                                 List<UUID> pieceIds) throws IOException {
        FiniteRuntime runtime = requireRuntime(level.getServer());
        return runtime.bindArtifacts(domain, runtime.piecePlans(domain, pieceIds));
    }

    private static FiniteRuntime requireRuntime(MinecraftServer server) {
        ForgeFiniteService service = SERVERS.get(server);
        FiniteRuntime runtime = service == null ? null : service.runtime;
        if (service == null || runtime == null) throw new IllegalStateException("Finite service is not available");
        service.entryGuard();
        return runtime;
    }

    /** Latest committed basis for a dimension — exposed for future host revalidation. */
    public static Optional<com.blockreality.core.finite.FiniteBasis> basis(ServerLevel level)
            throws IOException {
        FiniteRuntime runtime = requireRuntime(level.getServer());
        return runtime.basis(level.dimension().location().toString());
    }
}
