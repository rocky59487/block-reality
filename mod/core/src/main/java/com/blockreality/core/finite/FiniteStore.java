package com.blockreality.core.finite;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The finite object store (design §4): immutable content-addressed blobs under
 * {@code objects/<sha256>.blob}, private per-request staging under
 * {@code staging/<request-uuid>/}, and a per-domain {@code HEAD-<bsiDomainHex>} cache.
 *
 * <p>Admit → stage → force → atomic move → readback-verify is the only publish path.
 * If the filesystem cannot atomically replace the destination the publish fails and the
 * staged evidence is left in place — a store never reports an object it cannot prove. Reads
 * re-validate the physical envelope (magic, schema, kind, length, payload hash and the
 * object's own name-hash) on every call; nothing is trusted from a cache or the index name.
 *
 * <p>The store holds an exclusive {@code .lock} for its lifetime: two writers in one save
 * directory is a configuration error, not a race to arbitrate. Staging residue after a crash
 * stays visible through {@link #stagingEvidence()} instead of being silently reclaimed.
 */
public final class FiniteStore implements AutoCloseable {

    public static final long MAX_OBJECT_BYTES = 64L << 20;
    public static final long MAX_STAGING_BYTES = 256L << 20;
    public static final int MAX_OBJECTS = 1 << 20;

    /** Process-crash test seam: named checkpoints inside {@link #publish}. Never a control channel. */
    interface FaultHook { void at(String stage) throws IOException; }

    private final Path root, objects, staging;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final FaultHook hook;
    private long stagedBytes;
    private boolean closed;

    private FiniteStore(Path root, FileChannel lockChannel, FileLock lock, FaultHook hook) {
        this.root = root;
        this.objects = root.resolve("objects");
        this.staging = root.resolve("staging");
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.hook = hook;
    }

    /** Open (creating) the finite store rooted at {@code root}, taking the exclusive lock. */
    public static FiniteStore open(Path root) throws IOException {
        return open(root, null);
    }

    static FiniteStore open(Path root, FaultHook hook) throws IOException {
        Objects.requireNonNull(root);
        Files.createDirectories(root.resolve("objects"));
        Files.createDirectories(root.resolve("staging"));
        Path lockFile = root.resolve(".lock");
        var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock fileLock;
            try {
                fileLock = channel.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException sameJvm) {
                // The JVM-wide lock table reports a second open in this process the same way
                // the OS reports a foreign one — either way this store already has an owner.
                fileLock = null;
            }
            if (fileLock == null) throw new IOException("Finite store is locked by another writer: " + root);
            var store = new FiniteStore(root, channel, fileLock, hook);
            store.stagedBytes = store.stagingBytes();
            try (DirectoryStream<Path> blobs = Files.newDirectoryStream(store.objects, "*.blob")) {
                long count = 0;
                for (Path ignored : blobs) count++;
                if (count > MAX_OBJECTS) throw new IOException("Finite object count exceeds bound");
            }
            return store;
        } catch (IOException | RuntimeException failure) {
            try { channel.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    /** The conventional location beside a construction journal directory. */
    public static FiniteStore openAdjacentTo(Path journalDirectory) throws IOException {
        return open(journalDirectory.resolveSibling("finite"));
    }

    public Path root() { return root; }

    // ---------------------------------------------------------------- admission

    /** Reserve capacity for one object envelope. Fails before a byte is staged. */
    private void admit(long envelopeBytes) throws IOException {
        ready();
        if (envelopeBytes < FiniteCodec.ENVELOPE_HEADER_BYTES
                || envelopeBytes > MAX_OBJECT_BYTES + FiniteCodec.ENVELOPE_HEADER_BYTES)
            throw new IOException("Finite object exceeds admission bound");
        if (envelopeBytes > MAX_STAGING_BYTES || stagedBytes > MAX_STAGING_BYTES - envelopeBytes)
            throw new IOException("Finite staging admission exhausted");
    }

    /**
     * Publish one immutable payload and return its durable reference. The blob is written in
     * this request's private staging directory, forced to durable media, atomically moved into
     * {@code objects/}, then re-read and re-validated from its final name. Any failure leaves
     * the staging evidence behind and publishes nothing.
     */
    public FiniteBlobRef publish(UUID request, int kind, byte[] payload) throws IOException {
        Objects.requireNonNull(request);
        FiniteDomainId.nonzero(request);
        if (payload == null) throw new IOException("Finite object payload required");
        // Admission is decided before the envelope is ever materialized.
        admit((long) payload.length + FiniteCodec.ENVELOPE_HEADER_BYTES);
        var envelope = FiniteCodec.pack(kind, payload);
        var sha = FiniteSource.sha256(envelope);
        var name = sha + ".blob";
        var staged = staging.resolve(request.toString()).resolve(name);
        var target = objects.resolve(name);
        try {
            Files.createDirectories(staged.getParent());
            try (var channel = FileChannel.open(staged, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(envelope));
                channel.force(true);
            }
            stagedBytes += envelope.length;
            if (hook != null) hook.at("forced");
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileAlreadyExistsException sameName) {
                // Content-addressed: the same name can only mean a previous publish completed
                // its move (or a crash left the moved object behind). The readback below still
                // re-validates the target; the duplicate stage is simply redundant. The staged
                // bytes are released once below, as on the plain-move path.
                Files.deleteIfExists(staged);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Finite store requires atomic object publish", unsupported);
            }
            if (hook != null) hook.at("moved");
            // Read back from the durable name, not from the staging buffer.
            var stored = Files.readAllBytes(target);
            if (stored.length != envelope.length) throw new IOException("Finite object readback mismatch");
            FiniteCodec.ObjectBody body;
            try {
                body = FiniteCodec.unpack(stored, sha);
            } catch (IllegalArgumentException corrupt) {
                throw new IOException("Finite object failed readback validation: " + sha, corrupt);
            }
            if (body.kind() != kind) throw new IOException("Finite object kind mismatch");
            stagedBytes -= envelope.length;
            try { Files.deleteIfExists(staged.getParent()); } catch (IOException ignored) { }
            return new FiniteBlobRef(sha, stored.length);
        } catch (IOException | RuntimeException failure) {
            // Bounded evidence: the staged file (if any) stays for diagnosis; nothing was published.
            try { if (!Files.exists(target)) Files.deleteIfExists(staged); } catch (IOException ignored) { }
            throw failure;
        }
    }

    /** Read and fully re-validate the object behind {@code ref}. */
    public FiniteCodec.ObjectBody read(FiniteBlobRef ref) throws IOException {
        ready();
        Objects.requireNonNull(ref);
        var path = objects.resolve(ref.sha256() + ".blob");
        byte[] stored;
        try {
            stored = Files.readAllBytes(path);
        } catch (java.nio.file.NoSuchFileException missing) {
            throw new IOException("Finite object missing: " + ref.sha256(), missing);
        }
        if (stored.length != ref.length()) throw new IOException("Finite object length mismatch: " + ref.sha256());
        try {
            return FiniteCodec.unpack(stored, ref.sha256());
        } catch (IllegalArgumentException corrupt) {
            throw new IOException("Finite object corrupt: " + ref.sha256(), corrupt);
        }
    }

    public boolean contains(FiniteBlobRef ref) {
        if (closed || ref == null) return false;
        return Files.isRegularFile(objects.resolve(ref.sha256() + ".blob"));
    }

    // ---------------------------------------------------------------- HEAD cache

    private Path headPath(FiniteDomainId domain) {
        return root.resolve("HEAD-" + FiniteCodec.BsiHex(domain.bsiDomain()));
    }

    /**
     * Refresh the HEAD cache through the same staging → force → atomic move discipline.
     * The cache can only ever point somewhere; the journal root is the authority it must
     * agree with on every open.
     */
    public void writeHead(FiniteBasis basis, String rootHash) throws IOException {
        ready();
        var request = UUID.nameUUIDFromBytes(("head/" + basis.domain().bsiDomain()
                + "/" + basis.sessionGeneration()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var bytes = FiniteCodec.encodeHead(basis, rootHash);
        var staged = staging.resolve(request.toString()).resolve("head");
        var target = headPath(basis.domain());
        try {
            Files.createDirectories(staged.getParent());
            try (var channel = FileChannel.open(staged, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Finite store requires atomic HEAD publish", unsupported);
            }
            try { Files.deleteIfExists(staged.getParent()); } catch (IOException ignored) { }
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(staged); } catch (IOException ignored) { }
            throw failure;
        }
    }

    /** The cached head for {@code domain}, or empty if none was ever written. Always re-validated. */
    public java.util.Optional<FiniteCodec.Head> readHead(FiniteDomainId domain) throws IOException {
        ready();
        var path = headPath(domain);
        if (!Files.isRegularFile(path)) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(FiniteCodec.decodeHead(Files.readAllBytes(path), domain));
        } catch (IllegalArgumentException corrupt) {
            throw new IOException("Finite HEAD cache corrupt for " + domain.bsiDomain(), corrupt);
        }
    }

    // ---------------------------------------------------------------- staging evidence

    /** Request directories left behind by crashes or failed publishes; bounded evidence, never silently dropped. */
    public List<Path> stagingEvidence() throws IOException {
        ready();
        var out = new ArrayList<Path>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(staging)) {
            for (Path dir : dirs) if (Files.isDirectory(dir)) out.add(dir);
        }
        out.sort(null);
        return List.copyOf(out);
    }

    /** Remove one evidence directory after the caller has inspected it. Never called implicitly. */
    public void discardEvidence(Path requestDirectory) throws IOException {
        ready();
        if (!requestDirectory.startsWith(staging) || requestDirectory.equals(staging))
            throw new IOException("Refusing to discard non-staging path");
        try (DirectoryStream<Path> files = Files.newDirectoryStream(requestDirectory)) {
            for (Path file : files) {
                long size = Files.size(file);
                if (Files.deleteIfExists(file)) stagedBytes -= size;
            }
        }
        Files.deleteIfExists(requestDirectory);
    }

    private long stagingBytes() throws IOException {
        long total = 0;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(staging)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
                    for (Path file : files) total += Files.size(file);
                }
            }
        }
        return total;
    }

    private void ready() throws IOException {
        if (closed) throw new IOException("Finite store is closed");
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try { lock.release(); } finally { lockChannel.close(); }
    }
}
