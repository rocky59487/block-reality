package com.blockreality.core.protocol;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.EngineCatalogue;
import com.blockreality.api.GoverningFibre;
import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolCodecTest {

    private static final WorldRevision REV = new WorldRevision(7);

    // A complete, schema-valid reply: one member (one station), one shell, one
    // unassigned block. The strict tests below delete or corrupt exactly one field at
    // a time, so every literal here is written once and referenced by replacement.
    private static String fullReply() {
        return """
            {"ok":true,"op":"solve","revision":7,
             "singular":false,"islands":1,"singularIslands":0,
             "bucklingFactor":14.25,
             "nodes":6,"dof":36,
             "equilibrium":{"applied":[0,-20000,0],"reaction":[0,20000,0],"residual":1.5e-15},
             "maxDC":0.25,"governing":1,"governingKind":"member",
             "members":[{
               "id":1,"mat":"steel","section":"steel_rect_200x400",
               "lengthMm":4000,"dc":0.25,
               "governingFibre":"CRUSH","governingStation":0,
               "i":{"N":0,"Vy":20000,"Vz":0,"T":0,"My":0,"Mz":-80000000},
               "j":{"N":0,"Vy":0,"Vz":0,"T":0,"My":0,"Mz":0},
               "field":{"origin":[500,64500,500],"ax":[1,0,0],"ay":[0,1,0],"az":[0,0,1],
                        "A":80000,"Iy":266666666,"Iz":1066666666,"cy":100,"cz":200,
                        "wy":-6.16,"wz":0},
               "blocks":[[0,64,0],[1,64,0]],
               "stations":[{
                 "x":0,"world":[500,64500,500],
                 "fibres":[
                   {"name":"TOP_Y","offsetMm":200,"sigma":-15,"dir":[0,1,0]},
                   {"name":"BOT_Y","offsetMm":200,"sigma":15,"dir":[0,-1,0]},
                   {"name":"PLUS_Z","offsetMm":100,"sigma":0,"dir":[0,0,1]},
                   {"name":"MINUS_Z","offsetMm":100,"sigma":0,"dir":[0,0,-1]}],
                 "sigmaTens":15,"sigmaComp":15,"tau":0.375,"naY":0}]
             }],
             "shells":[{
               "id":1,"mat":"concrete","plate":"concrete_slab_200","t":200,
               "dc":0.1,"face":"TOP","corner":2,
               "blocks":[[0,70,0],[1,70,0],[1,70,1],[0,70,1]],
               "world":[[500,70500,500],[1500,70500,500],[1500,70500,1500],[500,70500,1500]],
               "ex":[1,0,0],"ey":[0,0,1],"n":[0,1,0],
               "N":{"xx":-10,"yy":-10,"xy":0},
               "M":{"xx":100000,"yy":100000,"xy":0},
               "Q":{"x":0,"y":0},
               "Mc":[[100000,100000,0],[100000,100000,0],[100000,100000,0],[100000,100000,0]],
               "vmTop":8,"vmBot":3,
               "dcRaw":0.1,"edgeRecovered":false
             }],
             "unassigned":[[5,64,0]]}
            """;
    }

    @Test
    void solveRequestCarriesBlocksAndMaterialsOnly() {
        SolveRequest r = SolveRequest.builder(REV)
                .block(new BlockKey(0, 64, 0), "steel", "steel_rect_200x400", true)
                .block(new BlockKey(1, 64, 0), "steel", "steel_rect_200x400", false)
                .load(SolveRequest.PointLoad.downwards(new BlockKey(1, 64, 0), 20_000))
                .build();

        JsonValue v = JsonValue.parse(ProtocolCodec.encodeSolve(r));
        assertEquals("solve", v.str("op", ""));
        assertEquals(7, v.i64("revision", -1));
        assertEquals(2, v.arr("blocks").size());
        assertTrue(v.arr("blocks").get(0).bool("support", false));
        assertEquals("steel_rect_200x400", v.arr("blocks").get(0).str("section", ""));
        assertEquals(-20_000.0, v.arr("loads").get(0).num("fy", 0), 0);

        // D-006: nothing describing the structural model may appear in the request.
        for (String forbidden : new String[]{"members", "nodes", "elements", "stiffness", "supportsDof"}) {
            assertFalse(v.has(forbidden), "request leaked a model concept: " + forbidden);
        }
    }

    @Test
    void solveShmDoorbellCarriesRevisionAndByteCount() {
        // The byte count is the frame boundary (#27). It must travel as a plain
        // integer so the exact-integer reading accepts it at the far end.
        JsonValue v = JsonValue.parse(ProtocolCodec.encodeSolveShm(7, 4096));
        assertEquals("solve.shm", v.str("op", ""));
        assertTrue(v.isExactInt("revision"));
        assertEquals(7, v.exactI64("revision"));
        assertTrue(v.isExactInt("bytes"));
        assertEquals(4096, v.exactI64("bytes"));
    }

    // ------------------------------------------------------------------- solve
    @Test
    void aCompleteReplyDecodesWithEveryFieldCarried() {
        AnalysisResult r = ProtocolCodec.decodeSolve(fullReply(), REV);
        assertTrue(r.ok(), r.diagnostic());
        assertTrue(r.isUsable());
        assertEquals(0.25, r.maxDc(), 0);
        assertEquals("member", r.governingKind());
        assertEquals(1, r.islands());
        assertEquals(1.5e-15, r.equilibriumResidual(), 0);
        assertEquals(14.25, r.bucklingFactor(), 0);
        assertEquals(1, r.members().size());
        assertEquals(1, r.shells().size());
        assertEquals(1, r.unassigned().size());
        assertEquals(GoverningFibre.CRUSH, r.members().get(0).governingFibre());
        assertTrue(r.members().get(0).field().isPresent());
        assertTrue(r.shells().get(0).governingTopFace());
        assertEquals(Optional.of(0.0), r.members().get(0).stations().get(0).naOffsetYMm());
        assertTrue(r.members().get(0).stations().get(0).naOffsetZMm().isEmpty());
    }

    @Test
    void missingRequiredFieldsFailTheReplyAndNameTheField() {
        // Behaviour change under #31: a reply that claims ok but lacks a required
        // field used to decode with the gap filled by a default — maxDC 0, empty
        // members — which turned a half-written reply into "everything is fine".
        // Now the reply fails and the message says which field broke it.
        record Case(String cut, String named) { }
        for (Case c : new Case[] {
                new Case("\"maxDC\":0.25,", "maxDC"),
                new Case("\"equilibrium\":{\"applied\":[0,-20000,0],\"reaction\":[0,20000,0],\"residual\":1.5e-15},",
                         "equilibrium"),
                new Case(",\"Mz\":-80000000", "members[0].i.Mz"),
                new Case("\"sigma\":-15,", "members[0].stations[0].fibres[0].sigma"),
                new Case("\"singular\":false,", "singular"),
        }) {
            String line = fullReply().replace(c.cut(), "");
            assertFalse(line.equals(fullReply()), "mutation did not apply: " + c.cut());
            AnalysisResult r = ProtocolCodec.decodeSolve(line, REV);
            assertFalse(r.ok(), "should have failed without " + c.named());
            assertTrue(r.diagnostic().contains(c.named()),
                    "diagnostic should name " + c.named() + " but was: " + r.diagnostic());
        }
    }

    @Test
    void unknownGoverningFibreFailsTheReplyRatherThanDegrading() {
        // Inside a reply that claims ok, an unknown fibre means the engine speaks a
        // vocabulary this build does not. Mapping it to NONE would show a governing
        // member with no governing reason.
        String line = fullReply().replace("\"governingFibre\":\"CRUSH\"",
                                          "\"governingFibre\":\"SOMETHING_NEW\"");
        AnalysisResult r = ProtocolCodec.decodeSolve(line, REV);
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("governingFibre"), r.diagnostic());
    }

    @Test
    void wrongTypedRequiredFieldsFail() {
        // Type confusion is the same failure as absence: the field the schema names
        // is not there, whatever else happens to occupy the key.
        String line = fullReply().replace("\"maxDC\":0.25", "\"maxDC\":\"0.25\"");
        AnalysisResult r = ProtocolCodec.decodeSolve(line, REV);
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("maxDC"), r.diagnostic());

        String face = fullReply().replace("\"face\":\"TOP\"", "\"face\":\"SIDE\"");
        AnalysisResult rf = ProtocolCodec.decodeSolve(face, REV);
        assertFalse(rf.ok());
        assertTrue(rf.diagnostic().contains("face"), rf.diagnostic());
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
                fullReply().replace("\"revision\":7", "\"revision\":6"), REV);
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("revision mismatch"));
    }

    @Test
    void aRevisionWrittenAsADoubleIsRejected() {
        // 7.0 equals 7, but a revision that arrives as a double literal has been
        // through a representation that folds values above 2^53 — the exact-integer
        // reading exists precisely so this field can never take that path.
        AnalysisResult r = ProtocolCodec.decodeSolve(
                fullReply().replace("\"revision\":7", "\"revision\":7.0"), REV);
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("revision"), r.diagnostic());
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
                "{\"ok\":true,\"revision\":7,\"singular\":true,\"islands\":1,\"singularIslands\":1,"
                        + "\"diagnostic\":\"mechanism\",\"equilibrium\":{\"residual\":0},"
                        + "\"maxDC\":0,\"governing\":-1,\"members\":[],\"shells\":[],\"unassigned\":[]}", REV);
        assertTrue(r.ok(), r.diagnostic());
        assertTrue(r.singular());
        assertFalse(r.isUsable(), "a mechanism has no numbers to report");
        assertEquals("mechanism", r.diagnostic());
    }

    @Test
    void absentNeutralAxisStaysAbsent() {
        // A fully tensile section genuinely has no neutral axis. Defaulting the field to
        // zero would draw one through the centroid of a member that has none.
        String line = fullReply().replace(",\"naY\":0", "");
        AnalysisResult r = ProtocolCodec.decodeSolve(line, REV);
        assertTrue(r.isUsable(), r.diagnostic());
        assertFalse(r.members().get(0).stations().get(0).hasNeutralAxis());
        assertEquals(-15.0, r.members().get(0).stations().get(0).fibres().get(0).sigmaMpa(), 0);
    }

    @Test
    void unknownFailModeDegradesRatherThanThrowingInTheApiHelper() {
        // GoverningFibre.fromWire keeps its degrade-to-NONE for casual callers; the
        // STRICT rejection of unknown names lives in decodeSolve, which refuses the
        // reply instead of consulting this helper.
        assertEquals(GoverningFibre.NONE, GoverningFibre.fromWire("SOMETHING_NEW"));
        assertEquals(GoverningFibre.NONE, GoverningFibre.fromWire(null));
        assertEquals(GoverningFibre.CRUSH, GoverningFibre.fromWire("crush"));
    }

    // ------------------------------------------------------------------- hello
    private static String fullHello() {
        return "{\"ok\":true,\"op\":\"hello\",\"engine\":\"FrameCore\",\"protocol\":1,\"shm\":1,"
                + "\"materials\":[\"steel\",\"concrete\"],"
                + "\"sections\":[\"steel_rect_200x400\"],"
                + "\"plates\":[{\"id\":\"concrete_slab_200\",\"t\":200}]}";
    }

    @Test
    void aCompleteHelloYieldsTheCatalogue() {
        Optional<EngineCatalogue> cat = ProtocolCodec.decodeHello(fullHello());
        assertTrue(cat.isPresent());
        assertEquals("FrameCore", cat.get().engine());
        assertTrue(cat.get().isCompatible());
        assertTrue(cat.get().supportsShm());
        assertEquals(2, cat.get().materials().size());
        assertTrue(cat.get().hasSection("steel_rect_200x400"));
        assertTrue(cat.get().hasPlate("concrete_slab_200"));
    }

    @Test
    void helloWithoutShmIsJsonOnlyNotRejected() {
        // shm is a capability, not a requirement: engines that predate the binary
        // transport simply stay on JSON.
        Optional<EngineCatalogue> cat = ProtocolCodec.decodeHello(
                fullHello().replace("\"shm\":1,", ""));
        assertTrue(cat.isPresent());
        assertFalse(cat.get().supportsShm());
    }

    @Test
    void helloIsRejectedWhenNotOk() {
        assertTrue(ProtocolCodec.decodeHello("{\"ok\":false,\"error\":\"x\"}").isEmpty());
        assertTrue(ProtocolCodec.decodeHello("garbage").isEmpty());
    }

    @Test
    void helloIsRejectedWhenTheCatalogueCannotServeAsTheIndexAbi() {
        // The catalogue is the index ABI of the binary wire (#34): tokens travel as
        // indices into these lists. Each rejection below is a catalogue on which two
        // ends could agree about a number and disagree about what it names.
        for (String bad : new String[] {
                // materials missing entirely
                fullHello().replace("\"materials\":[\"steel\",\"concrete\"],", ""),
                // a plate without an id has no token to index
                fullHello().replace("{\"id\":\"concrete_slab_200\",\"t\":200}", "{\"t\":200}"),
                // duplicate section: two indices, one name
                fullHello().replace("[\"steel_rect_200x400\"]",
                                    "[\"steel_rect_200x400\",\"steel_rect_200x400\"]"),
                // a token that is both a section and a plate has no single role
                fullHello().replace("\"id\":\"concrete_slab_200\"", "\"id\":\"steel_rect_200x400\""),
                // a plate with a non-positive thickness is not a plate
                fullHello().replace("\"t\":200", "\"t\":0"),
                fullHello().replace("\"t\":200", "\"t\":-50"),
                // protocol written as a double defeats the exact-integer version gate
                fullHello().replace("\"protocol\":1", "\"protocol\":1.0"),
                // shm present but not a plain non-negative integer
                fullHello().replace("\"shm\":1", "\"shm\":1.5"),
                fullHello().replace("\"shm\":1", "\"shm\":-1"),
                // a material that is not a string cannot be a token
                fullHello().replace("\"materials\":[\"steel\",\"concrete\"]",
                                    "\"materials\":[\"steel\",42]"),
        }) {
            assertTrue(ProtocolCodec.decodeHello(bad).isEmpty(), "should have rejected: " + bad);
        }
    }
}
