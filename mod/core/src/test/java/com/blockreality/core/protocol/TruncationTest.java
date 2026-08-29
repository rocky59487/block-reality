package com.blockreality.core.protocol;

import com.blockreality.api.geom.BlockKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N14-b and N14-c, the two lines that decide what the player is told about an answer
 * whose input was incomplete. Frozen in {@code docs/GATES.md} before this existed.
 */
class TruncationTest {

    private static BlockKey k(int x, int y, int z) { return new BlockKey(x, y, z); }

    /** An element for {@link Truncation#touching}: an id and the blocks it came from. */
    private record Elem(int id, List<BlockKey> blocks) { }

    private static Set<Integer> touching(Set<BlockKey> face, List<Elem> elems) {
        return Truncation.touching(face, elems, Elem::blocks, Elem::id);
    }

    @Test
    @DisplayName("N14-b: a skipped block touching the request is on the truncation face")
    void faceIsTheIntersection() {
        Set<BlockKey> included = Set.of(k(0, 1, 0), k(0, 2, 0), k(0, 3, 0));
        // the base of the column, in a chunk nobody could read
        List<BlockKey> skipped = List.of(k(0, 0, 0));
        assertEquals(Set.of(k(0, 0, 0)), Truncation.face(skipped, included));
    }

    @Test
    @DisplayName("a skipped block nowhere near the request is a different structure, not a truncation")
    void distantSkipIsNotTruncation() {
        Set<BlockKey> included = Set.of(k(0, 1, 0), k(0, 2, 0));
        List<BlockKey> skipped = List.of(k(900, 70, -400));
        assertEquals(Set.of(), Truncation.face(skipped, included));
    }

    @Test
    @DisplayName("adjacency is the six faces: a diagonal neighbour carries no load path")
    void diagonalDoesNotCount() {
        Set<BlockKey> included = Set.of(k(0, 0, 0));
        assertEquals(Set.of(), Truncation.face(List.of(k(1, 1, 0)), included));
        assertEquals(Set.of(), Truncation.face(List.of(k(1, 1, 1)), included));
        assertEquals(Set.of(k(1, 0, 0)), Truncation.face(List.of(k(1, 0, 0)), included));
    }

    @Test
    @DisplayName("all six faces are checked, none forgotten")
    void allSixNeighbours() {
        Set<BlockKey> included = Set.of(k(0, 0, 0));
        for (BlockKey n : List.of(k(1, 0, 0), k(-1, 0, 0), k(0, 1, 0),
                                  k(0, -1, 0), k(0, 0, 1), k(0, 0, -1))) {
            assertEquals(Set.of(n), Truncation.face(List.of(n), included), "neighbour " + n);
        }
    }

    @Test
    @DisplayName("N14-e: nothing skipped means nothing to say")
    void emptySkipIsEmptyFace() {
        Set<BlockKey> included = Set.of(k(0, 0, 0), k(0, 1, 0));
        assertEquals(Set.of(), Truncation.face(List.of(), included));
        assertEquals(Set.of(), Truncation.face(null, included));
    }

    @Test
    @DisplayName("an empty request cannot be truncated by anything")
    void emptyRequestIsNeverTruncated() {
        assertEquals(Set.of(), Truncation.face(List.of(k(0, 0, 0)), Set.of()));
    }

    @Test
    @DisplayName("the face keeps the order it was given, so reported examples are stable")
    void faceOrderIsStable() {
        Set<BlockKey> included = Set.of(k(0, 0, 0), k(5, 0, 0));
        List<BlockKey> skipped = List.of(k(-1, 0, 0), k(6, 0, 0), k(0, -1, 0));
        assertEquals(List.of(k(-1, 0, 0), k(6, 0, 0), k(0, -1, 0)),
                List.copyOf(Truncation.face(skipped, included)));
        assertEquals(List.of(k(-1, 0, 0), k(6, 0, 0)),
                Truncation.examples(Truncation.face(skipped, included), 2));
    }

    @Test
    @DisplayName("N14-c: the element against the missing base is withheld, the far one is not")
    void onlyTheElementAtTheFaceIsWithheld() {
        // a column standing on a base that never arrived, and a beam three metres away
        Set<BlockKey> face = Set.of(k(0, 0, 0));
        List<Elem> elems = List.of(
                new Elem(1, List.of(k(0, 1, 0), k(0, 2, 0), k(0, 3, 0))),
                new Elem(2, List.of(k(4, 3, 0), k(5, 3, 0), k(6, 3, 0))));
        assertEquals(Set.of(1), touching(face, elems));
    }

    @Test
    @DisplayName("an element is withheld when ANY of its blocks touches the face, not only the first")
    void anyBlockTouchingIsEnough() {
        Set<BlockKey> face = Set.of(k(9, 0, 0));
        List<Elem> elems = List.of(
                new Elem(7, List.of(k(0, 0, 0), k(4, 0, 0), k(8, 0, 0))));
        assertEquals(Set.of(7), touching(face, elems));
    }

    @Test
    @DisplayName("touching is also six-faced: an element only cornering the face keeps its verdict")
    void touchingIgnoresDiagonals() {
        Set<BlockKey> face = Set.of(k(0, 0, 0));
        List<Elem> elems = List.of(new Elem(3, List.of(k(1, 1, 0), k(2, 2, 0))));
        assertEquals(Set.of(), touching(face, elems));
    }

    @Test
    @DisplayName("no face means no element is withheld, whatever was solved")
    void noFaceWithholdsNothing() {
        List<Elem> elems = List.of(new Elem(1, List.of(k(0, 0, 0))),
                                   new Elem(2, List.of(k(1, 0, 0))));
        assertEquals(Set.of(), touching(Set.of(), elems));
        assertEquals(Set.of(), touching(null, elems));
    }

    @Test
    @DisplayName("ids are reported, not indices — the packet identifies elements by id")
    void reportsIdsNotIndices() {
        Set<BlockKey> face = Set.of(k(0, 0, 0));
        List<Elem> elems = List.of(
                new Elem(101, List.of(k(4, 0, 0))),
                new Elem(102, List.of(k(0, 1, 0))));
        assertEquals(Set.of(102), touching(face, elems));
    }

    @Test
    @DisplayName("the portal case from #74: the surviving column is withheld, the far one is not")
    void portalFrame() {
        // columns at x=0 and x=6 under a beam at y=5; the right column never arrived
        Set<BlockKey> included = new java.util.HashSet<>();
        for (int y = 0; y < 5; y++) included.add(k(0, y, 0));
        for (int x = 0; x <= 6; x++) included.add(k(x, 5, 0));
        List<BlockKey> skipped = new java.util.ArrayList<>();
        for (int y = 0; y < 5; y++) skipped.add(k(6, y, 0));

        Set<BlockKey> face = Truncation.face(skipped, included);
        // only the top of the missing column touches the beam
        assertEquals(Set.of(k(6, 4, 0)), face);

        List<Elem> elems = List.of(
                new Elem(1, List.of(k(0, 0, 0), k(0, 1, 0), k(0, 2, 0), k(0, 3, 0), k(0, 4, 0))),
                new Elem(2, List.of(k(0, 5, 0), k(1, 5, 0), k(2, 5, 0), k(3, 5, 0),
                                    k(4, 5, 0), k(5, 5, 0), k(6, 5, 0))));
        Set<Integer> withheld = touching(face, elems);
        assertTrue(withheld.contains(2), "the beam ends at the missing column and must be withheld");
        assertEquals(Set.of(2), withheld, "the left column stands on ground that did arrive");
    }
}
