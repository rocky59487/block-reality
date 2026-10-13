package com.blockreality.core.finite;

import com.blockreality.core.bsi.BsiCorot;
import com.blockreality.core.bsi.BsiCorotFracture;
import com.blockreality.core.bsi.BsiFrame;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiRecords;
import com.blockreality.core.bsi.BsiHeaders;
import com.blockreality.core.bsi.BsiResponse;
import com.blockreality.core.bsi.BsiVocabulary;
import com.blockreality.core.engine.InProcessEngine;
import com.blockreality.core.json.JsonValue;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One finite BSI session over {@link InProcessEngine} (design §5 lifecycle):
 *
 * <pre>
 *   open → hello (engine.open) → vocab.declare → world.declare (identified)
 *        → corot.checkpoint.import (only with durable history) → corot.solve …
 * </pre>
 *
 * <p>The session is the Java-side ordering authority and nothing more. It never recomputes
 * mechanics, never invents a vocabulary mapping, and never promotes a checkpoint token to a
 * fracture token. Every step binds to the durable {@link FiniteSource}: the engine's fresh
 * vocabulary reply must reproduce the recorded mapping (ids may be reassigned only by the
 * engine, so table equality is the contract), the declared world must be the identical
 * source, and a checkpoint may only land on a world with no existing finite history.
 *
 * <p>A protocol violation disables the lane instead of continuing — the same refusal model
 * as the linear engine, because the cost model is the same (D-044).
 */
public final class FiniteSession implements AutoCloseable {

    public enum Step { NEW, VOCABULARY, WORLD, RESTORED, DISABLED }

    private final FiniteLane lane;
    private final FiniteDomainId domain;
    private FiniteSource source;
    private final long sessionGeneration;
    private Step step = Step.NEW;
    private BsiVocabulary vocabulary;
    private long worldRevision = -1;
    private String refusal = "";
    private long sequence;
    /**
     * The token of the newest accepted analysis (solve, import or arc). Arc can only start
     * from it, and any reply replaces it — an older token is stale and never valid again.
     */
    private BsiFracture.Token liveToken;
    private boolean liveConverged;
    /** The historySequence the live token corresponds to (the checkpoint basis on import). */
    private long historyBasis;
    /**
     * The in-memory corot fracture candidate — a strict receipt plus its binding, alive only
     * between {@link #fracturePrepare} and {@link #fractureFinish} on this session. It is
     * never durable authority: the durable record is the journal entry plus plan objects.
     */
    private BsiCorotFracture.Prepared fractureCandidate;
    /** The exact {@code bsi.corot.fracture.prepare} request frame bytes, kept as evidence. */
    private byte[] fractureRequestFrame;
    /** A finish ran on this session — further solve/arc/prepare are forbidden (doc §4.2). */
    private boolean fractureFinished;
    /** The candidate committed — checkpoint export stays legal without a live token. */
    private boolean fractureCommitted;

    private FiniteSession(FiniteLane lane, FiniteDomainId domain, FiniteSource source, long sessionGeneration) {
        this.lane = Objects.requireNonNull(lane);
        this.domain = Objects.requireNonNull(domain);
        this.source = Objects.requireNonNull(source);
        this.sessionGeneration = sessionGeneration;
    }

    /**
     * Attach a finite lane to an already-handshaken engine. {@code sessionGeneration} is the
     * host's revocation domain — live only, never written to disk.
     */
    public static FiniteSession attach(InProcessEngine engine, FiniteDomainId domain,
                                       FiniteSource source, long sessionGeneration) {
        return attach(FiniteLane.inProcess(engine), domain, source, sessionGeneration);
    }

