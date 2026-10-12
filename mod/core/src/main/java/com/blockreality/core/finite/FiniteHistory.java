package com.blockreality.core.finite;

import com.blockreality.core.bsi.BsiCorot;
import com.blockreality.core.transaction.ConstructionTransaction.Request;
import com.blockreality.core.transaction.FileTransactionJournal;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * The W3 history-commit orchestrator (design §4.3/§7): it turns "run solve or one arc
 * step and keep the result" into a durable, journal-committed history entry without ever
 * touching the authority session.
 *
 * <p>{@link #stage} does all native work on a <em>private</em> session: replay the
 * committed vocabulary/world and checkpoint into a separate handle, run the requested
 * analysis, export the new checkpoint and publish it as an immutable object. The returned
 * {@link Staged} is a proposal — nothing is committed, nothing in the authority has moved.
 * {@link #prepareHistory} then builds the {@code CommitHistory} journal intent whose
 * {@code finite/root/<bsiDomain>} before-image is the committed root (the basis CAS), so a
 * proposal built against a stale root can never be applied. The caller commits through the
 * construction journal and calls {@link FiniteRegistry#applyCommitted}; only then does any
 * authority state change.
 *
 * <p>Failure anywhere leaves evidence, not damage: the private session dies with its lane,
 * an already-published object is inert content-addressed garbage the registry never names,
 * and the committed root is untouched.
 */
public final class FiniteHistory {

    private final FiniteStore store;
    private final FiniteRegistry registry;

    public FiniteHistory(FiniteStore store, FiniteRegistry registry) {
        this.store = Objects.requireNonNull(store);
        this.registry = Objects.requireNonNull(registry);
    }

    /**
     * A fully staged history proposal. {@code session} is the private handle that produced
     * the result and exported checkpoint — the caller may keep it to become the new
     * authority session after the commit lands, or close it.
     */
    public record Staged(FiniteResult result, FiniteBlobRef blob, FiniteRoot.Checkpoint slot,
                         FiniteRoot.Next next, FiniteSession session) {
        public Staged {
            Objects.requireNonNull(result); Objects.requireNonNull(blob);
            Objects.requireNonNull(slot); Objects.requireNonNull(next);
            Objects.requireNonNull(session);
        }
    }

    /**
     * Stage one history step against the domain's committed root: replay the durable
     * source into a private session, restore the committed checkpoint when one exists,
     * run {@code arc} (or a solve when {@code arc} is null — including the compulsory
     * solve first when an arc step has no committed state to start from), export a
     * checkpoint of {@code format} and publish it. Throws {@link IOException} on any
     * refusal, leaving committed authority untouched.
     *
     * @param sessionGeneration a fresh generation distinct from the authority session's
     * @param publishRequest    the request id owning the store staging directory
     */
    public Staged stage(FiniteLane lane, FiniteDomainId domain, BsiCorot.ArcOptions arc,
                        int format, long byteBudget, long allocationBudget, long elementBudget,
                        long sessionGeneration, UUID publishRequest,
                        String engineBuild, String contractSha256) throws IOException {
        Objects.requireNonNull(lane); Objects.requireNonNull(domain);
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
            // Arc steps start from converged state: with no committed history that means
            // solving first; with one, the import's token is the start.
            FiniteResult result;
            if (arc != null && committed.checkpoint() == null) {
                if (session.solve() == null)
                    throw new IOException("staging solve refused: " + session.refusal());
            }
            result = arc == null ? session.solve() : session.arcAdvance(arc);
            if (result == null)
                throw new IOException("staging analysis refused: " + session.refusal());
            var exported = session.exportCheckpoint(format, byteBudget, allocationBudget, elementBudget);
            if (exported == null)
                throw new IOException("staging export refused: " + session.refusal());
            if (exported.format() != format)
                throw new IOException("staging export format drifted");
            var blob = store.publish(publishRequest, FiniteCodec.KIND_CHECKPOINT, exported.blob());
            var slot = new FiniteRoot.Checkpoint(blob, format, engineBuild, contractSha256,
                    byteBudget, allocationBudget, elementBudget);
            var next = new FiniteRoot.Next(committed.worldRevision(), committed.historySequence() + 1,
                    committed.source(), slot);
            return new Staged(result, blob, slot, next, session);
        } catch (IOException | RuntimeException failure) {
            session.close();
            throw failure;
        }
    }

    /**
     * Build the {@code CommitHistory} journal intent for a staged proposal. The proposal's
     * {@code next} is re-derived and re-validated by the registry, so a staged value built
     * against a root that has since moved is refused at this point.
     */
    public FiniteRegistry.Prepared prepareHistory(Request request, FiniteDomainId domain, Staged staged) {
        Objects.requireNonNull(staged);
        return registry.prepareCommitHistory(request, domain, staged.next());
    }
}
