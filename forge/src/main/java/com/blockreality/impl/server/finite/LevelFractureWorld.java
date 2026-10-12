package com.blockreality.impl.server.finite;

import com.blockreality.core.transaction.ConstructionTransaction.Receipt;
import com.blockreality.core.transaction.ConstructionTransaction.Value;
import com.blockreality.impl.server.construction.ConstructionService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The production {@link ForgeFiniteFractureHost.World}: a {@code ServerLevel} bound to
 * one finite dimension. Cell images use the shared construction codec
 * ({@link ConstructionService#encodeCellImage}/{@link #decodeCellImage}); chunk
 * before/after images persist through the same bounded {@code ChunkFileParticipant}
 * path construction uses; the pending-body world mark is an invisible
 * {@code structure_void} on an air cell — a named reservation, never a rigid body
 * (doc §9.4).
 *
 * <p>Threading: every method must run on the server thread — the host's access
 * guard asserts it before any call lands here.
 */
final class LevelFractureWorld implements ForgeFiniteFractureHost.World {

    private final ServerLevel level;
    /** Cells this adapter applied since construction — the S2C notice set. */
    private final Set<BlockPos> applied = new LinkedHashSet<>();
    /** Pending-body anchor positions marked since construction. */
    private final Set<BlockPos> marked = new LinkedHashSet<>();

    LevelFractureWorld(ServerLevel level) {
        this.level = Objects.requireNonNull(level);
    }

    @Override public Value readCell(String cellKey) throws IOException {
        BlockPos pos = ConstructionService.cellPosition(cellKey);
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) throw new IOException("Fracture cell is in an unloaded chunk " + cellKey);
        BlockState state = chunk.getBlockState(pos);
        if (state.isAir()) return Value.missing();
        return ConstructionService.encodeCellImage(state);
    }

    @Override public void applyCell(String cellKey, Value image) throws IOException {
        BlockPos pos = ConstructionService.cellPosition(cellKey);
        if (!image.present()) {
            if (level.getBlockEntity(pos) != null) level.removeBlockEntity(pos);
            if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3))
                throw new IOException("Fracture cell removal refused " + cellKey);
        } else {
            BlockState desired = ConstructionService.decodeCellImage(image);
            if (!level.setBlock(pos, desired, 3))
                throw new IOException("Fracture cell write refused " + cellKey);
        }
        applied.add(pos);
    }

    /**
     * Named mutation sources a static image cannot capture or reverse (doc §9.8
     * rejection leg): an in-flight piston entity, a scheduled block tick and a
     * scheduled fluid tick are all refused by name. A regular block entity is
     * captured inside the full chunk baseline, so it is not a blocker.
     */
    @Override public Set<String> uncapturable(String cellKey) throws IOException {
        BlockPos pos = ConstructionService.cellPosition(cellKey);
        var blockers = new LinkedHashSet<String>();
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) { blockers.add("unloaded-chunk"); return Set.copyOf(blockers); }
        BlockState state = chunk.getBlockState(pos);
        if (chunk.getBlockEntities().get(pos) instanceof PistonMovingBlockEntity)
            blockers.add("moving-piston");
        if (level.getBlockTicks().hasScheduledTick(pos, state.getBlock()))
            blockers.add("scheduled-block-tick");
        if (level.getFluidTicks().hasScheduledTick(pos, state.getFluidState().getType()))
            blockers.add("scheduled-fluid-tick");
        return Set.copyOf(blockers);
    }

    @Override public void captureBaseline(Set<String> chunkKeys) throws IOException {
        ConstructionService.persistLiveChunkImages(level, parse(chunkKeys));
    }

    @Override public void persistAfter(Set<String> chunkKeys) throws IOException {
        ConstructionService.persistLiveChunkImages(level, parse(chunkKeys));
    }

    /**
     * The world-side pending-body mark (doc §9.4): decode the durable record, and
     * when the anchor cell is air place a {@code structure_void} — a collisionless,
     * invisible reservation that honestly says "pending body", never a fake block.
     * An occupied anchor keeps only the durable record (no silent overwrite).
     */
    @Override public void markBody(String bodyKey, Value record) throws IOException {
        if (record == null || !record.present() || record.bytes().length != ForgeFiniteFractureHost.BODY_RECORD_BYTES)
            throw new IOException("Foreign pending-body record " + bodyKey);
        var buffer = ByteBuffer.wrap(record.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        var pos = new BlockPos(buffer.getInt(), buffer.getInt(), buffer.getInt());
        buffer.getLong(); // artifact id — durable identity lives in the record
        if (buffer.getInt() != ForgeFiniteFractureHost.BODY_STATE_PENDING || buffer.getInt() != 0)
            throw new IOException("Pending-body record carries an unknown state " + bodyKey);
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null && chunk.getBlockState(pos).isAir()
                && level.setBlock(pos, Blocks.STRUCTURE_VOID.defaultBlockState(), 3))
            marked.add(pos);
    }

    /** Post-commit notice: one block update per applied cell and body mark (doc §9.3). */
    @Override public void publishNotice(Receipt receipt) throws IOException {
        var air = Blocks.AIR.defaultBlockState();
        for (BlockPos pos : applied) level.sendBlockUpdated(pos, air, air, 2);
        for (BlockPos pos : marked) level.sendBlockUpdated(pos, air, air, 2);
    }

    private static List<ChunkPos> parse(Set<String> chunkKeys) throws IOException {
        var positions = new java.util.ArrayList<ChunkPos>(chunkKeys.size());
        for (String key : chunkKeys) {
            String[] parts = key.split("/");
            if (parts.length != 3 || !parts[0].equals("chunk"))
                throw new IOException("Not a chunk baseline key " + key);
            try { positions.add(new ChunkPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]))); }
            catch (NumberFormatException failure) { throw new IOException("Malformed chunk baseline key " + key); }
        }
        return List.copyOf(positions);
    }
}
