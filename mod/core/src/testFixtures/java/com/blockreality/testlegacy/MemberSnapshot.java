package com.blockreality.testlegacy;

import com.blockreality.api.*;

// Test-only protocol-2 model. Never included in a production source set or jar.

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
        /** Legacy Sidecar diagnostics; native and client display paths leave this empty. */
        Optional<StressFieldSpec> field,
        @javax.annotation.Nonnull Optional<BeamDisplayField> display,
        boolean overloaded,
        @javax.annotation.Nonnull Optional<Double> governingPositionMm) {

    public MemberSnapshot {
        blocks = List.copyOf(blocks);
        stations = List.copyOf(stations);
        if (display.isPresent() && (!display.get().stations().equals(stations) || display.get().lengthMm() != lengthMm))
            throw new IllegalArgumentException("member and display samples disagree");
    }

    /** Legacy source compatibility. Its protocol has numeric DC but no independent verdict flag. */
    public MemberSnapshot(int id, String material, String section, double lengthMm, double dc,
                          GoverningFibre governingFibre, int governingStation, EndForces endI, EndForces endJ,
                          List<BlockKey> blocks, List<StressStation> stations, Optional<StressFieldSpec> field) {
        this(id, material, section, lengthMm, dc, governingFibre, governingStation, endI, endJ, blocks, stations,
                field, legacyDisplay(field, stations), dc > 1.0,
                governingStation >= 0 && governingStation < stations.size()
                        ? Optional.of(stations.get(governingStation).xMm()) : Optional.empty());
    }

    private static Optional<BeamDisplayField> legacyDisplay(Optional<StressFieldSpec> field, List<StressStation> samples) {
        if (field.isEmpty() || samples.isEmpty()) return Optional.empty();
        var f = field.get();
        try {
            return Optional.of(new BeamDisplayField(f.originMm(), f.ax(), f.ay(), f.az(), f.lengthMm(), f.cz(), f.cy(), samples));
        } catch (IllegalArgumentException e) { return Optional.empty(); }
    }

    public boolean isOverloaded() { return overloaded; }

    /** Largest stress magnitude anywhere on this member, for normalising the overlay. */
    public double peakMagnitudeMpa() {
        if (display.isPresent()) return display.get().peakMagnitudeMpa();
        double m = 0;
        for (StressStation s : stations) m = Math.max(m, s.peakMagnitudeMpa());
        return m;
    }
    public com.blockreality.api.MemberSnapshot snapshot() {
        return new com.blockreality.api.MemberSnapshot(id, material, section, lengthMm, dc,
                governingFibre, governingStation, endI, endJ, blocks, stations, display,
                overloaded, governingPositionMm);
    }
}
