package com.blockreality.impl.server;

import com.blockreality.api.AnalysisResult;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The background half of the analysis loop, with its failure paths closed.
 *
 * <p>The three-stage loop hangs on one atomic flag: {@code inFlight} blocks the next
 * dispatch until the current result has been applied. The original code reset the flag
 * in a {@code finally} around <em>apply</em> only; the solve itself ran bare, so any
 * throwable out of it — a {@code BufferOverflowException} from a size overflow, an NPE
 * from a concurrent close, anything — skipped the reset and wedged the dimension's
 * analysis forever, unrecoverable even by {@code /br reset} (#36).
 *
 * <p>This class owns the rule instead: <strong>whatever happens, the flag is released
 * exactly once</strong> — by the apply path when delivery succeeds, by this wrapper when
 * anything before delivery fails. No Minecraft imports, so the rule has a JUnit harness
 * (6.3).
 */
final class SolveDispatch {

    private SolveDispatch() { }

    /** How a failure is reported; production passes the mod logger. */
    interface ErrorSink {
        void error(String message, Throwable t);
    }

    /**
     * Runs one solve and hands the result to the delivery stage. Call on the background
     * thread.
     *
     * @param solve        the blocking engine conversation; documented never to throw,
     *                     but "documented" is not "guaranteed", and this wrapper is
     *                     where the guarantee actually lives
     * @param deliver      schedules apply on the main thread. Once it RETURNS normally,
     *                     releasing the in-flight flag is the apply path's duty; if it
     *                     throws, it is still ours.
     * @param releaseInFlight resets the in-flight flag; must be idempotent-safe in the
     *                     sense that it is called exactly once per dispatch
     * @param errors       where failures are recorded — a silent swallow here would
     *                     turn "analysis died" into "analysis is mysteriously slow"
     */
    static void run(Supplier<AnalysisResult> solve,
                    Consumer<AnalysisResult> deliver,
                    Runnable releaseInFlight,
                    ErrorSink errors) {
        AnalysisResult result;
        try {
            result = solve.get();
        } catch (Throwable t) {
            errors.error("background solve threw; the in-flight flag is being released", t);
            releaseInFlight.run();
            return;
        }
        try {
            deliver.accept(result);
        } catch (Throwable t) {
            errors.error("result delivery threw; the in-flight flag is being released", t);
            releaseInFlight.run();
        }
    }
}
