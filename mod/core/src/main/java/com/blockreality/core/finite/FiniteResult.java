package com.blockreality.core.finite;

import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A live worker-side view of one finite analysis basis (design §3).
 *
 * <p>This is a W2 skeleton: it carries the {@link FiniteBasis}, the native convergence
 * verdicts verbatim, the per-island status/reason table decoded from {@code corotIslands}
 * + {@code corotText}, and the complete native section payload so W3 can build typed views
 * without another native call. {@code token} is worker-live identity only — it is never
 * written to durable state (design §3.1), and a token minted in one session can never
 * authorise work in another.
 *
 * <p>No stress, force, energy or stability is computed in Java; mechanics and material
 * history availability arrive decided and stay decided.
 */
public record FiniteResult(FiniteBasis basis, boolean fullyConverged, List<Island> islands,
                           Map<String, BsiResponse.Section> sections, byte[] payload,
                           double loadFactor, java.util.UUID artifactNamespace,
                           String generation, BsiFracture.Token token, Map<String, Long> limits,
                           ArcStep arc, com.blockreality.core.bsi.BsiCorot.Availability availability) {

    /**
     * The arc step scalars (design §7): {@code radius} is the accepted continuation step
     * and {@code constraintResidual} the engine's own path-constraint residual. Present
     * only on {@code corot.arc.advance} results; never computed in Java.
     */
    public record ArcStep(double radius, double constraintResidual) {
        public ArcStep {
            if (!(radius > 0) || !Double.isFinite(radius)
                    || !Double.isFinite(constraintResidual) || constraintResidual < 0)
                throw new IllegalArgumentException("invalid arc step");
        }
    }

    /**
     * One island verdict row (contract {@code bsi_corot_island_record}): the status enum is
     * kept as the raw wire value (0 CONVERGED … 4 INACTIVE) and {@code flags} carries the
     * resumed/transferred/checkpoint bits. {@code reason} is the UTF-8 reason slice or "".
     */
    public record Island(int index, long status, long flags, String reason) {
        public Island {
            Objects.requireNonNull(reason);
            if (index < 0 || status < 0 || flags < 0) throw new IllegalArgumentException("invalid island verdict");
        }
        /** The wire status names; callers display them, they never guess safety. */
        public String statusName() {
            return switch ((int) status) {
                case 0 -> "CONVERGED"; case 1 -> "MECHANISM"; case 2 -> "NOT_CONVERGED";
                case 3 -> "UNSUPPORTED"; case 4 -> "INACTIVE"; default -> "UNKNOWN";
            };
        }
    }

    public FiniteResult {
        Objects.requireNonNull(basis);
        Objects.requireNonNull(artifactNamespace);
        FiniteDomainId.nonzero(artifactNamespace);
        islands = List.copyOf(islands);
        sections = Map.copyOf(sections);
        payload = payload.clone();
        Objects.requireNonNull(generation);
        if (!generation.matches("[0-9a-f]{16}")) throw new IllegalArgumentException("invalid finite generation");
        Objects.requireNonNull(token);
        Objects.requireNonNull(availability);          // an empty census is honest; absent is not
        limits = Map.copyOf(limits);
    }

    /** loadFactor is reported only by solve/arc flows; NaN marks an unreported one (e.g. import). */
    public boolean loadFactorReported() { return !Double.isNaN(loadFactor); }
}
