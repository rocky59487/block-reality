package com.blockreality.core.finite;

import com.blockreality.core.bsi.BsiCorotFracture;
import com.blockreality.core.bsi.BsiFracture;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The W4-A private fracture staging orchestrator (FINITE_FRACTURE_TRANSACTION §4.4).
 * It mirrors {@link FiniteHistory}: every native step runs on a private session the
 * caller hands in, the committed authority is never touched, and the result is an
 * inert {@link Staged} proposal — objects and plan — that only becomes durable when the
 * registry proves and the journal commits it.
 *
 * <p>Sequence S0–S4: read the committed root and source → declare the recorded
 * vocabulary and world at the committed revision on the private session → import the
 * committed checkpoint when one exists → {@code bsi.corot.fracture.prepare} under the
 * complete §3 receipt validation → on a qualified receipt, {@code bsi.fracture.finish
 * commit} with the receipt-derived after source → export the after checkpoint → publish
 * source and checkpoint as content-addressed objects → assemble the durable plan. A
 * non-qualified receipt is a named {@link NotQualified} incompletion after a native
 * discard — never a commit, never a world write. Any failure closes the private
 * session; the native candidate dies with it.
 */
public final class FiniteFracture {

    private final FiniteStore store;
    private final FiniteRegistry registry;

    public FiniteFracture(FiniteStore store, FiniteRegistry registry) {
        this.store = Objects.requireNonNull(store);
        this.registry = Objects.requireNonNull(registry);
    }

    /** The receipt failed the commit qualification — a durable-REJECTED condition. */
    public static final class NotQualified extends IOException {
        private final transient BsiCorotFracture.Prepared prepared;
        NotQualified(BsiCorotFracture.Prepared prepared) {
            super("Corot fracture receipt is not commit-qualified");
            this.prepared = prepared;
        }
        public BsiCorotFracture.Prepared prepared() { return prepared; }
    }

    /**
     * A fully staged fracture proposal. {@code session} is the private handle that ran
     * the native work — the caller may adopt it as the new authority session after the
     * durable commit lands, or close it.
     */
    public record Staged(FiniteFracturePlan plan, FiniteSource source, FiniteSource afterSource,
                         byte[] requestFrame, byte[] wireFrame, FiniteSession session,
                         BsiCorotFracture.Prepared prepared) {
        public Staged {
            Objects.requireNonNull(plan); Objects.requireNonNull(source);
            Objects.requireNonNull(afterSource); Objects.requireNonNull(session);
            requestFrame = requestFrame.clone(); wireFrame = wireFrame.clone();
        }
        /** The receipt the staging validated; may be null only on hand-built test stagings. */
        public BsiCorotFracture.Prepared prepared() { return prepared; }
    }

