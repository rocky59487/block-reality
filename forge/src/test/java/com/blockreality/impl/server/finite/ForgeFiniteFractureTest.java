package com.blockreality.impl.server.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.BsiCorotFracture;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiResponse;
import com.blockreality.core.finite.FiniteCodec;
import com.blockreality.core.finite.FiniteCodec.Operation;
import com.blockreality.core.finite.FiniteDomainId;
import com.blockreality.core.finite.FiniteFracture;
import com.blockreality.core.finite.FiniteFractureDriver;
import com.blockreality.core.finite.FiniteFractureHost;
import com.blockreality.core.finite.FiniteFracturePlan;
import com.blockreality.core.finite.FiniteLane;
import com.blockreality.core.finite.FiniteRegistry;
import com.blockreality.core.finite.FiniteRoot;
import com.blockreality.core.finite.FiniteSession;
import com.blockreality.core.finite.FiniteSource;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-W4B gate evidence (FINITE_FRACTURE_TRANSACTION §9.8): the Forge finite-fracture
 * host over the shared construction journal — pure capture, shadow writes, world
 * visibility only after durable COMMITTED, publish-once replay, PREPARED rollback,
 * COMMITTED fold-forward republish, three-class recovery routing, born-body durable
 * records, the private-lane thread contract and the named refusal legs.
 * No MinecraftServer is needed: the host seam is {@code ForgeFiniteFractureHost.World}
 * and the service kernel is MC-free; the Forge shell wiring is file-asserted.
 */
class ForgeFiniteFractureTest {

    private static final UUID JOURNAL_DOMAIN = new UUID(7, 7), SESSION = new UUID(9, 9);
    private static final UUID ACTOR = new UUID(8, 8);
    private static final String DIMENSION = "minecraft:overworld";
    private static final ConstructionDeclaration STEEL_DECL =
            new ConstructionDeclaration("steel", "steel_rect_200x400", 1);
    private static final String ENGINE_BUILD = "engine/test";
    private static final String CONTRACT_SHA = "cd".repeat(32);
    private static final int FORMAT = 1, BUDGET = 4;
    private static final long BYTE_BUDGET = 1L << 20, ALLOC_BUDGET = 4096, ELEM_BUDGET = 4096;

    // ---------------------------------------------------------------- harness

    /**
     * The authority-lane stand-in (same shape as {@code ForgeFiniteRuntimeTest}'s
     * scripted lane): dynamic vocabulary/world replies for the post-commit
     * {@code syncAll}, a named NOOP error for anything else — native work here is
     * never part of the fracture evidence, only its absence is.
     */
    private static final class AuthorityLane implements FiniteLane {
        record Call(String method, String header, byte[] payload) { }
        final List<Call> calls = new ArrayList<>();
        final List<String> threads = new ArrayList<>();
        private com.blockreality.core.bsi.BsiFracture.World pendingWorld;

        @Override public BsiResponse request(String headerJson, byte[] payload) {
            threads.add(Thread.currentThread().getName());
            var header = JsonValue.parse(headerJson);
            String method = header.str("method", "");
            calls.add(new Call(method, headerJson, payload == null ? new byte[0] : payload));
            String id = header.str("id", "");
            if (method.equals("bsi.vocab.declare"))
                return respond(FractureTestFixtures.vocabReply(id, header.exactI64("revision")));
            if (method.equals("bsi.world.declare")) {
                var world = pendingWorld;
                assertNotNull(world, "world declare with no pending world");
                return respond(FractureTestFixtures.worldOk(id, world));
            }
            return respond("{\"bsi\":1,\"kind\":\"error\",\"id\":\"" + id + "\",\"method\":\""
                    + method + "\",\"revision\":" + header.exactI64("revision")
                    + ",\"code\":\"NOOP\"}", new byte[0]);
        }

        @Override public boolean adoptVocabulary(
                com.blockreality.core.bsi.BsiVocabulary vocabulary) { return true; }

        @Override public boolean declareFiniteWorld(
                com.blockreality.core.bsi.BsiFracture.World world) {
            pendingWorld = world;
            try {
                String id = "w" + calls.size();
                var response = request(com.blockreality.core.bsi.BsiFracture.declare(id, world),
                        world.payload());
                com.blockreality.core.bsi.BsiFracture.checkWorld(response, id, world);
                return true;
            } catch (IllegalArgumentException failure) {
                return false;
            }
        }

        @Override public boolean ready() { return true; }
        @Override public boolean has(String capability) { return true; }

        static BsiResponse respond(String header) { return respond(header, new byte[0]); }
        static BsiResponse respond(String header, byte[] payload) {
            return BsiResponse.of(new com.blockreality.core.bsi.BsiFrame.Decoded(0, header, payload));
        }
    }

    /** A recording world: shadow semantics are proven because mutation only happens here. */
    private static final class FakeWorld implements ForgeFiniteFractureHost.World {
        final Map<String, Value> cells = new LinkedHashMap<>();
        final Map<String, Value> bodies = new LinkedHashMap<>();
        final Map<String, Set<String>> blockers = new HashMap<>();
        final List<String> events = new ArrayList<>();
        final List<String> mutationPhases = new ArrayList<>();
        /** The durable phase observed at each world mutation — must always be COMMITTED. */
        java.util.function.Supplier<String> commitProbe = () -> "?";
        int baselines, afters, notices;

