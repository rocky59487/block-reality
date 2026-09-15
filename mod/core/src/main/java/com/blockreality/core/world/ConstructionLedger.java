package com.blockreality.core.world;

import com.blockreality.api.geom.BlockKey;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/** Main-thread declarations and publication; immutable work/graph objects can cross to a worker. */
public final class ConstructionLedger {
    public static final int MAX_RECORDS = 262144, MAX_PARENTS = 524288;
    static final Comparator<BlockKey> ORDER = Comparator.comparingInt(BlockKey::x)
            .thenComparingInt(BlockKey::y).thenComparingInt(BlockKey::z);
    public record Artifact(long id, ConstructionDeclaration declaration, List<Long> parents, List<BlockKey> cells) {
        public Artifact { parents = List.copyOf(parents); cells = List.copyOf(cells); }
        public boolean active() { return !cells.isEmpty(); }
        Artifact retire() { return active() ? new Artifact(id, declaration, parents, List.of()) : this; }
    }
    public static final class Graph {
        private final UUID namespace;
        private final long nextId;
        private final NavigableMap<Long, Artifact> records;
        private final Map<BlockKey, Long> owners;
        Graph(UUID namespace, long nextId, Map<Long, Artifact> records) {
            this.namespace = Objects.requireNonNull(namespace); this.nextId = nextId;
            // Detached immutable entries also protect generator-based arrays and
            // navigable range views on Java 17. No writer retains this private copy.
            this.records = Collections.unmodifiableNavigableMap(new ConcurrentSkipListMap<>(records));
            var owners = new HashMap<BlockKey, Long>();
            long references = 0;
            if (nextId != records.size() + 1L || records.size() > MAX_RECORDS) throw invalid();
            long expectedId = 1;
            for (Artifact a : this.records.values()) {
                if (a.id() != expectedId++ || this.records.get(a.id()) != a) throw invalid();
                long last = 0;
                for (long parent : a.parents()) {
                    if (parent <= last || parent >= a.id() || !records.containsKey(parent)) throw invalid();
                    last = parent;
                }
                references += a.parents().size();
                BlockKey previous = null;
                for (BlockKey p : a.cells()) {
                    validatePosition(p);
                    if (previous != null && ORDER.compare(previous, p) >= 0 || owners.put(p, a.id()) != null) throw invalid();
                    previous = p;
                }
            }
            if (references > MAX_PARENTS || owners.size() > WorldCellIndex.MAX_CELLS) throw invalid();
            // This validated map is privately owned. Map.copyOf uses a linear-probing
            // table on Java 17; dense coordinate hashes make both creation and reads
            // pathological at registry capacity (REGISTRY_SCALING baseline).
            this.owners = freezeOwned(owners);
        }
        public UUID namespace() { return namespace; }
        public long nextId() { return nextId; }
        public NavigableMap<Long, Artifact> records() { return records; }
        public Map<BlockKey, Long> owners() { return owners; }
        public String key(long id) { return namespace + "/" + id; }
        public long activeCount() { return records.values().stream().filter(Artifact::active).count(); }
    }
    public record Work(long epoch, Map<BlockKey, ConstructionDeclaration> cells, Set<BlockKey> destroyed, Graph graph) {
        public Work {
            if (epoch < 0 || cells.size() > WorldCellIndex.MAX_CELLS || destroyed.size() > WorldCellIndex.MAX_CELLS) throw invalid();
            var captured = new HashMap<>(cells);
            captured.forEach((pos, declaration) -> { Objects.requireNonNull(pos); Objects.requireNonNull(declaration); });
            cells = freezeOwned(captured);
            var removed = new HashSet<>(destroyed);
            removed.forEach(Objects::requireNonNull);
            destroyed = Collections.unmodifiableSet(removed);
        }
    }
    public record Completion(long epoch, Graph graph, String failure) { }

    /** The supplied map must be privately owned and never mutated after this call. */
    private static <V> Map<BlockKey, V> freezeOwned(Map<BlockKey, V> owned) {
        // On supported Java 17 builds, UnmodifiableEntrySet.toArray(IntFunction)
        // can expose mutable backing entries. Supply detached entries on every
        // iteration route; arrays/streams/spliterators then cannot escape ownership.
        return Collections.unmodifiableMap(new AbstractMap<>() {
            @Override public V get(Object key) { return owned.get(key); }
            @Override public boolean containsKey(Object key) { return owned.containsKey(key); }
            @Override public int size() { return owned.size(); }
            @Override public Set<BlockKey> keySet() { return Collections.unmodifiableSet(owned.keySet()); }
            @Override public Collection<V> values() { return Collections.unmodifiableCollection(owned.values()); }
            @Override public Set<Entry<BlockKey, V>> entrySet() {
                return new AbstractSet<>() {
                    @Override public int size() { return owned.size(); }
                    @Override public Iterator<Entry<BlockKey, V>> iterator() {
                        var iterator = owned.entrySet().iterator();
                        return new Iterator<>() {
                            @Override public boolean hasNext() { return iterator.hasNext(); }
                            @Override public Entry<BlockKey, V> next() {
                                var entry = iterator.next(); return Map.entry(entry.getKey(), entry.getValue());
                            }
                        };
                    }
                };
            }
        });
    }

    final Map<BlockKey, ConstructionDeclaration> cells = new HashMap<>();
    final Set<BlockKey> destroyed = new HashSet<>();
    private Graph graph;
    long epoch, completedEpoch;
    String failure = "";

