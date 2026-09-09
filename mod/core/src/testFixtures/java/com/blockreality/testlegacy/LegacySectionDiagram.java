package com.blockreality.testlegacy;

import com.blockreality.api.*;
import com.blockreality.core.render.SectionDiagram;
import java.util.Optional;

public final class LegacySectionDiagram {
    private LegacySectionDiagram() {}
    /** Test-only legacy reconstruction. Production draws the supplied neutral-axis intercept. */
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

}
