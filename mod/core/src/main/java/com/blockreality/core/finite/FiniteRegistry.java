package com.blockreality.core.finite;

import com.blockreality.core.finite.FiniteCodec.Operation;
import com.blockreality.core.transaction.ConstructionTransaction.Change;
import com.blockreality.core.transaction.ConstructionTransaction.Entry;
import com.blockreality.core.transaction.ConstructionTransaction.Intent;
import com.blockreality.core.transaction.ConstructionTransaction.Phase;
import com.blockreality.core.transaction.ConstructionTransaction.Request;
import com.blockreality.core.transaction.ConstructionTransaction.Value;
import com.blockreality.core.transaction.FileTransactionJournal;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The journal-backed authority for finite domains, artifact→owner bindings and root
 * descriptors (design §4.2). It owns no files and decides nothing on its own: every
 * mutation is a versioned construction-journal transaction over {@code finite/*}
 * resources, and every recovery is the same fold of committed entries.
 *
 * <p>Resource keys:
 *
 * <ul>
 *   <li>{@code finite/order} — the total order of finite operations (monotonic, gapless);
 *   <li>{@code finite/tx/<requestId>} — the versioned operation descriptor;
 *   <li>{@code finite/domain/<dimensionHash>} — the domain descriptor (identity, allocator,
 *       observation clock);
 *   <li>{@code finite/owner/<namespace>/<id>} — one {@link ArtifactBinding};
 *   <li>{@code finite/root/<bsiDomain>} — the current root descriptor; its
 *       {@code prevRootHash} chains the whole committed history.
 * </ul>
 *
 * <p>Store references are verified, never trusted: a commit that names an object the store
 * cannot prove is refused, and a fold that cannot find a referenced blob fails closed.
 * Owner ids are minted strictly from {@code nextOwnerId}, monotonic and never reused —
 * including after retirement or restart. Nothing here computes mechanics or merges
 * adjacent pieces: each manufactured piece keeps its own binding however it touches others.
 */
public final class FiniteRegistry {

    private static final String ORDER_KEY = "finite/order";
    private static final int MAX_OWNERS = 1 << 20, MAX_DOMAINS = 4096;

    private final UUID journalDomain;
    private final FiniteStore store;
    private final Map<String, FiniteDomain> domains = new HashMap<>();          // dimHash -> domain
    private final Map<UUID, String> dimensionByBsi = new HashMap<>();           // bsiDomain -> dimension
    private final Map<UUID, String> dimensionByNs = new HashMap<>();            // artifactNamespace -> dimension
    private final Map<UUID, Map<Long, ArtifactBinding>> bindingsByNs = new HashMap<>();
    private int totalBindings;
    private final Map<UUID, FiniteRoot> roots = new HashMap<>();                // bsiDomain -> root
    private final Map<String, FiniteSource> sourceCache = new HashMap<>();      // sourceHash -> source
    private long order;
    private boolean failed;

    private FiniteRegistry(UUID journalDomain, FiniteStore store) {
        this.journalDomain = Objects.requireNonNull(journalDomain);
        this.store = Objects.requireNonNull(store);
    }

    // ---------------------------------------------------------------- keys

    public static String domainKey(String dimensionHash) {
        FiniteBasis.hash(dimensionHash);
        return "finite/domain/" + dimensionHash;
    }

    public static String ownerKey(UUID artifactNamespace, long ownerId) {
        FiniteDomainId.nonzero(artifactNamespace);
        if (ownerId <= 0) throw invalid();
        return "finite/owner/" + FiniteCodec.BsiHex(artifactNamespace) + "/" + "%016x".formatted(ownerId);
    }

    public static String rootKey(UUID bsiDomain) {
        FiniteDomainId.nonzero(bsiDomain);
        return "finite/root/" + FiniteCodec.BsiHex(bsiDomain);
    }

    public static String txKey(UUID requestId) {
        FiniteDomainId.nonzero(requestId);
        return "finite/tx/" + FiniteCodec.BsiHex(requestId);
    }

    public static String dimensionHash(String dimension) {
        FiniteDomainId.dimension(dimension);
        return FiniteSource.sha256(dimension.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The request planHash that binds a finite operation descriptor: SHA-256 of its encoding. */
    public static String planHash(Operation op) { return FiniteSource.sha256(FiniteCodec.encodeOperation(op)); }

    // ---------------------------------------------------------------- recovery fold

    /**
     * Rebuild the finite authority from the committed journal. Rejected and aborted
     * entries confer nothing; prepared entries are ignored until the coordinator decides
     * them (the host re-validates a pending entry through {@link #validatePrepared}).
     */
    public static FiniteRegistry load(FileTransactionJournal journal, FiniteStore store) throws IOException {
        Objects.requireNonNull(journal); Objects.requireNonNull(store);
        synchronized (journal) {
            var registry = new FiniteRegistry(journal.domain(), store);
            var sequence = new TreeMap<Long, UUID>();
            try {
                for (UUID id : journal.entryIds()) {
                    Entry entry = journal.read(id).orElseThrow(() -> new IOException("Finite record disappeared"));
                    if (entry.phase() == Phase.REJECTED || entry.phase() == Phase.ABORTED) continue;
                    var tx = finiteTx(entry);
                    if (tx == null) continue;                       // not a finite transaction
                    if (entry.phase() == Phase.PREPARED) continue;  // provisional; never publishes
                    if (sequence.putIfAbsent(tx.order(), id) != null) throw invalid();
                }
                for (var step : sequence.entrySet()) {
                    Entry entry = journal.verify(step.getValue()).orElseThrow(() -> new IOException("Finite record disappeared"));
                    if (entry.phase() != Phase.COMMITTED) throw invalid();
                    registry.accept(entry, finiteTx(entry));
                }
                return registry;
            } catch (RuntimeException malformed) {
                throw new IOException("Finite history is inconsistent", malformed);
            }
        }
    }

    private record FoldedTx(long order, Operation op) { }

    /** The {@code finite/order} + {@code finite/tx/<request>} pair, or null for non-finite intents. */
    private static FoldedTx finiteTx(Entry entry) {
        Intent intent = entry.intent();
        if (intent == null) return null;
        Value orderValue = null, descriptor = null;
        boolean touchesFinite = false;
        for (Change change : intent.changes()) {
            if (change.resource().equals(ORDER_KEY)) orderValue = change.after();
            else if (change.resource().equals(txKey(entry.request().id()))) descriptor = change.after();
            else if (change.resource().startsWith("finite/")) touchesFinite = true;
        }
        if (orderValue == null && descriptor == null && !touchesFinite) return null;
        if (orderValue == null || descriptor == null || !descriptor.present()) throw invalid();
        long order = number(orderValue);
        return new FoldedTx(order, FiniteCodec.decodeOperation(descriptor.bytes()));
    }

    private static long number(Value value) {
        if (value == null || !value.present() || value.bytes().length != 8) throw invalid();
        var buffer = java.nio.ByteBuffer.wrap(value.bytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        long n = buffer.getLong();
        if (n < 0) throw invalid();
        return n;
    }

    static Value numberValue(long value) {
        if (value < 0) throw invalid();
        return Value.of(java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(value).array());
    }

    private static UUID hexUuid(String hex32) {
        if (hex32 == null || !hex32.matches("[0-9a-f]{32}")) throw invalid();
        var buffer = java.nio.ByteBuffer.wrap(java.util.HexFormat.of().parseHex(hex32));
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static UUID namespaceOf(String ownerKey) {
        return hexUuid(ownerKey.substring("finite/owner/".length(), "finite/owner/".length() + 32));
    }

    // ---------------------------------------------------------------- validation + accept

    /**
     * Validate a committed (or pending-validated) finite intent against current authority,
     * then publish it. Any inconsistency poisons this instance — reconstruction is the only
     * honest next step.
     */
    private void accept(Entry entry, FoldedTx tx) {
        Set<String> allowed = validate(entry, tx);
        failed = true; // a VM/allocation failure during publication leaves this instance unusable
        for (Change change : entry.intent().changes()) {
            var key = change.resource();
            if (!allowed.contains(key)) continue;
            var after = change.after();
            if (key.startsWith("finite/domain/")) {
                var domain = FiniteCodec.decodeDomain(after.bytes());
                domains.put(key.substring("finite/domain/".length()), domain);
                dimensionByBsi.put(domain.id().bsiDomain(), domain.id().dimension());
                dimensionByNs.put(domain.id().artifactNamespace(), domain.id().dimension());
            } else if (key.startsWith("finite/owner/")) {
                var binding = FiniteCodec.decodeBinding(after.bytes());
                var ns = namespaceOf(key);
                if (bindingsByNs.computeIfAbsent(ns, n -> new HashMap<>())
                        .put(binding.ownerId(), binding) == null) totalBindings++;
            } else if (key.startsWith("finite/root/")) {
                var root = FiniteCodec.decodeRoot(after.bytes());
                roots.put(hexUuid(key.substring("finite/root/".length())), root);
            }
        }
        order = tx.order();
        failed = false;
    }

    /**
     * Re-derive the entire finite change set the descriptor demands and require the intent to
     * carry exactly it. Non-finite participant images (world/chunk changes added by the
     * coordinator) are ignored by this authority and never rewritten. Returns the set of
     * finite resource keys this operation is allowed to touch; anything else under
     * {@code finite/} refuses the whole intent — unknown finite schemas fail closed.
     */
    private Set<String> validate(Entry entry, FoldedTx tx) {
        if (tx.order() != order + 1) throw invalid();
        if (!journalDomain.equals(entry.request().domain())) throw invalid();
        var byResource = new HashMap<String, Change>();
        for (Change change : entry.intent().changes()) {
            if (change.resource().startsWith("finite/")
                    && byResource.put(change.resource(), change) != null) throw invalid();
        }
        var orderChange = byResource.get(ORDER_KEY);
        if (orderChange == null || !orderChange.before().present()
                || number(orderChange.before()) != order || number(orderChange.after()) != tx.order()) throw invalid();
        var descriptor = byResource.get(txKey(entry.request().id()));
        if (descriptor == null || descriptor.before().present() || !descriptor.after().present()
                || !java.util.Arrays.equals(descriptor.after().bytes(), FiniteCodec.encodeOperation(tx.op())))
            throw invalid();
        var allowed = new HashSet<String>();
        allowed.add(ORDER_KEY);
        allowed.add(txKey(entry.request().id()));
        var op = tx.op();
        if (op instanceof Operation.CreateDomain create) validateCreate(byResource, allowed, create, entry.request().id());
        else if (op instanceof Operation.Bind bind) validateBind(byResource, allowed, bind, entry.request().id());
        else if (op instanceof Operation.Retire retire) validateRetire(byResource, allowed, retire, entry.request().id());
        else if (op instanceof Operation.Observe) validateObserve(byResource, allowed);
        else if (op instanceof Operation.CommitRoot commit) validateCommit(byResource, allowed, commit.next(), entry.request().id(), false);
        else if (op instanceof Operation.CommitHistory history) validateCommit(byResource, allowed, history.next(), entry.request().id(), true);
        else throw invalid();
        for (String key : byResource.keySet())
            if (!allowed.contains(key)) throw invalid();  // unexpected finite resource — fail closed
        return allowed;
    }

    private FiniteDomain decodeDomainChange(Map<String, Change> byResource, Set<String> allowed, String dimHash) {
        var change = byResource.get(domainKey(dimHash));
        if (change == null || !change.after().present()) throw invalid();
        var domain = FiniteCodec.decodeDomain(change.after().bytes());
        if (!journalDomain.equals(domain.id().journalDomain())) throw invalid();
        allowed.add(domainKey(dimHash));
        return domain;
    }

    private void validateCreate(Map<String, Change> byResource, Set<String> allowed,
                                Operation.CreateDomain create, UUID requestId) {
        var dimHash = dimensionHash(create.dimension());
        var change = byResource.get(domainKey(dimHash));
        if (change == null || change.before().present() || domains.containsKey(dimHash)) throw invalid();
        var expected = FiniteDomain.create(expectedIdentity(create.dimension(), requestId), requestId);
        if (!expected.equals(FiniteCodec.decodeDomain(change.after().bytes()))) throw invalid();
        if (domains.size() + 1 > MAX_DOMAINS) throw invalid();
        allowed.add(domainKey(dimHash));
    }

    private void validateBind(Map<String, Change> byResource, Set<String> allowed,
                              Operation.Bind bind, UUID requestId) {
        String dimHash = null;
        for (var e : byResource.entrySet()) {
            if (e.getKey().startsWith("finite/domain/")) {
                if (dimHash != null) throw invalid();
                dimHash = e.getKey().substring("finite/domain/".length());
            }
        }
        if (dimHash == null) throw invalid();
        var before = domains.get(dimHash);
        if (before == null) throw invalid();
        var after = decodeDomainChange(byResource, allowed, dimHash);
        var plans = bind.plans();
        if (after.nextOwnerId() != before.nextOwnerId() + plans.size()
                || after.observationEpoch() != before.observationEpoch()
                || !after.id().equals(before.id()) || !after.createdBy().equals(before.createdBy())) throw invalid();
        var ns = after.id().artifactNamespace();
        var existing = bindingsByNs.getOrDefault(ns, Map.of());
        var claimed = new HashSet<com.blockreality.api.geom.BlockKey>();
        var seenPieces = new HashSet<UUID>();
        var seenLegacy = new HashSet<String>();
        var priorCells = new HashSet<com.blockreality.api.geom.BlockKey>();
        var priorPieces = new HashSet<UUID>();
        var priorLegacy = new HashSet<String>();
        for (var prior : existing.values()) {
            if (prior.active()) priorCells.addAll(prior.cells());
            // Identity is forever: a piece id or legacy key may never be bound again,
            // even after the original binding retires (one-time adoption, no re-mint).
            if (prior.pieceId() != null) priorPieces.add(prior.pieceId());
            if (prior.legacyKey() != null) priorLegacy.add(prior.legacyKey());
        }
        long mint = before.nextOwnerId();
        for (int i = 0; i < plans.size(); i++) {
            var plan = plans.get(i);
            long ownerId = mint + i;
            var key = ownerKey(ns, ownerId);
            var change = byResource.get(key);
            if (change == null || change.before().present()) throw invalid();
            var binding = FiniteCodec.decodeBinding(change.after().bytes());
            var expected = new ArtifactBinding(ownerId, plan.origin(), plan.pieceId(), plan.legacyKey(),
                    requestId, plan.declaration(), plan.cells(), ArtifactBinding.Disposition.ACTIVE, null);
            if (!expected.equals(binding)) throw invalid();
            if (binding.pieceId() != null && (!seenPieces.add(binding.pieceId()) || priorPieces.contains(binding.pieceId()))) throw invalid();
            if (binding.legacyKey() != null && (!seenLegacy.add(binding.legacyKey()) || priorLegacy.contains(binding.legacyKey()))) throw invalid();
            for (var cell : binding.cells())
                if (!claimed.add(cell) || priorCells.contains(cell)) throw invalid(); // one owner per cell
            if (existing.containsKey(ownerId)) throw invalid();
            allowed.add(key);
        }
        if (totalBindings + plans.size() > MAX_OWNERS) throw invalid();
    }

    private void validateRetire(Map<String, Change> byResource, Set<String> allowed,
                                Operation.Retire retire, UUID requestId) {
        var byId = new HashMap<Long, String>();
        for (var e : byResource.entrySet()) {
            if (!e.getKey().startsWith("finite/owner/")) continue;
            var ns = namespaceOf(e.getKey());
            var dimension = dimensionByNs.get(ns);
            if (dimension == null) throw invalid();
            long candidateId = Long.parseLong(e.getKey().substring(e.getKey().length() - 16), 16);
            if (byId.put(candidateId, e.getKey()) != null) throw invalid();
        }
        for (long id : retire.ownerIds()) {
            var key = byId.get(id);
            if (key == null) throw invalid();
            var change = byResource.get(key);
            if (!change.before().present() || !change.after().present()) throw invalid();
            var before = FiniteCodec.decodeBinding(change.before().bytes());
            var after = FiniteCodec.decodeBinding(change.after().bytes());
            if (!after.equals(before.retired(requestId))) throw invalid();
            var committed = bindingsByNs.getOrDefault(namespaceOf(key), Map.of()).get(id);
            if (committed == null || !committed.equals(before) || !committed.active()) throw invalid();
            allowed.add(key);
        }
        if (byId.size() != retire.ownerIds().size()) throw invalid();
    }

    private void validateObserve(Map<String, Change> byResource, Set<String> allowed) {
        String dimHash = null;
        for (var e : byResource.entrySet()) {
            if (e.getKey().startsWith("finite/domain/")) {
                if (dimHash != null) throw invalid();
                dimHash = e.getKey().substring("finite/domain/".length());
            }
        }
        if (dimHash == null) throw invalid();
        var before = domains.get(dimHash);
        var after = decodeDomainChange(byResource, allowed, dimHash);
        if (before == null || !after.equals(before.observed())) throw invalid();
    }

    /**
     * One shared root-change validator for both commit ops; {@code history} selects the
     * W3 history-only policy (design §7):
     *
     * <ul>
     *   <li>{@code CommitRoot} — genesis ({@code revision==0, sequence==0}, no committed
     *       root) or a world boundary ({@code worldRevision+1, historySequence+1}).
     *   <li>{@code CommitHistory} — requires a committed root, keeps
     *       {@code worldRevision} unchanged, increments {@code historySequence} by exactly
     *       one, names the identical source and must carry a new checkpoint.
     * </ul>
     */
    private void validateCommit(Map<String, Change> byResource, Set<String> allowed,
                                FiniteRoot.Next next, UUID requestId, boolean history) {
        String key = null;
        UUID bsi = null;
        for (var e : byResource.entrySet()) {
            if (e.getKey().startsWith("finite/root/")) {
                if (key != null) throw invalid();
                key = e.getKey();
                bsi = hexUuid(e.getKey().substring("finite/root/".length()));
            }
        }
        if (key == null || bsi == null || !dimensionByBsi.containsKey(bsi)) throw invalid();
        var change = byResource.get(key);
        if (!change.after().present()) throw invalid();
        var committed = roots.get(bsi);
        var expected = new FiniteRoot(next.worldRevision(), next.historySequence(), next.source(), next.checkpoint(),
                committed == null ? FiniteRoot.GENESIS_HASH : FiniteSource.sha256(FiniteCodec.encodeRoot(committed)),
                requestId);
        var actual = FiniteCodec.decodeRoot(change.after().bytes());
        if (!expected.equals(actual)) throw invalid();
        if (committed == null) {
            if (history) throw invalid();                // history has no root to advance
            if (next.worldRevision() != 0 || next.historySequence() != 0) throw invalid();
        } else {
            if (next.historySequence() != committed.historySequence() + 1) throw invalid();
            boolean sameWorld = next.worldRevision() == committed.worldRevision();
            boolean nextWorld = next.worldRevision() == committed.worldRevision() + 1;
            if (history) {
                // History-only: identical world, identical source, mandatory checkpoint.
                if (!sameWorld || next.checkpoint() == null
                        || !next.source().equals(committed.source())) throw invalid();
            } else if (!nextWorld) throw invalid();      // same-revision work is CommitHistory
        }
        // The named objects must physically exist and decode before the authority moves.
        verifySourceRef(next.source());
        if (next.checkpoint() != null) verifyBlobRef(next.checkpoint().blob(), FiniteCodec.KIND_CHECKPOINT);
        if (change.before().present()) {
            var before = FiniteCodec.decodeRoot(change.before().bytes());
            if (!before.equals(committed)) throw invalid();
        } else if (committed != null) throw invalid();
        allowed.add(key);
    }

    private void verifySourceRef(FiniteBlobRef ref) {
        try {
            var body = store.read(ref);
            if (body.kind() != FiniteCodec.KIND_SOURCE) throw invalid();
            if (!sourceCache.containsKey(ref.sha256()))
                sourceCache.put(ref.sha256(), FiniteSource.decode(body.payload()));
        } catch (IOException e) {
            throw new IllegalArgumentException("Finite source object unavailable", e);
        }
    }

    private void verifyBlobRef(FiniteBlobRef ref, int kind) {
        try {
            if (store.read(ref).kind() != kind) throw invalid();
        } catch (IOException e) {
            throw new IllegalArgumentException("Finite object unavailable", e);
        }
    }

    // ---------------------------------------------------------------- prepare API

    /** A private finite proposal; the coordinator merges non-finite participants afterwards. */
    public record Prepared(Request request, Operation operation, List<Change> changes) {
        public Prepared { Objects.requireNonNull(request); Objects.requireNonNull(operation); changes = List.copyOf(changes); }
        public Intent withParticipants(List<Change> participants) {
            var all = new ArrayList<>(changes);
            all.addAll(participants);
            return new Intent(request, all, List.of(), List.of());
        }
        public Intent intent() { return withParticipants(List.of()); }
    }

    public synchronized Prepared prepareCreateDomain(Request request, String dimension) {
        healthy(); ready(request);
        var op = new Operation.CreateDomain(dimension);
        var identity = expectedIdentity(dimension, request.id());
        var domain = FiniteDomain.create(identity, request.id());
        var changes = base(request, op);
        changes.add(new Change(domainKey(dimensionHash(dimension)), Value.missing(),
                Value.of(FiniteCodec.encodeDomain(domain))));
        return prove(request, op, changes);
    }

    public synchronized Prepared prepareBind(Request request, FiniteDomainId domain, List<Operation.BindingPlan> plans) {
        healthy(); ready(request);
        var current = requireCurrent(domain);
        var op = new Operation.Bind(plans);
        var changes = base(request, op);
        changes.add(domainChange(current, current.allocated(plans.size())));
        long mint = current.nextOwnerId();
        var ns = domain.artifactNamespace();
        for (int i = 0; i < plans.size(); i++) {
            var plan = plans.get(i);
            var binding = new ArtifactBinding(mint + i, plan.origin(), plan.pieceId(), plan.legacyKey(),
                    request.id(), plan.declaration(), plan.cells(), ArtifactBinding.Disposition.ACTIVE, null);
            changes.add(new Change(ownerKey(ns, mint + i), Value.missing(),
                    Value.of(FiniteCodec.encodeBinding(binding))));
        }
        return prove(request, op, changes);
    }

    public synchronized Prepared prepareRetire(Request request, FiniteDomainId domain, java.util.Set<Long> ownerIds) {
        healthy(); ready(request);
        var current = requireCurrent(domain);
        var op = new Operation.Retire(ownerIds);
        var changes = base(request, op);
        var existing = bindingsByNs.getOrDefault(domain.artifactNamespace(), Map.of());
        for (long id : ownerIds) {
            var binding = existing.get(id);
            if (binding == null || !binding.active()) throw invalid();
            changes.add(new Change(ownerKey(domain.artifactNamespace(), id),
                    Value.of(FiniteCodec.encodeBinding(binding)),
                    Value.of(FiniteCodec.encodeBinding(binding.retired(request.id())))));
        }
        return prove(request, op, changes);
    }

    public synchronized Prepared prepareObserve(Request request, FiniteDomainId domain) {
        healthy(); ready(request);
        var current = requireCurrent(domain);
        var op = new Operation.Observe();
        var changes = base(request, op);
        changes.add(domainChange(current, current.observed()));
        return prove(request, op, changes);
    }

    /**
     * Propose a world-boundary root commit: the genesis commit ({@code revision 0,
     * sequence 0}) or {@code worldRevision+1}/{@code historySequence+1} with a possibly
     * new source. Same-revision history is {@link #prepareCommitHistory}, never this op.
     */
    public synchronized Prepared prepareCommitRoot(Request request, FiniteDomainId domain, FiniteRoot.Next next) {
        healthy(); ready(request);
        Objects.requireNonNull(next);
        var current = requireCurrent(domain);
        var op = new Operation.CommitRoot(next);
        var changes = base(request, op);
        // Re-derive via the same validator so the intent carries exactly the chained root.
        var committed = roots.get(domain.bsiDomain());
        var root = new FiniteRoot(next.worldRevision(), next.historySequence(), next.source(), next.checkpoint(),
                committed == null ? FiniteRoot.GENESIS_HASH : FiniteSource.sha256(FiniteCodec.encodeRoot(committed)),
                request.id());
        var key = rootKey(domain.bsiDomain());
        var before = committed == null ? Value.missing() : Value.of(FiniteCodec.encodeRoot(committed));
        changes.add(new Change(key, before, Value.of(FiniteCodec.encodeRoot(root))));
        return prove(request, op, changes);
    }

    /**
     * Propose a W3 history-only commit (design §7): {@code worldRevision} stays put,
     * {@code historySequence} advances by exactly one, the source is the committed one and
     * a new checkpoint slot is mandatory. The resulting {@code finite/root/<bsiDomain>}
     * before-image is the committed root — the basis CAS: a prepared intent that no longer
     * matches committed authority can never be applied.
     */
    public synchronized Prepared prepareCommitHistory(Request request, FiniteDomainId domain, FiniteRoot.Next next) {
        healthy(); ready(request);
        Objects.requireNonNull(next);
        var current = requireCurrent(domain);
        var op = new Operation.CommitHistory(next);
        var changes = base(request, op);
        var committed = roots.get(domain.bsiDomain());
        var root = new FiniteRoot(next.worldRevision(), next.historySequence(), next.source(), next.checkpoint(),
                committed == null ? FiniteRoot.GENESIS_HASH : FiniteSource.sha256(FiniteCodec.encodeRoot(committed)),
                request.id());
        var key = rootKey(domain.bsiDomain());
        var before = committed == null ? Value.missing() : Value.of(FiniteCodec.encodeRoot(committed));
        changes.add(new Change(key, before, Value.of(FiniteCodec.encodeRoot(root))));
        return prove(request, op, changes);
    }

    private Prepared prove(Request request, Operation op, List<Change> changes) {
        if (!request.planHash().equals(planHash(op))) throw invalid();
        var prepared = new Prepared(request, op, changes);
        // Re-run the full validator on the proposal: a prepare that cannot pass its own fold is a bug.
        validate(Entry.prepared(prepared.intent()), new FoldedTx(order + 1, op));
        return prepared;
    }

    private ArrayList<Change> base(Request request, Operation op) {
        var changes = new ArrayList<Change>();
        changes.add(new Change(ORDER_KEY, numberValue(order), numberValue(order + 1)));
        changes.add(new Change(txKey(request.id()), Value.missing(), Value.of(FiniteCodec.encodeOperation(op))));
        return changes;
    }

    private Change domainChange(FiniteDomain before, FiniteDomain after) {
        return new Change(domainKey(dimensionHash(before.id().dimension())),
                Value.of(FiniteCodec.encodeDomain(before)), Value.of(FiniteCodec.encodeDomain(after)));
    }

    /** Deterministic namespace derivation: replay of the same request mints the same identities. */
    private FiniteDomainId expectedIdentity(String dimension, UUID requestId) {
        var seed = journalDomain.toString() + "/" + dimension + "/" + requestId;
        var bsi = UUID.nameUUIDFromBytes((seed + "/bsi").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var ns = UUID.nameUUIDFromBytes((seed + "/artifacts").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new FiniteDomainId(journalDomain, dimension, bsi, ns);
    }

    private FiniteDomain requireCurrent(FiniteDomainId id) {
        var domain = domains.get(dimensionHash(id.dimension()));
        if (domain == null || !domain.id().equals(id)) throw invalid();
        return domain;
    }

    private void ready(Request request) {
        if (!journalDomain.equals(request.domain()) || order == Long.MAX_VALUE) throw invalid();
    }

    // ---------------------------------------------------------------- committed application + queries

    /** Only a verified COMMITTED journal entry can publish new authoritative finite state. */
    public synchronized void applyCommitted(FileTransactionJournal journal, UUID id) throws IOException {
        healthy();
        if (!journalDomain.equals(journal.domain())) throw new IOException("Foreign construction journal");
        Optional<Entry> found;
        try { found = journal.verify(id); }
        catch (IOException failure) { failed = true; throw failure; }
        Entry entry = found.orElseThrow(() -> new IOException("Committed finite record missing"));
        if (entry.phase() != Phase.COMMITTED) throw new IOException("Finite record has no committed decision");
        var tx = finiteTx(entry);
        if (tx == null) throw new IOException("Committed record is not a finite transaction");
        try { accept(entry, tx); }
        catch (RuntimeException malformed) {
            failed = true;
            throw new IOException("Committed finite record is inconsistent", malformed);
        }
    }

    /** Validate a pending entry against committed authority before its images are trusted. */
    public synchronized Operation validatePrepared(Entry entry) throws IOException {
        healthy();
        try {
            if (entry.phase() != Phase.PREPARED) throw invalid();
            var tx = finiteTx(entry);
            if (tx == null) throw invalid();
            validate(entry, tx);
            return tx.op();
        } catch (RuntimeException malformed) {
            throw new IOException("Pending finite record does not match committed authority", malformed);
        }
    }

    public synchronized Optional<FiniteDomain> domain(String dimension) {
        healthy();
        FiniteDomainId.dimension(dimension);
        return Optional.ofNullable(domains.get(dimensionHash(dimension)));
    }

    public synchronized Optional<ArtifactBinding> binding(UUID artifactNamespace, long ownerId) {
        healthy();
        FiniteDomainId.nonzero(artifactNamespace);
        if (ownerId <= 0) throw invalid();
        return Optional.ofNullable(bindingsByNs.getOrDefault(artifactNamespace, Map.of()).get(ownerId));
    }

    public synchronized Optional<FiniteRoot> root(FiniteDomainId domain) {
        healthy();
        Objects.requireNonNull(domain);
        return Optional.ofNullable(roots.get(domain.bsiDomain()));
    }

    /** All ACTIVE bindings of one namespace, in owner-id order — the durable owners20 table rows. */
    public synchronized List<ArtifactBinding> activeBindings(UUID artifactNamespace) {
        healthy();
        return bindingsByNs.getOrDefault(artifactNamespace, Map.of()).values().stream()
                .filter(ArtifactBinding::active)
                .sorted(java.util.Comparator.comparingLong(ArtifactBinding::ownerId)).toList();
    }

    /** The durable view a session basis needs; the source object is re-validated through the store. */
    public synchronized FiniteBasis basis(FiniteDomainId domain, long sessionGeneration) throws IOException {
        healthy();
        var source = source(domain);
        var root = root(domain).orElseThrow(() -> new IOException("Finite domain has no committed root"));
        return new FiniteBasis(domain, root.worldRevision(), root.historySequence(),
                source.sourceHash(), source.vocabHash(), source.ownersHash(), sessionGeneration);
    }

    /** Load (and re-validate) the finite source behind the committed root. */
    public synchronized FiniteSource source(FiniteDomainId domain) throws IOException {
        healthy();
        var root = root(domain).orElseThrow(() -> new IOException("Finite domain has no committed root"));
        try {
            var body = store.read(root.source());
            if (body.kind() != FiniteCodec.KIND_SOURCE) throw invalid();
            var source = FiniteSource.decode(body.payload());
            sourceCache.put(root.source().sha256(), source);
            return source;
        } catch (IOException e) { throw e; }
    }

    private void healthy() { if (failed) throw new IllegalStateException("Finite registry requires reconstruction"); }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid finite history or proposal"); }

}
