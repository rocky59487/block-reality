package com.blockreality.impl.server.construction;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static com.blockreality.impl.server.construction.PlayerInventoryImageTest.*;
import static com.blockreality.impl.server.construction.CanonicalNbt.MAX_DOCUMENT_BYTES;

class PlayerFileParticipantTest {
    @TempDir Path root;
    Path file() { return root.resolve(ACTOR + ".dat"); }
    static void equalData(CompoundTag expected, CompoundTag actual) throws IOException {
        assertArrayEquals(CanonicalNbt.encode(expected, MAX_DOCUMENT_BYTES), CanonicalNbt.encode(actual, MAX_DOCUMENT_BYTES));
    }

    @Test void exactFullPlayerSurvivesVanillaAndOfflineReloadWithInventoryOnlyChange() throws Exception {
        var store = new PlayerFileParticipant(root); var absent = store.capture(ACTOR);
        assertFalse(absent.exists()); assertThrows(IOException.class, absent::data);
        CompoundTag player = player(); var baseline = store.write(absent, player);
        equalData(player, NbtIo.readCompressed(file().toFile()));
        CompoundTag inventoryChange = PlayerInventoryImage.replace(player, Map.of(0, com.blockreality.core.transaction.ConstructionTransaction.Value.missing()));
        var committed = store.write(baseline, inventoryChange);
        equalData(inventoryChange, committed.data()); equalData(inventoryChange, new PlayerFileParticipant(root).verify(ACTOR).data());
        assertEquals(912, committed.data().getInt("XpTotal"));
        assertEquals(3, committed.data().getList("Inventory", 10).getCompound(0).getByte("Count"));
        CompoundTag returned = committed.data(); returned.putInt("XpTotal", 0);
        assertEquals(912, committed.data().getInt("XpTotal"));
        assertEquals(!System.getProperty("os.name").startsWith("Windows"), store.directorySyncAvailable());
    }

    @Test void staleForeignAndMissingIdentityCannotOverwriteExistingPlayer() throws Exception {
        var store = new PlayerFileParticipant(root); var absent = store.capture(ACTOR);
        var before = store.write(absent, player()); CompoundTag later = player(); later.putInt("XpTotal", 999);
        store.write(before, later); byte[] original = Files.readAllBytes(file());
        assertThrows(IOException.class, () -> store.write(before, player()));
        assertThrows(IOException.class, () -> store.write(absent, player()));
        CompoundTag foreign = player(); foreign.putUUID("UUID", UUID.randomUUID());
        assertThrows(IOException.class, () -> store.write(store.capture(ACTOR), foreign));
        CompoundTag missing = player(); missing.remove("UUID");
        assertThrows(IOException.class, () -> store.write(store.capture(ACTOR), missing));
        var other = new PlayerFileParticipant(root.resolve("other"));
        assertThrows(IOException.class, () -> other.write(before, player()));
        assertArrayEquals(original, Files.readAllBytes(file()));
        NbtIo.writeCompressed(foreign, file().toFile()); byte[] foreignBytes = Files.readAllBytes(file());
        assertThrows(IOException.class, () -> store.capture(ACTOR)); assertArrayEquals(foreignBytes, Files.readAllBytes(file()));
    }

    @Test void failuresAtEveryRealStorageBoundaryRemainResolvable() throws Exception {
        for (var stage : PlayerFileParticipant.Stage.values()) {
            Path directory = root.resolve(stage.name()); var good = new PlayerFileParticipant(directory);
            var baseline = good.write(good.capture(ACTOR), player()); CompoundTag after = player(); after.putInt("XpTotal", 1010);
            var failing = new PlayerFileParticipant(directory, reached -> { if (stage == reached) throw new IOException("Injected " + stage); });
            assertThrows(IOException.class, () -> failing.write(baseline, after));
            equalData(stage == PlayerFileParticipant.Stage.TEMP_FORCED ? player() : after, good.verify(ACTOR).data());
            try (var files = Files.list(directory)) {
                assertEquals(stage == PlayerFileParticipant.Stage.TEMP_FORCED ? 1 : 0,
                        files.filter(p -> p.toString().endsWith(".tmp")).count());
            }
            // A lost acknowledgment can be resolved by a fresh owner and then rolled back exactly.
            var reopened = new PlayerFileParticipant(directory); reopened.write(reopened.verify(ACTOR), player());
            equalData(player(), reopened.capture(ACTOR).data());
        }
    }

