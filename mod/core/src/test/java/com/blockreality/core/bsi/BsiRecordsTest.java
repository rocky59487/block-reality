package com.blockreality.core.bsi;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The records are the wire. A field that moves by a byte here is a wrong answer at the far end
 * that still compiles, so these check the layout against hand-written bytes AND against the
 * schema shipped in the jar — the second one catches the case where both sides of this repository
 * agree with each other and disagree with the contract.
 */
class BsiRecordsTest {

    @Test
    void aBlockRecordIsTheFortyBytesTheContractDescribes() {
        BsiRecords.Block b = new BsiRecords.Block(1, -2, 3, 4, -1, 2, 1, 3, 0.5, 0.25);
        byte[] out = BsiRecords.encodeBlocks(List.of(b));
        assertEquals(40, out.length);
        ByteBuffer v = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, v.getInt(0), "x at offset 0");
        assertEquals(-2, v.getInt(4), "y at offset 4");
        assertEquals(3, v.getInt(8), "z at offset 8");
        assertEquals(4, v.getInt(12), "mat at offset 12");
        assertEquals(-1, v.getInt(16), "sect at offset 16 (-1 = material default)");
        assertEquals(2, v.get(20), "axis at offset 20");
        assertEquals(1, v.get(21), "joint at offset 21");
        assertEquals(3, v.get(22), "axisRot at offset 22");
        assertEquals(0, v.get(23), "attr at offset 23, zero when there are no attributes");
        assertEquals(0.5, v.getDouble(24), "fill at offset 24");
        assertEquals(0.25, v.getDouble(32), "strength at offset 32");
    }

    @Test
    void aLoadRecordIsSixtyFourBytesWithZeroMoments() {
        byte[] out = BsiRecords.encodeLoads(List.of(new BsiRecords.Load(7, 8, 9, 1.5, -2.5, 3.5)));
        assertEquals(64, out.length);
        ByteBuffer v = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(7, v.getInt(0));
        assertEquals(0, v.getInt(12), "flags must be zero");
        assertEquals(1.5, v.getDouble(16));
        assertEquals(-2.5, v.getDouble(24));
        assertEquals(3.5, v.getDouble(32));
        assertEquals(0.0, v.getDouble(40), "v1 moments are zero");
        assertEquals(0.0, v.getDouble(56));
    }

    @Test
    void blocksTravelInCanonicalOrderAndDuplicatesAreNamed() {
        List<BsiRecords.Block> jumbled = List.of(
                BsiRecords.Block.of(2, 0, 0, 0, -1, 0),
                BsiRecords.Block.of(0, 0, 1, 0, -1, 0),
                BsiRecords.Block.of(0, 0, 0, 0, -1, 0));
        List<BsiRecords.Block> sorted = BsiRecords.canonical(jumbled);
        assertEquals(List.of(0, 0, 2), sorted.stream().map(BsiRecords.Block::x).toList());
        assertEquals(List.of(0, 1, 0), sorted.stream().map(BsiRecords.Block::z).toList());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BsiRecords.canonical(List.of(BsiRecords.Block.of(5, 6, 7, 0, -1, 0), BsiRecords.Block.of(5, 6, 7, 1, -1, 0))));
        assertTrue(e.getMessage().contains("(5,6,7)"), "the duplicate is named at its cell: " + e.getMessage());
    }

    @Test
    void twoLoadsOnOneCellAreTwoLoadsOrderedByTheirBytes() {
        byte[] out = BsiRecords.encodeLoads(List.of(
                new BsiRecords.Load(1, 0, 0, 0, -2.0, 0),
                new BsiRecords.Load(1, 0, 0, 0, -1.0, 0)));
        assertEquals(128, out.length, "same cell, two records -- the contract says two loads, not one");
        ByteBuffer v = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        assertTrue(v.getDouble(24) <= v.getDouble(88), "ties break by the raw record bytes, deterministically");
    }

    @Test
    void outOfRangeFieldsAreRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new BsiRecords.Block(0, 0, 0, 0, -1, 3, 0, 0, 1, 1), "axis 3");
        assertThrows(IllegalArgumentException.class, () -> new BsiRecords.Block(0, 0, 0, 0, -1, 0, 0, 0, 0, 1), "fill 0");
        assertThrows(IllegalArgumentException.class, () -> new BsiRecords.Block(0, 0, 0, 0, -1, 0, 0, 4, 1, 1), "axisRot 4");
        assertThrows(IllegalArgumentException.class,
                () -> new BsiRecords.Block(BsiRecords.MAX_COORD + 1, 0, 0, 0, -1, 0, 0, 0, 1, 1), "coordinate range");
    }

    @Test
    void everyRecordSizeMatchesTheSchemaShippedInThisJar() throws Exception {
        String schema;
        try (InputStream in = BsiRecordsTest.class.getResourceAsStream("/blockreality/contract/bsi.schema.json")) {
            assertNotNull(in, "the build must copy contract/bsi.schema.json into the jar");
            schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(size(schema, "block", false), BsiRecords.BLOCK_BYTES);
        assertEquals(size(schema, "attr", false), BsiRecords.ATTR_BYTES);
        assertEquals(size(schema, "edit", false), BsiRecords.EDIT_BYTES);
        assertEquals(size(schema, "load", false), BsiRecords.LOAD_BYTES);
        assertEquals(size(schema, "blocks", false), BsiRecords.BLOCK_RESULT_BYTES);
        assertEquals(size(schema, "equilibrium", false), BsiRecords.EQUILIBRIUM_BYTES);
        assertEquals(size(schema, "quality", false), BsiRecords.QUALITY_BYTES);
        assertEquals(size(schema, "buckling", false), BsiRecords.BUCKLING_BYTES);
        assertEquals(size(schema, "members", false), BsiRecords.MEMBER_BYTES);
        assertEquals(size(schema, "memberBlocks", false), BsiRecords.MEMBER_BLOCK_BYTES);
        assertEquals(size(schema, "stations", false), BsiRecords.STATION_BYTES);
        assertEquals(size(schema, "stations", true), BsiRecords.STATION_F32_BYTES);
        assertEquals(size(schema, "facets", false), BsiRecords.FACET_BYTES);
        assertEquals(size(schema, "facetSurfaces", false), BsiRecords.FACET_SURFACES_BYTES);
        assertEquals(size(schema, "facetSurfaces", true), BsiRecords.FACET_SURFACES_F32_BYTES);
    }

    /** Reads {@code x-records.<name>.bytes} (or {@code x-f32}) without a JSON library. */
    private static int size(String schema, String record, boolean f32) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(record) + "\":\\s*\\{[^}]*?\"" + (f32 ? "x-f32" : "bytes") + "\":\\s*(\\d+)")
                .matcher(schema);
        assertTrue(m.find(), "schema has no x-records." + record + (f32 ? ".x-f32" : ".bytes"));
        return Integer.parseInt(m.group(1));
    }
}
