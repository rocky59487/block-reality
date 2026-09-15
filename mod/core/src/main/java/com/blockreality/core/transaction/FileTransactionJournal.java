package com.blockreality.core.transaction;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static com.blockreality.core.transaction.ConstructionTransaction.*;

/**
 * One checksummed, atomically replaced decision per transaction; bounded index, lazy image reads.
 * Never evicts keys or repairs a corrupt record. The caller owns the directory for this domain.
 *
 * <p>Images and replacement files are forced to storage. POSIX directory entries are forced too.
 * Windows JDK 17 cannot open directories as FileChannels: the destination is forced after rename,
 * and {@link #directorySyncAvailable()} reports the missing directory barrier explicitly. Process
 * crash tests do not qualify power-loss behavior of the OS, storage device or Minecraft participants.
 */
public final class FileTransactionJournal implements TransactionJournal {
    private static final String MANIFEST = "domain.brtx", OWNER = ".owner.lock";
    private static final int MAX_ORPHANS = 1024;
    private final Path directory;
    private final UUID domain;
    private final FileChannel ownerChannel;
    private final FileLock ownerLock;
    private final FileChannel directoryChannel;
    private final Map<UUID, Integer> sizes = new HashMap<>();
    private final Map<Path, Long> orphans = new HashMap<>();
    private final Faults faults;
    private final int keyLimit;
    private final long byteLimit;
    private UUID pending;
    private UUID unverified;
    private IOException readFailure;
    private long recordBytes, orphanBytes;
    private boolean closed;

    // Package-local injection exercises actual file operations. No property or packet enables faults.
    enum Stage { TEMP_FORCED, REPLACED, TARGET_FORCED, DIRECTORY_FORCED }
    @FunctionalInterface interface Faults { void at(Stage stage, Entry entry) throws IOException; }

    /** Reopen the manifest's domain, or create one only in an otherwise empty owned directory. */
    public static FileTransactionJournal open(Path directory) throws IOException {
        return new FileTransactionJournal(directory, null, (stage, entry) -> { }, MAX_KEYS, MAX_JOURNAL_BYTES, true);
    }

    public FileTransactionJournal(Path directory, UUID domain) throws IOException {
        this(directory, domain, (stage, entry) -> { });
    }

    FileTransactionJournal(Path directory, UUID domain, Faults faults) throws IOException {
        this(directory, domain, faults, MAX_KEYS, MAX_JOURNAL_BYTES);
    }

    // Reduced quotas exercise admission with real files; production callers cannot raise the frozen caps.
    FileTransactionJournal(Path directory, UUID domain, Faults faults, int keyLimit, long byteLimit) throws IOException {
        this(directory, Objects.requireNonNull(domain), faults, keyLimit, byteLimit, false);
    }

