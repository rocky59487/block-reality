package com.blockreality.api;

import com.blockreality.api.geom.Vec3d;

import java.util.Optional;

/**
 * The stress field of one member, as a function rather than as samples of one.
 *
 * <p>This is what makes a proper contour possible. Sending eleven stations and four fibres
 * forces a renderer to interpolate between samples of a function it could simply evaluate,
 * and no amount of interpolation recovers what happens between the four corners of a
 * section. With the member's local frame, its section properties and the uniform load, the
 * exact value is available at every point of the member — which is exactly what a surface
 * plot needs, and it is smaller on the wire than the samples were.
 *
 * <p><strong>The field.</strong> Tension-positive, at distance {@code x} along the member
 * and offsets {@code y}, {@code z} in the local section axes:
 *
 * <pre>
 *   N(x)  = Ni(1-t) + Nj·t
 *   Mz(x) = Mzi(1-t) + Mzj·t + (wy/2)·x·(L-x)
 *   My(x) = Myi(1-t) + Myj·t + (wz/2)·x·(L-x)
 *
 *   sigma(x, y, z) = -N/A + Mz·y/Iz - My·z/Iy
 * </pre>
 *
 * <p>Three sign facts are packed into that last line, and each was measured rather than
 * assumed (ENGINE_FINDINGS):
 *
 * <ul>
 *   <li>{@code N} is compression-positive on the wire, hence the leading minus.
 *   <li>The end values are <em>section</em> forces. FrameCore reports end J as an element
 *       end action whose sign flips with the member's direction; the sidecar converts it
 *       once, at the boundary.
 *   <li><strong>The two bending terms carry opposite signs.</strong> With a right-handed
 *       local triad, {@code +Mz} puts {@code +y} in tension while {@code +My} puts
 *       {@code +z} in compression. This is not symmetry-breaking for its own sake; it is
 *       what the deflected shape does.
 * </ul>
 *
 * <p>The parabolic term is exact for the uniform load the engine applies, so this
 * reproduces the closed form at every point rather than near the sampled ones.
 *
 * @param originMm node i, Minecraft world millimetres
 * @param ax       member axis, unit, Minecraft world space
 * @param ay       local y ("top") direction, unit
 * @param az       local z direction, unit
 * @param cz       half-depth along local y — pairs with {@code Iz}, not {@code Iy}
 * @param cy       half-depth along local z — pairs with {@code Iy}
 * @param wy       uniform load along local y, N/mm
 */
