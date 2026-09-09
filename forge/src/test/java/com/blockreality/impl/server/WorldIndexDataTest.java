package com.blockreality.impl.server;

import com.blockreality.core.world.WorldCellIndex;
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
