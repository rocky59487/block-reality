package com.blockreality.impl.server.construction;

import com.mojang.datafixers.util.Either;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Read an already-forced participant on its existing worker, with bounds before NBT allocation. */
final class BoundedChunkRead {
    private BoundedChunkRead() { }

    /** The owner excludes worker close and other saves throughout the transaction/recovery barrier. */
    static CompletableFuture<Optional<CompoundTag>> load(IOWorker worker, ChunkPos pos) {
        Objects.requireNonNull(worker); Objects.requireNonNull(pos);
        return worker.submitTask(() -> {
            try {
                if (worker.pendingWrites.containsKey(pos)) throw new IOException("Chunk write is still pending");
                // Access stays on the mailbox thread; no competing cache or region handle is created.
                try (var stream = worker.storage.getRegionFile(pos).getChunkDataInputStream(pos)) {
                    return Either.left(stream == null ? Optional.empty() : Optional.of(parse(stream)));
                }
            } catch (Exception failure) { return Either.right(failure); }
        });
    }

    static CompoundTag parse(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(ChunkFileParticipant.MAX_CHUNK_BYTES + 1);
        if (bytes.length > ChunkFileParticipant.MAX_CHUNK_BYTES) throw new IOException("Decoded chunk byte capacity exceeded");
        return CanonicalNbt.read(bytes,ChunkFileParticipant.MAX_CHUNK_BYTES);
    }
}
