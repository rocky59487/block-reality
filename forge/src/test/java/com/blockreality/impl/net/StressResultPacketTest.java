package com.blockreality.impl.net;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.EndForces;
import com.blockreality.api.GoverningFibre;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ShellFieldSpec;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.StressFieldSpec;
import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.geom.Vec3d;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip and failure modes of the client packet (#39/#40/#42/INV-5).
 *
 * <p>Rules pinned here:
 * <ul>
 *   <li>decode never throws, for ANY truncation of a valid frame;
 *   <li>off-schema content (hostile counts, NaN) rejects the packet, never launders;
 *   <li>truncation keeps the governing element and says how much is missing;
 *   <li>the safety classification is the server's, bit-for-bit, after the float trip.
 * </ul>
 */
class StressResultPacketTest {

    private static final String DIM = "minecraft:overworld";

    private static StressFieldSpec field(double lengthMm) {
        return new StressFieldSpec(
                new Vec3d(500, 64500, 500),
                new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, 1),
                lengthMm,
                80_000, 2.6e8, 1.06e9, 100, 200,
                -6.16, 0,
                new EndForces(1000, 2, 3, 4, 5, 2.9e7),
                new EndForces(-1000, -2, -3, -4, -5, -1.4e7));
    }

    private static MemberSnapshot member(int id, double dc, int governingStation) {
        StressFieldSpec f = field(4000);
        return new MemberSnapshot(id, "steel", "steel_rect_200x400", 4000, dc,
                GoverningFibre.CRUSH, governingStation,
                f.endI(), f.endJ(),
                List.of(new BlockKey(id, 64, 0)),
                f.stations(11),
                Optional.of(f));
    }

    private static ShellSnapshot shell(int id, double dc) {
        List<Vec3d> corners = List.of(new Vec3d(500, 70500, 500), new Vec3d(1500, 70500, 500),
                new Vec3d(1500, 70500, 1500), new Vec3d(500, 70500, 1500));
        List<ShellFieldSpec.Moments> mc = List.of(
                new ShellFieldSpec.Moments(1e5, 1e5, 0), new ShellFieldSpec.Moments(1e5, 1e5, 0),
                new ShellFieldSpec.Moments(1e5, 1e5, 0), new ShellFieldSpec.Moments(1e5, 1e5, 0));
        ShellFieldSpec f = new ShellFieldSpec(corners,
                new Vec3d(1, 0, 0), new Vec3d(0, 0, 1), new Vec3d(0, 1, 0),
                200, -10, -10, 0, 1e5, 1e5, 0, 0, 0, mc);
        return new ShellSnapshot(id, "concrete", "concrete_slab_200", 200, dc, dc, true, false,
                List.of(new BlockKey(0, 70, 0), new BlockKey(1, 70, 0),
                        new BlockKey(1, 70, 1), new BlockKey(0, 70, 1)),
                Optional.of(f));
    }

    private static AnalysisResult result(List<MemberSnapshot> members, List<ShellSnapshot> shells,
                                         double maxDc, int governing, String governingKind,
                                         double bucklingFactor) {
        return new AnalysisResult(new WorldRevision(9), true, false, "",
                maxDc, governing, governingKind, 1, 0, 1e-14, bucklingFactor,
                members, shells, List.of());
    }

    @org.junit.jupiter.api.Test
    void bucklingSkippedRoundTripsAndAContradictionIsRejected() {
        // Skipped + zero factor: the honest wire shape when the server chose not to ask.
        AnalysisResult skipped = result(List.of(), List.of(), 0.4, -1, "", 0.0);
        StressResultPacket out = roundTrip(StressResultPacket.of(skipped, DIM, true));
        assertTrue(out.valid(), out.invalidReason());
        assertTrue(out.bucklingSkipped());

        // Skipped + NONZERO factor cannot come from this server (skipping means not
        // asking, and an unasked engine answers 0) — strict decode refuses it.
        AnalysisResult contradictory = result(List.of(), List.of(), 0.4, -1, "", 2.5);
        StressResultPacket bad = roundTrip(StressResultPacket.of(contradictory, DIM, true));
        assertFalse(bad.valid());
    }

    @org.junit.jupiter.api.Test
    void anOverlongDimensionIsClippedNotThrown() {
        // writeUtf THROWS on an overlong string; one long datapack dimension id must
        // not disconnect a whole dimension of players (v0.3a review §3-6).
        String longDim = "datapack:" + "x".repeat(400);
        AnalysisResult r = result(List.of(), List.of(), 0.4, -1, "", 0.0);
        StressResultPacket out = roundTrip(StressResultPacket.of(r, longDim, false));
        assertTrue(out.valid(), out.invalidReason());
        assertEquals(256, out.dimension().length());
    }

    private static StressResultPacket roundTrip(StressResultPacket in) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        StressResultPacket.encode(in, buf);
        StressResultPacket out = StressResultPacket.decode(buf);
        assertEquals(0, buf.readableBytes(), "decode must consume the frame exactly");
        return out;
    }

    // ------------------------------------------------------------------ INV-5
    // The display track's budget, measured on the PIPELINE rather than on the
    // language. `DisplayTrackPrecisionTest` pins the budget itself — that one double
    // → float → double step cannot exceed 1e-5 — but it imports nothing from this
    // project, so on its own it says nothing about whether the packet carries the
    // numbers the server computed. Five separate review passes made the same point
    // (PR26_REVIEW MECH-10), and they were right: "invariant 5 has an executable
    // gate" was a claim about a test of IEEE-754.
    //
    // This is the gate that claim needs. Every double the server put in is compared
    // against the double the client gets out, over the WHOLE packet, found by walking
    // the record components rather than by listing them — so a field added later is
    // covered without anyone remembering to add it here.

    private static final double DISPLAY_REL_BUDGET = 1e-5;

    /** Awkward values across every magnitude the pipeline carries, never round. */
    private static double awkward(int k) {
        double[] mant = { 1.2345678901234, Math.PI / 3, 9.8765432109, 6.02214076 };
        return mant[Math.floorMod(k, 4)] * Math.pow(10, (k % 17) - 6) * (k % 3 == 0 ? -1 : 1);
    }

    private static StressFieldSpec awkwardField(int k) {
        return new StressFieldSpec(
                new Vec3d(awkward(k), awkward(k + 1), awkward(k + 2)),
                new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, 1),
                4000,
                awkward(k + 3), awkward(k + 4), awkward(k + 5), awkward(k + 6), awkward(k + 7),
                awkward(k + 8), awkward(k + 9),
                new EndForces(awkward(k + 10), awkward(k + 11), awkward(k + 12),
                        awkward(k + 13), awkward(k + 14), awkward(k + 15)),
                new EndForces(awkward(k + 16), awkward(k + 17), awkward(k + 18),
                        awkward(k + 19), awkward(k + 20), awkward(k + 21)));
    }

    @Test
    void everyNumberTheClientDrawsIsWithinTheDisplayBudgetOfTheServersOwn() throws Exception {
        List<MemberSnapshot> members = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            StressFieldSpec f = awkwardField(i * 7);
            members.add(new MemberSnapshot(i, "steel", "steel_rect_200x400", 4000,
                    0.37 * i, GoverningFibre.CRUSH, 5, f.endI(), f.endJ(),
                    List.of(new BlockKey(i, 64, 0)), f.stations(11), Optional.of(f)));
        }
        AnalysisResult r = result(members, List.of(shell(1, 0.1)), 1.48, 4, "member", 3.75);

        StressResultPacket in = StressResultPacket.of(r, DIM, false);
        StressResultPacket out = roundTrip(in);
        assertTrue(out.valid(), out.invalidReason());
        assertEquals(in.members().size(), out.members().size());

        List<String> over = new ArrayList<>();
        for (int i = 0; i < in.members().size(); i++) {
            compareDoubles("member[" + i + "]", in.members().get(i), out.members().get(i), over);
        }
        for (int i = 0; i < in.shells().size(); i++) {
            compareDoubles("shell[" + i + "]", in.shells().get(i), out.shells().get(i), over);
        }
        assertTrue(over.isEmpty(), "display track budget violated (invariant 5):\n"
                + String.join("\n", over));
        // ...and the walk must actually have looked at something, or an empty
        // comparison would pass for the wrong reason.
        assertTrue(compared.get() > 200, "only " + compared.get() + " numbers compared");
    }

    private static final java.util.concurrent.atomic.AtomicInteger compared =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Walks two values of the same shape, comparing every double it reaches. */
    private static void compareDoubles(String path, Object a, Object b, List<String> over)
            throws Exception {
        if (a == null || b == null) return;
        if (a instanceof Double || a instanceof Float) {
            double x = ((Number) a).doubleValue(), y = ((Number) b).doubleValue();
            compared.incrementAndGet();
            if (x == y) return;
            double rel = Math.abs(y - x) / Math.max(Math.abs(x), Double.MIN_NORMAL);
            if (!(rel <= DISPLAY_REL_BUDGET)) {
                over.add(String.format("%s: server %.17g, client %.17g, rel %.3g", path, x, y, rel));
            }
            return;
        }
        if (a instanceof Optional<?> oa && b instanceof Optional<?> ob) {
            if (oa.isPresent() && ob.isPresent()) {
                compareDoubles(path, oa.get(), ob.get(), over);
            }
            return;
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            for (int i = 0; i < Math.min(la.size(), lb.size()); i++) {
                compareDoubles(path + "[" + i + "]", la.get(i), lb.get(i), over);
            }
            return;
        }
        if (a.getClass().isRecord() && a.getClass() == b.getClass()) {
            for (java.lang.reflect.RecordComponent rc : a.getClass().getRecordComponents()) {
                rc.getAccessor().setAccessible(true);
                compareDoubles(path + "." + rc.getName(),
                        rc.getAccessor().invoke(a), rc.getAccessor().invoke(b), over);
            }
        }
    }

    @Test
    void anOverLongTokenIsTruncatedRatherThanThrownDuringTheBroadcast() {
        // encode() runs inside the loop that sends to every player, so writeUtf's length
        // check throwing would cost the whole broadcast, not one member's drawing.
        // EngineStatusPacket has guarded its two strings all along; these two did not
        // (PR26_REVIEW ATK-10 / DF-11). Tokens come from the engine's own catalogue, so
        // this can only fire against an engine that is not the one shipped here — which
        // is exactly the case a guard exists for.
        String huge = "x".repeat(400);
        StressFieldSpec f = field(4000);
        MemberSnapshot m = new MemberSnapshot(1, "steel", huge, 4000, 0.4,
                GoverningFibre.CRUSH, 5, f.endI(), f.endJ(),
                List.of(new BlockKey(1, 64, 0)), f.stations(11), Optional.of(f));
        StressResultPacket out = roundTrip(
                StressResultPacket.of(result(List.of(m), List.of(), 0.4, 1, "member", 0), DIM, false));
        assertTrue(out.valid(), out.invalidReason());
        assertEquals(48, out.members().get(0).section().length());
    }

    @Test
    void roundTripPreservesTheDrawableResult() {
        AnalysisResult r = result(List.of(member(1, 0.25, 5)), List.of(shell(1, 0.1)),
                0.25, 1, "member", 14.25);
        StressResultPacket out = roundTrip(StressResultPacket.of(r, DIM, false));

        assertTrue(out.valid(), out.invalidReason());
        assertEquals(9, out.revision());
        assertEquals(DIM, out.dimension());
        assertFalse(out.singular());
        assertEquals(0.25, out.maxDc(), 1e-6);
        assertFalse(out.overCapacity());
        assertEquals(1, out.islands());
        assertEquals(14.25, out.bucklingFactor(), 1e-6);
        assertFalse(out.bucklingCritical());
        assertFalse(out.membersTruncated());
        assertFalse(out.shellsTruncated());

        assertEquals(1, out.members().size());
        MemberSnapshot m = out.members().get(0);
        assertEquals(1, m.id());
        assertEquals(0.25, m.dc(), 1e-6);
        assertEquals(GoverningFibre.CRUSH, m.governingFibre());
        assertEquals("steel_rect_200x400", m.section());
        assertEquals(List.of(new BlockKey(1, 64, 0)), m.blocks());
        assertTrue(m.field().isPresent());
        assertEquals(11, m.stations().size(), "stations are regenerated from the field");

        assertEquals(1, out.shells().size());
        ShellSnapshot s = out.shells().get(0);
        assertEquals("concrete_slab_200", s.plate());
        assertTrue(s.governingTopFace());
        assertTrue(s.field().isPresent());
    }

    @Test
    void governingStationSurvivesTheTripAsAPosition() {
        // INV-5: the index used to be hardcoded 0 on the client, so a mid-span
        // governing section rendered the end section next to the mid-span D/C. The
        // wire now carries the POSITION; the client picks its nearest regenerated
        // station, which for the same station count is the same index.
        AnalysisResult r = result(List.of(member(1, 0.8, 5)), List.of(),
                0.8, 1, "member", 0);
        StressResultPacket out = roundTrip(StressResultPacket.of(r, DIM, false));
        assertEquals(5, out.members().get(0).governingStation(),
                "mid-span governing station must not decode as 0");
    }

    @Test
    void aMemberWithoutAGoverningStationDecodesAsNone() {
        AnalysisResult r = result(List.of(member(1, 0.8, -1)), List.of(), 0.8, 1, "member", 0);
        StressResultPacket out = roundTrip(StressResultPacket.of(r, DIM, false));
        assertEquals(-1, out.members().get(0).governingStation());
    }

    @Test
    void truncationKeepsTheGoverningMemberAndSaysSo() {
        // 70 members, governing is the LAST — past the 64 cap. Dropping it would
        // headline a max D/C nothing on screen exhibits (#42).
        List<MemberSnapshot> members = new ArrayList<>();
        for (int i = 1; i <= 70; i++) members.add(member(i, i == 70 ? 1.31 : 0.2, -1));
        AnalysisResult r = result(members, List.of(), 1.31, 70, "member", 0);

        StressResultPacket out = roundTrip(StressResultPacket.of(r, DIM, false));
        assertTrue(out.valid(), out.invalidReason());
        assertTrue(out.membersTruncated());
        assertEquals(70, out.totalMembers());
        assertEquals(64, out.members().size());
        assertTrue(out.members().stream().anyMatch(m -> m.id() == 70),
                "the governing member must survive truncation (#42)");
    }

    @Test
    void theSafetyClassificationIsTheServersNotAFloatComparison() {
        // A D/C of 1 + 1e-12 rounds to exactly 1.0f on the wire; a client comparing
        // floats would call it safe. The carried verdict must win (#55).
        double dcJustOver = Math.nextUp(1.0);
        AnalysisResult r = result(List.of(member(1, dcJustOver, -1)), List.of(),
                dcJustOver, 1, "member", Math.nextUp(0.0));
        StressResultPacket out = roundTrip(StressResultPacket.of(r, DIM, false));

        assertTrue(out.overCapacity(), "server verdict maxDc > 1 must survive the float trip");
        assertTrue(out.members().get(0).isOverloaded(),
                "the decoded member must agree with the server verdict");
        assertTrue(out.bucklingCritical(), "0 < factor <= 1 decided on the double");
    }

    @Test
    void aTruncatedBufferNeverThrowsItRejects() {
        AnalysisResult r = result(List.of(member(1, 0.25, 5)), List.of(shell(1, 0.1)),
                0.25, 1, "member", 14.25);
        FriendlyByteBuf full = new FriendlyByteBuf(Unpooled.buffer());
        StressResultPacket.encode(StressResultPacket.of(r, DIM, false), full);
        byte[] bytes = new byte[full.readableBytes()];
        full.getBytes(0, bytes);

        // Every prefix length, not a sample: the throw the javadoc forbids came from
        // netty's own bounds check, which any cut can trigger (#39/TEST-12).
        for (int len = 0; len < bytes.length; len++) {
            FriendlyByteBuf cut = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes, 0, len));
            StressResultPacket out = StressResultPacket.decode(cut);   // must not throw
            assertFalse(out.valid(), "a truncated frame must be rejected, len=" + len);
        }
    }

    @Test
    void aHostileMemberCountRejectsThePacket() {
        // Declared count past the cap: the old clamp read 64 and left the rest of the
        // frame to be misparsed as the shell section (#39). Rejection, not misparse.
        //
        // Every field is labelled because this frame is written by hand, and it was
        // ALREADY misaligned before N14 added a field: bucklingSkipped was missing, the
        // two bytes of varInt(1000) were read as that boolean plus totalMembers, and the
        // test still passed because the misparse happened to land on the count check it
        // was asserting. It only surfaced when one more field shifted the wreckage past
        // that check. A hand-written frame has to be spelled out or it tests nothing.
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarLong(9);
        buf.writeUtf(DIM, 256);
        buf.writeBoolean(false);
        buf.writeFloat(0.25f);
        buf.writeBoolean(false);
        buf.writeVarInt(1);
        buf.writeVarInt(0);
        buf.writeFloat(0f);    // bucklingFactor
        buf.writeBoolean(false);  // bucklingCritical
        buf.writeBoolean(false);  // bucklingSkipped
        buf.writeVarInt(0);    // truncatedBlocks
        buf.writeVarInt(1000); // totalMembers
        buf.writeVarInt(0);    // totalShells
        buf.writeVarInt(65);   // one past MAX_MEMBERS

        StressResultPacket out = StressResultPacket.decode(buf);
        assertFalse(out.valid());
        assertTrue(out.invalidReason().contains("count"), out.invalidReason());
        assertEquals(0, buf.readableBytes(), "the rejected frame must still be consumed");
    }

    @Test
    void nanIsCorruptionNotZero() {
        // The old decoder washed NaN to 0.0 and rendered it (#40). A NaN maxDc must
        // reject the packet so the client keeps its previous good state.
        AnalysisResult r = result(List.of(member(1, 0.5, -1)), List.of(),
                Double.NaN, 1, "member", 0);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        StressResultPacket.encode(StressResultPacket.of(r, DIM, false), buf);
        StressResultPacket out = StressResultPacket.decode(buf);
        assertFalse(out.valid());
        assertTrue(out.invalidReason().contains("finite"), out.invalidReason());
    }

    @Test
    void anEmptyResultRoundTripsAsMechanismData() {
        // All-singular verdicts are broadcast with empty lists; the client's
        // mechanism branch depends on this arriving intact (#43).
        AnalysisResult r = new AnalysisResult(new WorldRevision(4), true, true,
                "no restrained structure", 0, -1, "", 1, 1, 0, 0,
                List.of(), List.of(), List.of());
        StressResultPacket out = roundTrip(StressResultPacket.of(r, DIM, false));
        assertTrue(out.valid(), out.invalidReason());
        assertTrue(out.singular());
        assertEquals(1, out.singularIslands());
        assertTrue(out.members().isEmpty());
        assertTrue(out.shells().isEmpty());
    }

    // ------------------------------------------------------------- N14, model completeness

    @Test
    void withheldElementsAndTheirReasonSurviveTheWire() {
        // The server left two blocks out because their chunk was not loaded, and ruled
        // that member 1 stands against that boundary. Both facts have to arrive: the
        // count is what the HUD says, the id is what stops the colour (N14-b/c).
        AnalysisResult r = result(List.of(member(1, 0.4, 5), member(2, 0.2, 5)),
                List.of(shell(7, 0.3)), 0.4, 1, "member", 0.0);
        StressResultPacket out = roundTrip(StressResultPacket.of(r, DIM, false,
                java.util.Set.of(1), java.util.Set.of(7), 2));
        assertTrue(out.valid(), out.invalidReason());
        assertEquals(2, out.truncatedBlocks());
        assertEquals(java.util.Set.of(1), out.withheldMembers());
        assertEquals(java.util.Set.of(7), out.withheldShells());
    }

    @Test
    void aWholeModelSendsNoWithholdingAtAll() {
        // N14-e: nothing skipped must be indistinguishable from the previous release.
        AnalysisResult r = result(List.of(member(1, 0.4, 5)), List.of(shell(7, 0.3)), 0.4, 1, "member", 0.0);
        StressResultPacket out = roundTrip(StressResultPacket.of(r, DIM, false));
        assertEquals(0, out.truncatedBlocks());
        assertTrue(out.withheldMembers().isEmpty());
        assertTrue(out.withheldShells().isEmpty());
    }

    @Test
    void withholdingWithoutATruncatedBlockIsRejected() {
        // A greyed-out member the player can find no reason for is worse than none.
        // The server sets both from one computation, so the pair cannot disagree
        // honestly — same posture as the buckling contradiction.
        AnalysisResult r = result(List.of(member(1, 0.4, 5)), List.of(), 0.4, 1, "member", 0.0);
        StressResultPacket in = StressResultPacket.of(r, DIM, false,
                java.util.Set.of(1), java.util.Set.of(), 0);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        StressResultPacket.encode(in, buf);
        StressResultPacket out = StressResultPacket.decode(buf);
        assertFalse(out.valid());
        assertTrue(out.invalidReason().contains("withheld"), out.invalidReason());
    }

    @Test
    void theWithheldFlagFollowsItsMemberThroughOverlayTruncation() {
        // The flag rides on the element rather than in a separate id list precisely so
        // that dropping elements at MAX_MEMBERS cannot leave it pointing at the wrong
        // one. Withhold a member that survives the cut and one that does not.
        List<MemberSnapshot> many = new java.util.ArrayList<>();
        for (int i = 0; i < 80; i++) many.add(member(i, 0.1 + i * 0.001, 5));
        AnalysisResult r = result(many, List.of(), 0.179, 2, "member", 0.0);
        StressResultPacket out = roundTrip(StressResultPacket.of(r, DIM, false,
                java.util.Set.of(2, 70), java.util.Set.of(), 1));
        assertTrue(out.valid(), out.invalidReason());
        // 70 was dropped with the overlay truncation; 2 survived and keeps its flag
        assertEquals(java.util.Set.of(2), out.withheldMembers());
        assertTrue(out.members().stream().anyMatch(m -> m.id() == 2));
    }

    @Test
    void theMaterialTokenSurvivesTheWire() {
        // It did not. The decoder built every snapshot with "" for material, so the
        // client could not tell steel from timber and the material lens — a lens the
        // listing advertises — rendered as the stress lens. Same shape as the endForces
        // trap this class already carries a comment about.
        AnalysisResult r = result(List.of(member(1, 0.4, 5)), List.of(shell(7, 0.3)),
                0.4, 1, "member", 0.0);
        StressResultPacket out = roundTrip(StressResultPacket.of(r, DIM, false));
        assertTrue(out.valid(), out.invalidReason());
        assertEquals("steel", out.members().get(0).material());
        assertEquals("concrete", out.shells().get(0).material());
    }
}
