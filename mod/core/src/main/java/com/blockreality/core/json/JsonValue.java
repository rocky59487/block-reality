package com.blockreality.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON tree, mirroring the sidecar's {@code json.hpp} on the C++ side.
 *
 * <p>Hand-written rather than pulled from a library for two reasons. A Forge mod that
 * bundles its own JSON library invites a version clash with every other mod in the pack,
 * and the protocol shape here is fixed and small enough that a parser for it fits on one
 * screen.
 *
 * <p><strong>Nothing here throws.</strong> A malformed document parses to {@link #NULL},
 * a missing field returns the caller's default, and a field of the wrong type returns the
 * caller's default rather than a class cast. The sidecar is a separate process that can
 * die mid-write, so a truncated line is a normal event, not an exceptional one.
 */
public final class JsonValue {

    public enum Type { NULL, BOOL, NUM, STR, ARR, OBJ }

    public static final JsonValue NULL = new JsonValue(Type.NULL);

    private final Type type;
    private boolean bool;
    private double num;
    private String str = "";
    private List<JsonValue> arr = List.of();
    private Map<String, JsonValue> obj = Map.of();

    private JsonValue(Type t) { this.type = t; }

    static JsonValue ofBool(boolean b) { JsonValue v = new JsonValue(Type.BOOL); v.bool = b; return v; }

    static JsonValue ofNum(double d) { JsonValue v = new JsonValue(Type.NUM); v.num = d; return v; }

    static JsonValue ofStr(String s) { JsonValue v = new JsonValue(Type.STR); v.str = s; return v; }

    static JsonValue ofArr(List<JsonValue> a) { JsonValue v = new JsonValue(Type.ARR); v.arr = a; return v; }

    static JsonValue ofObj(Map<String, JsonValue> o) { JsonValue v = new JsonValue(Type.OBJ); v.obj = o; return v; }

    public Type type() { return type; }

    public boolean isNull() { return type == Type.NULL; }

    public boolean isObject() { return type == Type.OBJ; }

    // ------------------------------------------------------------- accessors
    private JsonValue get(String key) {
        JsonValue v = obj.get(key);
        return v == null ? NULL : v;
    }

    public double num(String key, double dflt) {
        JsonValue v = get(key);
        return v.type == Type.NUM ? v.num : dflt;
    }

    public long i64(String key, long dflt) {
        JsonValue v = get(key);
        return v.type == Type.NUM ? (long) v.num : dflt;
    }

    public int i32(String key, int dflt) {
        JsonValue v = get(key);
        return v.type == Type.NUM ? (int) v.num : dflt;
    }

    public String str(String key, String dflt) {
        JsonValue v = get(key);
        return v.type == Type.STR ? v.str : dflt;
    }

    public boolean bool(String key, boolean dflt) {
        JsonValue v = get(key);
        return v.type == Type.BOOL ? v.bool : dflt;
    }

    public boolean has(String key) { return obj.containsKey(key); }

    /** Array field, or an empty list — never null, never a wrong-type failure. */
    public List<JsonValue> arr(String key) {
        JsonValue v = get(key);
        return v.type == Type.ARR ? v.arr : List.of();
    }

    public JsonValue objField(String key) { return get(key); }

    /** This value as a number, for elements of a numeric array. */
    public double asNum(double dflt) { return type == Type.NUM ? num : dflt; }

    public String asStr(String dflt) { return type == Type.STR ? str : dflt; }

    public List<JsonValue> asArr() { return type == Type.ARR ? arr : List.of(); }

    // ---------------------------------------------------------------- parser
    public static JsonValue parse(String src) {
        if (src == null) return NULL;
        P p = new P(src);
        JsonValue v = p.value();
        return p.ok ? v : NULL;
    }

    private static final class P {
        private final String s;
        private int i;
        private boolean ok = true;
        // A malformed document could otherwise recurse until the stack gives out; the
        // protocol never nests more than four deep.
        private static final int MAX_DEPTH = 64;
        private int depth;

        P(String s) { this.s = s; }

        private void ws() { while (i < s.length() && s.charAt(i) <= ' ') i++; }

        private boolean eat(char c) {
            ws();
            if (i < s.length() && s.charAt(i) == c) { i++; return true; }
            return false;
        }

        private boolean lit(String w) {
            ws();
            if (s.startsWith(w, i)) { i += w.length(); return true; }
            return false;
        }

        JsonValue value() {
            if (!ok) return NULL;
            if (++depth > MAX_DEPTH) { ok = false; return NULL; }
            try {
                ws();
                if (i >= s.length()) { ok = false; return NULL; }
                switch (s.charAt(i)) {
                    case '{': return object();
                    case '[': return array();
                    case '"': return ofStr(string());
                    case 't': if (lit("true")) return ofBool(true); ok = false; return NULL;
                    case 'f': if (lit("false")) return ofBool(false); ok = false; return NULL;
                    case 'n': if (lit("null")) return NULL; ok = false; return NULL;
                    default: return number();
                }
            } finally {
                depth--;
            }
        }

        private JsonValue object() {
            Map<String, JsonValue> m = new LinkedHashMap<>();
            if (!eat('{')) { ok = false; return NULL; }
            if (eat('}')) return ofObj(m);
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') { ok = false; return NULL; }
                String k = string();
                if (!eat(':')) { ok = false; return NULL; }
                JsonValue v = value();
                if (!ok) return NULL;
                m.put(k, v);
                if (eat(',')) continue;
                if (eat('}')) return ofObj(m);
                ok = false;
                return NULL;
            }
        }

        private JsonValue array() {
            List<JsonValue> a = new ArrayList<>();
            if (!eat('[')) { ok = false; return NULL; }
            if (eat(']')) return ofArr(a);
            while (true) {
                JsonValue v = value();
                if (!ok) return NULL;
                a.add(v);
                if (eat(',')) continue;
                if (eat(']')) return ofArr(a);
                ok = false;
                return NULL;
            }
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            if (!eat('"')) { ok = false; return ""; }
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 't' -> b.append('\t');
                        case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'u' -> {
                            if (i + 4 <= s.length()) {
                                try {
                                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                } catch (NumberFormatException nfe) {
                                    b.append('?');
                                }
                                i += 4;
                            } else {
                                ok = false;
                                return "";
                            }
                        }
                        default -> b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            ok = false;
            return "";
        }

        private JsonValue number() {
            ws();
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean any = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    any = true;
                    i++;
                } else {
                    break;
                }
            }
            if (!any) { ok = false; return NULL; }
            try {
                return ofNum(Double.parseDouble(s.substring(start, i)));
            } catch (NumberFormatException e) {
                ok = false;
                return NULL;
            }
        }
    }
}
