package com.blockreality.core;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisDeliveryClockTest {
    private static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether";
    private static final UUID A = new UUID(1, 1), B = new UUID(2, 2);
    private static AnalysisDeliveryClock bound() {
        var clock = new AnalysisDeliveryClock();
        assertTrue(clock.accept(OVERWORLD, OVERWORLD, A, 5, 10, true, 10));
        return clock;
    }

    @Test void rejectsAnotherSourceUntilBootstrap() {
        var clock = bound();
        assertFalse(clock.accept(OVERWORLD, OVERWORLD, B, 6, 10, false, 10));
        assertEquals(A, clock.source()); assertEquals(5, clock.sequence());
        assertTrue(clock.accept(OVERWORLD, OVERWORLD, B, 7, 0, true, -1));
        assertEquals(B, clock.source()); assertEquals(0, clock.worldRevision());
        assertFalse(clock.accept(OVERWORLD, OVERWORLD, A, 8, 11, false, 11));
    }

    @Test void rejectsLateAndDuplicateUpdates() {
        var clock = bound();
        assertFalse(clock.accept(OVERWORLD, OVERWORLD, A, 5, 10, false, 10));
        assertFalse(clock.accept(OVERWORLD, OVERWORLD, A, 4, 10, true, 10));
        assertEquals(5, clock.sequence()); assertEquals(10, clock.resultRevision());
    }

    @Test void rejectsOtherDimensionsWithoutChangingState() {
        var clock = bound();
        assertFalse(clock.accept(OVERWORLD, NETHER, B, 6, 11, true, 11));
        assertEquals(OVERWORLD, clock.dimension()); assertEquals(A, clock.source());
        assertEquals(5, clock.sequence()); assertEquals(10, clock.worldRevision());
    }

    @Test void travelRequiresFreshBootstrapAndKeepsTheConnectionWatermark() {
        var clock = bound(); clock.leaveDimension();
        assertNull(clock.source()); assertEquals(5, clock.sequence());
        assertFalse(clock.accept(NETHER, NETHER, B, 6, 0, false, -1));
        assertTrue(clock.accept(NETHER, NETHER, B, 7, 0, true, -1));
        clock.leaveDimension();
        assertFalse(clock.accept(OVERWORLD, OVERWORLD, A, 5, 10, true, 10));
        assertTrue(clock.accept(OVERWORLD, OVERWORLD, A, 8, 11, true, 10));
        assertEquals(11, clock.worldRevision()); assertEquals(10, clock.resultRevision());
    }

    @Test void aNewConnectionStartsANewOrderingDomain() {
        var clock = bound(); clock.resetConnection();
        assertEquals(0, clock.sequence());
        assertFalse(clock.accept(OVERWORLD, OVERWORLD, B, 1, 0, false, -1));
        assertTrue(clock.accept(OVERWORLD, OVERWORLD, B, 1, 0, true, -1));
    }

    @Test void aNewSequenceCanRecoverARefusalAtTheSameRevision() {
        var clock = bound();
        assertTrue(clock.accept(OVERWORLD, OVERWORLD, A, 6, 11, false, -1));
        assertTrue(clock.accept(OVERWORLD, OVERWORLD, A, 7, 11, false, 11));
        assertEquals(11, clock.resultRevision());
        assertFalse(clock.accept(OVERWORLD, OVERWORLD, A, 8, 11, false, 10));
        assertFalse(clock.accept(OVERWORLD, OVERWORLD, A, 9, 10, false, -1));
        assertEquals(7, clock.sequence());
    }

    @Test void rejectsImpossibleRevisionsBeforeBinding() {
        var clock = new AnalysisDeliveryClock();
        assertFalse(clock.accept(OVERWORLD, OVERWORLD, A, 1, -1, true, -1));
        assertFalse(clock.accept(OVERWORLD, OVERWORLD, A, 1, 10, true, 11));
        assertFalse(clock.accept(OVERWORLD, OVERWORLD, A, 1, 10, true, -2));
        assertFalse(clock.accept(null, OVERWORLD, A, 1, 10, true, 10));
        assertNull(clock.source()); assertEquals(0, clock.sequence());
    }
}
