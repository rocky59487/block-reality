package com.blockreality.impl.server;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.RevisionGate;
import com.blockreality.core.engine.GameWorldSnapshot;
import com.blockreality.core.engine.NativeGameRuntime;
import com.blockreality.core.engine.GameNativeLoader;
import com.blockreality.core.bsi.BsiHeaders;
import com.blockreality.core.bsi.BsiRecords;
import com.blockreality.core.protocol.Truncation;
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
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

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
    private final NativeGameRuntime engine;
    private volatile String engineDetails = "native engine not loaded yet";
    private final AtomicBoolean cleanupStarted = new AtomicBoolean();
    private boolean enabledLastTick;
    private long probedRevision = -1;

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
    /** Whether the last request explicitly disabled the native eigen analysis. */
    private boolean lastBucklingSkipped;
    private boolean dirty;
    private int ticksSinceSolve;
    private AnalysisResult latest;
    /**
     * Ids whose verdict was withheld because their input was cut (N14-c).
     *
     * <p>Kept beside {@link #latest} rather than recomputed per reader: the overlay and
     * the commands have to withhold the SAME elements, and two call sites deriving the
     * same set independently is how they stop agreeing.
     */
    private Set<Integer> withheldMembers = Set.of();
    private Set<Integer> withheldShells = Set.of();

    // ---- the in-progress gather; all main-thread ----
    private final GatherCycle<BlockPos> cycle = new GatherCycle<>();
    private List<GameWorldSnapshot.Cell> cycleCells;
    private Set<BlockKey> cycleGround;
    private List<BlockKey> cycleUndeclared;
    private record Gathered(GameWorldSnapshot world, String diagnostic) { }
    private Set<BlockKey> cycleIncluded;
    private List<BlockPos> cycleStale;
    /** Tracked blocks this cycle could not read, because their chunk is not loaded. */
    private List<BlockKey> cycleSkipped;

    /**
     * Blocks that were left out of the request in flight and touch what was sent (N14-b).
     *
     * <p>Empty is a fact, not an absence: the gather ran and nothing was missing. It is
     * recomputed every cycle, so a reply is always read against its own request.
     */
    private Set<BlockKey> truncationFace = Set.of();

    /** Last revision the clients were told about, result or pending signal (INV-4). */
    private long lastAnnouncedRevision = -1;

    private static boolean enabled() {
        return BRConfig.INSTANCE.analysisEnabled.get() && BRConfig.INSTANCE.mode.get() == BRConfig.EngineMode.INPROCESS;
    }

    private StructureManager(ResourceKey<Level> dimension) {
        this.dimension = dimension;
        enabledLastTick = enabled();
        java.util.function.Consumer<String> log = message -> {
            engineDetails = message;
            BlockRealityMod.LOG.info("[{}] {}", dimension.location(), message);
        };
        this.engine = new NativeGameRuntime(enabledLastTick,
                () -> GameNativeLoader.open(BRConfig.INSTANCE.enginePath.get(),
                        net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("blockreality/engine"),
                        BRConfig.INSTANCE.numThreads.get(), log),
                BRConfig.INSTANCE.requestTimeoutMs.get(), log);
    }

    public String engineLocation() { return engineDetails; }

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
        engine.requestReset(enabled());
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

    /** True when this member's structure ran into a chunk the gather could not read. */
    public boolean isWithheld(int memberId) { return withheldMembers.contains(memberId); }

    public boolean isShellWithheld(int shellId) { return withheldShells.contains(shellId); }

    /** How many members and facets have no verdict this round. */
    public int withheldCount() { return withheldMembers.size() + withheldShells.size(); }

    /** Tracked blocks the gather could not read that touched what it did send. */
    public int truncatedBlocks() { return truncationFace.size(); }

    public NativeGameRuntime.Status engineStatus() { return engine.state().status(); }

    /** The native transport selected for the game session. */
    public String engineTransport() { return "BSI/JNA in-process"; }

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
        if (!(e.getPlacedBlock().getBlock() instanceof StructuralBlock)) {
            groundChanged(level, e.getPos()); return;
        }
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
        if (!(e.getState().getBlock() instanceof StructuralBlock)) {
            groundChanged(level, e.getPos()); return;
        }
        StructureManager m = of(level);
        m.structural.remove(e.getPos().immutable());
        m.loaded.remove(e.getPos().immutable());
        m.markDirty();
    }

    /** Ground edits change the declared world even when no structural cell was placed or removed. */
    private static void groundChanged(ServerLevel level, BlockPos pos) {
        StructureManager manager = BY_DIMENSION.get(level.dimension());
        if (manager == null) return;
        for (net.minecraft.core.Direction face : net.minecraft.core.Direction.values()) {
            if (manager.structural.contains(pos.relative(face))) { manager.markDirty(); return; }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onNeighbourChange(BlockEvent.NeighborNotifyEvent e) {
        if (e.getLevel() instanceof ServerLevel level) groundChanged(level, e.getPos());
    }

    /**
     * An explosion removes blocks without a {@code BreakEvent} for any of them.
     *
     * <p>Nothing in this mod noticed, so a creeper could take out a column and the
     * analysis would keep reporting the structure that used to be there — the
     * silently-safe answer, on the most ordinary event in the game (PR26_REVIEW DF-01).
     *
     * <p>{@code Detonate} fires after the affected-block list is final and before the
     * blocks are removed, so the positions here are exactly the ones about to go. The
     * tracked set is pruned and the world marked dirty; the gather does the rest.
     */
    // LOWEST: protection mods edit the affected-block list at their own priorities, and
    // pruning must act on the FINAL list — removing a block from tracking that a claim
    // mod then saves would desynchronise the model from the world (v0.3a review §3-6).
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExplode(ExplosionEvent.Detonate e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        StructureManager m = BY_DIMENSION.get(level.dimension());
        if (m == null) return;
        boolean touched = false;
        for (BlockPos pos : e.getAffectedBlocks()) {
            groundChanged(level, pos);
            if (!(level.getBlockState(pos).getBlock() instanceof StructuralBlock)) continue;
            BlockPos at = pos.immutable();
            m.structural.remove(at);
            m.loaded.remove(at);
            touched = true;
        }
        if (touched) m.markDirty();
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
        if (m != null) m.closeEngineAsync();
    }

    /** Invalidate now; even a wedged native call can only hold a daemon cleanup thread. */
    private void closeEngineAsync() {
        engine.requestClose();
        if (!cleanupStarted.compareAndSet(false, true)) return;
        Thread cleanup = new Thread(engine::closeWhenIdle, "br-native-close");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (!(e.level instanceof ServerLevel level)) return;
        StructureManager m = BY_DIMENSION.get(level.dimension());
        if (m != null) m.tick(level);
    }

    public static void shutdownAll() {
        for (StructureManager m : BY_DIMENSION.values()) m.closeEngineAsync();
        BY_DIMENSION.clear();
        AnalysisExecutor.shutdown();
    }

    // ------------------------------------------------------------------- loop
    private void tick(ServerLevel level) {
        ticksSinceSolve++;
        boolean on = enabled();
        if (on != enabledLastTick) {
            enabledLastTick = on;
            engine.requestReset(on);
            requestResolve();
            if (!on) {
                Thread cleanup = new Thread(engine::closeWhenIdle, "br-native-disable");
                cleanup.setDaemon(true); cleanup.start();
                BRNetwork.sendEngineStatus(level, engineStatus(), "analysis is off");
            }
        }
        if (!on || engine.closed()) return;
        if (engine.pollTimeout()) {
            gate.bump();
            latest = AnalysisResult.failed(gate.current(), engine.state().detail());
            BRNetwork.sendEngineStatus(level, engineStatus(), engine.state().detail());
        }

        long currentRevision = gate.current().value();

        // INV-4: the overlay clients are drawing describes an older world the moment
        // the revision moves. Tell them once per revision change, so the HUD can say
        // "stale" instead of presenting the old picture as current.
        if (latest != null && currentRevision != lastAnnouncedRevision) {
            BRNetwork.sendAnalysisPending(level, currentRevision);
            lastAnnouncedRevision = currentRevision;
        }

        if (inFlight.get() || engineStatus() == NativeGameRuntime.Status.DISABLED) return;

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
        cycleCells = new ArrayList<>();
        cycleGround = new HashSet<>();
        cycleUndeclared = new ArrayList<>();
        cycleIncluded = new HashSet<>();
        cycleStale = new ArrayList<>();
        cycleSkipped = new ArrayList<>();
        // A STABLE order: the live set may change while the cycle is parked, and an
        // iterator over it would be invalidated. The copy is O(n) refs, once per cycle.
        cycle.begin(List.copyOf(structural), revision);
    }

    /** One block of the gather. Main thread only — the single point that reads world state. */
    private void visitForCycle(ServerLevel level, BlockPos pos) {
        // Never touch an unloaded chunk: getBlockState would force it to load, and a
        // background structure could drag chunks in behind the player's back.
        // Such blocks are skipped, NOT forgotten — they come back with their chunk.
        //
        // But skipping used to end here, and that was the whole of #74: the block left
        // the request, nothing recorded that it had, and the engine was asked about a
        // structure with a piece missing. It answered confidently, because from where it
        // sits nothing was missing. Now the skip is written down, so finishCycle can work
        // out whether it cut through anything (N14-a).
        if (!level.isLoaded(pos)) {
            cycleSkipped.add(new BlockKey(pos.getX(), pos.getY(), pos.getZ()));
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof StructuralBlock sb)) {
            // Removed by something that raised no event — a command, another mod,
            // world generation. The set is a cache of the world, not the truth.
            cycleStale.add(pos);
            return;
        }
        BlockKey key = new BlockKey(pos.getX(), pos.getY(), pos.getZ());
        StructuralBlock.Axis axis = state.getValue(StructuralBlock.AXIS);
        if (axis == StructuralBlock.Axis.UNDECLARED) { cycleUndeclared.add(key); return; }
        cycleCells.add(GameWorldSnapshot.Cell.of(key, sb.materialToken(), sb.sectionToken(), axis.wire()));
        cycleIncluded.add(key);
        for (net.minecraft.core.Direction face : net.minecraft.core.Direction.values()) {
            BlockPos neighbour = pos.relative(face);
            BlockKey at = new BlockKey(neighbour.getX(), neighbour.getY(), neighbour.getZ());
            if (!level.isLoaded(neighbour)) { cycleSkipped.add(at); continue; }
            BlockState observed = level.getBlockState(neighbour);
            if (!(observed.getBlock() instanceof StructuralBlock) && !observed.isAir()
                    && observed.isFaceSturdy(level, neighbour, face.getOpposite())) cycleGround.add(at);
        }
    }

    /** Completes the request: stale removal, then the loads that may travel (#38). */
    private Gathered finishCycle() {
        cycleStale.forEach(structural::remove);
        cycleStale.forEach(loaded::remove);
        List<BsiRecords.Load> forces = new ArrayList<>();
        for (var entry : loaded.entrySet()) {
            BlockPos p = entry.getKey();
            if (!structural.contains(p)) { loaded.remove(p); continue; }
            if (!probeWithoutLoads && cycleIncluded.contains(new BlockKey(p.getX(), p.getY(), p.getZ()))) {
                double[] f = entry.getValue();
                forces.add(new BsiRecords.Load(p.getX(), p.getY(), p.getZ(), f[0], f[1], f[2]));
            }
        }
        lastBucklingSkipped = !BRConfig.INSTANCE.bucklingEnabled.get();
        truncationFace = Truncation.face(cycleSkipped, cycleIncluded);
        String diagnostic = cycleUndeclared.isEmpty() ? "" : "undeclared placement axis at "
                + cycleUndeclared.get(0) + " (" + cycleUndeclared.size()
                + " blocks); sneak-right-click with an empty hand to declare X/Y/Z";
        GameWorldSnapshot snapshot = diagnostic.isEmpty()
                ? new GameWorldSnapshot(gate.current(), cycleCells, List.copyOf(cycleGround), forces)
                : new GameWorldSnapshot(gate.current(), List.of(), List.of(), List.of());
        cycleCells = null; cycleGround = null; cycleUndeclared = null;
        cycleIncluded = null; cycleStale = null; cycleSkipped = null;
        return new Gathered(snapshot, diagnostic);
    }

    private void dispatch(ServerLevel level, Gathered request) {
        ticksSinceSolve = 0;
        inFlight.set(true);
        probeInFlight.set(probeWithoutLoads);
        probeWithoutLoads = false;

        MinecraftServer server = level.getServer();
        Integer threads = BRConfig.INSTANCE.numThreads.get();
        BsiHeaders.EigenBuckling buckling = BRConfig.INSTANCE.bucklingEnabled.get()
                ? new BsiHeaders.EigenBuckling(BRConfig.INSTANCE.bucklingDofBudget.get()) : null;
        boolean accepted = AnalysisExecutor.submit(() -> SolveDispatch.run(
                () -> request.diagnostic().isEmpty()
                        ? engine.analyze(request.world(), threads, buckling)
                        : AnalysisResult.failed(request.world().revision(), request.diagnostic()),
                result -> server.execute(() -> {
                    // A stopped server runs execute() INLINE on this background thread
                    // (CONC-6). Touch nothing but the flag: the world is going away.
                    if (server.isStopped() || engine.closed() || BY_DIMENSION.get(dimension) != this) {
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
     * Applies a finished analysis, on the main thread.
     *
     * <p>A stale or failed result changes nothing at all. That is the single most
     * important rule on this side of the boundary: analysis failure must never mutate the
     * world, because a mod that demolishes a build when its analysis fails is a
     * mod nobody will install twice.
     *
     * <p>What reaches the wire is decided by the gate, not assumed (#53): the broadcast
     * happens only for a result the commit gate accepted — or for an all-singular
     * mechanism verdict, which is real information about the current world even though
     * there is nothing usable to draw. An ok-but-empty reply updates {@code latest} for
     * {@code /br status} and travels no further.
     */
    private void apply(ServerLevel level, AnalysisResult result) {
        if (engine.closed()) return;
        if (gate.isStale(result)) { probeInFlight.set(false); dirty = true; return; }
        if (probeInFlight.getAndSet(false) && result.ok()) {
            // A STALE probe drops nothing (A-5 follow-up, v0.3a review §3-6): the player
            // edited while it solved, so it is a picture of an old world — a load it
            // says has nowhere to stand may stand fine now. dirty re-solves; if loads
            // still break the solve, the UNAVAILABLE path re-arms the probe itself.
            if (gate.isStale(result)) {
                dirty = true;
                return;
            }
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
            // ...and the withholding goes with it. This path replaces `latest` without
            // recomputing them, so leaving the old sets in place would leave ids from a
            // previous answer marked against a result that has no members at all. Nothing
            // reads them on this path today; that is a reason to keep it that way, not a
            // reason to leave a stale set lying where the next reader will find it.
            withheldMembers = Set.of();
            withheldShells = Set.of();
            BRNetwork.sendEngineStatus(level, engineStatus(), result.diagnostic());
            dirty = false;
            // At most one unloaded-force probe per revision, and only after a real native refusal.
            if (!result.ok() && !loaded.isEmpty() && engineStatus() == NativeGameRuntime.Status.READY
                    && probedRevision != result.revision().value()) {
                probedRevision = result.revision().value(); probeWithoutLoads = true; dirty = true;
            }
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
        // Computed HERE and not at the two places that read it. The overlay withheld
        // these elements and `/br section` printed their D/C anyway, so the same member
        // read "not judged" on screen and "D/C = 0.83" in chat -- one number, two
        // surfaces, two answers, which is the thing N14-c exists to prevent. Deriving
        // them once beside `latest` is what stops the two from drifting again.
        withheldMembers = Truncation.touching(truncationFace, result.members(),
                MemberSnapshot::blocks, MemberSnapshot::id);
        withheldShells = Truncation.touching(truncationFace, result.shells(),
                ShellSnapshot::blocks, ShellSnapshot::id);
        boolean committed = gate.acceptForCommit(result);
        if (committed || display == RevisionGate.Display.MECHANISM) {
            // Elements standing against a block we could not read do not get a verdict
            // (N14-c). Everything else is shown exactly as before — an empty face makes
            // both sets empty and the packet identical to the one v0.3c sent (N14-e).
            BRNetwork.sendResult(level, result, lastBucklingSkipped,
                    withheldMembers, withheldShells, truncationFace.size());
            lastAnnouncedRevision = result.revision().value();
        }
    }

    /** Removes every load the engine has nowhere to put, and says so out loud. */
    private void dropRefusedLoads(ServerLevel level, AnalysisResult probe) {
        Map<BlockKey, BlockPos> posOf = new HashMap<>();
        for (BlockPos p : loaded.keySet()) {
            posOf.put(new BlockKey(p.getX(), p.getY(), p.getZ()), p);
        }
        // NOT every unassigned block: only the ones whose reason means they belong to
        // no element at all. Since N17 the list also holds the blocks of mechanisms and of
        // fully supported structures, and both of those ARE nodes -- the engine accepts a
        // load on them. Passing the whole list here would delete a player's test load the
        // moment the thing it sat on lost its last support, citing a reason that is not
        // true. (The fully-supported half of that was already happening.)
        for (BlockKey k : SnapshotLoads.refusedBy(posOf.keySet(), probe.blocksFormingNoElement())) {
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
