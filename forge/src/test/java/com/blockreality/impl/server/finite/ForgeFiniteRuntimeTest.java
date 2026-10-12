package com.blockreality.impl.server.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.BsiCorot;
import com.blockreality.core.bsi.BsiFrame;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiResponse;
import com.blockreality.core.bsi.BsiVocabulary;
import com.blockreality.core.finite.ArtifactBinding;
import com.blockreality.core.finite.FiniteBasis;
import com.blockreality.core.finite.FiniteCodec;
import com.blockreality.core.finite.FiniteCodec.Operation;
import com.blockreality.core.finite.FiniteDomainId;
import com.blockreality.core.finite.FiniteRegistry;
import com.blockreality.core.finite.FiniteRoot;
import com.blockreality.core.finite.FiniteSource;
import com.blockreality.core.finite.FiniteStore;
import com.blockreality.core.finite.FiniteLane;
import com.blockreality.core.json.JsonValue;
import com.blockreality.core.transaction.AtomicConstructionCoordinator;
import com.blockreality.core.transaction.ConstructionTransaction.Change;
import com.blockreality.core.transaction.ConstructionTransaction.Entry;
import com.blockreality.core.transaction.ConstructionTransaction.Intent;
import com.blockreality.core.transaction.ConstructionTransaction.Phase;
import com.blockreality.core.transaction.ConstructionTransaction.Reason;
import com.blockreality.core.transaction.ConstructionTransaction.Receipt;
import com.blockreality.core.transaction.ConstructionTransaction.Request;
import com.blockreality.core.transaction.ConstructionTransaction.Value;
import com.blockreality.core.transaction.FileTransactionJournal;
import com.blockreality.core.transaction.ManufacturedPiece;
import com.blockreality.core.transaction.ManufacturedRegistry;
import com.blockreality.core.world.ConstructionDeclaration;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-W4B0 gate evidence: the Forge finite runtime over the shared construction journal —
 * transaction bridging, domain adoption, artifact binding, recovery routing, replay rules,
 * the scripted-lane worker and source assembly. No MinecraftServer is needed: the runtime
 * is MC-free and the service wiring is verified by file assertions (A2/A4).
 */
class ForgeFiniteRuntimeTest {

    private static final UUID JOURNAL_DOMAIN = new UUID(7, 7), SESSION = new UUID(9, 9);
    private static final String DIMENSION = "minecraft:overworld";
    private static final ConstructionDeclaration STEEL =
            new ConstructionDeclaration("steel", "steel_rect_200x400", 1);

    private static final String VOCAB_REQUEST = """
            {"version":1,
             "materials":[
               {"name":"steel","role":"member","model":"isotropic","E":2.0e11,"nu":0.3,"rho":7850,
                "allow":{"sigmaC":2.5e8,"sigmaT":2.5e8,"tau":1.45e8},"defaultSection":"steel_rect_200x400"},
               {"name":"ground_rigid","role":"support","supportKind":"fixAll"}],
             "sections":[{"name":"steel_rect_200x400","kind":"rect","p":[0.2,0.4]}]}""";

