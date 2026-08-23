package com.blockreality.impl.server;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * A snapshot gather that can span ticks.
 *
 * <p>Reading the world into a solve request happens on the main thread under a per-tick
 * budget. The old loop gathered everything or nothing: when the world grew large enough
 * that one full gather blew the budget, the tick returned <em>without changing any
 * state</em>, so the next tick re-ran the identical full gather and threw it away again —
 * a livelock that silently turned analysis off for the whole dimension while still
 * billing every tick the full gather cost (#35).
 *
 * <p>This class makes the gather resumable: a cycle captures a stable iteration order
 * once, and each tick visits items from a cursor until the budget runs out. Progress is
 * monotone — the cursor only advances — so a gather over N items completes within
 * ceil(N / itemsPerBudget) ticks no matter how large N is.
 *
 * <p><strong>Consistency across ticks</strong> is the revision's job: a cycle records the
 * revision it started at, and {@link #ensureCurrent} abandons the cycle the moment the
 * world moves on, so a finished request never mixes two states of the world. A player
 * editing continuously can delay a large gather indefinitely — but that was already true
 * of the solve itself (in-flight results go stale the same way), and it heals the moment
 * the editing pauses.
 *
 * <p>No Minecraft imports, on purpose: the budget/cursor/revision state machine is the
 * part that livelocked, so it is the part that gets a JUnit harness (6.3).
 *
 * @param <P> the position type ({@code BlockPos} in production, anything in tests)
 */
final class GatherCycle<P> {

    /** What one {@link #step} call accomplished. */
    enum Step {
        /** No cycle in progress; nothing was done. */
        IDLE,
        /** Budget exhausted with items remaining; call again next tick. */
        YIELDED,
        /** All items visited; the caller finishes and dispatches the request. */
        COMPLETE
    }

    private List<P> order;
    private int cursor;
    private long revision = -1;

    /** Items visited per clock check. Checking the clock per item would double the work. */
    private static final int CLOCK_STRIDE = 64;

    boolean inProgress() { return order != null; }

    /** The revision this cycle is gathering for; meaningless unless {@link #inProgress}. */
    long revision() { return revision; }

    /** Where the cursor stands, for tests and diagnostics. */
    int visited() { return cursor; }

    /**
     * Starts a cycle over a STABLE list — the caller copies its live set, because the
     * live set may change while the cycle is parked between ticks.
     */
    void begin(List<P> snapshotOrder, long revision) {
        this.order = snapshotOrder;
        this.cursor = 0;
        this.revision = revision;
    }

    void abandon() {
        order = null;
        cursor = 0;
        revision = -1;
    }

    /**
     * Abandons the cycle if the world has moved past it.
     *
     * @return true if the cycle was abandoned (the caller should begin a fresh one)
     */
    boolean ensureCurrent(long currentRevision) {
        if (order != null && revision != currentRevision) {
            abandon();
            return true;
        }
        return false;
    }

    /**
     * Visits items until the budget is spent or the list ends. On {@link Step#COMPLETE}
     * the cycle is over and {@link #inProgress} is false again.
     *
     * @param budgetNanos main-thread time this call may use
     * @param clock       nanotime source, injectable so tests need no real waiting
     * @param visit       the per-item work (world reads, builder appends)
     */
    Step step(long budgetNanos, LongSupplier clock, Consumer<P> visit) {
        if (order == null) return Step.IDLE;
        long start = clock.getAsLong();
        while (cursor < order.size()) {
            visit.accept(order.get(cursor));
            cursor++;
            if (cursor % CLOCK_STRIDE == 0 && cursor < order.size()
                    && clock.getAsLong() - start > budgetNanos) {
                return Step.YIELDED;
            }
        }
        abandon();
        return Step.COMPLETE;
    }
}
