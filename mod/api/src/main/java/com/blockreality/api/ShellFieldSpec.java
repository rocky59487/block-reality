package com.blockreality.api;

import com.blockreality.api.geom.Vec3d;

import java.util.List;

/**
 * The surface stress field of one MITC4 plate facet, as a function rather than as samples.
 *
 * <p>The beam field ({@link StressFieldSpec}) is a scalar: one number describes the state
 * at a point of a section. A plate's is <strong>not</strong>. The state at a point on a
 * plate's surface is a two-dimensional stress tensor, and every honest thing this class
 * does follows from that:
 *
 * <pre>
 *   sigma_x  = Nxx/t + (6/t^2) * Mxx * zf
 *   sigma_y  = Nyy/t + (6/t^2) * Myy * zf
 *   tau_xy   = Nxy/t + (6/t^2) * Mxy * zf        zf = -1 bottom, +1 top
 * </pre>
 *
 * <p>with the moment resultants interpolated bilinearly from the four corner values, in
 * the element's own natural coordinates. Membrane resultants are element-constant, which
 * is what the engine recovers; bending is not, which is why the corners travel.
 *
 * <h2>Reading the tensor as one number</h2>
 * A contour needs a scalar. Two are offered and they answer different questions:
 *
 * <ul>
 *   <li>{@link #signedPrincipal} — the principal stress of largest magnitude, carrying its
 *       own sign. This is the quantity that is directly comparable with a beam's
 *       tension-positive fibre stress, so a slab and the beam under it can share one
 *       colour scale and one legend instead of being two pictures that look alike and
 *       mean different things.
 *   <li>{@link #vonMises} — the yield-relevant scalar, and the one the engine's own
 *       demand/capacity screen uses. It is never negative, so it says how hard the
 *       material is working, not which way.
 * </ul>
 *
 * <h2>The local triad is left-handed in Minecraft space</h2>
 * {@code ex}, {@code ey} and {@code normal} are a right-handed triad <em>in the engine</em>.
 * The Minecraft axis map swaps two axes, so read here they satisfy
 * {@code ex × ey = −normal}. They are a perfectly good basis for projecting a point onto
 * the facet, which is all this class uses them for. Do not rebuild the normal from a cross
 * product of the other two.
 *
 * @param cornersMm   the four corner nodes in Minecraft world millimetres, in the engine's
 *                    counter-clockwise order about {@code normal}
 * @param thicknessMm plate thickness — a real dimension, decoupled from the block (D-004)
 * @param cornerM     per-corner bending resultants, parallel to {@code cornersMm}
 */
