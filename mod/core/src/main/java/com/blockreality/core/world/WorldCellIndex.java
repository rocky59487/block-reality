package com.blockreality.core.world;

import com.blockreality.api.geom.BlockKey;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Durable observation coverage, not physical connectivity or a solver model. Main-thread owned. */
public final class WorldCellIndex {
    public static final int MAX_CELLS = 131072;
    public static final int MAX_BYTES = 16 + 12 * MAX_CELLS + 32;
    private static final int MAGIC = 0x57524958, VERSION = 1;
    private static final Comparator<BlockKey> ORDER = Comparator.comparingInt(BlockKey::x)
            .thenComparingInt(BlockKey::y).thenComparingInt(BlockKey::z);
    private final NavigableSet<BlockKey> cells = new TreeSet<>(ORDER);
    private final Map<Long, Set<BlockKey>> chunks = new HashMap<>();
    public record Chunk(int x, int z) { }
    private final Map<Chunk, Integer> observations = new HashMap<>();
    private List<Chunk> observationSnapshot;
    private boolean capacityExceeded;
    private long generation;
    private List<BlockKey> snapshot;

    public int size() { return cells.size(); }
    public boolean contains(BlockKey pos) { return cells.contains(pos); }
    public boolean refused() { return capacityExceeded; }
    public long generation() { return generation; }
    public List<BlockKey> cells() {
        if (snapshot == null) snapshot = List.copyOf(cells);
        return snapshot;
    }
    public List<Chunk> observationChunks() {
        if (observationSnapshot == null) observationSnapshot = observations.keySet().stream()
                .sorted(Comparator.comparingInt(Chunk::x).thenComparingInt(Chunk::z)).toList();
        return observationSnapshot;
    }

    public boolean add(BlockKey pos) {
        validate(pos);
        if (capacityExceeded || cells.contains(pos)) return false;
        if (cells.size() == MAX_CELLS) { refuse(); return false; }
        insert(pos); changed(); return true;
    }

    public boolean remove(BlockKey pos) {
        if (capacityExceeded || !cells.remove(pos)) return false;
        observe(pos, -1);
        long key = chunk(pos);
        Set<BlockKey> set = chunks.get(key);
        set.remove(pos);
        if (set.isEmpty()) chunks.remove(key);
        changed(); return true;
    }

    /** Only call with a COMPLETE observed chunk, never with its currently visible subset. */
    public boolean replaceChunk(int x, int z, Collection<BlockKey> observed) {
        if (capacityExceeded) return false;
        if (observed.size() > MAX_CELLS) { refuse(); return false; }
        var next = new HashSet<BlockKey>();
        for (BlockKey pos : observed) {
            validate(pos);
            if ((pos.x() >> 4) != x || (pos.z() >> 4) != z || !next.add(pos))
                throw new IllegalArgumentException("invalid or duplicate chunk observation");
        }
        long key = chunk(x, z);
        Set<BlockKey> previous = chunks.getOrDefault(key, Set.of());
        if (cells.size() - previous.size() + next.size() > MAX_CELLS) { refuse(); return false; }
        if (previous.equals(next)) return false;
        for (BlockKey pos : previous) observe(pos, -1);
        cells.removeAll(previous);
        chunks.remove(key);
        for (BlockKey pos : next) insert(pos);
        changed(); return true;
    }

    /** Structure OR one of its horizontal ground-observation faces touches this chunk. */
    public boolean observesChunk(int x, int z) {
        return observations.containsKey(new Chunk(x, z));
    }

    public byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(16 + cells.size() * 12 + 32);
        b.putInt(MAGIC).putInt(VERSION).putInt(cells.size()).putInt(capacityExceeded ? 1 : 0);
        for (BlockKey p : cells) b.putInt(p.x()).putInt(p.y()).putInt(p.z());
        b.put(digest(b.array(), b.position()));
        return b.array();
    }

    public static WorldCellIndex decode(byte[] bytes) {
        if (bytes.length < 48 || bytes.length > MAX_BYTES) throw invalid();
        ByteBuffer b = ByteBuffer.wrap(bytes);
        if (b.getInt() != MAGIC || b.getInt() != VERSION) throw invalid();
        int count = b.getInt(), refused = b.getInt();
        if (count < 0 || count > MAX_CELLS || bytes.length != 48 + 12 * count
                || (refused != 0 && refused != 1)) throw invalid();
        if (!MessageDigest.isEqual(digest(bytes, bytes.length - 32),
                Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length))) throw invalid();
        WorldCellIndex index = new WorldCellIndex();
        BlockKey previous = null;
        for (int i = 0; i < count; i++) {
            BlockKey pos = new BlockKey(b.getInt(), b.getInt(), b.getInt());
            validate(pos);
            if (previous != null && ORDER.compare(previous, pos) >= 0) throw invalid();
            index.insert(pos);
            previous = pos;
        }
        index.capacityExceeded = refused == 1;
        return index;
    }

    private void insert(BlockKey pos) {
        cells.add(pos);
        chunks.computeIfAbsent(chunk(pos), ignored -> new HashSet<>()).add(pos);
        observe(pos, 1);
    }
    private void observe(BlockKey p, int delta) {
        int x = p.x() >> 4, z = p.z() >> 4;
        observe(new Chunk(x, z), delta);
        if ((p.x() & 15) == 0) observe(new Chunk(x - 1, z), delta);
        if ((p.x() & 15) == 15) observe(new Chunk(x + 1, z), delta);
        if ((p.z() & 15) == 0) observe(new Chunk(x, z - 1), delta);
        if ((p.z() & 15) == 15) observe(new Chunk(x, z + 1), delta);
    }
    private void observe(Chunk chunk, int delta) {
        int count = observations.getOrDefault(chunk, 0) + delta;
        if (count == 0) observations.remove(chunk); else observations.put(chunk, count);
        observationSnapshot = null;
    }
    private void refuse() { capacityExceeded = true; changed(); }
    private void changed() { generation++; snapshot = null; }
    private static long chunk(BlockKey p) { return chunk(p.x() >> 4, p.z() >> 4); }
    private static long chunk(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    private static void validate(BlockKey p) {
        Objects.requireNonNull(p);
        if (p.x() < -30000000 || p.x() >= 30000000 || p.z() < -30000000 || p.z() >= 30000000
                || p.y() < -2048 || p.y() >= 2048) throw invalid();
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid world cell index; original data must be retained");
    }
    private static byte[] digest(byte[] bytes, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes, 0, length); return digest.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
