package com.blockreality.api;

import com.blockreality.api.geom.Vec3d;
import javax.annotation.Nonnull;
import java.util.List;

/** Immutable face-centre samples. Interpolation is a bounded drawing approximation, never mechanics. */
public final class BeamDisplayField {
    public enum Side { FIRST, LAST }
    private static final List<String> FACES = List.of("TOP_Y", "BOT_Y", "PLUS_Z", "MINUS_Z");
    private final Vec3d originMm, ax, ay, az;
    private final double lengthMm, halfYMm, halfZMm, peakMpa;
    private final List<StressStation> stations;
    private final List<Double> breaksMm;

    public BeamDisplayField(@Nonnull Vec3d originMm, @Nonnull Vec3d ax, @Nonnull Vec3d ay,
                            @Nonnull Vec3d az, double lengthMm, double halfYMm, double halfZMm,
                            @Nonnull List<StressStation> stations) {
        vector(originMm); vector(ax); vector(ay); vector(az);
        for (Vec3d a : List.of(ax, ay, az)) if (Math.abs(a.dot(a) - 1) > 1e-9)
            throw new IllegalArgumentException("non-unit beam frame");
        if (Math.abs(ax.dot(ay)) > 1e-9 || Math.abs(ax.dot(az)) > 1e-9 || Math.abs(ay.dot(az)) > 1e-9)
            throw new IllegalArgumentException("non-orthogonal beam frame");
        finite(lengthMm); finite(halfYMm); finite(halfZMm);
        if (lengthMm <= 0 || halfYMm <= 0 || halfZMm <= 0 || stations.isEmpty())
            throw new IllegalArgumentException("empty or degenerate beam display");
        this.originMm = originMm; this.ax = ax; this.ay = ay; this.az = az;
        this.lengthMm = lengthMm; this.halfYMm = halfYMm; this.halfZMm = halfZMm;
        this.stations = List.copyOf(stations);
        double last = -1, peak = 0;
        var breaks = new java.util.ArrayList<Double>();
        for (StressStation s : this.stations) {
            finite(s.xMm()); vector(s.centroidMm());
            if (s.xMm() < last || s.xMm() < 0 || s.xMm() > lengthMm || s.fibres().size() != 4)
                throw new IllegalArgumentException("invalid beam station order or range");
            if (s.xMm() == last && (breaks.isEmpty() || breaks.get(breaks.size() - 1) != last)) breaks.add(last);
            last = s.xMm();
            finite(s.sigmaTensMpa()); finite(s.sigmaCompMpa()); finite(s.tauMpa());
            s.naOffsetYMm().ifPresent(BeamDisplayField::finite); s.naOffsetZMm().ifPresent(BeamDisplayField::finite);
            for (int j = 0; j < 4; j++) {
                Fibre f = s.fibres().get(j); finite(f.sigmaMpa()); vector(f.direction()); finite(f.offsetMm());
                Vec3d expected = (j < 2 ? ay : az).scaled(j % 2 == 0 ? 1 : -1);
                if (!FACES.get(j).equals(f.name()) || f.direction().minus(expected).lengthSquared() > 1e-18
                        || Math.abs(f.offsetMm() - (j < 2 ? halfYMm : halfZMm)) > 1e-9)
                    throw new IllegalArgumentException("beam face sample geometry disagrees");
                peak = Math.max(peak, Math.abs(f.sigmaMpa()));
            }
        }
        this.peakMpa = peak; this.breaksMm = List.copyOf(breaks);
    }

    @Nonnull public Vec3d originMm() { return originMm; }
    @Nonnull public Vec3d ax() { return ax; }
    @Nonnull public Vec3d ay() { return ay; }
    @Nonnull public Vec3d az() { return az; }
    public double lengthMm() { return lengthMm; }
    public double halfYMm() { return halfYMm; }
    public double halfZMm() { return halfZMm; }
    public double peakMagnitudeMpa() { return peakMpa; }
    @Nonnull public List<StressStation> stations() { return stations; }
    @Nonnull public List<Double> breaksMm() { return breaksMm; }
    public double longitudinalMm(@Nonnull Vec3d p) { return p.minus(originMm).dot(ax); }

    /** O(log stations) lookup; exact duplicate x selects its first or last stored sample. */
    public double faceSigmaMpa(double xMm, int face, @Nonnull Side side) {
        finite(xMm);
        if (face < 0 || face > 3) throw new IllegalArgumentException("unknown beam face");
        int lo = upper(xMm, side);
        if (lo == 0) return sigma(0, face);
        if (lo == stations.size()) return sigma(lo - 1, face);
        double t = fraction(lo, xMm);
        return (1 - t) * sigma(lo - 1, face) + t * sigma(lo, face);
    }

    private int upper(double xMm, Side side) {
        int lo = 0, hi = stations.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            double x = stations.get(mid).xMm();
            if (x < xMm || (side == Side.LAST && x == xMm)) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private double fraction(int upper, double xMm) {
        double a = stations.get(upper - 1).xMm();
        return (xMm - a) / (stations.get(upper).xMm() - a);
    }

    /** Normalized section coordinates. Corners blend adjacent face centres; they are not corner stresses. */
    public double sampleMpa(double xMm, double y, double z, @Nonnull Side side) {
        finite(xMm); finite(y); finite(z);
        y = Math.max(-1, Math.min(1, y)); z = Math.max(-1, Math.min(1, z));
        double wy = Math.abs(y), wz = Math.abs(z), sum = wy + wz;
        if (sum > 1) { wy /= sum; wz /= sum; }
        int lo = upper(xMm, side), fy = y >= 0 ? 0 : 1, fz = z >= 0 ? 2 : 3;
        if (lo == 0) return section(0, wy, wz, fy, fz);
        if (lo == stations.size()) return section(lo - 1, wy, wz, fy, fz);
        double t = fraction(lo, xMm);
        return (1 - t) * section(lo - 1, wy, wz, fy, fz) + t * section(lo, wy, wz, fy, fz);
    }

    private double section(int i, double wy, double wz, int fy, int fz) {
        double centre = 0;
        for (int j = 0; j < 4; j++) centre += .25 * sigma(i, j);
        return (1 - wy - wz) * centre + wy * sigma(i, fy) + wz * sigma(i, fz);
    }

    public double sampleWorldMpa(@Nonnull Vec3d p, double blockHalfMm, @Nonnull Side side) {
        vector(p); finite(blockHalfMm);
        if (blockHalfMm <= 0) throw new IllegalArgumentException("invalid magnification");
        Vec3d d = p.minus(originMm);
        return sampleMpa(d.dot(ax), d.dot(ay) / blockHalfMm, d.dot(az) / blockHalfMm, side);
    }
    private double sigma(int station, int face) { return stations.get(station).fibres().get(face).sigmaMpa(); }
    private static void vector(Vec3d p) { finite(p.x()); finite(p.y()); finite(p.z()); }
    private static void finite(double x) {
        if (!Double.isFinite(x)) throw new IllegalArgumentException("non-finite beam display");
    }
}
