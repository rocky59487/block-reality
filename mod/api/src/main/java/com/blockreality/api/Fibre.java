package com.blockreality.api;

import com.blockreality.api.geom.Vec3d;

/**
 * One extreme fibre of a member's cross-section at one station along its length.
 *
 * <p><strong>Sign convention: tension-positive.</strong> {@code sigmaMpa > 0} is tension,
 * {@code < 0} is compression. The conversion out of FrameCore's compression-positive
 * kernel happens once, in the sidecar, and the renderer must never flip it again
 * (TEACHING_PORT: convert at the engine boundary, the UI does not re-convert).
 *
 * <p>{@link #direction} is already in Minecraft world space and points from the section
 * centroid towards this fibre. Carrying it on the wire means the renderer never has to
 * reconstruct the member's local axes — the failure mode that produced both of the
 * FrameCore issues in ENGINE_FINDINGS.md.
 *
 * @param name       wire token: {@code TOP_Y}, {@code BOT_Y}, {@code PLUS_Z}, {@code MINUS_Z}
 * @param direction  unit vector from centroid to fibre, Minecraft world space
 * @param offsetMm   distance from the centroid to this fibre, millimetres
 * @param sigmaMpa   axial stress, <strong>tension-positive</strong>, MPa
 */
public record Fibre(String name, Vec3d direction, double offsetMm, double sigmaMpa) {

    public boolean isTension() { return sigmaMpa > 0; }

    public boolean isCompression() { return sigmaMpa < 0; }

    /** World-space position of this fibre, given the station's centroid position in mm. */
    public Vec3d positionMm(Vec3d centroidMm) {
        return centroidMm.plus(direction.scaled(offsetMm));
    }
}
