package com.blockreality.core.bsi;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Hand-written LE fixtures exercise the contract, with no stress formulas or native substitutes. */
class BsiRecoveryTest {
    private static ByteBuffer bytes(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }
    private static String section(String name, long offset, long size, long count) {
        return "{\"name\":\"" + name + "\",\"offset\":" + offset + ",\"bytes\":" + size + ",\"count\":" + count + "}";
    }
    private static BsiResponse response(byte[] payload, String... sections) {
        return BsiResponse.of(new BsiFrame.Decoded(0, "{\"sections\":[" + String.join(",", sections) + "]}", payload));
    }
    private static byte[] shellBytes(boolean f32) {
        int width = f32 ? 4 : 8;
        ByteBuffer b = bytes(560 + 64 * width + 36);
        for (int e = 0; e < 2; e++) {
            int o = 280 * e;
            b.putInt(o, 7 + 4 * e).putInt(o + 4, e).putInt(o + 8, e == 0 ? 0 : 1).putInt(o + 12, e + 1).putInt(o + 16, 5);
            b.putDouble(o + 24, .2 + e);
            for (int k = 0; k < 12; k++) b.putDouble(o + 32 + 8 * k, 100 * e + k + .5);
            for (int k = 0; k < 17; k++) b.putDouble(o + 128 + 8 * k, 20 * e + k + .25);
            b.putDouble(o + 264, 1. + 0x1p-30).put(o + 272, (byte) (e == 0 ? 1 : 2)).put(o + 273, (byte) 6);
        }
        for (int k = 0; k < 64; k++) {
            if (f32) b.putInt(560 + 4 * k, 0x3f800001 + k);
            else b.putDouble(560 + 8 * k, k + .125);
        }
        for (int k = 0; k < 9; k++) b.putInt(560 + 64 * width + 4 * k, k - 4);
        return b.array();
    }
    private static BsiResponse shell(byte[] data, boolean f32) {
        int size = f32 ? 256 : 512;
        return response(data, section("facets", 0, 560, 2),
                section(f32 ? "facetSurfaces:f32" : "facetSurfaces", 560, size, 2), section("facetBlocks", 560 + size, 36, 3));
    }

    @Test void everyFacetFieldAndItsChildRangesDecodeFromSpecifiedOffsets() {
        for (boolean f32 : new boolean[]{false, true}) {
            var r = shell(shellBytes(f32), f32);
            assertEquals(2, r.facets().size()); assertEquals(3, r.facetBlocks().size());
            for (int e = 0; e < 2; e++) {
                var f = r.facets().get(e);
                assertEquals(7 + 4 * e, f.id()); assertEquals(e, f.island()); assertEquals(5, f.material());
                assertEquals(e == 0 ? 0 : 1, f.blockFirst()); assertEquals(e + 1, f.blockCount());
                assertEquals(.2 + e, f.thicknessM());
                for (int c = 0; c < 4; c++) for (int j = 0; j < 3; j++) assertEquals(100 * e + 3 * c + j + .5, f.corners()[c][j]);
                List<double[]> vectors = List.of(f.ex(), f.ey(), f.n(), f.membrane(), f.bending(), f.shear());
                int k = 0; for (var vector : vectors) for (double v : vector) assertEquals(20 * e + k++ + .25, v);
                assertEquals(Double.doubleToRawLongBits(1. + 0x1p-30), Double.doubleToRawLongBits(f.dc()));
                assertEquals(e == 0, f.overloaded()); assertEquals(e == 1, f.governingTop()); assertEquals(6, f.governingFibre());
            }
            for (int k = 0; k < 3; k++) assertArrayEquals(new int[]{3 * k - 4, 3 * k - 3, 3 * k - 2}, r.facetBlocks().get(k));
        }
    }

    @Test void surfacesKeepTopBottomCornerAndScalarOrderAtBothWidths() {
        for (boolean f32 : new boolean[]{false, true}) {
            var r = shell(shellBytes(f32), f32); int k = 0;
            for (var f : r.facetSurfaces()) for (var side : List.of(f.top(), f.bottom())) {
                assertEquals(4, side.size());
                for (var s : side) for (double v : new double[]{s.s1(), s.s2(), s.theta(), s.vm()}) {
                    assertEquals(f32 ? (double) Float.intBitsToFloat(0x3f800001 + k) : k + .125, v); k++;
                }
            }
            assertEquals(64, k);
        }
    }