public record StressFieldSpec(
        Vec3d originMm, Vec3d ax, Vec3d ay, Vec3d az,
        double lengthMm,
        double area, double iy, double iz, double cy, double cz,
        double wy, double wz,
        EndForces endI, EndForces endJ) {

    /** Axial force at a distance along the member, compression-positive as on the wire. */
    public double axialAt(double xMm) {
        double t = t(xMm);
        return endI.n() * (1 - t) + endJ.n() * t;
    }

    /** Bending moment about local z. Includes the exact parabola of the uniform load. */
    public double mzAt(double xMm) {
        double t = t(xMm);
        return endI.mz() * (1 - t) + endJ.mz() * t + (wy / 2.0) * xMm * (lengthMm - xMm);
    }

    public double myAt(double xMm) {
        double t = t(xMm);
        return endI.my() * (1 - t) + endJ.my() * t + (wz / 2.0) * xMm * (lengthMm - xMm);
    }

    /**
     * Tension-positive normal stress, MPa.
     *
     * @param xMm distance along the member from end i
     * @param yMm offset along local y, within {@code ±cz}
     * @param zMm offset along local z, within {@code ±cy}
     */
    public double sigmaAt(double xMm, double yMm, double zMm) {
        double s = 0;
        if (area > 0) s -= axialAt(xMm) / area;
        if (iz > 0) s += mzAt(xMm) * yMm / iz;
        if (iy > 0) s -= myAt(xMm) * zMm / iy;
        return s;
    }

    /**
     * Stress at a point of the world, given in millimetres.
     *
     * <p>The block is a <strong>magnified view of the section</strong>: a structural block
     * is a one-metre cube while its section is a few hundred millimetres, so a point on the
     * cube's surface is mapped proportionally onto the section rather than extrapolated
     * outside it. Extrapolating would invent stresses at places where there is no material.
     */
    public double sigmaAtWorldMm(Vec3d pMm, double blockHalfMm) {
        double dx = pMm.x() - originMm.x();
        double dy = pMm.y() - originMm.y();
        double dz = pMm.z() - originMm.z();

        double x = dx * ax.x() + dy * ax.y() + dz * ax.z();
        double yLocal = dx * ay.x() + dy * ay.y() + dz * ay.z();
        double zLocal = dx * az.x() + dy * az.y() + dz * az.z();

        double xc = Math.max(0, Math.min(x, lengthMm));
        double scale = blockHalfMm > 0 ? blockHalfMm : 1;
        return sigmaAt(xc, clamp(yLocal / scale, -1, 1) * cz, clamp(zLocal / scale, -1, 1) * cy);
    }

    /** Largest stress magnitude anywhere on the member, for the legend and normalisation. */
    public double peakMagnitudeMpa(int samples) {
        double peak = 0;
        int n = Math.max(2, samples);
        for (int i = 0; i < n; i++) {
            double x = lengthMm * i / (n - 1.0);
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    peak = Math.max(peak, Math.abs(sigmaAt(x, sy * cz, sz * cy)));
                }
            }
        }
        return peak;
    }

    /** World position of the centroid at a distance along the member, millimetres. */
    public Vec3d centroidMm(double xMm) {
        return new Vec3d(originMm.x() + ax.x() * xMm,
                         originMm.y() + ax.y() * xMm,
                         originMm.z() + ax.z() * xMm);
    }

    /**
     * A station reconstructed from the field.
     *
     * <p>The client regenerates these rather than receiving them: the field is smaller on
     * the wire than its own samples, and a client that can evaluate exactly should never
     * be handed an approximation of the same thing.
     */
    public StressStation stationAt(double xMm) {
        double sTop = sigmaAt(xMm, cz, 0);
        double sBot = sigmaAt(xMm, -cz, 0);
        double sPls = sigmaAt(xMm, 0, cy);
        double sMin = sigmaAt(xMm, 0, -cy);

        java.util.List<Fibre> fibres = java.util.List.of(
                new Fibre("TOP_Y", ay, cz, sTop),
                new Fibre("BOT_Y", ay.scaled(-1), cz, sBot),
                new Fibre("PLUS_Z", az, cy, sPls),
                new Fibre("MINUS_Z", az.scaled(-1), cy, sMin));

        double tens = Math.max(0, Math.max(Math.max(sTop, sBot), Math.max(sPls, sMin)));
        double comp = Math.max(0, Math.max(Math.max(-sTop, -sBot), Math.max(-sPls, -sMin)));

        Optional<Double> naY = Optional.empty();
        if ((sTop > 0) != (sBot > 0) && Math.abs(sTop - sBot) > 1e-12) {
            naY = Optional.of(-cz * (sTop + sBot) / (sTop - sBot));
        }
        Optional<Double> naZ = Optional.empty();
        if ((sPls > 0) != (sMin > 0) && Math.abs(sPls - sMin) > 1e-12) {
            naZ = Optional.of(-cy * (sPls + sMin) / (sPls - sMin));
        }
        return new StressStation(xMm, centroidMm(xMm), fibres, tens, comp, 0, naY, naZ);
    }

    /** {@code n} evenly spaced stations, for the section diagram and the picker. */
    public java.util.List<StressStation> stations(int n) {
        int k = Math.max(2, n);
        java.util.List<StressStation> out = new java.util.ArrayList<>(k);
        for (int i = 0; i < k; i++) out.add(stationAt(lengthMm * i / (k - 1.0)));
        return out;
    }

    private double t(double xMm) {
        if (lengthMm <= 0) return 0;
        return clamp(xMm / lengthMm, 0, 1);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
