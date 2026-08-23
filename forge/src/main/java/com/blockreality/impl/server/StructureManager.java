package com.blockreality.impl.server;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.RevisionGate;
import com.blockreality.core.protocol.SolveRequest;
import com.blockreality.core.sidecar.SidecarClient;
import com.blockreality.core.sidecar.SidecarConfig;
import com.blockreality.impl.BRConfig;
import com.blockreality.impl.BlockRealityMod;
import com.blockreality.impl.block.StructuralBlock;
import com.blockreality.impl.net.BRNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 *   gather (budgeted)  -> solve             -> apply
 * </pre>
 *
 * <p>The gather may span several ticks on a large world ({@link GatherCycle}); the
 * request it produces is immutable, so the player may keep building while the solve
 * runs. The edit is not a race — it just makes the in-flight result stale, and
 * {@link RevisionGate} throws it away when it lands.
 *
 * <p><strong>State is per dimension, never static-global.</strong> The previous codebase
 * kept one map for every level, which meant the Nether and the Overworld shared an index
 * and nothing could be unit-tested. Keying on {@link ResourceKey} costs nothing and makes
 * the bug unrepresentable.
 */
public final class StructureManager {

    /** Main-thread share of a tick. Background solving does not come out of this. */
    private static final long TICK_BUDGET_NS = 8_000_000L;


    private static final Map<ResourceKey<Level>, StructureManager> BY_DIMENSION = new ConcurrentHashMap<>();

    private final ResourceKey<Level> dimension;
    private final RevisionGate gate = new RevisionGate();
    private final SidecarClient sidecar;