    @Test void allElevenStationFieldsUseTheSelectedStorageIncludingPositionAndMissingAxes() {
        int[] ieee = {0x3eaaaaab, 0x3f800002, 1, 0x80000000, 0x7f7fffff, 2, 0x3f800000, 0xbf800000, 0, 0x7fc00000, 0x7fc00000};
        for (boolean f32 : new boolean[]{false, true}) {
            int width = f32 ? 4 : 8; ByteBuffer p = bytes(22 * width);
            for (int k = 0; k < 22; k++) {
                if (f32) p.putInt(ieee[k % 11]); else p.putDouble(k % 11 >= 9 ? Double.NaN : k + .125);
            }
            var r = response(p.array(), section(f32 ? "stations:f32" : "stations", 0, p.capacity(), 2));
            assertTrue(r.members().isEmpty()); assertEquals(2, r.stations().length);
            for (int k = 0; k < 22; k++) {
                double v = r.stations()[k / 11][k % 11];
                if (k % 11 >= 9) assertTrue(Double.isNaN(v));
                else assertEquals(Double.doubleToRawLongBits(f32 ? (double) Float.intBitsToFloat(ieee[k % 11]) : k + .125), Double.doubleToRawLongBits(v));
            }
        }
    }

    @Test void oldHeadersAreByteIdenticalAndStorageDoesNotImplyDisplay() {
        String old = "{\"bsi\":1,\"kind\":\"request\",\"id\":\"r\",\"method\":\"bsi.solve\",\"revision\":6,\"body\":{\"selfWeight\":true,\"gravity\":[0,-9.81,0],\"loads\":2,\"numThreads\":1,\"include\":[\"members\"]}}";
        assertEquals(old, BsiHeaders.solve("r", 6, true, new double[]{0, -9.81, 0}, 2, 1, List.of("members")));
        assertEquals(old, BsiHeaders.solve("r", 6, true, new double[]{0, -9.81, 0}, 2, 1, List.of("members"), null));
        var p = new BsiHeaders.Precision(BsiHeaders.Tier.COMMIT, BsiHeaders.Storage.F32);
        String h = BsiHeaders.solve("r", 6, false, null, 0, null, List.of("stations"), p);
        assertTrue(h.contains("\"precision\":{\"tier\":\"commit\",\"storage\":\"f32\"}"));
        assertFalse(h.contains("display"));
        assertThrows(NullPointerException.class, () -> new BsiHeaders.Precision(null, BsiHeaders.Storage.F32));
    }

    @Test void malformedDirectoryNeverBecomesAnEmptyReadback() {
        List<String> bad = new ArrayList<>(List.of(section("stations", 0, 87, 1), section("stations", 0, 88, 2),
                section("x-new", 1, Integer.MAX_VALUE, 0), section("x-new", 0, 0, 2147483648L),
                section("x-new", -1, 1, 1), section("x-new", 0, -1, 1), section("x-new", 0, 0, -1),
                section("members", 0, 160, 1), section("stations", 0, 0, Integer.MAX_VALUE)));
        bad.add(section("stations", 0, 88, 1).replace("\"count\":1", "\"count\":1.5"));
        bad.add(section("stations", 0, 88, 1).replace("\"count\":1", "\"count\":\"1\""));
        bad.add("{\"name\":\"stations\",\"offset\":0,\"bytes\":88}");
        for (String s : bad) assertThrows(IllegalArgumentException.class, () -> response(new byte[88], s), s);
        assertThrows(IllegalArgumentException.class, () -> BsiResponse.of(new BsiFrame.Decoded(0, "{\"sections\":{}}", new byte[0])));
    }

    @Test void duplicatesOverlapsAndDualStorageAreRejectedButUnknownSectionsRemain() {
        String s = section("stations", 0, 88, 1);
        assertThrows(IllegalArgumentException.class, () -> response(new byte[176], s, s));
        assertThrows(IllegalArgumentException.class, () -> response(new byte[176], s, section("x-new", 87, 2, 0)));
        assertThrows(IllegalArgumentException.class, () -> response(new byte[176], s, section("stations:f32", 88, 44, 1)));
        var r = response(new byte[100], s, section("x-new", 88, 12, 42));
        assertTrue(r.sections().containsKey("x-new")); assertEquals(1, r.stations().length);
    }

