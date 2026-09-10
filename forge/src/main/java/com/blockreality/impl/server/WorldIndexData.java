package com.blockreality.impl.server;

import com.blockreality.core.world.WorldCellIndex;
import com.blockreality.core.world.ConstructionDeclaration;
import com.blockreality.core.world.ConstructionLedger;
import com.blockreality.core.diagnostics.PipelineProfile;
import static com.blockreality.core.diagnostics.PipelineProfile.Stage.*;
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
import java.io.OutputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
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
    void tickObjects(net.minecraft.server.MinecraftServer server, java.util.function.BooleanSupplier live,
                     PipelineProfile profile) {
        if (objectsInFlight || !failure().isEmpty() || objects.cellCount() != index.size() || !objects.pending()) return;
        ConstructionLedger.Work work;
        try (var ignored = profile.begin(METADATA_CAPTURE)) { work = objects.work(); }
        objectsInFlight = true;
        var queued = profile.begin(METADATA_QUEUE);
        var profileContext = profile.capture();
        boolean submitted = AnalysisExecutor.submit(() -> profile.run(profileContext, () -> SolveDispatch.run(
                () -> {
                    queued.close();
                    try (var ignored = profile.begin(METADATA_RECONCILE)) { return ConstructionLedger.reconcile(work); }
                },
                result -> server.execute(() -> profile.run(profileContext, () -> {
                    try (var ignored = profile.begin(METADATA_PUBLISH)) { if (live.getAsBoolean()) publishObjects(result); }
                    finally { objectsInFlight = false; }
                })), () -> objectsInFlight = false,
                (message, error) -> com.blockreality.impl.BlockRealityMod.LOG.error("object bookkeeping: " + message, error))));
        if (!submitted) { queued.close(); objectsInFlight = false; }
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
            writeCompressed(root, temporary);
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
    // Vanilla NBT/gzip framing, with less compression work before synchronous persistence.
    /** A construction baseline needs an acknowledgment, including a bounded exact decoded readback. */
    void checkpoint(Path target) throws IOException {
        if (!failure().isEmpty()) throw new IOException("World coverage is not available for checkpoint");
        save(target.toFile());
        if (isDirty() || !failure().isEmpty()) throw new IOException("World coverage checkpoint write failed");
        try (FileChannel channel = FileChannel.open(target,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
        if (!System.getProperty("os.name","").startsWith("Windows"))
            try (FileChannel directory = FileChannel.open(target.toAbsolutePath().getParent(),StandardOpenOption.READ)) { directory.force(true); }
        CompoundTag expected = new CompoundTag(); expected.put("data",save(new CompoundTag())); NbtUtils.addCurrentDataVersion(expected);
        CountingDigest wanted = new CountingDigest(); NbtIo.write(expected,new DataOutputStream(wanted));
        CountingDigest actual = new CountingDigest();
        try (var input = new java.util.zip.GZIPInputStream(Files.newInputStream(target,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = input.read(buffer,0,(int)Math.min(buffer.length,wanted.bytes-actual.bytes+1))) != -1) {
                actual.write(buffer,0,count);
                if (actual.bytes > wanted.bytes) throw new IOException("World coverage checkpoint exceeds expected image");
            }
        }
        if (actual.bytes != wanted.bytes || !java.util.Arrays.equals(actual.digest.digest(),wanted.digest.digest()))
            throw new IOException("World coverage checkpoint readback mismatch");
    }
    private static final class CountingDigest extends OutputStream {
        private final java.security.MessageDigest digest;
        private long bytes;
        CountingDigest() {
            try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
        @Override public void write(int value) { digest.update((byte)value); bytes++; }
        @Override public void write(byte[] value, int offset, int length) { digest.update(value,offset,length); bytes += length; }
    }
    void writeCompressed(CompoundTag root, Path temporary) throws IOException {
        try (OutputStream file = Files.newOutputStream(temporary);
             var gzip = new RegistryGzip(file);
             var output = new DataOutputStream(new BufferedOutputStream(gzip))) {
            NbtIo.write(root, output);
        }
    }
    private static final class RegistryGzip extends GZIPOutputStream {
        RegistryGzip(OutputStream output) throws IOException {
            super(output, 32768);
            def.setLevel(Deflater.BEST_SPEED);
        }
    }
    // A named I/O seam also permits a real disk failure test; no unsafe non-atomic fallback.
    void replace(Path temporary, Path target) throws IOException {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
