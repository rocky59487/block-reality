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
    // Exact-integer sidecar of `num`, mirroring the C++ parser. A double folds every
    // integer above 2^53 onto its neighbours, and the world revision — the one field
    // whose whole job is telling two adjacent values apart — is a 64-bit long. The
    // parser fills these whenever the literal was a plain integer that fits a long.
    private long exact;
    private boolean isExact;
    private String str = "";
    private List<JsonValue> arr = List.of();
    private Map<String, JsonValue> obj = Map.of();

    private JsonValue(Type t) { this.type = t; }

    static JsonValue ofBool(boolean b) { JsonValue v = new JsonValue(Type.BOOL); v.bool = b; return v; }

    static JsonValue ofNum(double d) { JsonValue v = new JsonValue(Type.NUM); v.num = d; return v; }

    static JsonValue ofExact(long l) {
        JsonValue v = new JsonValue(Type.NUM);
        v.num = l;
        v.exact = l;
        v.isExact = true;
        return v;
    }

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

    // Strict interrogators. The defaulted accessors above are for fields where a
    // default is an honest reading; a REQUIRED field goes through these first, so
    // the codec can refuse a missing or wrong-typed value instead of substituting
    // one and letting the reply claim more than the wire actually said.
    public boolean isNum(String key) { return get(key).type == Type.NUM; }

    public boolean isFiniteNum(String key) {
        JsonValue v = get(key);
        return v.type == Type.NUM && Double.isFinite(v.num);
    }

    public boolean isStr(String key) { return get(key).type == Type.STR; }

    public boolean isBool(String key) { return get(key).type == Type.BOOL; }

    public boolean isArr(String key) { return get(key).type == Type.ARR; }

    public boolean isObj(String key) { return get(key).type == Type.OBJ; }

    /** Present, numeric, and written as a plain integer that fits a long — exactly. */
    public boolean isExactInt(String key) {
        JsonValue v = get(key);
        return v.type == Type.NUM && v.isExact;
    }

    public long exactI64(String key) {
        JsonValue v = get(key);
        return (v.type == Type.NUM && v.isExact) ? v.exact : 0L;
    }

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
        // Trailing tokens are a parse error, mirroring the C++ side: accepting a
        // valid prefix would make "{...}garbage" indistinguishable from a good line.
        p.ws();
        if (p.i != src.length()) return NULL;
        return p.ok ? v : NULL;
    }

    private static final class P {
        private final String s;
        int i;
        boolean ok = true;
        // A malformed document could otherwise recurse until the stack gives out; the
        // protocol never nests more than four deep.
        private static final int MAX_DEPTH = 64;
        private int depth;

        P(String s) { this.s = s; }

        void ws() { while (i < s.length() && s.charAt(i) <= ' ') i++; }

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
                        case '"' -> b.append('"');
                        case '\\' -> b.append('\\');
                        case '/' -> b.append('/');
                        case 'u' -> {
                            if (i + 4 <= s.length()) {
                                try {
                                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                } catch (NumberFormatException nfe) {
                                    ok = false;
                                    return "";
                                }
                                i += 4;
                            } else {
                                ok = false;
                                return "";
                            }
                        }
                        // "\x" is not JSON. Reading it as a literal x accepts input
                        // no other parser would, and both ends must reject alike.
                        default -> {
                            ok = false;
                            return "";
                        }
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
            boolean any = false, plain = true;   // plain: digits only after the sign
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    if (!Character.isDigit(c)) plain = false;
                    any = true;
                    i++;
                } else {
                    break;
                }
            }
            if (!any) { ok = false; return NULL; }
            String lit = s.substring(start, i);
            // A plain integer that fits a long is kept EXACT, so revision survives
            // above 2^53 where the double representation cannot. One carve-out: "-0"
            // must stay a double, because long has no negative zero — routing it
            // through ofExact would read it as +0.0, and the shm transport carries
            // the engine's raw -0.0 bits, so the two wires would disagree about a
            // value the equivalence gate compares bit-for-bit.
            if (plain) {
                try {
                    long lv = Long.parseLong(lit);
                    if (lv != 0 || lit.charAt(0) != '-') {
                        return ofExact(lv);
                    }
                } catch (NumberFormatException e) {
                    // Beyond long range: falls through to the double reading below.
                }
            }
            try {
                return ofNum(Double.parseDouble(lit));
            } catch (NumberFormatException e) {
                ok = false;
                return NULL;
            }
        }
    }
}