    private static String vocabReply(String id, long revision) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id + "\",\"method\":\"bsi.vocab.declare\","
                + "\"revision\":" + revision + ",\"status\":\"ok\",\"version\":1,"
                + "\"materials\":[{\"id\":0,\"name\":\"steel\"},{\"id\":1,\"name\":\"ground_rigid\"}],"
                + "\"sections\":[{\"id\":0,\"name\":\"steel_rect_200x400\"}]}";
    }

    private static String worldOk(String id, UUID domain, long revision, UUID ns) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id + "\",\"method\":\"bsi.world.declare\","
                + "\"revision\":" + revision + ",\"status\":\"ok\",\"identity\":{\"domain\":\""
                + BsiFracture.hex(domain) + "\",\"revision\":" + revision
                + ",\"artifactNamespace\":\"" + BsiFracture.hex(ns) + "\"}}";
    }

    /** A scripted {@link FiniteLane} that answers declare calls and records its calling thread. */
    private static final class ScriptedLane implements FiniteLane {
        record Call(String method, String header, byte[] payload) { }
        final List<Call> calls = new ArrayList<>();
        final List<String> threads = new ArrayList<>();
        volatile CountDownLatch gate;
        private BsiFracture.World pendingWorld;

        private void park() {
            CountDownLatch latch = gate;
            if (latch != null) {
                try { latch.await(30, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        @Override public BsiResponse request(String headerJson, byte[] payload) {
            threads.add(Thread.currentThread().getName());
            park();
            var header = JsonValue.parse(headerJson);
            String method = header.str("method", "");
            calls.add(new Call(method, headerJson, payload == null ? new byte[0] : payload));
            String id = header.str("id", "");
            if (method.equals("bsi.vocab.declare"))
                return respond(vocabReply(id, header.exactI64("revision")));
            if (method.equals("bsi.world.declare")) {
                var world = pendingWorld;
                assertNotNull(world, "world declare with no pending world");
                return respond(worldOk(id, world.stamp().domain(), world.stamp().revision(),
                        world.artifactNamespace()));
            }
            return respond("{\"bsi\":1,\"kind\":\"error\",\"id\":\"" + id + "\",\"method\":\"" + method
                    + "\",\"revision\":" + header.exactI64("revision") + ",\"code\":\"NOOP\"}", new byte[0]);
        }

        @Override public boolean adoptVocabulary(BsiVocabulary vocabulary) { return true; }

        @Override public boolean declareFiniteWorld(BsiFracture.World world) {
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

        @Override public boolean ready() { return true; }

        static BsiResponse respond(String header) { return respond(header, new byte[0]); }

        static BsiResponse respond(String header, byte[] payload) {
            return BsiResponse.of(new BsiFrame.Decoded(0, header, payload));
        }
    }

    @TempDir Path dir;

    private Path journalDir;
    private FileTransactionJournal journal;
    private ManufacturedRegistry metadata;
    private AtomicConstructionCoordinator coordinator;
    private FiniteRuntime runtime;
    private ScriptedLane lane;
    private final AtomicBoolean onServerThread = new AtomicBoolean(true);
    private final AtomicBoolean entryOpen = new AtomicBoolean(true);

    private void openRuntime() throws IOException {
        journalDir = dir.resolve("blockreality/construction");
        journal = new FileTransactionJournal(journalDir, JOURNAL_DOMAIN);
        metadata = ManufacturedRegistry.load(journal);
        coordinator = new AtomicConstructionCoordinator(journal);
        lane = new ScriptedLane();
        runtime = FiniteRuntime.open(journal, journalDir, coordinator, metadata, SESSION,
                () -> new FiniteLaneWorker.Lane(lane, () -> { }),
                () -> { if (!onServerThread.get()) throw new IllegalStateException("requires the server thread"); },
                () -> {
                    if (!onServerThread.get()) throw new IllegalStateException("requires the server thread");
                    if (!entryOpen.get()) throw new IllegalStateException("finite entry is closed");
                },
                Set.of(DIMENSION), message -> { });
        recoverRouting();
        runtime.markReady();
    }

    /** The same routing ConstructionService performs: finite pending entries get a finite host. */
    private void recoverRouting() throws IOException {
        Optional<UUID> pending = journal.pending();
        Entry entry = pending.isPresent()
                ? journal.verify(pending.get()).orElseThrow(() -> new IOException("pending vanished"))
                : null;
        AtomicConstructionCoordinator.Host routed = runtime.recoveryHost(entry);
        coordinator.recover(routed != null ? routed : bootstrapHost());
    }

    /** Empty-journal bootstrap host (the construction host's role when nothing is pending). */
    private static AtomicConstructionCoordinator.Host bootstrapHost() {
        return new AtomicConstructionCoordinator.Host() {
            @Override public void checkAccess() { }
            @Override public long revision() { return 0; }
            @Override public Value read(String resource) { return Value.missing(); }
            @Override public void checkpoint(List<String> resources) { }
            @Override public void write(String resource, Value value) { }
            @Override public void flush(List<String> resources) { }
            @Override public void publish(Receipt receipt) { }
            @Override public void publishRecoveredState() { }
        };
    }

    private void closeAll() throws IOException {
        if (runtime != null) runtime.close();
        if (journal != null) journal.close();
    }

    private static FiniteDomainSource.Spec spec() {
        var options = new BsiCorot.Options(new UUID(0, 0), 0, 1.0, new double[]{0, -9.81, 0}, 5,
                1e-4, 1e-3, 1e-3, 1.0, 1e-6, 1.0, 1e-8, 1e-4, 1 << 20, 0,
                50, 4, 8, 25, 1, 4096, 8, 2, 8);
        return new FiniteDomainSource.Spec(VOCAB_REQUEST, vocabReply("r1", 0), "ground_rigid",
                new byte[152], new byte[0], new byte[0], new byte[0], new byte[0], new byte[0],
                options);
    }

    private static Receipt decided(FiniteRuntime.CommitOutcome outcome) {
        return ((FiniteRuntime.CommitOutcome.Decided) outcome).receipt();
    }

    // ---------------------------------------------------------------- A. wiring

    @Test void storeLocationAdjacentToJournal() throws Exception {
        openRuntime();
        assertEquals(journalDir.resolveSibling("finite"), runtime.store().root());
        closeAll();
    }

    // ---------------------------------------------------------------- B. transaction bridge

    @Test void adoptionCommitsFiniteIntent() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        assertEquals(JOURNAL_DOMAIN, domain.journalDomain());
        assertEquals(DIMENSION, domain.dimension());
        assertNotEquals(new UUID(0, 0), domain.bsiDomain());
        assertNotEquals(new UUID(0, 0), domain.artifactNamespace());
        // The durable record is a construction journal entry with finite participants.
        UUID tx = null;
        for (UUID id : journal.entryIds()) {
            Entry entry = journal.read(id).orElseThrow();
            if (entry.intent() == null) continue;
            boolean finite = entry.intent().changes().stream()
                    .anyMatch(c -> c.resource().startsWith("finite/"));
            if (finite) { assertNull(tx); tx = id; }
        }
        assertNotNull(tx);
        Entry entry = journal.verify(tx).orElseThrow();
        assertEquals(Phase.COMMITTED, entry.phase());
        var resources = entry.intent().changes().stream().map(Change::resource).toList();
        assertTrue(resources.contains("finite/order"), resources::toString);
        assertTrue(resources.stream().anyMatch(r -> r.startsWith("finite/tx/")), resources::toString);
        assertTrue(resources.stream().anyMatch(r -> r.startsWith("finite/domain/")), resources::toString);
        assertEquals(1, runtime.order());
        // Idempotent: a second adoption of the same dimension returns the same identity.
        assertEquals(domain, runtime.adoptDomain(DIMENSION));
        assertEquals(1, runtime.order());
        closeAll();
    }

    @Test void bindMintsMonotonicOwners() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        var plans = List.of(
                Operation.BindingPlan.manufactured(new UUID(70, 1), STEEL,
                        List.of(new BlockKey(1, 64, 1), new BlockKey(1, 65, 1))),
                Operation.BindingPlan.manufactured(new UUID(70, 2), STEEL,
                        List.of(new BlockKey(2, 64, 1))),
                Operation.BindingPlan.legacy("legacy/observed/0", STEEL,
                        List.of(new BlockKey(9, 64, 9))));
        Receipt receipt = runtime.bindArtifacts(domain, plans);
        assertEquals(Phase.COMMITTED, receipt.phase());
        var ns = domain.artifactNamespace();
        var first = runtime.binding(ns, 1).orElseThrow();
        var second = runtime.binding(ns, 2).orElseThrow();
        var adopted = runtime.binding(ns, 3).orElseThrow();
        assertEquals(new UUID(70, 1), first.pieceId());
        assertEquals(new UUID(70, 2), second.pieceId());
        assertNotEquals(first.ownerId(), second.ownerId());          // adjacency never merges owners
        assertEquals(ArtifactBinding.Origin.LEGACY_NO_REFUND, adopted.origin());
        assertEquals("legacy/observed/0", adopted.legacyKey());
        assertEquals(4, runtime.domain(DIMENSION).orElseThrow().nextOwnerId());
        assertEquals(3, runtime.bindingsBornBy(domain, receipt.request().id()).size());
        assertEquals(3, runtime.activeBindings(domain).size());
        closeAll();
    }

    @Test void retiredOwnerIdNeverReused() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        runtime.bindArtifacts(domain, List.of(Operation.BindingPlan.manufactured(
                new UUID(70, 1), STEEL, List.of(new BlockKey(1, 64, 1)))));
        Receipt retired = runtime.retireArtifacts(domain, Set.of(1L));
        assertEquals(Phase.COMMITTED, retired.phase());
        assertEquals(ArtifactBinding.Disposition.RETIRED,
                runtime.binding(domain.artifactNamespace(), 1).orElseThrow().disposition());
        runtime.bindArtifacts(domain, List.of(Operation.BindingPlan.manufactured(
                new UUID(70, 2), STEEL, List.of(new BlockKey(2, 64, 1)))));
        assertTrue(runtime.binding(domain.artifactNamespace(), 2).isPresent()); // never a recycled 1
        closeAll();
    }

    @Test void replaySameRequestReusesReceipt() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        var plans = List.of(Operation.BindingPlan.manufactured(new UUID(70, 1), STEEL,
                List.of(new BlockKey(1, 64, 1))));
        Receipt first = runtime.bindArtifacts(domain, plans);
        Request request = first.request();
        long entries = journal.entryIds().size();
        // The identical request is a replay: the stored receipt is returned without re-deciding.
        Receipt replay = runtime.submit(request,
                req -> runtime.registry().prepareBind(req, domain, plans));
        assertEquals(first, replay);
        assertEquals(entries, journal.entryIds().size());
        assertEquals(1, runtime.activeBindings(domain).size());
        closeAll();
    }

    @Test void replayDifferentInputsRejected() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        Receipt first = runtime.bindArtifacts(domain, List.of(Operation.BindingPlan.manufactured(
                new UUID(70, 1), STEEL, List.of(new BlockKey(1, 64, 1)))));
        // Same request id, different effective input: the binding hash no longer matches.
        var foreign = new Operation.Retire(Set.of(1L));
        var reused = new Request(first.request().id(), first.request().actor(), first.request().session(),
                first.request().domain(), first.request().baseRevision(), FiniteRegistry.planHash(foreign));
        assertThrows(IOException.class, () -> runtime.submit(reused,
                req -> runtime.registry().prepareRetire(req, domain, Set.of(1L))));
        // The committed record is untouched.
        Entry committed = journal.verify(first.request().id()).orElseThrow();
        assertEquals(Phase.COMMITTED, committed.phase());
        assertEquals(first.request(), committed.request());
        closeAll();
    }

    @Test void pendingFiniteRecordRecovers() throws Exception {
        openRuntime();
        // Leave a PREPARED finite record in the journal — a crash between write and decision.
        var op = new Operation.CreateDomain(DIMENSION);
        var request = new Request(new UUID(20, 1), new UUID(8, 8), SESSION, JOURNAL_DOMAIN, 0,
                FiniteRegistry.planHash(op));
        var prepared = runtime.registry().prepareCreateDomain(request, DIMENSION);
        journal.create(Entry.prepared(prepared.intent()));
        // Startup routing: the runtime claims the pending entry, the coordinator aborts it.
        Entry pending = journal.verify(journal.pending().orElseThrow()).orElseThrow();
        var host = runtime.recoveryHost(pending);
        assertNotNull(host);
        coordinator.recover(host);
        Entry decided = journal.verify(request.id()).orElseThrow();
        assertEquals(Phase.ABORTED, decided.phase());
        assertEquals(Reason.RECOVERED, decided.reason());
        assertTrue(runtime.domain(DIMENSION).isEmpty());             // fold confers nothing
        assertEquals(0, runtime.order());
        closeAll();
    }

    @Test void manufacturedPendingNotClaimed() throws Exception {
        openRuntime();
        // A construction (non-finite) pending entry is left for the construction host.
        var request = new Request(new UUID(30, 1), new UUID(8, 8), SESSION, JOURNAL_DOMAIN, 0,
                "ab".repeat(32));
        var prepared = metadata.prepareBuild(request, DIMENSION, false,
                List.of(new ManufacturedPiece.Plan(STEEL, List.of(new BlockKey(3, 64, 3)))));
        journal.create(Entry.prepared(prepared.withParticipants(List.of())));
        Entry pending = journal.verify(journal.pending().orElseThrow()).orElseThrow();
        assertNull(runtime.recoveryHost(pending));
        journal.decide(request.id(), Phase.ABORTED, Reason.RECOVERED);
        closeAll();
    }

    @Test void poisonedRegistryRefusesFurtherOps() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        // Craft a COMMITTED entry whose binding image is malformed: publication poisons the fold.
        var plans = List.of(Operation.BindingPlan.manufactured(new UUID(70, 1), STEEL,
                List.of(new BlockKey(1, 64, 1))));
        var op = new Operation.Bind(plans);
        var request = new Request(new UUID(20, 2), new UUID(8, 8), SESSION, JOURNAL_DOMAIN,
                runtime.order(), FiniteRegistry.planHash(op));
        var prepared = runtime.registry().prepareBind(request, domain, plans);
        var tampered = new ArrayList<Change>();
        for (Change change : prepared.changes()) {
            if (change.resource().startsWith("finite/owner/"))
                tampered.add(new Change(change.resource(), change.before(), Value.of(new byte[]{7, 7})));
            else tampered.add(change);
        }
        journal.create(Entry.prepared(new Intent(request, tampered, List.of(), List.of())));
        journal.decide(request.id(), Phase.COMMITTED, Reason.NONE);
        var broken = journal.verify(request.id()).orElseThrow().receipt();
        assertThrows(IOException.class, () -> runtime.committed(broken));
        assertFalse(runtime.failure().isEmpty());
        assertFalse(runtime.available());
        var adopt = new Operation.CreateDomain("minecraft:the_nether");
        assertThrows(IllegalStateException.class, () -> runtime.submit(
                new Request(new UUID(20, 3), new UUID(8, 8), SESSION, JOURNAL_DOMAIN,
                        runtime.order(), FiniteRegistry.planHash(adopt)),
                req -> runtime.registry().prepareCreateDomain(req, "minecraft:the_nether")));
        closeAll();
    }

    // ---------------------------------------------------------------- C. native lane worker

    @Test void laneCallsRunOnWorkerThread() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        runtime.bindArtifacts(domain, List.of(Operation.BindingPlan.manufactured(
                new UUID(70, 1), STEEL, List.of(new BlockKey(1, 64, 1)))));
        var outcome = runtime.commitSource(domain, spec(),
                FiniteRuntime.DomainObservation.observed(Set.of(new BlockKey(1, 63, 1))));
        assertEquals(Phase.COMMITTED, decided(outcome).phase());
        assertTrue(runtime.lane().awaitIdle(10_000));
        assertFalse(lane.threads.isEmpty());
        for (String thread : lane.threads)
            assertTrue(thread.startsWith("br-finite-lane"), thread);
        closeAll();
    }

    @Test void commitDrivesSessionSync() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        runtime.bindArtifacts(domain, List.of(Operation.BindingPlan.manufactured(
                new UUID(70, 1), STEEL, List.of(new BlockKey(1, 64, 1)))));
        var outcome = runtime.commitSource(domain, spec(),
                FiniteRuntime.DomainObservation.observed(Set.of(new BlockKey(1, 63, 1))));
        assertEquals(Phase.COMMITTED, decided(outcome).phase());
        assertTrue(runtime.lane().awaitIdle(10_000));
        var methods = lane.calls.stream().map(ScriptedLane.Call::method).toList();
        assertEquals(List.of("bsi.vocab.declare", "bsi.world.declare"), methods);
        runtime.drainLaneEvents(16);
        var status = runtime.laneStatus(DIMENSION);
        assertNotNull(status);
        assertEquals(FiniteLaneWorker.Kind.SYNCED, status.kind());
        assertEquals(0, status.basis().worldRevision());
        closeAll();
    }

    @Test void submitNeverBlocksCaller() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        runtime.bindArtifacts(domain, List.of(Operation.BindingPlan.manufactured(
                new UUID(70, 1), STEEL, List.of(new BlockKey(1, 64, 1)))));
        lane.gate = new CountDownLatch(1);                          // native peer parked
        long started = System.nanoTime();
        var outcome = runtime.commitSource(domain, spec(),
                FiniteRuntime.DomainObservation.observed(Set.of(new BlockKey(1, 63, 1))));
        long elapsed = System.nanoTime() - started;
        assertEquals(Phase.COMMITTED, decided(outcome).phase());    // journal decision is synchronous
        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(5), "caller joined a parked worker");
        lane.gate.countDown();
        assertTrue(runtime.lane().awaitIdle(10_000));
        closeAll();
    }

    @Test void staleGenerationDropped() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        runtime.bindArtifacts(domain, List.of(Operation.BindingPlan.manufactured(
                new UUID(70, 1), STEEL, List.of(new BlockKey(1, 64, 1)))));
        runtime.commitSource(domain, spec(),
                FiniteRuntime.DomainObservation.observed(Set.of(new BlockKey(1, 63, 1))));
        assertTrue(runtime.lane().awaitIdle(10_000));
        runtime.drainLaneEvents(16);
        runtime.reset();                                            // revoke every live session
        // A sync stamped with the revoked generation never reaches the engine.
        int callsBefore = lane.calls.size();
        runtime.lane().sync(new FiniteLaneWorker.Sync(domain, null, null, null, null, 0));
        assertTrue(runtime.lane().awaitIdle(10_000));
        runtime.drainLaneEvents(16);
        var status = runtime.laneStatus(DIMENSION);
        assertNotNull(status);
        assertEquals(FiniteLaneWorker.Kind.STALE, status.kind());
        assertEquals(callsBefore, lane.calls.size());                // no native call survived
        closeAll();
    }

    @Test void basisReflectsCommittedRoot() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        assertTrue(runtime.basis(DIMENSION).isEmpty());              // no committed root yet
        runtime.bindArtifacts(domain, List.of(Operation.BindingPlan.manufactured(
                new UUID(70, 1), STEEL, List.of(new BlockKey(1, 64, 1)))));
        var outcome = runtime.commitSource(domain, spec(),
                FiniteRuntime.DomainObservation.observed(Set.of(new BlockKey(1, 63, 1))));
        assertEquals(Phase.COMMITTED, decided(outcome).phase());
        FiniteBasis basis = runtime.basis(DIMENSION).orElseThrow();
        assertEquals(domain, basis.domain());
        assertEquals(0, basis.worldRevision());
        assertEquals(0, basis.historySequence());
        assertEquals(runtime.sessionGeneration(), basis.sessionGeneration());
        assertEquals(64, basis.sourceHash().length());
        closeAll();
    }

    // ---------------------------------------------------------------- D. source assembly

    @Test void sourceCoversOwnersAndSupports() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        runtime.bindArtifacts(domain, List.of(
                Operation.BindingPlan.manufactured(new UUID(70, 1), STEEL,
                        List.of(new BlockKey(1, 64, 1), new BlockKey(1, 65, 1))),
                Operation.BindingPlan.manufactured(new UUID(70, 2), STEEL,
                        List.of(new BlockKey(2, 64, 1)))));
        var bindings = runtime.activeBindings(domain);
        var supports = Set.of(new BlockKey(1, 63, 1), new BlockKey(2, 63, 1));
        FiniteSource source = FiniteDomainSource.build(domain, bindings, supports, spec(), 0);
        // Every structural cell is owned exactly once; supports are blocks, never owners.
        var owned = new java.util.HashSet<BlockKey>();
        var buf = java.nio.ByteBuffer.wrap(source.owners20()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        while (buf.hasRemaining()) {
            owned.add(new BlockKey(buf.getInt(), buf.getInt(), buf.getInt()));
            buf.getLong();
        }
        assertEquals(Set.of(new BlockKey(1, 64, 1), new BlockKey(1, 65, 1), new BlockKey(2, 64, 1)), owned);
        var decoded = FiniteSource.decode(source.serialize());
        assertEquals(5, decoded.blocks().size());
        closeAll();
    }

    @Test void sourceCanonicalOrderStable() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        runtime.bindArtifacts(domain, List.of(
                Operation.BindingPlan.manufactured(new UUID(70, 1), STEEL,
                        List.of(new BlockKey(1, 64, 1), new BlockKey(1, 65, 1))),
                Operation.BindingPlan.manufactured(new UUID(70, 2), STEEL,
                        List.of(new BlockKey(2, 64, 1)))));
        var forward = runtime.activeBindings(domain);
        var reversed = new ArrayList<>(forward);
        java.util.Collections.reverse(reversed);
        FiniteSource a = FiniteDomainSource.build(domain, forward,
                new java.util.LinkedHashSet<>(List.of(new BlockKey(1, 63, 1), new BlockKey(2, 63, 1))), spec(), 0);
        FiniteSource b = FiniteDomainSource.build(domain, reversed,
                new java.util.LinkedHashSet<>(List.of(new BlockKey(1, 63, 1), new BlockKey(2, 63, 1))), spec(), 0);
        assertArrayEquals(a.serialize(), b.serialize());
        closeAll();
    }

    @Test void optionsRoundTrip() {
        var domain = new UUID(42, 43);
        byte[] record = FiniteDomainSource.options176(domain, 9, spec().options());
        var decoded = BsiCorot.options(record);
        assertEquals(domain, decoded.expectedDomain());
        assertEquals(9, decoded.expectedRevision());
        assertTrue(decoded.retainLoadPath());                        // forced on by the builder
        assertEquals(1.0, decoded.loadFactor());
        assertEquals(-9.81, decoded.gravity()[1]);
    }

    @Test void sourceRequiresMaterials() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        runtime.bindArtifacts(domain, List.of(Operation.BindingPlan.manufactured(
                new UUID(70, 1), STEEL, List.of(new BlockKey(1, 64, 1)))));
        var empty = new FiniteDomainSource.Spec(VOCAB_REQUEST, vocabReply("r1", 0), "ground_rigid",
                new byte[0], new byte[0], new byte[0], new byte[0], new byte[0], new byte[0],
                spec().options());
        assertThrows(IllegalArgumentException.class, () -> FiniteDomainSource.build(domain,
                runtime.activeBindings(domain), Set.of(new BlockKey(1, 63, 1)), empty, 0));
        closeAll();
    }

    @Test void incompleteObservationWaits() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        long entries = journal.entryIds().size();
        var outcome = runtime.commitSource(domain, spec(), FiniteRuntime.DomainObservation.waiting(3));
        assertInstanceOf(FiniteRuntime.CommitOutcome.Waiting.class, outcome);
        assertEquals(3, ((FiniteRuntime.CommitOutcome.Waiting) outcome).missingChunks());
        assertEquals(FiniteRuntime.Observation.WAITING_CHUNKS, runtime.observation(DIMENSION));
        assertEquals(entries, journal.entryIds().size());            // nothing journaled
        assertTrue(runtime.root(domain).isEmpty());
        closeAll();
    }

    // ---------------------------------------------------------------- E. lifecycle/recovery

    @Test void restartFoldIdentical() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        runtime.bindArtifacts(domain, List.of(Operation.BindingPlan.manufactured(
                new UUID(70, 1), STEEL, List.of(new BlockKey(1, 64, 1)))));
        runtime.commitSource(domain, spec(),
                FiniteRuntime.DomainObservation.observed(Set.of(new BlockKey(1, 63, 1))));
        assertTrue(runtime.lane().awaitIdle(10_000));
        closeAll();
        // Reopen the same directories: the fold is byte-identical authority.
        journal = new FileTransactionJournal(journalDir, JOURNAL_DOMAIN);
        metadata = ManufacturedRegistry.load(journal);
        coordinator = new AtomicConstructionCoordinator(journal);
        lane = new ScriptedLane();
        runtime = FiniteRuntime.open(journal, journalDir, coordinator, metadata, SESSION,
                () -> new FiniteLaneWorker.Lane(lane, () -> { }),
                () -> { }, () -> { }, Set.of(DIMENSION), message -> { });
        recoverRouting();
        var folded = runtime.domain(DIMENSION).orElseThrow();
        assertEquals(domain, folded.id());
        assertEquals(2, folded.nextOwnerId());
        assertEquals(0, runtime.root(domain).orElseThrow().worldRevision());
        assertEquals(3, runtime.order());
        runtime.markReady();
        runtime.syncAll();
        assertTrue(runtime.lane().awaitIdle(10_000));
        runtime.drainLaneEvents(16);
        assertEquals(FiniteLaneWorker.Kind.SYNCED, runtime.laneStatus(DIMENSION).kind());
        closeAll();
    }

    @Test void closedServiceRefuses() throws Exception {
        openRuntime();
        runtime.close();
        entryOpen.set(false);                                       // the service-level gate too
        assertFalse(runtime.available());
        assertTrue(runtime.closed());
        assertThrows(IllegalStateException.class, () -> runtime.adoptDomain("minecraft:the_nether"));
        assertThrows(IllegalStateException.class, () -> runtime.adoptDomain(DIMENSION));
        closeAll();
    }

    @Test void tamperedFiniteOrderRejected() throws Exception {
        openRuntime();
        var op = new Operation.CreateDomain(DIMENSION);
        var request = new Request(new UUID(20, 4), new UUID(8, 8), SESSION, JOURNAL_DOMAIN, 0,
                FiniteRegistry.planHash(op));
        var prepared = runtime.registry().prepareCreateDomain(request, DIMENSION);
        // Forge a COMMITTED entry whose finite/order after-image skips the chain.
        var tampered = new ArrayList<Change>();
        for (Change change : prepared.changes()) {
            if (change.resource().equals("finite/order"))
                tampered.add(new Change(change.resource(), change.before(),
                        Value.of(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(5).array())));
            else tampered.add(change);
        }
        journal.create(Entry.prepared(new Intent(request, tampered, List.of(), List.of())));
        journal.decide(request.id(), Phase.COMMITTED, Reason.NONE);
        assertThrows(IOException.class, () -> FiniteRegistry.load(journal, runtime.store()));
        closeAll();
    }

    @Test void missingSourceBlobFailsClosed() throws Exception {
        openRuntime();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        runtime.bindArtifacts(domain, List.of(Operation.BindingPlan.manufactured(
                new UUID(70, 1), STEEL, List.of(new BlockKey(1, 64, 1)))));
        runtime.commitSource(domain, spec(),
                FiniteRuntime.DomainObservation.observed(Set.of(new BlockKey(1, 63, 1))));
        assertTrue(runtime.lane().awaitIdle(10_000));
        String sha = runtime.root(domain).orElseThrow().source().sha256();
        Files.delete(runtime.store().root().resolve("objects").resolve(sha + ".blob"));
        assertThrows(IOException.class, () -> runtime.source(domain));
        closeAll();
    }

    @Test void nonServerThreadRefused() throws Exception {
        openRuntime();
        long entries = journal.entryIds().size();
        onServerThread.set(false);
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try { runtime.adoptDomain(DIMENSION); }
            catch (Throwable problem) { failure.set(problem); }
        });
        thread.start();
        thread.join(10_000);
        onServerThread.set(true);
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals(entries, journal.entryIds().size());            // nothing journaled
        closeAll();
    }
}
