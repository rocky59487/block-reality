package com.blockreality.api;

/**
 * The six internal force components at one end of a member, in the member's own local
 * frame. Units are N and N·mm.
 *
 * <p>These are section forces at both ends, with {@code N} compression-positive.
 * Sidecar supplies this convention; the BSI display adapter negates BSI's tension-positive
 * N and converts moments from N·m once at ingestion. These values are diagnostic:
 * native and network display paths use the supplied {@link Fibre} samples instead.
 */
public record EndForces(double n, double vy, double vz, double t, double my, double mz) {

    public static final EndForces ZERO = new EndForces(0, 0, 0, 0, 0, 0);
}
