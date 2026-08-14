package com.blockreality.api.geom;

/** An axis-aligned box in Minecraft world space, in blocks. */
public record Aabb(Vec3d min, Vec3d max) {

    public static Aabb around(BlockKey a, BlockKey b) {
        return new Aabb(
                new Vec3d(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())),
                new Vec3d(Math.max(a.x(), b.x()) + 1, Math.max(a.y(), b.y()) + 1, Math.max(a.z(), b.z()) + 1));
    }

    public Vec3d centre() {
        return new Vec3d((min.x() + max.x()) / 2, (min.y() + max.y()) / 2, (min.z() + max.z()) / 2);
    }
}
