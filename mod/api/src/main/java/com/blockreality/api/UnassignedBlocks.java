package com.blockreality.api;

import com.blockreality.api.geom.BlockKey;

import java.util.List;

/**
 * The blocks that produced no element result, and the one reason they share.
 *
 * <p>Grouped rather than one reason per block, because the size of this field is already a
 * known cost: a fully supported 120x120 raft reports all 14,400 of its blocks on every
 * solve. One string per reason keeps that from getting worse. No cap is applied at either
 * end — a cap would be a fresh way to lose blocks quietly, which is the thing this field
 * exists to prevent.
 *
 * @param reason why, as this build understands it
 * @param wire   the raw token, kept so an {@link UnassignedReason#UNKNOWN} can still be
 *               named in a log line rather than reported as a blank
 * @param blocks the blocks, in the order the engine listed them
 */
public record UnassignedBlocks(UnassignedReason reason, String wire, List<BlockKey> blocks) {

    public UnassignedBlocks {
        blocks = List.copyOf(blocks);
        wire = wire == null ? "" : wire;
    }

    public static UnassignedBlocks of(String wireToken, List<BlockKey> blocks) {
        return new UnassignedBlocks(UnassignedReason.fromWire(wireToken), wireToken, blocks);
    }

    /** A name for a log line: the known token, or the raw one, or a placeholder. */
    public String label() {
        if (reason != UnassignedReason.UNKNOWN) return reason.wire();
        return wire.isEmpty() ? "(no reason given)" : wire;
    }
}