    /** The lane-injecting form used by scripted native peers. */
    public static FiniteSession attach(FiniteLane lane, FiniteDomainId domain,
                                       FiniteSource source, long sessionGeneration) {
        Objects.requireNonNull(lane); Objects.requireNonNull(domain); Objects.requireNonNull(source);
        if (!lane.ready() || sessionGeneration < 0)
            throw new IllegalArgumentException("finite session requires a ready lane");
        return new FiniteSession(lane, domain, source, sessionGeneration);
    }

    public Step step() { return step; }
    public String refusal() { return refusal; }
    public FiniteDomainId domain() { return domain; }
    public FiniteSource source() { return source; }
    public BsiVocabulary vocabulary() { return vocabulary; }
    public long worldRevision() { return worldRevision; }
    public long sessionGeneration() { return sessionGeneration; }
    /** The historySequence of the state the live token refers to. */
    public long historySequence() { return historyBasis; }

    /**
     * Step 3 of the lifecycle: replay the durable vocabulary body and require the engine's
     * fresh reply to reproduce the recorded mapping exactly.
     */
    public boolean declareVocabulary() {
        if (!ready(Step.NEW)) return false;
        var requestText = source.vocabRequestJson();
        int version = (int) JsonValue.parseStrict(requestText).exactI64("version");
        String id = nextId();
        var response = lane.request(BsiHeaders.vocabDeclare(id, 0, requestText), null);
        try {
            var reply = BsiVocabulary.decodeReply(response, id, 0, version);
            var stored = BsiVocabulary.decodeStored(JsonValue.parseStrict(source.vocabReplyJson()), version);
            if (!reply.vocabulary().sameMapping(stored)) throw new IllegalArgumentException("vocabulary mapping drift");
            if (!lane.adoptVocabulary(reply.vocabulary())) throw new IllegalArgumentException("vocabulary adoption refused");
            vocabulary = reply.vocabulary();
            step = Step.VOCABULARY;
            return true;
        } catch (IllegalArgumentException failure) {
            refuse(failure.getMessage());
            return false;
        }
    }

    /**
     * Step 3 variant for a lane that already declared this exact durable vocabulary —
     * a resync must not send a second {@code bsi.vocab.declare} to an engine session
     * that already holds one (the contract refuses it). The stored durable reply is
     * adopted directly: its mapping was proven by the original declare on this lane.
     */
    public boolean adoptStoredVocabulary() {
        if (!ready(Step.NEW)) return false;
        try {
            int version = (int) JsonValue.parseStrict(source.vocabRequestJson()).exactI64("version");
            var stored = BsiVocabulary.decodeStored(JsonValue.parseStrict(source.vocabReplyJson()), version);
            if (!lane.adoptVocabulary(stored)) throw new IllegalArgumentException("vocabulary adoption refused");
            vocabulary = stored;
            step = Step.VOCABULARY;
            return true;
        } catch (IllegalArgumentException failure) {
            refuse(failure.getMessage());
            return false;
        }
    }

    /**
     * Step 4: declare the identical identified world — same vocabulary, same blocks, same
     * owners — so a later checkpoint import meets the contract's identical-world rule.
     */
    public boolean declareWorld(long worldRevision) {
        if (!ready(Step.VOCABULARY)) return false;
        if (worldRevision < 0) { refuse("negative world revision"); return false; }
        var world = new BsiFracture.World(new BsiFracture.Stamp(domain.bsiDomain(), worldRevision),
                domain.artifactNamespace(), source.blocks(), source.owners());
        if (!lane.declareFiniteWorld(world)) { refuse("finite world declaration refused"); return false; }
        this.worldRevision = worldRevision;
        step = Step.WORLD;
        return true;
    }

