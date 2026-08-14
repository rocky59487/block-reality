package com.blockreality.core.render;

import com.blockreality.api.render.Hatch;
import com.blockreality.api.render.Rgb;
import com.blockreality.api.render.StressPalette;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The requirement in one line: a loaded cantilever must read blue on top and red
 * underneath, not uniformly red.
 */
class StressPaletteTest {

    private static final StressPalette P = StressPalette.SIGNED_DEFAULT;

    @Test
    void tensionIsBlueAndCompressionIsRed() {
        Rgb tension = P.signedStress(+24.24, 24.24);
        Rgb compression = P.signedStress(-24.24, 24.24);

        assertTrue(tension.b() > tension.r(),
                "tension must read blue, got " + tension);
        assertTrue(compression.r() > compression.b(),
                "compression must read red, got " + compression);
    }

    @Test
    void equalAndOppositeStressesGiveDistinctColours() {
        // The failure this guards against is the "everything glows red" overlay: if the
        // palette keyed off magnitude alone, these two would be identical.
        assertNotEquals(P.signedStress(+10, 20), P.signedStress(-10, 20));
    }

    @Test
    void zeroStressIsNeutral() {
        assertEquals(P.zeroColour(), P.signedStress(0, 20));
        assertEquals(Hatch.NONE, P.hatchFor(0, 20));
    }

    @Test
    void saturationRisesWithMagnitude() {
        Rgb zero = P.zeroColour();
        double prev = -1;
        for (double s : new double[]{1, 5, 10, 15, 20}) {
            double d = distance(zero, P.signedStress(s, 20));
            assertTrue(d > prev, "colour must move further from neutral as stress rises");
            prev = d;
        }
    }

    @Test
    void magnitudeMapsLinearlySoAFigureCanBeReadAgainstItsLegend() {
        // Half the stress must sit halfway along the ramp in linear light, within a
        // rounding step of the 8-bit encode.
        Rgb half = P.signedStress(10, 20);
        Rgb expected = Rgb.lerp(P.zeroColour(), P.tensionColour(), 0.5);
        assertTrue(distance(half, expected) <= 2.0,
                "expected " + expected + " got " + half);
    }

    @Test
    void hatchGivesANonColourChannel() {
        assertEquals(Hatch.DIAGONAL_UP, P.hatchFor(+10, 20));
        assertEquals(Hatch.DIAGONAL_DOWN, P.hatchFor(-10, 20));
        assertEquals(Hatch.CROSS, StressPalette.utilizationHatch(1.4));
    }

    @Test
    void degenerateInputsGiveNeutralRatherThanNaN() {
        for (double peak : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(P.zeroColour(), P.signedStress(5, peak));
        }
        assertEquals(P.zeroColour(), P.signedStress(Double.NaN, 10));
    }

    @Test
    void utilisationRampIsColdThenAmberThenFlatVermilion() {
        Rgb safe = StressPalette.utilization(0.1);
        Rgb near = StressPalette.utilization(0.95);
        Rgb over = StressPalette.utilization(1.2);

        assertTrue(safe.b() > safe.r(), "safe should read cold, got " + safe);
        assertTrue(near.r() > near.b(), "near-limit should read warm, got " + near);
        assertTrue(over.r() > over.g(), "over-limit should read red, got " + over);

        // Beyond the limit the member has simply failed; 3.0 is not more interesting
        // than 1.5 and must not be a brighter red.
        assertEquals(over, StressPalette.utilization(3.0));
    }

    @Test
    void failureRedIsNotTheSameAsCompressionRed() {
        // Red means "compressed" in STRESS mode and "spent" in UTILIZATION mode. If the
        // two were the same swatch, a screenshot would not say which lens it came from.
        assertNotEquals(StressPalette.utilization(1.5), P.compressionColour());
    }

    @Test
    void teachingPaletteKeepsItsOwnTokens() {
        assertEquals("#0C6266", StressPalette.TEACHING.tensionColour().toString());
        assertEquals("#315E80", StressPalette.TEACHING.compressionColour().toString());
        assertEquals(StressPalette.SIGNED_DEFAULT, StressPalette.byId("nonsense"));
    }

    @Test
    void linearLightInterpolationKeepsTheMidpointOutOfTheMud() {
        // Lerping sRGB bytes directly darkens the middle of a blue-to-red ramp, putting a
        // dark band exactly at the neutral axis — the place the reader is looking.
        Rgb mid = Rgb.lerp(Rgb.hex(0x2F6FB0), Rgb.hex(0xB0342A), 0.5);
        int naive = (0x2F + 0xB0) / 2 + (0x6F + 0x34) / 2 + (0xB0 + 0x2A) / 2;
        assertTrue(mid.r() + mid.g() + mid.b() > naive,
                "linear-light midpoint should be lighter than the naive byte average");
    }

    private static double distance(Rgb a, Rgb b) {
        double dr = a.r() - b.r(), dg = a.g() - b.g(), db = a.b() - b.b();
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
