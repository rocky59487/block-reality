package com.blockreality.api.geom;

/**
 * A double vector in <em>Minecraft world space</em>: +Y is up, one block is one metre.
 *
 * <p>The sidecar has already converted out of FrameCore's Z-up frame, so nothing on
 * this side of the boundary ever performs an axis swap. If you find yourself writing
 * one, the bug is upstream.
 */
public record Vec3d(double x, double y, double z) {

    public static final Vec3d ZERO = new Vec3d(0, 0, 0);

    public Vec3d plus(Vec3d o) { return new Vec3d(x + o.x, y + o.y, z + o.z); }

    public Vec3d scaled(double s) { return new Vec3d(x * s, y * s, z * s); }

    public double length() { return Math.sqrt(x * x + y * y + z * z); }

    /** Unit vector, or {@link #ZERO} for a zero-length input — never NaN. */
    public Vec3d normalised() {
        double l = length();
        return l > 0 ? new Vec3d(x / l, y / l, z / l) : ZERO;
    }
}