    /**
     * Step 5 (optional): import durable history. Allowed exactly once, only on a world with
     * no existing finite history, and only when the checkpoint's durable basis names this
     * identical source — that is the Java side of the identical-world-bytes rule.
     */
    public FiniteResult importCheckpoint(FiniteCheckpointRef ref, byte[] blob) {
        if (!ready(Step.WORLD)) return null;
        Objects.requireNonNull(ref); Objects.requireNonNull(blob);
        try {
            if (!ref.basis().domain().equals(domain)
                    || ref.basis().worldRevision() != worldRevision
                    || !ref.basis().sourceHash().equals(source.sourceHash())
                    || !ref.basis().ownersHash().equals(source.ownersHash())
                    || !ref.basis().vocabHash().equals(source.vocabHash()))
                throw new IllegalArgumentException("checkpoint basis does not name this world");
            String id = nextId();
            var response = lane.request(BsiCorot.checkpointImport(id, worldRevision,
                    domain.bsiDomain(), worldRevision, ref.format(), ref.byteBudget(),
                    ref.allocationBudget(), ref.elementBudget(), blob.length), blob);
            var identity = BsiCorot.checkAnalysis(response, id, "bsi.corot.checkpoint.import",
                    worldRevision, domain.bsiDomain(), worldRevision, domain.artifactNamespace());
            liveToken = identity.token();
            liveConverged = identity.fullyConverged();
            historyBasis = ref.basis().historySequence();
            step = Step.RESTORED;
            return result(identity, Double.NaN, null);
        } catch (IllegalArgumentException failure) {
            refuse(failure.getMessage());
            return null;
        }
    }

    /** The finite solve for the declared world and this source's complete input payload. */
    public FiniteResult solve() {
        if (step != Step.WORLD && step != Step.RESTORED) { refuse("solve out of order at " + step); return null; }
        if (fractureFinished || step == Step.DISABLED) { seal("solve after fracture finish"); return null; }
        try {
            var options = BsiCorot.options(source.options());
            if (!options.expectedDomain().equals(domain.bsiDomain())
                    || options.expectedRevision() != worldRevision)
                throw new IllegalArgumentException("options name a different world stamp");
            String id = nextId();
            var response = lane.request(BsiCorot.solve(id, worldRevision, source.options(),
                            source.materials().length / BsiCorot.MATERIAL_BYTES,
                            source.cellLoads().length / 64,
                            source.prescribed().length / BsiCorot.PRESCRIBED_BYTES,
                            source.loads().length / BsiCorot.LOAD_BYTES,
                            source.shellLaws().length / BsiCorot.SHELL_LAW_BYTES,
                            source.shellLayers().length / BsiCorot.SHELL_LAYER_BYTES),
                    source.corotPayload());
            var identity = BsiCorot.checkAnalysis(response, id, "bsi.corot.solve",
                    worldRevision, domain.bsiDomain(), worldRevision, domain.artifactNamespace());
            liveToken = identity.token();
            liveConverged = identity.fullyConverged();
            step = Step.RESTORED;
            return result(identity, Double.NaN, null);
        } catch (IllegalArgumentException failure) {
            refuse(failure.getMessage());
            return null;
        }
    }

    /**
     * One static arc-continuation step (design §7): the wire body carries no binary payload,
     * starts from the live fully-converged token, and mints a new one. A world whose
     * declared {@code prescribed} carries any nonzero value is a prescribed-displacement
     * path and is refused before the wire — those paths belong to solve.
     */
    public FiniteResult arcAdvance(BsiCorot.ArcOptions options) {
        Objects.requireNonNull(options);
        if (fractureFinished) { seal("arc after fracture finish"); return null; }
        if (step != Step.RESTORED || liveToken == null) {
            refuse("arc out of order at " + step);
            return null;
        }
        try {
            if (!liveConverged) throw new IllegalArgumentException("arc requires a fully converged source");
            rejectPrescribedPaths();
            var token = liveToken;
            String id = nextId();
            var response = lane.request(BsiCorot.arcAdvance(id, worldRevision, domain.bsiDomain(),
                    worldRevision, token, options), new byte[0]);
            var advanced = BsiCorot.checkArc(response, id, worldRevision, domain.bsiDomain(),
                    worldRevision, domain.artifactNamespace(), corotWorld(), options, token);
            liveToken = advanced.token();
            liveConverged = true;
            var basis = new FiniteBasis(domain, worldRevision, historyBasis, source.sourceHash(),
                    source.vocabHash(), source.ownersHash(), sessionGeneration);
            var islands = advanced.islands().stream()
                    .map(i -> new FiniteResult.Island(i.index(), i.status(), i.flags(), i.reason())).toList();
            return new FiniteResult(basis, true, islands, advanced.sections(), advanced.payload(),
                    advanced.loadFactor(), domain.artifactNamespace(), advanced.generation(),
                    advanced.token(), Map.of(),
                    new FiniteResult.ArcStep(advanced.radius(), advanced.constraintResidual()),
                    advanced.availability());
        } catch (IllegalArgumentException failure) {
            refuse(failure.getMessage());
            return null;
        }
    }

