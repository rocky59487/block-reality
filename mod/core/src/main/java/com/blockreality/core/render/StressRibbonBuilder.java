package com.blockreality.core.render;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.Fibre;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.StressStation;
import com.blockreality.api.geom.Vec3d;
import com.blockreality.api.render.Rgb;
import com.blockreality.api.render.StressPalette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a solved member into drawable ribbons.
 *
 * <h2>What normalisation to use</h2>
 * The choice changes what the picture answers, so it is explicit rather than defaulted:
 *
 * <ul>
 *   <li>{@link #memberPeak} — each member is scaled to its own maximum. Every member shows
 *       its full internal distribution, and a lightly loaded one looks just as vivid as an
 *       overloaded one. Right for reading <em>one</em> member.
 *   <li>{@link #scenePeak} — all members share one scale. Members become comparable and a
 *       lightly loaded one correctly looks pale. Right for reading a <em>structure</em>.
 * </ul>
 *
 * <p>Getting this wrong does not produce a wrong colour, it produces a misleading figure,
 * which is worse because it looks fine.
 */
public final class StressRibbonBuilder {

    private StressRibbonBuilder() { }

    /** Millimetres to blocks. One block is one metre (CLAUDE.md). */
    private static final double MM_TO_BLOCK = 1.0 / 1000.0;

    public static double memberPeak(MemberSnapshot m) {
        double p = m.peakMagnitudeMpa();
        return p > 0 ? p : 1.0;
    }

    public static double scenePeak(AnalysisResult r) {
        double p = 0;
        for (MemberSnapshot m : r.members()) p = Math.max(p, m.peakMagnitudeMpa());
        return p > 0 ? p : 1.0;
    }

    public static List<StressRibbon> build(AnalysisResult result, StressPalette palette, double peakMpa) {
        List<StressRibbon> out = new ArrayList<>(result.members().size());
        for (MemberSnapshot m : result.members()) out.add(build(m, palette, peakMpa));
        return out;
    }

    public static StressRibbon build(MemberSnapshot member, StressPalette palette, double peakMpa) {
        List<StressStation> stations = member.stations();
        double peak = peakMpa > 0 && Double.isFinite(peakMpa) ? peakMpa : 1.0;

        // Group by fibre name so each face becomes one continuous strip. Ordering follows
        // the first station's fibre order, which is the engine's, so TOP_Y is always drawn
        // before BOT_Y and the legend order matches the geometry.
        Map<String, List<Sample>> byFibre = new LinkedHashMap<>();
        for (StressStation st : stations) {
            Vec3d centre = st.centroidMm();
            for (Fibre f : st.fibres()) {
                byFibre.computeIfAbsent(f.name(), k -> new ArrayList<>())
                       .add(new Sample(scale(f.positionMm(centre)), f.direction(), f.sigmaMpa()));
            }
        }

        List<StressRibbon.Band> bands = new ArrayList<>();
        for (Map.Entry<String, List<Sample>> e : byFibre.entrySet()) {
            List<Sample> s = e.getValue();
            for (int i = 0; i + 1 < s.size(); i++) {
                Sample a = s.get(i);
                Sample b = s.get(i + 1);
                Rgb ca = palette.signedStress(a.sigma, peak);
                Rgb cb = palette.signedStress(b.sigma, peak);
                // The hatch is picked from the more heavily stressed end, so a segment
                // that straddles the neutral axis is hatched as what it is mostly doing
                // rather than flickering between the two patterns.
                double dominant = Math.abs(a.sigma) >= Math.abs(b.sigma) ? a.sigma : b.sigma;
                bands.add(new StressRibbon.Band(e.getKey(), a.dir, a.pos, b.pos, ca, cb,
                        palette.hatchFor(dominant, peak), a.sigma, b.sigma));
            }
        }

        return new StressRibbon(member.id(), peak, bands, neutralAxis(stations));
    }

    /**
     * The neutral-axis polyline, in blocks.
     *
     * <p>Stations without a neutral axis are skipped rather than filled in at the
     * centroid. A fully tensile section has no neutral axis, and drawing a line through
     * it would tell the player something false about a case that is worth noticing.
     */
    private static List<Vec3d> neutralAxis(List<StressStation> stations) {
        List<Vec3d> pts = new ArrayList<>();
        for (StressStation st : stations) {
            Optional<Double> off = st.naOffsetYMm();
            if (off.isEmpty()) continue;
            // The offset is measured from the centroid along the same axis as the TOP_Y
            // fibre, so that fibre's direction is the one to walk along.
            Optional<Fibre> top = st.fibre("TOP_Y");
            if (top.isEmpty()) continue;
            pts.add(scale(st.centroidMm().plus(top.get().direction().scaled(off.get()))));
        }
        return pts;
    }

    private static Vec3d scale(Vec3d mm) {
        return new Vec3d(mm.x() * MM_TO_BLOCK, mm.y() * MM_TO_BLOCK, mm.z() * MM_TO_BLOCK);
    }

    private record Sample(Vec3d pos, Vec3d dir, double sigma) { }
}
