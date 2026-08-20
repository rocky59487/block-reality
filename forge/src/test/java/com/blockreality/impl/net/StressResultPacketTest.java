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

    private static StressResultPacket roundTrip(StressResultPacket in) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        StressResultPacket.encode(in, buf);
        StressResultPacket out = StressResultPacket.decode(buf);
        assertEquals(0, buf.readableBytes(), "decode must consume the frame exactly");
        return out;
    }

    @Test
    void roundTripPreservesTheDrawableResult() {
        AnalysisResult r = result(List.of(member(1, 0.25, 5)), List.of(shell(1, 0.1)),
                0.25, 1, "member", 14.25);
        StressResultPacket out = roundTrip(StressResultPacket.of(r, DIM));

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
        StressResultPacket out = roundTrip(StressResultPacket.of(r, DIM));
        assertEquals(5, out.members().get(0).governingStation(),
                "mid-span governing station must not decode as 0");
    }

    @Test
    void aMemberWithoutAGoverningStationDecodesAsNone() {
        AnalysisResult r = result(List.of(member(1, 0.8, -1)), List.of(), 0.8, 1, "member", 0);
        StressResultPacket out = roundTrip(StressResultPacket.of(r, DIM));
        assertEquals(-1, out.members().get(0).governingStation());
    }

    @Test
    void truncationKeepsTheGoverningMemberAndSaysSo() {
        // 70 members, governing is the LAST — past the 64 cap. Dropping it would
        // headline a max D/C nothing on screen exhibits (#42).
        List<MemberSnapshot> members = new ArrayList<>();
        for (int i = 1; i <= 70; i++) members.add(member(i, i == 70 ? 1.31 : 0.2, -1));
        AnalysisResult r = result(members, List.of(), 1.31, 70, "member", 0);

        StressResultPacket out = roundTrip(StressResultPacket.of(r, DIM));
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
        StressResultPacket out = roundTrip(StressResultPacket.of(r, DIM));

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
        StressResultPacket.encode(StressResultPacket.of(r, DIM), full);
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
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarLong(9);
        buf.writeUtf(DIM, 256);
        buf.writeBoolean(false);
        buf.writeFloat(0.25f);
        buf.writeBoolean(false);
        buf.writeVarInt(1);
        buf.writeVarInt(0);
        buf.writeFloat(0f);
        buf.writeBoolean(false);
        buf.writeVarInt(1000);
        buf.writeVarInt(0);
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
        StressResultPacket.encode(StressResultPacket.of(r, DIM), buf);
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
        StressResultPacket out = roundTrip(StressResultPacket.of(r, DIM));
        assertTrue(out.valid(), out.invalidReason());
        assertTrue(out.singular());
        assertEquals(1, out.singularIslands());
        assertTrue(out.members().isEmpty());
        assertTrue(out.shells().isEmpty());
    }
}
