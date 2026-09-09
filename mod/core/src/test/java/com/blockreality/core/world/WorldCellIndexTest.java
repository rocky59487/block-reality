package com.blockreality.core.world;

import com.blockreality.api.geom.BlockKey;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldCellIndexTest {
    @Test void oppositeObservationOrdersAndReloadHaveIdenticalBytes() {
        var west = List.of(new BlockKey(-17, -64, -1), new BlockKey(-18, 300, -1));
        var east = List.of(new BlockKey(16, 200, 0), new BlockKey(17, 200, 0));
        var a = new WorldCellIndex(); a.replaceChunk(-2, -1, west); a.replaceChunk(1, 0, east);
        var b = new WorldCellIndex(); b.replaceChunk(1, 0, east); b.replaceChunk(-2, -1, west);
        assertArrayEquals(a.encode(), b.encode());
        var restored = WorldCellIndex.decode(a.encode());
        assertEquals(a.cells(), restored.cells()); assertArrayEquals(a.encode(), restored.encode());
        long revision = restored.generation();
        assertFalse(restored.replaceChunk(-2, -1, west)); assertEquals(revision, restored.generation());
        assertThrows(UnsupportedOperationException.class, () -> restored.cells().clear());
    }

    @Test void emptyReconciliationOnlyRemovesItsOwnChunkAndSnapshotsStayImmutable() {
        var index = new WorldCellIndex(); var west = new BlockKey(-1, 0, -1); var east = new BlockKey(0, 0, 0);
        index.add(west); index.add(east); var before = index.cells();
        assertTrue(index.replaceChunk(-1, -1, List.of()));
        assertEquals(List.of(east), index.cells()); assertEquals(2, before.size());
        assertFalse(index.replaceChunk(-1, -1, List.of()));
        assertTrue(index.remove(east)); assertEquals(0, index.size());
    }

    @Test void invalidChunkObservationsAreAtomic() {
        var index = new WorldCellIndex(); var p = new BlockKey(0, 0, 0); index.add(p); byte[] before = index.encode();
        for (var invalid : List.of(List.of(p, p), List.of(new BlockKey(16, 0, 0)),
                List.of(new BlockKey(0, 2048, 0)))) {
            assertThrows(IllegalArgumentException.class, () -> index.replaceChunk(0, 0, invalid));
            assertArrayEquals(before, index.encode());
        }
    }

    @Test void coverageIncludesOnlyActualGroundBordersWithoutInventingDiagonalContacts() {
        var index = new WorldCellIndex(); index.add(new BlockKey(-1, 0, -1));
        assertTrue(index.observesChunk(-1, -1)); assertTrue(index.observesChunk(0, -1));
        assertTrue(index.observesChunk(-1, 0)); assertFalse(index.observesChunk(0, 0));
        assertFalse(index.observesChunk(-2, -1));
        index.replaceChunk(-1, -1, List.of(new BlockKey(-8, 0, -8)));
        assertFalse(index.observesChunk(0, -1)); assertTrue(index.observesChunk(-1, -1));
    }

    @Test void capacityFailureDoesNotEvictAndSurvivesRestart() {
        var index = new WorldCellIndex();
        for (int x = 0; x < WorldCellIndex.MAX_CELLS; x++) assertTrue(index.add(new BlockKey(x, 0, 0)));
        assertEquals(WorldCellIndex.MAX_BYTES, index.encode().length);
        var cells = index.cells();
        assertFalse(index.replaceChunk(-1, 0, List.of(new BlockKey(-1, 0, 0))));
        assertTrue(index.refused()); assertEquals(cells, index.cells());
        var restored = WorldCellIndex.decode(index.encode());
        assertTrue(restored.refused()); assertFalse(restored.remove(new BlockKey(0, 0, 0)));
        assertFalse(restored.add(new BlockKey(-1, 0, 0))); assertEquals(cells, restored.cells());
        // Replacement within the bound remains permitted BEFORE the sticky fault occurs.
        var bounded = new WorldCellIndex(); for (BlockKey p : cells) bounded.add(p);
        assertTrue(bounded.replaceChunk(0, 0, List.of(new BlockKey(0, 1, 0))));
        assertFalse(bounded.refused()); assertEquals(WorldCellIndex.MAX_CELLS - 15, bounded.size());
    }

    @Test void damagedChecksumsAndEveryTruncationFailClosed() {
        var index = new WorldCellIndex(); index.add(new BlockKey(1, 2, 3)); byte[] encoded = index.encode();
        for (int i = 0; i < encoded.length; i++) {
            byte[] cut = Arrays.copyOf(encoded, i);
            assertThrows(IllegalArgumentException.class, () -> WorldCellIndex.decode(cut));
            byte[] changed = encoded.clone(); changed[i] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> WorldCellIndex.decode(changed));
        }
        assertThrows(IllegalArgumentException.class, () -> WorldCellIndex.decode(Arrays.copyOf(encoded, encoded.length + 1)));
    }

    @Test void validDigestCannotAuthorizeUnknownSchemaDuplicatesOrderingOrOutOfWorldCells() throws Exception {
        var index = new WorldCellIndex(); index.add(new BlockKey(1, 2, 3)); index.add(new BlockKey(4, 5, 6));
        for (int[] change : new int[][]{{4, 2}, {12, 2}, {16, 30000000}, {20, -2049}, {28, 1}, {28, 0}}) {
            byte[] bytes = index.encode(); ByteBuffer.wrap(bytes).putInt(change[0], change[1]);
            if (change[0] == 28 && change[1] == 1) System.arraycopy(bytes, 16, bytes, 28, 12);
            resign(bytes);
            assertThrows(IllegalArgumentException.class, () -> WorldCellIndex.decode(bytes));
        }
    }
    private static void resign(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, bytes.length - 32));
        System.arraycopy(hash, 0, bytes, bytes.length - 32, 32);
    }
}
