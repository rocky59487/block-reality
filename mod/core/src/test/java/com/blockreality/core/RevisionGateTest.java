package com.blockreality.core;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.BucklingState;
import com.blockreality.api.EndForces;
import com.blockreality.api.GoverningFibre;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.WorldRevision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevisionGateTest {

    /** A solved result must actually carry an element, or it is not usable by definition. */
    private static AnalysisResult solved(long rev) {
        return new AnalysisResult(new WorldRevision(rev), true, false, "", 0.5, 1, "member",
                1, 0, 0, 0, BucklingState.DISABLED_BY_REQUEST,
                List.of(member()), List.of(), List.of());
    }

    /** Every structure in the world is a mechanism: nothing at all to draw. */
    private static AnalysisResult mechanism(long rev) {
        return new AnalysisResult(new WorldRevision(rev), true, true, "unrestrained", 0, -1, "",
                1, 1, 0, 0, BucklingState.DISABLED_BY_REQUEST,
                List.of(), List.of(), List.of());
    }

    /** One structure is a mechanism and one is not — the ordinary case in a built world. */
    private static AnalysisResult partlyMechanism(long rev) {
        return new AnalysisResult(new WorldRevision(rev), true, true, "unrestrained", 0.5, 1,
                "member", 2, 1, 0, 0, BucklingState.DISABLED_BY_REQUEST,
                List.of(member()), List.of(), List.of());
    }

    private static MemberSnapshot member() {
        return new MemberSnapshot(1, "steel", "steel_rect_200x400", 4000, 0.5,
                GoverningFibre.NONE, -1, EndForces.ZERO, EndForces.ZERO,
                List.of(), List.of(), Optional.empty());
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

    @Test
    void oneMechanismDoesNotBlankTheWholeWorld() {
        // The bug this pins: with several buildings in a dimension, one unsupported
        // structure used to take every other building's result down with it.
        RevisionGate g = new RevisionGate();
        WorldRevision r = g.bump();
        AnalysisResult partial = partlyMechanism(r.value());
        assertTrue(partial.isUsable(), "the sound building's numbers are still numbers");
        assertFalse(partial.allSingular());
        assertEquals(RevisionGate.Display.CURRENT, g.displayState(partial));
        assertTrue(g.acceptForCommit(partial));
    }

    @Test
    void aWorldOfNothingButMechanismsHasNothingToDraw() {
        RevisionGate g = new RevisionGate();
        WorldRevision r = g.bump();
        assertEquals(RevisionGate.Display.MECHANISM, g.displayState(mechanism(r.value())));
    }
}
