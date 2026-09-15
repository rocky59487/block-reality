package com.blockreality.impl.server.construction;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class LiveChunkCaptureTest {
    private static CompoundTag image() {
        CompoundTag image = new CompoundTag(); ListTag entities = new ListTag();
        for (int x : new int[]{3,1}) {
            CompoundTag entity = new CompoundTag(); entity.putString("id","minecraft:chest");
            entity.putInt("x",x); entity.putInt("y",80); entity.putInt("z",2); entity.putBoolean("keepPacked",false);
            CompoundTag data = new CompoundTag(); data.putString("name","完整資料");
            data.putLongArray("bits",new long[]{-1,Long.MIN_VALUE,Long.MAX_VALUE});
            entity.put("ForgeCaps",data); entities.add(entity);
        }
        image.put("block_entities",entities);
        CompoundTag caps = new CompoundTag(); caps.putInt("custom:value",17); image.put("ForgeCaps",caps);
        image.putLong("LastUpdate",100); return image;
    }

    @Test void exactEntitiesAndCapabilitiesAllowDifferentListAndCompoundOrder() throws Exception {
        CompoundTag original = image(), reordered = original.copy();
        var entities = reordered.getList("block_entities",Tag.TAG_COMPOUND);
        var first = entities.remove(0); entities.add(first);
        LiveChunkCapture.verifyWitness(original,reordered);
        reordered.putString("custom:save","preserved hook data");
        LiveChunkCapture.verifyWitness(original,reordered);
        assertEquals("preserved hook data",reordered.getString("custom:save"));
    }

    @Test void droppedOrChangedChunkCapabilitiesNeverBecomeAnEmptySuccess() {
        CompoundTag original = image();
        for (int fault=0;fault<4;fault++) {
            CompoundTag changed = original.copy();
            switch (fault) {
                case 0 -> changed.remove("ForgeCaps");
                case 1 -> changed.put("ForgeCaps",new CompoundTag());
                case 2 -> changed.getCompound("ForgeCaps").putInt("custom:value",18);
                case 3 -> changed.putString("ForgeCaps","wrong type");
            }
            assertThrows(IOException.class,()->LiveChunkCapture.verifyWitness(original,changed));
        }
        CompoundTag absent = original.copy(); absent.remove("ForgeCaps");
        assertThrows(IOException.class,()->LiveChunkCapture.verifyWitness(absent,original));
    }

    @Test void omittedChangedDuplicateAndForeignBlockEntitiesRefuse() {
        CompoundTag original = image();
        for (int fault=0;fault<5;fault++) {
            CompoundTag changed = original.copy(); var entities = changed.getList("block_entities",Tag.TAG_COMPOUND);
            switch (fault) {
                case 0 -> entities.remove(0);
                case 1 -> entities.getCompound(0).remove("ForgeCaps");
                case 2 -> entities.add(entities.getCompound(0).copy());
                case 3 -> entities.getCompound(0).putInt("x",500);
                case 4 -> entities.getCompound(0).getCompound("ForgeCaps").putString("name","lost");
            }
            assertThrows(IOException.class,()->LiveChunkCapture.verifyWitness(original,changed));
        }
    }

    @Test void entityTypePositionAndPackedFlagsAreStrict() {
        CompoundTag original = image();
        for (int fault=0;fault<6;fault++) {
            CompoundTag changed = original.copy(); var entity = changed.getList("block_entities",Tag.TAG_COMPOUND).getCompound(0);
            switch (fault) {
                case 0 -> changed.putString("block_entities","invalid");
                case 1 -> entity.putString("x","3");
                case 2 -> entity.remove("id");
                case 3 -> entity.putBoolean("keepPacked",true);
                case 4 -> entity.putInt("keepPacked",0);
                case 5 -> entity.remove("keepPacked");
            }
            assertThrows(IOException.class,()->LiveChunkCapture.verifyWitness(original,changed));
        }
    }

    @Test void hooksCanAddButCannotReplaceDeleteOrMutateExistingFields() throws Exception {
        CompoundTag original = image(), additive = original.copy();
        additive.putString("custom:save","保存"); LiveChunkCapture.verifyHook(original,additive);
        for (int fault=0;fault<4;fault++) {
            CompoundTag changed = original.copy();
            switch (fault) {
                case 0 -> changed.remove("LastUpdate");
                case 1 -> changed.putLong("LastUpdate",101);
                case 2 -> changed.putInt("LastUpdate",100);
                case 3 -> changed.getCompound("ForgeCaps").putInt("custom:value",18);
            }
            assertThrows(IOException.class,()->LiveChunkCapture.verifyHook(original,changed));
        }
    }
}