    /**
     * The private {@code bsi.corot.fracture.prepare} (FINITE_FRACTURE_TRANSACTION §4.1):
     * only on a declared world or restored history, with no live candidate and no previous
     * finish. Capabilities are proved first — a missing one refuses before the wire. The
     * reply goes through {@link BsiCorotFracture#checkPrepared} in full; any refusal or
     * malformed success poisons this session. The returned candidate is memory-only.
     */
    public BsiCorotFracture.Prepared fracturePrepare(UUID requestId,
                                                   List<BsiCorotFracture.Rule> rules,
                                                   int budget) {
        Objects.requireNonNull(requestId); Objects.requireNonNull(rules);
        if (fractureFinished || fractureCandidate != null) {
            seal("fracture prepare after a fracture decision");
            return null;
        }
        if (step != Step.WORLD && step != Step.RESTORED) {
            refuse("fracture prepare out of order at " + step);
            return null;
        }
        try {
            for (String capability : List.of("bsi.corot", "bsi.corot.fracture", "bsi.fracture"))
                if (!lane.has(capability)) throw new IllegalArgumentException("missing capability " + capability);
            if (source.loads().length > 0 && !lane.has("bsi.corot.fracture.loads"))
                throw new IllegalArgumentException("missing capability bsi.corot.fracture.loads");
            if (source.hasShellMaterials() && !lane.has("bsi.corot.shell.materials"))
                throw new IllegalArgumentException("missing capability bsi.corot.shell.materials");
            if (requestId.equals(new UUID(0, 0)) || budget < 0 || budget > BsiCorotFracture.MAX_EVENTS)
                throw new IllegalArgumentException("invalid fracture request identity or budget");
            var roles = source.materialRoles();
            for (var rule : rules)
                if (!roles.containsKey(rule.material()))
                    throw new IllegalArgumentException("fracture rule names an unknown material");
            var options = BsiCorot.options(source.options());
            if (!options.expectedDomain().equals(domain.bsiDomain())
                    || options.expectedRevision() != worldRevision)
                throw new IllegalArgumentException("options name a different world stamp");
            // The prepare frame's id is the request-bound deterministic one: the durable
            // requestFrame must be re-derivable without this session's local sequence.
            String id = BsiCorotFracture.prepareId(requestId);
            byte[] ruleBytes = BsiCorotFracture.encodeRules(rules);
            String header = BsiCorotFracture.prepare(id, worldRevision, source.options(),
                    source.materials().length / BsiCorot.MATERIAL_BYTES,
                    source.cellLoads().length / 64,
                    source.prescribed().length / BsiCorot.PRESCRIBED_BYTES,
                    source.loads().length / BsiCorot.LOAD_BYTES,
                    requestId, rules.size(), budget,
                    source.shellLaws().length / BsiCorot.SHELL_LAW_BYTES,
                    source.shellLayers().length / BsiCorot.SHELL_LAYER_BYTES);
            byte[] payload = source.fracturePayload(ruleBytes);
            var response = lane.request(header, payload);
            var prepared = BsiCorotFracture.checkPrepared(response, id, worldRevision,
                    domain.bsiDomain(), worldRevision, domain.artifactNamespace(),
                    fractureWorld(), requestId, budget);
            fractureCandidate = prepared;
            fractureRequestFrame = BsiFrame.encode(header, payload);
            return prepared;
        } catch (IllegalArgumentException failure) {
            refuse(failure.getMessage());
            return null;
        }
    }

