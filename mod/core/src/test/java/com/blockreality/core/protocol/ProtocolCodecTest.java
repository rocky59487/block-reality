package com.blockreality.core.protocol;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.FailMode;
import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.json.JsonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolCodecTest {

    private static final WorldRevision REV = new WorldRevision(7);

    @Test
    void solveRequestCarriesBlocksAndMaterialsOnly() {
        SolveRequest r = SolveRequest.builder(REV)
                .block(new BlockKey(0, 64, 0), "steel", "steel_h400", true)
                .block(new BlockKey(1, 64, 0), "steel", "steel_h400", false)
                .load(SolveRequest.PointLoad.downwards(new BlockKey(1, 64, 0), 20_000))
                .build();

        JsonValue v = JsonValue.parse(ProtocolCodec.encodeSolve(r));
        assertEquals("solve", v.str("op", ""));
        assertEquals(7, v.i64("revision", -1));
        assertEquals(2, v.arr("blocks").size());
        assertTrue(v.arr("blocks").get(0).bool("support", false));
        assertEquals("steel_h400", v.arr("blocks").get(0).str("section", ""));
        assertEquals(-20_000.0, v.arr("loads").get(0).num("fy", 0), 0);

        // D-006: nothing describing the structural model may appear in the request.
        for (String forbidden : new String[]{"members", "nodes", "elements", "stiffness", "supportsDof"}) {
            assertFalse(v.has(forbidden), "request leaked a model concept: " + forbidden);
        }
    }

    @Test
    void errorLineBecomesAFailedResult() {
        AnalysisResult r = ProtocolCodec.decodeSolve(
                "{\"ok\":false,\"error\":\"bad input\",\"revision\":7}", REV);
        assertFalse(r.ok());
        assertFalse(r.isUsable());
        assertEquals("bad input", r.diagnostic());
    }

    @Test
    void replyForTheWrongRevisionIsRejected() {
        // The pipe is ordered, so this means client and sidecar have lost sync. Guessing
        // which message it is would be worse than failing.
        AnalysisResult r = ProtocolCodec.decodeSolve(
                "{\"ok\":true,\"revision\":6,\"maxDC\":0.5,\"members\":[]}", REV);
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("revision mismatch"));
    }

    @Test
    void truncatedReplyIsAFailureNotAnException() {
        AnalysisResult r = ProtocolCodec.decodeSolve("{\"ok\":true,\"revi", REV);
        assertFalse(r.ok());
        assertEquals(REV, r.revision());
    }

    @Test
    void singularReplyIsOkButNotUsable() {
        AnalysisResult r = ProtocolCodec.decodeSolve(
                "{\"ok\":true,\"revision\":7,\"singular\":true,\"diagnostic\":\"mechanism\","
                        + "\"maxDC\":0,\"governing\":-1,\"members\":[],\"unassigned\":[]}", REV);
        assertTrue(r.ok());
        assertTrue(r.singular());
        assertFalse(r.isUsable(), "a mechanism has no numbers to report");
        assertEquals("mechanism", r.diagnostic());
    }

    @Test
    void absentNeutralAxisStaysAbsent() {
        // A fully tensile section genuinely has no neutral axis. Defaulting the field to
        // zero would draw one through the centroid of a member that has none.
        String line = "{\"ok\":true,\"revision\":7,\"maxDC\":0.1,\"governing\":1,\"members\":[{"
                + "\"id\":1,\"mat\":\"steel\",\"section\":\"steel_h400\",\"lengthMm\":4000,"
                + "\"dc\":0.1,\"mode\":\"TENSION\",\"i\":{\"N\":-1},\"j\":{\"N\":-1},"
                + "\"blocks\":[[0,64,0]],\"stations\":[{\"x\":0,\"world\":[0,64000,0],"
                + "\"fibres\":[{\"name\":\"TOP_Y\",\"offsetMm\":200,\"sigma\":5,\"dir\":[0,1,0]}],"
                + "\"sigmaTens\":5,\"sigmaComp\":0,\"tau\":0}]}],\"unassigned\":[]}";
        AnalysisResult r = ProtocolCodec.decodeSolve(line, REV);
        assertTrue(r.isUsable());
        assertEquals(FailMode.TENSION, r.members().get(0).mode());
        assertFalse(r.members().get(0).stations().get(0).hasNeutralAxis());
        assertEquals(5.0, r.members().get(0).stations().get(0).fibres().get(0).sigmaMpa(), 0);
    }

    @Test
    void unknownFailModeDegradesRatherThanThrowing() {
        // A newer engine may add modes. An unknown token must not take the reply down.
        assertEquals(FailMode.NONE, FailMode.fromWire("SOMETHING_NEW"));
        assertEquals(FailMode.NONE, FailMode.fromWire(null));
        assertEquals(FailMode.CRUSH, FailMode.fromWire("crush"));
    }

    @Test
    void helloIsRejectedWhenNotOk() {
        assertTrue(ProtocolCodec.decodeHello("{\"ok\":false,\"error\":\"x\"}").isEmpty());
        assertTrue(ProtocolCodec.decodeHello("garbage").isEmpty());
    }
}