    public ConstructionLedger() { graph = new Graph(UUID.randomUUID(), 1, Map.of()); }
    ConstructionLedger(Graph graph) { this.graph = graph; }
    public int cellCount() { return cells.size(); }
    public Set<BlockKey> positions() { return Collections.unmodifiableSet(cells.keySet()); }
    public ConstructionDeclaration declaration(BlockKey pos) { return cells.get(pos); }
    public Graph graph() { return graph; }
    public long epoch() { return epoch; }
    public long completedEpoch() { return completedEpoch; }
    public String failure() { return failure; }
    public boolean ready() { return failure.isEmpty() && epoch == completedEpoch; }
    public boolean pending() { return failure.isEmpty() && epoch != completedEpoch; }
    public Work work() { return new Work(epoch, cells, destroyed, graph); }

    public boolean observe(BlockKey pos, ConstructionDeclaration declaration) {
        validatePosition(pos); Objects.requireNonNull(declaration);
        if (!failure.isEmpty() || declaration.equals(cells.get(pos))) return false;
        if (!cells.containsKey(pos) && cells.size() == WorldCellIndex.MAX_CELLS) return refuse("construction cell capacity exceeded");
        var old = cells.get(pos);
        if (old != null && !old.sameProduct(declaration) && !rememberDestruction(pos)) return false;
        if (!advance()) return false;
        cells.put(pos, declaration); return true;
    }
    public boolean remove(BlockKey pos) {
        if (!failure.isEmpty() || !cells.containsKey(pos) && !graph.owners().containsKey(pos)) return false;
        if (!rememberDestruction(pos) || !advance()) return false;
        cells.remove(pos); return true;
    }
    private boolean rememberDestruction(BlockKey pos) {
        if (epoch == Long.MAX_VALUE) return refuse("construction epoch exhausted");
        if (!destroyed.contains(pos) && destroyed.size() == WorldCellIndex.MAX_CELLS)
            return refuse("pending construction destruction capacity exceeded");
        destroyed.add(pos); return true;
    }
    private boolean advance() {
        if (epoch == Long.MAX_VALUE) return refuse("construction epoch exhausted");
        epoch++; return true;
    }
    private boolean refuse(String reason) { failure = reason; return false; }

    /** A completed but stale graph has never been published; it cannot consume or resurrect IDs. */
    public boolean publish(Completion completion) {
        if (completion.epoch() != epoch || !failure.isEmpty()) return false;
        if (!completion.failure().isEmpty()) { failure = completion.failure(); return true; }
        if (!graph.namespace().equals(completion.graph().namespace())) throw invalid();
        graph = completion.graph(); completedEpoch = epoch; destroyed.clear(); return true;
    }

    /** Pure worker operation. Adjacency describes declared object grouping, not physical stability. */
    public static Completion reconcile(Work work) {
        try { return new Completion(work.epoch(), rebuild(work), ""); }
        catch (RuntimeException e) { return new Completion(work.epoch(), work.graph(), "construction registry refused: " + e.getMessage()); }
    }

    private record Component(ConstructionDeclaration declaration, List<BlockKey> cells,
                             SortedSet<Long> parents, Set<Long> continuous) { }
    private static Graph rebuild(Work work) {
        var remaining = new TreeSet<BlockKey>(ORDER); remaining.addAll(work.cells().keySet());
        var components = new ArrayList<Component>();
        var occurrences = new HashMap<Long, Integer>();
        while (!remaining.isEmpty()) {
            BlockKey start = remaining.first(); remaining.remove(start);
            ConstructionDeclaration declaration = work.cells().get(start);
            var queue = new ArrayDeque<BlockKey>(); queue.add(start);
            var positions = new ArrayList<BlockKey>(); var parents = new TreeSet<Long>(); var continuous = new HashSet<Long>();
            while (!queue.isEmpty()) {
                BlockKey p = queue.removeFirst(); positions.add(p);
                Long owner = work.graph().owners().get(p);
                if (owner != null) {
                    parents.add(owner);
                    if (!work.destroyed().contains(p)) continuous.add(owner);
                }
                for (BlockKey near : declaration.neighbours(p)) {
                    var other = work.cells().get(near);
                    if (other != null && declaration.joins(other) && remaining.remove(near)) queue.addLast(near);
                }
            }
            positions.sort(ORDER);
            // A rebuilt child still participates in a split of the old geometry. Counting
            // only surviving cells would incorrectly leave its sibling holding the parent ID.
            for (long id : parents) occurrences.merge(id, 1, Integer::sum);
            components.add(new Component(declaration.groupDeclaration(), List.copyOf(positions), parents, continuous));
        }
        var records = new TreeMap<Long, Artifact>();
        work.graph().records().forEach((id, artifact) -> records.put(id, artifact.retire()));
        long nextId = work.graph().nextId();
        for (Component c : components) {
            Long retained = c.parents().size() == 1 ? c.parents().first() : null;
            if (retained != null && c.continuous().contains(retained) && occurrences.get(retained) == 1) {
                var old = work.graph().records().get(retained);
                records.put(retained, new Artifact(retained, c.declaration(), old.parents(), c.cells()));
            } else {
                if (records.size() == MAX_RECORDS || nextId == Long.MAX_VALUE) throw new IllegalStateException("object identity capacity exhausted");
                long id = nextId++;
                records.put(id, new Artifact(id, c.declaration(), List.copyOf(c.parents()), c.cells()));
            }
        }
        return new Graph(work.graph().namespace(), nextId, records);
    }
    public byte[] encode() { return ConstructionCodec.encode(this); }
    public static ConstructionLedger decode(byte[] bytes) { return ConstructionCodec.decode(bytes); }
    static void validatePosition(BlockKey p) {
        if (p.x() < -30000000 || p.x() >= 30000000 || p.z() < -30000000 || p.z() >= 30000000
                || p.y() < -2048 || p.y() >= 2048) throw invalid();
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid construction registry data"); }
}
