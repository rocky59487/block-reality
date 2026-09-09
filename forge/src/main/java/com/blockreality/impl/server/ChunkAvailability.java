package com.blockreality.impl.server;

import com.blockreality.core.world.WorldCellIndex.Chunk;
import java.util.*;
import java.util.function.Predicate;

/** FULL availability may change long before Forge emits ChunkEvent.Unload. No chunk requests. */
final class ChunkAvailability {
    private List<Chunk> order = List.of();
    private final Map<Chunk, Boolean> previous = new HashMap<>();
    private int cursor;
    boolean poll(List<Chunk> chunks, Predicate<Chunk> readable, int budget) {
        if (chunks != order) { order = chunks; previous.clear(); cursor = 0; }
        boolean changed = false;
        for (int i = 0; i < Math.min(budget, order.size()); i++) {
            Chunk chunk = order.get(cursor);
            boolean now = readable.test(chunk);
            Boolean old = previous.put(chunk, now);
            changed |= old == null ? !now : old != now;
            cursor = (cursor + 1) % order.size();
        }
        return changed;
    }
    /** Commit/display acceptance rechecks the whole observed domain, independent of poll latency. */
    static boolean allReadable(List<Chunk> chunks, Predicate<Chunk> readable) {
        for (Chunk chunk : chunks) if (!readable.test(chunk)) return false;
        return true;
    }
}
