package com.blockreality.core.protocol;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.EngineCatalogue;
import com.blockreality.api.GoverningFibre;
import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binary reply frame, decoded from hand-written bytes.
 *
 * <p>The fixture below mirrors the sidecar's {@code encodeShmReply} field for field; the
 * end-to-end agreement of the two transports is verify.py's job, and THIS file's job is
 * the failure modes a correct engine cannot produce: wrong frame lengths, unknown
 * ordinals, non-finite values, tokens in the wrong role. Every one of them must come
 * back as a failed result — never an exception, and never a "cleaned-up" success.
 */
class BinaryCodecTest {

    private static final WorldRevision REV = new WorldRevision(7);

    private static final EngineCatalogue CAT = new EngineCatalogue(
            "FrameCore", 1,
            List.of("steel", "concrete"),
            List.of("steel_rect_200x400"),
            List.of("concrete_slab_200"),
            1);

    /** Knobs for the corruption cases; {@link #valid()} is the schema-true baseline. */
    private record Knobs(int fibre, double memberDc, double naY, int shellFlags,
                         int memberTok, int shellTok) { }

    private static Knobs valid() { return new Knobs(1, 0.25, 12.5, 1, 0, 1); }

    /**
     * One member (one block, one station), one shell, one unassigned block, in exactly
     * the order the C++ encoder writes them. Returns the buffer with its position at
     * the end of the frame, so {@code buf.position()} is the doorbell's byte count.
     */
    private static ByteBuffer reply(Knobs k) {
        ByteBuffer b = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(BinaryCodec.RESP_MAGIC);
        b.putInt(7).putInt(0);            // revision lo/hi
        b.putInt(1);                      // ok
        b.putInt(0);                      // singular
        b.putInt(1).putInt(0);            // islands, singularIslands
        b.putInt(0);                      // diagnostic: ""
        b.putInt(0);                      // note: ""
        for (int i = 0; i < 6; i++) b.putDouble(0);   // applied[3] + reaction[3]
        b.putDouble(1.5e-15);             // residual
        b.putDouble(0.25);                // maxDC
        b.putInt(1);                      // governing
        b.putInt(1);                      // governingKind: member
        b.putDouble(14.25);               // bucklingFactor
        b.putInt(6).putInt(36);           // nodes, dof

        b.putInt(1);                      // nMembers
        b.putInt(1);                      // id
        b.putInt(0);                      // matIdx: steel
        b.putInt(k.memberTok());          // tokIdx
        b.putDouble(4000);                // lengthMm
        b.putDouble(k.memberDc());        // dc
        b.putInt(k.fibre());              // governing fibre ordinal
        b.putInt(0);                      // governingStation
        for (int i = 0; i < 12; i++) b.putDouble(i);          // fi + fj
        b.putDouble(500).putDouble(64500).putDouble(500);     // origin
        b.putDouble(1).putDouble(0).putDouble(0);             // ax
        b.putDouble(0).putDouble(1).putDouble(0);             // ay
        b.putDouble(0).putDouble(0).putDouble(1);             // az
        b.putDouble(80000).putDouble(2.6e8).putDouble(1.06e9); // A, Iy, Iz
        b.putDouble(100).putDouble(200);                       // cy, cz
        b.putDouble(-6.16).putDouble(0);                       // wy, wz
        b.putInt(1);                      // nBlocks
        b.putInt(0).putInt(64).putInt(0);
        b.putInt(1);                      // nStations
        b.putDouble(0);                   // x
        b.putDouble(500).putDouble(64500).putDouble(500);      // world
        b.putDouble(-15).putDouble(15).putDouble(0).putDouble(0); // fibre sigmas
        b.putDouble(15).putDouble(15).putDouble(0.375);        // sigmaTens/Comp, tau
        b.putDouble(k.naY());             // naY
        b.putDouble(Double.NaN);          // naZ: absent by the NaN convention

        b.putInt(1);                      // nShells
        b.putInt(1);                      // id
        b.putInt(1);                      // matIdx: concrete
        b.putInt(k.shellTok());           // tokIdx
        b.putDouble(200).putDouble(0.1).putDouble(0.1);        // t, dc, dcRaw
        b.putInt(k.shellFlags());         // flags: bit0 top face, bit1 recovery
        b.putInt(2);                      // governing corner
        b.putInt(0).putInt(70).putInt(0);
        b.putInt(1).putInt(70).putInt(0);
        b.putInt(1).putInt(70).putInt(1);
        b.putInt(0).putInt(70).putInt(1);
        for (int c = 0; c < 4; c++) {
            b.putDouble(500 + c).putDouble(70500).putDouble(500);   // world corners
        }
        b.putDouble(1).putDouble(0).putDouble(0);              // ex
        b.putDouble(0).putDouble(0).putDouble(1);              // ey
        b.putDouble(0).putDouble(1).putDouble(0);              // n
        b.putDouble(-10).putDouble(-10).putDouble(0);          // Nxx, Nyy, Nxy
        b.putDouble(1e5).putDouble(1e5).putDouble(0);          // Mxx, Myy, Mxy
        b.putDouble(0).putDouble(0);                           // Qx, Qy
        for (int i = 0; i < 12; i++) b.putDouble(1e5);         // Mc
        for (int i = 0; i < 12; i++) b.putDouble(1e5);         // McRaw
        b.putDouble(8).putDouble(3);                           // vmTop, vmBot

        b.putInt(1);                      // nUnassigned
        b.putInt(5).putInt(64).putInt(0);
        return b;
    }

