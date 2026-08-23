package com.blockreality.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java-side gate for the display track's precision budget (invariant 5).
 *
 * <p>The commit track never leaves double precision. The display track crosses the
 * client packet as float32, and the project's stated budget for it is
 * <strong>rel ≤ 1e-5</strong>. This file pins the BUDGET: for every magnitude the
 * pipeline carries (stresses in MPa, moments in N·mm, geometry in mm, D/C ratios), one
 * round trip double → float → double must stay inside it.
 *
 * <p><strong>This is not the pipeline gate, and it was described as one.</strong> Nothing
 * here imports anything from this project — it is a statement about IEEE-754, and calling
 * it "invariant 5's first executable gate" was a capability claim resting on a test of the
 * language (PR26_REVIEW MECH-10, found independently by five review passes). The gate that
 * claim needs is
 * {@code StressResultPacketTest.everyNumberTheClientDrawsIsWithinTheDisplayBudgetOfTheServersOwn},
 * which walks the whole encoded packet and compares every double the client receives
 * against the one the server computed.
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
