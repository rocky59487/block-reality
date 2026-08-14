package com.blockreality.core;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.WorldRevision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevisionGateTest {

    private static AnalysisResult solved(long rev) {
        return new AnalysisResult(new WorldRevision(rev), true, false, "", 0.5, 1, List.of(), List.of());
    }

    private static AnalysisResult mechanism(long rev) {
        return new AnalysisResult(new WorldRevision(rev), true, true, "unrestrained", 0, -1, List.of(), List.of());
    }

    @Test
    void currentResultCommits() {
        RevisionGate g = new RevisionGate();
        WorldRevision r = g.bump();
        assertTrue(g.acceptForCommit(solved(r.value())));
        assertEquals(RevisionGate.Display.CURRENT, g.displayState(solved(r.value())));
    }

    @Test
    void aResultForASupersededWorldNeverCommits() {
        // The scenario this exists for: the player breaks a beam, an overload result is
        // computed, the player repairs it, and only then does the result arrive. Applying
        // it would collapse a structure that is now fine.
        RevisionGate g = new RevisionGate();
        WorldRevision old = g.bump();
        g.bump();
        assertFalse(g.acceptForCommit(solved(old.value())));
        assertEquals(1, g.rejectedCount());
    }

    @Test
    void staleResultsAreStillDrawableButLabelled() {
        RevisionGate g = new RevisionGate();
        WorldRevision old = g.bump();
        g.bump();
        assertEquals(RevisionGate.Display.STALE, g.displayState(solved(old.value())));
    }

    @Test
    void aMechanismNeitherCommitsNorDrawsAsAStressField() {
        RevisionGate g = new RevisionGate();
        WorldRevision r = g.bump();
        assertFalse(g.acceptForCommit(mechanism(r.value())));
        assertEquals(RevisionGate.Display.MECHANISM, g.displayState(mechanism(r.value())));
    }

    @Test
    void engineFailureDrawsNothing() {
        RevisionGate g = new RevisionGate();
        WorldRevision r = g.bump();
        AnalysisResult f = AnalysisResult.failed(r, "sidecar died");
        assertFalse(g.acceptForCommit(f));
        assertEquals(RevisionGate.Display.UNAVAILABLE, g.displayState(f));
    }

    @Test
    void revisionsAreMonotonic() {
        RevisionGate g = new RevisionGate();
        long prev = g.current().value();
        for (int i = 0; i < 100; i++) {
            long now = g.bump().value();
            assertTrue(now > prev);
            prev = now;
        }
    }
}
