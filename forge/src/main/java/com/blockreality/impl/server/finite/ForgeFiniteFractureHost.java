package com.blockreality.impl.server.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiResponse;
import com.blockreality.core.bsi.BsiVocabulary;
import com.blockreality.core.finite.FiniteDomain;
import com.blockreality.core.finite.FiniteDomainId;
import com.blockreality.core.finite.FiniteFracture;
import com.blockreality.core.finite.FiniteFractureHost;
import com.blockreality.core.finite.FiniteFracturePlan;
import com.blockreality.core.finite.FiniteLane;
import com.blockreality.core.transaction.AtomicConstructionCoordinator;
import com.blockreality.core.transaction.ConstructionTransaction.Change;
import com.blockreality.core.transaction.ConstructionTransaction.Entry;
import com.blockreality.core.transaction.ConstructionTransaction.Phase;
import com.blockreality.core.transaction.ConstructionTransaction.Receipt;
import com.blockreality.core.transaction.ConstructionTransaction.Request;
import com.blockreality.core.transaction.ConstructionTransaction.Value;
import com.blockreality.core.transaction.FileTransactionJournal;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * The W4-B Forge finite-fracture host (FINITE_FRACTURE_TRANSACTION §9): the world
 * participant half of {@code FiniteFractureDriver} bound to the shared construction
 * journal. Everything here is Minecraft-free — the live world reaches it through the
 * {@link World} seam and the engine through {@link LaneSource}; the production
 * adapters are {@code LevelFractureWorld} and the in-process engine loader.
 *
 * <p>Frozen semantics (doc §9):
 *
 * <ul>
 *   <li><b>Shadow write.</b> {@link #write} only moves a private staged overlay; the
 *       live world stays in its before state until durable COMMITTED. {@link #publish}
 *       is the single mutation point — removals, pending-body marks, after-chunk
 *       persistence and the S2C notice all happen there, exactly once.</li>
 *   <li><b>Pure capture.</b> {@link #prepareImages} reads live cells, checks named
 *       uncapturable mutation sources and assembles body records — it never mutates,
 *       never spawns, never calls native.</li>
 *   <li><b>Namespace.</b> Host resources are {@code cell/…} and {@code body/…} only;
 *       {@code finite/} and {@code fracture/} keys are served by the driver's
 *       {@code Publication} fold and are refused here outright.</li>
 *   <li><b>Private lane.</b> {@link #openLane} returns a marshaling lane whose every
 *       call runs on its own daemon thread ({@code br-fracture-lane-*}); the server
 *       thread never executes JNA and never touches the authority
 *       {@link FiniteLaneWorker}.</li>
 *   <li><b>Recovery.</b> {@link #publishRecoveredState} re-applies committed fracture
 *       intents idempotently — live==after skips, live==before reapplies, anything
 *       else fails closed.</li>
 * </ul>
 */
public final class ForgeFiniteFractureHost implements FiniteFractureHost {

    /** The durable {@code body/<ns>/<id>} record layout (doc §9.4). */
    public static final int BODY_RECORD_BYTES = 28;
    /** {@code state} field of a body record: durable mark, never a spawned rigid body. */
    public static final int BODY_STATE_PENDING = 0;
    private static final int MAX_BASELINE_CHUNKS = 256;

    /**
     * The world side the host serves. Production binds a {@code ServerLevel}; tests
     * bind a recording fake. Every key here is a journal resource key, never a parsed
     * position — parsing stays in the adapter.
     */
    public interface World {
        /** The live canonical cell image; {@code missing} iff the cell is air. */
        Value readCell(String cellKey) throws IOException;
        /** One publish-time cell image — {@code missing} removes the cell and its entity. */
        void applyCell(String cellKey, Value image) throws IOException;
        /** Named mutation sources that cannot be captured or reversed at this cell. */
        Set<String> uncapturable(String cellKey) throws IOException;
        /** Persist the complete live NBT of every affected chunk as a durable before-image. */
        void captureBaseline(Set<String> chunkKeys) throws IOException;
        /** Persist the complete live NBT of every affected chunk after publication. */
        void persistAfter(Set<String> chunkKeys) throws IOException;
        /** The world-side pending-body mark for a durable body record. */
        void markBody(String bodyKey, Value record) throws IOException;
        /** The single post-commit S2C notice for this fracture. */
        void publishNotice(Receipt receipt) throws IOException;
    }

    /** Resolves a finite dimension name to its bound world adapter. */
    @FunctionalInterface
    public interface Worlds { World world(String dimension) throws IOException; }

    /**
     * Opens one private engine lane — the same engine the {@link FiniteLaneWorker}
     * factory opens, but owned by this lane's own thread from open to close.
     * {@code engineBuild}/{@code contractSha256} are stamped into durable checkpoint
     * slots; an empty string is never acceptable.
     */
    @FunctionalInterface
    public interface LaneSource { OpenedLane open() throws Exception; }
    public record OpenedLane(FiniteLane lane, AutoCloseable closer,
                             String engineBuild, String contractSha256) {
        public OpenedLane {
            Objects.requireNonNull(lane); Objects.requireNonNull(closer);
            Objects.requireNonNull(engineBuild); Objects.requireNonNull(contractSha256);
            if (engineBuild.isBlank() || contractSha256.isBlank())
                throw new IllegalArgumentException("private lane carries no engine stamp");
        }
    }

    private final FiniteRuntime runtime;
    private final FileTransactionJournal journal;
    private final Worlds worlds;
    private final LaneSource lanes;
    private final Runnable accessGuard;
    private final Consumer<String> log;

    /** Committed {@code body/…} records folded from the journal plus this host's publishes. */
    private final Map<String, Value> bodies = new TreeMap<>();
    private long stagingGeneration = -1;
    private volatile PrivateLane lane;
    private boolean closed;

    // ---------------------------------------------------------------- transaction binding
    private FiniteDomainId boundDomain;
    private World world;
    private Request boundRequest;
    private FiniteFracturePlan boundPlan;
    private Map<String, Change> declared = Map.of();       // non-finite participants only
    private final Map<String, Value> staged = new TreeMap<>();
    private final Set<String> baselineChunks = new TreeSet<>();
    private boolean baselinePersisted;

    public ForgeFiniteFractureHost(FiniteRuntime runtime, FileTransactionJournal journal,
                                   Worlds worlds, LaneSource lanes,
                                   Runnable accessGuard, Consumer<String> log) {
        this.runtime = Objects.requireNonNull(runtime);
        this.journal = Objects.requireNonNull(journal);
        this.worlds = Objects.requireNonNull(worlds);
        this.lanes = Objects.requireNonNull(lanes);
        this.accessGuard = Objects.requireNonNull(accessGuard);
        this.log = Objects.requireNonNull(log);
    }

    // ---------------------------------------------------------------- host contract

    @Override public void checkAccess() {
        accessGuard.run();
        if (closed) throw new IllegalStateException("Fracture host is closed");
    }

    /**
     * The committed finite-root world revision for the bound domain — the same clock
     * the driver's {@code Publication} reads, so the CAS check and the durable basis
     * can never disagree (doc §9.1). The Forge world gate is a separate clock and is
     * deliberately not consulted.
     */
    @Override public long revision() throws IOException {
        checkAccess();
        if (boundDomain == null) throw new IOException("Fracture host is not bound to a domain");
        var root = runtime.registry().root(boundDomain)
                .orElseThrow(() -> new IOException("finite domain has no committed root"));
        return root.worldRevision();
    }

    /**
     * The committed value of a host resource: staged overlay first, then the live
     * world ({@code cell/…}) or the folded body table ({@code body/…}). {@code finite/}
     * and {@code fracture/} keys are the journal's — they never reach a host resource.
     */
    @Override public Value read(String resource) throws Exception {
        checkAccess();
        requireHostResource(resource);
        Value stagedValue = staged.get(resource);
        if (stagedValue != null) return stagedValue;
        if (resource.startsWith("cell/")) return requireWorld().readCell(resource);
        if (resource.startsWith("body/")) {
            Value record = bodies.get(resource);
            return record == null ? Value.missing() : record;
        }
        throw new IOException("Unknown fracture resource " + resource);
    }

    /**
     * Durable host before-images (doc §9.3): re-verify every listed live value still
     * equals its declared before, then persist the affected chunks' complete live NBT
     * once. No visible mutation.
     */
    @Override public void checkpoint(List<String> resources) throws Exception {
        checkAccess();
        for (String resource : resources) {
            Change change = declared.get(resource);
            if (change == null)
                throw new IOException("Foreign fracture checkpoint resource " + resource);
            Value live = resource.startsWith("cell/") ? requireWorld().readCell(resource)
                    : readCommittedBody(resource);
            if (!live.equals(change.before()))
                throw new IOException("Fracture participant drifted before checkpoint: " + resource);
        }
        if (!baselinePersisted && !baselineChunks.isEmpty()) {
            requireWorld().captureBaseline(Set.copyOf(baselineChunks));
            baselinePersisted = true;
        }
    }

    /**
     * One participant image — under the frozen shadow semantics this only moves the
     * private staged overlay after proving the value is exactly the declared before
     * or after image. The live world is never written here (doc §9.3).
     */
    @Override public void write(String resource, Value value) throws Exception {
        checkAccess();
        requireHostResource(resource);
        Objects.requireNonNull(value);
        Change change = declared.get(resource);
        if (change == null)
            throw new IOException("Foreign fracture write " + resource);
        if (!value.equals(change.before()) && !value.equals(change.after()))
            throw new IOException("Foreign fracture image " + resource);
        staged.put(resource, value);
    }

    /**
     * Participant durability: every listed resource must be declared and carry a
     * staged image, and the chunk before-baseline must already be durable (it was
     * persisted in {@link #checkpoint}; the staged images themselves are durable via
     * the journal intent).
     */
    @Override public void flush(List<String> resources) throws Exception {
        checkAccess();
        for (String resource : resources) {
            if (!declared.containsKey(resource))
                throw new IOException("Foreign fracture flush resource " + resource);
            if (!staged.containsKey(resource))
                throw new IOException("Fracture participant was never staged " + resource);
        }
        if (!baselineChunks.isEmpty() && !baselinePersisted)
            throw new IOException("Fracture chunk baseline was never persisted");
    }

    /**
     * Pure capture (doc §9.3/§9.8): binds this host to the transaction, derives the
     * removed-cell set from before/after owners, reads every live before-image,
     * refuses named uncapturable mutation sources, and assembles {@code body/…}
     * records for the plan's born bodies. No world mutation, no native call, no
     * permanent identity beyond the receipt's own owner ids.
     */
    @Override public List<Change> prepareImages(Request request, FiniteFracture.Staged staged)
            throws Exception {
        checkAccess();
        Objects.requireNonNull(request); Objects.requireNonNull(staged);
        var plan = staged.plan();
        if (boundDomain != null && !boundDomain.equals(plan.domain()))
            throw new IOException("Fracture staged plan is not the bound domain");
        var world = worldFor(plan.domain().dimension());

        // Removed cells: owner positions in the before source, gone in the after.
        var beforeOwners = index(staged.source().owners());
        var afterOwners = index(staged.afterSource().owners());
        var removed = new TreeMap<BlockKey, Long>(CELL_ORDER);
        for (var entry : beforeOwners.entrySet()) {
            Long kept = afterOwners.get(entry.getKey());
            if (kept == null) removed.put(entry.getKey(), entry.getValue());
            else if (!kept.equals(entry.getValue()))
                throw new IOException("Owner artifact changed under a surviving cell " + entry.getKey());
        }

        var changes = new ArrayList<Change>();
        var declared = new LinkedHashMap<String, Change>();
        var chunks = new TreeSet<String>();
        for (BlockKey cell : removed.keySet()) {
            String cellKey = cellKey(cell);
            Value live = world.readCell(cellKey);
            if (!live.present())
                throw new IOException("Fracture target cell is already absent " + cellKey);
            var blockers = world.uncapturable(cellKey);
            if (!blockers.isEmpty()) {
                // Named refusal: the blockers ride as suppressed detail on the durable REJECTED.
                var refused = new AtomicConstructionCoordinator.ValidationRefused();
                refused.addSuppressed(new IOException("Uncapturable fracture mutation at "
                        + cellKey + ": " + String.join(",", blockers)));
                throw refused;
            }
            var change = new Change(cellKey, live, Value.missing());
            changes.add(change);
            declared.put(cellKey, change);
            chunks.add(chunkKey(cell));
        }
        if (chunks.size() > MAX_BASELINE_CHUNKS)
            throw new IOException("Fracture baseline chunk capacity exceeded");

        // Born bodies: durable pending records only, anchored on air-or-removed cells.
        for (BsiFracture.Owner body : plan.bornBodies()) {
            String bodyKey = bodyKey(plan.domain(), body.artifact());
            if (bodies.containsKey(bodyKey))
                throw new IOException("Born body id is already durable " + bodyKey);
            if (!removed.containsKey(body.position())
                    && world.readCell(cellKey(body.position())).present()) {
                var refused = new AtomicConstructionCoordinator.ValidationRefused();
                refused.addSuppressed(new IOException("Born body anchor is occupied " + bodyKey));
                throw refused;
            }
            var change = new Change(bodyKey, Value.missing(), Value.of(encodeBody(body)));
            changes.add(change);
            declared.put(bodyKey, change);
        }

        this.boundDomain = plan.domain();
        this.world = world;
        this.boundRequest = request;
        this.boundPlan = plan;
        this.declared = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(declared));
        this.staged.clear();
        this.baselineChunks.clear();
        this.baselineChunks.addAll(chunks);
        this.baselinePersisted = false;
        return List.copyOf(changes);
    }

    /**
     * The single visible publication (doc §9.3): applies every declared after image,
     * registers the pending-body marks, persists the after-state chunks and sends the
     * S2C notice — in that order, exactly once per durable COMMITTED.
     */
    @Override public void publish(Receipt receipt, FiniteFracturePlan plan) throws Exception {
        checkAccess();
        Objects.requireNonNull(receipt); Objects.requireNonNull(plan);
        if (receipt.phase() != Phase.COMMITTED)
            throw new IOException("Fracture publish requires a committed receipt");
        if (boundRequest == null || !receipt.request().equals(boundRequest))
            throw new IOException("Fracture publish request is not the bound transaction");
        if (!plan.equals(boundPlan))
            throw new IOException("Fracture publish plan is not the staged plan");
        if (world == null) throw new IOException("Fracture host has no bound world");
        for (Change change : declared.values()) {
            if (change.resource().startsWith("cell/")) world.applyCell(change.resource(), change.after());
            else if (change.resource().startsWith("body/")) {
                world.markBody(change.resource(), change.after());
                bodies.put(change.resource(), change.after());
            }
        }
        for (Change change : declared.values()) {
            if (!change.resource().startsWith("cell/")) continue;
            Value live = world.readCell(change.resource());
            if (!live.equals(change.after()))
                throw new IOException("Fracture publication did not take " + change.resource());
        }
        if (!baselineChunks.isEmpty()) world.persistAfter(Set.copyOf(baselineChunks));
        world.publishNotice(receipt);
        log.accept("fracture " + receipt.request().id() + " published " + declared.size() + " images");
    }

    /**
     * Rebuild visible state from committed authority (doc §9.3): folds every
     * COMMITTED fracture intent's non-finite changes back onto the world
     * idempotently and rebuilds the committed body table. A live value that is
     * neither before nor after image is foreign state — fail closed.
     */
    @Override public void publishRecoveredState() throws Exception {
        checkAccess();
        bodies.clear();
        var adapters = new LinkedHashMap<String, World>();
        var republished = new LinkedHashMap<World, Set<String>>();
        for (UUID id : journal.entryIds()) {
            Entry entry = journal.verify(id)
                    .orElseThrow(() -> new IOException("Finite record disappeared"));
            if (entry.phase() != Phase.COMMITTED || entry.intent() == null) continue;
            if (!FiniteJournalHost.isFracture(entry)) continue;
            FiniteDomainId domain = fractureDomain(entry);
            World adapter = boundDomain != null && boundDomain.equals(domain) && world != null
                    ? world
                    : null;
            if (adapter == null) {
                adapter = adapters.get(domain.dimension());
                if (adapter == null) {
                    adapter = worldFor(domain.dimension());
                    adapters.put(domain.dimension(), adapter);
                }
            }
            for (Change change : entry.intent().changes()) {
                String resource = change.resource();
                if (resource.startsWith("finite/") || resource.startsWith("fracture/")) continue;
                if (resource.startsWith("body/")) {
                    bodies.put(resource, change.after());
                    adapter.markBody(resource, change.after());
                    continue;
                }
                if (!resource.startsWith("cell/"))
                    throw new IOException("Unknown recovered fracture resource " + resource);
                Value live = adapter.readCell(resource);
                if (live.equals(change.after())) continue;
                if (!live.equals(change.before()))
                    throw new IOException("Foreign recovered fracture state " + resource);
                adapter.applyCell(resource, change.after());
                republished.computeIfAbsent(adapter, key -> new TreeSet<>()).add(chunkKey(resource));
            }
        }
        for (var step : republished.entrySet()) step.getKey().persistAfter(step.getValue());
        log.accept("fracture republished " + bodies.size() + " body records, "
                + republished.values().stream().mapToInt(Set::size).sum() + " chunks");
    }

    // ---------------------------------------------------------------- lane + session

    /**
     * A fresh private lane (doc §9.5): every operation is marshalled onto the lane's
     * own daemon thread — the caller never executes JNA and never sees the authority
     * {@link FiniteLaneWorker}. The synchronous wait on the reply slot is the named
     * §5.2 slow path, not a posted-future join.
     */
    @Override public FiniteLane openLane() throws IOException {
        checkAccess();
        PrivateLane previous = lane;
        lane = null;
        if (previous != null) previous.close();
        String name = boundDomain == null ? "recovery" : boundDomain.dimension().replace(':', '-');
        PrivateLane opened = PrivateLane.open(name + "-" + (stagingGeneration + 1), lanes);
        lane = opened;
        return opened;
    }

    /** A generation strictly above the authority session's at mint time (doc §9.5). */
    @Override public long sessionGeneration() {
        stagingGeneration = Math.max(stagingGeneration + 1, runtime.sessionGeneration() + 1);
        return stagingGeneration;
    }

    @Override public String engineBuild() {
        PrivateLane current = lane;
        if (current == null || current.opened == null)
            throw new IllegalStateException("No private fracture lane opened");
        return current.opened.engineBuild();
    }

    @Override public String contractSha256() {
        PrivateLane current = lane;
        if (current == null || current.opened == null)
            throw new IllegalStateException("No private fracture lane opened");
        return current.opened.contractSha256();
    }

    // ---------------------------------------------------------------- binding

    /**
     * Bind this host to a domain before {@code driver.execute} — the world resolves
     * up front and {@link #openLane}/{@link #revision} can name/verify it (doc §9.7).
     */
    /**
     * The durable body table folded from the journal (doc §9.4): every COMMITTED
     * fracture intent's {@code body/…} after-image. Bind-time, not publish-time —
     * a fresh host must see durable records or the duplicate-id guard in
     * {@link #prepareImages} would silently overwrite them.
     */
    private void foldBodies() throws IOException {
        bodies.clear();
        for (UUID id : journal.entryIds()) {
            Entry entry = journal.verify(id)
                    .orElseThrow(() -> new IOException("Finite record disappeared"));
            if (entry.phase() != Phase.COMMITTED || entry.intent() == null) continue;
            if (!FiniteJournalHost.isFracture(entry)) continue;
            for (Change change : entry.intent().changes())
                if (change.resource().startsWith("body/"))
                    bodies.put(change.resource(), change.after());
        }
    }

    void bindDomain(FiniteDomainId domain) throws IOException {
        Objects.requireNonNull(domain);
        this.boundDomain = domain;
        this.world = worldFor(domain.dimension());
        foldBodies();
    }

    /**
     * Bind this host to a dangling PREPARED fracture entry for recovery routing: the
     * declared participant set comes from the durable intent, the world resolves
     * through the intent's committed root key (doc §9.6).
     */
    void bindRecovery(Entry pending) throws IOException {
        Objects.requireNonNull(pending); Objects.requireNonNull(pending.intent());
        this.boundDomain = fractureDomain(pending);
        this.world = worldFor(boundDomain.dimension());
        this.boundRequest = pending.request();
        this.boundPlan = null;
        var declared = new LinkedHashMap<String, Change>();
        var chunks = new TreeSet<String>();
        for (Change change : pending.intent().changes()) {
            String resource = change.resource();
            if (resource.startsWith("finite/") || resource.startsWith("fracture/")) continue;
            declared.put(resource, change);
            if (resource.startsWith("cell/")) chunks.add(chunkKey(resource));
        }
        this.declared = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(declared));
        this.staged.clear();
        this.baselineChunks.clear();
        this.baselineChunks.addAll(chunks);
        this.baselinePersisted = true;   // a rollback persists nothing new
        foldBodies();
    }

    /** Close the private lane; the caller owns its lifecycle (doc §9.5). */
    void closeLane() {
        PrivateLane current = lane;
        lane = null;
        if (current != null) current.close();
    }

    void close() {
        closed = true;
        closeLane();
    }

    // ---------------------------------------------------------------- internals

    /** The domain a fracture intent binds, resolved via its committed root key. */
    private FiniteDomainId fractureDomain(Entry entry) throws IOException {
        Objects.requireNonNull(entry.intent());
        for (Change change : entry.intent().changes()) {
            String resource = change.resource();
            if (!resource.startsWith("finite/root/")) continue;
            UUID bsiDomain = parseUuid(resource.substring("finite/root/".length()));
            for (FiniteDomain domain : runtime.registry().domains())
                if (domain.id().bsiDomain().equals(bsiDomain)) return domain.id();
            throw new IOException("Fracture entry names an unknown finite domain");
        }
        throw new IOException("Fracture entry carries no finite root change");
    }

    private World requireWorld() throws IOException {
        if (world == null) throw new IOException("Fracture host has no bound world");
        return world;
    }

    private World worldFor(String dimension) throws IOException {
        try { return Objects.requireNonNull(worlds.world(dimension)); }
        catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("Fracture world is unavailable", failure); }
    }

    private Value readCommittedBody(String resource) {
        Value record = bodies.get(resource);
        return record == null ? Value.missing() : record;
    }

    private static void requireHostResource(String resource) throws IOException {
        Objects.requireNonNull(resource);
        if (resource.startsWith("finite/") || resource.startsWith("fracture/"))
            throw new IOException("Finite authority resource is not host-served " + resource);
    }

    /** Canonical cell ordering for change emission — {@code BlockKey} is not Comparable. */
    private static final Comparator<BlockKey> CELL_ORDER = Comparator.comparingInt(BlockKey::x)
            .thenComparingInt(BlockKey::y).thenComparingInt(BlockKey::z);

    private static Map<BlockKey, Long> index(List<BsiFracture.Owner> owners) {
        var map = new TreeMap<BlockKey, Long>(CELL_ORDER);
        for (BsiFracture.Owner owner : owners)
            if (map.put(owner.position(), owner.artifact()) != null)
                throw new IllegalArgumentException("Duplicate finite owner cell " + owner.position());
        return map;
    }

    static String cellKey(BlockKey cell) {
        return "cell/" + cell.x() + "/" + cell.y() + "/" + cell.z();
    }

    /** {@code chunk/<x>/<z>} — a baseline key only, never a journal participant (doc §9.2). */
    static String chunkKey(BlockKey cell) {
        return "chunk/" + (cell.x() >> 4) + "/" + (cell.z() >> 4);
    }

    private static String chunkKey(String cellKey) {
        String[] parts = cellKey.split("/");
        if (parts.length != 4 || !parts[0].equals("cell"))
            throw new IllegalArgumentException("Not a cell key " + cellKey);
        return "chunk/" + (Integer.parseInt(parts[1]) >> 4) + "/" + (Integer.parseInt(parts[3]) >> 4);
    }

    /** {@code body/<ns-hex32>/<artifact-hex16>} (doc §9.4). */
    static String bodyKey(FiniteDomainId domain, long artifact) {
        return "body/" + hex(domain.artifactNamespace()) + "/" + "%016x".formatted(artifact);
    }

    /** The 28-byte pending-body record: position, durable artifact, named state, flags. */
    static byte[] encodeBody(BsiFracture.Owner body) {
        var buffer = ByteBuffer.allocate(BODY_RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(body.position().x()).putInt(body.position().y()).putInt(body.position().z());
        buffer.putLong(body.artifact());
        buffer.putInt(BODY_STATE_PENDING);
        buffer.putInt(0);
        return buffer.array();
    }

    static String hex(UUID id) { return id.toString().replace("-", ""); }

    private static UUID parseUuid(String hex) throws IOException {
        try {
            if (hex.length() != 32) throw new IllegalArgumentException();
            return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16),
                    Long.parseUnsignedLong(hex.substring(16), 16));
        } catch (RuntimeException failure) {
            throw new IOException("Fracture entry carries a malformed domain key", failure);
        }
    }

    /**
     * The marshaling private lane (doc §9.5): a single daemon thread owns the
     * {@link OpenedLane} from open to close; callers park on a {@link CountDownLatch}
     * reply slot per call — the synchronous lane contract, never a posted-future
     * join. If a native call never returns the thread stays parked: a daemon under
     * the same revocation-only contract as {@link FiniteLaneWorker}.
     */
    static final class PrivateLane implements FiniteLane, AutoCloseable {
        private static final Callable<Object> POISON = () -> null;
        private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();
        private final Thread thread;
        private volatile boolean closed;
        volatile OpenedLane opened;

        private static final class Task {
            final Callable<?> job;
            final CountDownLatch done = new CountDownLatch(1);
            volatile Object result;
            volatile Throwable failure;
            Task(Callable<?> job) { this.job = job; }
        }

        private PrivateLane(String name) {
            this.thread = new Thread(this::loop, "br-fracture-lane-" + name);
            this.thread.setDaemon(true);
        }

        static PrivateLane open(String name, LaneSource source) throws IOException {
            var lane = new PrivateLane(name);
            lane.thread.start();
            Task task;
            try {
                task = lane.submit(() -> { lane.opened = source.open(); return null; });
            } catch (IOException failure) {
                lane.close();
                throw failure;
            }
            if (task.failure != null) {
                lane.close();
                throw new IOException("Private fracture lane could not open", task.failure);
            }
            if (lane.opened == null) {
                lane.close();
                throw new IOException("Private fracture lane opened nothing");
            }
            return lane;
        }

        private void loop() {
            while (true) {
                Task task;
                try { task = queue.take(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                if (task.job == POISON) return;
                try { task.result = task.job.call(); }
                catch (Throwable failure) { task.failure = failure; }
                task.done.countDown();
            }
        }

        private Task submit(Callable<?> job) throws IOException {
            if (closed) throw new IOException("Private fracture lane is closed");
            var task = new Task(job);
            queue.offer(task);
            try { task.done.await(); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Private fracture lane wait interrupted");
            }
            return task;
        }

        private <T> T call(Callable<T> job) {
            Task task;
            try { task = submit(job); }
            catch (IOException failure) { throw new IllegalStateException(failure.getMessage(), failure); }
            if (task.failure != null) throw raise(task.failure);
            @SuppressWarnings("unchecked") T result = (T) task.result;
            return result;
        }

        @Override public BsiResponse request(String headerJson, byte[] payload) {
            Objects.requireNonNull(headerJson);          // a null payload is a header-only frame
            return call(() -> opened.lane().request(headerJson, payload));
        }

        @Override public boolean adoptVocabulary(BsiVocabulary vocabulary) {
            return call(() -> opened.lane().adoptVocabulary(vocabulary));
        }

        @Override public boolean declareFiniteWorld(BsiFracture.World world) {
            return call(() -> opened.lane().declareFiniteWorld(world));
        }

        @Override public boolean ready() {
            return call(() -> opened.lane().ready());
        }

        @Override public boolean has(String capability) {
            return call(() -> opened.lane().has(capability));
        }

        private static RuntimeException raise(Throwable failure) {
            if (failure instanceof RuntimeException runtime) return runtime;
            if (failure instanceof Error error) throw error;
            return new IllegalStateException("Private fracture lane call failed", failure);
        }

        /** Close the engine on the lane thread, then let the thread die (doc §9.5). */
        @Override public void close() {
            if (closed) return;
            closed = true;
            var task = new Task(() -> {
                OpenedLane held = opened;
                if (held != null) held.closer().close();
                return null;
            });
            queue.offer(task);
            try { task.done.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            queue.offer(new Task(POISON));
        }
    }
}