    @Test void changeDuringStagingIsDetectedBeforeAtomicReplacement() throws Exception {
        var store = new PlayerFileParticipant(root); var baseline = store.write(store.capture(ACTOR), player());
        CompoundTag unrelatedEdit = player(); unrelatedEdit.putInt("XpTotal", 321);
        AtomicBoolean changed = new AtomicBoolean();
        var racing = new PlayerFileParticipant(root, stage -> {
            if (stage == PlayerFileParticipant.Stage.TEMP_FORCED) {
                NbtIo.writeCompressed(unrelatedEdit, file().toFile()); changed.set(true);
            }
        });
        assertThrows(IOException.class, () -> racing.write(baseline, player())); assertTrue(changed.get());
        equalData(unrelatedEdit, store.capture(ACTOR).data());
        try (var files = Files.list(root)) { assertEquals(1, files.filter(p -> p.toString().endsWith(".tmp")).count()); }
    }

    @Test void corruptionTruncationTrailingGzipAndInflationCapacityPreserveOriginals() throws Exception {
        var store = new PlayerFileParticipant(root); store.write(store.capture(ACTOR), player());
        byte[] valid = Files.readAllBytes(file());
        var invalids = new ArrayList<byte[]>(); invalids.add(new byte[0]); invalids.add(new byte[]{1, 2, 3});
        invalids.add(Arrays.copyOf(valid, valid.length + 1));
        for (int length = 1; length < valid.length; length++) invalids.add(Arrays.copyOf(valid, length));
        byte[] checksum = valid.clone(); checksum[checksum.length - 8] ^= 1; invalids.add(checksum);
        byte[] combined = new byte[valid.length * 2]; System.arraycopy(valid, 0, combined, 0, valid.length);
        System.arraycopy(valid, 0, combined, valid.length, valid.length); invalids.add(combined);
        for (byte[] invalid : invalids) {
            Files.write(file(), invalid); assertThrows(IOException.class, () -> store.capture(ACTOR));
            assertArrayEquals(invalid, Files.readAllBytes(file()));
        }
        // Small gzip input with excessive decoded data must stop at the frozen decoded bound.
        try (var gzip = new java.util.zip.GZIPOutputStream(Files.newOutputStream(file()))) {
            byte[] chunk = new byte[32768]; for (int i = 0; i <= MAX_DOCUMENT_BYTES / chunk.length; i++) gzip.write(chunk);
        }
        byte[] bomb = Files.readAllBytes(file()); assertThrows(IOException.class, () -> store.capture(ACTOR));
        assertArrayEquals(bomb, Files.readAllBytes(file()));
        try (var channel = java.nio.channels.FileChannel.open(file(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(PlayerFileParticipant.MAX_COMPRESSED_BYTES); channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        assertThrows(IOException.class, () -> store.capture(ACTOR));
        assertEquals(PlayerFileParticipant.MAX_COMPRESSED_BYTES + 1L, Files.size(file()));
    }

    @Test void nonFileTargetAndNonFileDirectoryRefuseWithoutReplacement() throws Exception {
        Files.createDirectory(file()); var store = new PlayerFileParticipant(root);
        assertThrows(IOException.class, () -> store.capture(ACTOR)); assertTrue(Files.isDirectory(file()));
        Path notDirectory = root.resolve("plain"); Files.writeString(notDirectory, "keep");
        assertThrows(IOException.class, () -> new PlayerFileParticipant(notDirectory));
        assertEquals("keep", Files.readString(notDirectory));
    }

    @Test void retainedTemporaryCountAndByteCapacityRefuseWithoutDeletingEvidence() throws Exception {
        var good = new PlayerFileParticipant(root); good.write(good.capture(ACTOR), player());
        byte[] original = Files.readAllBytes(file());
        var failing = new PlayerFileParticipant(root, stage -> {
            if (stage == PlayerFileParticipant.Stage.TEMP_FORCED) throw new IOException("Injected retention");
        }, 1, 1024 * 1024);
        assertThrows(IOException.class, () -> failing.write(failing.capture(ACTOR), player()));
        assertEquals(1, failing.orphanFiles().size());
        Path retained = failing.orphanFiles().get(0); byte[] retainedBytes = Files.readAllBytes(retained);
        var reopened = new PlayerFileParticipant(root, stage -> { }, 1, 1024 * 1024);
        assertThrows(IOException.class, () -> reopened.write(reopened.capture(ACTOR), player()));
        assertArrayEquals(original, Files.readAllBytes(file())); assertArrayEquals(retainedBytes, Files.readAllBytes(retained));
        var byteBound = new PlayerFileParticipant(root, stage -> { }, 2, retainedBytes.length);
        assertThrows(IOException.class, () -> byteBound.write(byteBound.capture(ACTOR), player()));
        assertThrows(IOException.class, () -> new PlayerFileParticipant(root, stage -> { }, 2, retainedBytes.length - 1));
        assertThrows(IllegalArgumentException.class, () -> new PlayerFileParticipant(root, stage -> { }, 1025, 1024));
        assertArrayEquals(retainedBytes, Files.readAllBytes(retained));
    }
}
