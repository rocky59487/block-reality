package com.blockreality.api;

import com.blockreality.api.geom.BlockKey;

import java.util.List;
import java.util.Optional;

/**
 * One structural member as the engine sees it, for one analysis.
 *
 * <p>"Snapshot" is literal: this is the result of one solve at one revision. It is not
 * the member's identity. The persistent object — stable id, damage history, lineage —
 * is the registry's business (D-011); this record is what gets recomputed and thrown
 * away every time the world changes.
 *
 * @param id        engine-side member index for this analysis, not a persistent identity
 * @param material  material token, e.g. {@code steel}
 * @param section   section token, e.g. {@code steel_rect_200x400} — never square (GATES.md)
 * @param lengthMm  member length, millimetres
 * @param dc        display demand/capacity ratio; {@code overloaded} carries the authoritative verdict
 * @param governingFibre    which fibre governs {@code dc} — see {@link GoverningFibre}
 * @param governingStation  index into {@code stations} of the section that governs;
 *                          {@code -1} if none. The controlling section is often in the
 *                          MIDDLE of a member, not at an end.
 * @param blocks    the blocks this member was extracted from
 * @param stations  sampled stress states along the length, ordered by {@code xMm}; ties retain source order
 */
public record MemberSnapshot(
        int id,
        String material,
        String section,
        double lengthMm,
        double dc,
        GoverningFibre governingFibre,
        int governingStation,
        EndForces endI,
        EndForces endJ,
        List<BlockKey> blocks,
        List<StressStation> stations,
        @javax.annotation.Nonnull Optional<BeamDisplayField> display,
        boolean overloaded,
        @javax.annotation.Nonnull Optional<Double> governingPositionMm) {

    public MemberSnapshot {
        blocks = List.copyOf(blocks);
        stations = List.copyOf(stations);
        if (display.isPresent() && (!display.get().stations().equals(stations) || display.get().lengthMm() != lengthMm))
            throw new IllegalArgumentException("member and display samples disagree");
    }

    public boolean isOverloaded() { return overloaded; }

    /** Largest stress magnitude anywhere on this member, for normalising the overlay. */
    public double peakMagnitudeMpa() {
        if (display.isPresent()) return display.get().peakMagnitudeMpa();
        double m = 0;
        for (StressStation s : stations) m = Math.max(m, s.peakMagnitudeMpa());
        return m;
    }
}
