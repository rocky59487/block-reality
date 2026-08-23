package com.blockreality.impl.server;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
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
 *
 * <p><strong>The pool follows the server's lifetime, not the JVM's.</strong> {@link
 * #shutdown()} runs when a server stops — and in a single-player session the player can
 * then open another world in the same JVM. The old {@code static final} pool stayed dead
 * forever after the first shutdown, so the second world's first structural block threw
 * {@link RejectedExecutionException} through the event bus and crashed the server (#37).
 * {@link #pool()} therefore rebuilds a dead pool, and {@link #submit} absorbs the
 * unavoidable race where a shutdown lands between the two calls.
 *
 * <p>Sizing: at least two threads even on small machines. The pool is shared by every
 * dimension (one in-flight solve per dimension, gated by {@code StructureManager}'s
 * {@code inFlight}), and a single thread would queue the Nether's solve behind a wedged
 * Overworld one for the full request timeout — serialising every dimension behind the
 * slowest, which is exactly what per-dimension clients exist to avoid (CONC-10).
 */
public final class AnalysisExecutor {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    // Core == max, with core threads allowed to time out. An unbounded queue would
    // otherwise make the maximum meaningless: ThreadPoolExecutor only grows past the core
    // size once the queue is full, so `0, N` with a LinkedBlockingQueue silently runs
    // everything on one thread. Stating the real number beats a comment that claims a
    // parallelism the pool never provides.
    private static final int THREADS =
            Math.max(2, Runtime.getRuntime().availableProcessors() / 4);

    private static ExecutorService pool;

    private AnalysisExecutor() { }

    private static ExecutorService build() {
        ThreadPoolExecutor p = new ThreadPoolExecutor(
                THREADS, THREADS,
                30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "br-analysis-" + COUNTER.incrementAndGet());
                    t.setDaemon(true);
                    // Below the game's own threads: a tick that needs CPU must win.
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                });
        // No threads at all until the first analysis, and none kept once building stops.
        p.allowCoreThreadTimeOut(true);
        return p;
    }

    /** The live pool, rebuilt if a previous server shut the last one down. */
    public static synchronized ExecutorService pool() {
        if (pool == null || pool.isShutdown()) {
            pool = build();
        }
        return pool;
    }

    /**
     * Runs {@code task} on the pool.
     *
     * @return false if the pool refused it — a shutdown raced this submit. The caller
     *         must undo whatever bookkeeping assumed the task would run (in-flight
     *         flags above all); what it must never see is the {@link
     *         RejectedExecutionException} itself, because on the event-bus path that
     *         is a server crash (#37).
     */
    public static boolean submit(Runnable task) {
        try {
            pool().execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /**
     * Stops the pool and waits briefly for in-flight work to unwind. Interrupting first
     * matters: a solve blocked on the sidecar's reply queue wakes on interrupt, fails
     * the request, and lets the thread exit — so the wait is bounded by cleanup, not by
     * the request timeout.
     */
    public static synchronized void shutdown() {
        if (pool == null) return;
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                // Daemon threads: they cannot hold the JVM open. Proceed with shutdown.
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
