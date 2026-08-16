package com.blockreality.core.render;

import com.blockreality.api.Fibre;
import com.blockreality.api.StressStation;

import java.util.Optional;

/**
 * The classic section stress diagram, as data: a linear profile across the depth with the
 * neutral axis where it crosses zero.
 *
 * <p>This is the answer to "the overlay is hard to read". Coloured ribbons threaded through
 * one-metre cubes can show that <em>something</em> differs between the top and the bottom
 * of a member, but they cannot say which is which, by how much, or where the sign changes —
 * and at building scale they stop being a picture at all. A section diagram says all three
 * at a glance, in the form every structures textbook already uses, and it can carry words.
 *
 * <p>Words matter more than they look. Whether the top of a beam is in tension or in
 * compression is <strong>not a property of beams</strong>: a cantilever hogs and puts its
 * top in tension, a beam sitting on two supports sags and puts its top in compression.
 * Both are correct, and colour alone leaves the reader to guess which case they are in.
 * The diagram states it.
 *
 * @param topSigmaMpa      tension-positive stress at the top fibre
 * @param bottomSigmaMpa   tension-positive stress at the bottom fibre
 * @param neutralFraction  0 at the top fibre, 1 at the bottom; empty when the whole section
 *                         has one sign and there is genuinely no neutral axis
 */
public record SectionDiagram(double topSigmaMpa, double bottomSigmaMpa,
                             Optional<Double> neutralFraction) {

    public static Optional<SectionDiagram> of(StressStation station) {
        Optional<Fibre> top = station.fibre("TOP_Y");
        Optional<Fibre> bot = station.fibre("BOT_Y");
        if (top.isEmpty() || bot.isEmpty()) return Optional.empty();

        double t = top.get().sigmaMpa();
        double b = bot.get().sigmaMpa();

        Optional<Double> na = Optional.empty();
        // Only when the two ends really straddle zero. A section that is entirely in
        // tension has no neutral axis, and extrapolating one to a point outside the
        // section would draw a line that is not there.
        if ((t > 0) != (b > 0) && Math.abs(t - b) > 1e-12) {
            na = Optional.of(t / (t - b));
        }
        return Optional.of(new SectionDiagram(t, b, na));
    }

    /** Stress at a depth, 0 at the top fibre and 1 at the bottom. Linear by Euler-Bernoulli. */
    public double sigmaAt(double fraction) {
        double f = fraction < 0 ? 0 : Math.min(fraction, 1);
        return topSigmaMpa + (bottomSigmaMpa - topSigmaMpa) * f;
    }

    public double peakMagnitudeMpa() {
        return Math.max(Math.abs(topSigmaMpa), Math.abs(bottomSigmaMpa));
    }

    /** True when the member is bending here rather than uniformly stretched or squashed. */
    public boolean isBending() { return neutralFraction.isPresent(); }

    /** Translation key naming what the top face is doing, for a label the reader can trust. */
    public String topLabelKey() { return labelKey(topSigmaMpa); }

    public String bottomLabelKey() { return labelKey(bottomSigmaMpa); }

    private static String labelKey(double sigma) {
        if (sigma > 1e-9) return "br.section.tension";
        if (sigma < -1e-9) return "br.section.compression";
        return "br.section.zero";
    }
}