        @Override public Value readCell(String cellKey) {
            events.add("read " + cellKey);
            return cells.getOrDefault(cellKey, Value.missing());
        }

        @Override public void applyCell(String cellKey, Value image) {
            mutationPhases.add(commitProbe.get());
            events.add("apply " + cellKey);
            if (image.present()) cells.put(cellKey, image); else cells.remove(cellKey);
        }

        @Override public Set<String> uncapturable(String cellKey) {
            events.add("uncapturable " + cellKey);
            return blockers.getOrDefault(cellKey, Set.of());
        }

        @Override public void captureBaseline(Set<String> chunkKeys) {
            baselines++;
            events.add("baseline " + new TreeSet<>(chunkKeys));
        }

        @Override public void persistAfter(Set<String> chunkKeys) {
            afters++;
            events.add("after " + new TreeSet<>(chunkKeys));
        }

        @Override public void markBody(String bodyKey, Value record) {
            mutationPhases.add(commitProbe.get());
            events.add("body " + bodyKey);
            bodies.put(bodyKey, record);
        }

        @Override public void publishNotice(Receipt receipt) {
            notices++;
            events.add("notice " + receipt.request().id());
        }
    }

    @TempDir Path dir;

    private Path journalDir;
    private FileTransactionJournal journal;
    private ManufacturedRegistry metadata;
    private AtomicConstructionCoordinator coordinator;
    private FiniteRuntime runtime;
    private AuthorityLane lane;
    private FakeWorld world;
    private FractureTestFixtures.ProbeLane probe;
    private FiniteFractureService.Core core;
    private final AtomicBoolean onServerThread = new AtomicBoolean(true);
    private final AtomicBoolean entryOpen = new AtomicBoolean(true);

    private void openRuntime() throws IOException {
        journalDir = dir.resolve("blockreality/construction");
        journal = new FileTransactionJournal(journalDir, JOURNAL_DOMAIN);
        metadata = ManufacturedRegistry.load(journal);
        coordinator = new AtomicConstructionCoordinator(journal);
        lane = new AuthorityLane();
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

    /** Open the MC-free service kernel exactly like FiniteFractureService.openCore. */
    private void openFracture() throws IOException {
        openRuntime();
        world = new FakeWorld();
        probe = new FractureTestFixtures.ProbeLane();
        core = new FiniteFractureService.Core(runtime, journal, runtime.store(),
                dimension -> world,
                () -> new ForgeFiniteFractureHost.OpenedLane(probe, () -> { },
                        ENGINE_BUILD, CONTRACT_SHA),
                () -> { if (!onServerThread.get()) throw new IllegalStateException("requires the server thread"); },
                () -> {
                    if (!onServerThread.get()) throw new IllegalStateException("requires the server thread");
                    if (!entryOpen.get()) throw new IllegalStateException("fracture entry is closed");
                },
                Set.of(DIMENSION), message -> { }, SESSION);
        core.recover();                                          // ServerStarting ordering
        world.events.clear();                                    // startup republish is not evidence
    }

    /** The same routing the production pending router performs (fracture first). */
    private AtomicConstructionCoordinator.Host routePending(Entry pending) throws IOException {
        if (pending == null) return null;
        if (FiniteJournalHost.isFracture(pending)) return core.recoveryHost(pending);
        return runtime.recoveryHost(pending);
    }

    private void recoverRouting() throws IOException {
        Optional<UUID> pending = journal.pending();
        Entry entry = pending.isPresent()
                ? journal.verify(pending.get()).orElseThrow(() -> new IOException("pending vanished"))
                : null;
        AtomicConstructionCoordinator.Host routed = entry == null ? null : runtime.recoveryHost(entry);
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
            @Override public void publish(Receipt receipt) { }
            @Override public void publishRecoveredState() { }
        };
    }

    private void closeAll() throws IOException {
        if (core != null) core.close();
        if (runtime != null) runtime.close();
        if (journal != null) journal.close();
    }

    private String phaseOf(UUID requestId) {
        try { return journal.verify(requestId).map(e -> e.phase().name()).orElse("ABSENT"); }
        catch (IOException failure) { return "?"; }
    }

    // ---------------------------------------------------------------- fixture setup

    /**
     * Adopt a dimension, bind owner 1 to the STEEL cell and commit the fixture world:
     * GROUND support at (0,63,0), STEEL member at (1,64,1) owned by artifact 1 — the
     * exact before-image the REMOVED receipt consumes. The root commit rides the
     * registry-level journal path because the fixture's support deliberately sits
     * outside {@code FiniteDomainSource}'s face-adjacency rule.
     */
    private FiniteDomainId adoptWorld() throws IOException {
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        runtime.bindArtifacts(domain, List.of(Operation.BindingPlan.manufactured(
                new UUID(70, 1), STEEL_DECL, List.of(FractureTestFixtures.STEEL_CELL))));
        FiniteSource source = FiniteSource.build(FractureTestFixtures.VOCAB_REQUEST,
                FractureTestFixtures.vocabReply("r1", 0),
                List.of(FractureTestFixtures.GROUND, FractureTestFixtures.STEEL),
                List.of(new BsiFracture.Owner(FractureTestFixtures.STEEL_CELL, 1)),
                Set.of(1), new byte[152], new byte[0], new byte[0],
                new byte[0], new byte[0], new byte[0],
                FractureTestFixtures.options176(domain.bsiDomain(), 0));
        UUID commitId = new UUID(21, 1);
        var ref = runtime.store().publish(commitId, FiniteCodec.KIND_SOURCE, source.serialize());
        var next = new FiniteRoot.Next(0, 0, ref, null);
        var op = new Operation.CommitRoot(next);
        var request = new Request(commitId, ACTOR, SESSION, JOURNAL_DOMAIN,
                runtime.order(), FiniteRegistry.planHash(op));
        // The normal finite submit path keeps runtime.order() equal to the folded
        // journal order — a bare journal.decide would leave the counter stale and
        // poison every later request's baseRevision.
        Receipt receipt = runtime.submit(request,
                req -> runtime.registry().prepareCommitRoot(req, domain, next));
        if (receipt.phase() != Phase.COMMITTED)
            throw new IOException("Root commit refused: " + receipt.reason());
        world.cells.put(ForgeFiniteFractureHost.cellKey(FractureTestFixtures.STEEL_CELL),
                Value.of(new byte[]{0x55}));                      // the live steel cell
        world.commitProbe = () -> phaseOf(commitId);
        return domain;
    }

