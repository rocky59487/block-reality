package com.blockreality.core.finite;

import com.blockreality.core.bsi.BsiCorot;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.finite.FiniteCodec.Operation;
import com.blockreality.core.transaction.ConstructionTransaction.Entry;
import com.blockreality.core.transaction.ConstructionTransaction.Phase;
import com.blockreality.core.transaction.ConstructionTransaction.Reason;
import com.blockreality.core.transaction.FileTransactionJournal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.blockreality.core.finite.FiniteFixtures.*;

/**
 * The durable history path (spec O7/O8): private staging, checkpoint export, object
 * publish, and the {@code CommitHistory} operation's strict sequencing — same world
 * revision, identical source, historySequence + 1, mandatory checkpoint. A stale
 * proposal can never be applied: the root change's before-image is the basis CAS.
 */
class FiniteHistoryTest {

    @TempDir Path root;

    private FileTransactionJournal journal;
    private FiniteStore store;

    private FiniteRegistry open() throws IOException {
        journal = new FileTransactionJournal(root.resolve("journal"), JOURNAL);
        store = FiniteStore.open(root.resolve("finite"));
        return FiniteRegistry.load(journal, store);
    }

    private UUID commit(FiniteRegistry.Prepared prepared) throws IOException {
        journal.create(Entry.prepared(prepared.intent()));
        journal.decide(prepared.request().id(), Phase.COMMITTED, Reason.NONE);
        return prepared.request().id();
    }

    private FiniteRegistry openWithDomain() throws IOException {
        var registry = open();
        registry.applyCommitted(journal, commit(registry.prepareCreateDomain(
                request(1, new Operation.CreateDomain(DIMENSION)), DIMENSION)));
        return registry;
    }