    /**
     * Stage one finite fracture against the domain's committed root.
     *
     * @param lane              a fresh private lane (never the authority session's)
     * @param sessionGeneration a fresh generation distinct from the authority session's
     * @param publishRequest    the request id owning the store staging directory
     */
    public Staged stage(FiniteLane lane, FiniteDomainId domain, UUID requestId,
                        List<BsiCorotFracture.Rule> rules, int budget,
                        int format, long byteBudget, long allocationBudget, long elementBudget,
                        long sessionGeneration, UUID publishRequest, UUID actor,
                        String actorProvenance, String engineBuild, String contractSha256)
            throws IOException {
        Objects.requireNonNull(lane); Objects.requireNonNull(domain); Objects.requireNonNull(rules);
        var committed = registry.root(domain)
                .orElseThrow(() -> new IOException("finite domain has no committed root"));
        var source = registry.source(domain);
        var session = FiniteSession.attach(lane, domain, source, sessionGeneration);
        try {
            if (!session.declareVocabulary())
                throw new IOException("staging vocabulary refused: " + session.refusal());
            if (!session.declareWorld(committed.worldRevision()))
                throw new IOException("staging world refused: " + session.refusal());
            if (committed.checkpoint() != null) {
                var slot = committed.checkpoint();
                var body = store.read(slot.blob());
                if (body.kind() != FiniteCodec.KIND_CHECKPOINT)
                    throw new IOException("committed root names a non-checkpoint object");
                var basis = new FiniteBasis(domain, committed.worldRevision(), committed.historySequence(),
                        source.sourceHash(), source.vocabHash(), source.ownersHash(), sessionGeneration);
                var ref = new FiniteCheckpointRef(slot.engineBuild(), slot.contractSha256(), slot.format(),
                        basis, slot.blob(), slot.byteBudget(), slot.allocationBudget(), slot.elementBudget());
                if (session.importCheckpoint(ref, body.payload()) == null)
                    throw new IOException("staging restore refused: " + session.refusal());
            }
            var prepared = session.fracturePrepare(requestId, rules, budget);
            if (prepared == null)
                throw new IOException("staging fracture refused: " + session.refusal());
            if (!prepared.qualified()) {
                // Named incompletion: native discard first, then the caller records REJECTED.
                if (session.fractureFinish(prepared, false, null) == null)
                    throw new IOException("staging fracture discard refused: " + session.refusal());
                throw new NotQualified(prepared);
            }
            byte[] ruleBytes = BsiCorotFracture.encodeRules(rules);
            var afterSource = FiniteSource.afterFracture(source, prepared, ruleBytes);
            var finish = session.fractureFinish(prepared, true, afterSource);
            if (finish != BsiFracture.Finish.COMMITTED && finish != BsiFracture.Finish.REPLAYED)
                throw new IOException("staging fracture commit refused: " + finish);
            var exported = session.exportCheckpoint(format, byteBudget, allocationBudget, elementBudget);
            if (exported == null)
                throw new IOException("staging export refused: " + session.refusal());
            if (exported.format() != format)
                throw new IOException("staging export format drifted");
            // Publish the after-checkpoint before the durable root commit may name it.
            var checkpointBlob = store.publish(publishRequest, FiniteCodec.KIND_CHECKPOINT, exported.blob());
            var sourceRef = store.publish(publishRequest, FiniteCodec.KIND_SOURCE, afterSource.serialize());
            var checkpointSlot = new FiniteRoot.Checkpoint(checkpointBlob, format, engineBuild,
                    contractSha256, byteBudget, allocationBudget, elementBudget);
            byte[] requestFrame = session.fractureRequestFrame();
            if (requestFrame == null) throw new IOException("staging kept no request frame");
            byte[] wireFrame = prepared.frame();
            var basis = new FiniteFracturePlan.Basis(committed.worldRevision(),
                    committed.historySequence(), source.sourceHash(), source.vocabHash(),
                    source.ownersHash());
            var after = new FiniteFracturePlan.After(committed.worldRevision() + 1,
                    committed.historySequence() + 1, afterSource.sourceHash(), sourceRef, checkpointSlot);
            var plan = new FiniteFracturePlan(domain, requestId, actor, actorProvenance,
                    basis, after, FiniteSource.sha256(requestFrame), FiniteSource.sha256(wireFrame),
                    prepared.token(), prepared.steps(), prepared.flags(), prepared.remainingBlocks(),
                    prepared.end(), prepared.corotFlags(), prepared.pending(), prepared.fullyConverged(),
                    prepared.remainingGeneration(), prepared.cells().size(),
                    prepared.retiredOwnership(), prepared.bornBodies(),
                    prepared.loadLedger(), prepared.archiveRecords());
            return new Staged(plan, source, afterSource, requestFrame, wireFrame, session, prepared);
        } catch (IOException | RuntimeException failure) {
            session.close();
            throw failure;
        }
    }

    /** Build the {@code CommitFracture} journal intent for a staged proposal. */
    public FiniteRegistry.Prepared prepareFracture(com.blockreality.core.transaction.ConstructionTransaction.Request request,
                                                   FiniteDomainId domain, Staged staged) {
        Objects.requireNonNull(staged);
        return registry.prepareCommitFracture(request, domain, staged.plan(),
                staged.requestFrame(), staged.wireFrame());
    }
}