    private final Set<BlockPos> structural = ConcurrentHashMap.newKeySet();
    /**
     * Test loads by block: {fx, fy, fz} in newtons, Minecraft axes (+y up). The stress
     * glasses toggle the configured downward value; {@code /br load} writes any vector,
     * which is what makes a shear wall loadable in its own plane from inside the game.
     * In memory only, like {@code structural} itself: the world is the save file, the
     * loads are the experiment.
     */
    private final Map<BlockPos, double[]> loaded = new ConcurrentHashMap<>();

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    /**
     * The engine refused the last request and there are loads in play, so the next one
     * goes out WITHOUT them: a refusal reply names no block, but a solve that carries no
     * load cannot be refused for carrying one, and its {@code unassigned} list says
     * exactly which loads have nowhere to stand (A-5).
     */
    private boolean probeWithoutLoads;
    /** The reply now arriving is that probe: use it to drop loads, do not draw it. */
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);
    private boolean dirty;
    private int ticksSinceSolve;
    private AnalysisResult latest;

    // ---- the in-progress gather; all main-thread ----
    private final GatherCycle<BlockPos> cycle = new GatherCycle<>();
    private SolveRequest.Builder cycleBuilder;
    private Set<BlockKey> cycleIncluded;
    private List<BlockPos> cycleStale;

    /** Last revision the clients were told about, result or pending signal (INV-4). */
    private long lastAnnouncedRevision = -1;

    private final SidecarLocator.Result location;

    private StructureManager(ResourceKey<Level> dimension) {
        this.dimension = dimension;
        this.location = SidecarLocator.locate();
        BlockRealityMod.LOG.info("[{}] {}", dimension.location(), SidecarLocator.describe(location));

        // A path is still handed over when nothing was found, so the client reports
        // "binary not found: <path>" rather than a bare null. It never starts.
        Path exe = location.found().orElse(Path.of("br-sidecar"));
        this.sidecar = new SidecarClient(
                new SidecarConfig(exe, BRConfig.INSTANCE.requestTimeoutMs.get(), 4, 2000),
                msg -> BlockRealityMod.LOG.info("[{}] {}", dimension.location(), msg));
    }

    public SidecarLocator.Result engineLocation() { return location; }

    public ResourceKey<Level> dimension() { return dimension; }

    public int structuralBlockCount() { return structural.size(); }

    public int loadedBlockCount() { return loaded.size(); }

    /** Forces the next tick to re-analyse, for {@code /br resolve}. */
    public void requestResolve() {
        gate.bump();
        dirty = true;
        ticksSinceSolve = Integer.MAX_VALUE / 2;
    }

    /** Clears a disabled engine so the next tick tries again, for {@code /br reset}. */
    public void resetEngine() {
        // Off the server thread: reset() takes the client's conversation lock, and a
        // wedged solve holds that lock for up to the request timeout (CONC-2).
        if (!AnalysisExecutor.submit(sidecar::reset)) {
            sidecar.reset();
        }
        requestResolve();
    }

    /** All managers that currently exist, for {@code /br status}. */
    public static java.util.Collection<StructureManager> all() { return BY_DIMENSION.values(); }

    /**
     * Re-reads loaded chunks around a point, for {@code /br scan}.
     *
     * <p>Bounded by a chunk radius rather than sweeping the world: an unbounded scan on a
     * large save is a stall, and a command that freezes the server is not a diagnostic.
     *
     * <p>Reconciles as well as adds (#54): a tracked position inside the scanned area
     * whose block is no longer structural — removed by an edit that raised no event —
     * is dropped, and its test load with it. Without this, {@code /br scan} could only
     * ever grow the model.
     *
     * @return how many structural blocks are now tracked in the scanned area
     */
    public int rescan(ServerLevel level, BlockPos centre, int chunkRadius) {
        int cx = centre.getX() >> 4;
        int cz = centre.getZ() >> 4;
        int found = 0;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                // hasChunk, not getChunk: scanning must never be the thing that generates
                // terrain.
                if (!level.hasChunk(cx + dx, cz + dz)) continue;
                found += scanChunk(this, level.getChunk(cx + dx, cz + dz));
            }
        }

        List<BlockPos> gone = new ArrayList<>();
        for (BlockPos pos : structural) {
            int pcx = pos.getX() >> 4;
            int pcz = pos.getZ() >> 4;
            if (Math.abs(pcx - cx) > chunkRadius || Math.abs(pcz - cz) > chunkRadius) continue;
            if (!level.isLoaded(pos)) continue;
            if (!(level.getBlockState(pos).getBlock() instanceof StructuralBlock)) gone.add(pos);
        }
        for (BlockPos pos : gone) {
            structural.remove(pos);
            loaded.remove(pos);
        }

        if (found > 0 || !gone.isEmpty()) markDirty();
        return found;
    }

    private static int scanChunk(StructureManager m, net.minecraft.world.level.chunk.ChunkAccess access) {
        if (!(access instanceof LevelChunk chunk)) return 0;
        int found = 0;
        LevelChunkSection[] sections = chunk.getSections();
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection section = sections[si];
            if (section == null || section.hasOnlyAir()) continue;
            if (!section.maybeHas(s -> s.getBlock() instanceof StructuralBlock)) continue;

            int baseY = chunk.getMinBuildHeight() + (si << 4);
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        if (!(section.getBlockState(x, y, z).getBlock() instanceof StructuralBlock)) continue;
                        m.structural.add(new BlockPos(
                                chunk.getPos().getMinBlockX() + x, baseY + y,
                                chunk.getPos().getMinBlockZ() + z));
                        found++;
                    }
                }
            }
        }
        return found;
    }

    public static StructureManager of(ServerLevel level) {
        return BY_DIMENSION.computeIfAbsent(level.dimension(), StructureManager::new);
    }

    public RevisionGate gate() { return gate; }

    public AnalysisResult latest() { return latest; }

    public SidecarClient.Status engineStatus() { return sidecar.status(); }

    /** Which wire the engine conversation uses: {@code "shm"} or {@code "json"}. */
    public String engineTransport() { return sidecar.transport(); }

    /** Glasses affordance: toggle the configured downward test load on one block. */
    public boolean toggleLoad(BlockPos pos) {
        BlockPos key = pos.immutable();
        boolean added = loaded.putIfAbsent(key,
                new double[] { 0, -Math.abs(BRConfig.INSTANCE.demoLoadNewtons.get()), 0 }) == null;
        if (!added) loaded.remove(key);
        markDirty();
        return added;
    }

    /**
     * Sets an arbitrary test load on one block, for {@code /br load}. A zero vector
     * clears it — "no load" is represented by absence, never by a stored zero that
     * would still count as a loaded block.
     *
     * @return true if a load is now present, false if the call cleared it
     */
    public boolean setLoad(BlockPos pos, double fxN, double fyN, double fzN) {
        BlockPos key = pos.immutable();
        if (fxN == 0 && fyN == 0 && fzN == 0) {
            loaded.remove(key);
        } else {
            loaded.put(key, new double[] { fxN, fyN, fzN });
        }
        markDirty();
        return loaded.containsKey(key);
    }

    /** @return how many loads were removed */
    public int clearAllLoads() {
        int n = loaded.size();
        if (n > 0) {
            loaded.clear();
            markDirty();
        }
        return n;
    }

    /** Read-only view of the current test loads, newtons in Minecraft axes. */
    public Map<BlockPos, double[]> loads() {
        return java.util.Collections.unmodifiableMap(loaded);
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

    /**
     * LOWEST priority, and NOT {@code receiveCanceled}: {@code BreakEvent} is
     * cancellable, and the old default-priority listener ran before protection mods
     * had their say — a break that a claim mod then cancelled had already been
     * dropped from tracking, silently un-modelling a block that still stands
     * (FORGE-6). At LOWEST, every higher-priority cancellation has happened, and a
     * cancelled event is simply not delivered here.
     *
     * <p>Backstop for what priority cannot promise (a same-priority canceller
     * registered later than this mod): the gather drops tracked positions whose
     * block turns out not to be structural, and {@code /br scan} or a chunk reload
     * re-adopts a block that was dropped by mistake.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        if (!(e.getState().getBlock() instanceof StructuralBlock)) return;
        StructureManager m = of(level);
        m.structural.remove(e.getPos().immutable());
        m.loaded.remove(e.getPos().immutable());
        m.markDirty();
    }

    /**
     * Adopts structural blocks that arrive without a place event.
     *
     * <p>Three cases need this, and the first is fatal without it: <strong>the tracked set
     * is in memory only</strong>, so reloading a world would forget every structure ever
     * built until each block was placed again. The others are {@code /setblock} and world
     * edit tools, which bypass {@code EntityPlaceEvent} entirely.
     *
     * <p>Scanning a chunk block by block would be 98k lookups per chunk. Instead each
     * section is asked whether its <em>palette</em> could contain a structural block —
     * a handful of comparisons — and only matching sections are walked.
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        StructureManager m = BY_DIMENSION.computeIfAbsent(level.dimension(), StructureManager::new);
        if (scanChunk(m, e.getChunk()) > 0) m.markDirty();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        StructureManager m = BY_DIMENSION.remove(level.dimension());
        if (m != null) m.closeSidecarAsync();
    }

    /**
     * Closes the engine conversation off the server thread. close() waits for an
     * in-flight solve (they share the client's lock) and then for process exit — up to
     * seconds against a wedged sidecar, which the server thread must not eat (CONC-9).
     * Queued on the analysis pool; if the pool is already gone (server stopping), the
     * pool has been drained first, so the inline fallback is uncontended.
     */
    private void closeSidecarAsync() {
        if (!AnalysisExecutor.submit(sidecar::close)) {
            sidecar.close();
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (!(e.level instanceof ServerLevel level)) return;
        StructureManager m = BY_DIMENSION.get(level.dimension());
        if (m != null) m.tick(level);
    }

    /**
     * Ordered shutdown (CONC-8): stop the pool FIRST and wait for it to drain, so no
     * background solve can race the closes; THEN close every sidecar, uncontended.
     * The old order closed the clients while their solves were still running, which
     * manufactured exactly the close-versus-solve window the per-client lock now has
     * to absorb.
     */
    public static void shutdownAll() {
        AnalysisExecutor.shutdown();
        for (StructureManager m : BY_DIMENSION.values()) m.sidecar.close();
        BY_DIMENSION.clear();
    }

    // ------------------------------------------------------------------- loop
    private void tick(ServerLevel level) {
        ticksSinceSolve++;
        if (!BRConfig.INSTANCE.analysisEnabled.get()) return;

        long currentRevision = gate.current().value();

        // INV-4: the overlay clients are drawing describes an older world the moment
        // the revision moves. Tell them once per revision change, so the HUD can say
        // "stale" instead of presenting the old picture as current.
        if (latest != null && currentRevision != lastAnnouncedRevision) {
            BRNetwork.sendAnalysisPending(level, currentRevision);
            lastAnnouncedRevision = currentRevision;
        }

        if (inFlight.get()) return;

        long tickStart = System.nanoTime();

        // A gather parked between ticks dies with the revision it was reading (#35's
        // cursor must never mix two worlds into one request).
        cycle.ensureCurrent(currentRevision);

        if (!cycle.inProgress()) {
            if (!dirty || ticksSinceSolve < BRConfig.INSTANCE.minTicksBetweenSolves.get()) return;
            if (structural.isEmpty()) { dirty = false; return; }
            beginCycle(currentRevision);
        }

        long remaining = Math.max(0, TICK_BUDGET_NS - (System.nanoTime() - tickStart));
        GatherCycle.Step step = cycle.step(remaining, System::nanoTime,
                pos -> visitForCycle(level, pos));
        if (step != GatherCycle.Step.COMPLETE) return;

        dispatch(level, finishCycle());
    }

    private void beginCycle(long revision) {
        dirty = false;
        cycleBuilder = SolveRequest.builder(gate.current());
        cycleIncluded = new HashSet<>();
        cycleStale = new ArrayList<>();
        // A STABLE order: the live set may change while the cycle is parked, and an
        // iterator over it would be invalidated. The copy is O(n) refs, once per cycle.
        cycle.begin(List.copyOf(structural), revision);
    }

    /** One block of the gather. Main thread only — the single point that reads world state. */
    private void visitForCycle(ServerLevel level, BlockPos pos) {
        // Never touch an unloaded chunk: getBlockState would force it to load, and a
        // background structure could drag chunks in behind the player's back.
        // Such blocks are skipped, NOT forgotten — they come back with their chunk.
        if (!level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof StructuralBlock sb)) {
            // Removed by something that raised no event — a command, another mod,
            // world generation. The set is a cache of the world, not the truth.
            cycleStale.add(pos);
            return;
        }
        BlockKey key = new BlockKey(pos.getX(), pos.getY(), pos.getZ());
        cycleBuilder.block(key, sb.materialToken(), sb.sectionToken(), isSupported(level, pos));
        cycleIncluded.add(key);
    }

    /** Completes the request: stale removal, then the loads that may travel (#38). */
    private SolveRequest finishCycle() {
        cycleStale.forEach(structural::remove);
        cycleStale.forEach(loaded::remove);

        Map<BlockKey, double[]> loadsByKey = new HashMap<>();
        Map<BlockKey, BlockPos> posOf = new HashMap<>();
        for (Map.Entry<BlockPos, double[]> e : loaded.entrySet()) {
            BlockKey k = new BlockKey(e.getKey().getX(), e.getKey().getY(), e.getKey().getZ());
            loadsByKey.put(k, e.getValue());
            posOf.put(k, e.getKey());
        }
        Set<BlockKey> tracked = new HashSet<>();
        for (BlockPos p : structural) {
            tracked.add(new BlockKey(p.getX(), p.getY(), p.getZ()));
        }
        // Loads travel only with blocks that are IN this request; the rest wait or die
        // with their block. The rule lives in SnapshotLoads, where JUnit can reach it.
        //
        // ...unless this is the recovery probe, which deliberately carries none of them.
        List<BlockKey> staleLoads = probeWithoutLoads
                ? SnapshotLoads.append(cycleBuilder, Map.of(), cycleIncluded, tracked)
                : SnapshotLoads.append(cycleBuilder, loadsByKey, cycleIncluded, tracked);
        for (BlockKey k : staleLoads) {
            BlockPos p = posOf.get(k);
            if (p != null) loaded.remove(p);
        }

        SolveRequest request = cycleBuilder.build();
        cycleBuilder = null;
        cycleIncluded = null;
        cycleStale = null;
        return request;
    }

    private void dispatch(ServerLevel level, SolveRequest request) {
        ticksSinceSolve = 0;
        inFlight.set(true);
        probeInFlight.set(probeWithoutLoads);
        probeWithoutLoads = false;

        MinecraftServer server = level.getServer();
        boolean accepted = AnalysisExecutor.submit(() -> SolveDispatch.run(
                () -> sidecar.solve(request),
                result -> server.execute(() -> {
                    // A stopped server runs execute() INLINE on this background thread
                    // (CONC-6). Touch nothing but the flag: the world is going away.
                    if (server.isStopped()) {
                        inFlight.set(false);
                        return;
                    }
                    try {
                        apply(level, result);
                    } finally {
                        inFlight.set(false);
                    }
                }),
                () -> { probeInFlight.set(false); inFlight.set(false); },
                (msg, t) -> BlockRealityMod.LOG.error("[{}] {}", dimension.location(), msg, t)));

        if (!accepted) {
            // The pool shut down between the tick and the submit (#37's race). Nothing
            // ran: release the flag and keep the work queued for a future tick.
            inFlight.set(false);
            dirty = true;
        }
    }


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
     *
     * <p>What reaches the wire is decided by the gate, not assumed (#53): the broadcast
     * happens only for a result the commit gate accepted — or for an all-singular
     * mechanism verdict, which is real information about the current world even though
     * there is nothing usable to draw. An ok-but-empty reply updates {@code latest} for
     * {@code /br status} and travels no further.
     */
    private void apply(ServerLevel level, AnalysisResult result) {
        if (probeInFlight.getAndSet(false) && result.ok()) {
            // The probe answered. Every load standing on a block that became no element
            // is dropped, the player is told which, and the world is marked dirty so the
            // next tick solves the real request — with the surviving loads. The probe
            // itself is never drawn: it is a picture of the structure with the player's
            // experiment removed, and showing that as the answer would understate it.
            dropRefusedLoads(level, result);
            dirty = true;
            return;
        }
        // The display-track classification (INV-4): what, if anything, the clients
        // should be shown. This is displayState's real call site — it was designed
        // for exactly this decision and then never wired in, which left the audit
        // finding a display contract that existed only in tests.
        RevisionGate.Display display = gate.displayState(result);

        if (display == RevisionGate.Display.UNAVAILABLE) {
            latest = result;
            BRNetwork.sendEngineStatus(level, sidecar.status(), result.diagnostic());
            // A failure used to leave `dirty` false, so the dimension went quiet until the
            // player next edited a block: one engine timeout and analysis was over for the
            // session (DF-03). Retrying is throttled by minTicksBetweenSolves and bounded
            // by the client's own restart budget, so "keep trying" cannot become a spin.
            dirty = true;
            // ...and if loads are in play, the very next request finds out whether one of
            // them is the reason (A-5).
            if (!result.ok() && !loaded.isEmpty()) probeWithoutLoads = true;
            return;
        }
        if (gate.isStale(result)) {
            // Not an error: the player edited while we solved. The next tick re-solves.
            // latest is NOT overwritten — a stale picture must not shadow a newer one.
            // (Checked before the MECHANISM broadcast on purpose: a stale mechanism
            // verdict is as expired as a stale solve.)
            dirty = true;
            return;
        }

        latest = result;
        boolean committed = gate.acceptForCommit(result);
        if (committed || display == RevisionGate.Display.MECHANISM) {
            BRNetwork.sendResult(level, result);
            lastAnnouncedRevision = result.revision().value();
        }
    }

    /** Removes every load the engine has nowhere to put, and says so out loud. */
    private void dropRefusedLoads(ServerLevel level, AnalysisResult probe) {
        Map<BlockKey, BlockPos> posOf = new HashMap<>();
        for (BlockPos p : loaded.keySet()) {
            posOf.put(new BlockKey(p.getX(), p.getY(), p.getZ()), p);
        }
        for (BlockKey k : SnapshotLoads.refusedBy(posOf.keySet(), probe.unassigned())) {
            BlockPos p = posOf.get(k);
            if (p == null || loaded.remove(p) == null) continue;
            Component msg = Component.translatable("br.msg.load_dropped",
                    p.getX(), p.getY(), p.getZ()).withStyle(ChatFormatting.YELLOW);
            for (ServerPlayer sp : level.players()) sp.sendSystemMessage(msg);
            BlockRealityMod.LOG.info("[{}] dropped a test load at {}: the block forms no element",
                    dimension.location(), p);
        }
    }
}
