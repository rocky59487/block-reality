package com.blockreality.core.transaction;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.blockreality.core.transaction.ConstructionTransaction.*;
import static com.blockreality.core.transaction.TransactionFixtures.*;

class FileTransactionJournalTest {
    @TempDir Path root;

    @Test void exclusiveOwnerDomainPinAndLazyRestartReplayRetainOriginalImages() throws Exception {
        var intent = intent(request(1, 0)); Entry committed;
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            assertThrows(IOException.class, () -> { try (var unexpected = new FileTransactionJournal(root, DOMAIN)) { fail(unexpected.domain().toString()); } });
            journal.create(Entry.prepared(intent)); assertEquals(Optional.of(intent.request().id()), journal.pending());
            committed = journal.decide(intent.request().id(), Phase.COMMITTED, Reason.NONE);
            assertTrue(journal.pending().isEmpty()); assertEquals(1, journal.keyCount());
            assertEquals(Files.size(record(root, intent.request().id())), journal.recordBytes());
            assertThrows(IOException.class, () -> journal.decide(intent.request().id(), Phase.ABORTED, Reason.RECOVERED));
            assertThrows(IOException.class, () -> journal.create(Entry.prepared(intent)));
        }
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            for (int i = 0; i < 100; i++) assertEquals(committed, journal.read(intent.request().id()).orElseThrow());
            assertEquals(committed, journal.verify(intent.request().id()).orElseThrow());
            assertTrue(journal.orphanFiles().isEmpty());
        }
        var foreign = new UUID(99, 99);
        assertThrows(IOException.class, () -> { try (var unexpected = new FileTransactionJournal(root, foreign)) { fail(unexpected.domain().toString()); } });
        try (var journal = new FileTransactionJournal(root, DOMAIN)) { assertEquals(1, journal.keyCount()); }
    }

    @Test void onlyOnePreparedIntentAndNoForeignDomainOrPrematureTerminalCreation() throws Exception {
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            var first = Entry.prepared(intent(request(1, 0)));
            var second = Entry.prepared(intent(request(2, 0)));
            assertThrows(IOException.class, () -> journal.create(first.finish(Phase.COMMITTED, Reason.NONE)));
            Request r = new Request(new UUID(1, 9), ACTOR, SESSION, new UUID(8, 8), 0, "ab".repeat(32));
            assertThrows(IOException.class, () -> journal.create(Entry.rejected(r, Reason.VALIDATION_REFUSED)));
            journal.create(first);
            assertThrows(IOException.class, () -> journal.create(second));
            assertThrows(IOException.class, () -> journal.create(Entry.rejected(request(3, 0), Reason.STALE_REVISION)));
            journal.decide(first.request().id(), Phase.ABORTED, Reason.APPLY_FAILED);
            journal.create(second); assertEquals(2, journal.keyCount());
        }
        try (var journal = new FileTransactionJournal(root, DOMAIN)) { assertEquals(Optional.of(request(2, 0).id()), journal.pending()); }
    }

    @Test void everyAtomicWriteFaultIsResolvedFromRealFilesAndTempsRemainEvidence() throws Exception {
        for (var stage : FileTransactionJournal.Stage.values()) for (Phase phase : List.of(Phase.PREPARED, Phase.COMMITTED, Phase.ABORTED)) {
            Path directory = root.resolve(stage + "-" + phase); var fired = new AtomicBoolean();
            var request = request(1, 0); var prepared = Entry.prepared(intent(request));
            try (var journal = new FileTransactionJournal(directory, DOMAIN, (at, entry) -> {
                if (at == stage && entry.phase() == phase && !fired.getAndSet(true)) throw new IOException("Injected " + at);
            })) {
                if (phase == Phase.PREPARED) assertThrows(IOException.class, () -> journal.create(prepared));
                else {
                    journal.create(prepared);
                    assertThrows(IOException.class, () -> journal.decide(request.id(), phase, phase == Phase.COMMITTED ? Reason.NONE : Reason.RECOVERED));
                }
                var actual = journal.verify(request.id()); assertTrue(fired.get());
                if (stage == FileTransactionJournal.Stage.TEMP_FORCED) {
                    assertEquals(1, journal.orphanFiles().size());
                    if (phase == Phase.PREPARED) assertTrue(actual.isEmpty());
                    else assertEquals(prepared, actual.orElseThrow());
                } else { assertEquals(phase, actual.orElseThrow().phase()); assertTrue(journal.orphanFiles().isEmpty()); }
            }
            try (var journal = new FileTransactionJournal(directory, DOMAIN)) {
                if (stage == FileTransactionJournal.Stage.TEMP_FORCED) assertEquals(1, journal.orphanFiles().size());
                else assertEquals(phase, journal.read(request.id()).orElseThrow().phase());
            }
        }
    }

    @Test void corruptRecordsUnknownSchemaTruncationAndDomainMarkerArePreservedAndRefused() throws Exception {
        byte[] valid = TransactionCodec.encode(Entry.prepared(intent(request(1, 0))));
        for (String mode : List.of("checksum", "schema", "truncated", "filename", "marker", "missing-marker", "multiple-prepared", "unknown-file")) {
            Path directory = root.resolve(mode);
            try (var journal = new FileTransactionJournal(directory, DOMAIN)) { journal.create(Entry.prepared(intent(request(1, 0)))); }
            Path target = record(directory, request(1, 0).id()); byte[] bytes = valid.clone();
            switch (mode) {
                case "checksum" -> bytes[160] ^= 1;
                case "schema" -> { ByteBuffer.wrap(bytes).putInt(4, 99); TransactionCodecTest.resign(bytes); }
                case "truncated" -> bytes = Arrays.copyOf(bytes, bytes.length - 1);
                case "filename" -> target = record(directory, request(2, 0).id());
                case "marker" -> { target = directory.resolve("domain.brtx"); bytes = Files.readAllBytes(target); bytes[10] ^= 1; }
                case "missing-marker" -> Files.delete(directory.resolve("domain.brtx"));
                case "multiple-prepared" -> { target = record(directory, request(2, 0).id()); bytes = TransactionCodec.encode(Entry.prepared(intent(request(2, 0)))); }
                case "unknown-file" -> target = directory.resolve("unrecognized.data");
                default -> throw new AssertionError(mode);
            }
            Files.write(target, bytes); byte[] retained = bytes;
            assertThrows(IOException.class, () -> { try (var unexpected = new FileTransactionJournal(directory, DOMAIN)) { fail(mode + unexpected.domain()); } }, mode);
            assertArrayEquals(retained, Files.readAllBytes(target), mode);
            if (mode.equals("missing-marker")) assertFalse(Files.exists(directory.resolve("domain.brtx")));
        }
    }

    @Test void readThroughDetectsDeletionAndCorruptionInsteadOfServingCachedSuccess() throws Exception {
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            var r = request(1, 0); journal.create(Entry.rejected(r, Reason.STALE_REVISION));
            Path file = record(root, r.id()); byte[] original = Files.readAllBytes(file);
            byte[] changed = original.clone(); changed[20] ^= 1; Files.write(file, changed);
            assertThrows(IOException.class, () -> journal.read(r.id()));
            Files.write(file, original); assertThrows(IOException.class, () -> journal.pending());
        }
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            var r = request(1, 0); assertEquals(Phase.REJECTED, journal.read(r.id()).orElseThrow().phase());
            Files.delete(record(root, r.id())); assertThrows(IOException.class, () -> journal.read(r.id()));
            assertThrows(IOException.class, () -> journal.pending());
        }
    }

    @Test void refusedAndAbortedKeysAreNeverEvictedAndClosedStoresCannotMutate() throws Exception {
        var journal = new FileTransactionJournal(root, DOMAIN);
        for (int i = 1; i <= 100; i++) journal.create(Entry.rejected(request(i, i), Reason.STALE_REVISION));
        journal.close(); journal.close();
        assertThrows(IOException.class, () -> journal.read(request(1, 1).id()));
        try (var reopened = new FileTransactionJournal(root, DOMAIN)) {
            assertEquals(100, reopened.keyCount());
            for (int i = 1; i <= 100; i++) assertEquals(request(i, i), reopened.read(request(i, i).id()).orElseThrow().request());
        }
    }

    @Test void fullKeyAndByteQuotasRefuseNewKeysButStillAllowTheReservedDecisionAndReplay() throws Exception {
        var first = Entry.prepared(intent(request(1, 0))); int size = TransactionCodec.encode(first).length;
        for (boolean bytes : List.of(false, true)) {
            Path directory = root.resolve("quota-" + bytes);
            try (var journal = new FileTransactionJournal(directory, DOMAIN, (s, e) -> { }, bytes ? MAX_KEYS : 1, bytes ? size : MAX_JOURNAL_BYTES)) {
                journal.create(first); assertEquals(size, journal.recordBytes());
                var committed = journal.decide(first.request().id(), Phase.COMMITTED, Reason.NONE);
                assertThrows(IOException.class, () -> journal.create(Entry.rejected(request(2, 0), Reason.STALE_REVISION)));
                assertEquals(1, journal.keyCount()); assertEquals(committed, journal.read(first.request().id()).orElseThrow());
            }
            try (var journal = new FileTransactionJournal(directory, DOMAIN)) {
                assertEquals(Phase.COMMITTED, journal.read(first.request().id()).orElseThrow().phase());
                assertTrue(journal.read(request(2, 0).id()).isEmpty());
            }
        }
    }

    private static Path record(Path directory, UUID id) { return directory.resolve("txn-" + id + ".brtx"); }
}
