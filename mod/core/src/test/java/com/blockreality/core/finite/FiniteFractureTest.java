package com.blockreality.core.finite;

import com.blockreality.core.bsi.BsiCorotFracture;
import com.blockreality.core.bsi.BsiFrame;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.finite.FiniteCodec.Operation;
import com.blockreality.core.finite.FiniteFracture.Staged;
import com.blockreality.core.transaction.AtomicConstructionCoordinator;
import com.blockreality.core.transaction.ConstructionTransaction;
import com.blockreality.core.transaction.ConstructionTransaction.Change;
import com.blockreality.core.transaction.ConstructionTransaction.Entry;
import com.blockreality.core.transaction.ConstructionTransaction.Phase;
import com.blockreality.core.transaction.ConstructionTransaction.Reason;
import com.blockreality.core.transaction.ConstructionTransaction.Receipt;
import com.blockreality.core.transaction.ConstructionTransaction.Request;
import com.blockreality.core.transaction.ConstructionTransaction.Value;
import com.blockreality.core.transaction.FileTransactionJournal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.blockreality.core.finite.FiniteFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * W4-A finite fracture tests (FINITE_FRACTURE_TRANSACTION §7): the scripted native
 * peer drives the real wire/receipt/plan/journal paths with no engine. Every leg
 * asserts durable behaviour — a staged mutation is rejected before authority moves,
 * and a committed decision replays byte-for-byte without re-running native work.
 */
class FiniteFractureTest {

    @TempDir Path root;
    private FileTransactionJournal journal;
    private FiniteStore store;

    private FiniteRegistry open() throws IOException {
        journal = new FileTransactionJournal(root.resolve("journal"), JOURNAL);
        store = FiniteStore.open(root.resolve("finite"));
        return FiniteRegistry.load(journal, store);
    }

    /** Journal a prepared proposal as COMMITTED; returns the request id. */
    private UUID commit(FiniteRegistry.Prepared prepared) throws IOException {
        journal.create(Entry.prepared(prepared.intent()));
        journal.decide(prepared.request().id(), Phase.COMMITTED, Reason.NONE);
        return prepared.request().id();
    }

    private record World(FiniteRegistry registry, FiniteDomainId domain, FiniteSource source,
                         FiniteBlobRef sourceRef, FiniteRoot committed) { }

    /** A domain + genesis root at revision 0 naming the two-block fixture source. */
    private World openWorld() throws IOException {
        var registry = open();
        registry.applyCommitted(journal, commit(registry.prepareCreateDomain(
                request(1, new Operation.CreateDomain(DIMENSION)), DIMENSION)));
        var domain = registry.domain(DIMENSION).orElseThrow().id();
        var source = FiniteFixtures.source(domain.bsiDomain(), 0);
        var sourceRef = store.publish(new UUID(80, 1), FiniteCodec.KIND_SOURCE, source.serialize());
        var genesis = new FiniteRoot.Next(0, 0, sourceRef, null);
        registry.applyCommitted(journal, commit(registry.prepareCommitRoot(
                request(2, new Operation.CommitRoot(genesis)), domain, genesis)));
        return new World(registry, domain, source, sourceRef,
                registry.root(domain).orElseThrow());
    }

