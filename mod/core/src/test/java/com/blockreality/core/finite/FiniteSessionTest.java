package com.blockreality.core.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.BsiCorot;
import com.blockreality.core.bsi.BsiFrame;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiResponse;
import com.blockreality.core.bsi.BsiVocabulary;
import com.blockreality.core.json.JsonValue;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.blockreality.core.finite.FiniteFixtures.*;

/**
 * Finite session against a scripted native-shaped peer: ordering, identity binding and
 * every refusal path is exercised without a native library. The lane's
 * {@code declareFiniteWorld} speaks the same identified world-declare wire the
 * production {@code InProcessEngine} does.
 */
class FiniteSessionTest {

    /** A scripted {@link FiniteLane}: each request pops one scripted answer. */
    private static final class ScriptedLane implements FiniteLane {
        record Call(String method, String header, byte[] payload) { }
        final List<Call> calls = new ArrayList<>();
        final Deque<Scripted> script = new ArrayDeque<>();
        /** The scripted engine's declared capabilities — the W4-A core set by default. */
        final java.util.Set<String> capabilities = new java.util.HashSet<>(List.of(
                "bsi.corot", "bsi.corot.fracture", "bsi.fracture",
                "bsi.corot.fracture.loads", "bsi.corot.shell.materials"));
        boolean vocabularyAdopted;
        boolean worldDeclared;
        int worldSequence;

        private sealed interface Scripted { }
        private record Reply(String header, byte[] payload) implements Scripted { }
        private record Refuse(String code) implements Scripted { }

        void reply(String header) { script.add(new Reply(header, new byte[0])); }
        void reply(String header, byte[] payload) { script.add(new Reply(header, payload)); }
        void error(String code) { script.add(new Refuse(code)); }

        @Override public BsiResponse request(String headerJson, byte[] payload) {
            var header = JsonValue.parse(headerJson);
            calls.add(new Call(header.str("method", ""), headerJson, payload == null ? new byte[0] : payload));
            var next = script.poll();
            if (next == null) throw new AssertionError("unscripted native call: " + headerJson);
            if (next instanceof Refuse refused) {
                return respond("{\"bsi\":1,\"kind\":\"error\",\"id\":\"" + header.str("id", "")
                        + "\",\"method\":\"" + header.str("method", "") + "\",\"revision\":"
                        + header.exactI64("revision") + ",\"code\":\"" + refused.code()
                        + "\",\"message\":\"scripted refusal\"}", new byte[0]);
            }
            var reply = (Reply) next;
            return respond(reply.header(), reply.payload());
        }

        @Override public boolean adoptVocabulary(BsiVocabulary vocabulary) {
            vocabularyAdopted = true;
            return true;
        }

        @Override public boolean declareFiniteWorld(BsiFracture.World world) {
            String id = "w" + (++worldSequence);
            var response = request(BsiFracture.declare(id, world), world.payload());
            try {
                BsiFracture.checkWorld(response, id, world);
                worldDeclared = true;
                return true;
            } catch (IllegalArgumentException failure) {
                return false;
            }
        }

        @Override public boolean ready() { return true; }

        @Override public boolean has(String capability) { return capabilities.contains(capability); }

        static BsiResponse respond(String header, byte[] payload) {
            return BsiResponse.of(new BsiFrame.Decoded(0, header, payload));
        }
    }

