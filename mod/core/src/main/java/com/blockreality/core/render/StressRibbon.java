package com.blockreality.core.render;

import com.blockreality.api.geom.Vec3d;
import com.blockreality.api.render.Hatch;
import com.blockreality.api.render.Rgb;

import java.util.List;

/**
 * Everything needed to draw one member's stress field, with no engineering left in it.
 *
 * <p>The renderer's job is reduced to pushing vertices. It performs no sign conversion,
 * no axis transform, no normalisation and no colour decision — all of those have already
 * happened, in one place each, upstream. Both FrameCore issues in ENGINE_FINDINGS.md were
 * axis-pairing mistakes, and the way to stop making them is to leave the renderer no axes
 * to pair.
 *
 * <p>All positions are in <strong>blocks</strong> (metres), Minecraft world space, ready
 * to hand to a vertex consumer.
 *
 * @param peakMpa the magnitude that maps to full saturation, for the legend
 */
public record StressRibbon(int memberId, double peakMpa, List<Band> bands, List<Vec3d> neutralAxis) {

    public StressRibbon {
        bands = List.copyOf(bands);
        neutralAxis = List.copyOf(neutralAxis);
    }

    /**
     * A quad-strip segment along one fibre, between two adjacent stations.
     *
     * <p>The two ends carry their own colours so the strip is interpolated by the GPU;
     * a single flat colour per segment would produce visible banding exactly where the
     * gradient is steepest, which on a cantilever is at the support.
     *
     * @param fibre     which extreme fibre, e.g. {@code TOP_Y}
     * @param fibreDir  unit vector from the centreline out to this fibre, world space.
     *                  Carried so the renderer can widen the strip into a quad without
     *                  reconstructing the member's local axes — the one operation that
     *                  has already gone wrong twice upstream (ENGINE_FINDINGS.md).
     * @param fromSigma tension-positive stress at the near end, MPa
     */
    public record Band(
            String fibre,
            Vec3d fibreDir,
            Vec3d from, Vec3d to,
            Rgb fromColour, Rgb toColour,
            Hatch hatch,
            double fromSigma, double toSigma) {

        /** True when the sign flips inside this segment — the neutral axis crosses here. */
        public boolean crossesZero() { return (fromSigma > 0) != (toSigma > 0); }
    }
}
