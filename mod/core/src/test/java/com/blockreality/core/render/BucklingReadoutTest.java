package com.blockreality.core.render;

import com.blockreality.api.BucklingState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BucklingReadoutTest {
    @Test void refusedWorldKeepsTheSuppliedLocalCriticalWarning() {
        for (var state : new BucklingState[]{BucklingState.NOT_ELIGIBLE,
                BucklingState.NOT_ELIGIBLE_SCALE, BucklingState.SOLVER_FAILED}) {
            var rows = BucklingReadout.lines(state, 0, true);
            assertEquals(2, rows.size(), state.name());
            assertEquals("br.buckling.local_critical", rows.get(0).key());
            assertEquals(BucklingReadout.Tone.CRITICAL, rows.get(0).tone());
            assertEquals(state.translationKey(), rows.get(1).key());
            assertTrue(rows.stream().allMatch(r -> r.arguments().isEmpty()), "refusal must not invent a factor");
        }
    }

    @Test void neverDerivesCriticalFromTheDisplayedFactor() {
        assertEquals(1, BucklingReadout.lines(BucklingState.COMPUTED, 0.5, false).size());
        var rows = BucklingReadout.lines(BucklingState.COMPUTED, 2, true);
        assertEquals(2, rows.size());
        assertEquals(BucklingReadout.Tone.CRITICAL, rows.get(0).tone());
        assertEquals("2.000", rows.get(1).arguments().get(0));
    }

    @Test void everyWorldStateHasAnExplicitReadout() {
        for (var state : BucklingState.values()) {
            var rows = BucklingReadout.lines(state, 123.456789, false);
            assertEquals(1, rows.size());
            var row = rows.get(0);
            assertFalse(row.key().isBlank()); assertFalse(row.fallback().isBlank());
            assertEquals(state.hasFactor() ? 1 : 0, row.arguments().size());
            if (state.hasFactor()) assertEquals("123.457", row.arguments().get(0));
            else assertEquals(state.translationKey(), row.key());
        }
    }
}
