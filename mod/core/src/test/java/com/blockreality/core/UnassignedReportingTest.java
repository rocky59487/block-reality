package com.blockreality.core;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.BucklingState;
import com.blockreality.api.UnassignedBlocks;
import com.blockreality.api.UnassignedReason;
import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N17 and N18 on the Java side of the wire: what the reason codes mean and what the game
 * is allowed to do with them.
 */
class UnassignedReportingTest {

    private static BlockKey k(int x, int y, int z) { return new BlockKey(x, y, z); }

    private static AnalysisResult with(List<UnassignedBlocks> groups) {
        return new AnalysisResult(new WorldRevision(1), true, false, "", 0.2, -1, "",
                1, 0, 1e-15, 0, BucklingState.NO_POSITIVE_EIGENVALUE,
                List.of(), List.of(), groups);
    }

    // ------------------------------------------------------------------ codes
    @Test
    @DisplayName("every code this build knows survives a trip through the wire spelling")
    void everyCodeRoundTrips() {
        for (UnassignedReason r : UnassignedReason.values()) {
            if (r == UnassignedReason.UNKNOWN) continue;
            assertSame(r, UnassignedReason.fromWire(r.wire()), r.name());
        }
    }

    @Test
    @DisplayName("N17-c: a code from a newer engine is UNKNOWN, not an exception")
    void anUnrecognisedCodeDegrades() {
        assertSame(UnassignedReason.UNKNOWN, UnassignedReason.fromWire("BULK_UNSUPPORTED"));
        assertSame(UnassignedReason.UNKNOWN, UnassignedReason.fromWire(""));
        assertSame(UnassignedReason.UNKNOWN, UnassignedReason.fromWire(null));
    }

    @Test
    @DisplayName("an unknown group still carries its blocks and can still be named")
    void anUnknownGroupKeepsItsPayload() {
        UnassignedBlocks g = UnassignedBlocks.of("BULK_UNSUPPORTED", List.of(k(0, 64, 0)));
        assertSame(UnassignedReason.UNKNOWN, g.reason());
        assertEquals(1, g.blocks().size());
        assertEquals("BULK_UNSUPPORTED", g.label(),
                "the raw token is kept so a log line can name what this build did not know");
        assertEquals("(no reason given)", UnassignedBlocks.of(null, List.of()).label());
    }

    @Test
    @DisplayName("N17-e: fully supported and a plate strip do not share a translation")
    void reasonsHaveTheirOwnSentences() {
        assertNotEquals(UnassignedReason.FULLY_SUPPORTED.translationKey(),
                UnassignedReason.PLATE_STRIP.translationKey());
        assertEquals("br.unassigned.fully_supported",
                UnassignedReason.FULLY_SUPPORTED.translationKey());
        assertEquals("br.unassigned.plate_no_facet",
                UnassignedReason.PLATE_NO_FACET.translationKey());
    }

    // ---------------------------------------------------- what a load may sit on
    @Test
    @DisplayName("the extraction failures are the ones a test load can never sit on")
    void extractionFailuresFormNoElement() {
        for (UnassignedReason r : List.of(UnassignedReason.RUN_TOO_SHORT,
                UnassignedReason.PLATE_LONE, UnassignedReason.PLATE_STRIP,
                UnassignedReason.PLATE_SOLID, UnassignedReason.PLATE_NO_FACET)) {
            assertTrue(r.formsNoElement(), r.name());
        }
    }

    @Test
    @DisplayName("a mechanism block and a fully supported block ARE elements; loads stay")
    void inModelReasonsDoNotFormNoElement() {
        // Both are nodes of a real element, so the engine accepts a load on them. Calling
        // them "forms no element" would delete a player's test load the moment their
        // structure lost its last support.
        assertFalse(UnassignedReason.MECHANISM.formsNoElement());
        assertFalse(UnassignedReason.FULLY_SUPPORTED.formsNoElement());
    }

    @Test
    @DisplayName("an unknown code does NOT authorise deleting the load")
    void unknownIsSafeSide() {
        // A load that keeps being refused is visible and recoverable. A load the game
        // deleted citing a reason it could not read is neither.
        assertFalse(UnassignedReason.UNKNOWN.formsNoElement());
    }

    @Test
    @DisplayName("the result separates every unassigned block from the load-refusing subset")
    void theTwoViewsDiffer() {
        AnalysisResult r = with(List.of(
                UnassignedBlocks.of("MECHANISM", List.of(k(0, 64, 0), k(1, 64, 0))),
                UnassignedBlocks.of("RUN_TOO_SHORT", List.of(k(9, 64, 0))),
                UnassignedBlocks.of("FULLY_SUPPORTED", List.of(k(5, 64, 0)))));
        assertEquals(4, r.unassignedBlocks().size());
        assertEquals(List.of(k(9, 64, 0)), r.blocksFormingNoElement(),
                "only the block that belongs to no element at all");
    }

    @Test
    @DisplayName("nothing unassigned means both views are empty, not merely small")
    void emptyStaysEmpty() {
        AnalysisResult r = with(List.of());
        assertTrue(r.unassignedBlocks().isEmpty());
        assertTrue(r.blocksFormingNoElement().isEmpty());
    }

    // --------------------------------------------------------------- buckling
    @Test
    @DisplayName("N18-a: each buckling state has its own wire token and its own sentence")
    void bucklingStatesAreDistinct() {
        for (BucklingState s : BucklingState.values()) {
            if (s == BucklingState.UNKNOWN) continue;
            assertSame(s, BucklingState.fromWire(s.wire()), s.name());
        }
        assertSame(BucklingState.UNKNOWN, BucklingState.fromWire("something-else"));
        assertSame(BucklingState.UNKNOWN, BucklingState.fromWire(null));
        assertEquals(BucklingState.values().length,
                java.util.Arrays.stream(BucklingState.values())
                        .map(BucklingState::translationKey).distinct().count());
    }

    @Test
    @DisplayName("only COMPUTED means there is a number to show")
    void onlyComputedCarriesAFactor() {
        assertTrue(BucklingState.COMPUTED.hasFactor());
        for (BucklingState s : BucklingState.values()) {
            if (s != BucklingState.COMPUTED) assertFalse(s.hasFactor(), s.name());
        }
    }

    @Test
    @DisplayName("the engine never says disabled-by-scale; only the host can know that")
    void scaleIsHostOnly() {
        // If this token ever appeared on the engine wire it would mean the engine had
        // opinions about the host's size policy, which it cannot have: from its side a
        // request that did not ask is just a request that did not ask.
        assertEquals("disabled-by-scale", BucklingState.DISABLED_BY_SCALE.wire());
        assertNotEquals(BucklingState.DISABLED_BY_SCALE.wire(),
                BucklingState.DISABLED_BY_REQUEST.wire());
    }

    @Test
    @DisplayName("a reply that said nothing about buckling is UNKNOWN, not COMPUTED")
    void absentIsNotReassuring() {
        AnalysisResult r = new AnalysisResult(new WorldRevision(1), true, false, "", 0, -1, "",
                0, 0, 0, 0, null, List.of(), List.of(), List.of());
        assertSame(BucklingState.UNKNOWN, r.bucklingState());
        assertFalse(r.bucklingState().hasFactor());
    }
}