    private static String worldOk(FiniteDomainId domain, long revision) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"w1\",\"method\":\"bsi.world.declare\","
                + "\"revision\":" + revision + ",\"status\":\"ok\",\"identity\":{\"domain\":\""
                + BsiFracture.hex(domain.bsiDomain()) + "\",\"revision\":" + revision
                + ",\"artifactNamespace\":\"" + BsiFracture.hex(domain.artifactNamespace()) + "\"}}";
    }

    /** Script one complete staging run: vocabulary, world, prepared receipt, finish, export. */
    private static FiniteSessionTestProbe stagingLane(FiniteDomainId domain, UUID requestId,
                                                      FractureFixtures.Variant variant) {
        var lane = new FiniteSessionTestProbe();
        lane.reply(vocabReply("f1", 0));
        lane.reply(worldOk(domain, 0));
        lane.reply(FractureFixtures.preparedHeader(requestId, domain.bsiDomain(),
                domain.artifactNamespace(), variant),
                FractureFixtures.payload(domain.bsiDomain(), variant));
        lane.reply(FractureFixtures.finishReply("f2", 0, requestId, domain.bsiDomain(),
                domain.artifactNamespace(), variant != FractureFixtures.Variant.PENDING));
        if (variant != FractureFixtures.Variant.PENDING)
            lane.reply(FractureFixtures.exportReply("f3", domain.bsiDomain(), 1, 1, 48),
                    new byte[48]);
        return lane;
    }

    private static Staged stage(FiniteStore store, FiniteRegistry registry, World world,
                                FiniteSessionTestProbe lane, UUID requestId) throws IOException {
        return new FiniteFracture(store, registry).stage(lane, world.domain(), requestId,
                List.of(FractureFixtures.rule()), 4, 1, 1 << 20, 4096, 4096,
                11, requestId, ACTOR, "test/actor", "engine/test", "cd".repeat(32));
    }

    private void closeAll() throws IOException {
        if (store != null) store.close();
        if (journal != null) journal.close();
        store = null; journal = null;
    }

    // ---------------------------------------------------------------- source + rules

    @Test void failureRulesRoundTripAndLegacyDecode() throws Exception {
        var domain = domainId(DIMENSION);
        var rules = BsiCorotFracture.encodeRules(List.of(FractureFixtures.rule()));
        var source = FiniteSource.build(VOCAB_REQUEST, vocabReply("r1", 0),
                List.of(GROUND, STEEL), List.of(new BsiFracture.Owner(
                        new com.blockreality.api.geom.BlockKey(1, 64, 1), 1)),
                java.util.Set.of(1), new byte[152], new byte[0], new byte[0],
                new byte[0], new byte[0], new byte[0], options176(domain.bsiDomain(), 0), rules);
        var decoded = FiniteSource.decode(source.serialize());
        assertArrayEquals(source.serialize(), decoded.serialize());
        assertArrayEquals(rules, decoded.failureRules());
        // Legacy: a 12-section payload decodes with empty rules and keeps its bytes.
        var legacy = FiniteFixtures.source(domain.bsiDomain(), 0);
        var legacyDecoded = FiniteSource.decode(legacy.serialize());
        assertEquals(0, legacyDecoded.failureRules().length);
        assertArrayEquals(legacy.serialize(), legacyDecoded.serialize());
        assertNotEquals(source.sourceHash(), legacyDecoded.sourceHash());
        // Stride and unknown-material hygiene refuse garbage.
        assertThrows(IllegalArgumentException.class, () -> FiniteSource.build(
                VOCAB_REQUEST, vocabReply("r1", 0), List.of(GROUND, STEEL),
                List.of(new BsiFracture.Owner(new com.blockreality.api.geom.BlockKey(1, 64, 1), 1)),
                java.util.Set.of(1), new byte[152], new byte[0], new byte[0],
                new byte[0], new byte[0], new byte[0], options176(domain.bsiDomain(), 0),
                new byte[41]));
    }

    @Test void fracturePayloadOrderIsContractExact() {
        var domain = domainId(DIMENSION);
        var source = FiniteFixtures.source(domain.bsiDomain(), 0);
        var rules = BsiCorotFracture.encodeRules(List.of(FractureFixtures.rule()));
        var payload = source.fracturePayload(rules);
        // materials ‖ cellLoads ‖ prescribed ‖ loads ‖ rules ‖ shellLaws ‖ shellLayers
        int expected = 152 + rules.length;
        assertEquals(expected, payload.length);
        assertArrayEquals(Arrays.copyOfRange(payload, 0, 152), source.materials());
        assertArrayEquals(Arrays.copyOfRange(payload, 152, expected), rules);
        assertThrows(IllegalArgumentException.class, () -> source.fracturePayload(new byte[7]));
    }

    @Test void intentHashCountsLayersFromTheLayerSection() throws Exception {
        var world = openWorld();
        var domain = world.domain();
        var committed = world.committed();
        // 2 shell laws + 3 shell layers: a caller that derived the layer count from the
        // law section (112/16=7) would hash a request frame staging never produces and
        // refuse every shell world. Keep the two counts visibly different.
        var source = FiniteSource.build(VOCAB_REQUEST, vocabReply("r2", 0),
                List.of(GROUND, STEEL), List.of(new BsiFracture.Owner(
                        new com.blockreality.api.geom.BlockKey(1, 64, 1), 1)),
                java.util.Set.of(1), new byte[152],
                new byte[2 * FiniteSource.SHELL_LAW_BYTES],
                new byte[3 * FiniteSource.SHELL_LAYER_BYTES],
                new byte[0], new byte[0], new byte[0], options176(domain.bsiDomain(), 0));
        var rules = List.of(FractureFixtures.rule());
        var requestId = new UUID(70, 99);
        String header = BsiCorotFracture.prepare(BsiCorotFracture.prepareId(requestId),
                committed.worldRevision(), source.options(),
                source.materials().length / FiniteSource.MATERIAL_BYTES, 0, 0, 0,
                requestId, rules.size(), 4, 2, 3);
        byte[] frame = BsiFrame.encode(header,
                source.fracturePayload(BsiCorotFracture.encodeRules(rules)));
        var expected = FiniteCodec.fractureIntentHash(domain, committed.worldRevision(),
                committed.historySequence(), source.sourceHash(), source.vocabHash(),
                source.ownersHash(), requestId, ACTOR, "test/actor", 1, 1 << 20, 4096, 4096,
                FiniteSource.sha256(frame));
        var actual = FiniteFractureDriver.intentHash(domain, committed, source, requestId,
                ACTOR, "test/actor", 1, 1 << 20, 4096, 4096, rules, 4);
        assertEquals(expected, actual);
        closeAll();
    }

    // ---------------------------------------------------------------- wire + session

    @Test void fracturePrepareStoresDeterministicRequestFrame() throws Exception {
        var world = openWorld();
        var requestId = new UUID(70, 1);
        var lane = stagingLane(world.domain(), requestId, FractureFixtures.Variant.RETAINED);
        var session = FiniteSession.attach(lane, world.domain(), world.source(), 5);
        assertTrue(session.declareVocabulary());
        assertTrue(session.declareWorld(0));
        var prepared = session.fracturePrepare(requestId, List.of(FractureFixtures.rule()), 4);
        assertNotNull(prepared);
        assertTrue(prepared.qualified());
        assertEquals(2, prepared.remainingBlocks());
        assertTrue(prepared.retiredOwnership().isEmpty());
        // The request frame is canonical: re-derive it byte for byte.
        var rules = BsiCorotFracture.encodeRules(List.of(FractureFixtures.rule()));
        var canonical = BsiFrame.encode(
                BsiCorotFracture.prepare(BsiCorotFracture.prepareId(requestId), 0,
                        world.source().options(), 1, 0, 0, 0, requestId, 1, 4, 0, 0),
                world.source().fracturePayload(rules));
        assertArrayEquals(canonical, session.fractureRequestFrame());
        session.close();
        closeAll();
    }

    @Test void fractureCommitAdvancesSessionAndSealsIt() throws Exception {
        var world = openWorld();
        var requestId = new UUID(70, 2);
        var lane = stagingLane(world.domain(), requestId, FractureFixtures.Variant.RETAINED);
        var session = FiniteSession.attach(lane, world.domain(), world.source(), 5);
        session.declareVocabulary(); session.declareWorld(0);
        var prepared = session.fracturePrepare(requestId, List.of(FractureFixtures.rule()), 4);
        var rules = BsiCorotFracture.encodeRules(List.of(FractureFixtures.rule()));
        var after = FiniteSource.afterFracture(world.source(), prepared, rules);
        var finish = session.fractureFinish(prepared, true, after);
        assertEquals(BsiFracture.Finish.COMMITTED, finish);
        assertEquals(1, session.worldRevision());
        assertArrayEquals(after.options(), session.source().options());
        assertNull(session.fractureCandidate());
        // The session is sealed: no more solve, arc, or fracture prepare.
        assertNull(session.solve());
        assertNull(session.fracturePrepare(new UUID(70, 3), List.of(), 4));
        assertNull(session.arcAdvance(CorotArcFixtures.arcOptions()));
        // Export still works — the RESTORED after-state must be durable-able.
        lane.reply(FractureFixtures.exportReply("f3", world.domain().bsiDomain(), 1, 1, 48),
                new byte[48]);
        assertNotNull(session.exportCheckpoint(1, 1 << 20, 4096, 4096));
        session.close();
        closeAll();
    }

    @Test void fracturePrepareRefusesMissingCapabilitiesAndPoisons() throws Exception {
        var world = openWorld();
        var lane = new FiniteSessionTestProbe();
        lane.capabilities.remove("bsi.corot.fracture");
        lane.reply(vocabReply("f1", 0));
        lane.reply(worldOk(world.domain(), 0));
        var session = FiniteSession.attach(lane, world.domain(), world.source(), 5);
        session.declareVocabulary(); session.declareWorld(0);
        assertNull(session.fracturePrepare(new UUID(70, 4), List.of(), 4));
        assertNotNull(session.refusal());
        // The poisoned session refuses every further step.
        assertNull(session.solve());
        assertNull(session.fracturePrepare(new UUID(70, 5), List.of(), 4));
        closeAll();
    }

    @Test void malformedReceiptPoisonsSession() throws Exception {
        var world = openWorld();
        var lane = new FiniteSessionTestProbe();
        lane.reply(vocabReply("f1", 0));
        lane.reply(worldOk(world.domain(), 0));
        var requestId = new UUID(70, 6);
        // Wrong requestId echo — the receipt cannot bind this request.
        lane.reply(FractureFixtures.preparedHeader(new UUID(70, 7), world.domain().bsiDomain(),
                world.domain().artifactNamespace(), FractureFixtures.Variant.RETAINED),
                FractureFixtures.payload(world.domain().bsiDomain(), FractureFixtures.Variant.RETAINED));
        var session = FiniteSession.attach(lane, world.domain(), world.source(), 5);
        session.declareVocabulary(); session.declareWorld(0);
        assertNull(session.fracturePrepare(requestId, List.of(FractureFixtures.rule()), 4));
        assertNotNull(session.refusal());
        closeAll();
    }

    @Test void receiptMutationLegsAllRefuse() throws Exception {
        var world = openWorld();
        var source = world.source();
        var fractureWorld = new BsiCorotFracture.FractureWorld(source.blocks(), source.owners(),
                source.vocabulary(), source.defaultSections(source.vocabulary()),
                source.materialRoles(), source.cellLoads(), source.shellLaws());
        var requestId = new UUID(70, 8);
        var legalHeader = FractureFixtures.preparedHeader(requestId, world.domain().bsiDomain(),
                world.domain().artifactNamespace(), FractureFixtures.Variant.RETAINED);
        var legalPayload = FractureFixtures.payload(world.domain().bsiDomain(),
                FractureFixtures.Variant.RETAINED);
        // The baseline is legal and commit-qualified.
        var legal = BsiCorotFracture.checkPrepared(FiniteSessionTestProbe.respond(legalHeader, legalPayload),
                BsiCorotFracture.prepareId(requestId), 0, world.domain().bsiDomain(), 0,
                world.domain().artifactNamespace(), fractureWorld, requestId, 4);
        assertTrue(legal.qualified());
        record Leg(String name, String header, byte[] payload) { }
        var legs = new ArrayList<Leg>();
        legs.add(new Leg("wrong-status",
                legalHeader.replace("\"prepared\"", "\"analyzed\""), legalPayload));
        legs.add(new Leg("wrong-request", legalHeader.replace(BsiFracture.hex(requestId),
                BsiFracture.hex(new UUID(9, 9))), legalPayload));
        legs.add(new Leg("wrong-after", legalHeader.replace("\"revision\":1}", "\"revision\":2}"),
                legalPayload));
        legs.add(new Leg("pending-set", legalHeader.replace("\"pending\":-1", "\"pending\":0"),
                legalPayload));
        legs.add(new Leg("flags-range", legalHeader.replace("\"flags\":0", "\"flags\":4"),
                legalPayload));
        legs.add(new Leg("not-converged", legalHeader.replace("true", "false"), legalPayload));
        legs.add(new Leg("bad-generation", legalHeader.replace(FractureFixtures.GENERATION,
                "zz"), legalPayload));
        legs.add(new Leg("remaining-mismatch", legalHeader.replace("\"remainingBlocks\":2",
                "\"remainingBlocks\":1"), legalPayload));
        legs.add(new Leg("bad-token", legalHeader.replace(FractureFixtures.TOKEN.sequence(),
                "0000000000000000"), legalPayload));
        legs.add(new Leg("truncated", legalHeader,
                Arrays.copyOf(legalPayload, legalPayload.length - 8)));
        legs.add(new Leg("trailing", legalHeader,
                Arrays.copyOf(legalPayload, legalPayload.length + 8)));
        legs.add(new Leg("renamed-section", legalHeader.replace("\"name\":\"fractureCells\"",
                "\"name\":\"fractureCellsSwapped\""), legalPayload));
        var drifted = legalPayload.clone();
        drifted[3 * 80 + 8] ^= 0x55;                          // cell source image
        legs.add(new Leg("cell-source-drift", legalHeader, drifted));
        var owned = legalPayload.clone();
        owned[3 * 80 + 48 + 4] ^= 0x02;                       // cell artifact echo
        legs.add(new Leg("cell-owner-drift", legalHeader, owned));
        for (var leg : legs) {
            assertThrows(IllegalArgumentException.class, () -> BsiCorotFracture.checkPrepared(
                    FiniteSessionTestProbe.respond(leg.header(), leg.payload()),
                    BsiCorotFracture.prepareId(requestId), 0, world.domain().bsiDomain(), 0,
                    world.domain().artifactNamespace(), fractureWorld, requestId, 4), leg.name());
        }
        closeAll();
    }

    // ---------------------------------------------------------------- staging

    @Test void stageProducesConsistentPlanAndObjects() throws Exception {
        var world = openWorld();
        var requestId = new UUID(70, 10);
        var staged = stage(store, world.registry(), world,
                stagingLane(world.domain(), requestId, FractureFixtures.Variant.RETAINED),
                requestId);
        var plan = staged.plan();
        assertEquals(1, plan.after().worldRevision());
        assertEquals(1, plan.after().historySequence());
        assertEquals(0, plan.before().worldRevision());
        assertEquals(0, plan.before().historySequence());
        assertEquals(world.source().sourceHash(), plan.before().sourceHash());
        assertEquals(requestId, plan.request());
        assertEquals(ACTOR, plan.actor());
        assertEquals("test/actor", plan.actorProvenance());
        assertEquals(1, plan.steps());
        assertEquals(2, plan.remainingBlocks());
        assertEquals(-1, plan.pending());
        assertEquals(0, plan.corotFlags());
        assertEquals(1, plan.beforeCells());
        assertTrue(plan.fullyConverged());
        assertEquals(FractureFixtures.GENERATION, plan.remainingGeneration());
        assertTrue(plan.retiredOwnership().isEmpty());
        // The plan's frame hashes bind the exact wire evidence.
        assertEquals(FiniteSource.sha256(staged.requestFrame()), plan.requestFrameHash());
        assertEquals(FiniteSource.sha256(staged.wireFrame()), plan.wireFrameHash());
        // The published objects are exactly the after-state the receipt claims.
        assertTrue(store.contains(plan.after().source()));
        assertTrue(store.contains(plan.after().checkpoint().blob()));
        var afterBody = store.read(plan.after().source());
        assertEquals(FiniteCodec.KIND_SOURCE, afterBody.kind());
        assertArrayEquals(staged.afterSource().serialize(),
                FiniteSource.decode(afterBody.payload()).serialize());
        assertEquals(plan.after().sourceHash(),
                FiniteSource.decode(afterBody.payload()).sourceHash());
        assertEquals(1, plan.after().checkpoint().format());
        staged.session().close();
        closeAll();
    }

    @Test void stageIsPrivateAndLeavesAuthorityUntouched() throws Exception {
        var world = openWorld();
        var requestId = new UUID(70, 11);
        var staged = stage(store, world.registry(), world,
                stagingLane(world.domain(), requestId, FractureFixtures.Variant.RETAINED),
                requestId);
        // Nothing committed: the root is still revision 0 sequence 0, the source unchanged.
        var root = world.registry().root(world.domain()).orElseThrow();
        assertEquals(0, root.worldRevision());
        assertEquals(0, root.historySequence());
        assertEquals(world.sourceRef(), root.source());
        staged.session().close();
        closeAll();
    }

    @Test void unqualifiedReceiptDiscardsAndRefuses() throws Exception {
        var world = openWorld();
        var requestId = new UUID(70, 12);
        var failure = assertThrows(FiniteFracture.NotQualified.class, () -> stage(store,
                world.registry(), world,
                stagingLane(world.domain(), requestId, FractureFixtures.Variant.PENDING),
                requestId));
        assertNotNull(failure.prepared());
        assertFalse(failure.prepared().qualified());
        // The discard ran on the private session; committed authority never moved.
        assertEquals(0, world.registry().root(world.domain()).orElseThrow().worldRevision());
        closeAll();
    }

    // ---------------------------------------------------------------- registry

    /** Commit an owner-1 binding so the fracture has something to retire. */
    private void bindOwnerOne(World world) throws IOException {
        var plan = new Operation.Bind(List.of(Operation.BindingPlan.manufactured(
                new UUID(30, 1), declaration(),
                List.of(new com.blockreality.api.geom.BlockKey(1, 64, 1)))));
        world.registry().applyCommitted(journal, commit(world.registry().prepareBind(
                request(3, plan), world.domain(), plan.plans())));
    }

    @Test void commitFractureMovesRootAndRetiresOwners() throws Exception {
        var world = openWorld();
        bindOwnerOne(world);
        var requestId = new UUID(70, 20);
        var staged = stage(store, world.registry(), world,
                stagingLane(world.domain(), requestId, FractureFixtures.Variant.REMOVED),
                requestId);
        var plan = staged.plan();
        assertEquals(List.of(1L), plan.retiredOwnership());
        var request = new Request(requestId, ACTOR, SESSION, JOURNAL, 0,
                FiniteCodec.fractureIntentHash(plan));
        var prepared = world.registry().prepareCommitFracture(request, world.domain(),
                plan, staged.requestFrame(), staged.wireFrame());
        // The intent names exactly: order + tx + root + retired binding + chunked evidence.
        var keys = prepared.changes().stream().map(Change::resource).sorted().toList();
        assertTrue(keys.contains("finite/order"));
        assertTrue(keys.contains(FiniteRegistry.txKey(requestId)));
        assertTrue(keys.contains(FiniteRegistry.rootKey(world.domain().bsiDomain())));
        assertTrue(keys.contains(FiniteRegistry.ownerKey(world.domain().artifactNamespace(), 1)));
        assertTrue(keys.contains(FiniteRegistry.fractureKey(requestId, "plan", 0)));
        assertTrue(keys.contains(FiniteRegistry.fractureKey(requestId, "request", 0)));
        assertTrue(keys.contains(FiniteRegistry.fractureKey(requestId, "wire", 0)));
        world.registry().applyCommitted(journal, commit(prepared));
        var root = world.registry().root(world.domain()).orElseThrow();
        assertEquals(1, root.worldRevision());
        assertEquals(1, root.historySequence());
        assertEquals(plan.after().source(), root.source());
        assertEquals(plan.after().checkpoint(), root.checkpoint());
        var binding = world.registry().binding(world.domain().artifactNamespace(), 1).orElseThrow();
        assertFalse(binding.active());
        staged.session().close();
        closeAll();

        // Fold survives a restart byte-for-byte.
        var registry2 = open();
        var folded = registry2.root(world.domain()).orElseThrow();
        assertEquals(root, folded);
        closeAll();
    }

    @Test void tamperedFractureEvidenceAllRefuse() throws Exception {
        var world = openWorld();
        var requestId = new UUID(70, 21);
        var staged = stage(store, world.registry(), world,
                stagingLane(world.domain(), requestId, FractureFixtures.Variant.RETAINED),
                requestId);
        var plan = staged.plan();
        var request = new Request(requestId, ACTOR, SESSION, JOURNAL, 0,
                FiniteCodec.fractureIntentHash(plan));
        var prepared = world.registry().prepareCommitFracture(request, world.domain(),
                plan, staged.requestFrame(), staged.wireFrame());
        var intent = prepared.intent();
        staged.session().close();
        for (String target : List.of("plan/0", "request/0", "wire/0")) {
            var changes = new ArrayList<Change>();
            for (var change : intent.changes()) {
                var tampered = change;
                if (change.resource().equals(FiniteRegistry.fractureKey(requestId,
                        target.substring(0, target.indexOf('/')), 0)))
                    tampered = new Change(change.resource(), change.before(), tamper(change.after()));
                changes.add(tampered);
            }
            var mutated = new ConstructionTransaction.Intent(request, changes, List.of(), List.of());
            var entry = Entry.prepared(mutated);
            assertThrows(IOException.class, () -> world.registry().validatePrepared(entry), target);
        }
        // The tx descriptor itself is evidence-protected too.
        var changes = new ArrayList<Change>();
        for (var change : intent.changes()) {
            var tampered = change;
            if (change.resource().equals(FiniteRegistry.txKey(requestId)))
                tampered = new Change(change.resource(), change.before(), tamper(change.after()));
            changes.add(tampered);
        }
        var entry = Entry.prepared(
                new ConstructionTransaction.Intent(request, changes, List.of(), List.of()));
        assertThrows(IOException.class, () -> world.registry().validatePrepared(entry), "tx-descriptor");
        closeAll();
    }

    private static Value tamper(Value value) {
        var bytes = value.bytes();
        bytes[bytes.length / 2] ^= 0x55;
        return Value.of(bytes);
    }

    @Test void staleBasisRefusesAtPrepare() throws Exception {
        var world = openWorld();
        var requestId = new UUID(70, 22);
        var staged = stage(store, world.registry(), world,
                stagingLane(world.domain(), requestId, FractureFixtures.Variant.RETAINED),
                requestId);
        var plan = staged.plan();
        // A request bound to the wrong intent hash refuses before any durable effect.
        var wrong = new Request(requestId, ACTOR, SESSION, JOURNAL, 0, "ab".repeat(32));
        assertThrows(IllegalArgumentException.class, () -> world.registry()
                .prepareCommitFracture(wrong, world.domain(), plan,
                        staged.requestFrame(), staged.wireFrame()));
        staged.session().close();
        closeAll();
    }

    // ---------------------------------------------------------------- driver

    /** A minimal core host: no world participants, one scripted private lane per staging. */
    private final class CoreHost implements FiniteFractureHost {
        FiniteSessionTestProbe lane = new FiniteSessionTestProbe();
        FiniteDomainId domain;
        FiniteRegistry registry;
        final Map<String, Value> participants = new java.util.HashMap<>();
        int publishes, recoveredPublishes;
        Receipt lastReceipt; FiniteFracturePlan lastPlan;
        boolean failPublish;

        CoreHost domain(FiniteDomainId d, FiniteRegistry r) { domain = d; registry = r; return this; }
        @Override public void checkAccess() { }
        @Override public long revision() {
            var root = domain == null ? null : registry.root(domain).orElse(null);
            return root == null ? -1 : root.worldRevision();
        }
        @Override public Value read(String resource) {
            return participants.getOrDefault(resource, Value.missing());
        }
        @Override public void checkpoint(List<String> resources) { }
        @Override public void write(String resource, Value value) { participants.put(resource, value); }
        @Override public void flush(List<String> resources) { }
        @Override public List<Change> prepareImages(Request request, Staged staged) { return List.of(); }
        @Override public void publish(Receipt receipt, FiniteFracturePlan plan) throws IOException {
            if (failPublish) throw new IOException("lost ACK");
            publishes++; lastReceipt = receipt; lastPlan = plan;
        }
        @Override public void publishRecoveredState() { recoveredPublishes++; }
        @Override public FiniteLane openLane() { return lane; }
        @Override public long sessionGeneration() { return 77; }
        @Override public String engineBuild() { return "engine/test"; }
        @Override public String contractSha256() { return "cd".repeat(32); }
    }

    private Request fractureRequest(FiniteDomainId domain, FiniteRoot committed,
                                    FiniteSource source, UUID requestId,
                                    List<BsiCorotFracture.Rule> rules, int budget) {
        var hash = FiniteFractureDriver.intentHash(domain, committed, source, requestId,
                ACTOR, "test/actor", 1, 1 << 20, 4096, 4096, rules, budget);
        return new Request(requestId, ACTOR, SESSION, JOURNAL, committed.worldRevision(), hash);
    }

    @Test void driverCommitsAndReplaysWithoutNativeWork() throws Exception {
        var world = openWorld();
        var driver = new FiniteFractureDriver(journal, store, world.registry());
        var requestId = new UUID(70, 30);
        var host = new CoreHost().domain(world.domain(), world.registry());
        driver.recover(host);
        var rules = List.of(FractureFixtures.rule());
        host.lane = stagingLane(world.domain(), requestId, FractureFixtures.Variant.RETAINED);
        var request = fractureRequest(world.domain(), world.committed(), world.source(),
                requestId, rules, 4);
        var outcome = driver.execute(request, world.domain(), rules, 4, 1, 1 << 20,
                4096, 4096, "test/actor", host);
        assertEquals(Phase.COMMITTED, outcome.receipt().phase());
        assertEquals(1, outcome.receipt().revision());
        assertNotNull(outcome.plan());
        assertEquals(1, host.publishes);
        assertEquals(outcome.receipt(), host.lastReceipt);
        // Registry folded the commit: root advanced, after-source readable.
        var root = world.registry().root(world.domain()).orElseThrow();
        assertEquals(1, root.worldRevision());
        assertEquals(outcome.plan().after().source(), root.source());
        outcome.session().close();

        // Replay: identical request reads back the durable decision; the fresh lane has
        // no scripted replies left — any native call would have thrown.
        var host2 = new CoreHost().domain(world.domain(), world.registry());
        var replayed = driver.execute(request, world.domain(), rules, 4, 1, 1 << 20,
                4096, 4096, "test/actor", host2);
        assertEquals(Phase.COMMITTED, replayed.receipt().phase());
        assertArrayEquals(outcome.plan().encode(), replayed.plan().encode());
        assertEquals(0, host2.publishes);            // publish ran exactly once — at commit
        assertTrue(host2.lane.calls.isEmpty());      // no native work on replay
        driver.close();
        closeAll();
    }

    @Test void driverRejectsReplayConflictAndStaleBasis() throws Exception {
        var world = openWorld();
        var driver = new FiniteFractureDriver(journal, store, world.registry());
        var requestId = new UUID(70, 31);
        var host = new CoreHost().domain(world.domain(), world.registry());
        driver.recover(host);
        var rules = List.of(FractureFixtures.rule());
        host.lane = stagingLane(world.domain(), requestId, FractureFixtures.Variant.RETAINED);
        var request = fractureRequest(world.domain(), world.committed(), world.source(),
                requestId, rules, 4);
        var outcome = driver.execute(request, world.domain(), rules, 4, 1, 1 << 20,
                4096, 4096, "test/actor", host);
        assertEquals(Phase.COMMITTED, outcome.receipt().phase());
        outcome.session().close();
        // Same key, different binding → durable replay conflict.
        var conflicting = new Request(requestId, ACTOR, new UUID(55, 55), JOURNAL, 0,
                "ab".repeat(32));
        assertThrows(AtomicConstructionCoordinator.ReplayConflict.class,
                () -> driver.execute(conflicting, world.domain(), rules, 4, 1, 1 << 20,
                        4096, 4096, "test/actor", host));
        // A new key bound to a stale basis → durable REJECTED, never prepared.
        var host2 = new CoreHost().domain(world.domain(), world.registry());
        var staleId = new UUID(70, 36);
        var stale = new Request(staleId, ACTOR, SESSION, JOURNAL, 0, "ab".repeat(32));
        var rejected = driver.execute(stale, world.domain(), rules, 4, 1, 1 << 20,
                4096, 4096, "test/actor", host2);
        assertEquals(Phase.REJECTED, rejected.receipt().phase());
        assertEquals(Reason.STALE_REVISION, rejected.receipt().reason());
        assertTrue(host2.lane.calls.isEmpty());
        driver.close();
        closeAll();
    }

    @Test void unqualifiedStageBecomesDurableRejected() throws Exception {
        var world = openWorld();
        var driver = new FiniteFractureDriver(journal, store, world.registry());
        var requestId = new UUID(70, 32);
        var host = new CoreHost().domain(world.domain(), world.registry());
        driver.recover(host);
        host.lane = stagingLane(world.domain(), requestId, FractureFixtures.Variant.PENDING);
        var rules = List.of(FractureFixtures.rule());
        var request = fractureRequest(world.domain(), world.committed(), world.source(),
                requestId, rules, 4);
        var outcome = driver.execute(request, world.domain(), rules, 4, 1, 1 << 20,
                4096, 4096, "test/actor", host);
        assertEquals(Phase.REJECTED, outcome.receipt().phase());
        assertEquals(Reason.VALIDATION_REFUSED, outcome.receipt().reason());
        // The key is spent: nothing may reuse it.
        assertThrows(AtomicConstructionCoordinator.ReplayConflict.class,
                () -> driver.execute(new Request(requestId, ACTOR, SESSION, JOURNAL, 0,
                        "cd".repeat(32)), world.domain(), rules, 4, 1, 1 << 20,
                        4096, 4096, "test/actor", host));
        // The committed root never moved.
        assertEquals(0, world.registry().root(world.domain()).orElseThrow().worldRevision());
        driver.close();
        closeAll();
    }

    @Test void committedCrashFoldsForwardAfterRestart() throws Exception {
        var world = openWorld();
        var driver = new FiniteFractureDriver(journal, store, world.registry());
        var requestId = new UUID(70, 33);
        var host = new CoreHost().domain(world.domain(), world.registry());
        driver.recover(host);
        var rules = List.of(FractureFixtures.rule());
        host.lane = stagingLane(world.domain(), requestId, FractureFixtures.Variant.RETAINED);
        var request = fractureRequest(world.domain(), world.committed(), world.source(),
                requestId, rules, 4);
        var outcome = driver.execute(request, world.domain(), rules, 4, 1, 1 << 20,
                4096, 4096, "test/actor", host);
        assertEquals(Phase.COMMITTED, outcome.receipt().phase());
        var committedPlanBytes = outcome.plan().encode();
        outcome.session().close();
        driver.close();
        closeAll();                                     // the crash: all state gone

        // Fresh journal/store/registry over the same directories, new driver.
        var registry2 = open();
        var driver2 = new FiniteFractureDriver(journal, store, registry2);
        var host2 = new CoreHost().domain(world.domain(), registry2);
        driver2.recover(host2);
        assertEquals(1, host2.recoveredPublishes);
        var folded = registry2.root(world.domain()).orElseThrow();
        assertEquals(1, folded.worldRevision());
        assertEquals(1, folded.historySequence());
        // The durable replay still answers the same plan, byte for byte.
        var host3 = new CoreHost().domain(world.domain(), registry2);
        var replayed = driver2.execute(request, world.domain(), rules, 4, 1, 1 << 20,
                4096, 4096, "test/actor", host3);
        assertEquals(Phase.COMMITTED, replayed.receipt().phase());
        assertArrayEquals(committedPlanBytes, replayed.plan().encode());
        driver2.close();
        closeAll();
    }

    @Test void publishFailureRecoversToCommittedState() throws Exception {
        var world = openWorld();
        var driver = new FiniteFractureDriver(journal, store, world.registry());
        var requestId = new UUID(70, 34);
        var host = new CoreHost().domain(world.domain(), world.registry());
        driver.recover(host);
        var rules = List.of(FractureFixtures.rule());
        host.lane = stagingLane(world.domain(), requestId, FractureFixtures.Variant.RETAINED);
        host.failPublish = true;                     // lost ACK after durable COMMITTED
        var request = fractureRequest(world.domain(), world.committed(), world.source(),
                requestId, rules, 4);
        assertThrows(IOException.class, () -> driver.execute(request, world.domain(),
                rules, 4, 1, 1 << 20, 4096, 4096, "test/actor", host));
        // Recovery replays durable state, never the native token.
        var host2 = new CoreHost().domain(world.domain(), world.registry());
        driver.recover(host2);
        var root = world.registry().root(world.domain()).orElseThrow();
        assertEquals(1, root.worldRevision());
        assertEquals(1, host2.recoveredPublishes);
        driver.close();
        closeAll();
    }

    @Test void preparedEntryCrashAborts() throws Exception {
        var world = openWorld();
        var requestId = new UUID(70, 35);
        var staged = stage(store, world.registry(), world,
                stagingLane(world.domain(), requestId, FractureFixtures.Variant.RETAINED),
                requestId);
        staged.session().close();
        var request = new Request(requestId, ACTOR, SESSION, JOURNAL, 0,
                FiniteCodec.fractureIntentHash(staged.plan()));
        var prepared = world.registry().prepareCommitFracture(request, world.domain(),
                staged.plan(), staged.requestFrame(), staged.wireFrame());
        journal.create(Entry.prepared(prepared.intent()));
        // Crash before decision: a fresh driver recovers — the entry aborts, no commit.
        var driver = new FiniteFractureDriver(journal, store, world.registry());
        var host = new CoreHost().domain(world.domain(), world.registry());
        driver.recover(host);
        var entry = journal.read(requestId).orElseThrow();
        assertEquals(Phase.ABORTED, entry.phase());
        assertEquals(0, world.registry().root(world.domain()).orElseThrow().worldRevision());
        driver.close();
        closeAll();
    }
}
