package com.blockreality.core.finite;

import java.util.Objects;

/**
 * A persisted pointer to an opaque engine checkpoint blob (design §3).
 *
 * <p>The blob stays in place under {@code objects/} — it is never decoded, re-serialised or
 * round-tripped through floats/JSON. {@code engineBuild} and {@code contractSha256} pin what
 * produced it so an incompatible engine refuses rather than silently adopting old history
 * (P10: unknown engine/checkpoint pairs are read-only, never overwritten).
 */
public record FiniteCheckpointRef(String engineBuild, String contractSha256, int format,
                                  FiniteBasis basis, FiniteBlobRef blob,
                                  long byteBudget, long allocationBudget, long elementBudget) {

    public FiniteCheckpointRef {
        FiniteCodec.text(engineBuild, 256);
        FiniteCodec.text(contractSha256, 128);
        FiniteCodec.format(format);
        Objects.requireNonNull(basis);
        Objects.requireNonNull(blob);
        if (byteBudget < 32 || byteBudget > 268431360
                || allocationBudget < 1 || allocationBudget > 536870912
                || elementBudget < 1 || elementBudget > 16777216) throw invalid();
    }

    /** The journal-storable checkpoint slot of this ref; the basis stays in the root chain. */
    public FiniteRoot.Checkpoint slot() {
        return new FiniteRoot.Checkpoint(blob, format, engineBuild, contractSha256,
                byteBudget, allocationBudget, elementBudget);
    }

    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid finite checkpoint reference"); }
}
