package com.blockreality.impl.server;

import com.blockreality.core.world.WorldCellIndex;
import com.blockreality.core.world.ConstructionDeclaration;
import com.blockreality.core.world.ConstructionLedger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorldIndexDataTest {
    @TempDir Path root;
    @BeforeAll static void version() { SharedConstants.tryDetectVersion(); }
    private WorldIndexData open(Path folder) {
        return WorldIndexData.open(new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer()), folder);
    }
    private Path file(Path folder) { return folder.resolve(WorldIndexData.NAME + ".dat"); }

    private static final ConstructionDeclaration X = new ConstructionDeclaration("steel","steel_rect_200x400",0);
    private static void settle(WorldIndexData data) {
        assertTrue(data.publishObjects(ConstructionLedger.reconcile(data.objects().work())));
        assertTrue(data.objectsReady());
    }

    @Test void gzipWriterReadsLegacyCompressionAndPreservesPendingMetadataForVanilla() throws Exception {
        var data=WorldIndexData.fresh();var pos=new BlockPos(1,200,8);
        data.observe(pos,X);settle(data);data.remove(pos);data.observe(pos,X);
        var expected=data.save(new CompoundTag());var legacy=new CompoundTag();legacy.put("data",expected);
        net.minecraft.nbt.NbtUtils.addCurrentDataVersion(legacy);
        net.minecraft.nbt.NbtIo.writeCompressed(legacy,file(root).toFile());
        var loaded=open(root);assertTrue(loaded.objects().pending());loaded.setDirty();
        loaded.save(file(root).toFile());assertFalse(loaded.isDirty());assertTrue(loaded.failure().isEmpty());
        var vanilla=net.minecraft.nbt.NbtIo.readCompressed(file(root).toFile());
        assertEquals(legacy.getInt("DataVersion"),vanilla.getInt("DataVersion"));
        assertEquals(expected,vanilla.getCompound("data"));
        var restored=open(root);assertTrue(restored.objects().pending());
        assertEquals(data.objects().graph().namespace(),restored.objects().graph().namespace());
        assertEquals(data.objects().epoch(),restored.objects().epoch());
        assertEquals(data.objects().work().destroyed(),restored.objects().work().destroyed());
    }

    @Test void failedCompressedWritePreservesOriginalFileAndAllowsExactRetry() throws Exception {
        var good=open(root);good.observe(new BlockPos(1,200,8),X);settle(good);good.save(file(root).toFile());
        byte[] before=Files.readAllBytes(file(root));
        var failing=new WorldIndexData(WorldCellIndex.decode(good.index().encode()),"") {
            boolean first=true;
            @Override void writeCompressed(CompoundTag tag,Path temporary) throws IOException {
                if (first) {
                    first=false;Files.write(temporary,new byte[]{0x1f,(byte)0x8b,8});
                    throw new IOException("SC injected partial gzip write failure");
                }
                super.writeCompressed(tag,temporary);
            }
        };
        failing.observe(new BlockPos(16,200,8),X);
        var expected=failing.save(new CompoundTag());
        failing.save(file(root).toFile());
        assertArrayEquals(before,Files.readAllBytes(file(root)));assertTrue(failing.isDirty());
        assertFalse(failing.failure().isEmpty());
        try(var files=Files.list(root)){assertEquals(1,files.count());}
        failing.save(file(root).toFile());assertFalse(failing.isDirty());assertTrue(failing.failure().isEmpty());
        assertEquals(expected,open(root).save(new CompoundTag()));
        try(var files=Files.list(root)){assertEquals(1,files.count());}
    }

    @Test void legacyCoverageMigratesWithoutForgettingUnloadedUnknownDeclarations() {
        var index = new WorldCellIndex(); index.add(new com.blockreality.api.geom.BlockKey(0,200,8));
        index.add(new com.blockreality.api.geom.BlockKey(16,200,8));
        CompoundTag old = new CompoundTag();old.putByteArray("coverage",index.encode());
        var data = WorldIndexData.load(old);assertEquals("PENDING_DECLARATIONS",data.objectStatus());
        data.observe(new BlockPos(0,200,8),X);assertEquals(2,data.size());assertFalse(data.objectsReady());
        var restored = WorldIndexData.load(data.save(new CompoundTag()));
        assertEquals(data.objects().graph().namespace(),restored.objects().graph().namespace());
        assertEquals("PENDING_DECLARATIONS",restored.objectStatus());
        restored.observe(new BlockPos(16,200,8),X);settle(restored);assertEquals(2,restored.objects().graph().activeCount());
    }
    @Test void savedPendingReplacementRetiresOriginalIdentityAfterReload() {
        var data=open(root);var pos=new BlockPos(1,200,8);data.observe(pos,X);settle(data);
        var id=data.objects().graph().owners().get(WorldIndexData.key(pos));
        data.remove(pos);data.observe(pos,X);data.save(file(root).toFile());
        var restored=open(root);assertEquals("PENDING",restored.objectStatus());settle(restored);
        assertNotEquals(id,restored.objects().graph().owners().get(WorldIndexData.key(pos)));
        restored.save(file(root).toFile());assertTrue(open(root).objectsReady());
    }
    @Test void unknownObjectSchemaCannotFallBackToCoverageOrOverwriteFile() throws Exception {
        var good=open(root);good.observe(new BlockPos(1,200,8),X);settle(good);
        CompoundTag data=good.save(new CompoundTag());data.putInt("objectsFormat",2);
        CompoundTag rootTag=new CompoundTag();rootTag.put("data",data);net.minecraft.nbt.NbtUtils.addCurrentDataVersion(rootTag);
        net.minecraft.nbt.NbtIo.writeCompressed(rootTag,file(root).toFile());
        byte[] original=Files.readAllBytes(file(root));var refused=open(root);
        assertFalse(refused.failure().isEmpty());refused.setDirty();refused.save(file(root).toFile());
        assertArrayEquals(original,Files.readAllBytes(file(root)));
        assertThrows(IllegalArgumentException.class,()->WorldIndexData.load(data));
    }

    @Test void actualSavedDataSurvivesReloadWithDimensionSeparation() throws Exception {
        Path other = root.resolve("DIM-1/data");
        WorldIndexData index = open(root); assertTrue(index.isDirty());
        index.add(new BlockPos(-1, 200, 0)); index.add(new BlockPos(16, 200, 0));
        index.save(file(root).toFile()); assertFalse(index.isDirty());
        WorldIndexData restored = open(root);
        assertEquals(2, restored.size()); assertTrue(restored.contains(new BlockPos(16, 200, 0)));
        assertTrue(open(other).isEmpty());
        var nether = open(other); nether.add(new BlockPos(100, 60, 100)); nether.save(file(other).toFile());
        assertEquals(2, open(root).size()); assertEquals(1, open(other).size());
        restored.remove(new BlockPos(-1, 200, 0)); restored.save(file(root).toFile());
        assertEquals(1, open(root).size());
    }

    @Test void corruptExistingFileIsNeverReplacedByEmptyData() throws Exception {
        byte[] original = {1, 2, 3, 4, 5}; Files.write(file(root), original);
        WorldIndexData refused = open(root); assertFalse(refused.failure().isEmpty());
        refused.add(new BlockPos(1, 2, 3)); refused.setDirty(); refused.save(file(root).toFile());
        assertArrayEquals(original, Files.readAllBytes(file(root))); assertTrue(refused.isEmpty());
        assertThrows(IllegalStateException.class, () -> refused.save(new CompoundTag()));
    }

    @Test void failedAtomicReplacementPreservesOldFileAndDirtyRetry() throws Exception {
        WorldIndexData good = open(root); good.add(new BlockPos(1, 2, 3)); good.save(file(root).toFile());
        byte[] before = Files.readAllBytes(file(root));
        WorldIndexData failing = new WorldIndexData(WorldCellIndex.decode(good.index().encode()), "") {
            boolean first = true;
            @Override void replace(Path tmp, Path target) throws IOException {
                assertTrue(Files.size(tmp) > 0);
                if (first) { first = false; throw new IOException("WR-5 injected replacement failure"); }
                super.replace(tmp, target);
            }
        };
        failing.add(new BlockPos(16, 2, 3)); failing.save(file(root).toFile());
        assertArrayEquals(before, Files.readAllBytes(file(root))); assertTrue(failing.isDirty());
        assertFalse(failing.failure().isEmpty());
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
        failing.save(file(root).toFile()); assertFalse(failing.isDirty()); assertTrue(failing.failure().isEmpty());
        assertEquals(2, open(root).size());
    }
}
