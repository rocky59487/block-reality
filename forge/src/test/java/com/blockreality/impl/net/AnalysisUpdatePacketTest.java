package com.blockreality.impl.net;

import com.blockreality.core.AnalysisDeliveryClock;
import com.blockreality.core.engine.NativeGameRuntime.Status;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisUpdatePacketTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final UUID SOURCE = new UUID(27, 19);

    private static AnalysisUpdatePacket packet(AnalysisUpdatePacket.Kind kind) {
        var result = kind == AnalysisUpdatePacket.Kind.RESULT
                ? StressResultPacket.of(DisplayDeliveryFixtures.fixture("small"), DIMENSION, false) : null;
        return AnalysisUpdatePacket.of(DIMENSION, SOURCE, 100, 21, true, kind, Status.READY,
                result == null ? "native 模型診斷" : "", result);
    }
    private static byte[] bytes(AnalysisUpdatePacket p) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AnalysisUpdatePacket.encode(p, b);
            byte[] raw = new byte[b.readableBytes()]; b.readBytes(raw); return raw;
        } finally { b.release(); }
    }
    private static AnalysisUpdatePacket decode(byte[] raw) {
        var b = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
        try {
            var p = assertDoesNotThrow(() -> AnalysisUpdatePacket.decode(b));
            assertEquals(0, b.readableBytes()); return p;
        } finally { b.release(); }
    }
    @Test void everyKindRoundTripsWithSourceAndRevision() {
        for (var kind : AnalysisUpdatePacket.Kind.values()) {
            var p = packet(kind); assertTrue(p.valid(), p.invalidReason());
            var q = decode(bytes(p)); assertTrue(q.valid(), q.invalidReason());
            assertEquals(kind, q.kind()); assertEquals(DIMENSION, q.dimension());
            assertEquals(SOURCE, q.sourceId()); assertEquals(100, q.sequence());
            assertEquals(21, q.worldRevision()); assertTrue(q.bootstrap());
            assertEquals(Status.READY, q.engineStatus()); assertEquals(p.detail(), q.detail());
            if (kind == AnalysisUpdatePacket.Kind.RESULT) {
                assertEquals(19, q.result().revision());
                assertEquals(p.result().overCapacity(), q.result().overCapacity());
                assertEquals(p.result().bucklingCritical(), q.result().bucklingCritical());
                assertEquals(p.result().members().get(0).stations(), q.result().members().get(0).stations());
                var before = p.result().shells().get(0).display().orElseThrow();
                var after = q.result().shells().get(0).display().orElseThrow();
                assertEquals(before.cornersMm(), after.cornersMm());
                assertEquals(before.top(), after.top()); assertEquals(before.bottom(), after.bottom());
            } else assertNull(q.result());
        }
    }
    @Test void everyByteTruncationFailsClosedForEveryKind() {
        for (var kind : AnalysisUpdatePacket.Kind.values()) {
            byte[] raw = bytes(packet(kind));
            for (int end = 0; end < raw.length; end++)
                assertFalse(decode(Arrays.copyOf(raw, end)).valid(), kind + " truncated at " + end);
        }
    }
    @Test void malformedIdentityEnumsAndTrailingPayloadAreRejected() {
        byte[] raw = bytes(packet(AnalysisUpdatePacket.Kind.EMPTY));
        var b = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
        int seq, rev, bootstrap, kind, status;
        try {
            b.readUtf(); b.readUUID(); seq = b.readerIndex(); b.readVarLong();
            rev = b.readerIndex(); b.readVarLong(); bootstrap = b.readerIndex();
            b.readByte(); kind = b.readerIndex(); b.readByte(); status = b.readerIndex();
        } finally { b.release(); }
        for (int offset : new int[]{bootstrap, kind, status}) {
            byte[] bad = raw.clone(); bad[offset] = 127; assertFalse(decode(bad).valid());
        }
        byte[] zero = raw.clone(); zero[seq] = 0; assertFalse(decode(zero).valid());
        // Replace the single-byte positive varlong with a correctly encoded negative one.
        for (int offset : new int[]{seq, rev}) {
            var bad = new FriendlyByteBuf(Unpooled.buffer());
            try {
                bad.writeBytes(raw, 0, offset); bad.writeVarLong(-1);
                bad.writeBytes(raw, offset + 1, raw.length - offset - 1);
                byte[] encoded = new byte[bad.readableBytes()]; bad.readBytes(encoded);
                assertFalse(decode(encoded).valid());
            } finally { bad.release(); }
        }
        assertFalse(decode(Arrays.copyOf(raw, raw.length + 1)).valid());
        byte[] result = bytes(packet(AnalysisUpdatePacket.Kind.RESULT));
        result[kind] = (byte) AnalysisUpdatePacket.Kind.EMPTY.ordinal();
        assertFalse(decode(result).valid());
        assertFalse(decode(new byte[AnalysisUpdatePacket.MAX_BYTES + 1]).valid());
    }
    @Test void senderRefusesContradictoryResultsBeforeWriting() {
        var r = packet(AnalysisUpdatePacket.Kind.RESULT).result();
        for (var p : List.of(
                AnalysisUpdatePacket.of("minecraft:the_nether", SOURCE, 1, 20, true, AnalysisUpdatePacket.Kind.RESULT, Status.READY, "", r),
                AnalysisUpdatePacket.of(DIMENSION, SOURCE, 1, 18, true, AnalysisUpdatePacket.Kind.RESULT, Status.READY, "", r),
                AnalysisUpdatePacket.of(DIMENSION, SOURCE, 1, 20, true, AnalysisUpdatePacket.Kind.RESULT, Status.READY, "error", r),
                AnalysisUpdatePacket.of(DIMENSION, SOURCE, 1, 20, true, AnalysisUpdatePacket.Kind.EMPTY, Status.READY, "", r),
                AnalysisUpdatePacket.of("not a dimension", SOURCE, 1, 20, true, AnalysisUpdatePacket.Kind.PENDING, Status.READY, "", null))) {
            assertFalse(p.valid()); assertThrows(IllegalArgumentException.class, () -> bytes(p));
        }
        // Bypass the sender factory to exercise the receiver's result/envelope checks.
        byte[] raw = bytes(packet(AnalysisUpdatePacket.Kind.RESULT));
        var b = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
        try {
            b.readUtf(); b.readUUID(); b.readVarLong(); raw[b.readerIndex()] = 18;
        } finally { b.release(); }
        assertFalse(decode(raw).valid());
        raw = bytes(packet(AnalysisUpdatePacket.Kind.RESULT));
        raw[1] = 'z'; // valid resource name, but no longer the payload's dimension
        assertFalse(decode(raw).valid());
    }
    @Test void diagnosticClippingPreservesUnicodeAndFitsTheReserve() {
        for (String detail : List.of("界".repeat(256), "a".repeat(254) + "🧱" + "x".repeat(50))) {
            var p = AnalysisUpdatePacket.of("x:" + "a".repeat(254), SOURCE, Long.MAX_VALUE, Long.MAX_VALUE,
                    true, AnalysisUpdatePacket.Kind.MODEL_REFUSED, Status.READY, detail, null);
            byte[] raw = bytes(p); var q = decode(raw);
            assertTrue(q.valid(), q.invalidReason()); assertEquals(p.detail(), q.detail());
            assertFalse(q.detail().contains("�")); assertTrue(q.detail().length() <= 256);
            assertTrue(raw.length <= DisplayDelivery.HEADER_RESERVE);
            System.out.println("notice bytes=" + raw.length);
        }
    }
    @Test void maximumIdentityAndSummaryShareTheExistingByteReserve() {
        String dimension = "x:" + "a".repeat(254);
        for (String fixture : List.of("small", "dense", "oversize", "shell-heavy")) {
            var r = DisplayDeliveryFixtures.fixture(fixture);
            var result = StressResultPacket.of(r, dimension, false);
            var p = AnalysisUpdatePacket.of(dimension, SOURCE, Long.MAX_VALUE, Long.MAX_VALUE,
                    true, AnalysisUpdatePacket.Kind.RESULT, Status.READY, "", result);
            byte[] raw = bytes(p); var q = decode(raw); assertTrue(q.valid(), q.invalidReason());
            int payload = DisplayDelivery.prepare(r, Set.of(), Set.of()).payloadBytes();
            int header = raw.length - payload;
            // Every remaining bounded scalar/count varint could grow to ten/five bytes.
            int worstScalarGrowth = 128;
            assertTrue(header + worstScalarGrowth <= DisplayDelivery.HEADER_RESERVE);
            assertTrue(raw.length <= AnalysisUpdatePacket.MAX_BYTES);
            assertEquals(result.governing(), q.result().governing());
            assertEquals(result.governingOmitted(), q.result().governingOmitted());
            assertEquals(result.members().stream().map(m -> m.id()).toList(), q.result().members().stream().map(m -> m.id()).toList());
            assertEquals(result.shells().stream().map(s -> s.id()).toList(), q.result().shells().stream().map(s -> s.id()).toList());
            System.out.println(fixture + " bytes=" + raw.length + " header=" + header + " scalarAllowance=" + worstScalarGrowth);
        }
    }
    @Test void decodedNoticesCannotRollBackAnAcceptedResult() {
        var clock = new AnalysisDeliveryClock();
        var current = decode(bytes(packet(AnalysisUpdatePacket.Kind.RESULT)));
        assertTrue(clock.accept(DIMENSION, current.dimension(), current.sourceId(), current.sequence(),
                current.worldRevision(), current.bootstrap(), current.result().revision()));
        var old = AnalysisUpdatePacket.of(DIMENSION, SOURCE, 99, 21, true,
                AnalysisUpdatePacket.Kind.ENGINE_UNAVAILABLE, Status.DISABLED, "late failure", null);
        var q = decode(bytes(old)); assertTrue(q.valid());
        assertFalse(clock.accept(DIMENSION, q.dimension(), q.sourceId(), q.sequence(), q.worldRevision(), q.bootstrap(), -1));
        assertEquals(19, clock.resultRevision()); assertEquals(100, clock.sequence());
    }
}
