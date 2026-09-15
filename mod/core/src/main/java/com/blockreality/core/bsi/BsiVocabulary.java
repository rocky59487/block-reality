package com.blockreality.core.bsi;

import com.blockreality.core.json.JsonValue;
import java.util.HashMap;
import java.util.Map;

/** Engine-assigned vocabulary identities. Declaration order is never an identifier. */
public record BsiVocabulary(int version, Map<Integer, String> materials, Map<Integer, String> sections) {
    public BsiVocabulary {
        if (version < 1) throw new IllegalArgumentException("invalid vocabulary version");
        materials = checkedCopy(materials);
        sections = checkedCopy(sections);
        if (materials.isEmpty()) throw new IllegalArgumentException("empty material table");
    }

    private static Map<Integer, String> checkedCopy(Map<Integer, String> source) {
        var names = new java.util.HashSet<String>();
        for (var e : source.entrySet()) {
            if (e.getKey() == null || e.getKey() < 0 || e.getValue() == null
                    || e.getValue().isBlank() || !names.add(e.getValue()))
                throw new IllegalArgumentException("invalid or duplicate vocabulary identity");
        }
        return Map.copyOf(source);
    }

    public static BsiVocabulary decode(BsiResponse reply, String requestId, long revision, int version) {
        if (reply == null || reply.isError()) throw new IllegalArgumentException("vocabulary declaration refused");
        var h = reply.header();
        if (!"response".equals(h.str("kind", "")) || !"ok".equals(reply.status())
                || !"bsi.vocab.declare".equals(h.str("method", "")) || !requestId.equals(h.str("id", ""))
                || !h.isExactInt("bsi") || h.exactI64("bsi") != BsiContract.MAJOR
                || !h.isExactInt("revision") || h.exactI64("revision") != revision
                || !h.isExactInt("version") || h.exactI64("version") != version)
            throw new IllegalArgumentException("vocabulary response identity mismatch");
        return new BsiVocabulary(version, table(h, "materials"), table(h, "sections"));
    }

    private static Map<Integer, String> table(JsonValue h, String field) {
        if (!h.isArr(field)) throw new IllegalArgumentException("missing vocabulary " + field);
        var result = new HashMap<Integer, String>();
        for (var row : h.arr(field)) {
            if (!row.isExactInt("id") || row.exactI64("id") < 0 || row.exactI64("id") > Integer.MAX_VALUE
                    || !row.isStr("name") || row.str("name", "").isBlank())
                throw new IllegalArgumentException("invalid vocabulary " + field + " entry");
            if (result.putIfAbsent((int) row.exactI64("id"), row.str("name", "")) != null)
                throw new IllegalArgumentException("duplicate vocabulary " + field + " id");
        }
        return result;
    }

    public int materialId(String name) { return id(materials, name, "material"); }
    public int sectionId(String name) { return id(sections, name, "section"); }

    private static int id(Map<Integer, String> table, String name, String kind) {
        for (var e : table.entrySet()) if (e.getValue().equals(name)) return e.getKey();
        throw new IllegalArgumentException("engine has no " + kind + " named " + name);
    }
}
