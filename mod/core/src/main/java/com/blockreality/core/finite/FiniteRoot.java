package com.blockreality.core.finite;

import java.util.Objects;
import java.util.UUID;

/**
 * The committed root descriptor behind {@code finite/root/<bsiDomain>} (design §4.1).
 *
 * <p>This is the single durable authority for which finite source and history are current.
 * {@code prevRootHash} chains to the SHA-256 of the value bytes this descriptor replaced,
 * so a committed history cannot silently lose a link. The on-disk {@code HEAD} file is only
 * a cache of where this chain currently points; it can never override a committed root.
 *
 * <p>{@code worldRevision} and {@code historySequence} are the two persisted clocks:
 * a new world revision may carry a different source ref; the same revision must carry the
 * identical source (history-only commits bump {@code historySequence} instead).
 */
public record FiniteRoot(long worldRevision, long historySequence, FiniteBlobRef source,
                         Checkpoint checkpoint, String prevRootHash, UUID requestId) {

    /** The checkpoint slot recorded inside the root: everything needed to re-import history. */
    public record Checkpoint(FiniteBlobRef blob, int format, String engineBuild, String contractSha256,
                             long byteBudget, long allocationBudget, long elementBudget) {
        public Checkpoint {
            Objects.requireNonNull(blob);
            FiniteCodec.format(format);
            FiniteCodec.text(engineBuild, 256);
            FiniteCodec.text(contractSha256, 128);
            if (byteBudget < 32 || byteBudget > 268431360
                    || allocationBudget < 1 || allocationBudget > 536870912
                    || elementBudget < 1 || elementBudget > 16777216) throw invalid();
        }
    }

    /** The new descriptor a root transaction proposes; the registry chains and validates it. */
    public record Next(long worldRevision, long historySequence, FiniteBlobRef source, Checkpoint checkpoint) {
        public Next {
            Objects.requireNonNull(source);
            if (worldRevision < 0 || historySequence < 0) throw invalid();
        }
    }

    public FiniteRoot {
        Objects.requireNonNull(source);
        FiniteDomainId.nonzero(requestId);
        FiniteBasis.hash(prevRootHash);
        if (worldRevision < 0 || historySequence < 0) throw invalid();
    }

    public static final String GENESIS_HASH = "0".repeat(64);

    public boolean genesis() { return prevRootHash.equals(GENESIS_HASH); }

    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid finite root descriptor"); }
}
