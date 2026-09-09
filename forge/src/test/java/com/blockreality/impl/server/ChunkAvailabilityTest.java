package com.blockreality.impl.server;

import com.blockreality.core.world.WorldCellIndex.Chunk;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkAvailabilityTest {
    @Test void demotionWithoutUnloadAndPromotionWithoutLoadInvalidate() {
        var chunks = List.of(new Chunk(256, 0), new Chunk(259, 0));
        var poll = new ChunkAvailability(); var missing = new HashSet<Chunk>();
        assertFalse(poll.poll(chunks, c -> !missing.contains(c), 256));
        missing.add(chunks.get(1));
        assertTrue(poll.poll(chunks, c -> !missing.contains(c), 256));
        assertFalse(poll.poll(chunks, c -> !missing.contains(c), 256));
        missing.clear(); assertTrue(poll.poll(chunks, c -> !missing.contains(c), 256));
    }
    @Test void pollHasBoundedWorkAndApplyRejectsAnUnvisitedMissingChunk() {
        var chunks = List.of(new Chunk(0, 0), new Chunk(1, 0), new Chunk(2, 0));
        var poll = new ChunkAvailability(); var calls = new AtomicInteger();
        assertFalse(poll.poll(chunks, c -> { calls.incrementAndGet(); return c.x() != 2; }, 2));
        assertEquals(2, calls.get());
        assertFalse(ChunkAvailability.allReadable(chunks, c -> c.x() != 2));
        assertTrue(poll.poll(chunks, c -> c.x() != 2, 2));
        assertTrue(ChunkAvailability.allReadable(chunks, c -> true));
    }
    @Test void removingCoverageForgetsOldAvailabilityAndEmptyDomainDoesNotReadWorld() {
        var poll = new ChunkAvailability();
        assertTrue(poll.poll(List.of(new Chunk(1, 0)), c -> false, 256));
        assertFalse(poll.poll(List.of(), c -> { fail("empty domain"); return false; }, 256));
        assertFalse(poll.poll(List.of(new Chunk(2, 0)), c -> true, 256));
    }
}