    private static String worldOk(String id, UUID domain, long revision, UUID ns) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id + "\",\"method\":\"bsi.world.declare\","
                + "\"revision\":" + revision + ",\"status\":\"ok\",\"identity\":{\"domain\":\""
                + BsiFracture.hex(domain) + "\",\"revision\":" + revision
                + ",\"artifactNamespace\":\"" + BsiFracture.hex(ns) + "\"}}";
    }

    private static String analysis(String method, String status, String id, long revision,
                                   long declaredRevision, UUID domain, UUID ns, String generation) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id + "\",\"method\":\"" + method + "\","
                + "\"revision\":" + revision + ",\"status\":\"" + status + "\","
                + "\"basis\":{\"domain\":\"" + BsiFracture.hex(domain) + "\",\"revision\":" + declaredRevision + "},"
                + "\"artifactNamespace\":\"" + BsiFracture.hex(ns) + "\","
                + "\"generation\":\"" + generation + "\","
                + "\"token\":{\"context\":\"" + BsiFracture.hex(new UUID(77, 77))
                + "\",\"sequence\":\"abcd0123abcd0123\"},\"fullyConverged\":true,\"sections\":[]}";
    }

    private FiniteDomainId domain;
    private FiniteSource source;

    private void setup() {
        domain = domainId(DIMENSION);
        source = FiniteFixtures.source(domain.bsiDomain(), 4);
    }

    private FiniteCheckpointRef checkpointRef() {
        return new FiniteCheckpointRef("engine/1.5", "cd".repeat(32), 1,
                new FiniteBasis(domain, 4, 0, source.sourceHash(), source.vocabHash(), source.ownersHash(), 0),
                new FiniteBlobRef("ab".repeat(32), 100), 1 << 20, 4096, 4096);
    }

    private FiniteSession openReady(ScriptedLane lane) {
        var session = FiniteSession.attach(lane, domain, source, 11);
        lane.reply(vocabReply("f1", 0));
        assertTrue(session.declareVocabulary());
        assertTrue(lane.vocabularyAdopted);
        lane.reply(worldOk("w1", domain.bsiDomain(), 4, domain.artifactNamespace()));
        assertTrue(session.declareWorld(4));
        assertTrue(lane.worldDeclared);
        return session;
    }

    @Test void orderedLifecycleVocabWorldImportSolve() {
        setup();
        var lane = new ScriptedLane();
        var session = openReady(lane);

        var ref = checkpointRef();
        var blob = new byte[64];
        lane.reply(analysis("bsi.corot.checkpoint.import", "restored", "f2", 4, 4,
                domain.bsiDomain(), domain.artifactNamespace(), "0123456789abcdef"));
        var imported = session.importCheckpoint(ref, blob);
        assertNotNull(imported, () -> "import refused: " + session.refusal());
        assertEquals("0123456789abcdef", imported.generation());
        // The import payload is the opaque blob, byte-identical.
        var importCall = lane.calls.get(2);
        assertEquals("bsi.corot.checkpoint.import", importCall.method());
        assertArrayEquals(blob, importCall.payload());

        lane.reply(analysis("bsi.corot.solve", "analyzed", "f3", 4, 4,
                domain.bsiDomain(), domain.artifactNamespace(), "fedcba9876543210"));
        var solved = session.solve();
        assertNotNull(solved);
        assertTrue(solved.fullyConverged());
        assertEquals(domain.artifactNamespace(), solved.artifactNamespace());
        assertEquals(source.sourceHash(), solved.basis().sourceHash());
        assertEquals(11, solved.basis().sessionGeneration());
        assertEquals(new BsiFracture.Token(new UUID(77, 77), "abcd0123abcd0123"), solved.token());
        // The solve payload is the durable corot record groups, in contract order.
        var solveCall = lane.calls.get(3);
        assertEquals("bsi.corot.solve", solveCall.method());
        assertArrayEquals(source.corotPayload(), solveCall.payload());
    }

    @Test void outOfOrderCallsRefuseAndDisable() {
        setup();
        var lane = new ScriptedLane();
        var session = FiniteSession.attach(lane, domain, source, 1);
        assertNull(session.solve());                            // solve before vocabulary
        assertEquals(FiniteSession.Step.DISABLED, session.step());
        session = FiniteSession.attach(lane, domain, source, 1);
        assertFalse(session.declareWorld(4));                   // world before vocabulary
        assertEquals(FiniteSession.Step.DISABLED, session.step());
        assertFalse(session.declareVocabulary());               // a refused lane stays refused
        assertTrue(lane.calls.isEmpty());
    }

    @Test void vocabularyMappingDriftRefuses() {
        setup();
        var lane = new ScriptedLane();
        var session = FiniteSession.attach(lane, domain, source, 1);
        // The engine answers with a different id mapping than the durable source recorded.
        var drifted = vocabReply("f1", 0).replace("{\"id\":1,\"name\":\"ground_rigid\"}",
                "{\"id\":7,\"name\":\"ground_rigid\"}");
        lane.reply(drifted);
        assertFalse(session.declareVocabulary());
        assertEquals(FiniteSession.Step.DISABLED, session.step());
        assertFalse(lane.vocabularyAdopted);
    }

    @Test void foreignCheckpointBasisRefusesBeforeTheWire() {
        setup();
        var lane = new ScriptedLane();
        var session = openReady(lane);
        // A checkpoint whose basis names different world bytes never reaches the engine.
        var foreign = new FiniteCheckpointRef("engine/1.5", "cd".repeat(32), 1,
                new FiniteBasis(domain, 4, 0, "ff".repeat(32), source.vocabHash(), source.ownersHash(), 0),
                new FiniteBlobRef("ab".repeat(32), 100), 1 << 20, 4096, 4096);
        assertNull(session.importCheckpoint(foreign, new byte[64]));
        assertEquals(FiniteSession.Step.DISABLED, session.step());
        assertEquals(2, lane.calls.size());                     // nothing was sent for the import
    }

    @Test void engineErrorAndSecondImportRefuse() {
        setup();
        var lane = new ScriptedLane();
        var session = openReady(lane);
        lane.error("WORLD_STATE");                              // engine refuses the import
        assertNull(session.importCheckpoint(checkpointRef(), new byte[64]));
        assertEquals(FiniteSession.Step.DISABLED, session.step());

        lane = new ScriptedLane();
        session = openReady(lane);
        lane.reply(analysis("bsi.corot.checkpoint.import", "restored", "f2", 4, 4,
                domain.bsiDomain(), domain.artifactNamespace(), "0123456789abcdef"));
        assertNotNull(session.importCheckpoint(checkpointRef(), new byte[64]));
        assertNull(session.importCheckpoint(checkpointRef(), new byte[64]));   // no second import
        assertEquals(FiniteSession.Step.DISABLED, session.step());
        assertEquals(3, lane.calls.size());
    }

    @Test void islandVerdictsDecodeFromSections() {
        setup();
        var lane = new ScriptedLane();
        var session = openReady(lane);
        // One island, status MECHANISM, reason "jammed".
        var reason = "jammed".getBytes(StandardCharsets.UTF_8);
        var payload = ByteBuffer.allocate(96 + reason.length).order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(1).putInt(0).putInt(2);                  // status, freeDof, flags
        for (int i = 0; i < 7; i++) payload.putInt(0);          // counters
        for (int i = 0; i < 6; i++) payload.putDouble(0);       // residuals
        payload.putInt(0).putInt(reason.length);                // reasonFirst, reasonCount
        payload.put(reason);
        var header = analysis("bsi.corot.solve", "analyzed", "f2", 4, 4,
                domain.bsiDomain(), domain.artifactNamespace(), "0123456789abcdef")
                .replace("\"sections\":[]",
                        "\"sections\":[{\"name\":\"corotIslands\",\"offset\":0,\"bytes\":96,\"count\":1},"
                                + "{\"name\":\"corotText\",\"offset\":96,\"bytes\":" + reason.length
                                + ",\"count\":" + reason.length + "}]");
        lane.reply(header, payload.array());
        var result = session.solve();
        assertNotNull(result, () -> "solve refused: " + session.refusal());
        assertEquals(1, result.islands().size());
        var island = result.islands().get(0);
        assertEquals(1, island.status());
        assertEquals("MECHANISM", island.statusName());
        assertEquals(2, island.flags());
        assertEquals("jammed", island.reason());
    }

    @Test void mismatchedResponseIdentityDisables() {
        setup();
        var lane = new ScriptedLane();
        var session = openReady(lane);
        // The engine answers about a foreign basis revision — never acceptable.
        lane.reply(analysis("bsi.corot.solve", "analyzed", "f2", 4, 9,
                domain.bsiDomain(), domain.artifactNamespace(), "0123456789abcdef"));
        assertNull(session.solve());
        assertEquals(FiniteSession.Step.DISABLED, session.step());
    }

    // ---------------------------------------------------------------- W3 arc advance

    private static final BsiFracture.Token ARC_A =
            new BsiFracture.Token(new UUID(0xA1, 0xA1), "aaaa1111aaaa1111");
    private static final BsiFracture.Token ARC_B =
            new BsiFracture.Token(new UUID(0xB2, 0xB2), "bbbb2222bbbb2222");
    private static final BsiFracture.Token ARC_C =
            new BsiFracture.Token(new UUID(0xC3, 0xC3), "cccc3333cccc3333");

    private static String solveOk(String id, UUID domain, long revision, UUID ns,
                                  BsiFracture.Token token, String generation, boolean converged) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id + "\",\"method\":\"bsi.corot.solve\","
                + "\"revision\":" + revision + ",\"status\":\"analyzed\","
                + "\"basis\":{\"domain\":\"" + BsiFracture.hex(domain) + "\",\"revision\":" + revision + "},"
                + "\"artifactNamespace\":\"" + BsiFracture.hex(ns) + "\","
                + "\"generation\":\"" + generation + "\","
                + "\"token\":{\"context\":\"" + BsiFracture.hex(token.context())
                + "\",\"sequence\":\"" + token.sequence() + "\"},\"fullyConverged\":" + converged
                + ",\"sections\":[]}";
    }

    @Test void solveThenArcAdvancesAndMintsLiveTokens() {
        setup();
        var lane = new ScriptedLane();
        var session = openReady(lane);
        lane.reply(solveOk("f2", domain.bsiDomain(), 4, domain.artifactNamespace(),
                ARC_A, "0123456789abcdef", true));
        assertNotNull(session.solve());

        var options = CorotArcFixtures.arcOptions();
        lane.reply(CorotArcFixtures.arcReply("f3", 4, domain.bsiDomain(), domain.artifactNamespace(),
                        "fedcba9876543210", ARC_B, 1.25, 0.5, 1e-12),
                CorotArcFixtures.payload(domain.bsiDomain(), 4));
        var stepped = session.arcAdvance(options);
        assertNotNull(stepped, () -> "arc refused: " + session.refusal());
        assertTrue(stepped.fullyConverged());
        assertEquals(ARC_B, stepped.token());
        assertEquals(1.25, stepped.loadFactor(), 1e-12);
        assertTrue(stepped.loadFactorReported());
        assertNotNull(stepped.arc());
        assertEquals(0.5, stepped.arc().radius(), 1e-12);
        assertEquals(1e-12, stepped.arc().constraintResidual(), 1e-15);

        // The wire carried an EMPTY payload and consumed the live token, not an older one.
        var call = lane.calls.get(3);
        assertEquals("bsi.corot.arc.advance", call.method());
        assertEquals(0, call.payload().length);
        var body = JsonValue.parseStrict(call.header()).objField("body");
        assertEquals(BsiFracture.hex(ARC_A.context()),
                body.objField("initialAnalysis").str("context", ""));
        assertEquals(ARC_A.sequence(), body.objField("initialAnalysis").str("sequence", ""));

        // The minted token feeds the next step: a second advance consumes ARC_B.
        lane.reply(CorotArcFixtures.arcReply("f4", 4, domain.bsiDomain(), domain.artifactNamespace(),
                        "aaaabbbbccccdddd", ARC_C, 1.5, 0.5, 0.0),
                CorotArcFixtures.payload(domain.bsiDomain(), 4));
        assertNotNull(session.arcAdvance(options));
        body = JsonValue.parseStrict(lane.calls.get(4).header()).objField("body");
        assertEquals(BsiFracture.hex(ARC_B.context()),
                body.objField("initialAnalysis").str("context", ""));
    }

    @Test void arcRequiresConvergedLiveTokenAndRefusesEcho() {
        setup();
        var lane = new ScriptedLane();
        var session = openReady(lane);
        // Out of order: no live token yet.
        assertNull(session.arcAdvance(CorotArcFixtures.arcOptions()));
        assertEquals(FiniteSession.Step.DISABLED, session.step());

        lane = new ScriptedLane();
        session = openReady(lane);
        // A non-converged solve is a valid answer but not an arc source.
        lane.reply(solveOk("f2", domain.bsiDomain(), 4, domain.artifactNamespace(),
                ARC_A, "0123456789abcdef", false));
        assertNotNull(session.solve());
        assertNull(session.arcAdvance(CorotArcFixtures.arcOptions()));
        assertEquals(FiniteSession.Step.DISABLED, session.step());
        assertTrue(session.refusal().contains("converged"));

        // An engine that echoes the consumed token back mints nothing: protocol violation.
        lane = new ScriptedLane();
        session = openReady(lane);
        lane.reply(solveOk("f2", domain.bsiDomain(), 4, domain.artifactNamespace(),
                ARC_A, "0123456789abcdef", true));
        assertNotNull(session.solve());
        lane.reply(CorotArcFixtures.arcReply("f3", 4, domain.bsiDomain(), domain.artifactNamespace(),
                        "fedcba9876543210", ARC_A, 1.25, 0.5, 1e-12),       // echo: same token
                CorotArcFixtures.payload(domain.bsiDomain(), 4));
        assertNull(session.arcAdvance(CorotArcFixtures.arcOptions()));
        assertEquals(FiniteSession.Step.DISABLED, session.step());
    }

    @Test void nonzeroPrescribedPathIsRefusedBeforeTheWire() {
        setup();
        // Rebuild the source with one prescribed record carrying a nonzero displacement.
        var prescribed = new byte[BsiCorot.PRESCRIBED_BYTES];
        ByteBuffer.wrap(prescribed).order(ByteOrder.LITTLE_ENDIAN).putDouble(16, 0.01);
        source = FiniteSource.build(VOCAB_REQUEST, vocabReply("r1", 0),
                List.of(GROUND, STEEL),
                List.of(new BsiFracture.Owner(new BlockKey(1, 64, 1), 1)),
                Set.of(1), new byte[152], new byte[0], new byte[0], new byte[0], prescribed,
                new byte[0], options176(domain.bsiDomain(), 4));
        var lane = new ScriptedLane();
        var session = openReady(lane);
        lane.reply(solveOk("f2", domain.bsiDomain(), 4, domain.artifactNamespace(),
                ARC_A, "0123456789abcdef", true));
        assertNotNull(session.solve());
        assertNull(session.arcAdvance(CorotArcFixtures.arcOptions()));
        assertEquals(FiniteSession.Step.DISABLED, session.step());
        assertTrue(session.refusal().contains("prescribed"));
        assertEquals(3, lane.calls.size());                     // nothing was sent for the arc
    }

    @Test void engineErrorOnArcDisablesTheLane() {
        setup();
        var lane = new ScriptedLane();
        var session = openReady(lane);
        lane.reply(solveOk("f2", domain.bsiDomain(), 4, domain.artifactNamespace(),
                ARC_A, "0123456789abcdef", true));
        assertNotNull(session.solve());
        lane.error("TOKEN_STALE");
        assertNull(session.arcAdvance(CorotArcFixtures.arcOptions()));
        assertEquals(FiniteSession.Step.DISABLED, session.step());
    }

    @Test void checkpointExportCarriesTheOpaqueBlobAndBasis() {
        setup();
        var lane = new ScriptedLane();
        var session = openReady(lane);
        lane.reply(solveOk("f2", domain.bsiDomain(), 4, domain.artifactNamespace(),
                ARC_A, "0123456789abcdef", true));
        assertNotNull(session.solve());
        var blob = new byte[48];
        lane.reply("{\"bsi\":1,\"kind\":\"response\",\"id\":\"f3\",\"method\":\"bsi.corot.checkpoint.export\","
                        + "\"revision\":4,\"status\":\"exported\",\"basis\":{\"domain\":\""
                        + BsiFracture.hex(domain.bsiDomain()) + "\",\"revision\":4},"
                        + "\"format\":2,\"bytes\":48}",
                blob);
        var exported = session.exportCheckpoint(2, 1 << 20, 4096, 4096);
        assertNotNull(exported, () -> "export refused: " + session.refusal());
        assertEquals(2, exported.format());
        assertArrayEquals(blob, exported.blob());
        var call = lane.calls.get(3);
        assertEquals("bsi.corot.checkpoint.export", call.method());
        var body = JsonValue.parseStrict(call.header()).objField("body");
        assertEquals(2, body.exactI64("format"));
    }
}
