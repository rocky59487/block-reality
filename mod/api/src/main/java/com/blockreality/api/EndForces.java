package com.blockreality.api;

/**
 * The six internal force components at one end of a member, in the member's own local
 * frame. Units are N and N·mm.
 *
 * <p>{@code N} is <strong>compression-positive</strong> here because that is what
 * FrameCore reports and these are passed through unaltered — the tension-positive
 * conversion is applied only to the fibre stresses in {@link Fibre}, exactly once, at
 * the sidecar boundary. Mixing the two conventions in one record would be the single
 * easiest way to introduce a sign bug, so they are kept in separate types with the
 * convention stated on each.
 */
public record EndForces(double n, double vy, double vz, double t, double my, double mz) {

    public static final EndForces ZERO = new EndForces(0, 0, 0, 0, 0, 0);
}
