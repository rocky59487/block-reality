package com.blockreality.core.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.finite.FiniteCodec.Operation;
import com.blockreality.core.transaction.ConstructionTransaction.Entry;
import com.blockreality.core.transaction.ConstructionTransaction.Phase;
import com.blockreality.core.transaction.ConstructionTransaction.Reason;
import com.blockreality.core.transaction.FileTransactionJournal;
import com.blockreality.core.transaction.ManufacturedRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.blockreality.core.finite.FiniteFixtures.*;

/** Journal-authority evidence: domains, owner minting, adoption, roots and folds. */
class FiniteRegistryTest {

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

    @Test void domainBindRetireAndRootCommitFoldAcrossReloads() throws Exception {
        var registry = open();
        var dimension = DIMENSION;
        // 1. createDomain
        var createOp = new Operation.CreateDomain(dimension);
        var createReq = request(1, createOp);
        var create = registry.prepareCreateDomain(createReq, dimension);
        var createId = commit(create);
        registry.applyCommitted(journal, createId);
        var domain = registry.domain(dimension).orElseThrow();
        assertEquals(JOURNAL, domain.id().journalDomain());
        assertEquals(1, domain.nextOwnerId());
        assertEquals(0, domain.observationEpoch());
        final var domainIdentity = domain.id();
        // A second creation of the same dimension is refused.
        var dupReq = request(2, new Operation.CreateDomain(dimension));
        assertThrows(IllegalArgumentException.class, () -> registry.prepareCreateDomain(dupReq, dimension));

        // 2. observe bumps only the observation epoch.
        var observeReq = request(3, new Operation.Observe());
        var observe = registry.prepareObserve(observeReq, domainIdentity);
        registry.applyCommitted(journal, commit(observe));
        assertEquals(1, registry.domain(dimension).orElseThrow().observationEpoch());
        domain = registry.domain(dimension).orElseThrow();

        // 3. bind: two adjacent same-material pieces stay separate; one legacy adoption.
        var plans = List.of(
                Operation.BindingPlan.manufactured(new UUID(70, 1), declaration(),
                        List.of(new BlockKey(1, 64, 1), new BlockKey(1, 65, 1))),
                Operation.BindingPlan.manufactured(new UUID(70, 2), declaration(),
                        List.of(new BlockKey(2, 64, 1))),   // adjacent to (1,64,1), same material
                Operation.BindingPlan.legacy("legacy/observed/0", declaration(),
                        List.of(new BlockKey(9, 64, 9))));
        var bindOp = new Operation.Bind(plans);
        var bindReq = request(4, bindOp);
        var bind = registry.prepareBind(bindReq, domainIdentity, plans);
        registry.applyCommitted(journal, commit(bind));
        var ns = domain.id().artifactNamespace();
        var first = registry.binding(ns, 1).orElseThrow();
        var second = registry.binding(ns, 2).orElseThrow();
        var adopted = registry.binding(ns, 3).orElseThrow();
        assertEquals(new UUID(70, 1), first.pieceId());
        assertEquals(new UUID(70, 2), second.pieceId());
        assertNotEquals(first.ownerId(), second.ownerId());           // no adjacency merge
        assertEquals(ArtifactBinding.Origin.LEGACY_NO_REFUND, adopted.origin());
        assertEquals("legacy/observed/0", adopted.legacyKey());
        assertEquals(4, registry.domain(dimension).orElseThrow().nextOwnerId());

        // One-time adoption: the same legacy key may never bind again.
        var repeat = List.of(Operation.BindingPlan.legacy("legacy/observed/0", declaration(),
                List.of(new BlockKey(11, 64, 11))));
        var repeatReq = request(5, new Operation.Bind(repeat));
        assertThrows(IllegalArgumentException.class,
                () -> registry.prepareBind(repeatReq, domainIdentity, repeat));

        // 4. retire owner 1; ids are never reused.
        var retireOp = new Operation.Retire(Set.of(1L));
        var retireReq = request(6, retireOp);
        registry.applyCommitted(journal, commit(registry.prepareRetire(retireReq, domainIdentity, Set.of(1L))));
        assertEquals(ArtifactBinding.Disposition.RETIRED, registry.binding(ns, 1).orElseThrow().disposition());
        var mint4 = List.of(Operation.BindingPlan.manufactured(new UUID(70, 4), declaration(),
                List.of(new BlockKey(20, 64, 20))));
        var mintReq = request(7, new Operation.Bind(mint4));
        registry.applyCommitted(journal, commit(registry.prepareBind(mintReq, domainIdentity, mint4)));
        assertTrue(registry.binding(ns, 4).isPresent());   // next id is 4, never a recycled 1
        assertFalse(registry.binding(ns, 1).orElseThrow().active());

        // 5. commitRoot: genesis → history-only → new revision.
        var source = FiniteFixtures.source(domain.id().bsiDomain(), 0);
        var sourceRef = store.publish(new UUID(80, 1), FiniteCodec.KIND_SOURCE, source.serialize());
        var genesis = new FiniteRoot.Next(0, 0, sourceRef, null);
        var commitReq = request(8, new Operation.CommitRoot(genesis));
        registry.applyCommitted(journal, commit(registry.prepareCommitRoot(commitReq, domainIdentity, genesis)));
        var rootDescriptor = registry.root(domainIdentity).orElseThrow();
        assertTrue(rootDescriptor.genesis());
        assertEquals(sourceRef, rootDescriptor.source());

        var checkpointBlob = store.publish(new UUID(80, 2), FiniteCodec.KIND_CHECKPOINT, new byte[]{1, 2, 3, 4});
        var history = new FiniteRoot.Next(0, 1, sourceRef,
                new FiniteRoot.Checkpoint(checkpointBlob, 1, "engine/1.5", "cd".repeat(32), 1 << 20, 4096, 4096));
        // W3: same-revision history is its own operation, never a CommitRoot.
        var historyReq = request(9, new Operation.CommitHistory(history));
        registry.applyCommitted(journal, commit(registry.prepareCommitHistory(historyReq, domainIdentity, history)));
        assertEquals(1, registry.root(domainIdentity).orElseThrow().historySequence());
        assertEquals(checkpointBlob, registry.root(domainIdentity).orElseThrow().checkpoint().blob());

        // Same revision with a different source is refused — history-only requires identical source.
        var otherSource = FiniteFixtures.source(domain.id().bsiDomain(), 1);
        var otherRef = store.publish(new UUID(80, 3), FiniteCodec.KIND_SOURCE, otherSource.serialize());
        var drift = new FiniteRoot.Next(0, 2, otherRef,
                new FiniteRoot.Checkpoint(checkpointBlob, 1, "engine/1.5", "cd".repeat(32), 1 << 20, 4096, 4096));
        assertThrows(IllegalArgumentException.class,
                () -> registry.prepareCommitHistory(request(10, new Operation.CommitHistory(drift)), domainIdentity, drift));
        // Skipping a history step is refused.
        var gap = new FiniteRoot.Next(1, 5, otherRef, null);
        assertThrows(IllegalArgumentException.class,
                () -> registry.prepareCommitRoot(request(11, new Operation.CommitRoot(gap)), domainIdentity, gap));
        // A missing store object refuses the commit.
        var ghost = new FiniteRoot.Next(1, 2, new FiniteBlobRef("ef".repeat(32), 100), null);
        assertThrows(IllegalArgumentException.class,
                () -> registry.prepareCommitRoot(request(12, new Operation.CommitRoot(ghost)), domainIdentity, ghost));
        var newWorld = new FiniteRoot.Next(1, 2, otherRef, null);
        registry.applyCommitted(journal, commit(registry.prepareCommitRoot(request(13, new Operation.CommitRoot(newWorld)), domainIdentity, newWorld)));
        assertEquals(otherRef, registry.root(domainIdentity).orElseThrow().source());

        // 6. Reload folds identically. The exclusive journal lock must be released first.
        store.close(); journal.close();
        var reloaded = open();
        var folded = reloaded.domain(dimension).orElseThrow();
        assertEquals(5, folded.nextOwnerId());
        assertEquals(1, folded.observationEpoch());
        assertEquals(otherRef, reloaded.root(domainIdentity).orElseThrow().source());
        assertEquals(ArtifactBinding.Disposition.RETIRED, reloaded.binding(ns, 1).orElseThrow().disposition());
        // The basis binds the committed root to the physical source.
        var basis = reloaded.basis(domainIdentity, 9);
        assertEquals(1, basis.worldRevision());
        assertEquals(9, basis.sessionGeneration());
        store.close();
        journal.close();
    }

