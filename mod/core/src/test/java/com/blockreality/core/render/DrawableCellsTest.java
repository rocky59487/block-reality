package com.blockreality.core.render;

import com.blockreality.api.EndForces;
import com.blockreality.api.GoverningFibre;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.geom.BlockKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The overlay stops painting a block the frame it is mined, without waiting for the
 * server to agree. Stress is the server's to say; presence is the client's.
 */
class DrawableCellsTest {

    private static BlockKey k(int x, int y, int z) { return new BlockKey(x, y, z); }

    private static MemberSnapshot member(int id, List<BlockKey> blocks) {
        return new MemberSnapshot(id, "steel", "steel_rect_200x400", 4000, 0.4,
                GoverningFibre.CRUSH, -1, EndForces.ZERO, EndForces.ZERO,
                blocks, List.of(), Optional.empty());
    }

    private static ShellSnapshot shell(int id, List<BlockKey> blocks) {
        return new ShellSnapshot(id, "concrete", "concrete_slab_200", 200, 0.3, 0.3,
                true, false, blocks, Optional.empty());
    }

    @Test
    @DisplayName("with the world unchanged, every cell of the answer is drawable")
    void everythingPresentIsDrawable() {
        var m = member(1, List.of(k(0, 0, 0), k(1, 0, 0), k(2, 0, 0)));
        Set<Long> live = DrawableCells.of(List.of(m), List.of());
        assertEquals(3, live.size());
        assertTrue(live.contains(DrawableCells.key(1, 0, 0)));
    }

    @Test
    @DisplayName("a mined block leaves the drawable set the same frame, with no new packet")
    void aMinedBlockIsDroppedImmediately() {
        var m = member(1, List.of(k(0, 0, 0), k(1, 0, 0), k(2, 0, 0)));
        // the player just broke the middle one
        Set<Long> live = DrawableCells.of(List.of(m), List.of(), b -> !b.equals(k(1, 0, 0)));
        assertEquals(2, live.size());
        assertFalse(live.contains(DrawableCells.key(1, 0, 0)),
                "the overlay must not paint a stress onto empty air");
        assertTrue(live.contains(DrawableCells.key(0, 0, 0)));
        assertTrue(live.contains(DrawableCells.key(2, 0, 0)));
    }

    @Test
    @DisplayName("occupancy follows, so a face the mined block was hiding becomes visible")
    void occupancyDropsWithTheBlock() {
        // two members meeting: 1 ends at x=2, 2 begins at x=3
        var a = member(1, List.of(k(0, 0, 0), k(1, 0, 0), k(2, 0, 0)));
        var b = member(2, List.of(k(3, 0, 0), k(4, 0, 0)));
        Set<Long> whole = DrawableCells.of(List.of(a, b), List.of());
        assertTrue(whole.contains(DrawableCells.key(3, 0, 0)),
                "while it is there, member 1's end face is interior and skipped");

        Set<Long> mined = DrawableCells.of(List.of(a, b), List.of(), p -> !p.equals(k(3, 0, 0)));
        assertFalse(mined.contains(DrawableCells.key(3, 0, 0)),
                "once it is gone the face must be drawn, in the same frame");
    }

    @Test
    @DisplayName("plate blocks are in the set too, or a buried beam fights the floor above it")
    void shellsOccupyCells() {
        var s = shell(7, List.of(k(0, 5, 0), k(1, 5, 0), k(1, 5, 1), k(0, 5, 1)));
        Set<Long> live = DrawableCells.of(List.of(), List.of(s));
        assertEquals(4, live.size());
        assertTrue(live.contains(DrawableCells.key(1, 5, 1)));
    }

    @Test
    @DisplayName("mining a slab block drops it as well; the rule is not member-only")
    void shellCellsAreFilteredToo() {
        var s = shell(7, List.of(k(0, 5, 0), k(1, 5, 0), k(1, 5, 1), k(0, 5, 1)));
        Set<Long> live = DrawableCells.of(List.of(), List.of(s), b -> !b.equals(k(1, 5, 1)));
        assertEquals(3, live.size());
        assertFalse(live.contains(DrawableCells.key(1, 5, 1)));
    }

    @Test
    @DisplayName("a block a member shares with a facet is one cell, not two")
    void overlapIsDeduplicated() {
        var m = member(1, List.of(k(0, 5, 0)));
        var s = shell(7, List.of(k(0, 5, 0), k(1, 5, 0), k(1, 5, 1), k(0, 5, 1)));
        assertEquals(4, DrawableCells.of(List.of(m), List.of(s)).size());
    }

    @Test
    @DisplayName("an emptied world draws nothing at all, rather than the last picture")
    void everythingGoneDrawsNothing() {
        var m = member(1, List.of(k(0, 0, 0), k(1, 0, 0)));
        var s = shell(7, List.of(k(0, 5, 0), k(1, 5, 0), k(1, 5, 1), k(0, 5, 1)));
        assertTrue(DrawableCells.of(List.of(m), List.of(s), b -> false).isEmpty());
    }

    @Test
    @DisplayName("the key packs the full build height and both horizontal extents")
    void keyCoversTheWorld() {
        assertEquals(DrawableCells.key(0, 0, 0), DrawableCells.key(k(0, 0, 0)));
        // distinct coordinates must not collide anywhere a player can build
        Set<Long> seen = new java.util.HashSet<>();
        for (int[] p : new int[][] {{0, -64, 0}, {0, 319, 0}, {-30_000_000, 0, 0},
                                    {30_000_000, 0, 0}, {0, 0, -30_000_000},
                                    {0, 0, 30_000_000}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}}) {
            assertTrue(seen.add(DrawableCells.key(p[0], p[1], p[2])),
                    "collision at " + p[0] + "," + p[1] + "," + p[2]);
        }
    }
}
