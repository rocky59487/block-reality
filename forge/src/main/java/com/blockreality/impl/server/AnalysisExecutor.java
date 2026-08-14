package com.blockreality.impl.server;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one thread pool structural analysis is allowed to use.
 *
 * <p>{@code ForkJoinPool.commonPool()} is banned outright. It is shared with the JVM's own
 * parallel work, and a long-running analysis parked on it competes with things the game
 * needs. The previous project's audit found exactly this; the fix is a small, named,
 * bounded pool that shows up in a thread dump under its own name.
 *
 * <p>Threads are daemons so a stuck analysis cannot keep the JVM alive after the server
 * has gone.
 */
public final class AnalysisExecutor {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static final ExecutorService POOL = new ThreadPoolExecutor(
            0, Math.max(1, Runtime.getRuntime().availableProcessors() / 4),
            30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "br-analysis-" + COUNTER.incrementAndGet());
                t.setDaemon(true);
                // Below the game's own threads: a tick that needs CPU must win.
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    private AnalysisExecutor() { }

    public static ExecutorService pool() { return POOL; }

    public static void shutdown() {
        POOL.shutdownNow();
    }
}
