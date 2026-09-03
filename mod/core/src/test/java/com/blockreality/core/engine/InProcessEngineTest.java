package com.blockreality.core.engine;

import com.blockreality.core.bsi.BsiContract;
import com.blockreality.core.bsi.BsiRecords;
import com.blockreality.core.bsi.BsiResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Java half of C-2: the same request, through the same C ABI the conformance runner drives,
 * must come back the same way (N24-b5).
 *
 * <p>Gated on {@code -Dbr.engine=<library>}: without a real engine there is nothing to talk to,
 * and a test that quietly passes when its subject is absent is worse than no test. CI always
 * supplies one (it builds libbsi_tectonic in the same job).
 *
 * <p>The world is C4-cantilever-selfweight from {@code contract/conformance}: five steel cells
 * with ground at x=-1. Reaction must be rho*A*g*L, and the number is not computed here — it is
 * the closed form the corpus states, so this test and the corpus cannot drift into agreeing on a
 * wrong value.
 */
class InProcessEngineTest {

    private static final String VOCAB = """
            {"version":1,
             "materials":[
               {"name":"steel","role":"member","model":"isotropic","E":2.0e11,"nu":0.3,"rho":7850,
                "allow":{"sigmaC":2.5e8,"sigmaT":2.5e8,"tau":1.45e8},"defaultSection":"steel_rect_200x400"},
               {"name":"ground_rigid","role":"support","supportKind":"fixAll"}],
             "sections":[{"name":"steel_rect_200x400","kind":"rect","p":[0.2,0.4]}]}""";

    private static Path engineLibrary() {
        String p = System.getProperty("br.engine", "");
        if (p.isBlank()) p = System.getenv("BR_ENGINE");
        Assumptions.assumeTrue(p != null && !p.isBlank() && Files.isRegularFile(Path.of(p)),
                "set -Dbr.engine=<libbsi_*.so> to run the cross-language leg");
        return Path.of(p);
    }

    @Test
    void aCantileverUnderItsOwnWeightComesBackWithTheClosedFormReaction() {
        Path lib = engineLibrary();
        try (InProcessEngine eng = InProcessEngine.open(lib, 1)) {
            assertEquals(InProcessEngine.Status.READY, eng.status(),
                    () -> "handshake failed: " + eng.disabledReason());
            assertFalse(eng.engineName().isEmpty());
            assertTrue(eng.declareVocabulary(VOCAB), "vocab.declare");

            List<BsiRecords.Block> world = List.of(
                    BsiRecords.Block.of(-1, 0, 0, 1, -1, 0),          // ground under the root face
                    BsiRecords.Block.of(0, 0, 0, 0, -1, 0),
                    BsiRecords.Block.of(1, 0, 0, 0, -1, 0),
                    BsiRecords.Block.of(2, 0, 0, 0, -1, 0),
                    BsiRecords.Block.of(3, 0, 0, 0, -1, 0),
                    BsiRecords.Block.of(4, 0, 0, 0, -1, 0));
            assertTrue(eng.declareWorld(7, world), "world.declare");

            // Ask only for what the engine says it can do. It declares no capabilities today
            // (tectonic2 MC68: C-5, C-6 and C-11's end-block step are red until MC64/MC65a), and
            // the host refuses an undeclared section rather than inventing one -- which is the
            // behaviour under test as much as the numbers are.
            List<String> include = eng.has("bsi.readback.members") ? List.of("members") : List.of();
            BsiResponse r = eng.solve(true, new double[]{0, -9.81, 0}, List.of(), 1, include);
            assertNotNull(r);
            assertFalse(r.isError(), () -> r.code() + ": " + r.message());
            assertEquals("ok", r.status());
            assertEquals(6, r.blocks().size(), "one record per declared cell, canonical order");

            // w = rho*A*g = 7850 * 0.08 * 9.81; L = 4 m node to node (corpus C4)
            double expected = 7850 * 0.08 * 9.81 * 4.0;
            BsiResponse.Equilibrium eq = r.equilibrium();
            assertNotNull(eq, "the solve must carry an equilibrium section");
            assertEquals(expected, eq.reaction()[1], expected * 1e-9, "reaction = rho*A*g*L");
            assertEquals(-expected, eq.applied()[1], expected * 1e-9, "applied = -rho*A*g*L");
            assertTrue(eq.residual() <= 1e-9, "residual " + eq.residual());

            if (eng.has("bsi.readback.members")) {
                BsiResponse.Member m = r.members().get(0);
                assertEquals(4.0, m.lengthM(), 1e-9, "node to node");
                assertEquals(expected / 2, Math.abs(m.endI()[5]), expected * 1e-9 * 4, "root moment |Mz| = wL^2/2");
            }
        }
    }

