package com.blockreality.core.protocol;

import com.blockreality.api.geom.BlockKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Which parts of an answer had incomplete input.
 *
 * <p>The gatherer reads the world one block at a time and cannot touch an unloaded
 * chunk — forcing one to load would let a structure somewhere drag chunks in behind the
 * player. So blocks it has seen before but cannot read right now are left out of the
 * request. The engine is then asked about a structure with a piece missing and answers
 * confidently, because from where it sits nothing is missing: a portal frame whose right
 * column was not loaded came back with max D/C 0.1927 where the whole frame gives 0.0606
 * (#74).
 *
 * <p>This class is the bookkeeping that makes that visible. It is deliberately not
 * mechanics and not connectivity analysis:
 *
 * <ul>
 *   <li>{@link #face} answers "which blocks that I know about, and did not send, are
 *       touching something I did send" — a request-integrity question about the input.
 *   <li>{@link #touching} answers "which returned elements sit against one of those" —
 *       so their verdict can be withheld while everything else is shown normally.
 * </ul>
 *
 * <p>Adjacency is face adjacency, six neighbours. That is how runs and facets are built,
 * and a diagonal neighbour carries no load path, so counting it would widen the false
 * positives for nothing. It is used only to <em>withhold</em> a number, never to assert
 * one — the criteria are frozen as N14 in {@code docs/GATES.md}, and the excluded option
 * is named there: a truncation face must never become a support.
 */
public final class Truncation {

    private Truncation() { }

    private static final int[][] NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
    };

    /**
     * The truncation face: blocks that were skipped and are face-adjacent to a block that
     * was sent.
     *
     * <p>A skipped block far from everything in the request is not a truncation of it —
     * it is a different structure that happens to be unloaded, and suppressing the whole
     * world for that would switch analysis off permanently on any world larger than the
     * loaded radius. That is why this is an intersection and not a count.
     *
     * <p>Iteration order of the result follows {@code skipped}, so a caller that wants to
     * report a few example coordinates gets a stable answer.
     */
    public static Set<BlockKey> face(Collection<BlockKey> skipped, Set<BlockKey> included) {
        if (skipped == null || skipped.isEmpty() || included == null || included.isEmpty()) {
            return Set.of();
        }
        Set<BlockKey> out = new LinkedHashSet<>();
        for (BlockKey p : skipped) {
            if (p == null) continue;
            for (int[] d : NEIGHBOURS) {
                if (included.contains(new BlockKey(p.x() + d[0], p.y() + d[1], p.z() + d[2]))) {
                    out.add(p);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Ids of the elements that sit against the truncation face.
     *
     * <p>An element qualifies when any of its blocks is face-adjacent to a face block.
     * The element's own blocks were all in the request by construction, so this is asking
     * whether the thing it was extracted from continues into a chunk nobody could read.
     *
     * <p>Conservative on purpose. Without island attribution from the engine this can
     * withhold a verdict from an element whose structure did in fact end at that face;
     * the alternative is showing a number that might be about a different building. The
     * over-withholding is recorded as out of scope in the N14 freeze.
     */
    public static <T> Set<Integer> touching(Set<BlockKey> face, List<T> elements,
                                            Function<T, List<BlockKey>> blocksOf,
                                            ToIntFunction<T> idOf) {
        if (face == null || face.isEmpty() || elements == null || elements.isEmpty()) {
            return Set.of();
        }
        // Expand the face into the shell of cells that touch it, once, instead of testing
        // six neighbours per block of every element.
        Set<BlockKey> shell = new HashSet<>(face.size() * 6);
        for (BlockKey p : face) {
            for (int[] d : NEIGHBOURS) {
                shell.add(new BlockKey(p.x() + d[0], p.y() + d[1], p.z() + d[2]));
            }
        }
        Set<Integer> out = new LinkedHashSet<>();
        for (T e : elements) {
            List<BlockKey> blocks = blocksOf.apply(e);
            if (blocks == null) continue;
            for (BlockKey b : blocks) {
                if (shell.contains(b)) {
                    out.add(idOf.applyAsInt(e));
                    break;
                }
            }
        }
        return out;
    }

    /** A few face coordinates for a log line or a chat message, in a stable order. */
    public static List<BlockKey> examples(Set<BlockKey> face, int max) {
        List<BlockKey> out = new ArrayList<>(Math.min(max, face.size()));
        for (BlockKey p : face) {
            if (out.size() >= max) break;
            out.add(p);
        }
        return out;
    }
}