    /** The five scripted replies one REMOVED staging consumes on the private lane. */
    private void scriptStaging(FractureTestFixtures.ProbeLane peer,
                               FiniteDomainId domain, UUID requestId) {
        peer.reply(FractureTestFixtures.vocabReply("f1", 0));
        peer.reply(FractureTestFixtures.worldOk("w1", domain, 0));
        peer.reply(FractureTestFixtures.preparedHeader(requestId, domain.bsiDomain(),
                domain.artifactNamespace()), FractureTestFixtures.payload(domain.bsiDomain()));
        peer.reply(FractureTestFixtures.finishReply("f2", 0, requestId, domain.bsiDomain(),
                domain.artifactNamespace()));
        peer.reply(FractureTestFixtures.exportReply("f3", domain.bsiDomain(), 1, FORMAT, 48),
                new byte[48]);
    }

    /** A direct scripted staging (no marshaling) — the way a crash test builds a real intent. */
    private FiniteFracture.Staged stageDirect(FiniteDomainId domain, UUID requestId)
            throws IOException {
        var peer = new FractureTestFixtures.ProbeLane();
        scriptStaging(peer, domain, requestId);
        return new FiniteFracture(runtime.store(), runtime.registry())
                .stage(peer, domain, requestId, List.of(FractureTestFixtures.rule()), BUDGET,
                        FORMAT, BYTE_BUDGET, ALLOC_BUDGET, ELEM_BUDGET,
                        runtime.sessionGeneration() + 1, requestId, ACTOR, "test/stage",
                        ENGINE_BUILD, CONTRACT_SHA);
    }

    private Request fractureRequest(FiniteDomainId domain, UUID requestId,
                                    String provenance) throws IOException {
        var committed = runtime.registry().root(domain).orElseThrow();
        var source = runtime.registry().source(domain);
        return new Request(requestId, ACTOR, SESSION, JOURNAL_DOMAIN, committed.worldRevision(),
                FiniteFractureDriver.intentHash(domain, committed, source, requestId, ACTOR,
                        provenance, FORMAT, BYTE_BUDGET, ALLOC_BUDGET, ELEM_BUDGET,
                        List.of(FractureTestFixtures.rule()), BUDGET));
    }

    /** The durable PREPARED fracture intent a crash could leave behind (world untouched). */
    private Entry stageFractureEntry(FiniteDomainId domain, UUID requestId) throws Exception {
        var staged = stageDirect(domain, requestId);
        var request = fractureRequest(domain, requestId, "test/stage");
        var prepared = new FiniteFracture(runtime.store(), runtime.registry())
                .prepareFracture(request, domain, staged);
        var host = newHost();
        host.bindDomain(domain);
        var images = host.prepareImages(request, staged);
        var changes = new ArrayList<Change>(prepared.changes());
        changes.addAll(images);
        return Entry.prepared(new Intent(request, changes, List.of(), List.of()));
    }

    /** SHA-256 hex — {@code FiniteSource.sha256} is core-package-private. */
    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            var out = new StringBuilder(64);
            for (byte b : digest) out.append(Character.forDigit((b >> 4) & 15, 16))
                    .append(Character.forDigit(b & 15, 16));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private ForgeFiniteFractureHost newHost() {
        return newHost(new FractureTestFixtures.ProbeLane());
    }

    private ForgeFiniteFractureHost newHost(FractureTestFixtures.ProbeLane peer) {
        return new ForgeFiniteFractureHost(runtime, journal, dimension -> world,
                () -> new ForgeFiniteFractureHost.OpenedLane(peer,
                        () -> { }, ENGINE_BUILD, CONTRACT_SHA),
                () -> { if (!onServerThread.get()) throw new IllegalStateException("requires the server thread"); },
                message -> { });
    }

    // ---------------------------------------------------------------- A. capture

