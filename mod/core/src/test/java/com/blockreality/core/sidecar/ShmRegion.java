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
 * delete it while either side still holds a view — which is why {@link #close} unmaps
 * <em>explicitly</em> before deleting. Waiting for the GC to collect the buffer
 * (the old behaviour) meant the delete always failed on Windows, and so did the
 * {@code deleteOnExit} fallback, because the mapping was usually still live at JVM
 * shutdown too: every session leaked its regions into the temp directory
 * permanently (CONC-7). After {@code close()} the buffers must not be touched —
 * an access to an unmapped buffer is a JVM crash, hence the closed flag.
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

    private boolean closed;

    Path path() { return path; }

    long size() { return map.capacity(); }

    /**
     * A fresh view of the region: independent position, little-endian, cleared. The
     * underlying pages are shared — this is a window, not a copy.
     */
    ByteBuffer buffer() {
        if (closed) throw new IllegalStateException("shm region is closed");
        ByteBuffer b = map.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        b.clear();
        return b;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            channel.close();
        } catch (IOException ignored) {
            // The unmap below is what actually releases the file on Windows.
        }
        unmap(map);
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The sidecar may still hold its own view for a moment; deleteOnExit
            // (registered in create) covers that narrowing race.
        }
    }

    /**
     * Releases the mapping now instead of at some future GC. {@code Unsafe.invokeCleaner}
     * is the supported way to do this on JDK 9+ (the java.nio buffer cleaner it calls is
     * exactly what the GC would eventually run). Duplicated views become invalid with
     * their parent, which is why {@link #buffer} refuses after close.
     */
    private static void unmap(MappedByteBuffer m) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            unsafeClass.getMethod("invokeCleaner", ByteBuffer.class).invoke(unsafe, m);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // No unmap available on this JVM: fall back to the old GC-dependent
            // behaviour. The delete below may then fail on Windows and the file
            // lingers until deleteOnExit — degraded, not broken.
        }
    }
}
