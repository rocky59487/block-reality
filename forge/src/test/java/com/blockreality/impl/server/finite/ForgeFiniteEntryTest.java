package com.blockreality.impl.server.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.BsiFrame;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiResponse;
import com.blockreality.core.bsi.BsiVocabulary;
import com.blockreality.core.finite.FiniteDomainId;
import com.blockreality.core.finite.FiniteLane;
import com.blockreality.core.json.JsonValue;
import com.blockreality.core.transaction.AtomicConstructionCoordinator;
import com.blockreality.core.transaction.ConstructionTransaction.Entry;
import com.blockreality.core.transaction.ConstructionTransaction.Phase;
import com.blockreality.core.transaction.ConstructionTransaction.Reason;
import com.blockreality.core.transaction.ConstructionTransaction.Request;
import com.blockreality.core.transaction.ConstructionTransaction.Value;
import com.blockreality.core.transaction.FileTransactionJournal;
import com.blockreality.core.transaction.ManufacturedPiece;
import com.blockreality.core.transaction.ManufacturedRegistry;
import com.blockreality.core.world.ConstructionDeclaration;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * W4-G gate evidence (docs/FINITE_DOMAIN_ENTRY.md §7): the gated finite-domain
 * bootstrap entry — adopt → bind → observe → reuse-or-mint vocabulary → commit — plus
 * the observation gather, admission, refusal and lane confinement legs. No
 * MinecraftServer is needed: {@code Core} is MC-free and the scripted lanes are
 * native-shaped peers, the same harness {@code ForgeFiniteRuntimeTest} uses.
 */
class ForgeFiniteEntryTest {

    private static final UUID JOURNAL_DOMAIN = new UUID(7, 7), SESSION = new UUID(9, 9);
    private static final UUID ACTOR = new UUID(8, 8);
    private static final String DIMENSION = "minecraft:overworld";
    private static final ConstructionDeclaration STEEL =
            new ConstructionDeclaration("steel", "steel_rect_200x400", 1);
    private static final ConstructionDeclaration TIMBER =
            new ConstructionDeclaration("timber", "timber_rect_140x240", 1);
    private static final ConstructionDeclaration CONCRETE =
            new ConstructionDeclaration("concrete", "concrete_rect_400x600", 0);
    private static final ConstructionDeclaration PANEL =
            new ConstructionDeclaration("concrete", "concrete_slab_200", 0);
    private static final ConstructionDeclaration AXILESS =
            new ConstructionDeclaration("concrete", "concrete_rect_400x600", -1);

