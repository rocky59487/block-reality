package com.blockreality.impl.server;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.WorldRevision;
import com.blockreality.core.protocol.SolveRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads travel with their blocks, or not at all (#38).
 *
 * <p>The blackout this locks out: a test load on a block whose chunk is unloaded was
 * still put on the wire, the engine's load-on-no-element check rejected the WHOLE
 * request, and the dimension's analysis went dark with no message. The guard must be
 * "the block is in THIS request", never "the block is tracked".
 */
class SnapshotLoadsTest {

    private static final BlockKey IN = new BlockKey(0, 64, 0);
    private static final BlockKey UNLOADED = new BlockKey(1000, 64, 0);
    private static final BlockKey GONE = new BlockKey(5, 64, 5);

    private static final double[] F = { 0, -20000, 0 };

    @Test
    void aLoadOnAnIncludedBlockTravels() {
        SolveRequest.Builder b = SolveRequest.builder(new WorldRevision(1));
        b.block(IN, "steel", "steel_rect_200x400", true);

        List<BlockKey> stale = SnapshotLoads.append(b,
                Map.of(IN, F), Set.of(IN), Set.of(IN));

        SolveRequest req = b.build();
        assertEquals(1, req.loads().size());
        assertEquals(IN, req.loads().get(0).at());
        assertEquals(-20000, req.loads().get(0).fy());
        assertTrue(stale.isEmpty());
    }

    @Test
    void aLoadInAnUnloadedChunkWaitsWithItsBlock() {
        // Tracked but NOT gathered this round: the block is skipped-not-forgotten, so
        // the load must be too — neither on the wire (whole-request rejection) nor
        // removed (it must come back with the chunk). This is the #38 case.
        SolveRequest.Builder b = SolveRequest.builder(new WorldRevision(2));
        b.block(IN, "steel", "steel_rect_200x400", true);

        List<BlockKey> stale = SnapshotLoads.append(b,
                Map.of(IN, F, UNLOADED, F),
                Set.of(IN),                    // gathered this round
                Set.of(IN, UNLOADED));         // tracked

        SolveRequest req = b.build();
        assertEquals(1, req.loads().size(), "the unloaded block's load must NOT travel");
        assertEquals(IN, req.loads().get(0).at());
        assertTrue(stale.isEmpty(), "the waiting load must NOT be removed either");
    }

    @Test
    void aLoadWhoseBlockIsGoneIsReportedStale() {
        // Not tracked at all: the block was removed, and the load hangs ON the block.
        SolveRequest.Builder b = SolveRequest.builder(new WorldRevision(3));
        b.block(IN, "steel", "steel_rect_200x400", true);

        List<BlockKey> stale = SnapshotLoads.append(b,
                Map.of(IN, F, GONE, F), Set.of(IN), Set.of(IN));

        assertEquals(1, b.build().loads().size());
        assertEquals(List.of(GONE), stale);
    }

    @Test
    void momentsAreNotInventedForPointLoads() {
        SolveRequest.Builder b = SolveRequest.builder(new WorldRevision(4));
        b.block(IN, "steel", "steel_rect_200x400", true);
        SnapshotLoads.append(b, Map.of(IN, new double[] { 1, 2, 3 }), Set.of(IN), Set.of(IN));
        SolveRequest.PointLoad l = b.build().loads().get(0);
        assertEquals(1, l.fx());
        assertEquals(2, l.fy());
        assertEquals(3, l.fz());
        assertEquals(0, l.mx());
        assertEquals(0, l.my());
        assertEquals(0, l.mz());
    }
}
