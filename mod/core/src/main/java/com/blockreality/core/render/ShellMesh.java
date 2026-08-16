package com.blockreality.core.render;

import com.blockreality.api.ShellFieldSpec;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.geom.Vec3d;

import java.util.List;
import java.util.Optional;

/**
 * Finds which plate facet owns a point of the world, so a contour can be drawn on the
 * blocks rather than on the mesh.
 *
 * <h2>Why the two are not the same place</h2>
 * Facet corners sit at block <em>centres</em>, the same convention members use, because
 * that is what lets a column share a node with the floor it holds up. The meshed plate is
 * therefore half a block short of the visible slab all the way round: an N×N slab of
 * blocks meshes into (N−1)×(N−1) facets. A block on the slab's edge is genuinely outside
 * every facet.
 *
 * <p>Two answers were available and only one is honest. Drawing the contour on the facets
 * would put a carpet floating half a block off the floor. Drawing it on the blocks means
 * the outermost half-block is <strong>clamped</strong> to the nearest facet's edge value —
 * the same clamp a member already applies past its own ends, extended rather than invented.
 * Clamping states a value that exists; extrapolating would invent one.
 */
public final class ShellMesh {

    private ShellMesh() { }

    /**
     * @param xi  natural coordinate, clamped into {@code [-1, 1]}
     * @param eta natural coordinate, clamped into {@code [-1, 1]}
     * @param outsideMm how far the point lay outside the facet in the plate's own plane,
     *                  millimetres. Zero when the point is genuinely inside.
     */
    public record Hit(ShellSnapshot shell, ShellFieldSpec field,
                      double xi, double eta, double outsideMm) { }

    /**
     * The facet nearest a world point, preferring one that contains it.
     *
     * <p>Distance is measured in three dimensions — in-plane overshoot and off-plane
     * offset together — so a floor and the wall meeting it do not steal each other's
     * blocks. Facets without a field are skipped rather than treated as empty ones.
     */
    public static Optional<Hit> locate(List<ShellSnapshot> shells, Vec3d pMm) {
        Hit best = null;
        double bestScore = Double.MAX_VALUE;

        for (ShellSnapshot s : shells) {
            if (s.field().isEmpty()) continue;
            ShellFieldSpec f = s.field().get();
            if (!f.isComplete()) continue;

            double[] p = f.paramAt(pMm);
            double overX = Math.max(0, Math.abs(p[0]) - 1) * f.halfX();
            double overY = Math.max(0, Math.abs(p[1]) - 1) * f.halfY();
            double off = f.offNormalMm(pMm);
            double score = overX * overX + overY * overY + off * off;

            if (score < bestScore) {
                bestScore = score;
                best = new Hit(s, f, clamp(p[0]), clamp(p[1]), Math.hypot(overX, overY));
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Largest signed-principal magnitude anywhere in the mesh, for a colour scale shared
     * by every facet.
     *
     * <p>Per-facet normalisation would be the easy choice and a badly misleading one: each
     * facet would saturate at its own peak, so a lightly stressed corner of a floor would
     * look exactly as alarming as the strip over a support.
     */
    public static double peakMpa(List<ShellSnapshot> shells) {
        double peak = 0;
        for (ShellSnapshot s : shells) peak = Math.max(peak, s.peakMpa());
        return peak;
    }

    private static double clamp(double v) { return v < -1 ? -1 : Math.min(v, 1); }
}
