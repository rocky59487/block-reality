package com.blockreality.core.protocol;

import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;

import java.util.ArrayList;
import java.util.List;

/**
 * What Minecraft sends the engine: blocks and materials, nothing else.
 *
 * <p>D-006 in one sentence — Java says <em>blocks</em>, the engine owns the
 * <em>model</em>. There is no member, node, element, stiffness or boundary condition in
 * this request, because deciding what those are is the engine's job. If a field ever
 * appears here that describes the structural model rather than the world, the boundary
 * has leaked.
 */
public record SolveRequest(WorldRevision revision, List<Block> blocks, List<PointLoad> loads) {

    public SolveRequest {
        blocks = List.copyOf(blocks);
        loads = List.copyOf(loads);
    }

    /**
     * @param support this block is held by the ground or by something outside the model.
     *                With none of these the structure is a mechanism, and the engine
     *                says so rather than returning zeros.
     */
    public record Block(BlockKey pos, String material, String section, boolean support) { }

    /** A point load applied at a block, in N and N·mm, Minecraft axes (+y up). */
    public record PointLoad(BlockKey at, double fx, double fy, double fz, double mx, double my, double mz) {

        public static PointLoad downwards(BlockKey at, double newtons) {
            return new PointLoad(at, 0, -Math.abs(newtons), 0, 0, 0, 0);
        }
    }

    public static Builder builder(WorldRevision revision) { return new Builder(revision); }

    public static final class Builder {
        private final WorldRevision revision;
        private final List<Block> blocks = new ArrayList<>();
        private final List<PointLoad> loads = new ArrayList<>();

        private Builder(WorldRevision revision) { this.revision = revision; }

        public Builder block(BlockKey pos, String material, String section, boolean support) {
            blocks.add(new Block(pos, material, section, support));
            return this;
        }

        public Builder load(PointLoad l) { loads.add(l); return this; }

        public SolveRequest build() { return new SolveRequest(revision, blocks, loads); }
    }
}
