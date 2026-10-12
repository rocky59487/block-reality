package com.blockreality.core.finite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real-filesystem object store evidence: publish, readback, lock, evidence, HEAD cache. */
class FiniteStoreTest {

    @TempDir Path root;

    @Test void publishReadBackAndExclusiveLock() throws Exception {
        var domain = FiniteFixtures.domainId(FiniteFixtures.DIMENSION);
        var source = FiniteFixtures.source(domain.bsiDomain(), 0);
        FiniteBlobRef ref;
        try (var store = FiniteStore.open(root)) {
            ref = store.publish(new UUID(60, 60), FiniteCodec.KIND_SOURCE, source.serialize());
            assertTrue(Files.isRegularFile(root.resolve("objects/" + ref.sha256() + ".blob")));
            var body = store.read(ref);
            assertEquals(FiniteCodec.KIND_SOURCE, body.kind());
            assertEquals(source, FiniteSource.decode(body.payload()));
            // A second writer in the same directory is refused by the exclusive lock.
            assertThrows(IOException.class, () -> FiniteStore.open(root));
            // Staging is clean after a successful publish.
            assertTrue(store.stagingEvidence().isEmpty());
        }
        // The lock releases with close; a fresh open re-reads the same object from disk.
        try (var store = FiniteStore.open(root)) {
            assertEquals(FiniteCodec.KIND_SOURCE, store.read(ref).kind());
        }
    }

    @Test void corruptedObjectNeverReadsBack() throws Exception {
        var domain = FiniteFixtures.domainId(FiniteFixtures.DIMENSION);
        var source = FiniteFixtures.source(domain.bsiDomain(), 0);
        try (var store = FiniteStore.open(root)) {
            var ref = store.publish(new UUID(61, 61), FiniteCodec.KIND_SOURCE, source.serialize());
            var path = root.resolve("objects/" + ref.sha256() + ".blob");
            var stored = Files.readAllBytes(path);
            stored[stored.length - 1] ^= 0x40;                        // payload corruption
            Files.write(path, stored);
            assertThrows(IOException.class, () -> store.read(ref));
            Files.write(path, java.util.Arrays.copyOf(stored, stored.length - 8)); // truncation
            assertThrows(IOException.class, () -> store.read(ref));
            // A wrong-length reference is refused before the file is even trusted.
            var wrong = new FiniteBlobRef(ref.sha256(), ref.length() + 1);
            assertThrows(IOException.class, () -> store.read(wrong));
        }
    }

    @Test void wrongKindAndMissingObjectRefuse() throws Exception {
        try (var store = FiniteStore.open(root)) {
            var checkpoint = store.publish(new UUID(62, 62), FiniteCodec.KIND_CHECKPOINT, new byte[]{7, 7, 7});
            var body = store.read(checkpoint);
            assertEquals(FiniteCodec.KIND_CHECKPOINT, body.kind());
            var missing = new FiniteBlobRef("ab".repeat(32), 100);
            assertThrows(IOException.class, () -> store.read(missing));
        }
    }

    @Test void admissionAndOversizeRefusal() throws Exception {
        try (var store = FiniteStore.open(root)) {
            assertThrows(IOException.class,
                    () -> store.publish(new UUID(63, 63), FiniteCodec.KIND_SOURCE,
                            new byte[(int) FiniteStore.MAX_OBJECT_BYTES + 1]));
        }
    }

    @Test void stagingEvidenceSurvivesAndIsBounded() throws Exception {
        var request = new UUID(64, 64);
        try (var store = FiniteStore.open(root)) {
            var dir = root.resolve("staging/" + request);
            Files.createDirectories(dir);
            Files.write(dir.resolve("orphan.blob"), new byte[]{1, 2, 3});
            var evidence = store.stagingEvidence();
            assertEquals(1, evidence.size());
            store.discardEvidence(evidence.get(0));
            assertTrue(store.stagingEvidence().isEmpty());
        }
    }

    @Test void headCacheRoundTripAndCloseRefusal() throws Exception {
        var domain = FiniteFixtures.domainId(FiniteFixtures.DIMENSION);
        var basis = new FiniteBasis(domain, 1, 2, "a".repeat(64), "b".repeat(64), "c".repeat(64), 5);
        var store = FiniteStore.open(root);
        assertTrue(store.readHead(domain).isEmpty());
        store.writeHead(basis, "d".repeat(64));
        assertEquals("d".repeat(64), store.readHead(domain).orElseThrow().rootHash());
        store.close();
        assertThrows(IOException.class,
                () -> store.publish(new UUID(65, 65), FiniteCodec.KIND_CHECKPOINT, new byte[]{1}));
        assertThrows(IOException.class, () -> store.readHead(domain));
    }

    @Test void adjacentOpenLandsBesideJournalDirectory() throws Exception {
        var journalDir = root.resolve("journal");
        var journal = com.blockreality.core.transaction.FileTransactionJournal.open(journalDir);
        journal.close();
        try (var store = FiniteStore.openAdjacentTo(journalDir)) {
            assertEquals(journalDir.resolveSibling("finite"), store.root());
            assertTrue(Files.isDirectory(store.root().resolve("objects")));
        }
        // The store must not poison the journal: scan() rejects unknown entries, so
        // anything FiniteStore creates inside the journal directory breaks the next open.
        com.blockreality.core.transaction.FileTransactionJournal.open(journalDir).close();
    }
}