    /**
     * {@code bsi.fracture.finish} on the live candidate (§4.2): strict echo through
     * {@link BsiCorotFracture#checkFinish}. On commit the session's source becomes the
     * receipt's remaining world ({@code afterSource} must match it byte for byte — blocks,
     * owners and the receipt's options record), the world revision becomes the after
     * revision, the live analysis token clears and the step stays {@code RESTORED} so a
     * checkpoint export can capture the after state. On discard the world is untouched.
     * Either way the session is sealed: no further prepare/solve/arc ever runs on it.
     */
    public BsiFracture.Finish fractureFinish(BsiCorotFracture.Prepared candidate,
                                             boolean commit, FiniteSource afterSource) {
        Objects.requireNonNull(candidate);
        if (fractureFinished) {
            seal("fracture finish after a fracture decision");
            return null;
        }
        if (candidate != fractureCandidate || step == Step.DISABLED) {
            refuse("fracture finish without the live candidate at " + step);
            return null;
        }
        try {
            if (commit) {
                Objects.requireNonNull(afterSource);
                if (!Arrays.equals(afterSource.blocks40(),
                        BsiRecords.encodeBlocks(candidate.remaining()))
                        || !Arrays.equals(afterSource.owners20(), ownersImage(candidate.remainingOwners()))
                        || !Arrays.equals(afterSource.options(), candidate.afterOptions()))
                    throw new IllegalArgumentException("after source is not the receipt's remaining world");
            }
            String id = nextId();
            var response = lane.request(
                    BsiCorotFracture.finish(id, worldRevision, candidate, commit), new byte[0]);
            var finish = BsiCorotFracture.checkFinish(response, id, worldRevision, candidate, commit);
            if (commit && finish != BsiFracture.Finish.DISCARDED) {
                this.source = afterSource;
                worldRevision = candidate.after().revision();
                liveToken = null;
                fractureCommitted = true;
                step = Step.RESTORED;
            }
            fractureCandidate = null;
            fractureFinished = true;
            return finish;
        } catch (IllegalArgumentException failure) {
            refuse(failure.getMessage());
            return null;
        }
    }

    /** The current in-memory fracture candidate, or null. Never durable authority. */
    public BsiCorotFracture.Prepared fractureCandidate() { return fractureCandidate; }
    /** The exact request frame the accepted prepare sent, or null. Evidence only. */
    public byte[] fractureRequestFrame() {
        return fractureRequestFrame == null ? null : fractureRequestFrame.clone();
    }

    private static byte[] ownersImage(List<BsiFracture.Owner> owners) {
        var out = java.nio.ByteBuffer.allocate(owners.size() * FiniteSource.OWNER_BYTES)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (var owner : owners)
            out.putInt(owner.position().x()).putInt(owner.position().y())
                    .putInt(owner.position().z()).putLong(owner.artifact());
        return out.array();
    }

    /** The declared world as the fracture receipt validator needs it (doc §1.2/§3). */
    private BsiCorotFracture.FractureWorld fractureWorld() {
        return new BsiCorotFracture.FractureWorld(source.blocks(), source.owners(), vocabulary,
                defaultSections(), source.materialRoles(), source.cellLoads(), source.shellLaws());
    }

