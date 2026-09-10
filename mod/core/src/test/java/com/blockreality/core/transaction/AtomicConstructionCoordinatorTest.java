package com.blockreality.core.transaction;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.blockreality.core.transaction.ConstructionTransaction.*;
import static com.blockreality.core.transaction.TransactionFixtures.*;
import static com.blockreality.core.transaction.AtomicConstructionCoordinator.*;

class AtomicConstructionCoordinatorTest {
    @TempDir Path root;

    @Test void checkpointMakesUnsavedBaselineDurableBeforePreparedAndIsNeverRepeatedForReplay() throws Exception {
        var request = request(1, 0); var intent = intent(request);
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            var host = new MemoryHost(intent) {
                @Override public void checkpoint(List<String> resources) throws IOException {
                    assertTrue(journal.read(request.id()).isEmpty()); assertEquals(0, writes);
                    assertEquals(before(intent), live); assertEquals(before(intent), visible);
                    super.checkpoint(resources);
                }
            };
            host.durable.put("inventory/0", value("older-unsaved-baseline"));
            host.onWrite = () -> assertEquals(before(intent), host.durable);
            var c = new AtomicConstructionCoordinator(journal); c.recover(host);
            var receipt = c.execute(request, r -> intent, host);
            assertEquals(Phase.COMMITTED, receipt.phase());
            assertEquals(1, Collections.frequency(host.calls, "checkpoint"));
            assertEquals(receipt, c.execute(request, r -> { fail(); return null; }, host));
            assertEquals(1, Collections.frequency(host.calls, "checkpoint"));
        }
    }

    @Test void failedCheckpointCreatesNoIntentAndDoesNotChangeVisibleParticipants() throws Exception {
        var request = request(1, 0); var intent = intent(request);
        var host = new MemoryHost(intent) {
            @Override public void checkpoint(List<String> resources) throws IOException { throw new IOException("Injected checkpoint"); }
        };
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            var c = new AtomicConstructionCoordinator(journal); c.recover(host);
            assertThrows(IOException.class, () -> c.execute(request, r -> intent, host));
            assertTrue(journal.read(request.id()).isEmpty()); assertTrue(journal.pending().isEmpty()); assertTrue(c.ready());
            assertEquals(before(intent), host.live); assertEquals(before(intent), host.visible); assertEquals(0, host.writes);
        }
    }

    @Test void checkpointCannotHideChangedImagesOrRevision() throws Exception {
        for (String resource : List.of("inventory/0", "revision")) {
            var request = request(1, 0); var intent = intent(request);
            var host = new MemoryHost(intent) {
                @Override public void checkpoint(List<String> resources) throws IOException {
                    super.checkpoint(resources); live.put(resource, value("99"));
                }
            };
            try (var journal = new FileTransactionJournal(root.resolve(resource.replace('/', '-')), DOMAIN)) {
                var c = new AtomicConstructionCoordinator(journal); c.recover(host);
                var receipt = c.execute(request, r -> intent, host);
                assertEquals(Reason.PARTICIPANT_CONFLICT, receipt.reason()); assertEquals(Phase.REJECTED, receipt.phase());
                assertEquals(0, host.writes); assertEquals(0, host.publications);
            }
        }
    }

    @Test void commitConsumesAllImagesOnceAndReplaySurvivesRestartAndLaterEdits() throws Exception {
        var request = request(1, 0); var intent = intent(request); var host = new MemoryHost(intent);
        var prepared = new AtomicInteger(); Receipt original;
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            var coordinator = new AtomicConstructionCoordinator(journal);
            assertThrows(RecoveryRequired.class, () -> coordinator.execute(request, r -> intent, host));
            coordinator.recover(host);
            host.onWrite = () -> { assertEquals(before(intent), host.visible); assertEquals(0, host.publications); };
            original = coordinator.execute(request, r -> { prepared.incrementAndGet(); return intent; }, host);
            assertEquals(Phase.COMMITTED, original.phase()); assertEquals(1, host.revision());
            assertEquals(after(intent), host.live); assertEquals(after(intent), host.durable); assertEquals(after(intent), host.visible);
            for (int i = 0; i < 100; i++) assertEquals(original, coordinator.execute(request, r -> { fail("Replay prepared again"); return null; }, host));
            assertEquals(1, prepared.get()); assertEquals(intent.changes().size(), host.writes);
            assertEquals(1, host.flushes); assertEquals(1, host.publications);
        }
        // A terminal record must not overwrite a newer world/inventory image during startup.
        host.live.put("inventory/0", value("steel:3")); host.live.put("revision", value("4"));
        var later = Map.copyOf(host.live); int writes = host.writes;
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            var coordinator = new AtomicConstructionCoordinator(journal); coordinator.recover(host);
            for (int i = 0; i < 100; i++) assertEquals(original, coordinator.execute(request, r -> { fail(); return null; }, host));
            assertEquals(later, host.live); assertEquals(writes, host.writes); assertEquals(later, host.visible);
        }
    }

    @Test void collisionsBindActorSessionDomainPayloadAndBaseWithoutChangingOriginalDecision() throws Exception {
        var request = request(1, 0); var intent = intent(request); var host = new MemoryHost(intent);
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            var c = new AtomicConstructionCoordinator(journal); c.recover(host);
            var original = c.execute(request, r -> intent, host); UUID foreign = new UUID(99, 99);
            for (Request collision : List.of(
                    new Request(request.id(), foreign, SESSION, DOMAIN, 0, request.planHash()),
                    new Request(request.id(), ACTOR, foreign, DOMAIN, 0, request.planHash()),
                    new Request(request.id(), ACTOR, SESSION, foreign, 0, request.planHash()),
                    new Request(request.id(), ACTOR, SESSION, DOMAIN, 0, "cd".repeat(32)),
                    new Request(request.id(), ACTOR, SESSION, DOMAIN, 1, request.planHash()))) {
                assertThrows(ReplayConflict.class, () -> c.execute(collision, r -> { fail(); return null; }, host));
                assertEquals(original, journal.read(request.id()).orElseThrow().receipt());
            }
            assertEquals(1, host.revision()); assertEquals(after(intent), host.live);
        }
    }

    @Test void validationStaleBaseAndConflictingImagesRefuseBeforeWritesAndRetainReplay() throws Exception {
        var original = intent(request(1, 0)); var host = new MemoryHost(original);
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            var c = new AtomicConstructionCoordinator(journal); c.recover(host);
            var refused = c.execute(request(1, 0), r -> { throw new ValidationRefused(); }, host);
            var stale = c.execute(request(2, 1), r -> { fail("Stale request prepared"); return null; }, host);
            var conflicting = new Intent(request(3, 0), List.of(new Change("inventory/0", value("wrong"), value("after"))), List.of(PIECE_A), List.of());
            var conflict = c.execute(request(3, 0), r -> conflicting, host);
            assertEquals(Reason.VALIDATION_REFUSED, refused.reason()); assertEquals(Reason.STALE_REVISION, stale.reason());
            assertEquals(Reason.PARTICIPANT_CONFLICT, conflict.reason());
            for (var receipt : List.of(refused, stale, conflict)) {
                assertEquals(Phase.REJECTED, receipt.phase()); assertTrue(receipt.created().isEmpty());
                assertEquals(receipt, c.execute(receipt.request(), r -> { fail(); return null; }, host));
            }
            assertEquals(before(original), host.live); assertEquals(0, host.writes); assertEquals(0, host.flushes);
        }
    }

    @Test void everyParticipantWriteAndFlushFailureRestoresAllBeforeImagesWithoutPublication() throws Exception {
        var request = request(1, 0); var intent = intent(request);
        for (int failure = 1; failure <= intent.changes().size() + 1; failure++) {
            var host = new MemoryHost(intent); if (failure <= intent.changes().size()) host.failWrite = failure; else host.failFlush = 1;
            try (var journal = new FileTransactionJournal(root.resolve("failure-" + failure), DOMAIN)) {
                var c = new AtomicConstructionCoordinator(journal); c.recover(host);
                var outcome = c.execute(request, r -> intent, host);
                assertEquals(Phase.ABORTED, outcome.phase()); assertEquals(Reason.APPLY_FAILED, outcome.reason());
                assertTrue(outcome.created().isEmpty()); assertEquals(before(intent), host.live);
                assertEquals(before(intent), host.durable); assertEquals(before(intent), host.visible);
                assertEquals(0, host.publications); assertTrue(c.ready()); assertTrue(journal.pending().isEmpty());
                assertEquals(outcome, c.execute(request, r -> { fail(); return null; }, host));
            }
        }
    }

    @Test void everyPrepareAndCommitStorageStageFailureEitherAbortsOrRetainsDurableCommit() throws Exception {
        for (Phase target : List.of(Phase.PREPARED, Phase.COMMITTED)) for (var stage : FileTransactionJournal.Stage.values()) {
            var fired = new AtomicBoolean(); var request = request(1, 0); var intent = intent(request); var host = new MemoryHost(intent);
            try (var journal = new FileTransactionJournal(root.resolve(target + "-" + stage), DOMAIN, (at, entry) -> {
                if (entry.phase() == target && at == stage && !fired.getAndSet(true)) throw new IOException("Injected decision I/O");
            })) {
                var c = new AtomicConstructionCoordinator(journal); c.recover(host);
                if (target == Phase.PREPARED && stage == FileTransactionJournal.Stage.TEMP_FORCED) {
                    assertThrows(IOException.class, () -> c.execute(request, r -> intent, host));
                    assertEquals(0, host.writes); assertEquals(before(intent), host.live); assertTrue(c.ready());
                } else {
                    var outcome = c.execute(request, r -> intent, host);
                    boolean committed = target == Phase.COMMITTED && stage != FileTransactionJournal.Stage.TEMP_FORCED;
                    assertEquals(committed ? Phase.COMMITTED : Phase.ABORTED, outcome.phase());
                    assertEquals(committed ? after(intent) : before(intent), host.live);
                    assertEquals(host.live, host.durable); assertEquals(host.live, host.visible);
                    assertEquals(committed ? 1 : 0, host.publications); assertTrue(c.ready());
                }
                assertTrue(fired.get());
            }
        }
    }

    @Test void failedRollbackRetainsPreparedAndBlocksUntilExplicitSuccessfulRecovery() throws Exception {
        var request = request(1, 0); var intent = intent(request); var host = new MemoryHost(intent);
        host.failFlush = 1; host.failWrite = intent.changes().size() + 2;
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            var c = new AtomicConstructionCoordinator(journal); c.recover(host);
            assertThrows(RecoveryRequired.class, () -> c.execute(request, r -> intent, host));
            assertFalse(c.ready()); assertEquals(Optional.of(request.id()), journal.pending()); assertEquals(0, host.publications);
            assertThrows(RecoveryRequired.class, () -> c.execute(request(2, 0), r -> { fail(); return null; }, host));
            c.recover(host); assertTrue(c.ready()); assertEquals(before(intent), host.live); assertEquals(before(intent), host.durable);
            assertEquals(Phase.ABORTED, c.execute(request, r -> { fail(); return null; }, host).phase());
        }
    }

    @Test void abortDecisionFailuresKeepRestoredImagesAndRequireRecoveryUntilTheMarkerIsDurable() throws Exception {
        for (var stage : FileTransactionJournal.Stage.values()) {
            var intent = intent(request(1, 0)); var host = new MemoryHost(intent); host.failFlush = 1;
            var fired = new AtomicBoolean();
            try (var journal = new FileTransactionJournal(root.resolve(stage.toString()), DOMAIN, (at, entry) -> {
                if (entry.phase() == Phase.ABORTED && at == stage && !fired.getAndSet(true)) throw new IOException("Injected abort marker");
            })) {
                var c = new AtomicConstructionCoordinator(journal); c.recover(host);
                if (stage == FileTransactionJournal.Stage.TEMP_FORCED) {
                    assertThrows(RecoveryRequired.class, () -> c.execute(intent.request(), r -> intent, host));
                    assertFalse(c.ready()); c.recover(host);
                } else assertEquals(Phase.ABORTED, c.execute(intent.request(), r -> intent, host).phase());
                assertTrue(fired.get()); assertTrue(c.ready()); assertEquals(before(intent), host.durable);
                assertEquals(Phase.ABORTED, c.execute(intent.request(), r -> { fail(); return null; }, host).phase());
            }
        }
    }

    @Test void corruptionCannotBeClearedByPublishingABaselineWithoutReopeningTheJournal() throws Exception {
        var intent = intent(request(1, 0)); var host = new MemoryHost(intent);
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            var c = new AtomicConstructionCoordinator(journal); c.recover(host); c.execute(intent.request(), r -> intent, host);
            Path file = root.resolve("txn-" + intent.request().id() + ".brtx"); byte[] bytes = Files.readAllBytes(file); bytes[12] ^= 1; Files.write(file, bytes);
            int baselines = host.baselines;
            assertThrows(RecoveryRequired.class, () -> c.execute(intent.request(), r -> { fail(); return null; }, host));
            assertThrows(RecoveryRequired.class, () -> c.recover(host)); assertFalse(c.ready()); assertEquals(baselines, host.baselines);
            assertArrayEquals(bytes, Files.readAllBytes(file));
        }
    }

    @Test void unknownExternalValueOrRevisionPreventsAnyRecoveryWrite() throws Exception {
        for (String foreign : List.of("world/2", "revision")) {
            var request = request(1, 0); var intent = intent(request); var host = new MemoryHost(intent);
            host.live.putAll(after(intent)); host.live.put(foreign, value(foreign.equals("revision") ? "99" : "foreign edit"));
            var untouched = Map.copyOf(host.live);
            try (var journal = new FileTransactionJournal(root.resolve(foreign.replace('/', '-')), DOMAIN)) {
                journal.create(Entry.prepared(intent)); var c = new AtomicConstructionCoordinator(journal);
                assertThrows(RecoveryRequired.class, () -> c.recover(host));
                assertEquals(untouched, host.live); assertEquals(0, host.writes); assertFalse(c.ready());
                assertEquals(Phase.PREPARED, journal.read(request.id()).orElseThrow().phase());
            }
        }
    }

    @Test void lostPublicationKeepsCommittedDecisionAndRecoveryPublishesBaselineWithoutApplyingAgain() throws Exception {
        var request = request(1, 0); var intent = intent(request); var host = new MemoryHost(intent);
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            var c = new AtomicConstructionCoordinator(journal); c.recover(host); host.failPublish = true;
            assertThrows(RecoveryRequired.class, () -> c.execute(request, r -> intent, host));
            assertFalse(c.ready()); assertEquals(after(intent), host.live); assertEquals(after(intent), host.durable);
            assertEquals(before(intent), host.visible); assertEquals(Phase.COMMITTED, journal.read(request.id()).orElseThrow().phase());
            int writes = host.writes; c.recover(host);
            assertEquals(Phase.COMMITTED, c.execute(request, r -> { fail(); return null; }, host).phase());
            assertEquals(writes, host.writes); assertEquals(after(intent), host.visible); assertTrue(c.ready());
        }
    }

    @Test void missingRevisionParticipantCannotCommitEvenWhenEverySuppliedImageMatches() throws Exception {
        var request = request(1, 0); var original = intent(request); var host = new MemoryHost(original);
        var incomplete = new Intent(request, original.changes().stream().filter(c -> !c.resource().equals("revision")).toList(), original.created(), List.of());
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            var c = new AtomicConstructionCoordinator(journal); c.recover(host);
            assertEquals(Phase.ABORTED, c.execute(request, r -> incomplete, host).phase());
            assertEquals(before(original), host.live); assertEquals(0, host.publications);
        }
    }

    @Test void secondPlayerOnSameBaseIsRejectedAndOrderedNonconflictingCommitAdvancesOnlyOnce() throws Exception {
        var first = intent(request(1, 0)); var host = new MemoryHost(first);
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            var c = new AtomicConstructionCoordinator(journal); c.recover(host);
            c.execute(first.request(), r -> first, host);
            var rival = new Request(request(2, 0).id(), new UUID(9, 9), SESSION, DOMAIN, 0, "ab".repeat(32));
            assertEquals(Reason.STALE_REVISION, c.execute(rival, r -> { fail(); return null; }, host).reason());
            var next = new Intent(request(3, 1), List.of(new Change("world/3", Value.missing(), value("timber/z")),
                    new Change("revision", value("1"), value("2"))), List.of(new UUID(6, 6)), List.of());
            assertEquals(Phase.COMMITTED, c.execute(next.request(), r -> next, host).phase());
            assertEquals(2, host.revision()); assertEquals(value("steel:8"), host.live.get("inventory/0"));
            assertEquals(value("timber/z"), host.visible.get("world/3")); assertEquals(2, host.publications);
        }
    }

    @Test void callbacksCannotReenterCoordinatorAndBaselineFailureLeavesItClosed() throws Exception {
        var request = request(1, 0); var intent = intent(request); var host = new MemoryHost(intent);
        try (var journal = new FileTransactionJournal(root, DOMAIN)) {
            var c = new AtomicConstructionCoordinator(journal); host.failBaseline = true;
            assertThrows(RecoveryRequired.class, () -> c.recover(host)); assertFalse(c.ready());
            host.failBaseline = false; c.recover(host);
            host.onWrite = () -> assertThrows(IOException.class, () -> c.execute(request(2, 0), r -> { fail(); return null; }, host));
            assertEquals(Phase.COMMITTED, c.execute(request, r -> intent, host).phase());
        }
    }

    @Test void decisionVerificationFailureDoesNotGuessRollbackAndMissingPreparedFileDoesNotReopenWrites() throws Exception {
        var request = request(1, 0); var intent = intent(request);
        for (boolean remove : List.of(false, true)) {
            Path directory = root.resolve("uncertain-" + remove); var host = new MemoryHost(intent);
            try (var actual = new FileTransactionJournal(directory, DOMAIN)) {
                TransactionJournal broken = new DelegatingJournal(actual) {
                    @Override public Entry decide(UUID id, Phase phase, Reason reason) throws IOException {
                        if (remove) Files.delete(directory.resolve("txn-" + id + ".brtx"));
                        throw new IOException("Injected decision failure");
                    }
                    @Override public Optional<Entry> verify(UUID id) throws IOException {
                        if (!remove) throw new IOException("Injected decision verification failure");
                        return Optional.empty();
                    }
                };
                var c = new AtomicConstructionCoordinator(broken); c.recover(host);
                assertThrows(RecoveryRequired.class, () -> c.execute(request, r -> intent, host));
                assertFalse(c.ready()); assertEquals(after(intent), host.live); assertEquals(intent.changes().size(), host.writes);
                assertEquals(0, host.publications);
            }
        }
    }

    static class DelegatingJournal implements TransactionJournal {
        final TransactionJournal journal;
        DelegatingJournal(TransactionJournal journal) { this.journal = journal; }
        @Override public UUID domain() { return journal.domain(); }
        @Override public Optional<Entry> read(UUID id) throws IOException { return journal.read(id); }
        @Override public Optional<Entry> verify(UUID id) throws IOException { return journal.verify(id); }
        @Override public Optional<UUID> pending() throws IOException { return journal.pending(); }
        @Override public void create(Entry entry) throws IOException { journal.create(entry); }
        @Override public Entry decide(UUID id, Phase phase, Reason reason) throws IOException { return journal.decide(id, phase, reason); }
        @Override public void close() throws IOException { journal.close(); }
    }
}