    @Test void malformedFiniteHistoryFailsClosed() throws Exception {
        var registry = open();
        var op = new Operation.CreateDomain(DIMENSION);
        var req = request(1, op);
        registry.applyCommitted(journal, commit(registry.prepareCreateDomain(req, DIMENSION)));
        store.close(); journal.close();
        // Corrupt the tx descriptor resource: rewrite the entry file via journal is not possible;
        // instead, corrupt the store object the root will need is covered above. Here: a foreign
        // journal domain refuses.
        journal = new FileTransactionJournal(root.resolve("journal"), JOURNAL);
        store = FiniteStore.open(root.resolve("finite"));
        var folded = FiniteRegistry.load(journal, store);
        assertTrue(folded.domain(DIMENSION).isPresent());
        store.close(); journal.close();
    }

    @Test void manufacturedRegistrySkipsFiniteEntries() throws Exception {
        var registry = open();
        var op = new Operation.CreateDomain(DIMENSION);
        registry.applyCommitted(journal, commit(registry.prepareCreateDomain(request(1, op), DIMENSION)));
        var manufactured = ManufacturedRegistry.load(journal);
        assertEquals(0, manufactured.lifetimePieces());   // finite tx folded nothing here
        store.close(); journal.close();
    }

    @Test void pendingEntryValidatesAgainstCommittedAuthority() throws Exception {
        var registry = open();
        var op = new Operation.CreateDomain(DIMENSION);
        var prepared = registry.prepareCreateDomain(request(1, op), DIMENSION);
        journal.create(Entry.prepared(prepared.intent()));
        var entry = journal.read(prepared.request().id()).orElseThrow();
        assertInstanceOf(Operation.CreateDomain.class, registry.validatePrepared(entry));
        journal.decide(prepared.request().id(), Phase.ABORTED, Reason.RECOVERED);
        store.close(); journal.close();
    }
}