    private FileTransactionJournal(Path directory, UUID requestedDomain, Faults faults,
                                   int keyLimit, long byteLimit, boolean discoverDomain) throws IOException {
        if (keyLimit < 1 || keyLimit > MAX_KEYS || byteLimit < 1 || byteLimit > MAX_JOURNAL_BYTES)
            throw new IllegalArgumentException("Invalid journal quota");
        this.keyLimit = keyLimit; this.byteLimit = byteLimit;
        this.directory = directory.toAbsolutePath().normalize();
        this.faults = Objects.requireNonNull(faults);
        Files.createDirectories(this.directory);
        if (!Files.isDirectory(this.directory, NOFOLLOW_LINKS)) throw new IOException("Invalid journal directory");
        ownerChannel = FileChannel.open(this.directory.resolve(OWNER), CREATE, WRITE, NOFOLLOW_LINKS);
        FileLock lock = null; FileChannel dir = null;
        try {
            try { lock = ownerChannel.tryLock(); }
            catch (OverlappingFileLockException held) { throw new IOException("Journal already owned", held); }
            if (lock == null) throw new IOException("Journal already owned");
            if (!System.getProperty("os.name", "").startsWith("Windows"))
                dir = FileChannel.open(this.directory, READ);
            directoryChannel = dir; ownerLock = lock;
            Path manifest = this.directory.resolve(MANIFEST);
            boolean exists = Files.exists(manifest, NOFOLLOW_LINKS);
            byte[] existingManifest = exists ? readBounded(manifest, 56) : null;
            if (discoverDomain && existingManifest == null) {
                // Even a failed initial manifest write is evidence; never mint a replacement domain over it.
                try (var files = Files.newDirectoryStream(this.directory)) {
                    for (Path file : files)
                        if (!file.getFileName().toString().equals(OWNER))
                            throw new IOException("Cannot discover construction domain: nonempty journal has no marker");
                }
                this.domain = UUID.randomUUID();
            } else if (discoverDomain) {
                if (existingManifest.length != 56) throw new IOException("Journal domain marker size invalid");
                var buffer = ByteBuffer.wrap(existingManifest);
                this.domain = new UUID(buffer.getLong(8), buffer.getLong(16));
            } else this.domain = requestedDomain;
            if (exists && !Arrays.equals(manifestBytes(), existingManifest))
                throw new IOException("Journal domain/schema/checksum mismatch");
            // Do not create a fresh domain marker over records whose original marker was lost.
            scan();
            if (!exists) {
                if (!sizes.isEmpty()) throw new IOException("Journal domain marker missing");
                writeAtomic(manifest, manifestBytes(), null);
            }
        } catch (IOException | RuntimeException | Error failure) {
            if (dir != null) try { dir.close(); } catch (IOException close) { failure.addSuppressed(close); }
            if (lock != null) try { lock.release(); } catch (IOException close) { failure.addSuppressed(close); }
            try { ownerChannel.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    public boolean directorySyncAvailable() { return directoryChannel != null; }
    public synchronized int keyCount() { return sizes.size(); }
    public synchronized long recordBytes() { return recordBytes; }
    /** Retained incomplete temporary files are diagnostics, never accepted decisions. */
    public synchronized List<Path> orphanFiles() { return List.copyOf(orphans.keySet()); }
    @Override public UUID domain() { return domain; }

    /**
     * Immutable sorted key inventory; not commit order. Records remain lazy and must be read/validated.
     * The caller owns external access to this directory; this does not rescan externally added files.
     */
    public synchronized List<UUID> entryIds() throws IOException {
        checkOpen();
        if (unverified != null) verify(unverified);
        return List.copyOf(new TreeSet<>(sizes.keySet()));
    }

    @Override public synchronized Optional<Entry> read(UUID id) throws IOException {
        checkOpen(); Objects.requireNonNull(id); Path file = path(id);
        try {
            if (!Files.exists(file, NOFOLLOW_LINKS)) {
                if (sizes.containsKey(id)) throw new IOException("Journal record disappeared: " + id);
                return Optional.empty();
            }
            byte[] bytes = readBounded(file, MAX_RECORD_BYTES); Entry entry = decode(bytes);
            if (!entry.request().id().equals(id)) throw new IOException("Journal filename/key mismatch");
            index(id, entry, bytes.length); return Optional.of(entry);
        } catch (IOException failure) {
            // Reopening performs a complete scan. A later baseline publication must not clear corruption.
            readFailure = failure; throw failure;
        }
    }

    @Override public synchronized Optional<UUID> pending() throws IOException {
        checkOpen();
        if (unverified != null) verify(unverified);
        if (pending != null) read(pending); // Read-through detects corruption/deletion, not a stale cache.
        return Optional.ofNullable(pending);
    }

    @Override public synchronized Optional<Entry> verify(UUID id) throws IOException {
        Optional<Entry> actual = read(id);
        if (actual.isPresent()) {
            try (FileChannel out = FileChannel.open(path(id), WRITE, NOFOLLOW_LINKS)) { out.force(true); }
        }
        if (directoryChannel != null) directoryChannel.force(true);
        if (id.equals(unverified)) unverified = null;
        return actual;
    }

    @Override public synchronized void create(Entry entry) throws IOException {
        checkOpen(); checkDomain(entry);
        if (entry.phase() != Phase.PREPARED && entry.phase() != Phase.REJECTED)
            throw new IOException("Cannot create a terminal decision without intent");
        if (read(entry.request().id()).isPresent()) throw new IOException("Transaction key already exists");
        if (pending().isPresent()) throw new IOException("Recovery required before another transaction");
        byte[] bytes = TransactionCodec.encode(entry);
        if (sizes.size() >= keyLimit || recordBytes + orphanBytes + bytes.length > byteLimit
                || orphans.size() >= MAX_ORPHANS) throw new IOException("Construction journal capacity exhausted");
        writeAtomic(path(entry.request().id()), bytes, entry);
        index(entry.request().id(), entry, bytes.length);
    }

    @Override public synchronized Entry decide(UUID id, Phase phase, Reason reason) throws IOException {
        Entry prior = read(id).orElseThrow(() -> new IOException("Prepared intent missing"));
        if (prior.phase() == phase && prior.reason() == reason && phase != Phase.PREPARED) return prior;
        if (prior.phase() != Phase.PREPARED) throw new IOException("Terminal decision is immutable");
        final Entry next;
        try { next = prior.finish(phase, reason); }
        catch (IllegalArgumentException | IllegalStateException invalid) { throw new IOException("Invalid journal transition", invalid); }
        byte[] bytes = TransactionCodec.encode(next);
        // Same images, same-sized phase/reason: capacity admission already reserved the terminal record.
        if (bytes.length != sizes.get(id)) throw new IOException("Decision changed intent size");
        writeAtomic(path(id), bytes, next); index(id, next, bytes.length); return next;
    }

    private void scan() throws IOException {
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.equals(OWNER) || name.equals(MANIFEST)) continue;
                if (name.matches("(domain|txn-[0-9a-f-]{36})\\.brtx\\.[0-9a-f-]{36}\\.tmp")) {
                    rememberOrphan(file); continue;
                }
                if (!name.matches("txn-[0-9a-f-]{36}\\.brtx")) throw new IOException("Unknown journal entry: " + name);
                final UUID id;
                try { id = UUID.fromString(name.substring(4, 40)); }
                catch (IllegalArgumentException bad) { throw new IOException("Invalid journal filename", bad); }
                if (!path(id).equals(file)) throw new IOException("Noncanonical journal filename");
                read(id);
            }
        }
    }

