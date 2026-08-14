package com.blockreality.api.geom;

/**
 * An integer block coordinate.
 *
 * <p>Deliberately not {@code net.minecraft.core.BlockPos}: the api layer stays free of
 * Minecraft types so the core can be tested headless (DECISIONS D-015). The Forge layer
 * converts at its own boundary, in one place.
 *
 * <p>One block is one metre (CLAUDE.md), so the millimetre value used by the engine is
 * exactly {@code coordinate * 1000}.
 */
public record BlockKey(int x, int y, int z) {

    public static final double BLOCK_MM = 1000.0;

    /** Centre of this block in millimetres — the node position the engine sees. */
    public Vec3d centreMm() {
        return new Vec3d(x * BLOCK_MM, y * BLOCK_MM, z * BLOCK_MM);
    }

    @Override
    public String toString() { return "(" + x + "," + y + "," + z + ")"; }
}