public record ShellFieldSpec(
        List<Vec3d> cornersMm,
        Vec3d ex, Vec3d ey, Vec3d normal,
        double thicknessMm,
        double nxx, double nyy, double nxy,
        double mxx, double myy, double mxy,
        double qx, double qy,
        List<Moments> cornerM) {

    /** Bending and twisting resultants at a point, per unit width (N·mm/mm). */
    public record Moments(double mxx, double myy, double mxy) {
        public static final Moments ZERO = new Moments(0, 0, 0);
    }

    /** A plane stress state on one face of the plate, in the facet's local axes (MPa). */
    public record Surface(double sx, double sy, double txy) { }

    public ShellFieldSpec {
        cornersMm = List.copyOf(cornersMm);
        cornerM = List.copyOf(cornerM);
    }

    public boolean isComplete() { return cornersMm.size() == 4 && cornerM.size() == 4; }

    /** Facet centre, Minecraft world millimetres. */
    public Vec3d centreMm() {
        if (cornersMm.size() < 4) return Vec3d.ZERO;
        double x = 0, y = 0, z = 0;
        for (Vec3d c : cornersMm) { x += c.x(); y += c.y(); z += c.z(); }
        return new Vec3d(x / 4, y / 4, z / 4);
    }

    /** Half-extent along {@code ex}, millimetres. */
    public double halfX() { return edgeHalf(0, 1); }

    /** Half-extent along {@code ey}, millimetres. */
    public double halfY() { return edgeHalf(0, 3); }

    private double edgeHalf(int a, int b) {
        if (cornersMm.size() < 4) return 0;
        Vec3d p = cornersMm.get(a), q = cornersMm.get(b);
        return new Vec3d(q.x() - p.x(), q.y() - p.y(), q.z() - p.z()).length() / 2.0;
    }

    /**
     * Natural coordinates of a world point, <strong>unclamped</strong>, so a caller can
     * tell whether the point is inside this facet ({@code |xi| <= 1} and {@code |eta| <= 1})
     * or how far outside it lies.
     *
     * <p>Exact for the rectangular facets the extractor produces — a general bilinear
     * inverse is not needed and would only hide a change of that assumption.
     */
    public double[] paramAt(Vec3d pMm) {
        Vec3d c = centreMm();
        double dx = pMm.x() - c.x(), dy = pMm.y() - c.y(), dz = pMm.z() - c.z();
        double hx = halfX(), hy = halfY();
        double u = dx * ex.x() + dy * ex.y() + dz * ex.z();
        double v = dx * ey.x() + dy * ey.y() + dz * ey.z();
        return new double[] { hx > 0 ? u / hx : 0, hy > 0 ? v / hy : 0 };
    }

    /** Signed distance from the facet plane, millimetres. Negative is behind the normal. */
    public double offNormalMm(Vec3d pMm) {
        Vec3d c = centreMm();
        return (pMm.x() - c.x()) * normal.x()
             + (pMm.y() - c.y()) * normal.y()
             + (pMm.z() - c.z()) * normal.z();
    }

    /** World position of a natural coordinate. */
    public Vec3d pointAt(double xi, double eta) {
        if (cornersMm.size() < 4) return Vec3d.ZERO;
        double[] n = shape(xi, eta);
        double x = 0, y = 0, z = 0;
        for (int k = 0; k < 4; k++) {
            Vec3d c = cornersMm.get(k);
            x += n[k] * c.x();
            y += n[k] * c.y();
            z += n[k] * c.z();
        }
        return new Vec3d(x, y, z);
    }

    /** Bending resultants at a natural coordinate, bilinear in the corner values. */
    public Moments momentAt(double xi, double eta) {
        if (cornerM.size() < 4) return new Moments(mxx, myy, mxy);
        double[] n = shape(clamp(xi), clamp(eta));
        double a = 0, b = 0, c = 0;
        for (int k = 0; k < 4; k++) {
            Moments m = cornerM.get(k);
            a += n[k] * m.mxx();
            b += n[k] * m.myy();
            c += n[k] * m.mxy();
        }
        return new Moments(a, b, c);
    }

    /**
     * Stress state at a point of the plate.
     *
     * @param zFrac position through the thickness: {@code -1} the bottom face, {@code +1}
     *              the top face, {@code 0} the mid-surface where only membrane survives.
     *              Values outside are clamped — there is no material there to have a stress.
     */
    public Surface surfaceAt(double xi, double eta, double zFrac) {
        double t = thicknessMm;
        if (!(t > 0)) return new Surface(0, 0, 0);
        Moments m = momentAt(xi, eta);
        double bend = 6.0 / (t * t) * clamp(zFrac);
        return new Surface(nxx / t + bend * m.mxx(),
                           nyy / t + bend * m.myy(),
                           nxy / t + bend * m.mxy());
    }

    /**
     * The principal stress of largest magnitude, keeping its sign — tension positive, the
     * same convention as every other stress this project reports.
     */
    public double signedPrincipal(double xi, double eta, double zFrac) {
        Surface s = surfaceAt(xi, eta, zFrac);
        double avg = 0.5 * (s.sx() + s.sy());
        double dev = 0.5 * (s.sx() - s.sy());
        double r = Math.sqrt(dev * dev + s.txy() * s.txy());
        double s1 = avg + r, s2 = avg - r;
        return Math.abs(s1) >= Math.abs(s2) ? s1 : s2;
    }

    /** Von Mises equivalent stress, never negative. */
    public double vonMises(double xi, double eta, double zFrac) {
        Surface s = surfaceAt(xi, eta, zFrac);
        double v = s.sx() * s.sx() - s.sx() * s.sy() + s.sy() * s.sy() + 3 * s.txy() * s.txy();
        return Math.sqrt(Math.max(0, v));
    }

    /** Largest signed-principal magnitude over the facet, for the legend and normalisation. */
    public double peakMagnitudeMpa() {
        double peak = 0;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                for (int z = -1; z <= 1; z += 2) {
                    peak = Math.max(peak, Math.abs(signedPrincipal(i, j, z)));
                }
            }
        }
        return peak;
    }

    /** Bilinear shape functions in MITC4's corner order: (-1,-1) (+1,-1) (+1,+1) (-1,+1). */
    private static double[] shape(double xi, double eta) {
        return new double[] {
                0.25 * (1 - xi) * (1 - eta),
                0.25 * (1 + xi) * (1 - eta),
                0.25 * (1 + xi) * (1 + eta),
                0.25 * (1 - xi) * (1 + eta),
        };
    }

    private static double clamp(double v) { return v < -1 ? -1 : Math.min(v, 1); }
}
