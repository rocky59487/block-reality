package com.blockreality.impl.server.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkDataEvent;
import java.io.IOException;
import java.util.*;

/** Complete, private live images; no storage, block mutation, journal decision or publication. */
final class LiveChunkCapture {
    private LiveChunkCapture() { }
    private static final int LIMIT = ChunkFileParticipant.MAX_CHUNK_BYTES;

    static CompoundTag capture(ServerLevel level, LevelChunk chunk) throws IOException {
        requireCurrent(level,chunk);
        try {
            CompoundTag witness = witness(chunk);
            CompoundTag image = ChunkSerializer.write(level,chunk);
            byte[] baseline = CanonicalNbt.encode(image,LIMIT);
            verifyWitness(witness,image);
            MinecraftForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk,level,image));
            verifyHook(CanonicalNbt.decode(baseline,LIMIT),image);
            byte[] result = CanonicalNbt.encode(image,LIMIT);

            requireCurrent(level,chunk);
            CompoundTag afterWitness = witness(chunk);
            CompoundTag after = ChunkSerializer.write(level,chunk);
            verifyWitness(afterWitness,after);
            if (!Arrays.equals(baseline,CanonicalNbt.encode(after,LIMIT)))
                throw new IOException("Live chunk changed during construction capture");
            // Serializers may retain tags; only independent canonical bytes leave this method.
            requireCurrent(level,chunk); entities(chunk);
            return CanonicalNbt.decode(result,LIMIT);
        } catch (IOException failure) { throw failure; }
        catch (RuntimeException failure) { throw new IOException("Live chunk serialization refused",failure); }
    }

    static void requireCurrent(ServerLevel level, LevelChunk chunk) throws IOException {
        Objects.requireNonNull(level); Objects.requireNonNull(chunk);
        if (!level.getServer().isSameThread() || chunk.getLevel() != level
                || level.getChunkSource().getChunkNow(chunk.getPos().x,chunk.getPos().z) != chunk)
            throw new IOException("Construction capture requires the current loaded chunk on its server thread");
    }

    /** Never call getBlockEntity: promoting a broken pending entry can erase its only saved image. */
    private static Map<BlockPos,BlockEntity> entities(LevelChunk chunk) throws IOException {
        var positions = chunk.getBlockEntitiesPos();
        var live = chunk.getBlockEntities();
        if (positions.size() > CanonicalNbt.MAX_TAGS || !positions.equals(live.keySet()))
            throw new IOException("Pending or inconsistent chunk block entities");
        var result = new TreeMap<BlockPos,BlockEntity>();
        for (BlockPos position : positions) {
            BlockEntity entity = live.get(position);
            if (entity == null || entity.isRemoved() || !entity.getBlockPos().equals(position)
                    || entity.getLevel() != chunk.getLevel() || chunk.getBlockEntityNbt(position) != null
                    || (position.getX() >> 4) != chunk.getPos().x || (position.getZ() >> 4) != chunk.getPos().z
                    || chunk.isOutsideBuildHeight(position))
                throw new IOException("Foreign, pending or removed chunk block entity");
            result.put(position.immutable(),entity);
        }
        return result;
    }

    @SuppressWarnings("deprecation") // The pinned Forge serializer uses this direct capability bridge too.
    private static CompoundTag witness(LevelChunk chunk) throws IOException {
        Map<BlockPos,BlockEntity> entities = entities(chunk);
        CompoundTag result = new CompoundTag(); ListTag tags = new ListTag(); long bytes = 0;
        for (var entry : entities.entrySet()) {
            CompoundTag saved = entry.getValue().saveWithFullMetadata();
            saved.putBoolean("keepPacked",false);
            if (!position(saved).equals(entry.getKey())) throw new IOException("Block entity serialized a foreign position");
            byte[] encoded = CanonicalNbt.encode(saved,LIMIT); bytes += encoded.length;
            if (bytes > LIMIT) throw new IOException("Block entity witness byte capacity exceeded");
            tags.add(CanonicalNbt.decode(encoded,LIMIT));
        }
        result.put("block_entities",tags);
        CompoundTag caps = chunk.writeCapsToNBT(); // Intentionally outside ChunkSerializer's swallowing catch.
        if (caps != null) result.put("ForgeCaps",caps);
        byte[] encoded = CanonicalNbt.encode(result,LIMIT);
        if (!entities.equals(entities(chunk))) throw new IOException("Block entity identity changed during capture");
        return CanonicalNbt.decode(encoded,LIMIT);
    }

    /** Independent witnesses also catch failures thrown only during the intervening vanilla call. */
    static void verifyWitness(CompoundTag witness, CompoundTag image) throws IOException {
        CanonicalNbt.encode(image,LIMIT);
        Map<BlockPos,byte[]> expected = savedEntities(witness), actual = savedEntities(image);
        if (!expected.keySet().equals(actual.keySet())) throw new IOException("Chunk serializer omitted block entities");
        for (BlockPos position : expected.keySet())
            if (!Arrays.equals(expected.get(position),actual.get(position)))
                throw new IOException("Chunk serializer changed block entity data");
        if (!Arrays.equals(capabilities(witness),capabilities(image)))
            throw new IOException("Chunk serializer omitted or changed capabilities");
    }

    static void verifyHook(CompoundTag baseline, CompoundTag image) throws IOException {
        CompoundTag retained = new CompoundTag();
        for (String key : baseline.getAllKeys()) {
            if (!image.contains(key)) throw new IOException("Chunk save hook removed a serialized field");
            retained.put(key,image.get(key));
        }
        if (!Arrays.equals(CanonicalNbt.encode(baseline,LIMIT),CanonicalNbt.encode(retained,LIMIT)))
            throw new IOException("Chunk save hook rewrote a serialized field");
    }

    private static byte[] capabilities(CompoundTag image) throws IOException {
        if (!image.contains("ForgeCaps")) return null;
        if (!(image.get("ForgeCaps") instanceof CompoundTag caps)) throw new IOException("Invalid chunk capabilities");
        return CanonicalNbt.encode(caps,LIMIT);
    }
    private static Map<BlockPos,byte[]> savedEntities(CompoundTag image) throws IOException {
        if (!(image.get("block_entities") instanceof ListTag tags)
                || (!tags.isEmpty() && tags.getElementType() != Tag.TAG_COMPOUND))
            throw new IOException("Invalid saved block entity list");
        var result = new HashMap<BlockPos,byte[]>();
        for (Tag tag : tags) {
            CompoundTag entity = (CompoundTag)tag;
            if (!entity.contains("id",Tag.TAG_STRING) || entity.getString("id").isEmpty()
                    || !entity.contains("keepPacked",Tag.TAG_BYTE) || entity.getByte("keepPacked") != 0)
                throw new IOException("Invalid live block entity image");
            if (result.put(position(entity),CanonicalNbt.encode(entity,LIMIT)) != null)
                throw new IOException("Duplicate saved block entity");
        }
        return result;
    }
    private static BlockPos position(CompoundTag tag) throws IOException {
        if (!tag.contains("x",Tag.TAG_INT) || !tag.contains("y",Tag.TAG_INT) || !tag.contains("z",Tag.TAG_INT))
            throw new IOException("Invalid block entity coordinates");
        return new BlockPos(tag.getInt("x"),tag.getInt("y"),tag.getInt("z"));
    }
}
