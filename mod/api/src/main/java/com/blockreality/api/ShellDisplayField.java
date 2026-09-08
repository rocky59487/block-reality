package com.blockreality.api;

import com.blockreality.api.geom.Vec3d;
import javax.annotation.Nonnull;
import java.util.List;

/** Immutable recovered samples. Interpolation is for drawing, never a mechanics verdict. */
public final class ShellDisplayField {
    /** Principal stresses and von Mises in MPa; angle in radians, in the supplied local frame. */
    public record Surface(double s1, double s2, double theta, double vm) {
        public Surface {
            finite(s1); finite(s2); finite(theta); finite(vm);
            if (vm < 0) throw new IllegalArgumentException("negative surface vm");
        }
        public double signedPrincipal() { return Math.abs(s1) >= Math.abs(s2) ? s1 : s2; }
    }

    private final List<Vec3d> cornersMm;
    private final Vec3d ex, ey, normal, centre;
    private final List<Surface> top, bottom;
    private final double halfX, halfY, peak;

    public ShellDisplayField(@Nonnull List<Vec3d> cornersMm, @Nonnull Vec3d ex, @Nonnull Vec3d ey,
                             @Nonnull Vec3d normal, @Nonnull List<Surface> top, @Nonnull List<Surface> bottom) {
        if (cornersMm.size() != 4 || top.size() != 4 || bottom.size() != 4)
            throw new IllegalArgumentException("shell display needs four corners on each face");
        this.cornersMm = List.copyOf(cornersMm);
        this.top = List.copyOf(top); this.bottom = List.copyOf(bottom);
        this.ex = ex; this.ey = ey; this.normal = normal;
        for (Vec3d p : this.cornersMm) vector(p);
        vector(ex); vector(ey); vector(normal);
        if (Math.abs(dot(ex, ex) - 1) > 1e-9 || Math.abs(dot(ey, ey) - 1) > 1e-9
                || Math.abs(dot(normal, normal) - 1) > 1e-9 || Math.abs(dot(ex, ey)) > 1e-9
                || Math.abs(dot(ex, normal)) > 1e-9 || Math.abs(dot(ey, normal)) > 1e-9)
            throw new IllegalArgumentException("invalid shell frame");
        // Both handednesses are accepted: legacy Sidecar axes were reflected. Never rebuild the normal.
        Vec3d a = difference(cornersMm.get(1), cornersMm.get(0));
        Vec3d b = difference(cornersMm.get(3), cornersMm.get(0));
        halfX = dot(a, ex) / 2; halfY = dot(b, ey) / 2;
        finite(halfX); finite(halfY);
        if (!(halfX > 0 && halfY > 0)) throw new IllegalArgumentException("degenerate shell geometry");
        double tolerance = Math.max(halfX, halfY) * 1e-9;
        if (difference(a, ex.scaled(2 * halfX)).length() > tolerance
                || difference(b, ey.scaled(2 * halfY)).length() > tolerance
                || difference(difference(cornersMm.get(2), cornersMm.get(0)), a.plus(b)).length() > tolerance)
            throw new IllegalArgumentException("non-rectangular shell display geometry");
        centre = cornersMm.get(0).plus(a.scaled(0.5)).plus(b.scaled(0.5));
        vector(centre);
        double p = 0;
        for (int k = 0; k < 4; k++) p = Math.max(p, Math.max(
                Math.abs(top.get(k).signedPrincipal()), Math.abs(bottom.get(k).signedPrincipal())));
        peak = p;
    }

    @Nonnull public List<Vec3d> cornersMm() { return cornersMm; }
    @Nonnull public Vec3d ex() { return ex; }
    @Nonnull public Vec3d ey() { return ey; }
    @Nonnull public Vec3d normal() { return normal; }
    @Nonnull public List<Surface> top() { return top; }
    @Nonnull public List<Surface> bottom() { return bottom; }
    @Nonnull public Vec3d centreMm() { return centre; }
    public double halfX() { return halfX; }
    public double halfY() { return halfY; }
    public double peakMagnitudeMpa() { return peak; }

    @Nonnull public double[] paramAt(@Nonnull Vec3d pMm) {
        Vec3d d = difference(pMm, centre);
        return new double[]{dot(d, ex) / halfX, dot(d, ey) / halfY};
    }
    public double offNormalMm(@Nonnull Vec3d pMm) { return dot(difference(pMm, centre), normal); }
    @Nonnull public Vec3d pointAt(double xi, double eta) {
        finite(xi); finite(eta);
        return centre.plus(ex.scaled(xi * halfX)).plus(ey.scaled(eta * halfY));
    }

    /** Convex interpolation of the eight signed-principal samples, not tensor recovery. */
    public double signedPrincipal(double xi, double eta, double zFrac) {
        return interpolate(xi, eta, zFrac, false);
    }
    /** Convex interpolation of the engine's equivalent-stress samples. */
    public double vonMises(double xi, double eta, double zFrac) {
        return interpolate(xi, eta, zFrac, true);
    }
    private double interpolate(double xi, double eta, double zFrac, boolean vm) {
        double u = (clamp(xi) + 1) / 2, v = (clamp(eta) + 1) / 2, z = (clamp(zFrac) + 1) / 2;
        return mix(face(bottom, u, v, vm), face(top, u, v, vm), z);
    }
    private static double face(List<Surface> s, double u, double v, boolean vm) {
        return mix(mix(value(s.get(0), vm), value(s.get(1), vm), u),
                mix(value(s.get(3), vm), value(s.get(2), vm), u), v);
    }
    private static double value(Surface s, boolean vm) { return vm ? s.vm() : s.signedPrincipal(); }
    private static double mix(double a, double b, double t) { return a * (1 - t) + b * t; }
    private static double clamp(double x) { finite(x); return Math.max(-1, Math.min(1, x)); }
    private static double dot(Vec3d a, Vec3d b) { return a.x()*b.x() + a.y()*b.y() + a.z()*b.z(); }
    private static Vec3d difference(Vec3d a, Vec3d b) { return a.plus(b.scaled(-1)); }
    private static void vector(Vec3d p) { finite(p.x()); finite(p.y()); finite(p.z()); }
    private static void finite(double x) {
        if (!Double.isFinite(x)) throw new IllegalArgumentException("non-finite shell display value");
    }
}
