package com.blockreality.impl.server;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.RevisionGate;
import com.blockreality.core.protocol.SolveRequest;
import com.blockreality.core.sidecar.SidecarClient;
import com.blockreality.core.sidecar.SidecarConfig;
import com.blockreality.impl.BlockRealityMod;
import com.blockreality.impl.block.StructuralBlock;
import com.blockreality.impl.net.BRNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-dimension structural state, and the three-stage analysis loop.
 *
 * <pre>
 *   main thread          background            main thread
 *   snapshot (immutable) -> solve            -> apply
 * </pre>
 *
 * <p>The snapshot is immutable, so the player may keep building while the solve runs. The
 * edit is not a race — it just makes the in-flight result stale, and {@link RevisionGate}
 * throws it away when it lands.
 *
 * <p><strong>State is per dimension, never static-global.</strong> The previous codebase
 * kept one map for every level, which meant the Nether and the Overworld shared an index
 * and nothing could be unit-tested. Keying on {@link ResourceKey} costs nothing and makes
 * the bug unrepresentable.
 */
public final class StructureManager {

    /** Main-thread share of a tick. Background solving does not come out of this. */
    private static final long TICK_BUDGET_NS = 8_000_000L;

    /** Don't re-solve more often than this; a player placing a row of blocks is one edit. */
    private static final int MIN_TICKS_BETWEEN_SOLVES = 10;

    private static final Map<ResourceKey<Level>, StructureManager> BY_DIMENSION = new ConcurrentHashMap<>();

    private final ResourceKey<Level> dimension;
    private final RevisionGate gate = new RevisionGate();
    private final SidecarClient sidecar;

    private final Set<BlockPos> structural = ConcurrentHashMap.newKeySet();
    /** Blocks carrying a demo test load, toggled with the stress glasses. */
    private final Set<BlockPos> loaded = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private boolean dirty;
    private int ticksSinceSolve;
    private AnalysisResult latest;

    private StructureManager(ResourceKey<Level> dimension) {
        this.dimension = dimension;
        String exe = System.getProperty("br.sidecar", System.getenv("BR_SIDECAR"));
        this.sidecar = new SidecarClient(
                SidecarConfig.of(Path.of(exe == null || exe.isBlank() ? "br-sidecar" : exe)),
                msg -> BlockRealityMod.LOG.info("[{}] {}", dimension.location(), msg));
    }

    public static StructureManager of(ServerLevel level) {
        return BY_DIMENSION.computeIfAbsent(level.dimension(), StructureManager::new);
    }

    public RevisionGate gate() { return gate; }

    public AnalysisResult latest() { return latest; }

    public SidecarClient.Status engineStatus() { return sidecar.status(); }

    public boolean toggleLoad(BlockPos pos) {
        boolean added = loaded.add(pos.immutable());
        if (!added) loaded.remove(pos.immutable());
        markDirty();
        return added;
    }

    private void markDirty() {
        gate.bump();
        dirty = true;
    }

    // ------------------------------------------------------------------ events
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        if (!(e.getPlacedBlock().getBlock() instanceof StructuralBlock)) return;
        StructureManager m = of(level);
        m.structural.add(e.getPos().immutable());
        m.markDirty();
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        if (!(e.getState().getBlock() instanceof StructuralBlock)) return;
        StructureManager m = of(level);
        m.structural.remove(e.getPos().immutable());
        m.loaded.remove(e.getPos().immutable());
        m.markDirty();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        StructureManager m = BY_DIMENSION.remove(level.dimension());
        if (m != null) m.sidecar.close();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (!(e.level instanceof ServerLevel level)) return;
        StructureManager m = BY_DIMENSION.get(level.dimension());
        if (m != null) m.tick(level);
    }

    public static void shutdownAll() {
        for (StructureManager m : BY_DIMENSION.values()) m.sidecar.close();
        BY_DIMENSION.clear();
        AnalysisExecutor.shutdown();
    }

    // ------------------------------------------------------------------- loop
    private void tick(ServerLevel level) {
        ticksSinceSolve++;
        if (!dirty || inFlight.get() || ticksSinceSolve < MIN_TICKS_BETWEEN_SOLVES) return;
        if (structural.isEmpty()) { dirty = false; return; }

        long start = System.nanoTime();
        SolveRequest request = snapshot(level);
        // If gathering alone blew the budget the world is large enough that the writeback
        // would too; wait a tick rather than half-finishing.
        if (System.nanoTime() - start > TICK_BUDGET_NS) return;

        dirty = false;
        ticksSinceSolve = 0;
        inFlight.set(true);

        AnalysisExecutor.pool().execute(() -> {
            AnalysisResult result = sidecar.solve(request);
            level.getServer().execute(() -> {
                try {
                    apply(level, result);
                } finally {
                    inFlight.set(false);
                }
            });
        });
    }

    /**
     * Reads the world into an immutable request. Main thread only — this is the only
     * point at which world state is touched, and after it returns the background thread
     * shares nothing with the game.
     */
    private SolveRequest snapshot(ServerLevel level) {
        SolveRequest.Builder b = SolveRequest.builder(gate.current());
        List<BlockPos> stale = new ArrayList<>();

        for (BlockPos pos : structural) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof StructuralBlock sb)) {
                // Removed by something that raised no event — a command, another mod,
                // world generation. The set is a cache of the world, not the truth.
                stale.add(pos);
                continue;
            }
            b.block(new BlockKey(pos.getX(), pos.getY(), pos.getZ()),
                    sb.materialToken(), sb.sectionToken(), isSupported(level, pos));
        }
        structural.removeAll(stale);

        for (BlockPos pos : loaded) {
            if (!structural.contains(pos)) continue;
            b.load(SolveRequest.PointLoad.downwards(
                    new BlockKey(pos.getX(), pos.getY(), pos.getZ()), DEMO_LOAD_N));
        }
        return b.build();
    }

    /** 20 kN, the load used by the cantilever fixture the sidecar's tests are pinned to. */
    private static final double DEMO_LOAD_N = 20_000;

    /**
     * Demo support rule: a structural block resting on something solid that is not itself
     * structural is held by the ground.
     *
     * <p>This is deliberately crude and deliberately temporary — MEMBER_SEMANTICS Q6
     * (what counts as ground contact, and with what fixity) is still open. Encoding a
     * guess here and letting it spread through the codebase is how a placeholder becomes
     * a permanent decision nobody remembers making.
     */
    private static boolean isSupported(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockState under = level.getBlockState(below);
        if (under.isAir()) return false;
        if (under.getBlock() instanceof StructuralBlock) return false;
        return under.isSolidRender(level, below);
    }

    /**
     * Applies a finished analysis, on the main thread.
     *
     * <p>A stale or failed result changes nothing at all. That is the single most
     * important rule on this side of the boundary: analysis failure must never mutate the
     * world, because a mod that demolishes a build when its helper process crashes is a
     * mod nobody will install twice.
     */
    private void apply(ServerLevel level, AnalysisResult result) {
        latest = result;

        if (!result.ok()) {
            BRNetwork.sendEngineStatus(level, sidecar.status(), result.diagnostic());
            return;
        }
        if (gate.isStale(result)) {
            // Not an error: the player edited while we solved. The next tick re-solves.
            dirty = true;
            return;
        }

        gate.acceptForCommit(result);
        BRNetwork.sendResult(level, result);
    }
}