    @Test
    void aForeignContractHashIsRefusedAndNothingElseIsSent() {
        Path lib = engineLibrary();
        try (BsiNative n = BsiNative.open(lib, "{}")) {
            String wrong = "0".repeat(64);
            byte[] reply = n.call(com.blockreality.core.bsi.BsiFrame.encode(
                    com.blockreality.core.bsi.BsiHeaders.hello("r1", 1, "test", wrong, 0), null));
            BsiResponse r = BsiResponse.of(com.blockreality.core.bsi.BsiFrame.decode(reply, reply.length));
            assertNotNull(r);
            assertTrue(r.isError());
            assertEquals("BSI_VERSION", r.code(), "a foreign contract is refused at the handshake (N24-b2)");

            // and the session stays refused: the next verb must not be answered
            byte[] after = n.call(com.blockreality.core.bsi.BsiFrame.encode(
                    com.blockreality.core.bsi.BsiHeaders.worldDeclare("r2", 1, 0, 0), new byte[0]));
            BsiResponse r2 = BsiResponse.of(com.blockreality.core.bsi.BsiFrame.decode(after, after.length));
            assertNotNull(r2);
            assertTrue(r2.isError());
            assertEquals("BSI_VERSION", r2.code(), "the session is poisoned, not merely unhappy");
        }
    }

    @Test
    void thisBuildKnowsWhichContractItSpeaks() {
        assertTrue(BsiContract.available(), "the build must copy contract/CONTRACT_SHA256 into the jar");
        assertTrue(BsiContract.sha256().matches("[0-9a-f]{64}"));
        assertEquals(1, BsiContract.MAJOR);
        assertEquals(1, BsiContract.CAPI_ABI);
    }

    /**
     * BSI_ADD1 G-D/G-E: the contract now bounds numThreads to 1..256 and fails the open on any
     * unknown non-{@code x-} key. Needs no engine — what is under test is the string this side
     * builds, and building an out-of-range one would take the mod down at load time.
     */
    @Test
    void openOptionsStayInsideWhatTheContractAccepts() {
        assertEquals("{\"log\":0,\"numThreads\":4}", InProcessEngine.openOptions(4));
        assertEquals("{\"log\":0}", InProcessEngine.openOptions(0),
                "no preference is spelled by omitting the key, not by sending 0 or 1");
        assertEquals("{\"log\":0}", InProcessEngine.openOptions(-1));
        assertEquals("{\"log\":0,\"numThreads\":256}", InProcessEngine.openOptions(9999),
                "a machine with more cores than the contract allows still has to ask for 256");
        for (int n : new int[]{-1, 0, 1, 2, 255, 256, 257, 1 << 20}) {
            String o = InProcessEngine.openOptions(n);
            assertFalse(o.contains("numThreads\":0"), o);
            int i = o.indexOf("\"numThreads\":");
            if (i < 0) continue;
            int v = Integer.parseInt(o.substring(i + 13, o.length() - 1));
            assertTrue(v >= 1 && v <= 256, "numThreads=" + v + " is outside the contract's 1..256");
        }
    }

    @Test
    void anEngineThatIsNotOneIsRefusedWithAReason() throws Exception {
        Path notAnEngine = Files.createTempFile("not-an-engine", ".so");
        Files.write(notAnEngine, new byte[]{0x7f, 'E', 'L', 'F', 0, 0, 0});
        try {
            InProcessEngine eng = InProcessEngine.open(notAnEngine, 1);
            assertEquals(InProcessEngine.Status.DISABLED, eng.status());
            assertNotNull(eng.disabledReason());
            assertFalse(eng.disabledReason().detail().isEmpty(), "a refusal always says why");
        } finally {
            Files.deleteIfExists(notAnEngine);
        }
    }
}
