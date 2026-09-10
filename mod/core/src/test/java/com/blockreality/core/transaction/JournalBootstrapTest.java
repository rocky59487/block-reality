package com.blockreality.core.transaction;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.blockreality.core.transaction.ConstructionTransaction.*;
import static com.blockreality.core.transaction.TransactionFixtures.*;

class JournalBootstrapTest {
    @TempDir Path root;

    @Test void newDomainReopensWithoutASecondIdentityStoreAndPreservesPinnedCompatibility() throws Exception {
        UUID domain; byte[] marker;
        try (var journal = FileTransactionJournal.open(root)) {
            domain = journal.domain(); marker = Files.readAllBytes(root.resolve("domain.brtx"));
            assertEquals(56, marker.length); assertTrue(journal.entryIds().isEmpty());
            assertThrows(IOException.class, () -> { try (var other = FileTransactionJournal.open(root)) { fail(other.domain().toString()); } });
            var request = new Request(new UUID(10, 1), ACTOR, SESSION, domain, 0, "ab".repeat(32));
            journal.create(Entry.prepared(intent(request)));
            journal.decide(request.id(), Phase.COMMITTED, Reason.NONE);
        }
        for (boolean pinned : List.of(false, true)) {
            try (var journal = pinned ? new FileTransactionJournal(root, domain) : FileTransactionJournal.open(root)) {
                assertEquals(domain, journal.domain()); assertEquals(List.of(new UUID(10, 1)), journal.entryIds());
                assertEquals(Phase.COMMITTED, journal.read(new UUID(10, 1)).orElseThrow().phase());
                assertArrayEquals(marker, Files.readAllBytes(root.resolve("domain.brtx")));
            }
        }
        assertThrows(IOException.class, () -> { try (var other = new FileTransactionJournal(root, new UUID(0, 99))) { fail(other.domain().toString()); } });
    }

    @Test void malformedManifestIsNeverAcceptedOrReplaced() throws Exception {
        byte[] valid;
        try (var journal = new FileTransactionJournal(root.resolve("valid"), DOMAIN)) {
            valid = Files.readAllBytes(root.resolve("valid/domain.brtx"));
        }
        for (String mode : List.of("checksum", "schema", "magic", "domain", "short", "oversized", "directory")) {
            Path directory = Files.createDirectory(root.resolve(mode)), marker = directory.resolve("domain.brtx");
            byte[] bytes = valid.clone();
            switch (mode) {
                case "checksum" -> bytes[55] ^= 1;
                case "schema", "magic" -> {
                    ByteBuffer.wrap(bytes).putInt(mode.equals("schema") ? 4 : 0, 99);
                    System.arraycopy(MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, 24)), 0, bytes, 24, 32);
                }
                case "domain" -> bytes[15] ^= 1;
                case "short" -> bytes = Arrays.copyOf(bytes, 23);
                case "oversized" -> bytes = Arrays.copyOf(bytes, 57);
                case "directory" -> { }
                default -> throw new AssertionError(mode);
            }
            if (mode.equals("directory")) Files.createDirectory(marker); else Files.write(marker, bytes);
            byte[] expected = bytes;
            assertThrows(IOException.class, () -> { try (var journal = FileTransactionJournal.open(directory)) { fail(mode + journal.domain()); } }, mode);
            if (mode.equals("directory")) assertTrue(Files.isDirectory(marker));
            else assertArrayEquals(expected, Files.readAllBytes(marker), mode);
        }
    }

    @Test void missingManifestWithAnyEvidenceRefusesFreshIdentityAndLeavesFilesUntouched() throws Exception {
        for (String name : List.of("txn-" + request(1, 0).id() + ".brtx",
                "domain.brtx." + UUID.randomUUID() + ".tmp", "unknown.data")) {
            Path directory = Files.createTempDirectory(root, "missing");
            byte[] bytes = TransactionCodec.encode(Entry.rejected(request(1, 0), Reason.STALE_REVISION));
            Files.write(directory.resolve(name), bytes);
            assertThrows(IOException.class, () -> { try (var journal = FileTransactionJournal.open(directory)) { fail(journal.domain().toString()); } });
            assertArrayEquals(bytes, Files.readAllBytes(directory.resolve(name)));
            assertFalse(Files.exists(directory.resolve("domain.brtx")));
        }
        Path lockOnly = Files.createDirectory(root.resolve("lock-only")); Files.createFile(lockOnly.resolve(".owner.lock"));
        try (var journal = FileTransactionJournal.open(lockOnly)) { assertNotNull(journal.domain()); }
    }

    @Test void inventoryIsImmutableSortedAndIndependentOfDecisionOrderAndLaterWrites() throws Exception {
        List<UUID> captured;
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            var empty = journal.entryIds();
            journal.create(Entry.prepared(intent(request(9, 0))));
            journal.decide(request(9, 0).id(), Phase.ABORTED, Reason.APPLY_FAILED);
            journal.create(Entry.rejected(request(1, 0), Reason.STALE_REVISION));
            journal.create(Entry.prepared(intent(request(5, 0))));
            journal.decide(request(5, 0).id(), Phase.COMMITTED, Reason.NONE);
            captured = journal.entryIds();
            assertEquals(List.of(request(1, 0).id(), request(5, 0).id(), request(9, 0).id()), captured);
            assertTrue(empty.isEmpty()); assertThrows(UnsupportedOperationException.class, () -> captured.clear());
            journal.create(Entry.rejected(request(2, 0), Reason.STALE_REVISION));
            assertEquals(3, captured.size()); assertEquals(4, journal.entryIds().size());
        }
        try (var journal = FileTransactionJournal.open(root)) {
            assertEquals(List.of(request(1, 0).id(), request(2, 0).id(), request(5, 0).id(), request(9, 0).id()), journal.entryIds());
        }
    }

    @Test void inventoryResolvesAmbiguousReplacementAndRefusesClosedOrLatchedCorruption() throws Exception {
        var fired = new AtomicBoolean();
        var journal = new FileTransactionJournal(root, DOMAIN, (stage, entry) -> {
            if (stage == FileTransactionJournal.Stage.REPLACED && !fired.getAndSet(true)) throw new IOException("lost acknowledgment");
        });
        try (journal) {
            assertThrows(IOException.class, () -> journal.create(Entry.prepared(intent(request(1, 0)))));
            assertTrue(fired.get()); assertEquals(0, journal.keyCount());
            assertEquals(List.of(request(1, 0).id()), journal.entryIds());
            assertEquals(Optional.of(request(1, 0).id()), journal.pending());
            Path record = root.resolve("txn-" + request(1, 0).id() + ".brtx");
            byte[] bytes = Files.readAllBytes(record); bytes[bytes.length - 1] ^= 1; Files.write(record, bytes);
            assertThrows(IOException.class, () -> journal.read(request(1, 0).id()));
            assertThrows(IOException.class, journal::entryIds);
        }
        assertThrows(IOException.class, journal::entryIds);
    }
}
