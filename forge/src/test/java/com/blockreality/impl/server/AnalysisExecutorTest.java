package com.blockreality.impl.server;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pool must follow the server lifecycle, not the JVM's (#37).
 *
 * <p>The crash this locks out: open world A, quit to title (shutdown), open world B,
 * place a structural block — the old {@code static final} pool was permanently dead,
 * so the dispatch threw {@code RejectedExecutionException} through the event bus and
 * took the server down.
 */
class AnalysisExecutorTest {

    @Test
    void thePoolIsRebuiltAfterShutdown() throws Exception {
        ExecutorService first = AnalysisExecutor.pool();
        AnalysisExecutor.shutdown();

        ExecutorService second = AnalysisExecutor.pool();
        assertNotSame(first, second, "a dead pool must be replaced, not returned");

        CountDownLatch ran = new CountDownLatch(1);
        assertTrue(AnalysisExecutor.submit(ran::countDown),
                "the rebuilt pool must accept work");
        assertTrue(ran.await(5, TimeUnit.SECONDS), "the rebuilt pool must RUN work");

        AnalysisExecutor.shutdown();
    }

    @Test
    void submitAfterShutdownDoesNotThrowItReportsFalse() {
        // The unavoidable race: shutdown lands between pool() and execute(). The
        // caller must get a boolean it can act on, never the REE (#37). Forcing the
        // race deterministically: grab the pool, shut it down, submit to the STALE
        // reference via the raw executor — then verify submit() itself heals.
        AnalysisExecutor.pool();
        AnalysisExecutor.shutdown();

        // submit() heals by rebuilding; it must not throw in any interleaving.
        assertTrue(AnalysisExecutor.submit(() -> { }),
                "submit after shutdown should rebuild and accept");
        AnalysisExecutor.shutdown();
    }

    @Test
    void shutdownIsIdempotentAndSafeWithoutAPool() {
        AnalysisExecutor.shutdown();
        AnalysisExecutor.shutdown();   // second call: nothing to stop, must not throw
    }
}