    @Test void parentRangesUseUnsignedArithmeticAndCannotOverflow() {
        for (int[] range : new int[][]{{-1, 1}, {0, -1}, {Integer.MAX_VALUE, Integer.MAX_VALUE}, {2, 2}, {4, 0}}) {
            byte[] data = shellBytes(false); ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(288, range[0]).putInt(292, range[1]);
            assertThrows(IllegalArgumentException.class, () -> shell(data, false));
        }
        ByteBuffer m = bytes(160 + 36 + 264); m.putInt(8, 1).putInt(12, 2).putInt(16, 1).putInt(20, 2);
        String member = section("members", 0, 160, 1), cells = section("memberBlocks", 160, 36, 3), stations = section("stations", 196, 264, 3);
        assertEquals(2, response(m.array(), member, cells, stations).members().get(0).stationCount());
        assertNotNull(response(m.array(), member, cells), "members-only need no station section");
        m.putInt(20, 3);
        assertThrows(IllegalArgumentException.class, () -> response(m.array(), member, cells, stations));
        assertThrows(IllegalArgumentException.class, () -> response(m.array(), member, stations));
    }

    @Test void facetsRequireTheirSurfacesAndCellIndexWithEqualCardinality() {
        byte[] p = shellBytes(false); String f = section("facets", 0, 560, 2), s = section("facetSurfaces", 560, 512, 2), c = section("facetBlocks", 1072, 36, 3);
        assertThrows(IllegalArgumentException.class, () -> response(p, f, c));
        assertThrows(IllegalArgumentException.class, () -> response(p, f, s));
        assertThrows(IllegalArgumentException.class, () -> response(p, s, c));
        assertThrows(IllegalArgumentException.class, () -> response(p, f, section("facetSurfaces", 560, 256, 1), c));
        assertThrows(IllegalArgumentException.class, () -> response(p, f, s, c, section("facetSurfaces:f32", 1108, 0, 0)));
    }

    @Test void everyEmittedRecoveryScalarRejectsNonfiniteValuesExceptMissingAxes() {
        for (boolean f32 : new boolean[]{false, true}) for (int field = 0; field < 31 + 32; field++) {
            for (double poison : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                byte[] p = shellBytes(f32); ByteBuffer b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
                if (field < 31) b.putDouble(280 + 24 + field * 8, poison);
                else if (f32) b.putFloat(560 + 128 + (field - 31) * 4, (float) poison);
                else b.putDouble(560 + 256 + (field - 31) * 8, poison);
                assertThrows(IllegalArgumentException.class, () -> shell(p, f32));
            }
        }
        for (boolean f32 : new boolean[]{false, true}) for (int field = 0; field < 11; field++) {
            int width = f32 ? 4 : 8;
            for (double poison : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                ByteBuffer p = bytes(22 * width);
                if (f32) p.putFloat((11 + field) * width, (float) poison); else p.putDouble((11 + field) * width, poison);
                String s = section(f32 ? "stations:f32" : "stations", 0, p.capacity(), 2);
                if (field >= 9 && Double.isNaN(poison)) assertNotNull(response(p.array(), s));
                else assertThrows(IllegalArgumentException.class, () -> response(p.array(), s));
            }
        }
        for (int k = 0; k < 15; k++) {
            ByteBuffer m = bytes(160); m.putDouble(32 + 8 * k, Double.NaN);
            assertThrows(IllegalArgumentException.class, () -> response(m.array(), section("members", 0, 160, 1)));
        }
    }

    @Test void validatedPayloadCannotBeChangedThroughCallerAliases() {
        byte[] p = shellBytes(false); var r = shell(p, false);
        ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putDouble(24, Double.NaN);
        ByteBuffer.wrap(r.payload()).order(ByteOrder.LITTLE_ENDIAN).putDouble(24, Double.NaN);
        assertEquals(.2, r.facets().get(0).thicknessM());
        assertThrows(UnsupportedOperationException.class, () -> r.sections().clear());
    }

    @Test void vocabularySectionsAreNameIdTablesAndNeverBinaryRecords() {
        for (String method : List.of("bsi.vocab.declare", "bsi.vocab.query")) {
            String h = "{\"method\":\"" + method + "\",\"sections\":[{\"id\":4,\"name\":\"rect\"}]}";
            var r = BsiResponse.of(new BsiFrame.Decoded(0, h, new byte[0]));
            assertEquals("rect", r.header().arr("sections").get(0).str("name", ""));
            assertTrue(r.sections().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> BsiResponse.of(new BsiFrame.Decoded(0, h, new byte[12])));
        }
    }
}
