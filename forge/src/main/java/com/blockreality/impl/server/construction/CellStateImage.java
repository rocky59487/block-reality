package com.blockreality.impl.server.construction;

import com.blockreality.core.transaction.ConstructionTransaction.Value;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;
import java.io.IOException;
import java.util.Arrays;

/** Exact registry-backed cell images. No fallback for unknown blocks/properties or physical interpretation. */
final class CellStateImage {
    private CellStateImage() { }
    static String key(BlockPos pos) throws IOException {
        if (pos.getX() < -30000000 || pos.getX() >= 30000000 || pos.getZ() < -30000000 || pos.getZ() >= 30000000
                || pos.getY() < -2048 || pos.getY() >= 2048) throw new IOException("Cell coordinate out of bounds");
        return "cell/"+pos.getX()+"/"+pos.getY()+"/"+pos.getZ();
    }
    static BlockPos position(String key) throws IOException {
        String[] parts = key.split("/",-1);
        if (parts.length != 4 || !parts[0].equals("cell")) throw new IOException("Unknown cell resource");
        try {
            BlockPos result = new BlockPos(Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),Integer.parseInt(parts[3]));
            if (!key(result).equals(key)) throw new IOException("Noncanonical cell resource");
            return result;
        } catch (NumberFormatException bad) { throw new IOException("Invalid cell resource",bad); }
    }
    static Value encode(BlockState state) throws IOException {
        return Value.of(CanonicalNbt.encode(NbtUtils.writeBlockState(state),1 << 20));
    }
    static BlockState decode(Value value) throws IOException {
        if (!value.present()) throw new IOException("A cell image cannot be missing");
        byte[] bytes = value.bytes();
        try {
            BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),CanonicalNbt.decode(bytes,1 << 20));
            if (!Arrays.equals(bytes,encode(state).bytes())) throw new IOException("Unknown or noncanonical block state");
            return state;
        } catch (RuntimeException failure) { throw new IOException("Invalid cell state image",failure); }
    }
}