    private static String exportReply(String id, UUID domain, long revision, int format, int bytes) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id
                + "\",\"method\":\"bsi.corot.checkpoint.export\",\"revision\":" + revision
                + ",\"status\":\"exported\",\"basis\":{\"domain\":\"" + BsiFracture.hex(domain)
                + "\",\"revision\":" + revision + "},\"format\":" + format
                + ",\"bytes\":" + bytes + "}";
    }

    @Test void solveThenArcCommitHistoryAdvancesSequenceOnly() throws Exception {
        var registry = openWithDomain();
        var committedDomain = registry.domain(DIMENSION).orElseThrow().id();
        var source = FiniteFixtures.source(committedDomain.bsiDomain(), 0);
        var sourceRef = store.publish(new UUID(80, 1), FiniteCodec.KIND_SOURCE, source.serialize());
        var genesisNext = new FiniteRoot.Next(0, 0, sourceRef, null);
        registry.applyCommitted(journal, commit(registry.prepareCommitRoot(
                request(2, new Operation.CommitRoot(genesisNext)), committedDomain, genesisNext)));
        var history = new FiniteHistory(store, registry);

        // Stage 1 — solve on a private session, export format 1, publish, commit seq 1.
        var lane1 = new FiniteSessionTestProbe();
        lane1.reply(vocabReply("f1", 0));
        lane1.reply(worldOk(committedDomain, 0));
        lane1.reply(FiniteSessionTestProbe.solveOk("f2", committedDomain.bsiDomain(), 0,
                committedDomain.artifactNamespace(), CorotArcFixtures.TOKEN_A, "0123456789abcdef", true));
        lane1.reply(exportReply("f3", committedDomain.bsiDomain(), 0, 1, 48), new byte[48]);
        var staged1 = history.stage(lane1, committedDomain, null, 1,
                1 << 20, 4096, 4096, 11, new UUID(90, 1), "engine/test", "cd".repeat(32));
        assertEquals(1, staged1.next().historySequence());
        assertEquals(0, staged1.next().worldRevision());
        assertEquals(sourceRef, staged1.next().source());         // identical source
        staged1.session().close();
        var stagedReq1 = request(3, new Operation.CommitHistory(staged1.next()));
        registry.applyCommitted(journal,
                commit(registry.prepareCommitHistory(stagedReq1, committedDomain, staged1.next())));
        var afterFirst = registry.root(committedDomain).orElseThrow();
        assertEquals(1, afterFirst.historySequence());
        assertEquals(0, afterFirst.worldRevision());
        assertEquals(staged1.blob(), afterFirst.checkpoint().blob());

        // Stage 2 — restore the committed checkpoint into a private handle, arc, export
        // format 2 (arc history requires ≥2), publish, commit seq 2.
        var lane2 = new FiniteSessionTestProbe();
        lane2.reply(vocabReply("f1", 0));
        lane2.reply(worldOk(committedDomain, 0));
        lane2.reply(FiniteSessionTestProbe.restored("f2", committedDomain.bsiDomain(), 0,
                committedDomain.artifactNamespace(), CorotArcFixtures.TOKEN_A, "0123456789abcdef"));
        lane2.reply(CorotArcFixtures.arcReply("f3", 0, committedDomain.bsiDomain(),
                        committedDomain.artifactNamespace(), "fedcba9876543210",
                        CorotArcFixtures.TOKEN_B, 1.25, 0.5, 1e-12),
                CorotArcFixtures.payload(committedDomain.bsiDomain(), 0));
        lane2.reply(exportReply("f4", committedDomain.bsiDomain(), 0, 2, 64), new byte[64]);
        var staged2 = history.stage(lane2, committedDomain, CorotArcFixtures.arcOptions(), 2,
                1 << 20, 4096, 4096, 22, new UUID(90, 2), "engine/test", "cd".repeat(32));
        assertEquals(2, staged2.next().historySequence());
        staged2.session().close();
        var stagedReq2 = request(4, new Operation.CommitHistory(staged2.next()));
        registry.applyCommitted(journal,
                commit(registry.prepareCommitHistory(stagedReq2, committedDomain, staged2.next())));
        var afterSecond = registry.root(committedDomain).orElseThrow();
        assertEquals(2, afterSecond.historySequence());
        assertEquals(0, afterSecond.worldRevision());
        assertEquals(staged2.blob(), afterSecond.checkpoint().blob());
        assertEquals(2, afterSecond.checkpoint().format());
        // The chain links: seq-2 root's prevRootHash is the seq-1 root image.
        assertFalse(afterSecond.genesis());

        // Replay survives a reload: historySequence and checkpoint arrive intact.
        store.close(); journal.close();
        registry = open();
        var folded = registry.root(committedDomain).orElseThrow();
        assertEquals(2, folded.historySequence());
        assertEquals(staged2.blob(), folded.checkpoint().blob());
        store.close(); journal.close();
    }

    private static String worldOk(FiniteDomainId domain, long revision) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"w1\",\"method\":\"bsi.world.declare\","
                + "\"revision\":" + revision + ",\"status\":\"ok\",\"identity\":{\"domain\":\""
                + BsiFracture.hex(domain.bsiDomain()) + "\",\"revision\":" + revision
                + ",\"artifactNamespace\":\"" + BsiFracture.hex(domain.artifactNamespace()) + "\"}}";
    }

    @Test void historyCommitRejectsEverySequencingViolation() throws Exception {
        var registry = openWithDomain();
        var domainIdentity = registry.domain(DIMENSION).orElseThrow().id();
        var source = FiniteFixtures.source(domainIdentity.bsiDomain(), 0);
        var sourceRef = store.publish(new UUID(80, 1), FiniteCodec.KIND_SOURCE, source.serialize());
        var genesisNext = new FiniteRoot.Next(0, 0, sourceRef, null);
        registry.applyCommitted(journal, commit(registry.prepareCommitRoot(
                request(2, new Operation.CommitRoot(genesisNext)), domainIdentity, genesisNext)));
        var checkpointBlob = store.publish(new UUID(80, 2), FiniteCodec.KIND_CHECKPOINT, new byte[48]);
        var slot = new FiniteRoot.Checkpoint(checkpointBlob, 1, "engine/test", "cd".repeat(32),
                1 << 20, 4096, 4096);

        for (var bad : new FiniteRoot.Next[]{
                new FiniteRoot.Next(1, 1, sourceRef, slot),        // crossing the world boundary
                new FiniteRoot.Next(0, 2, sourceRef, slot),        // skipping a sequence
                new FiniteRoot.Next(0, 1, sourceRef, null),        // history without a checkpoint
        }) {
            assertThrows(IllegalArgumentException.class, () -> registry.prepareCommitHistory(
                    request(90, new Operation.CommitHistory(bad)), domainIdentity, bad), bad.toString());
        }
        // A different source on the same revision is not history — it is a world change.
        var otherSource = FiniteFixtures.source(domainIdentity.bsiDomain(), 1);
        var otherRef = store.publish(new UUID(80, 3), FiniteCodec.KIND_SOURCE, otherSource.serialize());
        var drift = new FiniteRoot.Next(0, 1, otherRef, slot);
        assertThrows(IllegalArgumentException.class, () -> registry.prepareCommitHistory(
                request(91, new Operation.CommitHistory(drift)), domainIdentity, drift));
        // Same-revision work through CommitRoot is refused — the split is semantic.
        var sameRev = new FiniteRoot.Next(0, 1, sourceRef, slot);
        assertThrows(IllegalArgumentException.class, () -> registry.prepareCommitRoot(
                request(92, new Operation.CommitRoot(sameRev)), domainIdentity, sameRev));
        // CommitHistory on a domain that never committed a root has nothing to advance.
        var otherDimension = "blockreality:second";
        var create = registry.prepareCreateDomain(request(93, new Operation.CreateDomain(otherDimension)),
                otherDimension);
        registry.applyCommitted(journal, commit(create));
        var secondDomain = registry.domain(otherDimension).orElseThrow().id();
        var orphanRoot = new FiniteRoot.Next(0, 0, sourceRef, slot);
        assertThrows(IllegalArgumentException.class, () -> registry.prepareCommitHistory(
                request(94, new Operation.CommitHistory(orphanRoot)), secondDomain, orphanRoot));

        // The committed root never moved.
        var still = registry.root(domainIdentity).orElseThrow();
        assertEquals(0, still.historySequence());
        assertNull(still.checkpoint());
        store.close(); journal.close();
    }

    @Test void staleProposalFailsTheBasisCompareAndSwap() throws Exception {
        var registry = openWithDomain();
        var domainIdentity = registry.domain(DIMENSION).orElseThrow().id();
        var source = FiniteFixtures.source(domainIdentity.bsiDomain(), 0);
        var sourceRef = store.publish(new UUID(80, 1), FiniteCodec.KIND_SOURCE, source.serialize());
        var genesisNext = new FiniteRoot.Next(0, 0, sourceRef, null);
        registry.applyCommitted(journal, commit(registry.prepareCommitRoot(
                request(2, new Operation.CommitRoot(genesisNext)), domainIdentity, genesisNext)));
        var history = new FiniteHistory(store, registry);

        var lane1 = new FiniteSessionTestProbe();
        lane1.reply(vocabReply("f1", 0));
        lane1.reply(worldOk(domainIdentity, 0));
        lane1.reply(FiniteSessionTestProbe.solveOk("f2", domainIdentity.bsiDomain(), 0,
                domainIdentity.artifactNamespace(), CorotArcFixtures.TOKEN_A, "0123456789abcdef", true));
        lane1.reply(exportReply("f3", domainIdentity.bsiDomain(), 0, 1, 48), new byte[48]);
        var staged = history.stage(lane1, domainIdentity, null, 1,
                1 << 20, 4096, 4096, 11, new UUID(90, 1), "engine/test", "cd".repeat(32));
        staged.session().close();
        // Prepare the proposal but do NOT journal it yet — its before-image is the
        // genesis root bytes. The competing commit below moves the authority first.
        var stalePrepared = registry.prepareCommitHistory(
                request(3, new Operation.CommitHistory(staged.next())), domainIdentity, staged.next());

        // Another history commit lands first — the authority moves to seq 1.
        var lane2 = new FiniteSessionTestProbe();
        lane2.reply(vocabReply("f1", 0));
        lane2.reply(worldOk(domainIdentity, 0));
        lane2.reply(FiniteSessionTestProbe.solveOk("f2", domainIdentity.bsiDomain(), 0,
                domainIdentity.artifactNamespace(), CorotArcFixtures.TOKEN_B, "fedcba9876543210", true));
        lane2.reply(exportReply("f3", domainIdentity.bsiDomain(), 0, 1, 64), new byte[64]);
        var staged2 = history.stage(lane2, domainIdentity, null, 1,
                1 << 20, 4096, 4096, 22, new UUID(90, 2), "engine/test", "cd".repeat(32));
        staged2.session().close();
        registry.applyCommitted(journal, commit(registry.prepareCommitHistory(
                request(4, new Operation.CommitHistory(staged2.next())), domainIdentity, staged2.next())));
        assertEquals(1, registry.root(domainIdentity).orElseThrow().historySequence());

        // Only now journal the stale intent: the before-image it carries is the root
        // that no longer exists, so it can never validate against committed authority.
        journal.create(Entry.prepared(stalePrepared.intent()));
        var staleEntry = journal.read(stalePrepared.request().id()).orElseThrow();
        assertThrows(IOException.class, () -> registry.validatePrepared(staleEntry));

        // And if it were force-committed anyway, applyCommitted fails closed rather than
        // rewinding the root: the journal decision cannot move a mismatched before-image.
        journal.decide(stalePrepared.request().id(), Phase.COMMITTED, Reason.NONE);
        assertThrows(IOException.class,
                () -> registry.applyCommitted(journal, stalePrepared.request().id()));
        assertThrows(IllegalStateException.class, () -> registry.root(domainIdentity));
        store.close(); journal.close();
    }

    @Test void stagingFailureLeavesAuthorityUntouched() throws Exception {
        var registry = openWithDomain();
        var domainIdentity = registry.domain(DIMENSION).orElseThrow().id();
        var source = FiniteFixtures.source(domainIdentity.bsiDomain(), 0);
        var sourceRef = store.publish(new UUID(80, 1), FiniteCodec.KIND_SOURCE, source.serialize());
        var genesisNext = new FiniteRoot.Next(0, 0, sourceRef, null);
        registry.applyCommitted(journal, commit(registry.prepareCommitRoot(
                request(2, new Operation.CommitRoot(genesisNext)), domainIdentity, genesisNext)));
        var history = new FiniteHistory(store, registry);

        var lane = new FiniteSessionTestProbe();
        lane.reply(vocabReply("f1", 0));
        lane.reply(worldOk(domainIdentity, 0));
        lane.error("SOLVE_FAILED");                            // the private solve dies
        assertThrows(IOException.class, () -> history.stage(lane, domainIdentity, null, 1,
                1 << 20, 4096, 4096, 11, new UUID(90, 1), "engine/test", "cd".repeat(32)));
        assertEquals(0, registry.root(domainIdentity).orElseThrow().historySequence());
        assertNull(registry.root(domainIdentity).orElseThrow().checkpoint());
        store.close(); journal.close();
    }
}
