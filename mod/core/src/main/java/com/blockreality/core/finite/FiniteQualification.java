package com.blockreality.core.finite;

import java.util.List;
import java.util.Objects;

/**
 * The read-side qualification model of one {@link FiniteResult} (design §7.4): exactly
 * what the native engine certified, carried to the display lane without any Java-side
 * recomputation. An island status stays a raw wire code; {@link IslandView#statusName()}
 * names 0–4 and anything else is {@code UNKNOWN} — never guessed.
 *
 * <p>The two honest absences:
 *
 * <ul>
 *   <li>{@code loadFactorReported == false} — the response legitimately carried no load
 *       factor (e.g. a restored checkpoint); the HUD shows "not reported", not a number.
 *   <li>{@link #assessed()} is false when the reply named no islands — one unevaluated
 *       world is never a safe world.
 * </ul>
 */
public record FiniteQualification(long worldRevision, long historySequence, boolean fullyConverged,
                                  boolean loadFactorReported, double loadFactor,
                                  List<IslandView> islands,
                                  com.blockreality.core.bsi.BsiCorot.Availability availability,
                                  ArcView arc) {

    /** One island verdict with its wire status kept verbatim for display. */
    public record IslandView(long status, String statusName, long flags, String reason) {
        public IslandView { Objects.requireNonNull(statusName); Objects.requireNonNull(reason); }
    }

    /** Static-continuation scalars of an arc step, when the result is one. */
    public record ArcView(double radius, double constraintResidual) { }

    public FiniteQualification {
        islands = List.copyOf(islands);
        Objects.requireNonNull(availability);
        if (loadFactorReported && !Double.isFinite(loadFactor))
            throw new IllegalArgumentException("invalid finite load factor");
    }

    /** False when the reply carried no island rows — nothing was assessed. */
    public boolean assessed() { return !islands.isEmpty(); }

    /** Whether any profile reported the material-history bit (&4). */
    public boolean materialHistoryPresent() {
        return availability.reported() && availability.materialPresent();
    }

    /** Whether the response carried the ABI10 shell-material tail. */
    public boolean shellMaterialReported() {
        return availability.reported() && availability.shellProfiles() > 0;
    }

    public static FiniteQualification of(FiniteResult result) {
        Objects.requireNonNull(result);
        var islands = result.islands().stream()
                .map(i -> new IslandView(i.status(), i.statusName(), i.flags(), i.reason()))
                .toList();
        return new FiniteQualification(result.basis().worldRevision(), result.basis().historySequence(),
                result.fullyConverged(), result.loadFactorReported(), result.loadFactor(),
                islands, result.availability(),
                result.arc() == null ? null : new ArcView(result.arc().radius(), result.arc().constraintResidual()));
    }
}