    /** The durable engine reply covering the entire shipped game vocabulary. */
    private static String catalogReply(String id, long revision) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id + "\",\"method\":\"bsi.vocab.declare\","
                + "\"revision\":" + revision + ",\"status\":\"ok\",\"version\":1,"
                + "\"materials\":["
                + "{\"id\":0,\"name\":\"steel\"},{\"id\":1,\"name\":\"timber\"},{\"id\":2,\"name\":\"rebar\"},"
                + "{\"id\":3,\"name\":\"concrete\"},{\"id\":4,\"name\":\"brick\"},"
                + "{\"id\":5,\"name\":\"concrete_slab_200\"},{\"id\":6,\"name\":\"concrete_slab_150\"},"
                + "{\"id\":7,\"name\":\"steel_plate_20\"},{\"id\":8,\"name\":\"ground_rigid\"}],"
                + "\"sections\":["
                + "{\"id\":0,\"name\":\"steel_rect_200x400\"},{\"id\":1,\"name\":\"steel_rect_150x300\"},"
                + "{\"id\":2,\"name\":\"steel_rect_100x200\"},{\"id\":3,\"name\":\"timber_rect_140x240\"},"
                + "{\"id\":4,\"name\":\"rebar_round_d25\"}]}";
    }

    private static String worldOk(String id, UUID domain, long revision, UUID ns) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id + "\",\"method\":\"bsi.world.declare\","
                + "\"revision\":" + revision + ",\"status\":\"ok\",\"identity\":{\"domain\":\""
                + BsiFracture.hex(domain) + "\",\"revision\":" + revision
                + ",\"artifactNamespace\":\"" + BsiFracture.hex(ns) + "\"}}";
    }

    private static String errorReply(String id, long revision, String method) {
        return "{\"bsi\":1,\"kind\":\"error\",\"id\":\"" + id + "\",\"method\":\"" + method
                + "\",\"revision\":" + revision + ",\"code\":\"NOOP\"}";
    }

    /** A scripted {@link FiniteLane} answering declare calls, recording its calling thread. */
    private static final class ScriptedEntryLane implements FiniteLane {
        final List<String> threads = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final List<String> responses = new ArrayList<>();
        volatile boolean ready = true;
        volatile boolean corot = true;
        volatile boolean refuseVocab = false;
        private BsiFracture.World pendingWorld;

        private void witness() { threads.add(Thread.currentThread().getName()); }

        @Override public BsiResponse request(String headerJson, byte[] payload) {
            witness();
            var header = JsonValue.parse(headerJson);
            String method = header.str("method", "");
            calls.add(method);
            String id = header.str("id", "");
            long revision = header.exactI64("revision");
            String reply;
            if (method.equals("bsi.vocab.declare")) {
                reply = refuseVocab ? errorReply(id, revision, method) : catalogReply(id, revision);
            } else if (method.equals("bsi.world.declare")) {
                var world = pendingWorld;
                assertNotNull(world, "world declare with no pending world");
                reply = worldOk(id, world.stamp().domain(), world.stamp().revision(),
                        world.artifactNamespace());
            } else {
                reply = errorReply(id, revision, method);
            }
            responses.add(reply);
            return BsiResponse.of(new BsiFrame.Decoded(0, reply, new byte[0]));
        }

        @Override public boolean adoptVocabulary(BsiVocabulary vocabulary) { witness(); return true; }

        @Override public boolean declareFiniteWorld(BsiFracture.World world) {
            witness();
            pendingWorld = world;
            try {
                String id = "w" + calls.size();
                var response = request(BsiFracture.declare(id, world), world.payload());
                BsiFracture.checkWorld(response, id, world);
                return true;
            } catch (IllegalArgumentException failure) {
                return false;
            }
        }

        @Override public boolean ready() { witness(); return ready; }

        @Override public boolean has(String capability) { witness(); return corot; }
    }

    /** A recording observation world: explicit cells, a sturdy set and an air fallback. */
    private static final class FakeWorld implements FiniteObservation.World {
        final Map<BlockKey, FiniteObservation.Cell> cells = new HashMap<>();
        final Set<BlockKey> sturdyFaces = new HashSet<>();
        FiniteObservation.Cell fallback = new FiniteObservation.Cell.Air();
        int probes;
        int sturdyChecks;

        @Override public FiniteObservation.Cell cell(BlockKey position) {
            probes++;
            return cells.getOrDefault(position, fallback);
        }

        @Override public boolean sturdy(BlockKey position, BlockKey towards) {
            sturdyChecks++;
            return sturdyFaces.contains(position);
        }
    }

    @TempDir Path dir;

    private Path journalDir;
    private FileTransactionJournal journal;
    private ManufacturedRegistry metadata;
    private AtomicConstructionCoordinator coordinator;
    private FiniteRuntime runtime;
    private ScriptedEntryLane authorityLane;
    private FiniteEntryService.Core core;
    private FakeWorld fakeWorld;
    private List<ScriptedEntryLane> mintLanes;
    private AtomicInteger laneOpens;
    private final AtomicBoolean onServerThread = new AtomicBoolean(true);
    private final AtomicBoolean entryOpen = new AtomicBoolean(true);

    private void openRuntime() throws IOException {
        journalDir = dir.resolve("blockreality/construction");
        journal = new FileTransactionJournal(journalDir, JOURNAL_DOMAIN);
        metadata = ManufacturedRegistry.load(journal);
        coordinator = new AtomicConstructionCoordinator(journal);
        authorityLane = new ScriptedEntryLane();
        runtime = FiniteRuntime.open(journal, journalDir, coordinator, metadata, SESSION,
                () -> new FiniteLaneWorker.Lane(authorityLane, () -> { }),
                () -> { if (!onServerThread.get()) throw new IllegalStateException("requires the server thread"); },
                () -> {
                    if (!onServerThread.get()) throw new IllegalStateException("requires the server thread");
                    if (!entryOpen.get()) throw new IllegalStateException("finite entry is closed");
                },
                Set.of(DIMENSION), message -> { });
        recoverRouting();
        runtime.markReady();
    }

    private void recoverRouting() throws IOException {
        Optional<UUID> pending = journal.pending();
        Entry entry = pending.isPresent()
                ? journal.verify(pending.get()).orElseThrow(() -> new IOException("pending vanished"))
                : null;
        AtomicConstructionCoordinator.Host routed = runtime.recoveryHost(entry);
        coordinator.recover(routed != null ? routed : bootstrapHost());
    }

    private static AtomicConstructionCoordinator.Host bootstrapHost() {
        return new AtomicConstructionCoordinator.Host() {
            @Override public void checkAccess() { }
            @Override public long revision() { return 0; }
            @Override public Value read(String resource) { return Value.missing(); }
            @Override public void checkpoint(List<String> resources) { }
            @Override public void write(String resource, Value value) { }
            @Override public void flush(List<String> resources) { }
            @Override public void publish(com.blockreality.core.transaction.ConstructionTransaction.Receipt receipt) { }
            @Override public void publishRecoveredState() { }
        };
    }

    /** Wire the entry core exactly like the production service wires it. */
    private void openCore() throws IOException {
        openRuntime();
        fakeWorld = new FakeWorld();
        mintLanes = new ArrayList<>();
        laneOpens = new AtomicInteger();
        core = new FiniteEntryService.Core(runtime,
                dimension -> {
                    if (!dimension.equals(DIMENSION)) throw new IOException("not loaded " + dimension);
                    return fakeWorld;
                },
                () -> {
                    laneOpens.incrementAndGet();
                    var lane = new ScriptedEntryLane();
                    mintLanes.add(lane);
                    return new ForgeFiniteFractureHost.OpenedLane(lane, () -> { }, "engine/test",
                            "cd".repeat(32));
                },
                () -> {
                    if (!onServerThread.get()) throw new IllegalStateException("requires the server thread");
                    if (!entryOpen.get()) throw new IllegalStateException("finite entry is closed");
                },
                Set.of(DIMENSION), message -> { }, SESSION);
    }

    private void openCore(java.util.Set<String> admitted) throws IOException {
        openCore();
        core.close();
        core = new FiniteEntryService.Core(runtime,
                dimension -> fakeWorld,
                () -> {
                    laneOpens.incrementAndGet();
                    var lane = new ScriptedEntryLane();
                    mintLanes.add(lane);
                    return new ForgeFiniteFractureHost.OpenedLane(lane, () -> { }, "engine/test",
                            "cd".repeat(32));
                },
                () -> {
                    if (!onServerThread.get()) throw new IllegalStateException("requires the server thread");
                    if (!entryOpen.get()) throw new IllegalStateException("finite entry is closed");
                },
                admitted, message -> { }, SESSION);
    }

    private void closeAll() throws IOException {
        if (core != null) core.close();
        if (runtime != null) runtime.close();
        if (journal != null) journal.close();
    }

    /** Commit a manufactured piece through the real journal + metadata fold. */
    private UUID mintPiece(ConstructionDeclaration declaration, List<BlockKey> cells) throws IOException {
        var request = new Request(UUID.randomUUID(), ACTOR, SESSION, JOURNAL_DOMAIN, 0, "ab".repeat(32));
        var prepared = metadata.prepareBuild(request, DIMENSION, false,
                List.of(new ManufacturedPiece.Plan(declaration, cells)));
        journal.create(Entry.prepared(prepared.withParticipants(List.of())));
        journal.decide(request.id(), Phase.COMMITTED, Reason.NONE);
        metadata.applyCommitted(journal, request.id());
        return prepared.created().get(0);
    }

    // ---------------------------------------------------------------- happy path

    @Test void bootstrapAdoptsBindsObservesAndCommits() throws Exception {
        openCore();
        UUID piece = mintPiece(STEEL, List.of(new BlockKey(1, 64, 1), new BlockKey(1, 65, 1)));
        fakeWorld.cells.put(new BlockKey(1, 64, 1), new FiniteObservation.Cell.Structural(STEEL));
        fakeWorld.cells.put(new BlockKey(1, 65, 1), new FiniteObservation.Cell.Structural(STEEL));
        fakeWorld.cells.put(new BlockKey(1, 63, 1), new FiniteObservation.Cell.Other());
        fakeWorld.sturdyFaces.add(new BlockKey(1, 63, 1));

        var outcome = core.bootstrap(DIMENSION);
        var committed = assertInstanceOf(FiniteEntryService.Bootstrap.Committed.class, outcome);
        assertEquals(0, committed.worldRevision());
        assertEquals(1, committed.boundPieces());
        assertEquals(1, committed.supports());
        assertEquals(Phase.COMMITTED, committed.receipt().phase());

        FiniteDomainId domain = committed.domain();
        var bindings = runtime.activeBindings(domain);
        assertEquals(1, bindings.size());
        assertEquals(1, bindings.get(0).ownerId());              // first minted owner is monotonic
        assertEquals(piece, bindings.get(0).pieceId());
        var source = runtime.source(domain).orElseThrow();
        assertEquals(3, source.blocks().size());                 // 2 owned + 1 support
        assertEquals(2, source.owners().size());
        assertEquals(1, source.blocks().stream().filter(b -> b.sect() == -1).count());
        assertEquals(1, laneOpens.get());                        // genesis minted once
        assertEquals(mintLanes.get(0).responses.get(0), source.vocabReplyJson());
        assertEquals(4, journal.entryIds().size());              // build + adopt + bind + commit
        assertEquals(FiniteRuntime.Observation.SOURCED, runtime.observation(DIMENSION));

        assertTrue(runtime.lane().awaitIdle(10_000));
        runtime.drainLaneEvents(16);
        var laneEvent = runtime.laneStatus(DIMENSION);
        assertNotNull(laneEvent);
        assertEquals(FiniteLaneWorker.Kind.SYNCED, laneEvent.kind());
        closeAll();
    }

    @Test void bootstrapIsIdempotentOnSecondRun() throws Exception {
        openCore();
        mintPiece(STEEL, List.of(new BlockKey(1, 64, 1)));
        fakeWorld.cells.put(new BlockKey(1, 64, 1), new FiniteObservation.Cell.Structural(STEEL));

        var first = assertInstanceOf(FiniteEntryService.Bootstrap.Committed.class,
                core.bootstrap(DIMENSION));
        var second = assertInstanceOf(FiniteEntryService.Bootstrap.Committed.class,
                core.bootstrap(DIMENSION));

        assertEquals(0, first.worldRevision());
        assertEquals(1, second.worldRevision());
        assertEquals(0, second.boundPieces());                   // no re-bind of the bound piece
        assertEquals(1, laneOpens.get());                        // durable vocabulary reused — no second mint
        assertEquals(1, runtime.activeBindings(second.domain()).size());
        assertEquals(5, journal.entryIds().size());              // + adopt? no — idempotent adopt, +1 commit
        assertEquals(FiniteRuntime.Observation.SOURCED, runtime.observation(DIMENSION));
        closeAll();
    }

    // ---------------------------------------------------------------- admission

    @Test void unadmittedDimensionRefused() throws Exception {
        openCore();
        long entries = journal.entryIds().size();
        var failure = assertThrows(IOException.class, () -> core.bootstrap("minecraft:the_nether"));
        assertTrue(failure.getMessage().contains("minecraft:the_nether"), failure.getMessage());
        assertEquals(entries, journal.entryIds().size());
        assertEquals(0, laneOpens.get());
        closeAll();
    }

    @Test void emptyAdmittedRefusesEverything() throws Exception {
        openCore(Set.of());
        long entries = journal.entryIds().size();
        assertThrows(IOException.class, () -> core.bootstrap(DIMENSION));
        assertEquals(entries, journal.entryIds().size());
        assertEquals(0, laneOpens.get());
        closeAll();
    }

    // ---------------------------------------------------------------- observation

    @Test void unloadedCellWaits() throws Exception {
        openCore();
        mintPiece(STEEL, List.of(new BlockKey(1, 64, 1)));
        fakeWorld.cells.put(new BlockKey(1, 64, 1), new FiniteObservation.Cell.Unloaded());

        var outcome = core.bootstrap(DIMENSION);
        var waiting = assertInstanceOf(FiniteEntryService.Bootstrap.Waiting.class, outcome);
        assertEquals(1, waiting.missingChunks());
        assertEquals(FiniteRuntime.Observation.WAITING_CHUNKS, runtime.observation(DIMENSION));
        assertEquals(3, journal.entryIds().size());              // build + adopt + bind, no commit
        assertEquals(0, laneOpens.get());                        // waiting opens no lane
        closeAll();
    }

    @Test void airCellDriftRefuses() throws Exception {
        openCore();
        mintPiece(STEEL, List.of(new BlockKey(1, 64, 1)));
        // the bound cell reads air — the world drifted from its durable claim
        var failure = assertThrows(IOException.class, () -> core.bootstrap(DIMENSION));
        assertTrue(failure.getMessage().contains("cell/1/64/1"), failure.getMessage());
        var domain = runtime.domain(DIMENSION).orElseThrow();
        assertTrue(runtime.root(domain.id()).isEmpty());       // no source was committed
        assertEquals(0, laneOpens.get());
        closeAll();
    }

    @Test void foreignBlockDriftRefuses() throws Exception {
        openCore();
        mintPiece(STEEL, List.of(new BlockKey(1, 64, 1)));
        fakeWorld.cells.put(new BlockKey(1, 64, 1), new FiniteObservation.Cell.Other());
        var failure = assertThrows(IOException.class, () -> core.bootstrap(DIMENSION));
        assertTrue(failure.getMessage().contains("cell/1/64/1"), failure.getMessage());
        assertEquals(0, laneOpens.get());
        closeAll();
    }

    @Test void declarationMismatchDriftRefuses() throws Exception {
        openCore();
        mintPiece(STEEL, List.of(new BlockKey(1, 64, 1)));
        fakeWorld.cells.put(new BlockKey(1, 64, 1), new FiniteObservation.Cell.Structural(TIMBER));
        var failure = assertThrows(IOException.class, () -> core.bootstrap(DIMENSION));
        assertTrue(failure.getMessage().contains("cell/1/64/1"), failure.getMessage());
        assertEquals(0, laneOpens.get());
        closeAll();
    }

    @Test void nonSturdyNeighbourIsNotASupport() throws Exception {
        openCore();
        mintPiece(STEEL, List.of(new BlockKey(1, 64, 1)));
        fakeWorld.cells.put(new BlockKey(1, 64, 1), new FiniteObservation.Cell.Structural(STEEL));
        fakeWorld.cells.put(new BlockKey(1, 63, 1), new FiniteObservation.Cell.Other());
        // (1,63,1) is not sturdy on the contact face — it must not become a support
        var committed = assertInstanceOf(FiniteEntryService.Bootstrap.Committed.class,
                core.bootstrap(DIMENSION));
        assertEquals(0, committed.supports());
        assertEquals(1, runtime.source(committed.domain()).orElseThrow().blocks().size());
        closeAll();
    }

    // ---------------------------------------------------------------- spec refusals

    @Test void panelMaterialRefusesBeforeAnyLane() throws Exception {
        openCore();
        mintPiece(PANEL, List.of(new BlockKey(1, 64, 1)));
        fakeWorld.cells.put(new BlockKey(1, 64, 1), new FiniteObservation.Cell.Structural(PANEL));
        var failure = assertThrows(IOException.class, () -> core.bootstrap(DIMENSION));
        assertTrue(failure.getMessage().contains("concrete_slab_200"), failure.getMessage());
        assertEquals(0, laneOpens.get());                        // role precheck precedes the mint lane
        closeAll();
    }

    @Test void noBoundCellsRefuses() throws Exception {
        openCore();
        var failure = assertThrows(IOException.class, () -> core.bootstrap(DIMENSION));
        assertTrue(failure.getMessage().contains("no bound cells"), failure.getMessage());
        assertEquals(0, laneOpens.get());
        closeAll();
    }

    /**
     * The wire-axis invariant is enforced one layer below the domain entry: a manufactured
     * piece whose declaration carries no wire axis is refused at {@code prepareBuild}
     * admission, so no axis-less piece can ever be minted into the journal — let alone
     * bound into a finite domain. {@code unboundPlans} keeps the same check defensively.
     */
    @Test void axislessPieceRefusedAtCommit() throws Exception {
        openCore();
        assertThrows(IllegalArgumentException.class,
                () -> mintPiece(AXILESS, List.of(new BlockKey(1, 64, 1))));
        assertEquals(0, journal.entryIds().size());              // nothing journaled
        assertEquals(0, laneOpens.get());
        closeAll();
    }

    // ---------------------------------------------------------------- lane refusals

    @Test void laneWithoutCorotRefuses() throws Exception {
        openCore();
        mintPiece(STEEL, List.of(new BlockKey(1, 64, 1)));
        fakeWorld.cells.put(new BlockKey(1, 64, 1), new FiniteObservation.Cell.Structural(STEEL));
        mintLanesWillBe((lane) -> lane.corot = false);
        var failure = assertThrows(IOException.class, () -> core.bootstrap(DIMENSION));
        assertTrue(failure.getMessage().contains("bsi.corot"), failure.getMessage());
        assertEquals(3, journal.entryIds().size());
        closeAll();
    }

    @Test void vocabRefusalRefuses() throws Exception {
        openCore();
        mintPiece(STEEL, List.of(new BlockKey(1, 64, 1)));
        fakeWorld.cells.put(new BlockKey(1, 64, 1), new FiniteObservation.Cell.Structural(STEEL));
        mintLanesWillBe((lane) -> lane.refuseVocab = true);
        assertThrows(IOException.class, () -> core.bootstrap(DIMENSION));
        assertEquals(3, journal.entryIds().size());
        closeAll();
    }

    /** Install a mutator applied to every mint lane the lane source opens. */
    private void mintLanesWillBe(java.util.function.Consumer<ScriptedEntryLane> mutator) {
        var mutating = new ForgeFiniteFractureHost.LaneSource() {
            @Override public ForgeFiniteFractureHost.OpenedLane open() throws Exception {
                laneOpens.incrementAndGet();
                var lane = new ScriptedEntryLane();
                mutator.accept(lane);
                mintLanes.add(lane);
                return new ForgeFiniteFractureHost.OpenedLane(lane, () -> { }, "engine/test",
                        "cd".repeat(32));
            }
        };
        core.close();
        core = new FiniteEntryService.Core(runtime, dimension -> fakeWorld, mutating,
                () -> {
                    if (!onServerThread.get()) throw new IllegalStateException("requires the server thread");
                    if (!entryOpen.get()) throw new IllegalStateException("finite entry is closed");
                },
                Set.of(DIMENSION), message -> { }, SESSION);
    }

    // ---------------------------------------------------------------- guards + threads

    @Test void nonServerThreadRefused() throws Exception {
        openCore();
        long entries = journal.entryIds().size();
        onServerThread.set(false);
        assertThrows(IllegalStateException.class, () -> core.bootstrap(DIMENSION));
        onServerThread.set(true);
        assertEquals(entries, journal.entryIds().size());
        assertEquals(0, laneOpens.get());
        closeAll();
    }

    @Test void closedServiceRefuses() throws Exception {
        openCore();
        core.close();
        assertThrows(IllegalStateException.class, () -> core.bootstrap(DIMENSION));
        assertEquals(0, laneOpens.get());
        closeAll();
    }

    @Test void mintRunsOnPrivateLaneThread() throws Exception {
        openCore();
        mintPiece(STEEL, List.of(new BlockKey(1, 64, 1)));
        fakeWorld.cells.put(new BlockKey(1, 64, 1), new FiniteObservation.Cell.Structural(STEEL));
        assertInstanceOf(FiniteEntryService.Bootstrap.Committed.class, core.bootstrap(DIMENSION));
        assertTrue(runtime.lane().awaitIdle(10_000));
        runtime.drainLaneEvents(16);

        String caller = Thread.currentThread().getName();
        assertEquals(1, mintLanes.size());
        assertFalse(mintLanes.get(0).threads.isEmpty());
        for (String thread : mintLanes.get(0).threads) {
            assertTrue(thread.startsWith("br-entry-lane-"), "mint call ran on " + thread);
            assertNotEquals(caller, thread);
        }
        for (String thread : authorityLane.threads) {
            assertTrue(thread.startsWith("br-finite-lane-"), "authority call ran on " + thread);
            assertNotEquals(caller, thread);
        }
        closeAll();
    }

    // ---------------------------------------------------------------- status

    @Test void statusReportsDomainState() throws Exception {
        openCore();
        var empty = core.status(DIMENSION);
        assertTrue(empty.admitted());
        assertFalse(empty.adopted());
        assertEquals(0, empty.bindings());
        assertEquals(-1, empty.worldRevision());

        mintPiece(STEEL, List.of(new BlockKey(1, 64, 1)));
        fakeWorld.cells.put(new BlockKey(1, 64, 1), new FiniteObservation.Cell.Structural(STEEL));
        assertInstanceOf(FiniteEntryService.Bootstrap.Committed.class, core.bootstrap(DIMENSION));
        assertTrue(runtime.lane().awaitIdle(10_000));
        runtime.drainLaneEvents(16);

        var sourced = core.status(DIMENSION);
        assertTrue(sourced.adopted());
        assertEquals(FiniteRuntime.Observation.SOURCED, sourced.observation());
        assertEquals(1, sourced.bindings());
        assertEquals(1, sourced.boundCells());
        assertEquals(0, sourced.worldRevision());
        assertEquals(0, sourced.historySequence());
        assertEquals(1, sourced.sourceBlocks());
        assertNotNull(sourced.laneEvent());
        assertEquals(FiniteLaneWorker.Kind.SYNCED, sourced.laneEvent().kind());
        closeAll();
    }

    // ---------------------------------------------------------------- fracture entry

    @Test void fractureRequiresAdoptedDomain() throws Exception {
        openCore();
        var failure = assertThrows(IOException.class, () -> core.requireSourcedDomain(DIMENSION));
        assertTrue(failure.getMessage().contains("no adopted finite domain"));
        closeAll();
    }

    @Test void fractureRequiresCommittedSource() throws Exception {
        openCore();
        runtime.adoptDomain(DIMENSION);                     // adopted, never committed
        var failure = assertThrows(IOException.class, () -> core.requireSourcedDomain(DIMENSION));
        assertTrue(failure.getMessage().contains("no committed source"));
        closeAll();
    }

    @Test void fractureMaterialIdDecodesCommittedVocabulary() throws Exception {
        openCore();
        mintPiece(STEEL, List.of(new BlockKey(1, 64, 1)));
        fakeWorld.cells.put(new BlockKey(1, 64, 1), new FiniteObservation.Cell.Structural(STEEL));
        var committed = assertInstanceOf(FiniteEntryService.Bootstrap.Committed.class,
                core.bootstrap(DIMENSION));
        // Ids are engine-assigned — the durable vocabulary reply is the only authority.
        assertEquals(0, core.fractureMaterialId(committed.domain(), "steel"));
        assertEquals(8, core.fractureMaterialId(committed.domain(), "ground_rigid"));
        var failure = assertThrows(IOException.class,
                () -> core.fractureMaterialId(committed.domain(), "obtainium"));
        assertTrue(failure.getMessage().contains("not in the durable vocabulary"));
        closeAll();
    }
}
