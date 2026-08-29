package com.blockreality.impl.server;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.protocol.SolveRequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which test loads travel with a solve request — the rule that keeps loads and blocks
 * in lockstep.
 *
 * <p>The engine rejects a request containing any load on a block that formed no element
 * — globally, the whole request (#14's design). So the guard for "may this load
 * travel?" must be <em>"its block is in THIS request"</em>, not "its block is tracked":
 * a tracked block whose chunk is unloaded is skipped by the gather, and a load that
 * travelled anyway would poison every solve until the chunk came back — the whole
 * dimension's analysis silently off with no error a player can act on (#38).
 *
 * <p>Three fates, decided per load:
 * <ul>
 *   <li><strong>Included block</strong> → the load travels.
 *   <li><strong>Tracked but not included</strong> (unloaded chunk) → the load waits,
 *       exactly like its block: skipped, not forgotten.
 *   <li><strong>Not tracked at all</strong> (block gone) → the load is stale and is
 *       reported for removal — the load hangs ON the block and goes with it.
 * </ul>
 *
 * <p>There is a fourth fate the first three could not express, and it is the one that
 * hurt: an <em>included</em> block the engine forms no element from — a single structural
 * block, a plate block closing no facet. The load travels, the engine refuses the whole
 * request, and it refuses it again every tick, so the dimension's analysis stays dark
 * until the player thinks to remove a load nothing told them about. The glasses add a
 * load on any sneak-right-click with no feasibility check at all
 * ({@code StressGlassesItem.useOn}), so trying one block before building a span reaches
 * it immediately (PR26_REVIEW A-5, as corrected in {@code docs/pr26_findings}: the
 * hint string that claim rested on was never wired to anything, and has since been
 * deleted along with the three other dead keys).
 *
 * <p>The refusal reply carries no block list — it is written before the {@code unassigned}
 * field — so the recovery is a probe: re-solve the same world with NO loads, which cannot
 * be refused for this reason, and read {@code unassigned} off it. {@link #refusedBy} is
 * that rule.
 *
 * <p>No Minecraft imports: this rule is the one #38 shipped broken, so it is the one
 * that gets a plain JUnit harness (6.3).
 */
final class SnapshotLoads {

    private SnapshotLoads() { }

    /**
     * Appends the loads that may travel to {@code b}.
     *
     * @param loads    every test load, keyed by block
     * @param included blocks the gather actually put into this request
     * @param tracked  blocks still tracked (loaded or not)
     * @return the stale keys — loads whose block is gone; the caller removes them
     */
    static List<BlockKey> append(SolveRequest.Builder b,
                                 Map<BlockKey, double[]> loads,
                                 Set<BlockKey> included,
                                 Set<BlockKey> tracked) {
        List<BlockKey> stale = new ArrayList<>();
        for (Map.Entry<BlockKey, double[]> e : loads.entrySet()) {
            BlockKey key = e.getKey();
            if (included.contains(key)) {
                double[] f = e.getValue();
                b.load(new SolveRequest.PointLoad(key, f[0], f[1], f[2], 0, 0, 0));
            } else if (!tracked.contains(key)) {
                stale.add(key);
            }
            // else: tracked but not gathered this round — an unloaded chunk. The load
            // waits with its block (#38).
        }
        return stale;
    }

    /**
     * Loads that must be dropped after a no-load probe solve: those sitting on a block the
     * engine could form no element from.
     *
     * @param loaded     blocks currently carrying a test load
     * @param unassigned the probe's report of blocks that became no element
     * @return the subset of {@code loaded} the engine will keep refusing
     */
    static List<BlockKey> refusedBy(Set<BlockKey> loaded, Collection<BlockKey> unassigned) {
        List<BlockKey> out = new ArrayList<>();
        for (BlockKey k : unassigned) {
            if (loaded.contains(k)) out.add(k);
        }
        return out;
    }
}
