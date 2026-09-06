package com.blockreality.core.bsi;

import com.blockreality.core.json.JsonValue;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validate the binary directory once, before any consumer can read a record. */
final class BsiSections {
    private BsiSections() {}

    static Map<String, BsiResponse.Section> parse(JsonValue header, byte[] payload) {
        Map<String, BsiResponse.Section> sections = new LinkedHashMap<>();
        String method = header.str("method", "");
        // These methods return a vocabulary name/id table named "sections".
        if ("bsi.vocab.declare".equals(method) || "bsi.vocab.query".equals(method)) {
            if (payload.length != 0) throw invalid("vocabulary reply must have no binary payload");
            return Map.of();
        }
        if (header.has("sections") && !header.isArr("sections")) throw invalid("sections must be an array");
        for (JsonValue value : header.arr("sections")) {
            if (!value.isStr("name") || value.str("name", "").isEmpty()) throw invalid("section name");
            var s = new BsiResponse.Section(value.str("name", ""), integer(value, "offset"), integer(value, "bytes"), integer(value, "count"));
            if ((long) s.offset() + s.bytes() > payload.length) throw invalid("section outside payload: " + s.name());
            int size = recordSize(s.name());
            if (size > 0 && (long) s.count() * size != s.bytes()) throw invalid("record size/count: " + s.name());
            if (sections.putIfAbsent(s.name(), s) != null) throw invalid("duplicate section: " + s.name());
        }
        var ordered = new ArrayList<>(sections.values());
        ordered.sort(Comparator.comparingInt(BsiResponse.Section::offset));
        long end = 0;
        for (var s : ordered) if (s.bytes() > 0) {
            if (s.offset() < end) throw invalid("overlapping section: " + s.name());
            end = (long) s.offset() + s.bytes();
        }
        for (String base : new String[]{"stations", "facetSurfaces"})
            if (sections.containsKey(base) && sections.containsKey(base + ":f32")) throw invalid("two storage variants: " + base);
        ByteBuffer data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        var stations = either(sections, "stations");
        var members = sections.get("members");
        if (members != null) for (int k = 0; k < members.count(); k++) {
            int o = members.offset() + k * BsiRecords.MEMBER_BYTES;
            range(data, o + 8, sections.get("memberBlocks"), true);
            range(data, o + 16, stations, false);
            finite(data, o + 32, 15, false, false);
        }
        var facets = sections.get("facets");
        var surfaces = either(sections, "facetSurfaces");
        if (facets != null || surfaces != null || sections.containsKey("facetBlocks")) {
            if (facets == null || surfaces == null || !sections.containsKey("facetBlocks") || facets.count() != surfaces.count())
                throw invalid("facets require matching surfaces and facetBlocks");
            for (int k = 0; k < facets.count(); k++) {
                int o = facets.offset() + k * BsiRecords.FACET_BYTES;
                range(data, o + 8, sections.get("facetBlocks"), true);
                finite(data, o + 24, 31, false, false);
            }
            finite(data, surfaces.offset(), surfaces.count() * 32, surfaces.name().endsWith(":f32"), false);
        }
        if (stations != null) {
            boolean f32 = stations.name().endsWith(":f32");
            int width = f32 ? 4 : 8;
            for (int k = 0; k < stations.count(); k++) {
                int o = stations.offset() + k * 11 * width;
                finite(data, o, 9, f32, false); finite(data, o + 9 * width, 2, f32, true);
            }
        }
        return Collections.unmodifiableMap(sections);
    }

    private static int integer(JsonValue value, String key) {
        if (!value.isExactInt(key)) throw invalid("section " + key + " must be an integer");
        long n = value.exactI64(key);
        if (n < 0 || n > Integer.MAX_VALUE) throw invalid("section " + key + " outside addressable range");
        return (int) n;
    }
    private static BsiResponse.Section either(Map<String, BsiResponse.Section> sections, String base) {
        return sections.containsKey(base) ? sections.get(base) : sections.get(base + ":f32");
    }
    private static void range(ByteBuffer data, int offset, BsiResponse.Section child, boolean required) {
        long first = Integer.toUnsignedLong(data.getInt(offset)), count = Integer.toUnsignedLong(data.getInt(offset + 4));
        if (first > Integer.MAX_VALUE || count > Integer.MAX_VALUE ||
                (child == null ? required && (first != 0 || count != 0) : first + count > child.count()))
            throw invalid("child record range");
    }
    private static void finite(ByteBuffer data, int offset, int count, boolean f32, boolean missing) {
        for (int k = 0; k < count; k++) {
            double v = f32 ? data.getFloat(offset + 4 * k) : data.getDouble(offset + 8 * k);
            if (!Double.isFinite(v) && !(missing && Double.isNaN(v))) throw invalid("nonfinite recovery value");
        }
    }
    private static int recordSize(String name) {
        return switch (name) {
            case "blocks" -> BsiRecords.BLOCK_RESULT_BYTES;
            case "equilibrium" -> BsiRecords.EQUILIBRIUM_BYTES;
            case "quality" -> BsiRecords.QUALITY_BYTES;
            case "buckling" -> BsiRecords.BUCKLING_BYTES;
            case "members" -> BsiRecords.MEMBER_BYTES;
            case "memberBlocks" -> BsiRecords.MEMBER_BLOCK_BYTES;
            case "stations" -> BsiRecords.STATION_BYTES;
            case "stations:f32" -> BsiRecords.STATION_F32_BYTES;
            case "facets" -> BsiRecords.FACET_BYTES;
            case "facetBlocks" -> BsiRecords.FACET_BLOCK_BYTES;
            case "facetSurfaces" -> BsiRecords.FACET_SURFACES_BYTES;
            case "facetSurfaces:f32" -> BsiRecords.FACET_SURFACES_F32_BYTES;
            case "attrsEcho" -> BsiRecords.ATTR_BYTES;
            default -> 0;
        };
    }
    private static IllegalArgumentException invalid(String why) { return new IllegalArgumentException("Invalid BSI response: " + why); }
}
