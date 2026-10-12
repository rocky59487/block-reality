package com.blockreality.impl.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * O12: the finite qualification packet's wire contract — round-trip fidelity, bounded
 * size, closed-world rejection of truncation, trailing bytes and non-finite scalars.
 */
class FiniteQualificationPacketTest {

    private static final String DIM = "minecraft:overworld";

    private static FiniteQualificationPacket packet() {
        return FiniteQualificationPacket.of(DIM, 21, 7, true, true, 1.25,
                List.of(new FiniteQualificationPacket.Island(0, 2, ""),
                        new FiniteQualificationPacket.Island(4, 0, "idle")),
                true, 3, 2, 1, 2, 1, 0, true, 0.5, 1e-12);
    }

    private static byte[] bytes(FiniteQualificationPacket p) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            FiniteQualificationPacket.encode(p, b);
            byte[] raw = new byte[b.readableBytes()]; b.readBytes(raw); return raw;
        } finally { b.release(); }
    }

    private static FiniteQualificationPacket decode(byte[] raw) {
        var b = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
        try {
            var p = assertDoesNotThrow(() -> FiniteQualificationPacket.decode(b));
            assertEquals(0, b.readableBytes());
            return p;
        } finally { b.release(); }
    }

    @Test void roundTripPreservesEveryField() {
        var p = packet();
        assertTrue(p.valid(), p.invalidReason());
        var q = decode(bytes(p));
        assertTrue(q.valid(), q.invalidReason());
        assertEquals(DIM, q.dimension());
        assertEquals(21, q.worldRevision());
        assertEquals(7, q.historySequence());
        assertTrue(q.fullyConverged());
        assertTrue(q.loadFactorReported());
        assertEquals(1.25, q.loadFactor());
        assertEquals(2, q.islands().size());
        assertEquals(4, q.islands().get(1).status());
        assertEquals("idle", q.islands().get(1).reason());
        assertTrue(q.availabilityReported());
        assertEquals(3, q.memberProfiles());
        assertEquals(2, q.memberMaterial());
        assertEquals(1, q.memberRetained());
        assertEquals(2, q.shellProfiles());
        assertTrue(q.arcReported());
        assertEquals(0.5, q.arcRadius());
        assertEquals(1e-12, q.arcResidual());
    }

    @Test void everyTruncationFailsClosed() {
        byte[] raw = bytes(packet());
        for (int end = 0; end < raw.length; end++)
            assertFalse(decode(Arrays.copyOf(raw, end)).valid(), "truncated at " + end);
    }

    @Test void trailingAndOversizeBytesRefuse() {
        byte[] raw = bytes(packet());
        assertFalse(decode(Arrays.copyOf(raw, raw.length + 1)).valid());
        assertFalse(FiniteQualificationPacket.decode(
                new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[FiniteQualificationPacket.MAX_BYTES + 1]))).valid());
    }

    @Test void invalidFieldsNeverReachTheWire() {
        var good = packet();
        for (var p : List.of(
                FiniteQualificationPacket.of("not a dimension", 1, 0, true, false, 0,
                        List.of(), false, 0, 0, 0, 0, 0, 0, false, 0, 0),
                FiniteQualificationPacket.of(DIM, -1, 0, true, false, 0,
                        List.of(), false, 0, 0, 0, 0, 0, 0, false, 0, 0),
                FiniteQualificationPacket.of(DIM, 1, -1, true, false, 0,
                        List.of(), false, 0, 0, 0, 0, 0, 0, false, 0, 0),
                FiniteQualificationPacket.of(DIM, 1, 0, true, true, Double.NaN,
                        List.of(), false, 0, 0, 0, 0, 0, 0, false, 0, 0),
                FiniteQualificationPacket.of(DIM, 1, 0, true, false, 0,
                        List.of(new FiniteQualificationPacket.Island(-1, 0, "")), false,
                        0, 0, 0, 0, 0, 0, false, 0, 0),
                FiniteQualificationPacket.of(DIM, 1, 0, true, false, 0,
                        List.of(new FiniteQualificationPacket.Island(0, 8, "")), false,
                        0, 0, 0, 0, 0, 0, false, 0, 0),
                FiniteQualificationPacket.of(DIM, 1, 0, true, false, 0, List.of(),
                        true, 0, 1, 0, 0, 0, 0, false, 0, 0),       // material > profiles
                FiniteQualificationPacket.of(DIM, 1, 0, true, false, 0, List.of(),
                        false, 0, 0, 0, 0, 0, 0, true, 0, 0),       // arc radius 0
                FiniteQualificationPacket.of(DIM, 1, 0, true, false, 0, List.of(),
                        false, 0, 0, 0, 0, 0, 0, true, 1, 1e-9)))   // residual over the band
        {
            assertFalse(p.valid());
            assertThrows(IllegalArgumentException.class, () -> bytes(p));
        }
        assertTrue(good.valid());
    }

    @Test void emptyIslandTableIsAValidNotAssessedSignal() {
        var p = FiniteQualificationPacket.of(DIM, 0, 0, false, false, 0, List.of(),
                false, 0, 0, 0, 0, 0, 0, false, 0, 0);
        assertTrue(p.valid(), p.invalidReason());
        var q = decode(bytes(p));
        assertTrue(q.valid());
        assertTrue(q.islands().isEmpty());
        assertFalse(q.loadFactorReported());
        assertFalse(q.availabilityReported());
        assertFalse(q.arcReported());
    }
}
