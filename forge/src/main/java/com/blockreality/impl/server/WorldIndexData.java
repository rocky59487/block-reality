package com.blockreality.impl.server;

import com.blockreality.core.world.WorldCellIndex;
import com.blockreality.core.world.ConstructionDeclaration;
import com.blockreality.core.world.ConstructionLedger;
import net.minecraft.core.BlockPos;
import com.blockreality.api.geom.BlockKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.function.Consumer;

/** Minecraft persistence adapter. A failed read can never silently become a fresh registry. */
class WorldIndexData extends SavedData implements Iterable<BlockPos> {
    static final String NAME = "blockreality_world_index";
    private final WorldCellIndex index;
    private final String readFailure;
    private String writeFailure = "";
    private ConstructionLedger objects = new ConstructionLedger();
    private volatile boolean objectsInFlight;

    WorldIndexData(WorldCellIndex index, String failure) {
        this.index = index; this.readFailure = failure;
    }
    static WorldIndexData fresh() { return new WorldIndexData(new WorldCellIndex(), ""); }
    static WorldIndexData load(CompoundTag tag) {
        if (!tag.contains("coverage", Tag.TAG_BYTE_ARRAY)) throw new IllegalArgumentException("missing coverage");
        var data = new WorldIndexData(WorldCellIndex.decode(tag.getByteArray("coverage")), "");
        if (tag.contains("objectsFormat")) {
            if (!tag.contains("objectsFormat", Tag.TAG_INT) || tag.getInt("objectsFormat") != 1
                    || !tag.contains("objects", Tag.TAG_BYTE_ARRAY)) throw new IllegalArgumentException("invalid object schema");
            data.objects = ConstructionLedger.decode(tag.getByteArray("objects"));
            for (BlockKey pos : data.objects.positions()) if (!data.index.contains(pos))
                throw new IllegalArgumentException("object declaration outside coverage");
        } else if (tag.contains("objects")) throw new IllegalArgumentException("missing object schema");
        return data;
    }
    static WorldIndexData open(DimensionDataStorage storage, Path dataFolder) {
        WorldIndexData saved = storage.get(WorldIndexData::load, NAME);
        if (saved != null) return saved;
        // get() catches malformed NBT, I/O and decoder errors and returns null for all of them.
        // Only a positively absent file authorizes creating new data. Never use computeIfAbsent.
        if (!Files.notExists(dataFolder.resolve(NAME + ".dat")))
            return new WorldIndexData(new WorldCellIndex(), "World index unreadable; original save retained");
        WorldIndexData created = fresh();
        created.setDirty(); storage.set(NAME, created);
        return created;
    }
    WorldCellIndex index() { return index; }
    int size() { return index.size(); }
    boolean isEmpty() { return index.size() == 0; }
    boolean contains(BlockPos pos) { return index.contains(key(pos)); }
    void add(BlockPos pos) { edit(i -> i.add(key(pos))); }
    void remove(BlockPos pos) {
        if (!readFailure.isEmpty()) return;
        if (index.remove(key(pos))) { objects.remove(key(pos)); setDirty(); }
    }
    void observe(BlockPos pos, ConstructionDeclaration declaration) {
        if (!readFailure.isEmpty()) return;
        long before = index.generation(); index.add(key(pos));
        boolean changed = index.contains(key(pos)) && objects.observe(key(pos), declaration);
        if (changed || before != index.generation() || !objects.failure().isEmpty()) setDirty();
    }
    void observeChunk(int x, int z, java.util.Map<BlockKey, ConstructionDeclaration> observed) {
        if (!readFailure.isEmpty()) return;
        var previous = index.cellsInChunk(x, z);
        long before = index.generation(); index.replaceChunk(x, z, observed.keySet());
        if (index.refused()) { if (before != index.generation()) setDirty(); return; }
        var gone = previous.stream().filter(p -> !observed.containsKey(p)).toList();
        boolean changed = false;
        for (BlockKey p : gone) changed |= objects.remove(p);
        for (var e : observed.entrySet()) changed |= objects.observe(e.getKey(), e.getValue());
        if (changed || before != index.generation() || !objects.failure().isEmpty()) setDirty();
    }
    ConstructionLedger objects() { return objects; }
    boolean objectsReady() { return failure().isEmpty() && objects.ready() && objects.cellCount() == index.size(); }
    String objectStatus() {
        if (!failure().isEmpty()) return "REFUSED";
        if (objects.cellCount() != index.size()) return "PENDING_DECLARATIONS";
        return objects.ready() ? "CURRENT" : "PENDING";
    }
    boolean publishObjects(ConstructionLedger.Completion completion) {
        if (!objects.publish(completion)) return false;
        setDirty(); return true;
    }
    void tickObjects(net.minecraft.server.MinecraftServer server, java.util.function.BooleanSupplier live) {
        if (objectsInFlight || !failure().isEmpty() || objects.cellCount() != index.size() || !objects.pending()) return;
        var work = objects.work(); objectsInFlight = true;
        boolean submitted = AnalysisExecutor.submit(() -> SolveDispatch.run(
                () -> ConstructionLedger.reconcile(work),
                result -> server.execute(() -> {
                    try { if (live.getAsBoolean()) publishObjects(result); }
                    finally { objectsInFlight = false; }
                }), () -> objectsInFlight = false,
                (message, error) -> com.blockreality.impl.BlockRealityMod.LOG.error("object bookkeeping: " + message, error)));
        if (!submitted) objectsInFlight = false;
    }
    java.util.List<BlockPos> positions() {
        return index.cells().stream().map(p -> new BlockPos(p.x(), p.y(), p.z())).toList();
    }
    @Override public java.util.Iterator<BlockPos> iterator() { return positions().iterator(); }
    static BlockKey key(BlockPos pos) { return new BlockKey(pos.getX(), pos.getY(), pos.getZ()); }
    String failure() {
        if (!readFailure.isEmpty()) return readFailure;
        if (!writeFailure.isEmpty()) return writeFailure;
        if (!objects.failure().isEmpty()) return objects.failure();
        return index.refused() ? "World index capacity exceeded (131072 cells); analysis suspended" : "";
    }
    void edit(Consumer<WorldCellIndex> edit) {
        if (!readFailure.isEmpty()) return;
        long before = index.generation();
        edit.accept(index);
        if (index.generation() != before) setDirty();
    }
    @Override public CompoundTag save(CompoundTag tag) {
        if (!readFailure.isEmpty()) throw new IllegalStateException(readFailure);
        tag.putByteArray("coverage", index.encode());
        tag.putInt("objectsFormat", 1); tag.putByteArray("objects", objects.encode()); return tag;
    }
    @Override public void save(File file) {
        if (!isDirty() || !readFailure.isEmpty()) return;
        Path target = file.toPath(), temporary = null;
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            temporary = Files.createTempFile(target.toAbsolutePath().getParent(), NAME + "-", ".tmp");
            CompoundTag root = new CompoundTag(); root.put("data", save(new CompoundTag()));
            NbtUtils.addCurrentDataVersion(root);
            NbtIo.writeCompressed(root, temporary.toFile());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            replace(temporary, target);
            setDirty(false); writeFailure = "";
        } catch (IOException e) {
            writeFailure = "World index save failed; previous file retained, retry pending";
            com.blockreality.impl.BlockRealityMod.LOG.error(writeFailure, e);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
    // A named I/O seam also permits a real disk failure test; no unsafe non-atomic fallback.
    void replace(Path temporary, Path target) throws IOException {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
