package com.blockreality.api;

import com.blockreality.api.geom.Vec3d;

import java.util.List;
import java.util.Optional;

/**
 * The stress state at one sampling station along a member.
 *
 * <p>The stations are what the stress overlay draws. A member is not one colour: the
 * whole point is that a bent member is in tension on one face and compression on the
 * other <em>at the same station</em>, and that the split moves along the length.
 *
 * @param xMm           distance from end i along the member, millimetres
 * @param centroidMm    world position of the section centroid, millimetres
 * @param fibres        the extreme fibres, tension-positive (see {@link Fibre})
 * @param sigmaTensMpa  largest tensile stress at this station, {@code >= 0}
 * @param sigmaCompMpa  largest compressive <em>magnitude</em> at this station, {@code >= 0}
 * @param tauMpa        peak transverse shear stress, {@code k |V| / A} with the engine's
 *                      section factor: 1.5 for a solid rectangle, 4/3 for a solid circle
 * @param naOffsetYMm   neutral-axis offset from the centroid along local y, if one exists
 * @param naOffsetZMm   neutral-axis offset from the centroid along local z, if one exists
 */
public record StressStation(
        double xMm,
        Vec3d centroidMm,
        List<Fibre> fibres,
        double sigmaTensMpa,
        double sigmaCompMpa,
        double tauMpa,
        Optional<Double> naOffsetYMm,
        Optional<Double> naOffsetZMm) {

    public StressStation {
        fibres = List.copyOf(fibres);
    }

    /**
     * Whether the section is in bending at this station, in the only sense that matters
     * to a renderer: some fibre is in tension while another is in compression.
     *
     * <p>The absence of a neutral axis is not a defect — a fully tensile or fully
     * compressive section genuinely has none, and the sidecar declines to invent one.
     */
    public boolean hasNeutralAxis() {
        return naOffsetYMm.isPresent() || naOffsetZMm.isPresent();
    }

    /** Largest stress magnitude at this station, tension or compression. */
    public double peakMagnitudeMpa() {
        return Math.max(sigmaTensMpa, sigmaCompMpa);
    }

    public Optional<Fibre> fibre(String name) {
        return fibres.stream().filter(f -> f.name().equals(name)).findFirst();
    }
}
