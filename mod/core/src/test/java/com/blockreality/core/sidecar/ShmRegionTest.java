package com.blockreality.core.sidecar;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The region's file must die with the region (CONC-7).
 *
 * <p>The leak this locks out was Windows-specific and permanent: close() deleted
 * nothing because the mapping was still live, and deleteOnExit failed at JVM shutdown
 * for the same reason, so every session parked another few MB in %TEMP% forever. The
 * fix is an explicit unmap before the delete; this test fails on any platform if the
 * delete stops working, and on Windows it fails precisely when the unmap is removed.
 */
class ShmRegionTest {

    @Test
    void closeDeletesTheBackingFile() throws IOException {
        ShmRegion region = ShmRegion.create(64 * 1024);
        Path path = region.path();
        assertTrue(Files.exists(path), "the region is file-backed");
        assertEquals(64 * 1024, region.size());

        // Touch the mapping the way a solve would, so the mapped view is provably
        // live right before close — the exact state in which the old code leaked.
        ByteBuffer b = region.buffer();
        b.putLong(0, 0x4252513100000001L);

        region.close();
        assertTrue(Files.notExists(path),
                "close() must unmap and delete the backing file (CONC-7)");
    }

    @Test
    void closeIsIdempotent() throws IOException {
        ShmRegion region = ShmRegion.create(4096);
        region.close();
        region.close();   // second close: nothing to do, must not throw
    }

    @Test
    void aClosedRegionRefusesToHandOutBuffers() throws IOException {
        // After the unmap the pages are gone; an access through a stale view is a JVM
        // crash, not an exception. The guard has to sit at the only place views are
        // created.
        ShmRegion region = ShmRegion.create(4096);
        region.close();
        assertThrows(IllegalStateException.class, region::buffer);
    }

    @Test
    void independentViewsShareThePages() throws IOException {
        ShmRegion region = ShmRegion.create(4096);
        try {
            region.buffer().putInt(0, 0xCAFE);
            assertEquals(0xCAFE, region.buffer().getInt(0),
                    "buffer() is a window onto shared pages, not a copy");
        } finally {
            region.close();
        }
    }
}
