package com.blockreality.core.finite;

import com.blockreality.core.bsi.BsiCorot;
import com.blockreality.core.bsi.BsiCorotFracture;
import com.blockreality.core.bsi.BsiFrame;
import com.blockreality.core.transaction.AtomicConstructionCoordinator;
import com.blockreality.core.transaction.ConstructionTransaction;
import com.blockreality.core.transaction.ConstructionTransaction.Change;
import com.blockreality.core.transaction.ConstructionTransaction.Entry;
import com.blockreality.core.transaction.ConstructionTransaction.Intent;
import com.blockreality.core.transaction.ConstructionTransaction.Phase;
import com.blockreality.core.transaction.ConstructionTransaction.Receipt;
import com.blockreality.core.transaction.ConstructionTransaction.Request;
import com.blockreality.core.transaction.ConstructionTransaction.Value;
import com.blockreality.core.transaction.FileTransactionJournal;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The W4-A finite fracture driver (FINITE_FRACTURE_TRANSACTION §6): one durable,
 * crash-recoverable fracture transaction per journal request id, built on the shared
 * {@link AtomicConstructionCoordinator} protocol.
 *
 * <p>Properties this class guarantees:
 *
 * <ul>
 *   <li><b>Replay by key.</b> A re-issued request with an equal {@link Request} reads back
 *       the durable decision and its committed plan — native prepare never runs twice for
 *       the same key. Same key with a different request is {@code ReplayConflict}; same
 *       inputs under a different key is a new transaction.</li>
 *   <li><b>Private staging.</b> All native work happens on the host's
 *       {@link FiniteFractureHost#openLane()} in {@link FiniteFracture#stage}; the
 *       authority session is never touched and a native candidate never outlives its
 *       private session.</li>
 *   <li><b>Durable before visible.</b> The journal commits the intent (root, retired
 *       bindings, chunked plan/request/wire evidence) before {@code applyCommitted} folds
 *       the registry and {@link FiniteFractureHost#publish} makes anything visible.</li>
 *   <li><b>Recovery.</b> {@link #recover} rolls back a dangling PREPARED via before-images
 *       and fold-forwards committed entries through {@link FiniteRegistry#sync} — no
 *       native token is ever trusted after a restart.</li>
 * </ul>
 */
public final class FiniteFractureDriver implements AutoCloseable {

    private final FileTransactionJournal journal;
    private final FiniteStore store;
    private final FiniteRegistry registry;
    private final FiniteFracture fracture;
    private final AtomicConstructionCoordinator coordinator;
    private boolean busy, closed;

    public FiniteFractureDriver(FileTransactionJournal journal, FiniteStore store,
                                FiniteRegistry registry) {
        this.journal = Objects.requireNonNull(journal);
        this.store = Objects.requireNonNull(store);
        this.registry = Objects.requireNonNull(registry);
        this.fracture = new FiniteFracture(store, registry);
        this.coordinator = new AtomicConstructionCoordinator(journal);
    }

    /** The outcome of {@link #execute}: the durable receipt plus its committed plan. */
    public record Outcome(Receipt receipt, FiniteFracturePlan plan, FiniteSession session) { }

    /**
     * The durable intent hash a caller puts into {@code Request.planHash} (doc §5.3):
     * recomputed from the committed basis and the exact request frame the private
     * staging will produce — a pure function of durable inputs because the prepare
     * frame's wire id is {@link BsiCorotFracture#prepareId}.
     */
    public static String intentHash(FiniteDomainId domain, FiniteRoot committed,
                                    FiniteSource source, UUID requestId, UUID actor,
                                    String actorProvenance, int format, long byteBudget,
                                    long allocationBudget, long elementBudget,
                                    List<BsiCorotFracture.Rule> rules, int budget) {
        Objects.requireNonNull(domain); Objects.requireNonNull(committed);
        Objects.requireNonNull(source); Objects.requireNonNull(rules);
        byte[] ruleBytes = BsiCorotFracture.encodeRules(rules);
        String header = BsiCorotFracture.prepare(BsiCorotFracture.prepareId(requestId),
                committed.worldRevision(), source.options(),
                source.materials().length / BsiCorot.MATERIAL_BYTES,
                source.cellLoads().length / 64,
                source.prescribed().length / BsiCorot.PRESCRIBED_BYTES,
                source.loads().length / BsiCorot.LOAD_BYTES,
                requestId, rules.size(), budget,
                source.shellLaws().length / BsiCorot.SHELL_LAW_BYTES,
                source.shellLayers().length / BsiCorot.SHELL_LAYER_BYTES);
        byte[] frame = BsiFrame.encode(header, source.fracturePayload(ruleBytes));
        return FiniteCodec.fractureIntentHash(domain, committed.worldRevision(),
                committed.historySequence(), source.sourceHash(), source.vocabHash(),
                source.ownersHash(), requestId, actor, actorProvenance, format,
                byteBudget, allocationBudget, elementBudget, FiniteSource.sha256(frame));
    }

    /**
     * Run one fracture transaction to a durable decision. Recovery and replay ordering:
     * journal lookup → request equality → intent-hash/basis binding → private staging →
     * journal prepare → participant writes → durable COMMITTED → registry fold →
     * host publish.
     */
    public synchronized Outcome execute(Request request, FiniteDomainId domain,
                                        List<BsiCorotFracture.Rule> rules, int budget,
                                        int format, long byteBudget, long allocationBudget,
                                        long elementBudget, String actorProvenance,
                                        FiniteFractureHost host) throws IOException {
        enter();
        var publication = new Publication(host, domain);
        try {
            var receipt = coordinator.execute(request, bound -> {
                FiniteFracture.Staged staged;
                try {
                    staged = fracture.stage(host.openLane(), domain, bound.id(), rules, budget,
                            format, byteBudget, allocationBudget, elementBudget,
                            host.sessionGeneration(), bound.id(), bound.actor(), actorProvenance,
                            host.engineBuild(), host.contractSha256());
                } catch (FiniteFracture.NotQualified refused) {
                    throw new AtomicConstructionCoordinator.ValidationRefused();
                }
                if (!bound.planHash().equals(FiniteCodec.fractureIntentHash(staged.plan())))
                    throw new AtomicConstructionCoordinator.ValidationRefused();
                var prepared = fracture.prepareFracture(bound, domain, staged);
                var images = List.copyOf(host.prepareImages(bound, staged));
                for (var change : images) if (finite(change.resource()))
                    throw new IllegalArgumentException("fracture proof namespace is reserved");
                publication.plan = staged.plan();
                publication.staged = staged;
                var changes = new ArrayList<>(prepared.changes());
                changes.addAll(images);
                return new Intent(bound, changes, List.of(), List.of());
            }, publication);
            FiniteFracturePlan plan = publication.plan;
            if (plan == null && receipt.phase() == Phase.COMMITTED)
                plan = committedPlan(request.id());        // replayed key: durable evidence readback
            return new Outcome(receipt, plan,
                    publication.staged == null ? null : publication.staged.session());
        } finally { busy = false; }
    }

    /**
     * Startup/crash recovery (doc §6.3): roll back a dangling PREPARED via before-images
     * (no committed order ever rewinds), fold committed entries the in-memory registry
     * missed, then let the host republish durable state. Rebuilt hosts see the same fold
     * every restart.
     */
    public synchronized void recover(FiniteFractureHost host) throws IOException {
        enter();
        try {
            coordinator.recover(new Publication(host, null));
            registry.sync(journal);
        } finally { busy = false; }
    }

    /**
     * The same {@link Publication} {@link #recover} hands to this driver's coordinator,
     * exposed for the shared-journal pending router (doc §9.6): a mixed fracture intent
     * must be rolled back through the same finite fold + host delegation the execute
     * path uses, never through the pure-finite host that would refuse its world
     * resources. The returned host serves the pending entry exactly like
     * {@link #recover(FiniteFractureHost)}'s own {@code coordinator.recover} call —
     * {@code finite/…} from the journal fold, everything else via {@code host}.
     * Routing only — this does not run recovery itself and does not set {@code busy}.
     */
    public synchronized AtomicConstructionCoordinator.Host recoveryHost(FiniteFractureHost host)
            throws IOException {
        if (closed) throw new IOException("Closed fracture driver");
        return new Publication(host, null);
    }

    public boolean ready() { return coordinator.ready(); }

    // ---------------------------------------------------------------- journal fold

    /**
     * The committed {@code finite/…} resource images, folded in journal order — the same
     * committed values the registry holds, seen as coordinator participants. Staged
     * writes overlay this map for the duration of one transaction.
     */
    private Map<String, Value> foldFinite() throws IOException {
        var order = new TreeMap<Long, UUID>();
        for (UUID id : journal.entryIds()) {
            Entry entry = journal.read(id)
                    .orElseThrow(() -> new IOException("Finite record disappeared"));
            if (entry.phase() != Phase.COMMITTED || entry.intent() == null) continue;
            Long rank = finiteOrder(entry);
            if (rank != null) order.put(rank, id);
        }
        var map = new HashMap<String, Value>();
        for (var step : order.entrySet()) {
            Entry entry = journal.verify(step.getValue())
                    .orElseThrow(() -> new IOException("Finite record disappeared"));
            for (Change change : entry.intent().changes())
                if (finite(change.resource())) map.put(change.resource(), change.after());
        }
        return map;
    }

    private static Long finiteOrder(Entry entry) {
        Long order = null;
        boolean descriptor = false;
        for (Change change : entry.intent().changes()) {
            if (change.resource().equals("finite/order")) order = decodeOrder(change.after());
            else if (change.resource().equals(FiniteRegistry.txKey(entry.request().id())))
                descriptor = true;
        }
        return order != null && descriptor ? order : null;
    }

    private static long decodeOrder(Value value) {
        if (!value.present() || value.bytes().length != 8) throw new IllegalStateException("finite/order");
        return java.nio.ByteBuffer.wrap(value.bytes())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong();
    }

    static boolean finite(String resource) {
        return resource.startsWith("finite/") || resource.startsWith("fracture/");
    }

    /** The durable plan of a committed fracture entry — evidence readback for replay. */
    private FiniteFracturePlan committedPlan(UUID requestId) throws IOException {
        var entry = journal.verify(requestId)
                .orElseThrow(() -> new IOException("Finite record disappeared"));
        if (entry.phase() != Phase.COMMITTED || entry.intent() == null) return null;
        var chunks = new TreeMap<Integer, byte[]>();
        var prefix = "finite/fracture/" + FiniteCodec.BsiHex(requestId) + "/plan/";
        for (Change change : entry.intent().changes()) {
            var key = change.resource();
            if (key.startsWith(prefix)) {
                int part = Integer.parseInt(key.substring(prefix.length()));
                if (chunks.put(part, change.after().bytes()) != null) throw new IOException("duplicate");
            }
        }
        if (chunks.isEmpty()) return null;
        var bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < chunks.size(); i++) {
            var part = chunks.get(i);
            if (part == null) throw new IOException("fracture plan chunks incomplete");
            bytes.write(part, 0, part.length);
        }
        var plan = FiniteFracturePlan.decode(bytes.toByteArray());
        if (!plan.request().equals(requestId)) throw new IOException("fracture plan binding");
        return plan;
    }

    private void enter() throws IOException {
        if (closed || busy) throw new IOException("Closed or reentrant fracture driver");
        busy = true;
    }

    @Override public synchronized void close() {
        if (busy) throw new IllegalStateException("Fracture transaction in progress");
        closed = true;
    }

    // ---------------------------------------------------------------- coordinator host

    /**
     * The coordinator-facing host: {@code finite/…} resources are served from the
     * journal fold (committed values) plus in-flight staged writes — the journal entry
     * itself is their durability — while every other resource is the real host's
     * participant world. {@link #revision()} reads the world revision straight out of
     * the staged-or-committed {@code finite/root/<bsiDomain>} image, so the durable
     * basis and the revision clock are the same fact.
     */
    private final class Publication implements AtomicConstructionCoordinator.Host {
        final FiniteFractureHost host;
        final FiniteDomainId domain;
        final Map<String, Value> finite;
        FiniteFracture.Staged staged;
        FiniteFracturePlan plan;

        Publication(FiniteFractureHost host, FiniteDomainId domain) throws IOException {
            this.host = Objects.requireNonNull(host);
            this.domain = domain;
            this.finite = foldFinite();
        }

        @Override public void checkAccess() { host.checkAccess(); }

        @Override public long revision() {
            var key = domain != null ? FiniteRegistry.rootKey(domain.bsiDomain())
                    : pendingRootKey();
            var value = key == null ? null : finite.get(key);
            if (value == null || !value.present()) return -1;
            return FiniteCodec.decodeRoot(value.bytes()).worldRevision();
        }

        /** During recovery the target domain is the pending intent's own root resource. */
        private String pendingRootKey() {
            try {
                var pending = journal.pending();
                if (pending.isEmpty()) return null;
                var entry = journal.read(pending.get()).orElse(null);
                if (entry == null || entry.intent() == null) return null;
                for (Change change : entry.intent().changes())
                    if (change.resource().startsWith("finite/root/")) return change.resource();
            } catch (IOException ignored) { }
            return null;
        }

        @Override public Value read(String resource) throws Exception {
            if (finite(resource)) return finite.getOrDefault(resource, Value.missing());
            return host.read(resource);
        }

        @Override public void checkpoint(List<String> resources) throws Exception {
            var owned = new ArrayList<String>(resources.size());
            for (String resource : resources) if (!finite(resource)) owned.add(resource);
            host.checkpoint(owned);
        }

        @Override public void write(String resource, Value value) throws Exception {
            if (finite(resource)) finite.put(resource, value);
            else host.write(resource, value);
        }

        @Override public void flush(List<String> resources) throws Exception {
            var owned = new ArrayList<String>(resources.size());
            for (String resource : resources) if (!finite(resource)) owned.add(resource);
            host.flush(owned);
        }

        @Override public void publish(Receipt receipt) throws Exception {
            if (receipt.phase() != Phase.COMMITTED || plan == null)
                throw new IOException("Fracture publication binding differs");
            // Registry fold first — the host publishes only already-committed authority.
            registry.applyCommitted(journal, receipt.request().id());
            host.publish(receipt, plan);
        }

        @Override public void publishRecoveredState() throws Exception {
            host.publishRecoveredState();
        }
    }
}
