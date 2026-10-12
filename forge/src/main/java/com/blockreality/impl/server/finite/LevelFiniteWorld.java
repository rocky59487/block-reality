package com.blockreality.impl.server.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.world.ConstructionDeclaration;
import com.blockreality.impl.block.StructuralBlock;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The production {@link FiniteObservation.World} over a loaded {@link ServerLevel}:
 * every probe uses {@code getChunkNow} so a bootstrap observation never force-loads a
 * chunk, never generates terrain and never mutates the world. The live declaration is
 * read the same way {@code StructureManager.declaration} reads it — material and
 * section tokens plus the placed axis (undeclared → {@code -1}).
 */
final class LevelFiniteWorld implements FiniteObservation.World {

    private final ServerLevel level;

    LevelFiniteWorld(ServerLevel level) {
        this.level = Objects.requireNonNull(level);
    }

    @Override public FiniteObservation.Cell cell(BlockKey position) {
        BlockPos pos = new BlockPos(position.x(), position.y(), position.z());
        if (level.isOutsideBuildHeight(pos)) return new FiniteObservation.Cell.Outside();
        LevelChunk chunk = level.getChunkSource().getChunkNow(position.x() >> 4, position.z() >> 4);
        if (chunk == null) return new FiniteObservation.Cell.Unloaded();
        var state = chunk.getBlockState(pos);
        if (state.isAir()) return new FiniteObservation.Cell.Air();
        if (state.getBlock() instanceof StructuralBlock structural) {
            var axis = state.getValue(StructuralBlock.AXIS);
            return new FiniteObservation.Cell.Structural(new ConstructionDeclaration(
                    structural.materialToken(), structural.sectionToken(),
                    axis == StructuralBlock.Axis.UNDECLARED ? -1 : axis.wire()));
        }
        return new FiniteObservation.Cell.Other();
    }

    @Override public boolean sturdy(BlockKey position, BlockKey towards) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(position.x() >> 4, position.z() >> 4);
        if (chunk == null) return false;
        BlockPos pos = new BlockPos(position.x(), position.y(), position.z());
        Direction face = Direction.getNearest(towards.x() - position.x(),
                towards.y() - position.y(), towards.z() - position.z());
        return chunk.getBlockState(pos).isFaceSturdy(level, pos, face);
    }
}
