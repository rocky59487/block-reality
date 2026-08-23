package com.blockreality.core.json;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parser's contract is that it never throws. These are the inputs a dying process
 * actually produces: a line cut in half, an empty line, and whatever was in the buffer.
 */
class JsonTest {

    @Test
    void parsesTheProtocolShape() {
        JsonValue v = JsonValue.parse(
                "{\"ok\":true,\"revision\":7,\"maxDC\":0.069,\"members\":[{\"id\":1,\"mode\":\"CRUSH\"}]}");
        assertTrue(v.isObject());
        assertTrue(v.bool("ok", false));
        assertEquals(7, v.i64("revision", -1));
        assertEquals(0.069, v.num("maxDC", 0), 1e-12);
        assertEquals(1, v.arr("members").size());
        assertEquals("CRUSH", v.arr("members").get(0).str("mode", ""));
    }

    @Test
    void malformedInputParsesToNullRatherThanThrowing() {
        for (String bad : List.of(
                "",
                "{",
                "{\"ok\":",
                "{\"ok\":true",
                "[1,2",
                "not json at all",
                "{\"s\":\"unterminated",
                "{\"a\":}")) {
            JsonValue v = JsonValue.parse(bad);
            assertTrue(v.isNull(), "should have parsed to NULL: " + bad);
        }
    }

    @Test
    void nullInputIsSafe() {
        assertTrue(JsonValue.parse(null).isNull());
    }

    @Test
    void missingAndWrongTypedFieldsDegradeToDefaults() {
        JsonValue v = JsonValue.parse("{\"n\":\"a string\",\"s\":42,\"b\":[1]}");
        assertEquals(-1, v.num("n", -1), 0);        // string where a number was wanted
        assertEquals("dflt", v.str("s", "dflt"));   // number where a string was wanted
        assertFalse(v.bool("b", false));            // array where a bool was wanted
        assertEquals(0, v.arr("absent").size());
    }

    @Test
    void plainIntegersSurviveAbove2Pow53() {
        // A double folds every integer above 2^53 onto its neighbours; the revision
        // field's whole job is telling two adjacent values apart. 2^53 + 1 is the
        // first value the double reading gets wrong.
        JsonValue v = JsonValue.parse("{\"r\":9007199254740993}");
        assertTrue(v.isExactInt("r"));
        assertEquals(9_007_199_254_740_993L, v.exactI64("r"));

        String s = new JsonWriter().beginObj().kv("r", Long.MAX_VALUE).endObj().done();
        JsonValue back = JsonValue.parse(s);
        assertTrue(back.isExactInt("r"));
        assertEquals(Long.MAX_VALUE, back.exactI64("r"), "Long.MAX_VALUE must round-trip exactly");
    }

    @Test
    void nonPlainNumbersAreNotExactIntegers() {
        // 1e3 EQUALS 1000, but it was not written as a plain integer literal, and the
        // exact reading is for fields where the writer promised one. Accepting the
        // scientific form would let a double-typed field masquerade as a revision.
        JsonValue v = JsonValue.parse("{\"a\":1.5,\"b\":1e3,\"c\":42}");
        assertFalse(v.isExactInt("a"));
        assertFalse(v.isExactInt("b"));
        assertTrue(v.isExactInt("c"));
        assertEquals(42, v.exactI64("c"));
    }

    @Test
    void trailingGarbageRejectsTheWholeLine() {
        // Accepting a valid prefix would make "{...}garbage" indistinguishable from a
        // good line, and a protocol line with a tail is two messages fused, not one.
        assertTrue(JsonValue.parse("{\"ok\":true} trailing").isNull());
        assertTrue(JsonValue.parse("{\"ok\":true}{\"ok\":false}").isNull());
        assertTrue(JsonValue.parse("null null").isNull());
    }

    @Test
    void invalidEscapesAreAParseErrorNotALiteral() {
        // "\x" is not JSON. Reading it as a literal x would accept input no other
        // parser accepts, and both ends must reject alike.
        assertTrue(JsonValue.parse("{\"s\":\"a\\x\"}").isNull());
        assertTrue(JsonValue.parse("{\"s\":\"\\u12g4\"}").isNull());
        assertTrue(JsonValue.parse("{\"s\":\"\\u12\"}").isNull());
    }

    @Test
    void malformedNumbersAreAParseError() {
        assertTrue(JsonValue.parse("1.2.3").isNull());
        assertTrue(JsonValue.parse("{\"n\":1.2.3}").isNull());
        assertTrue(JsonValue.parse("{\"n\":--5}").isNull());
        assertTrue(JsonValue.parse("{\"n\":1e}").isNull());
    }

    @Test
    void deeplyNestedInputDoesNotBlowTheStack() {
        // A hostile or corrupt line must not take the server down via StackOverflowError.
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 5000; i++) b.append('[');
        assertTrue(JsonValue.parse(b.toString()).isNull());
    }

    @Test
    void writerEmitsNullForNonFiniteDoubles() {
        // JSON has no NaN. A bare `nan` token would fail to parse at the far end, which
        // turns a single bad number into a lost message.
        String s = new JsonWriter().beginObj()
                .kv("a", Double.NaN)
                .kv("b", Double.POSITIVE_INFINITY)
                .kv("c", 1.5)
                .endObj().done();
        assertEquals("{\"a\":null,\"b\":null,\"c\":1.5}", s);
        assertTrue(JsonValue.parse(s).isObject());
    }

    @Test
    void writerEscapesControlCharactersSoOneMessageStaysOneLine() {
        String s = new JsonWriter().beginObj().kv("t", "a\nb\tc\"d").endObj().done();
        assertFalse(s.contains("\n"), "a raw newline would split the protocol line in two");
        assertEquals("a\nb\tc\"d", JsonValue.parse(s).str("t", ""));
    }

    @Test
    void roundTripsNestedStructures() {
        String s = new JsonWriter().beginObj()
                .key("members").beginArr()
                .beginObj().kv("id", 1).key("dir").beginArr().val(0.0).val(1.0).val(0.0).endArr().endObj()
                .endArr()
                .kv("ok", true)
                .endObj().done();
        JsonValue v = JsonValue.parse(s);
        assertTrue(v.bool("ok", false));
        List<JsonValue> dir = v.arr("members").get(0).arr("dir");
        assertEquals(3, dir.size());
        assertEquals(1.0, dir.get(1).asNum(0), 0);
    }
}