    private Entry decode(byte[] bytes) throws IOException {
        try { Entry entry = TransactionCodec.decode(bytes); checkDomain(entry); return entry; }
        catch (IllegalArgumentException bad) { throw new IOException("Construction journal corrupt", bad); }
    }
    private void checkDomain(Entry entry) throws IOException {
        if (!domain.equals(entry.request().domain())) throw new IOException("Foreign construction domain");
    }
    private void index(UUID id, Entry entry, int size) throws IOException {
        Integer old = sizes.get(id); long nextBytes = recordBytes - (old == null ? 0 : old) + size;
        if ((old == null && sizes.size() >= keyLimit) || nextBytes + orphanBytes > byteLimit)
            throw new IOException("Construction journal capacity exhausted");
        if (entry.phase() == Phase.PREPARED && pending != null && !pending.equals(id))
            throw new IOException("Multiple prepared transactions require offline investigation");
        sizes.put(id, size); recordBytes = nextBytes;
        if (entry.phase() == Phase.PREPARED) pending = id;
        else if (id.equals(pending)) pending = null;
    }

    private void writeAtomic(Path target, byte[] bytes, Entry entry) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        if (entry != null) unverified = entry.request().id();
        try {
            try (FileChannel out = FileChannel.open(temp, CREATE_NEW, WRITE, NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) out.write(buffer);
                out.force(true);
            }
            fault(Stage.TEMP_FORCED, entry);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            fault(Stage.REPLACED, entry);
            try (FileChannel out = FileChannel.open(target, WRITE, NOFOLLOW_LINKS)) { out.force(true); }
            fault(Stage.TARGET_FORCED, entry);
            if (directoryChannel != null) directoryChannel.force(true);
            fault(Stage.DIRECTORY_FORCED, entry);
            if (entry != null) unverified = null;
        } finally {
            // A crash temp is retained byte-for-byte. Only a completed atomic move consumes it.
            if (Files.exists(temp, NOFOLLOW_LINKS)) rememberOrphan(temp);
        }
    }
    private void fault(Stage stage, Entry entry) throws IOException { if (entry != null) faults.at(stage, entry); }
    private void rememberOrphan(Path file) throws IOException {
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) throw new IOException("Invalid journal temporary file");
        long size = Files.size(file);
        Long old = orphans.put(file, size); orphanBytes += size - (old == null ? 0 : old);
        if (size > MAX_RECORD_BYTES || orphans.size() > MAX_ORPHANS || orphanBytes + recordBytes > byteLimit)
            throw new IOException("Retained journal temporary files exceed capacity");
    }
    private static byte[] readBounded(Path path, int maximum) throws IOException {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw new IOException("Invalid journal file");
        try (FileChannel in = FileChannel.open(path, READ, NOFOLLOW_LINKS)) {
            long size = in.size(); if (size <= 0 || size > maximum) throw new IOException("Journal record size invalid");
            byte[] bytes = new byte[(int) size]; var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) if (in.read(buffer) < 0) throw new IOException("Journal record truncated during read");
            if (in.read(ByteBuffer.allocate(1)) != -1) throw new IOException("Journal record grew during read");
            return bytes;
        }
    }
    private byte[] manifestBytes() {
        var out = ByteBuffer.allocate(56); out.putInt(0x42525444).putInt(1);
        out.putLong(domain.getMostSignificantBits()).putLong(domain.getLeastSignificantBits());
        try { var hash = MessageDigest.getInstance("SHA-256"); hash.update(out.array(), 0, 24); out.put(hash.digest()); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        return out.array();
    }
    private Path path(UUID id) { return directory.resolve("txn-" + id + ".brtx"); }
    private void checkOpen() throws IOException {
        if (closed || !ownerLock.isValid()) throw new IOException("Journal is closed");
        if (readFailure != null) throw new IOException("Journal read failed; reopen and verify all records before recovery", readFailure);
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return; closed = true;
        try { if (directoryChannel != null) directoryChannel.close(); }
        finally { try { ownerLock.release(); } finally { ownerChannel.close(); } }
    }
}