    private static AnalysisResult decode(ByteBuffer b, int frameBytes) {
        return BinaryCodec.decodeSolve(b, frameBytes, REV, CAT);
    }

    @Test
    void aWellFormedFrameDecodesCompletely() {
        ByteBuffer b = reply(valid());
        AnalysisResult r = decode(b, b.position());
        assertTrue(r.ok(), r.diagnostic());
        assertEquals(0.25, r.maxDc(), 0);
        assertEquals("member", r.governingKind());
        assertEquals(14.25, r.bucklingFactor(), 0);
        assertEquals(1.5e-15, r.equilibriumResidual(), 0);
        assertEquals(1, r.members().size());
        assertEquals(1, r.shells().size());
        assertEquals(List.of(new BlockKey(5, 64, 0)), r.unassigned());

        var m = r.members().get(0);
        assertEquals("steel", m.material());
        assertEquals("steel_rect_200x400", m.section());
        assertEquals(GoverningFibre.CRUSH, m.governingFibre());
        assertTrue(m.field().isPresent());
        assertEquals(12.5, m.stations().get(0).naOffsetYMm().orElseThrow(), 0);
        assertTrue(m.stations().get(0).naOffsetZMm().isEmpty(),
                "NaN on the wire is the absent-neutral-axis sentinel");

        var s = r.shells().get(0);
        assertEquals("concrete_slab_200", s.plate());
        assertTrue(s.governingTopFace());
        assertFalse(s.edgeRecovered());
    }

