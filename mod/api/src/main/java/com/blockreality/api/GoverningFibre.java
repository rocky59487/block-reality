package com.blockreality.api;

/**
 * Which fibre of the section governs a member's demand/capacity check.
 *
 * <p>This is <strong>not</strong> a load type and <strong>not</strong> a product failure
 * event. It was previously called {@code FailMode}, and that name invited exactly the
 * mistake it should prevent: a reader seeing {@code CRUSH} on a steel beam and routing it
 * into a concrete-crushing effect. Whether a member fractures, crushes or forms a
 * mechanism is a separate decision that nothing in this pipeline makes yet.
 *
 * <p>Read carefully: this is not the load type. {@code ElasticAllowable} takes the argmax
 * of five ratios, so a purely bent steel member reports {@link #CRUSH} — steel's
 * compressive allowable (350 MPa) is below its tensile one (500 MPa), so the compression
 * face reaches its limit first. The same cantilever in concrete flips to {@link #TENSION}.
 *
 * <p>That is more useful than a "BENDING" label would be: it names the face that gives
 * out first, which is exactly what a player needs in order to fix the member.
 */
public enum GoverningFibre {
    NONE,
    /** Compression face governs. */
    CRUSH,
    /** Tension face governs. */
    TENSION,
    SHEAR,
    BENDING,
    TORSION,
    /** Shell von Mises — not produced by the beam-only demo. */
    SHELL_VM;

    /** Parses the wire token, degrading unknown values to {@link #NONE} rather than throwing. */
    public static GoverningFibre fromWire(String s) {
        if (s == null) return NONE;
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
