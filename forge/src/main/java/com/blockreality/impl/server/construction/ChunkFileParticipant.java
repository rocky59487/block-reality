package com.blockreality.impl.server.construction;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Acknowledged complete chunk images on the level's existing region worker.
 * The construction host must supply complete images and exclude other saves/mutations;
 * this class does not make ChunkSerializer's swallowed serialization failures safe.
 * It never owns/closes the worker, mutates a live chunk, or chooses a journal decision.
 */
final class ChunkFileParticipant {
    static final int MAX_CHUNKS = 256, MAX_CHUNK_BYTES = CanonicalNbt.MAX_DOCUMENT_BYTES;
    static final long MAX_BATCH_BYTES = 64L * 1024 * 1024;
    interface Storage {
        CompletableFuture<Void> store(ChunkPos pos, CompoundTag image);
        CompletableFuture<Void> synchronize(boolean force);
        CompletableFuture<Optional<CompoundTag>> load(ChunkPos pos);
    }
    private final Storage storage;
    private final Thread owner = Thread.currentThread();
    private final int dataVersion;
    private final int chunkLimit;
    private final long byteLimit;
    private boolean ready;

    static ChunkFileParticipant bind(ServerLevel level) throws IOException {
        Objects.requireNonNull(level);
        if (!level.getServer().isSameThread()) throw new IOException("Chunk persistence requires the server thread");
        if (!(level.getChunkSource().chunkMap.chunkScanner() instanceof IOWorker worker))
            throw new IOException("Unsupported chunk storage worker");
        return new ChunkFileParticipant(storage(worker), SharedConstants.getCurrentVersion().getDataVersion().getVersion());
    }

    static Storage storage(IOWorker worker) {
        Objects.requireNonNull(worker);
        return new Storage() {
            public CompletableFuture<Void> store(ChunkPos pos, CompoundTag image) { return worker.store(pos,image); }
            public CompletableFuture<Void> synchronize(boolean force) { return worker.synchronize(force); }
            public CompletableFuture<Optional<CompoundTag>> load(ChunkPos pos) { return worker.loadAsync(pos); }
        };
    }

    ChunkFileParticipant(Storage storage, int dataVersion) throws IOException {
        this(storage,dataVersion,MAX_CHUNKS,MAX_BATCH_BYTES);
    }
    ChunkFileParticipant(Storage storage, int dataVersion, int chunkLimit, long byteLimit) throws IOException {
        this.storage = Objects.requireNonNull(storage); this.dataVersion = dataVersion;
        if (dataVersion < 1 || chunkLimit < 1 || chunkLimit > MAX_CHUNKS || byteLimit < 1 || byteLimit > MAX_BATCH_BYTES)
            throw new IllegalArgumentException("Invalid chunk persistence bounds");
        this.chunkLimit = chunkLimit; this.byteLimit = byteLimit;
        // A new adapter must drain/force the same worker before observing recovery state.
        await(storage.synchronize(true)); ready = true;
    }

    static final class Batch {
        private final ChunkFileParticipant source;
        private final Map<ChunkPos,byte[]> images;
        private Batch(ChunkFileParticipant source, Map<ChunkPos,byte[]> images) {
            this.source = source; this.images = Collections.unmodifiableMap(new LinkedHashMap<>(images));
        }
        Set<ChunkPos> positions() { return images.keySet(); }
        CompoundTag image(ChunkPos pos) throws IOException {
            byte[] bytes = images.get(pos);
            if (bytes == null) throw new IllegalArgumentException("Chunk is not in this batch");
            return CanonicalNbt.decode(bytes,MAX_CHUNK_BYTES);
        }
    }

    /** Pure, bounded preparation: validate/clone every image before any storage operation. */
    Batch prepare(Map<ChunkPos,CompoundTag> desired) throws IOException {
        check(); Objects.requireNonNull(desired);
        if (desired.isEmpty() || desired.size() > chunkLimit) throw new IOException("Chunk participant capacity exceeded");
        var images = new LinkedHashMap<ChunkPos,byte[]>(); long bytes = 0;
        for (var entry : desired.entrySet()) {
            byte[] encoded = encode(entry.getKey(),entry.getValue()); bytes += encoded.length;
            if (bytes > byteLimit) throw new IOException("Chunk participant byte capacity exceeded");
            images.put(entry.getKey(),encoded);
        }
        return new Batch(this,images);
    }

