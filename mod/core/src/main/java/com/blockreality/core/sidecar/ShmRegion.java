package com.blockreality.core.sidecar;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * One file-backed shared-memory region: the JVM half of the zero-copy transport.
 *
 * <p>The JVM creates a scratch file, maps it, and hands the path to the sidecar over
 * the handshake; both processes then read and write the SAME pages. Numbers cross as
 * raw little-endian doubles — written once, read once, never textualised — and the
 * stdio doorbell provides the ordering, so the half-duplex request/reply can reuse the
 * region front to back.
 *
 * <p>Growing is replacement: map a bigger new file, tell the sidecar to open it, let
 * this one go. A mapped file cannot be truncated portably, and Windows will refuse to
 * delete it while either side still holds a view — hence best-effort delete now,
 * {@code deleteOnExit} as the fallback, and a name that says what the file is.
 */
final class ShmRegion implements AutoCloseable {

    private final Path path;
    private final FileChannel channel;
    private final MappedByteBuffer map;

    private ShmRegion(Path path, FileChannel channel, MappedByteBuffer map) {
        this.path = path;
        this.channel = channel;
        this.map = map;
    }

    static ShmRegion create(long bytes) throws IOException {
        Path p = Files.createTempFile("br-shm-", ".bin");
        p.toFile().deleteOnExit();
        FileChannel ch = FileChannel.open(p,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            // Size the file before mapping; map() of a hole is fine on both platforms,
            // but an explicit length keeps the sidecar's "file is empty" guard honest.
            ch.truncate(0);
            ch.position(bytes - 1);
            ch.write(ByteBuffer.wrap(new byte[] { 0 }));
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_WRITE, 0, bytes);
            m.order(ByteOrder.LITTLE_ENDIAN);
            return new ShmRegion(p, ch, m);
        } catch (IOException | RuntimeException e) {
            try {
                ch.close();
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // The temp file is already registered for delete-on-exit.
            }
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }

    Path path() { return path; }

    long size() { return map.capacity(); }

    /**
     * A fresh view of the region: independent position, little-endian, cleared. The
     * underlying pages are shared — this is a window, not a copy.
     */
    ByteBuffer buffer() {
        ByteBuffer b = map.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        b.clear();
        return b;
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Nothing to do differently; the mapping dies with the buffer.
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Windows keeps the file while a view exists; deleteOnExit covers it.
        }
    }
}