    /**
     * The opaque checkpoint export of the live analysis (design §4.3): format 1 for pure
     * solve state, format ≥2 once arc direction history exists. The engine arbitrates;
     * Java carries the blob untouched.
     */
    public BsiCorot.Exported exportCheckpoint(int format, long byteBudget,
                                              long allocationBudget, long elementBudget) {
        if (step != Step.RESTORED || (liveToken == null && !fractureCommitted)) {
            refuse("checkpoint export out of order at " + step);
            return null;
        }
        try {
            String id = nextId();
            var response = lane.request(BsiCorot.checkpointExport(id, worldRevision,
                    domain.bsiDomain(), worldRevision, format, byteBudget, allocationBudget,
                    elementBudget), new byte[0]);
            return BsiCorot.checkExport(response, id, worldRevision, domain.bsiDomain(), worldRevision);
        } catch (IllegalArgumentException failure) {
            refuse(failure.getMessage());
            return null;
        }
    }

    /** Any nonzero prescribed displacement makes this world a solve-only path (§7). */
    private void rejectPrescribedPaths() {
        var prescribed = source.prescribed();
        var data = java.nio.ByteBuffer.wrap(prescribed).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        // bsi_corot_prescribed: int32 node[4] then double value[6] — the values start at +16.
        for (int at = 0; at + BsiCorot.PRESCRIBED_BYTES <= prescribed.length; at += BsiCorot.PRESCRIBED_BYTES)
            for (int k = 0; k < 6; k++)
                if (data.getDouble(at + 16 + 8 * k) != 0)
                    throw new IllegalArgumentException("arc refuses a nonzero prescribed path");
    }

    /** The declared world as the strict validator needs it (design §7, doc §3). */
    private BsiCorot.CorotWorld corotWorld() {
        return new BsiCorot.CorotWorld(source.blocks(), source.owners(), vocabulary,
                defaultSections(), source.hasShellMaterials());
    }

    /**
     * Each material's vocabulary-declared default section, as material id → section id.
     * The request's {@code materials[].defaultSection} names a section row; supports and
     * materials without one simply do not appear.
     */
    private Map<Integer, Integer> defaultSections() {
        var out = new java.util.HashMap<Integer, Integer>();
        var request = JsonValue.parseStrict(source.vocabRequestJson());
        for (JsonValue material : request.arr("materials")) {
            var name = material.str("name", "");
            var section = material.str("defaultSection", "");
            if (name.isEmpty() || section.isEmpty()) continue;
            try {
                out.put(vocabulary.materialId(name), vocabulary.sectionId(section));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("vocabulary default section drift", unknown);
            }
        }
        return out;
    }

    private FiniteResult result(BsiCorot.Identity identity, double loadFactor,
                                FiniteResult.ArcStep arc) {
        var basis = new FiniteBasis(domain, worldRevision, historyBasis, source.sourceHash(),
                source.vocabHash(), source.ownersHash(), sessionGeneration);
        var islands = identity.islands().stream()
                .map(i -> new FiniteResult.Island(i.index(), i.status(), i.flags(), i.reason())).toList();
        return new FiniteResult(basis, identity.fullyConverged(), islands,
                identity.sections(), identity.payload(), loadFactor,
                domain.artifactNamespace(), identity.generation(), identity.token(), Map.of(),
                arc, BsiCorot.availability(identity.sections(), identity.payload()));
    }

    private boolean ready(Step expected) {
        if (step == Step.DISABLED || step == expected) return step == expected;
        refuse("finite session order violated: expected " + expected + ", at " + step);
        return false;
    }

    private void refuse(String detail) {
        step = Step.DISABLED;
        refusal = detail == null ? "" : detail;
    }

    /**
     * Post-decision sealing is not a protocol violation: work after the fracture
     * decision is refused in place, but the committed after-state stays exportable
     * (doc §4.4 — the session is sealed, not poisoned).
     */
    private void seal(String detail) {
        refusal = detail == null ? "" : detail;
    }

    private String nextId() { return "f" + (++sequence); }

    @Override public void close() {
        step = Step.DISABLED;
    }
}
