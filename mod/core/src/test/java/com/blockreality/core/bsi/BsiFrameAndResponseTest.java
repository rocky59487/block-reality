package com.blockreality.core.bsi;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class BsiFrameAndResponseTest {

    @Test
    void aFrameRoundTrips() {
        byte[] payload = {1, 2, 3, 4, 5};
        byte[] f = BsiFrame.encode("{\"a\":1}", payload);
        BsiFrame.Decoded d = BsiFrame.decode(f, f.length);
        assertNotNull(d);
        assertEquals("{\"a\":1}", d.header());
        assertArrayEquals(payload, d.payload());
        assertTrue((d.flags() & BsiFrame.FLAG_HAS_PAYLOAD) != 0);
    }

    @Test
    void decodingNeverThrowsForAnyTruncationOfAValidFrame() {
        byte[] f = BsiFrame.encode("{\"kind\":\"response\"}", new byte[]{9, 9, 9});
        for (int n = 0; n <= f.length; n++) {
            byte[] cut = Arrays.copyOf(f, n);
            BsiFrame.Decoded d = assertDoesNotThrow(() -> BsiFrame.decode(cut, cut.length),
                    "a truncated frame must be refused, never thrown out of");
            if (n < f.length) assertNull(d, "a short frame is not a frame (" + n + " bytes)");
        }
    }

    @Test
    void badMagicAndInconsistentLengthsAreRefused() {
        byte[] f = BsiFrame.encode("{}", null);
        f[0] = 'X';
        assertNull(BsiFrame.decode(f, f.length), "bad magic");
        byte[] g = BsiFrame.encode("{}", null);
        assertNull(BsiFrame.decode(g, g.length - 1), "length that does not add up");
        assertNull(BsiFrame.decode(null, 0));
    }

    @Test
    void sectionsAreReadWhereTheyLie() {
        // one blocks record (24 B) and one equilibrium record (56 B), laid out as a reply would be
        ByteBuffer p = ByteBuffer.allocate(24 + 56).order(ByteOrder.LITTLE_ENDIAN);
        p.putDouble(1.25).putInt(3).putInt(7).put((byte) 2).put((byte) 1).put((byte) 1).put((byte) 0).putInt(0);
        p.putDouble(-1).putDouble(-2).putDouble(-3).putDouble(1).putDouble(2).putDouble(3).putDouble(1e-12);
        String header = "{\"bsi\":1,\"kind\":\"response\",\"id\":\"r1\",\"method\":\"bsi.solve\",\"revision\":42,"
                + "\"status\":\"ok\",\"diag\":{\"blocks\":1,\"islands\":1},"
                + "\"unassigned\":[{\"why\":\"NON_STRUCTURAL\",\"island\":-1,\"blocks\":[[9,0,0]]}],"
                + "\"sections\":[{\"name\":\"blocks\",\"offset\":0,\"bytes\":24,\"count\":1},"
                + "{\"name\":\"equilibrium\",\"offset\":24,\"bytes\":56,\"count\":1}]}";
        BsiResponse r = BsiResponse.of(new BsiFrame.Decoded(7, header, p.array()));
        assertNotNull(r);
        assertFalse(r.isError());
        assertEquals(42, r.revision());
        assertEquals(1, r.diag("blocks"));

        BsiResponse.BlockResult b = r.blocks().get(0);
        assertEquals(1.25, b.dc());
        assertEquals(3, b.island());
        assertEquals(7, b.owner());
        assertEquals(1, b.ownerKind());
        assertTrue(b.overloaded(), "the ENGINE's flag is read; this side never compares dc to 1 (N19)");

        BsiResponse.Equilibrium eq = r.equilibrium();
        assertArrayEquals(new double[]{-1, -2, -3}, eq.applied());
        assertArrayEquals(new double[]{1, 2, 3}, eq.reaction());
        assertEquals(1e-12, eq.residual());

        assertEquals("NON_STRUCTURAL", r.unassigned().get(0).why());
        assertArrayEquals(new int[]{9, 0, 0}, r.unassigned().get(0).blocks().get(0));
    }

    /**
     * blocks.flags is three independent bits, and reading one of them with another one's mask is
     * the kind of defect that shows up as "everything overloaded is also buckling". All eight
     * combinations, so a wrong mask cannot hide behind a fixture that only ever sets one bit.
     */
    @Test
    void theThreeBlockFlagsAreIndependentBits() {
        for (int f = 0; f < 8; f++) {
            ByteBuffer p = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            p.putDouble(0.5).putInt(1).putInt(0).put((byte) 0).put((byte) 1).put((byte) f).put((byte) 0).putInt(0);
            String header = "{\"bsi\":1,\"kind\":\"response\",\"id\":\"r1\",\"method\":\"bsi.solve\",\"revision\":1,"
                    + "\"status\":\"ok\",\"sections\":[{\"name\":\"blocks\",\"offset\":0,\"bytes\":24,\"count\":1}]}";
            BsiResponse r = BsiResponse.of(new BsiFrame.Decoded(7, header, p.array()));
            assertNotNull(r);
            BsiResponse.BlockResult b = r.blocks().get(0);
            assertEquals((f & 1) != 0, b.overloaded(), "bit0 at flags=" + f);
            assertEquals((f & 2) != 0, b.indicative(), "bit1 at flags=" + f);
            assertEquals((f & 4) != 0, b.bucklingCritical(), "bit2 at flags=" + f);
        }
    }

    @Test
    void anErrorFrameCarriesItsTokenNotItsProse() {
        String header = "{\"bsi\":1,\"kind\":\"error\",\"id\":\"r1\",\"method\":\"bsi.solve\",\"revision\":1,"
                + "\"code\":\"LOAD_TARGET\",\"message\":\"no active member owns this block\",\"at\":[1,2,3]}";
        BsiResponse r = BsiResponse.of(new BsiFrame.Decoded(1, header, new byte[0]));
        assertNotNull(r);
        assertTrue(r.isError());
        assertEquals("LOAD_TARGET", r.code(), "consumers branch on the token");
        assertFalse(r.message().isEmpty(), "the message is diagnostic only");
        assertTrue(r.blocks().isEmpty(), "an error frame has no sections to read");
    }

    @Test
    void headersCarryTheirKeysInSchemaOrder() {
        String h = BsiHeaders.hello("r1", 5, "test", "0".repeat(64), 1024);
        assertTrue(h.startsWith("{\"bsi\":1,\"kind\":\"request\",\"id\":\"r1\",\"method\":\"bsi.hello\",\"revision\":5,\"body\":"), h);
        assertTrue(h.contains("\"contractSha256\":\"" + "0".repeat(64) + "\""));
        String s = BsiHeaders.solve("r2", 6, true, new double[]{0, -9.81, 0}, 2, 1, java.util.List.of("members"));
        assertTrue(s.contains("\"selfWeight\":true"), s);
        assertTrue(s.contains("\"loads\":2"), s);
        assertTrue(s.contains("\"numThreads\":1"), s);
        assertTrue(s.contains("\"include\":[\"members\"]"), s);
        assertEquals(new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8), s);
    }
}
