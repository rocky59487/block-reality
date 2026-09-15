package com.blockreality.impl.server.construction;

import net.minecraft.nbt.CompoundTag;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static java.nio.file.StandardOpenOption.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/**
 * Acknowledged, exact player-file writes for construction checkpoints and offline recovery.
 * The owner must hold the server world lock and exclude player saves for the entire operation.
 * No Forge save callback is dispatched while transaction state is private.
 */
final class PlayerFileParticipant {
    static final int MAX_COMPRESSED_BYTES = 17 * 1024 * 1024;
    private static final int MAX_ORPHANS = 1024;
    private static final long MAX_ORPHAN_BYTES = 1024L * 1024 * 1024;
    private final Path directory;
    private final Faults faults;
    private final int orphanLimit;
    private final long orphanByteLimit;
    private final Map<Path, Long> orphans = new HashMap<>();
    private long orphanBytes;
    private IOException retentionFailure;
    enum Stage { TEMP_FORCED, REPLACED, TARGET_FORCED, DIRECTORY_FORCED, VERIFIED }
    @FunctionalInterface interface Faults { void at(Stage stage) throws IOException; }

    PlayerFileParticipant(Path directory) throws IOException { this(directory, stage -> { }); }
    PlayerFileParticipant(Path directory, Faults faults) throws IOException {
        this(directory, faults, MAX_ORPHANS, MAX_ORPHAN_BYTES);
    }
    PlayerFileParticipant(Path directory, Faults faults, int orphanLimit, long orphanByteLimit) throws IOException {
        if (orphanLimit < 1 || orphanLimit > MAX_ORPHANS || orphanByteLimit < 1 || orphanByteLimit > MAX_ORPHAN_BYTES)
            throw new IllegalArgumentException("Invalid player temporary-file quota");
        this.directory = directory.toAbsolutePath().normalize(); this.faults = Objects.requireNonNull(faults);
        this.orphanLimit = orphanLimit; this.orphanByteLimit = orphanByteLimit;
        Files.createDirectories(this.directory);
        if (!Files.isDirectory(this.directory, NOFOLLOW_LINKS)) throw new IOException("Invalid player directory");
        try (var files = Files.newDirectoryStream(this.directory, "*.brtx-*.tmp")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!name.matches("[0-9a-f-]{36}\\.brtx-[0-9a-f-]{36}\\.tmp"))
                    throw new IOException("Unknown player transaction temporary file");
                remember(file);
            }
        }
    }
    boolean directorySyncAvailable() { return !System.getProperty("os.name", "").startsWith("Windows"); }
    List<Path> orphanFiles() { return List.copyOf(orphans.keySet()); }

    static final class Snapshot {
        private final UUID actor;
        private final Path directory;
        private final byte[] compressed;
        private final CompoundTag data;
        private Snapshot(UUID actor, Path directory, byte[] compressed, CompoundTag data) {
            this.actor = actor; this.directory = directory; this.compressed = compressed; this.data = data;
        }
        boolean exists() { return compressed != null; }
        CompoundTag data() throws IOException {
            if (data == null) throw new IOException("Player baseline file missing");
            return data.copy();
        }
    }

    Snapshot capture(UUID actor) throws IOException {
        Objects.requireNonNull(actor); byte[] bytes = readFile(path(actor));
        if (bytes == null) return new Snapshot(actor, directory, null, null);
        CompoundTag data = CanonicalNbt.read(inflate(bytes), CanonicalNbt.MAX_DOCUMENT_BYTES);
        validateActor(data, actor);
        return new Snapshot(actor, directory, bytes, data);
    }

    /** Existing unreadable files cannot yield a Snapshot, so this never initializes over corruption. */
    Snapshot write(Snapshot before, CompoundTag desired) throws IOException {
        Objects.requireNonNull(before);
        if (retentionFailure != null) throw new IOException("Player temporary-file scan must be retried by a new owner", retentionFailure);
        if (!directory.equals(before.directory)) throw new IOException("Foreign player storage snapshot");
        validateActor(desired, before.actor);
        byte[] canonical = CanonicalNbt.encode(desired, CanonicalNbt.MAX_DOCUMENT_BYTES);
        Path target = path(before.actor);
        requireUnchanged(before, target);
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new FastGzip(bytes)) { gzip.write(canonical); }
        if (bytes.size() > MAX_COMPRESSED_BYTES || orphans.size() >= orphanLimit
                || bytes.size() > orphanByteLimit - orphanBytes) throw new IOException("Player temporary-file capacity exhausted");
        Path temporary = directory.resolve(before.actor + ".brtx-" + UUID.randomUUID() + ".tmp");
        // Failure leaves the temporary file as evidence. Atomic move consumes it only on success.
        Throwable failure = null;
        try {
            try (var file = FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes.toByteArray()); while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            faults.at(Stage.TEMP_FORCED);
            requireUnchanged(before, target);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            faults.at(Stage.REPLACED);
            force(target); faults.at(Stage.TARGET_FORCED);
            forceDirectory(); faults.at(Stage.DIRECTORY_FORCED);
            Snapshot actual = capture(before.actor);
            if (!Arrays.equals(canonical, CanonicalNbt.encode(actual.data(), CanonicalNbt.MAX_DOCUMENT_BYTES)))
                throw new IOException("Player write verification mismatch");
            faults.at(Stage.VERIFIED); return actual;
        } catch (IOException | RuntimeException | Error caught) { failure = caught; throw caught; }
        finally {
            try { if (Files.exists(temporary, NOFOLLOW_LINKS)) remember(temporary); }
            catch (IOException retained) {
                retentionFailure = retained;
                if (failure != null) failure.addSuppressed(retained); else throw retained;
            }
        }
    }

    /** Repeats barriers after an ambiguous post-replacement error before resolving actual state. */
    Snapshot verify(UUID actor) throws IOException {
        Path target = path(actor);
        if (Files.exists(target, NOFOLLOW_LINKS)) force(target);
        forceDirectory(); return capture(actor);
    }
    private void requireUnchanged(Snapshot before, Path target) throws IOException {
        if (!Arrays.equals(before.compressed, readFile(target))) throw new IOException("Player file changed since capture");
    }
    private void remember(Path file) throws IOException {
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) throw new IOException("Invalid player temporary file");
        long size = Files.size(file); Long old = orphans.put(file, size); orphanBytes += size - (old == null ? 0 : old);
        if (size > MAX_COMPRESSED_BYTES || orphans.size() > orphanLimit || orphanBytes > orphanByteLimit)
            throw new IOException("Retained player temporary files exceed capacity");
    }
    private static void validateActor(CompoundTag data, UUID actor) throws IOException {
        if (!data.hasUUID("UUID") || !actor.equals(data.getUUID("UUID"))) throw new IOException("Foreign or missing player UUID");
    }
    private Path path(UUID actor) { return directory.resolve(Objects.requireNonNull(actor) + ".dat"); }
    private static byte[] readFile(Path file) throws IOException {
        if (!Files.exists(file, NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) throw new IOException("Invalid player file");
        try (var channel = FileChannel.open(file, READ, NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size <= 0 || size > MAX_COMPRESSED_BYTES) throw new IOException("Invalid compressed player size");
            byte[] bytes = new byte[(int) size]; var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new IOException("Player file truncated during read");
            if (channel.read(ByteBuffer.allocate(1)) != -1) throw new IOException("Player file grew during read");
            return bytes;
        }
    }
    private static void force(Path file) throws IOException {
        try (var channel = FileChannel.open(file, WRITE, NOFOLLOW_LINKS)) { channel.force(true); }
    }
    private void forceDirectory() throws IOException {
        if (directorySyncAvailable()) try (var channel = FileChannel.open(directory, READ)) { channel.force(true); }
    }
    // Vanilla writes a single member without optional headers. GZIPInputStream tolerates trailing
    // garbage, so verify exact framing, checksum and length here instead of accepting a damaged file.
    private static byte[] inflate(byte[] bytes) throws IOException {
        if (bytes.length < 18 || bytes[0] != (byte) 0x1f || bytes[1] != (byte) 0x8b
                || bytes[2] != 8 || bytes[3] != 0) throw new IOException("Unsupported player gzip header");
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(bytes, 10, bytes.length - 10);
            var output = new ByteArrayOutputStream(); byte[] buffer = new byte[32768]; var crc = new CRC32();
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count > CanonicalNbt.MAX_DOCUMENT_BYTES - output.size()) throw new IOException("Player NBT capacity exceeded");
                output.write(buffer, 0, count); crc.update(buffer, 0, count);
                if (count == 0 && !inflater.finished()) throw new IOException("Truncated or invalid player gzip data");
            }
            int trailer = bytes.length - inflater.getRemaining();
            if (bytes.length - trailer != 8) throw new IOException("Trailing or truncated player gzip trailer");
            var footer = ByteBuffer.wrap(bytes, trailer, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (Integer.toUnsignedLong(footer.getInt()) != crc.getValue() || footer.getInt() != output.size())
                throw new IOException("Player gzip checksum/length mismatch");
            return output.toByteArray();
        } catch (DataFormatException corrupt) { throw new IOException("Corrupt player gzip data", corrupt); }
        finally { inflater.end(); }
    }
    private static final class FastGzip extends GZIPOutputStream {
        FastGzip(OutputStream output) throws IOException { super(output, 32768); def.setLevel(Deflater.BEST_SPEED); }
    }
}
