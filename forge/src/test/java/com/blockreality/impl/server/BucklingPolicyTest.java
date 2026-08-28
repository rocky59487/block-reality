package com.blockreality.impl.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The size policy's boundaries, pinned: equality runs, one past does not, zero never. */
class BucklingPolicyTest {

    @Test
    void theLimitIsInclusiveAndZeroMeansNever() {
        assertTrue(BucklingPolicy.enabled(299, 300));
        assertTrue(BucklingPolicy.enabled(300, 300), "the limit itself still runs");
        assertFalse(BucklingPolicy.enabled(301, 300), "one past the limit is skipped");
        assertFalse(BucklingPolicy.enabled(1, 0), "zero disables the screen outright");
        assertTrue(BucklingPolicy.enabled(0, 1), "an empty request is trivially within");
    }
}
