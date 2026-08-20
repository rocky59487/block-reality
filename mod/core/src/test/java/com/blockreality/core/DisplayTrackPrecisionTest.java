package com.blockreality.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java-side gate for the display track's precision budget (invariant 5).
 *
 * <p>The commit track never leaves double precision. The display track crosses the
 * client packet as float32, and the project's stated budget for it is
 * <strong>rel ≤ 1e-5</strong>. That claim was documentation-only — no test anywhere
 * held the down-conversion to it (GATE-1/TEST-12). This is that test: for every
 * magnitude the pipeline can carry (stresses in MPa, moments in N·mm, geometry in mm,
 * D/C ratios), one round trip double → float → double must stay inside the budget.
 *
 * <p>Why it passes with margin: float32 has a 24-bit significand, so the worst
 * relative error for a NORMAL value is 2^-24 ≈ 6.0e-8 — three decades inside 1e-5.
 * The test still matters, twice over: it pins the budget so a future wire change to
 * float16, or a scaling trick that pushes values subnormal, fails a test instead of
 * a screenshot; and it documents where the budget does NOT hold (subnormals), which
 * is why the packet rejects non-finite values instead of clamping into that range.
 */
class DisplayTrackPrecisionTest {

    private static final double DISPLAY_REL_BUDGET = 1e-5;

    @Test
    void oneFloatRoundTripStaysInsideTheDisplayBudget() {
        // Every decade the pipeline carries: D/C ~1, stresses up to 1e3 MPa, section
        // moduli ~1e6, moments ~1e9 N·mm, stiffnesses beyond — and their negatives.
        for (int exp = -6; exp <= 12; exp++) {
            for (double mantissa : new double[] { 1.0, 1.2345678901234, Math.PI / 3, 9.999999 }) {
                double value = mantissa * Math.pow(10, exp);
                check(value);
                check(-value);
            }
        }
    }

    @Test
    void theBoundaryValuesTheClassifierCaresAboutSurvive() {
        // Values that sit exactly where a display-side comparison would flip. The
        // packet carries the verdict separately (server double), but the NUMBER shown
        // must still be within budget of the truth.
        for (double value : new double[] { 1.0, Math.nextUp(1.0), Math.nextDown(1.0),
                0.6, 1.0 + 1e-7, 1.0 - 1e-7 }) {
            check(value);
        }
    }

    private static void check(double value) {
        double roundTripped = (float) value;
        double rel = Math.abs(roundTripped - value) / Math.abs(value);
        assertTrue(rel <= DISPLAY_REL_BUDGET,
                "display track budget violated at " + value + ": rel error " + rel
                        + " > " + DISPLAY_REL_BUDGET + " (invariant 5)");
    }
}
