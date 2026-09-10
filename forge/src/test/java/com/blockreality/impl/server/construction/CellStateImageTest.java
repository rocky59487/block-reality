package com.blockreality.impl.server.construction;

import com.blockreality.core.transaction.ConstructionTransaction.Value;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class CellStateImageTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @Test void exactRegistryStatesRoundTripIncludingAirAndAxisProperties() throws Exception {
        for (var state : new net.minecraft.world.level.block.state.BlockState[]{Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState(),
                Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS,Direction.Axis.Z)})
            assertSame(state,CellStateImage.decode(CellStateImage.encode(state)));
    }
    @Test void unknownBlocksPropertiesAndTypesCannotBecomeDefaults() throws Exception {
        CompoundTag original = NbtUtils.writeBlockState(Blocks.OAK_LOG.defaultBlockState());
        for (int fault=0;fault<7;fault++) {
            CompoundTag changed = original.copy();
            switch (fault) {
                case 0 -> changed.putString("Name","missing:unknown");
                case 1 -> changed.remove("Name");
                case 2 -> changed.getCompound("Properties").putString("axis","invalid");
                case 3 -> changed.getCompound("Properties").putString("invented","yes");
                case 4 -> changed.putString("Properties","wrong type");
                case 5 -> changed.getCompound("Properties").putInt("axis",1);
                case 6 -> changed.putString("extra","not part of a block state");
            }
            Value value = Value.of(CanonicalNbt.encode(changed,1 << 20));
            assertThrows(IOException.class,()->CellStateImage.decode(value));
        }
        assertThrows(IOException.class,()->CellStateImage.decode(Value.missing()));
        assertThrows(IOException.class,()->CellStateImage.decode(Value.of(new byte[0])));
    }
    @Test void canonicalCellKeysHaveExactBoundsAndNoAlternativeIntegerSpellings() throws Exception {
        for (BlockPos pos : new BlockPos[]{new BlockPos(-30000000,-2048,-30000000),new BlockPos(29999999,2047,29999999),BlockPos.ZERO})
            assertEquals(pos,CellStateImage.position(CellStateImage.key(pos)));
        for (String key : new String[]{"cell/30000000/0/0","cell/-30000001/0/0","cell/0/2048/0","cell/0/-2049/0","cell/0/0/30000000",
                "cell/+1/0/0","cell/01/0/0","cell/-0/0/0","cell/0/0/0/","cell/0//0","other/0/0/0","cell/2147483648/0/0"})
            assertThrows(IOException.class,()->CellStateImage.position(key));
    }
}
