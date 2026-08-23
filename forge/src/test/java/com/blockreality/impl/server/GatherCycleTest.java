package com.blockreality.impl.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The livelock lock (#35): a gather too large for one tick's budget must make
 * monotone progress across ticks and complete, never re-run from scratch forever.
 */
class GatherCycleTest {

    private static List<Integer> items(int n) {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(i);
        return out;
    }

    /** A clock that "spends" a fixed cost per read, so budgets exhaust deterministically. */
    private static final class TickingClock {
        private final AtomicLong now = new AtomicLong();
        private final long perRead;

        TickingClock(long perRead) { this.perRead = perRead; }

        long read() { return now.addAndGet(perRead); }
    }

    @Test
    void aCycleWithinBudgetCompletesInOneStep() {
        GatherCycle<Integer> cycle = new GatherCycle<>();
        List<Integer> visited = new ArrayList<>();
        cycle.begin(items(10), 1);
        GatherCycle.Step step = cycle.step(Long.MAX_VALUE, () -> 0L, visited::add);
        assertEquals(GatherCycle.Step.COMPLETE, step);
        assertEquals(items(10), visited, "every item visited, in order");
        assertFalse(cycle.inProgress());
    }

    @Test
    void anOverBudgetCycleYieldsAndResumesWhereItStopped() {
        // 1000 items, and the clock burns the whole budget after every stride of 64:
        // each step visits one stride and yields. The old code re-ran the FULL gather
        // each tick and threw it away — the visit count grew by N per tick with zero
        // progress. Here progress must be monotone and completion guaranteed.
        GatherCycle<Integer> cycle = new GatherCycle<>();
        List<Integer> visited = new ArrayList<>();
        cycle.begin(items(1000), 7);

        int steps = 0;
        GatherCycle.Step step;
        do {
            TickingClock clock = new TickingClock(10);
            int before = visited.size();
            step = cycle.step(5, clock::read, visited::add);   // budget < one clock read
            steps++;
            assertTrue(visited.size() > before, "every step must make progress");
            assertTrue(steps < 1000, "must terminate long before one step per item");
        } while (step == GatherCycle.Step.YIELDED);

        assertEquals(GatherCycle.Step.COMPLETE, step);
        assertEquals(items(1000), visited, "yielding must not skip or repeat items");
        assertTrue(steps > 1, "the point of the test: it could NOT finish in one budget");
    }

    @Test
    void aRevisionChangeAbandonsTheParkedCycle() {
        // The cursor must never stitch two world states into one request: a cycle
        // begun at revision 3 dies the moment the world says 4.
        GatherCycle<Integer> cycle = new GatherCycle<>();
        cycle.begin(items(100), 3);
        cycle.step(5, new TickingClock(10)::read, i -> { });
        assertTrue(cycle.inProgress());

        assertFalse(cycle.ensureCurrent(3), "same revision: cycle survives");
        assertTrue(cycle.inProgress());

        assertTrue(cycle.ensureCurrent(4), "new revision: cycle abandoned");
        assertFalse(cycle.inProgress());
        assertEquals(GatherCycle.Step.IDLE, cycle.step(Long.MAX_VALUE, () -> 0L, i -> { }));
    }

    @Test
    void stepWithNoCycleIsIdle() {
        GatherCycle<Integer> cycle = new GatherCycle<>();
        assertEquals(GatherCycle.Step.IDLE, cycle.step(Long.MAX_VALUE, () -> 0L, i -> { }));
        assertFalse(cycle.ensureCurrent(1), "nothing to abandon");
    }

    @Test
    void zeroBudgetStillGuaranteesMinimumProgress() {
        // Even a pathological budget must not stall the cursor: at least one clock
        // stride of items is visited per step, so N items finish in <= N/64 + 1 ticks.
        GatherCycle<Integer> cycle = new GatherCycle<>();
        List<Integer> visited = new ArrayList<>();
        cycle.begin(items(130), 1);
        int steps = 0;
        while (cycle.step(0, new TickingClock(1)::read, visited::add) == GatherCycle.Step.YIELDED) {
            steps++;
            assertTrue(steps <= 130, "livelock: zero budget stopped the cursor");
        }
        assertEquals(130, visited.size());
        assertTrue(steps <= 3, "expected about ceil(130/64) yields, got " + steps);
    }
}