    @Test void prepareImagesCapturesWithoutMutating() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        UUID requestId = new UUID(31, 1);
        var staged = stageDirect(domain, requestId);
        var host = newHost();
        host.bindDomain(domain);
        var images = host.prepareImages(fractureRequest(domain, requestId, "test/stage"), staged);
        assertEquals(1, images.size());
        Change cell = images.get(0);
        assertEquals("cell/1/64/1", cell.resource());
        assertEquals(Value.of(new byte[]{0x55}), cell.before());
        assertEquals(Value.missing(), cell.after());
        // Pure capture: the world saw reads and blocker probes only — never a mutation.
        assertTrue(world.mutationPhases.isEmpty(), world.events::toString);
        assertEquals(0, world.baselines);
        assertEquals(0, world.afters);
        assertEquals(0, world.notices);
        for (String event : world.events)
            assertTrue(event.startsWith("read ") || event.startsWith("uncapturable "), event);
        closeAll();
    }

    @Test void uncapturableSourceRefused() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        world.blockers.put("cell/1/64/1", Set.of("moving-piston"));
        UUID requestId = new UUID(31, 2);
        scriptStaging(probe, domain, requestId);
        Receipt receipt = core.fracture(domain, List.of(FractureTestFixtures.rule()), BUDGET, requestId);
        assertEquals(Phase.REJECTED, receipt.phase());
        assertEquals(Reason.VALIDATION_REFUSED, receipt.reason());
        Entry entry = journal.verify(requestId).orElseThrow();
        assertEquals(Phase.REJECTED, entry.phase());
        assertTrue(world.mutationPhases.isEmpty(), world.events::toString);
        assertEquals(Value.of(new byte[]{0x55}), world.cells.get("cell/1/64/1"));
        closeAll();
    }

    @Test void bornBodyAnchorOccupiedRefused() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        UUID requestId = new UUID(31, 3);
        // The anchor at (9,64,9) is an unrelated live cell — not one of the removed cells.
        var staged = bornStaged(domain, requestId, new BlockKey(9, 64, 9));
        world.cells.put("cell/9/64/9", Value.of(new byte[]{0x01}));
        var host = newHost();
        host.bindDomain(domain);
        assertThrows(AtomicConstructionCoordinator.ValidationRefused.class,
                () -> host.prepareImages(fractureRequest(domain, requestId, "test/stage"), staged));
        assertTrue(world.mutationPhases.isEmpty());
        closeAll();
    }

    @Test void foreignFiniteImageRefused() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        UUID requestId = new UUID(31, 4);
        var peer = new FractureTestFixtures.ProbeLane();
        scriptStaging(peer, domain, requestId);
        var inner = newHost(peer);
        inner.bindDomain(domain);
        var hostile = new FiniteFractureHost() {
            @Override public void checkAccess() { inner.checkAccess(); }
            @Override public long revision() throws IOException { return inner.revision(); }
            @Override public Value read(String r) throws Exception { return inner.read(r); }
            @Override public void checkpoint(List<String> r) throws Exception { inner.checkpoint(r); }
            @Override public void write(String r, Value v) throws Exception { inner.write(r, v); }
            @Override public void flush(List<String> r) throws Exception { inner.flush(r); }
            @Override public List<Change> prepareImages(Request request, FiniteFracture.Staged staged)
                    throws Exception {
                var images = new ArrayList<>(inner.prepareImages(request, staged));
                images.add(new Change("finite/evil", Value.missing(), Value.of(new byte[]{1})));
                return images;
            }
            @Override public void publish(Receipt r, FiniteFracturePlan p) throws Exception { inner.publish(r, p); }
            @Override public void publishRecoveredState() throws Exception { inner.publishRecoveredState(); }
            @Override public FiniteLane openLane() throws IOException { return inner.openLane(); }
            @Override public long sessionGeneration() { return inner.sessionGeneration(); }
            @Override public String engineBuild() { return inner.engineBuild(); }
            @Override public String contractSha256() { return inner.contractSha256(); }
        };
        var driver = new FiniteFractureDriver(journal, runtime.store(), runtime.registry());
        driver.recover(newHost());                              // the driver's coordinator must be ready
        var request = fractureRequest(domain, requestId, "test/stage");
        var failure = assertThrows(IOException.class,
                () -> driver.execute(request, domain, List.of(FractureTestFixtures.rule()), BUDGET,
                        FORMAT, BYTE_BUDGET, ALLOC_BUDGET, ELEM_BUDGET, "test/stage", hostile));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertTrue(world.mutationPhases.isEmpty());
        closeAll();
    }

    /** The synthetic staged proposal carrying one born body at {@code anchor}. */
    private FiniteFracture.Staged bornStaged(FiniteDomainId domain, UUID requestId, BlockKey anchor)
            throws IOException {
        var source = runtime.registry().source(domain);
        var afterSource = FiniteSource.build(FractureTestFixtures.VOCAB_REQUEST,
                FractureTestFixtures.vocabReply("r1", 0),
                List.of(FractureTestFixtures.GROUND), List.of(), Set.of(1),
                new byte[152], new byte[0], new byte[0], new byte[0], new byte[0], new byte[0],
                FractureTestFixtures.options176(domain.bsiDomain(), 1));
        var sourceRef = runtime.store().publish(requestId, FiniteCodec.KIND_SOURCE,
                afterSource.serialize());
        var checkpointBlob = runtime.store().publish(requestId, FiniteCodec.KIND_CHECKPOINT,
                new byte[48]);
        var slot = new FiniteRoot.Checkpoint(checkpointBlob, FORMAT, ENGINE_BUILD, CONTRACT_SHA,
                BYTE_BUDGET, ALLOC_BUDGET, ELEM_BUDGET);
        var committed = runtime.registry().root(domain).orElseThrow();
        byte[] requestFrame = {9, 9, 9}, wireFrame = {8, 8};
        var plan = new FiniteFracturePlan(domain, requestId, ACTOR, "test/stage",
                new FiniteFracturePlan.Basis(committed.worldRevision(), committed.historySequence(),
                        source.sourceHash(), source.vocabHash(), source.ownersHash()),
                new FiniteFracturePlan.After(committed.worldRevision() + 1,
                        committed.historySequence() + 1, afterSource.sourceHash(), sourceRef, slot),
                sha256Hex(requestFrame), sha256Hex(wireFrame),
                FractureTestFixtures.TOKEN, 1, 0, 1, 0, 0, -1, true,
                FractureTestFixtures.GENERATION, 1, List.of(1L),
                List.of(new BsiFracture.Owner(anchor, 7)), new byte[0], new byte[0]);
        var session = FiniteSession.attach(new FractureTestFixtures.ProbeLane(), domain, source,
                runtime.sessionGeneration() + 1);
        return new FiniteFracture.Staged(plan, source, afterSource, requestFrame, wireFrame,
                session, null);
    }

    @Test void bornBodyWritesDurableRecord() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        UUID requestId = new UUID(31, 5);
        var staged = bornStaged(domain, requestId, new BlockKey(2, 64, 2));
        var host = newHost();
        host.bindDomain(domain);
        var request = fractureRequest(domain, requestId, "test/stage");
        var images = host.prepareImages(request, staged);
        assertEquals(2, images.size());
        Change body = images.stream().filter(c -> c.resource().startsWith("body/"))
                .findFirst().orElseThrow();
        assertEquals(ForgeFiniteFractureHost.bodyKey(domain, 7), body.resource());
        assertEquals(Value.missing(), body.before());
        assertEquals(ForgeFiniteFractureHost.BODY_RECORD_BYTES, body.after().bytes().length);
        // The durable record: position, durable artifact id, pending state.
        var buf = java.nio.ByteBuffer.wrap(body.after().bytes())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(new BlockKey(2, 64, 2), new BlockKey(buf.getInt(), buf.getInt(), buf.getInt()));
        assertEquals(7, buf.getLong());
        assertEquals(ForgeFiniteFractureHost.BODY_STATE_PENDING, buf.getInt());
        assertEquals(0, buf.getInt());
        // Publish applies the world mark once — after the durable commit exists.
        world.commitProbe = () -> "COMMITTED";
        host.publish(new Receipt(request, Phase.COMMITTED, Reason.NONE, 1, List.of(), List.of()),
                staged.plan());
        String bodyKey = ForgeFiniteFractureHost.bodyKey(domain, 7);
        assertTrue(world.bodies.containsKey(bodyKey), world.events::toString);
        assertEquals(List.of("COMMITTED", "COMMITTED"), world.mutationPhases);
        assertEquals("COMMITTED", world.mutationPhases.get(0));
        closeAll();
    }

    @Test void committedBodyIdCannotBeRebornUnderAFreshHost() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        // A prior fracture committed a durable body record for artifact 7.
        UUID committedId = new UUID(61, 9);
        var changes = new ArrayList<Change>();
        changes.add(new Change(FiniteRegistry.fractureKey(committedId, "plan", 0),
                Value.missing(), Value.of(new byte[]{1})));
        changes.add(new Change(ForgeFiniteFractureHost.bodyKey(domain, 7), Value.missing(),
                Value.of(ForgeFiniteFractureHost.encodeBody(
                        new BsiFracture.Owner(new BlockKey(2, 64, 2), 7)))));
        journal.create(Entry.prepared(new Intent(
                fractureRequest(domain, committedId, "test/stage"), changes, List.of(), List.of())));
        journal.decide(committedId, Phase.COMMITTED, Reason.NONE);
        // A fresh host carries no instance memory of it — the durable fold must refuse.
        var host = newHost();
        host.bindDomain(domain);
        var staged = bornStaged(domain, new UUID(70, 5), new BlockKey(2, 64, 2));
        var failure = assertThrows(IOException.class, () -> host.prepareImages(
                fractureRequest(domain, new UUID(70, 5), "test/stage"), staged));
        assertTrue(failure.getMessage().contains("already durable"), failure::getMessage);
        closeAll();
    }


    // ---------------------------------------------------------------- B. durable path

    @Test void fractureCommitsThenPublishes() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        UUID requestId = new UUID(41, 1);
        scriptStaging(probe, domain, requestId);
        world.commitProbe = () -> phaseOf(requestId);
        Receipt receipt = core.fracture(domain, List.of(FractureTestFixtures.rule()), BUDGET,
                requestId);
        assertEquals(Phase.COMMITTED, receipt.phase());
        assertEquals(1, receipt.revision());
        Entry entry = journal.verify(requestId).orElseThrow();
        assertEquals(Phase.COMMITTED, entry.phase());
        // World visibility started only after the durable COMMITTED decision.
        assertFalse(world.mutationPhases.isEmpty());
        for (String phase : world.mutationPhases)
            assertEquals("COMMITTED", phase);
        assertEquals(Value.missing(), world.cells.getOrDefault("cell/1/64/1", Value.missing()));
        // Finite authority folded: after root + retired binding.
        assertEquals(1, runtime.registry().root(domain).orElseThrow().worldRevision());
        assertEquals(com.blockreality.core.finite.ArtifactBinding.Disposition.RETIRED,
                runtime.binding(domain.artifactNamespace(), 1).orElseThrow().disposition());
        assertEquals(1, world.baselines);
        assertEquals(1, world.afters);
        assertEquals(1, world.notices);
        // The durable plan evidence is chunked under finite/fracture/<tx>/.
        var resources = entry.intent().changes().stream().map(Change::resource).toList();
        assertTrue(resources.stream().anyMatch(r -> r.startsWith("finite/fracture/")),
                resources::toString);
        assertTrue(resources.contains("cell/1/64/1"), resources::toString);
        assertTrue(runtime.lane().awaitIdle(10_000));
        closeAll();
    }

    @Test void fractureKeepsFiniteOrderInSync() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        UUID requestId = new UUID(41, 4);
        scriptStaging(probe, domain, requestId);
        world.commitProbe = () -> phaseOf(requestId);
        Receipt receipt = core.fracture(domain, List.of(FractureTestFixtures.rule()), BUDGET,
                requestId);
        assertEquals(Phase.COMMITTED, receipt.phase());
        // The fracture driver folds the registry directly, bypassing runtime.committed —
        // the request clock must still advance or the next finite tx (bind/observe/
        // commitSource) CAS-refuses with PARTICIPANT_CONFLICT.
        Receipt followup = runtime.observeDomain(domain);
        assertEquals(Phase.COMMITTED, followup.phase(),
                () -> "follow-up observe refused: " + followup.reason());
        assertTrue(runtime.lane().awaitIdle(10_000));
        closeAll();
    }

    @Test void replayReadsDurableDecisionOnce() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        UUID requestId = new UUID(41, 2);
        scriptStaging(probe, domain, requestId);
        world.commitProbe = () -> phaseOf(requestId);
        Receipt first = core.fracture(domain, List.of(FractureTestFixtures.rule()), BUDGET,
                requestId);
        assertEquals(Phase.COMMITTED, first.phase());
        int mutations = world.mutationPhases.size();
        int notices = world.notices;
        long entries = journal.entryIds().size();
        // Re-issue the same request id: the durable decision is read back, never re-run.
        Receipt replay = core.fracture(domain, List.of(FractureTestFixtures.rule()), BUDGET,
                requestId);
        assertEquals(first, replay);
        assertEquals(entries, journal.entryIds().size());
        assertEquals(mutations, world.mutationPhases.size());       // publish-once
        assertEquals(notices, world.notices);
        assertTrue(runtime.lane().awaitIdle(10_000));
        closeAll();
    }

    @Test void nativeWorkStaysOnPrivateLane() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        UUID requestId = new UUID(41, 3);
        scriptStaging(probe, domain, requestId);
        world.commitProbe = () -> phaseOf(requestId);
        String caller = Thread.currentThread().getName();
        Receipt receipt = core.fracture(domain, List.of(FractureTestFixtures.rule()), BUDGET,
                requestId);
        assertEquals(Phase.COMMITTED, receipt.phase());
        assertFalse(probe.threads.isEmpty());
        for (String thread : probe.threads) {
            assertTrue(thread.startsWith("br-fracture-lane-"), thread);
            assertNotEquals(caller, thread);
        }
        // The private generation is minted strictly above the authority session's.
        var host = newHost();
        assertTrue(host.sessionGeneration() > runtime.sessionGeneration());
        assertTrue(runtime.lane().awaitIdle(10_000));
        closeAll();
    }

    @Test void staleBasisRejectedBeforeWorld() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        UUID requestId = new UUID(41, 4);
        var peer = new FractureTestFixtures.ProbeLane();
        scriptStaging(peer, domain, requestId);
        var driver = new FiniteFractureDriver(journal, runtime.store(), runtime.registry());
        driver.recover(newHost());
        // A request stamped on a foreign revision never reaches staging or the world.
        var stale = new Request(requestId, ACTOR, SESSION, JOURNAL_DOMAIN, 9,
                "ab".repeat(32));
        Receipt receipt = driver.execute(stale, domain, List.of(FractureTestFixtures.rule()),
                BUDGET, FORMAT, BYTE_BUDGET, ALLOC_BUDGET, ELEM_BUDGET, "test/stage", newHost())
                .receipt();
        assertEquals(Phase.REJECTED, receipt.phase());
        assertEquals(Reason.STALE_REVISION, receipt.reason());
        assertTrue(world.mutationPhases.isEmpty());
        assertTrue(peer.calls.isEmpty());
        closeAll();
    }

    @Test void planHashMismatchRejected() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        UUID requestId = new UUID(41, 5);
        var peer = new FractureTestFixtures.ProbeLane();
        scriptStaging(peer, domain, requestId);
        var bound = newHost(peer);                             // staging rides the scripted peer
        bound.bindDomain(domain);
        var driver = new FiniteFractureDriver(journal, runtime.store(), runtime.registry());
        driver.recover(newHost());
        var mismatched = new Request(requestId, ACTOR, SESSION, JOURNAL_DOMAIN, 0,
                "ef".repeat(32));
        Receipt receipt = driver.execute(mismatched, domain, List.of(FractureTestFixtures.rule()),
                BUDGET, FORMAT, BYTE_BUDGET, ALLOC_BUDGET, ELEM_BUDGET, "test/stage", bound)
                .receipt();
        assertEquals(Phase.REJECTED, receipt.phase());
        assertEquals(Reason.VALIDATION_REFUSED, receipt.reason());
        assertTrue(world.mutationPhases.isEmpty());
        closeAll();
    }

    // ---------------------------------------------------------------- C. recovery

    @Test void pendingFractureRollsBackThroughRoutedHost() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        UUID requestId = new UUID(51, 1);
        journal.create(stageFractureEntry(domain, requestId));
        world.commitProbe = () -> phaseOf(requestId);
        Entry pending = journal.verify(journal.pending().orElseThrow()).orElseThrow();
        assertTrue(FiniteJournalHost.isFracture(pending));
        var routed = routePending(pending);
        assertNotNull(routed);
        assertFalse(routed instanceof FiniteJournalHost);        // the finite host would refuse it
        coordinator.recover(routed);
        Entry decided = journal.verify(requestId).orElseThrow();
        assertEquals(Phase.ABORTED, decided.phase());
        assertEquals(Reason.RECOVERED, decided.reason());
        // Rollback restored before-images as shadow writes: the live world never moved.
        assertTrue(world.mutationPhases.isEmpty(), world.events::toString);
        assertEquals(Value.of(new byte[]{0x55}), world.cells.get("cell/1/64/1"));
        assertEquals(0, world.notices);
        closeAll();
    }

    @Test void committedCrashFoldsForwardAndRepublishes() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        UUID requestId = new UUID(51, 2);
        journal.create(stageFractureEntry(domain, requestId));
        journal.decide(requestId, Phase.COMMITTED, Reason.NONE);   // crash before apply+publish
        world.commitProbe = () -> phaseOf(requestId);
        assertEquals(0, runtime.registry().root(domain).orElseThrow().worldRevision());
        core.recover();
        // The committed intent folded forward and the world caught up once.
        assertEquals(1, runtime.registry().root(domain).orElseThrow().worldRevision());
        assertEquals(com.blockreality.core.finite.ArtifactBinding.Disposition.RETIRED,
                runtime.binding(domain.artifactNamespace(), 1).orElseThrow().disposition());
        assertEquals(Value.missing(), world.cells.getOrDefault("cell/1/64/1", Value.missing()));
        for (String phase : world.mutationPhases) assertEquals("COMMITTED", phase);
        // A second recovery pass is idempotent: live==after skips, nothing reapplies.
        int mutations = world.mutationPhases.size();
        core.recover();
        assertEquals(mutations, world.mutationPhases.size());
        assertEquals(Value.missing(), world.cells.getOrDefault("cell/1/64/1", Value.missing()));
        closeAll();
    }

    @Test void danglingPreparedRecoverThroughCoreRecover() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        UUID requestId = new UUID(51, 3);
        journal.create(stageFractureEntry(domain, requestId));
        world.commitProbe = () -> phaseOf(requestId);
        core.recover();                                            // construction recovery never ran
        Entry decided = journal.verify(requestId).orElseThrow();
        assertEquals(Phase.ABORTED, decided.phase());
        assertEquals(Reason.RECOVERED, decided.reason());
        assertTrue(world.mutationPhases.isEmpty(), world.events::toString);
        assertEquals(Value.of(new byte[]{0x55}), world.cells.get("cell/1/64/1"));
        closeAll();
    }

    @Test void foreignRecoveredStateFailsClosed() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        UUID requestId = new UUID(51, 4);
        journal.create(stageFractureEntry(domain, requestId));
        journal.decide(requestId, Phase.COMMITTED, Reason.NONE);
        // The live cell was replaced by something that is neither before nor after image.
        world.cells.put("cell/1/64/1", Value.of(new byte[]{0x7E}));
        assertThrows(IOException.class, () -> core.recover());
        closeAll();
    }

    // ---------------------------------------------------------------- D. routing

    @Test void pendingRoutesThreeClasses() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        // Pure finite pending → the finite journal host.
        var finiteOp = new Operation.CreateDomain("minecraft:the_nether");
        var finiteReq = new Request(new UUID(61, 1), ACTOR, SESSION, JOURNAL_DOMAIN,
                runtime.order(), FiniteRegistry.planHash(finiteOp));
        journal.create(Entry.prepared(runtime.registry()
                .prepareCreateDomain(finiteReq, "minecraft:the_nether").intent()));
        Entry finitePending = journal.verify(journal.pending().orElseThrow()).orElseThrow();
        var finiteHost = routePending(finitePending);
        assertInstanceOf(FiniteJournalHost.class, finiteHost);
        assertTrue(FiniteJournalHost.isFinite(finitePending));
        assertFalse(FiniteJournalHost.isFracture(finitePending));
        coordinator.recover(finiteHost);
        assertEquals(Phase.ABORTED, journal.verify(finiteReq.id()).orElseThrow().phase());
        // Mixed fracture pending → the driver's Publication over the Forge host.
        UUID requestId = new UUID(61, 2);
        journal.create(stageFractureEntry(domain, requestId));
        Entry fracturePending = journal.verify(journal.pending().orElseThrow()).orElseThrow();
        assertFalse(FiniteJournalHost.isFinite(fracturePending));
        assertTrue(FiniteJournalHost.isFracture(fracturePending));
        var fractureHost = routePending(fracturePending);
        assertNotNull(fractureHost);
        assertFalse(fractureHost instanceof FiniteJournalHost);
        coordinator.recover(fractureHost);
        assertEquals(Phase.ABORTED, journal.verify(requestId).orElseThrow().phase());
        assertTrue(world.mutationPhases.isEmpty());
        // Manufactured/non-finite pending → left for the construction host.
        var manufacturedReq = new Request(new UUID(61, 3), ACTOR, SESSION, JOURNAL_DOMAIN, 0,
                "ab".repeat(32));
        journal.create(Entry.prepared(metadata.prepareBuild(manufacturedReq, DIMENSION, false,
                List.of(new ManufacturedPiece.Plan(STEEL_DECL, List.of(new BlockKey(3, 64, 3)))))
                .withParticipants(List.of())));
        Entry manufacturedPending = journal.verify(journal.pending().orElseThrow()).orElseThrow();
        assertFalse(FiniteJournalHost.isFinite(manufacturedPending));
        assertFalse(FiniteJournalHost.isFracture(manufacturedPending));
        assertNull(routePending(manufacturedPending));
        journal.decide(manufacturedReq.id(), Phase.ABORTED, Reason.RECOVERED);
        closeAll();
    }

    // ---------------------------------------------------------------- E. admission

    @Test void fractureRefusedWithoutCommittedRoot() throws Exception {
        openFracture();
        FiniteDomainId domain = runtime.adoptDomain(DIMENSION);
        runtime.bindArtifacts(domain, List.of(Operation.BindingPlan.manufactured(
                new UUID(70, 2), STEEL_DECL, List.of(FractureTestFixtures.STEEL_CELL))));
        var failure = assertThrows(IOException.class, () -> core.fracture(domain,
                List.of(FractureTestFixtures.rule()), BUDGET, new UUID(71, 1)));
        assertTrue(failure.getMessage().contains("root"), failure.getMessage());
        closeAll();
    }

    @Test void fractureRefusedOutsideAdmittedDimension() throws Exception {
        openFracture();
        // A foreign domain identity in a non-admitted dimension (adoptDomain itself
        // refuses non-admitted dimensions, so the identity is crafted directly).
        var nether = new FiniteDomainId(JOURNAL_DOMAIN, "minecraft:the_nether",
                new UUID(11, 11), new UUID(12, 12));
        var failure = assertThrows(IOException.class, () -> core.fracture(nether,
                List.of(FractureTestFixtures.rule()), BUDGET, new UUID(71, 2)));
        assertTrue(failure.getMessage().contains("not admitted"), failure.getMessage());
        closeAll();
    }

    @Test void fractureRefusedOffServerThread() throws Exception {
        openFracture();
        FiniteDomainId domain = adoptWorld();
        onServerThread.set(false);
        try {
            assertThrows(IllegalStateException.class, () -> core.fracture(domain,
                    List.of(FractureTestFixtures.rule()), BUDGET, new UUID(71, 3)));
        } finally { onServerThread.set(true); }
        assertTrue(world.mutationPhases.isEmpty());
        closeAll();
    }

    // ---------------------------------------------------------------- F. shell wiring

    private static String shellSource(String file) throws IOException {
        var path = Path.of("src/main/java/com/blockreality/impl/server/finite/" + file);
        if (!Files.exists(path)) path = Path.of("forge/" + path);
        return Files.readString(path);
    }

    private static String modSource() throws IOException {
        var path = Path.of("src/main/java/com/blockreality/impl/BlockRealityMod.java");
        if (!Files.exists(path)) path = Path.of("forge/" + path);
        return Files.readString(path);
    }

    @Test void serviceLifecycleAndRoutingWired() throws Exception {
        String service = shellSource("FiniteFractureService.java");
        assertTrue(service.contains("EventPriority.NORMAL"),
                "fracture service must run after construction (HIGHEST) and finite (HIGH)");
        assertTrue(service.contains("setPendingRouter"),
                "the fracture-aware pending router must be installed before startup recovery");
        assertTrue(service.indexOf("isFracture") < service.indexOf("isFinite"),
                "the router must test fracture shape before the pure-finite host claims the entry");
        assertTrue(service.contains("core.recover()"),
                "startup recovery drives the driver's fold-forward + republish");
        assertTrue(service.contains("finiteTestDimensions"),
                "the test-mode fracture entry is gated on configured admitted dimensions");
        assertTrue(modSource().contains("FiniteFractureService.class"),
                "the service must be registered on the Forge event bus");
        // No event hook may trigger a fracture: the only call sites are the router,
        // startup recovery and the explicit static entry.
        String listener = service.substring(service.indexOf("private void openCore"));
        assertFalse(listener.contains("@SubscribeEvent"));
    }

    @Test void productionWorldAdapterHonest() throws Exception {
        String adapter = shellSource("LevelFractureWorld.java");
        assertTrue(adapter.contains("Blocks.STRUCTURE_VOID"),
                "pending bodies mark an invisible reservation, never a fake block or entity");
        assertTrue(adapter.contains("sendBlockUpdated"),
                "publication sends the post-commit block updates exactly once");
        assertTrue(adapter.contains("PistonMovingBlockEntity")
                        && adapter.contains("hasScheduledTick"),
                "named uncapturable mutation sources are refused, not approximated");
        assertFalse(adapter.contains("level.getChunk("),
                "the adapter must not force-generate chunks for capture");
        String host = shellSource("ForgeFiniteFractureHost.java");
        assertTrue(host.contains("br-fracture-lane-"),
                "native calls marshal onto the private lane thread");
        assertTrue(host.contains("cell/") && host.contains("body/"),
                "host resources stay under cell/… and body/…");
    }
}
