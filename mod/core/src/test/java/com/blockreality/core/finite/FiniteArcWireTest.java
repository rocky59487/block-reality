package com.blockreality.core.finite;

import com.blockreality.core.bsi.BsiCorot;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiVocabulary;
import com.blockreality.core.json.JsonValue;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.blockreality.core.finite.CorotArcFixtures.*;
import static com.blockreality.core.finite.FiniteFixtures.*;

/**
 * The {@code corot.arc.advance} wire oracles (spec O1–O4): request encoding, the canonical
 * happy-path reply, and every rejection leg — envelope identity, arc scalars, section
 * directory and record semantics. All payloads are the canonical image mutated once.
 */
class FiniteArcWireTest {

    private static final UUID NS = domainId(DIMENSION).artifactNamespace();
    private static final UUID DOM = domainId(DIMENSION).bsiDomain();
    private static final long REV = 4;

    private static BsiCorot.CorotWorld world() {
        var source = FiniteFixtures.source(DOM, REV);
        var vocabulary = new BsiVocabulary(1,
                Map.of(0, "steel", 1, "ground_rigid"), Map.of(0, "steel_rect_200x400"));
        return new BsiCorot.CorotWorld(source.blocks(), source.owners(), vocabulary,
                Map.of(0, 0), false);
    }

    private static BsiCorot.ArcResult check(String header, byte[] payload, BsiFracture.Token previous) {
        return BsiCorot.checkArc(respond(header, payload), "f4", REV, DOM, REV, NS, world(), arcOptions(), previous);
    }

    private static String reply(BsiFracture.Token token) {
        return arcReply("f4", REV, DOM, NS, GENERATION, token, 1.25, 0.5, 1e-12);
    }

    // ---------------------------------------------------------------- O1 request

    @Test void requestBodyCarriesEverySchemaField() {
        String json = BsiCorot.arcAdvance("f4", REV, DOM, REV, TOKEN_A, arcOptions());
        var body = JsonValue.parseStrict(json).objField("body");
        assertEquals("bsi.corot.arc.advance", JsonValue.parseStrict(json).str("method", ""));
        var expected = body.objField("expected");
        assertEquals(BsiFracture.hex(DOM), expected.str("domain", ""));
        assertEquals(REV, expected.exactI64("revision"));
        var initial = body.objField("initialAnalysis");
        assertEquals(BsiFracture.hex(TOKEN_A.context()), initial.str("context", ""));
        assertEquals(TOKEN_A.sequence(), initial.str("sequence", ""));
        for (String field : List.of("radius", "minRadius", "lengthScale", "loadScale",
                "relativeTolerance", "forceTolerance", "momentTolerance", "linearTolerance",
                "pathTolerance", "maxIterations", "maxAttempts", "maxBacktracks",
                "linearIterations", "restart", "initialDirection"))
            assertTrue(body.has(field), () -> "missing " + field);
        assertEquals(1, body.exactI64("initialDirection"));
        assertEquals(0.5, body.num("radius", 0), 1e-12);
    }

