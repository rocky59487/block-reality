package com.blockreality.impl.server;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.WorldRevision;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-flight flag release rule (#36): whatever the background solve does — return,
 * throw, or die in delivery — the flag is released exactly once, and a throw is
 * logged, never swallowed. The bug this locks out wedged a dimension's analysis
 * permanently on the first unexpected exception, with {@code /br reset} unable to
 * recover it because the flag, not the engine, was stuck.
 */
class SolveDispatchTest {

    private static final AnalysisResult RESULT =
            AnalysisResult.failed(new WorldRevision(1), "any");

    private final AtomicBoolean inFlight = new AtomicBoolean(true);
    private final List<String> errors = new ArrayList<>();

    private final SolveDispatch.ErrorSink sink = (msg, t) -> errors.add(msg + ": " + t);

    @Test
    void aThrowingSolveReleasesTheFlagAndLogs() {
        SolveDispatch.run(
                () -> { throw new IllegalStateException("boom"); },
                r -> { throw new AssertionError("deliver must not run when solve threw"); },
                () -> inFlight.set(false),
                sink);
        assertFalse(inFlight.get(), "the flag must be released or the loop is dead (#36)");
        assertEquals(1, errors.size(), "the failure must be LOGGED, not swallowed");
        assertTrue(errors.get(0).contains("boom"), errors.get(0));
    }

    @Test
    void aThrowingDeliveryReleasesTheFlagAndLogs() {
        // The other half of the same hole: solve returned fine, but scheduling the
        // apply threw. Same rule, same consequence if violated.
        SolveDispatch.run(
                () -> RESULT,
                r -> { throw new RuntimeException("server queue rejected"); },
                () -> inFlight.set(false),
                sink);
        assertFalse(inFlight.get());
        assertEquals(1, errors.size());
    }

    @Test
    void aSuccessfulRunHandsTheResultToDeliveryAndDoesNotReleaseItself() {
        // On success the APPLY path owns the release (its own try/finally): releasing
        // here too would open a window where a second solve dispatches while the first
        // is still being applied.
        AtomicInteger delivered = new AtomicInteger();
        SolveDispatch.run(
                () -> RESULT,
                r -> { assertSame(RESULT, r); delivered.incrementAndGet(); },
                () -> inFlight.set(false),
                sink);
        assertEquals(1, delivered.get());
        assertTrue(inFlight.get(), "release on success belongs to the apply path");
        assertTrue(errors.isEmpty());
    }

    @Test
    void anErrorInTheSolveDoesNotDoubleRelease() {
        AtomicInteger releases = new AtomicInteger();
        SolveDispatch.run(
                () -> { throw new OutOfMemoryError("simulated"); },   // even Errors
                r -> { },
                releases::incrementAndGet,
                sink);
        assertEquals(1, releases.get(), "released exactly once");
    }
}
