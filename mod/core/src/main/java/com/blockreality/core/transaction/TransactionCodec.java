package com.blockreality.core.transaction;

import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static com.blockreality.core.transaction.ConstructionTransaction.*;

/** Canonical, bounded disk format; independent of the unchanged BSI/result network protocol. */
final class TransactionCodec {
    private static final int MAGIC = 0x42525458, VERSION = 1, HASH_BYTES = 32;
    private TransactionCodec() { }

    static byte[] encode(Entry entry) {
        long size = 4 + 4 + 4 + 64 + 8 + 32 + 1 + 1 + 32;
        if (entry.intent() != null) {
            var intent = entry.intent(); size += 12L + 16L * (intent.created().size() + intent.retired().size());
            for (Change change : intent.changes())
                size += 12L + change.resource().length() + Math.max(0, change.before().size()) + Math.max(0, change.after().size());
        }
        if (size > MAX_RECORD_BYTES) throw invalid();
        var out = ByteBuffer.allocate((int) size);
        out.putInt(MAGIC).putInt(VERSION).putInt((int) size);
        var r = entry.request(); uuid(out, r.id()); uuid(out, r.actor()); uuid(out, r.session()); uuid(out, r.domain());
        out.putLong(r.baseRevision()).put(HexFormat.of().parseHex(r.planHash()));
        out.put((byte) entry.phase().ordinal()).put((byte) entry.reason().ordinal());
        if (entry.intent() != null) {
            var intent = entry.intent(); out.putInt(intent.changes().size());
            for (Change change : intent.changes()) {
                byte[] key = change.resource().getBytes(StandardCharsets.US_ASCII);
                out.putInt(key.length).put(key); change.before().put(out); change.after().put(out);
            }
            ids(out, intent.created()); ids(out, intent.retired());
        }
        if (out.remaining() != HASH_BYTES) throw new IllegalStateException("Journal size mismatch");
        out.put(hash(out.array(), out.position())); return out.array();
    }

    static Entry decode(byte[] bytes) {
        if (bytes.length < 150 || bytes.length > MAX_RECORD_BYTES) throw invalid();
        int body = bytes.length - HASH_BYTES;
        if (!MessageDigest.isEqual(hash(bytes, body), Arrays.copyOfRange(bytes, body, bytes.length))) throw invalid();
        try {
            var in = ByteBuffer.wrap(bytes, 0, body);
            if (in.getInt() != MAGIC || in.getInt() != VERSION || in.getInt() != bytes.length) throw invalid();
            var request = new Request(uuid(in), uuid(in), uuid(in), uuid(in), in.getLong(),
                    HexFormat.of().formatHex(read(in, 32)));
            Phase phase = indexed(Phase.values(), in.get()); Reason reason = indexed(Reason.values(), in.get());
            Intent intent = null;
            if (phase != Phase.REJECTED) {
                int n = count(in, MAX_RESOURCES); var changes = new ArrayList<Change>(n); String previous = null;
                for (int i = 0; i < n; i++) {
                    String key = new String(read(in, count(in, 256)), StandardCharsets.US_ASCII);
                    if (previous != null && previous.compareTo(key) >= 0) throw invalid();
                    changes.add(new Change(key, value(in), value(in))); previous = key;
                }
                intent = new Intent(request, changes, ids(in), ids(in));
            }
            if (in.hasRemaining()) throw invalid();
            return new Entry(request, phase, reason, intent);
        } catch (BufferUnderflowException | IndexOutOfBoundsException bad) { throw invalid(); }
    }

    private static Value value(ByteBuffer in) {
        int n = in.getInt(); if (n == -1) return Value.missing();
        if (n < 0 || n > MAX_VALUE_BYTES) throw invalid(); return Value.of(read(in, n));
    }
    private static byte[] read(ByteBuffer in, int n) {
        if (n < 0 || n > in.remaining()) throw invalid(); byte[] bytes = new byte[n]; in.get(bytes); return bytes;
    }
    private static int count(ByteBuffer in, int maximum) {
        int n = in.getInt(); if (n < 0 || n > maximum) throw invalid(); return n;
    }
    private static <T> T indexed(T[] values, int index) {
        if (index < 0 || index >= values.length) throw invalid(); return values[index];
    }
    private static UUID uuid(ByteBuffer in) { return new UUID(in.getLong(), in.getLong()); }
    private static void uuid(ByteBuffer out, UUID id) { out.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()); }
    private static List<UUID> ids(ByteBuffer in) {
        int n = count(in, MAX_PIECES); var ids = new ArrayList<UUID>(n); UUID previous = null;
        for (int i = 0; i < n; i++) {
            UUID id = uuid(in); if (previous != null && previous.compareTo(id) >= 0) throw invalid();
            ids.add(id); previous = id;
        }
        return ids;
    }
    private static void ids(ByteBuffer out, List<UUID> ids) { out.putInt(ids.size()); for (UUID id : ids) uuid(out, id); }
    private static byte[] hash(byte[] bytes, int length) {
        try { var hash = MessageDigest.getInstance("SHA-256"); hash.update(bytes, 0, length); return hash.digest(); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid construction journal record"); }
}