    @Test void requestValidationRejectsBadScalarsAndDirections() {
        // Direction is exactly ±1; budgets are [1, 1e6]; radii/scales are strictly positive.
        assertThrows(IllegalArgumentException.class, () -> new BsiCorot.ArcOptions(
                0.5, 1e-3, 1.0, 1.0, 0, 0, 0, 0, 0, 50, 4, 8, 25, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BsiCorot.ArcOptions(
                0.5, 1e-3, 1.0, 1.0, 0, 0, 0, 0, 0, 50, 4, 8, 25, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> new BsiCorot.ArcOptions(
                0.0, 1e-3, 1.0, 1.0, 0, 0, 0, 0, 0, 50, 4, 8, 25, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BsiCorot.ArcOptions(
                Double.NaN, 1e-3, 1.0, 1.0, 0, 0, 0, 0, 0, 50, 4, 8, 25, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BsiCorot.ArcOptions(
                0.5, 1e-3, 1.0, 1.0, -1.0, 0, 0, 0, 0, 50, 4, 8, 25, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BsiCorot.ArcOptions(
                0.5, 1e-3, 1.0, 1.0, 0, 0, 0, 0, 0, 0, 4, 8, 25, 1, 1));
        // A forged or empty initial token is refused before the wire.
        assertThrows(IllegalArgumentException.class, () -> BsiCorot.arcAdvance("f4", REV, DOM, REV,
                new BsiFracture.Token(new UUID(0, 0), "abcd0123abcd0123"), arcOptions()));
        assertThrows(IllegalArgumentException.class, () -> BsiCorot.arcAdvance("f4", REV, DOM, REV,
                new BsiFracture.Token(new UUID(1, 1), "not-hex"), arcOptions()));
        assertThrows(IllegalArgumentException.class, () -> BsiCorot.arcAdvance("f4", REV, DOM, REV,
                new BsiFracture.Token(new UUID(1, 1), "0000000000000000"), arcOptions()));
    }

    // ---------------------------------------------------------------- O2 happy path

    @Test void canonicalReplyValidatesAndReportsEveryScalar() {
        var result = check(reply(TOKEN_B), payload(DOM, REV), TOKEN_A);
        assertEquals(GENERATION, result.generation());
        assertEquals(TOKEN_B, result.token());
        assertEquals(1.25, result.loadFactor(), 1e-12);
        assertEquals(0.5, result.radius(), 1e-12);
        assertEquals(1e-12, result.constraintResidual(), 1e-15);
        assertEquals(1, result.islands().size());
        assertEquals(0, result.islands().get(0).status());
        var census = result.availability();
        assertTrue(census.reported());
        assertEquals(1, census.memberProfiles());
        assertEquals(1, census.memberMaterial());
        assertEquals(0, census.memberRetained());
    }

    // ---------------------------------------------------------------- O3 reply negatives

    @Test void envelopeIdentityMismatchLegsRefuse() {
        byte[] p = payload(DOM, REV);
        String good = reply(TOKEN_B);
        for (String bad : List.of(
                good.replace("\"method\":\"bsi.corot.arc.advance\"", "\"method\":\"bsi.corot.solve\""),
                good.replace("\"status\":\"advanced\"", "\"status\":\"analyzed\""),
                good.replace("\"id\":\"f4\"", "\"id\":\"f5\""),
                good.replace("\"bsi\":1,", "\"bsi\":2,"),
                good.replaceFirst("\"revision\":4,", "\"revision\":9,"),
                good.replace("\"revision\":" + REV + "},\"artifactNamespace\"",
                        "\"revision\":9},\"artifactNamespace\""),               // basis revision
                good.replace(BsiFracture.hex(DOM), BsiFracture.hex(new UUID(3, 3))),
                good.replace(BsiFracture.hex(NS), BsiFracture.hex(new UUID(4, 4))),
                good.replace(GENERATION, "0123456789abcdeZ"),
                good.replace("\"fullyConverged\":true", "\"fullyConverged\":false"))) {
            assertThrows(IllegalArgumentException.class, () -> check(bad, p, TOKEN_A), bad);
        }
        // A reply that re-uses the consumed token produced no new state.
        assertThrows(IllegalArgumentException.class, () -> check(reply(TOKEN_A), p, TOKEN_A));
        // Trailing payload bytes: the directory must end exactly at payload.length.
        assertThrows(IllegalArgumentException.class,
                () -> check(good, java.util.Arrays.copyOf(p, p.length + 8), TOKEN_A));
    }

    @Test void arcScalarViolationLegsRefuse() {
        byte[] p = payload(DOM, REV);
        for (String bad : List.of(
                reply(TOKEN_B).replace("\"loadFactor\":1.25", "\"loadFactor\":\"high\""),
                reply(TOKEN_B).replace("\"radius\":0.5", "\"radius\":0.75"),    // > request.radius 0.5
                reply(TOKEN_B).replace("\"radius\":0.5", "\"radius\":0"),
                reply(TOKEN_B).replace("\"radius\":0.5", "\"radius\":-0.1"),
                reply(TOKEN_B).replace("\"radius\":0.5", "\"radius\":1e999"),   // Infinity literal
                reply(TOKEN_B).replace("\"constraintResidual\":1.0E-12", "\"constraintResidual\":1e-9"),
                reply(TOKEN_B).replace("\"constraintResidual\":1.0E-12", "\"constraintResidual\":-1e-12"),
                reply(TOKEN_B).replace(",\"loadFactor\":1.25", ""),
                reply(TOKEN_B).replace(",\"radius\":0.5", ""),
                reply(TOKEN_B).replace(",\"constraintResidual\":1.0E-12", ""))) {
            assertThrows(IllegalArgumentException.class, () -> check(bad, p, TOKEN_A), bad);
        }
    }

    // ---------------------------------------------------------------- O4 section/record negatives

    private static byte[] mutate(String section, int relative, byte value) {
        var p = payload(DOM, REV);
        p[offset(section) + relative] = value;
        return p;
    }

    private static byte[] mutateInt(String section, int relative, int value) {
        var p = payload(DOM, REV);
        ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(offset(section) + relative, value);
        return p;
    }

    private static byte[] mutateDouble(String section, int relative, double value) {
        var p = payload(DOM, REV);
        ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putDouble(offset(section) + relative, value);
        return p;
    }

    private static byte[] edit(java.util.function.Consumer<ByteBuffer> edit) {
        var p = payload(DOM, REV);
        edit.accept(ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN));
        return p;
    }

    @Test void directoryMutationLegsRefuse() {
        String header = reply(TOKEN_B);
        // A dropped section, an unknown name and a reordered directory all fail.
        String dropped = header.replace("{\"name\":\"corotJoints\"", "{\"name\":\"corotJointsX\"");
        assertThrows(IllegalArgumentException.class, () -> check(dropped, payload(DOM, REV), TOKEN_A));
        String renamed = header.replace("\"corotJoints\"", "\"corotJointz\"");
        assertThrows(IllegalArgumentException.class, () -> check(renamed, payload(DOM, REV), TOKEN_A));
        // Reordered: put corotText before corotIslands — offsets no longer canonical.
        String dir = directory();
        int io = dir.indexOf("{\"name\":\"corotIslands\""), to = dir.indexOf("{\"name\":\"corotText\"");
        int end = dir.indexOf("{\"name\":\"corotNodes\"");
        String swapped = header.replace(dir,
                dir.substring(0, io) + dir.substring(to, end) + dir.substring(io, to) + dir.substring(end));
        assertThrows(IllegalArgumentException.class, () -> check(swapped, payload(DOM, REV), TOKEN_A));
        // Non-contiguous offset and a count/stride mismatch.
        String gapped = header.replace("\"offset\":" + offset("corotNodes"),
                "\"offset\":" + (offset("corotNodes") + 8));
        assertThrows(IllegalArgumentException.class, () -> check(gapped, payload(DOM, REV), TOKEN_A));
        String strideBad = header.replace("{\"name\":\"corotPoints\",\"offset\":" + offset("corotPoints")
                        + ",\"bytes\":" + BsiCorot.POINT_BYTES + ",\"count\":1}",
                "{\"name\":\"corotPoints\",\"offset\":" + offset("corotPoints")
                        + ",\"bytes\":" + (BsiCorot.POINT_BYTES - 8) + ",\"count\":1}");
        assertThrows(IllegalArgumentException.class, () -> check(strideBad, payload(DOM, REV), TOKEN_A));
    }

    @Test void recordMutationLegsRefuse() {
        String header = reply(TOKEN_B);
        // island status outside 0..4, and a non-converged status under fullyConverged.
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateInt("corotIslands", 0, 5), TOKEN_A));
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateInt("corotIslands", 0, 1), TOKEN_A));
        // island flags > 7 and a NaN residual.
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateInt("corotIslands", 8, 8), TOKEN_A));
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateDouble("corotIslands", 40, Double.NaN), TOKEN_A));
        // node availability that contradicts the converged island, and non-unit rotation.
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateInt("corotNodes", 20, 0), TOKEN_A));
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateDouble("corotNodes", 72, 2.0), TOKEN_A));
        // member availability bits above 7; a node id out of range; an inactive island id.
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateInt("corotMembers", 24, 8), TOKEN_A));
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateInt("corotMembers", 4, 7), TOKEN_A));
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateInt("corotMembers", 0, -1), TOKEN_A)); // active member, island -1
        // member profile backreference must name the member itself.
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateInt("corotProfiles", 0, 1), TOKEN_A));
        // member claims material state but carries no profile (O10 inconsistent leg).
        assertThrows(IllegalArgumentException.class, () -> check(header,
                edit(b -> b.putInt(offset("corotMembers") + 20, -1)   // profile = none
                          .putInt(offset("corotMembers") + 24, 5)),   // solved|material claimed
                TOKEN_A));
        // source that no world block owns (position drift), artifact drift, retired present.
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateInt("corotSources", 0, 9), TOKEN_A));
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateInt("corotSources", BsiCorot.SOURCE_BYTES + 40, 7), TOKEN_A));
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateInt("corotSources", 48, 5), TOKEN_A));
        // source index out of range, artifact member table drift, fibre law kind out of range.
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateInt("corotSourceIndices", 0, 9), TOKEN_A));
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateInt("corotArtifactMembers", 0, 1), TOKEN_A));
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateInt("corotFibers", 32, 9), TOKEN_A));
        // point loading outside {-1,0,1} and a nonzero reserved field.
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateInt("corotPoints", 112, 4), TOKEN_A));
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateInt("corotPoints", 116, 1), TOKEN_A));
        // member solved-but-zero-length is a contradiction.
        assertThrows(IllegalArgumentException.class,
                () -> check(header, mutateDouble("corotMembers", 40, 0.0), TOKEN_A));
    }

    @Test void availabilityCensusSeparatesMaterialFromRetained() {
        // The lenient census (solve path) counts the frozen rule: &4 → material,
        // &2 without &4 → retained, anything else → neither. A profile row reporting
        // only checkpoint-carried state is never promoted to "material".
        var p = payload(DOM, REV);
        var sections = respond(reply(TOKEN_B), p).sections();
        var census = BsiCorot.availability(sections, p);
        assertTrue(census.reported());
        assertEquals(1, census.memberProfiles());
        assertEquals(1, census.memberMaterial());
        assertEquals(0, census.memberRetained());
        assertTrue(census.materialPresent());

        ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(offset("corotProfiles") + 4, 3);
        census = BsiCorot.availability(sections, p);
        assertEquals(1, census.memberProfiles());
        assertEquals(0, census.memberMaterial());
        assertEquals(1, census.memberRetained());          // &2-only counts as retained
        assertFalse(census.materialPresent());

        ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(offset("corotProfiles") + 4, 0);
        census = BsiCorot.availability(sections, p);
        assertEquals(0, census.memberMaterial());
        assertEquals(0, census.memberRetained());
        assertFalse(census.materialPresent());

        // No profile section at all: the honest "not reported", not a zero census.
        var empty = respond(reply(TOKEN_B).replace(
                "{\"name\":\"corotProfiles\",\"offset\":" + offset("corotProfiles") + ",\"bytes\":"
                        + BsiCorot.PROFILE_BYTES + ",\"count\":1},", ""), p).sections();
        census = BsiCorot.availability(empty, p);
        assertFalse(census.reported());
        assertFalse(census.materialPresent());
    }

    // ------------------------------------------------- contract-parity legs (B1–B3)

    private static BsiCorot.ArcResult richCheck(String header, byte[] payload,
                                                BsiFracture.Token previous) {
        return BsiCorot.checkArc(respond(header, payload), "f4", REV, DOM, REV, NS,
                richWorld(), arcOptions(), previous);
    }

    private static String richReply(BsiFracture.Token token) {
        return arcReply("f4", REV, DOM, NS, GENERATION, token, 1.25, 0.5, 1e-12, richDirectory());
    }

    private static byte[] richMutateInt(String section, int relative, int value) {
        var p = richPayload(DOM, REV);
        ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(richOffset(section) + relative, value);
        return p;
    }

    private static byte[] richMutateDouble(String section, int relative, double value) {
        var p = richPayload(DOM, REV);
        ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putDouble(richOffset(section) + relative, value);
        return p;
    }

    @Test void richReplyValidatesMultiBlockMemberJointsAndShellTail() {
        // The rich image is deliberately legal everywhere the old checks were wrong:
        // a member spanning two same-artifact source blocks, a kind≥2 joint with a
        // non-unit orientation, and a first shell layer exactly at -0.5.
        var result = richCheck(richReply(TOKEN_B), richPayload(DOM, REV), TOKEN_A);
        assertEquals(TOKEN_B, result.token());
        var census = result.availability();
        assertEquals(2, census.memberProfiles());
        assertEquals(2, census.memberMaterial());
        assertEquals(1, census.shellProfiles());
        assertEquals(1, census.shellMaterial());
    }

    @Test void jointRotationFollowsKind() {
        String header = richReply(TOKEN_B);
        // B1 fail-open direction: an available kind≤1 joint with a non-unit quaternion
        // must refuse — the old check only looked at kind>1.
        assertThrows(IllegalArgumentException.class,
                () -> richCheck(header, richMutateDouble("corotJoints", 48, 2.0), TOKEN_A));
        assertThrows(IllegalArgumentException.class,
                () -> richCheck(header,
                        richMutateDouble("corotJoints", BsiCorot.JOINT_BYTES + 80, 2.0), TOKEN_A));
        assertThrows(IllegalArgumentException.class,
                () -> richCheck(header, richMutateDouble("corotJoints", 48, Double.NaN), TOKEN_A));
        // B1 fail-closed direction: the canonical kind 2 joint carries a non-unit finite
        // orientation and is legal; flipping it to a strict kind makes those same bytes
        // refuse — the old check did exactly this for every kind≥2 joint.
        assertThrows(IllegalArgumentException.class,
                () -> richCheck(header, richMutateInt("corotJoints", 2 * BsiCorot.JOINT_BYTES, 1), TOKEN_A));
    }

    @Test void artifactMembershipCountsDistinctElements() {
        String header = richReply(TOKEN_B);
        // Positive leg is the canonical reply itself (member 0 owns two same-artifact
        // source blocks but is listed once); the per-row counter refused it.
        assertDoesNotThrow(() -> richCheck(header, richPayload(DOM, REV), TOKEN_A));
        // A duplicated member row violates strict increase.
        assertThrows(IllegalArgumentException.class,
                () -> richCheck(header, richMutateInt("corotArtifactMembers", 4, 0), TOKEN_A));
        // A member the artifact provably owns but the table omits is refused: artifact 1
        // expects {0,1}, shrinking memberCount to 1 drops member 1.
        assertThrows(IllegalArgumentException.class,
                () -> richCheck(header, richMutateInt("corotArtifacts", 12, 1), TOKEN_A));
        // A member index beyond the table is refused outright.
        assertThrows(IllegalArgumentException.class,
                () -> richCheck(header, richMutateInt("corotArtifactMembers", 4, 5), TOKEN_A));
    }

    @Test void shellLayersAdmitTheGaussLobattoEndpoint() {
        String header = richReply(TOKEN_B);
        // Positive leg: first layer exactly -0.5 is legal (contract previous=-1, closed
        // bound); the old open sentinel refused it.
        assertDoesNotThrow(() -> richCheck(header, richPayload(DOM, REV), TOKEN_A));
        // Below the closed bound, above it, and a non-increasing order all refuse.
        assertThrows(IllegalArgumentException.class,
                () -> richCheck(header, richMutateDouble("corotShellLayers", 0, -0.6), TOKEN_A));
        assertThrows(IllegalArgumentException.class,
                () -> richCheck(header,
                        richMutateDouble("corotShellLayers", BsiCorot.SHELL_LAYER_BYTES, -0.5), TOKEN_A));
        assertThrows(IllegalArgumentException.class,
                () -> richCheck(header,
                        richMutateDouble("corotShellLayers", 2 * BsiCorot.SHELL_LAYER_BYTES, 0.6), TOKEN_A));
    }
}
