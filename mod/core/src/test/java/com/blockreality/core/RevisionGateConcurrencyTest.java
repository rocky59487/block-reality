package com.blockreality.core;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.WorldRevision;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate under contention (TEST-6).
 *
 * <p>{@code bump()} is a read-modify-write. Its callers arrive through Forge event
 * handlers with no thread assertion on the path, and a lost update would stamp a
 * request with a revision that another edit also claimed — {@code acceptForCommit}
 * would then bless a result describing a superseded world, which is the commit-track
 * non-determinism invariant 6 forbids. The gate is synchronized; this test is the
 * regression net for anyone who removes that.
 */
class RevisionGateConcurrencyTest {

    @Test
    void concurrentBumpsAreNeverLost() throws Exception {
        final int threads = 8;
        final int bumpsPerThread = 5_000;
        RevisionGate gate = new RevisionGate();
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Thread> pool = new ArrayList<>();
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    long previous = 0;
                    for (int i = 0; i < bumpsPerThread; i++) {
                        long got = gate.bump().value();
                        // Monotone per thread: each bump must observe a strictly
                        // larger revision than this thread's previous one.
                        if (got <= previous) {
                            throw new AssertionError("bump went backwards: " + got);
                        }
                        previous = got;
                    }
                } catch (InterruptedException | BrokenBarrierException e) {
                    failures.add(e);
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
            th.start();
            pool.add(th);
        }
        for (Thread th : pool) th.join(30_000);

        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals((long) threads * bumpsPerThread, gate.current().value(),
                "every bump must be counted exactly once — a lost update here lets a "
                        + "stale result pass the commit gate");
    }

    @Test
    void acceptForCommitRacingBumpNeverAcceptsASupersededResult() throws Exception {
        // One thread edits the world; another lands results for old revisions. No
        // interleaving may accept a result whose revision is no longer current.
        RevisionGate gate = new RevisionGate();
        final int rounds = 2_000;
        Thread editor = new Thread(() -> {
            for (int i = 0; i < rounds; i++) gate.bump();
        });
        List<String> violations = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread applier = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                AnalysisResult r = usable(gate.current());
                boolean accepted = gate.acceptForCommit(r);
                if (accepted && gate.lastAccepted() != null
                        && gate.lastAccepted().revision().value() > r.revision().value()) {
                    violations.add("older result overwrote a newer accepted one");
                }
            }
        });
        editor.start();
        applier.start();
        editor.join(30_000);
        applier.join(30_000);
        assertTrue(violations.isEmpty(), violations.toString());
        // And the accepted result, if any, is for a revision that once was current.
        if (gate.lastAccepted() != null) {
            assertTrue(gate.lastAccepted().revision().value() <= gate.current().value());
        }
        assertFalse(editor.isAlive());
        assertFalse(applier.isAlive());
    }

    private static AnalysisResult usable(WorldRevision rev) {
        return new AnalysisResult(rev, true, false, "", 0.5, 1, "member", 1, 0, 0, 0,
                List.of(new com.blockreality.api.MemberSnapshot(1, "steel", "s", 1000, 0.5,
                        com.blockreality.api.GoverningFibre.NONE, -1,
                        com.blockreality.api.EndForces.ZERO, com.blockreality.api.EndForces.ZERO,
                        List.of(), List.of(), java.util.Optional.empty())),
                List.of(), List.of());
    }
}
