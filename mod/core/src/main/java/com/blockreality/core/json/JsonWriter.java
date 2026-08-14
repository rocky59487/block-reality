package com.blockreality.core.json;

/**
 * Streaming JSON writer, matching the sidecar's writer.
 *
 * <p>Non-finite doubles are written as {@code null}: JSON has no NaN or Infinity, and a
 * bare {@code nan} token would fail to parse at the far end. Emitting {@code null} makes
 * the far end fall back to a default, which is a survivable outcome; emitting a broken
 * token is not.
 */
public final class JsonWriter {

    private final StringBuilder sb = new StringBuilder(256);
    private final boolean[] first = new boolean[32];
    private int depth = -1;
    private boolean suppressSep;

    public JsonWriter beginObj() { sep(); sb.append('{'); push(); return this; }

    public JsonWriter endObj() { sb.append('}'); pop(); return this; }

    public JsonWriter beginArr() { sep(); sb.append('['); push(); return this; }

    public JsonWriter endArr() { sb.append(']'); pop(); return this; }

    public JsonWriter key(String k) { sep(); sb.append('"').append(esc(k)).append("\":"); suppressSep = true; return this; }

    public JsonWriter val(boolean b) { sep(); sb.append(b); mark(); return this; }

    public JsonWriter val(long v) { sep(); sb.append(v); mark(); return this; }

    public JsonWriter val(int v) { return val((long) v); }

    public JsonWriter val(String v) { sep(); sb.append('"').append(esc(v)).append('"'); mark(); return this; }

    public JsonWriter val(double d) {
        sep();
        if (!Double.isFinite(d)) sb.append("null");
        else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
        else sb.append(d);
        mark();
        return this;
    }

    public JsonWriter kv(String k, boolean v) { return key(k).val(v); }

    public JsonWriter kv(String k, long v) { return key(k).val(v); }

    public JsonWriter kv(String k, int v) { return key(k).val(v); }

    public JsonWriter kv(String k, double v) { return key(k).val(v); }

    public JsonWriter kv(String k, String v) { return key(k).val(v); }

    public String done() { return sb.toString(); }

    private void push() { if (depth < first.length - 1) first[++depth] = true; }

    private void pop() { if (depth >= 0) depth--; mark(); }

    private void sep() {
        if (suppressSep) { suppressSep = false; return; }
        if (depth >= 0) {
            if (!first[depth]) sb.append(',');
            first[depth] = false;
        }
    }

    private void mark() { if (depth >= 0) first[depth] = false; }

    private static String esc(String in) {
        StringBuilder b = new StringBuilder(in.length() + 8);
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    // A newline smuggled into a token would split one protocol line into
                    // two, so control characters are replaced rather than escaped.
                    if (c < 0x20) b.append(' ');
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }
}
