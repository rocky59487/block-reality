package com.blockreality.core.bsi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * The BSI frame: {@code 'F' 'C' | flags u16 | headerLen u32 | payloadLen u32 | header | payload},
 * little-endian (contract {@code bsi_capi.h}).
 *
 * <p>One frame in, one frame out — that is the whole in-process ABI. The same layout carries the
 * arena's reply region and the frame transport, so a codec written once serves all three.
 *
 * <p>Decoding never throws on hostile bytes: a frame that does not add up is refused with a
 * message, because a decoder that throws into the server thread is a denial of service with extra
 * steps (the lesson SidecarPathsTest was written for).
 */
public final class BsiFrame {

    public static final int PREFIX_BYTES = 12;
    public static final int FLAG_END_OF_RESPONSE = 1;
    public static final int FLAG_HAS_PAYLOAD = 2;
    public static final int FLAG_BINARY_PAYLOAD = 4;

    private BsiFrame() {}

    /** Header text plus payload bytes, as a decoded frame. */
    public record Decoded(int flags, String header, byte[] payload) {}

    public static byte[] encode(String header, byte[] payload) {
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        byte[] p = payload == null ? new byte[0] : payload;
        int flags = p.length == 0 ? 0 : (FLAG_HAS_PAYLOAD | FLAG_BINARY_PAYLOAD);
        ByteBuffer b = ByteBuffer.allocate(PREFIX_BYTES + h.length + p.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'F').put((byte) 'C').putShort((short) flags).putInt(h.length).putInt(p.length);
        b.put(h).put(p);
        return b.array();
    }

    /** Decode exactly {@code length} bytes of {@code raw}. Returns null when the bytes are not a frame. */
    public static Decoded decode(byte[] raw, int length) {
        if (raw == null || length < PREFIX_BYTES || length > raw.length) return null;
        if (raw[0] != 'F' || raw[1] != 'C') return null;
        ByteBuffer b = ByteBuffer.wrap(raw, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        b.position(2);
        int flags = b.getShort() & 0xFFFF;
        long hl = b.getInt() & 0xFFFFFFFFL;
        long pl = b.getInt() & 0xFFFFFFFFL;
        if (PREFIX_BYTES + hl + pl != length) return null;
        byte[] header = new byte[(int) hl];
        b.get(header);
        byte[] payload = new byte[(int) pl];
        b.get(payload);
        return new Decoded(flags, new String(header, StandardCharsets.UTF_8), payload);
    }
}
