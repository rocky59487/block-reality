package com.blockreality.core.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.BsiCorotFracture;
import com.blockreality.core.bsi.BsiFracture;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The complete durable description of one finite fracture transaction
 * (FINITE_FRACTURE_TRANSACTION §5.1, journal value kind {@code VAL_FRACTURE_PLAN=5}).
 *
 * <p>The plan is pure evidence, never authority: it binds the request identity, the
 * committed before-basis, the exact after-root (source + checkpoint objects), the native
 * candidate token <em>as historical evidence only</em>, the receipt's decision counters,
 * the retired/born ownership and the fracture-load ledger plus the archive records as raw
 * section bytes. {@code planHash} is {@code sha256} of the canonical encoding — it is
 * derived, never stored in itself.
 *
 * <p>Construction freezes the basis invariants: the after stamp is exactly
 * {@code before+1} on both clocks, and a commit-qualified plan carries the native
 * qualification (empty end status, no pending decision, no physical flags, no
 * shell-material gap, fully converged) — the registry re-checks every field against the
 * stored wire frame before anything becomes durable.
 */
public record FiniteFracturePlan(FiniteDomainId domain, UUID request, UUID actor,
                                 String actorProvenance, Basis before, After after,
                                 String requestFrameHash, String wireFrameHash,
                                 BsiFracture.Token token,
                                 int steps, int flags, int remainingBlocks, int end,
                                 int corotFlags, int pending, boolean fullyConverged,
                                 String remainingGeneration, int beforeCells,
                                 List<Long> retiredOwnership, List<BsiFracture.Owner> bornBodies,
                                 byte[] loadLedger, byte[] archiveRecords) {

    public static final int MAX_LEDGER_BYTES = 1 << 20;   // chunked values cap any one byte field

    /** The committed basis the fracture stood on — the durable five-tuple, no session data. */
    public record Basis(long worldRevision, long historySequence,
                        String sourceHash, String vocabHash, String ownersHash) {
        public Basis {
            FiniteBasis.hash(sourceHash); FiniteBasis.hash(vocabHash); FiniteBasis.hash(ownersHash);
            if (worldRevision < 0 || historySequence < 0) throw invalid();
        }
    }

    /** The after root payload: the new stamps plus the content-addressed objects it names. */
    public record After(long worldRevision, long historySequence, String sourceHash,
                        FiniteBlobRef source, FiniteRoot.Checkpoint checkpoint) {
        public After {
            FiniteBasis.hash(sourceHash);
            Objects.requireNonNull(source); Objects.requireNonNull(checkpoint);
            if (worldRevision < 0 || historySequence < 0) throw invalid();
        }
        /** The {@code finite/root/<bsiDomain>} after-image this transaction proposes. */
        public FiniteRoot.Next next() {
            return new FiniteRoot.Next(worldRevision, historySequence, source, checkpoint);
        }
    }

    public FiniteFracturePlan {
        Objects.requireNonNull(domain); Objects.requireNonNull(request);
        Objects.requireNonNull(actor); Objects.requireNonNull(before); Objects.requireNonNull(after);
        FiniteDomainId.nonzero(request); FiniteDomainId.nonzero(actor);
        FiniteCodec.text(actorProvenance, 256);
        FiniteBasis.hash(requestFrameHash); FiniteBasis.hash(wireFrameHash);
        Objects.requireNonNull(token);
        if (after.worldRevision() != before.worldRevision() + 1
                || after.historySequence() != before.historySequence() + 1)
            throw invalid();
        if (steps < 0 || flags < 0 || flags > 3 || remainingBlocks < 0
                || end < 0 || end > 2 || corotFlags < 0 || corotFlags > 15
                || pending < -1 || pending > BsiCorotFracture.MAX_EVENTS
                || beforeCells < 0 || beforeCells > FiniteSource.MAX_BLOCKS)
            throw invalid();
        if (remainingGeneration == null || !remainingGeneration.matches("[0-9a-f]{16}"))
            throw invalid();
        retiredOwnership = List.copyOf(retiredOwnership);
        long previous = 0;
        for (long id : retiredOwnership) {
            if (id <= 0 || id <= previous) throw invalid();
            previous = id;
        }
        bornBodies = List.copyOf(bornBodies);
        var bornPositions = new java.util.HashSet<BlockKey>();
        var bornIds = new java.util.HashSet<Long>();
        for (var owner : bornBodies) {
            // The archive's installed-owner rows in archive order — unique, never re-sorted.
            if (owner.artifact() <= 0 || !bornPositions.add(owner.position())
                    || !bornIds.add(owner.artifact())) throw invalid();
        }
        loadLedger = Objects.requireNonNull(loadLedger).clone();
        archiveRecords = Objects.requireNonNull(archiveRecords).clone();
        if (loadLedger.length > MAX_LEDGER_BYTES || archiveRecords.length > MAX_LEDGER_BYTES)
            throw invalid();
        if (loadLedger.length % BsiCorotFracture.FRACTURE_LOAD_BYTES != 0
                || archiveRecords.length % BsiCorotFracture.ARCHIVE_BYTES != 0) throw invalid();
    }

    /** The plan's content identity — derived from the encoding, never persisted inside it. */
    public String planHash() { return FiniteSource.sha256(encode()); }

    public byte[] encode() { return FiniteCodec.encodeFracturePlan(this); }

    public static FiniteFracturePlan decode(byte[] data) {
        return FiniteCodec.decodeFracturePlan(data);
    }

    /**
     * The durable receipt fields the journal entry reports — the receipt the driver writes
     * into the terminal record after COMMITTED.
     */
    public record Outcome(int steps, int remainingBlocks, int end, int retiredOwners,
                          int bornBodies) { }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid finite fracture plan");
    }
}