    @Test
    void aFrameLongerThanItsContentIsRejected() {
        // The doorbell promised 8 more bytes than the reply uses. A schema divergence
        // that still parses is the most dangerous kind, and leftover bytes inside the
        // declared frame are its signature.
        ByteBuffer b = reply(valid());
        AnalysisResult r = decode(b, b.position() + 8);
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("frame length mismatch"), r.diagnostic());
    }

    @Test
    void aFrameShorterThanItsContentIsRejected() {
        // The reads must stop at the declared frame end, never continue into whatever
        // a previous, larger reply left in the region.
        ByteBuffer b = reply(valid());
        AnalysisResult r = decode(b, b.position() - 8);
        assertFalse(r.ok());
        assertEquals(REV, r.revision());
    }

    @Test
    void anUnknownFibreOrdinalIsRejectedNotMapped() {
        // The old floorMod mapping would have read ordinal 7 as NONE — a confident
        // wrong label on the exact member the player is told to fix.
        ByteBuffer b = reply(new Knobs(7, 0.25, 12.5, 1, 0, 1));
        AnalysisResult r = decode(b, b.position());
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("fibre"), r.diagnostic());
    }

    @Test
    void unknownShellFlagBitsAreRejected() {
        // bit2 is not part of layout 1. Ignoring it would let a future flag change
        // what the facet means while an old client renders the old meaning.
        ByteBuffer b = reply(new Knobs(1, 0.25, 12.5, 0b101, 0, 1));
        AnalysisResult r = decode(b, b.position());
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("flags"), r.diagnostic());
    }

    @Test
    void aNonFiniteDemandCapacityIsRejected() {
        // A NaN D/C poisons every comparison downstream while looking like data.
        ByteBuffer b = reply(new Knobs(1, Double.NaN, 12.5, 1, 0, 1));
        AnalysisResult r = decode(b, b.position());
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("not finite"), r.diagnostic());
    }

    @Test
    void nanNeutralAxisMeansAbsentNotError() {
        ByteBuffer b = reply(new Knobs(1, 0.25, Double.NaN, 1, 0, 1));
        AnalysisResult r = decode(b, b.position());
        assertTrue(r.ok(), r.diagnostic());
        assertTrue(r.members().get(0).stations().get(0).naOffsetYMm().isEmpty());
    }

    @Test
    void tokenRolesAreEnforcedNotJustRanges() {
        // Index 1 is in range for the shared token table, but it names a plate; a
        // member carrying it would pair a plate's name with a beam's numbers.
        ByteBuffer plateOnMember = reply(new Knobs(1, 0.25, 12.5, 1, 1, 1));
        AnalysisResult r1 = decode(plateOnMember, plateOnMember.position());
        assertFalse(r1.ok());
        assertTrue(r1.diagnostic().contains("not a beam section"), r1.diagnostic());

        ByteBuffer sectionOnShell = reply(new Knobs(1, 0.25, 12.5, 1, 0, 0));
        AnalysisResult r2 = decode(sectionOnShell, sectionOnShell.position());
        assertFalse(r2.ok());
        assertTrue(r2.diagnostic().contains("not a plate"), r2.diagnostic());
    }

    @Test
    void aBooleanWireFieldMustBeExactlyZeroOrOne() {
        ByteBuffer b = reply(valid());
        int frame = b.position();
        // singular sits at a fixed offset: magic(4) + revision(8) + ok(4) = 16.
        b.putInt(16, 2);
        AnalysisResult r = decode(b, frame);
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("singular"), r.diagnostic());
    }

    @Test
    void aBadMagicIsRejected() {
        ByteBuffer b = reply(valid());
        int frame = b.position();
        b.putInt(0, 0x0BADF00D);
        AnalysisResult r = decode(b, frame);
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("magic"), r.diagnostic());
    }

    @Test
    void aHostileCountFailsBeforeAllocating() {
        // A frame that is nothing but the fixed header (124 bytes: magic through dof,
        // both strings empty) followed by a member count of one million. The count is
        // under the flat plausibility cap, so only the byte-budget guard can refuse
        // it — and it must, from one comparison, before any list is sized.
        ByteBuffer b = reply(valid());
        ByteBuffer small = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        b.limit(124).position(0);
        small.put(b);                     // header prefix only
        small.putInt(1_000_000);          // member count with zero bytes behind it
        AnalysisResult r = BinaryCodec.decodeSolve(small, small.position(), REV, CAT);
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("cannot fit the frame"), r.diagnostic());
    }

    @Test
    void aRevisionMismatchIsRejected() {
        // The frame answers a question that was not asked. Accepting it would let a
        // stale reply masquerade as the current one — the exact confusion the
        // revision field exists to make impossible.
        ByteBuffer b = reply(valid());
        AnalysisResult r = BinaryCodec.decodeSolve(b, b.position(), new WorldRevision(8), CAT);
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("revision mismatch"), r.diagnostic());
        assertEquals(new WorldRevision(8), r.revision(),
                "the failure is stamped with the EXPECTED revision, so the gate can route it");
    }

    @Test
    void aNonOkFrameInTheRegionIsRejected() {
        // A failed solve errors on the doorbell and never writes the region; a non-ok
        // flag here means the two sides disagree about the conversation itself.
        ByteBuffer b = reply(valid());
        int frame = b.position();
        b.putInt(12, 0);   // ok flag sits at magic(4) + revision(8)
        AnalysisResult r = decode(b, frame);
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("non-ok"), r.diagnostic());
    }

    @Test
    void aNegativeCountIsRejected() {
        ByteBuffer b = reply(valid());
        ByteBuffer broken = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        b.limit(124).position(0);
        broken.put(b);                 // header prefix only
        broken.putInt(-1);             // member count
        AnalysisResult r = BinaryCodec.decodeSolve(broken, broken.position(), REV, CAT);
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("count"), r.diagnostic());
    }

    @Test
    void decodeNeverThrowsForAnyTruncationOfAValidFrame() {
        // The never-throws contract, exhaustively: every prefix of a well-formed frame
        // must come back as a failed result, not as an exception into the caller —
        // SidecarClient.solve() promises its callers exactly that (TEST-4).
        ByteBuffer full = reply(valid());
        int frame = full.position();
        for (int len = 0; len <= frame; len += 1) {
            AnalysisResult r = BinaryCodec.decodeSolve(full, len, REV, CAT);
            if (len == frame) {
                assertTrue(r.ok(), "the full frame is the baseline and must decode");
            } else {
                assertFalse(r.ok(), "prefix of " + len + " bytes must fail, not throw");
            }
        }
    }

    // ---------------------------------------------------------------- request
    @Test
    void encodeReturnsTheExactByteCountTheDoorbellNeeds() {
        SolveRequest req = SolveRequest.builder(REV)
                .block(new BlockKey(0, 64, 0), "steel", "steel_rect_200x400", true)
                .block(new BlockKey(1, 64, 0), "concrete", "concrete_slab_200", false)
                .load(SolveRequest.PointLoad.downwards(new BlockKey(1, 64, 0), 20_000))
                .build();
        ByteBuffer buf = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        int wrote = BinaryCodec.encodeSolve(req, CAT, buf);
        assertEquals(BinaryCodec.requestBytes(req), wrote,
                "requestBytes must be exact — the doorbell's byte count is the frame");
    }

    @Test
    void aTokenOutsideTheCatalogueCannotTravelAsAnIndex() {
        SolveRequest req = SolveRequest.builder(REV)
                .block(new BlockKey(0, 64, 0), "steel", "no_such_section", true)
                .build();
        ByteBuffer buf = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(-1, BinaryCodec.encodeSolve(req, CAT, buf),
                "the caller must fall back to JSON, whose validation names the token");
    }
}
