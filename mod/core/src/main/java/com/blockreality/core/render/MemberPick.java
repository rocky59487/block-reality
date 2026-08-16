package com.blockreality.core.render;

import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.StressStation;
import com.blockreality.api.geom.Vec3d;

import java.util.List;
import java.util.Optional;

/**
 * Which member is the player looking at.
 *
 * <p>This exists because drawing the full stress field of every member at once does not
 * scale past a handful of them: four ribbons per member times a hundred members is a haze
 * that answers no question. The readable version shows <em>one</em> member in detail — the
 * one under the crosshair — and reduces the rest to a single colour each.
 *
 * <p>Selection is by angular distance to the eye ray, not by Euclidean distance to the
 * player. Looking straight down a long beam should select that beam even though its far
 * end is many blocks away, and a member a metre to the side that you are not looking at
 * should not steal focus.
 */
public final class MemberPick {

    private MemberPick() { }

    /** Beyond this angle from the crosshair a member is not considered looked-at. */
    private static final double MAX_ANGLE_RAD = Math.toRadians(12);

    /** Blocks. Past this a member is context, not a subject. */
    public static final double MAX_DISTANCE = 64.0;

    /**
     * @param eye     camera position, blocks
     * @param look    unit view direction
     * @return the id of the member being looked at, if any
     */
    public static Optional<Integer> pick(Vec3d eye, Vec3d look, List<MemberSnapshot> members) {
        int best = -1;
        double bestScore = Double.MAX_VALUE;

        for (MemberSnapshot m : members) {
            List<StressStation> st = m.stations();
            if (st.isEmpty()) continue;

            // Sampling the stations rather than only the ends means a long member is
            // selectable by looking at its middle, which is exactly where the interesting
            // section usually is.
            for (StressStation s : st) {
                Vec3d p = blocks(s.centroidMm());
                Vec3d d = new Vec3d(p.x() - eye.x(), p.y() - eye.y(), p.z() - eye.z());
                double dist = d.length();
                if (dist > MAX_DISTANCE || dist < 1e-6) continue;

                double cos = (d.x() * look.x() + d.y() * look.y() + d.z() * look.z()) / dist;
                if (cos <= 0) continue;                       // behind the camera
                double angle = Math.acos(Math.min(1.0, cos));
                if (angle > MAX_ANGLE_RAD) continue;

                // Angle dominates, distance breaks ties: of two members equally on-axis,
                // the near one is the one you meant.
                double score = angle * 1000.0 + dist;
                if (score < bestScore) {
                    bestScore = score;
                    best = m.id();
                }
            }
        }
        return best >= 0 ? Optional.of(best) : Optional.empty();
    }

    private static Vec3d blocks(Vec3d mm) {
        return new Vec3d(mm.x() / 1000.0, mm.y() / 1000.0, mm.z() / 1000.0);
    }
}
