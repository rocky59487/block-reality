package com.blockreality.api;

/**
 * A lens of the stress glasses (DEMO_V0 §6).
 *
 * <p>The glasses are a diagnostic instrument, not a gate. They inform and warn; they
 * never prevent construction. A player is allowed to build something bad and watch it
 * fall over — that is the productive-failure loop the whole project is built around, and
 * a tool that refused to let it happen would destroy the lesson.
 *
 * <p>New lenses arrive through the registry, not by editing this enum, once the demo
 * slice is closed. The three marked {@code v0} are the ones the demo must ship.
 */
public enum ScanMode {

    /** v0. D/C ratio per member, plus the governing fibre. */
    UTILIZATION,

    /**
     * v0. Signed axial stress across the section depth — the tension/compression split
     * and the neutral axis. This is the lens that has to be right for the physics to be
     * legible at all.
     */
    STRESS,

    /** v0. Material grade, section token, and any damage already recorded. */
    MATERIAL,

    /** Later. Cracking, buckling, permanent bend and impact history. */
    DAMAGE,

    /** Later. N/V/T/M components and the governing mode. */
    FORCE_MODE,

    /** Later. How load reaches the supports. */
    LOAD_PATH,

    /** Later. Joints, formwork, curing, temporary shoring. */
    CONSTRUCTION;

    public boolean isDemoV0() {
        return this == UTILIZATION || this == STRESS || this == MATERIAL;
    }

    public ScanMode nextDemoMode() {
        return switch (this) {
            case UTILIZATION -> STRESS;
            case STRESS -> MATERIAL;
            default -> UTILIZATION;
        };
    }
}