    /** Checkpoint, after-image and rollback writes share the same durability path. */
    void persist(Batch batch) throws IOException {
        check(); Objects.requireNonNull(batch);
        if (batch.source != this) throw new IOException("Foreign chunk persistence batch");
        // Decode all private copies before issuing a store; storage never receives caller-owned tags.
        var tags = new LinkedHashMap<ChunkPos,CompoundTag>();
        for (ChunkPos pos : batch.positions()) tags.put(pos,batch.image(pos));
        var issued = new ArrayList<CompletableFuture<Void>>(tags.size());
        ready = false;
        Throwable failure = null;
        try {
            for (var entry : tags.entrySet()) issued.add(Objects.requireNonNull(storage.store(entry.getKey(),entry.getValue())));
        } catch (RuntimeException | Error problem) { failure = problem; }
        // Even a later synchronous issuance failure cannot let an older future outlive this call.
        for (CompletableFuture<Void> future : issued) {
            try { await(future); }
            catch (IOException | RuntimeException | Error problem) { failure = append(failure,problem); }
        }
        try { await(storage.synchronize(true)); }
        catch (IOException | RuntimeException | Error problem) { failure = append(failure,problem); }
        if (failure != null) rethrow(failure);
        for (var entry : batch.images.entrySet()) {
            CompoundTag actual = await(storage.load(entry.getKey())).orElseThrow(() -> new IOException("Stored chunk missing"));
            if (!Arrays.equals(entry.getValue(),encode(entry.getKey(),actual))) throw new IOException("Stored chunk image mismatch");
        }
        ready = true;
    }

    /** A verified complete disk baseline, never a missing-chunk-as-air approximation. */
    Batch read(Collection<ChunkPos> positions) throws IOException {
        check(); Objects.requireNonNull(positions);
        if (positions.isEmpty() || positions.size() > chunkLimit) throw new IOException("Chunk participant capacity exceeded");
        var keys = new LinkedHashSet<ChunkPos>();
        for (ChunkPos pos : positions) {
            position(pos); if (!keys.add(pos)) throw new IOException("Duplicate chunk participant");
        }
        ready = false;
        await(storage.synchronize(true));
        var images = new LinkedHashMap<ChunkPos,byte[]>(); long bytes = 0;
        for (ChunkPos pos : keys) {
            CompoundTag actual = await(storage.load(pos)).orElseThrow(() -> new IOException("Chunk baseline missing"));
            byte[] encoded = encode(pos,actual); bytes += encoded.length;
            if (bytes > byteLimit) throw new IOException("Chunk participant byte capacity exceeded");
            images.put(pos,encoded);
        }
        ready = true; return new Batch(this,images);
    }

    private byte[] encode(ChunkPos pos, CompoundTag image) throws IOException {
        position(pos);
        if (image == null || !image.contains("xPos",Tag.TAG_INT) || !image.contains("zPos",Tag.TAG_INT)
                || image.getInt("xPos") != pos.x || image.getInt("zPos") != pos.z
                || !image.contains("yPos",Tag.TAG_INT) || image.getInt("yPos") < -128 || image.getInt("yPos") > 127
                || !image.contains("Status",Tag.TAG_STRING) || !image.getString("Status").equals("minecraft:full")
                || !image.contains("DataVersion",Tag.TAG_INT) || image.getInt("DataVersion") != dataVersion
                || !image.contains("sections",Tag.TAG_LIST))
            throw new IOException("Foreign or invalid full chunk image");
        // getList returns an empty fallback on type mismatch, so inspect the original list too.
        var sections = (net.minecraft.nbt.ListTag) image.get("sections");
        if (!sections.isEmpty() && sections.getElementType() != Tag.TAG_COMPOUND)
            throw new IOException("Invalid chunk section list");
        return CanonicalNbt.encode(image,MAX_CHUNK_BYTES);
    }
    private static void position(ChunkPos pos) throws IOException {
        if (pos == null || pos.x < -1875000 || pos.x >= 1875000 || pos.z < -1875000 || pos.z >= 1875000)
            throw new IOException("Invalid chunk coordinate");
    }
    private void check() throws IOException {
        if (Thread.currentThread() != owner) throw new IOException("Chunk persistence requires its owner thread");
        if (!ready) throw new IOException("Chunk persistence requires a new recovery adapter");
    }
    private static <T> T await(CompletableFuture<T> future) throws IOException {
        try { return Objects.requireNonNull(future).join(); }
        catch (CompletionException failure) { throw new IOException("Chunk storage operation failed",failure.getCause()); }
    }
    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        if (next != first) first.addSuppressed(next); return first;
    }
    private static void rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException io) throw io;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new IOException("Chunk persistence failed",failure);
    }
}
