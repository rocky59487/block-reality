package com.blockreality.core.world;

import com.blockreality.api.geom.BlockKey;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionLedgerTest {
    static final ConstructionDeclaration X = new ConstructionDeclaration("steel", "steel_rect_200x400", 0);
    static BlockKey p(int x) { return new BlockKey(x, 200, 8); }
    static void settle(ConstructionLedger ledger) {
        var done = ConstructionLedger.reconcile(ledger.work()); assertEquals("", done.failure());
        assertTrue(ledger.publish(done)); assertTrue(ledger.ready());
    }
    static ConstructionLedger beam() {
        var ledger = new ConstructionLedger(); for (int x = 0; x < 5; x++) ledger.observe(p(x), X);
        settle(ledger); return ledger;
    }
    static long owner(ConstructionLedger l, BlockKey p) { return l.graph().owners().get(p); }

    @Test void frameRunsKeepParallelCrossingProductAndUndeclaredAxesSeparate() {
        var l = beam();
        for (int x = 0; x < 3; x++) l.observe(new BlockKey(x, 200, 9), X);
        l.observe(new BlockKey(1, 201, 8), new ConstructionDeclaration("steel", "steel_rect_200x400", 1));
        l.observe(p(5), new ConstructionDeclaration("steel", "steel_rect_100x200", 0));
        l.observe(p(10), new ConstructionDeclaration("steel", "steel_rect_200x400", -1));
        l.observe(p(11), new ConstructionDeclaration("steel", "steel_rect_200x400", -1)); settle(l);
        assertEquals(6, l.graph().activeCount());
        assertEquals(owner(l, p(0)), owner(l, p(4)));
        assertNotEquals(owner(l, p(0)), owner(l, new BlockKey(1, 200, 9)));
        assertNotEquals(owner(l, p(10)), owner(l, p(11)));
    }

    @Test void monolithAndPanelGroupsUseOnlyDeclaredProductsAndFaceAdjacency() {
        var l = new ConstructionLedger();
        var concrete = new ConstructionDeclaration("concrete", "concrete_rect_400x600", 0);
        l.observe(p(0), concrete); l.observe(p(1), concrete); l.observe(new BlockKey(1, 201, 8), concrete);
        l.observe(new BlockKey(2, 202, 8), concrete); // diagonal contact alone
        l.observe(p(2), new ConstructionDeclaration("brick", "brick_rect_230x350", 0));
        l.observe(p(10), new ConstructionDeclaration("concrete", "concrete_slab_200", 0));
        l.observe(p(11), new ConstructionDeclaration("concrete", "concrete_slab_200", 2));
        l.observe(p(12), new ConstructionDeclaration("concrete", "concrete_slab_150", 0)); settle(l);
        assertEquals(5, l.graph().activeCount());
        assertEquals(owner(l, p(0)), owner(l, new BlockKey(1, 201, 8)));
        assertNotEquals(owner(l, p(0)), owner(l, new BlockKey(2, 202, 8)));
        assertEquals(owner(l, p(10)), owner(l, p(11))); assertNotEquals(owner(l, p(11)), owner(l, p(12)));
    }

    @Test void noOpExtensionTrimAndUnrelatedObjectsRetainPublishedIdentity() {
        var l = beam(); long id = owner(l, p(0));
        byte[] before = l.encode(); assertFalse(l.observe(p(0), X)); assertArrayEquals(before, l.encode());
        l.observe(p(5), X); l.observe(p(10), X); settle(l); long other = owner(l, p(10));
        l.remove(p(0)); settle(l);
        assertEquals(id, owner(l, p(5))); assertEquals(other, owner(l, p(10)));
        assertEquals(List.of(p(1),p(2),p(3),p(4),p(5)), l.graph().records().get(id).cells());
    }

    @Test void splitAndMergeCreateNewIdentitiesWithExactLineage() {
        var l = beam(); long root = owner(l, p(0));
        l.remove(p(2)); settle(l); long left = owner(l, p(0)), right = owner(l, p(4));
        assertTrue(left > root && right > root && left != right); assertFalse(l.graph().records().get(root).active());
        assertEquals(List.of(root), l.graph().records().get(left).parents());
        assertEquals(List.of(root), l.graph().records().get(right).parents());
        l.observe(p(2), X); settle(l); long merged = owner(l, p(0));
        assertTrue(merged > right); assertEquals(List.of(left,right), l.graph().records().get(merged).parents());
        assertEquals(1, l.graph().activeCount()); assertEquals(4, l.graph().records().size());
    }

    @Test void splitRetiresParentEvenWhenOneChildWasCompletelyReplacedInTheBatch() {
        var l = beam(); long root = owner(l, p(0));
        l.remove(p(2));
        for (int x = 3; x < 5; x++) { l.remove(p(x)); l.observe(p(x), X); }
        settle(l); long left = owner(l, p(0)), right = owner(l, p(4));
        assertTrue(left > root && right > root && left != right);
        assertFalse(l.graph().records().get(root).active());
        assertEquals(List.of(root), l.graph().records().get(left).parents());
        assertEquals(List.of(root), l.graph().records().get(right).parents());
    }

    @Test void fullDestructionAndSameTickReplacementCannotResurrectIdentityEvenAfterPendingSave() {
        var l = beam(); long old = owner(l, p(0)); UUID namespace = l.graph().namespace();
        for (int x = 0; x < 5; x++) l.remove(p(x));
        for (int x = 0; x < 5; x++) l.observe(p(x), X);
        var restored = ConstructionLedger.decode(l.encode()); assertTrue(restored.pending()); settle(restored);
        assertEquals(namespace, restored.graph().namespace()); assertNotEquals(old, owner(restored, p(0)));
        assertFalse(restored.graph().records().get(old).active());
        assertEquals(List.of(old), restored.graph().records().get(owner(restored, p(0))).parents());
    }

    @Test void immutableWorkerInputAndStaleCompletionCannotOverwriteNewEdits() throws Exception {
        var l = beam(); l.observe(p(5), X); var work = l.work();
        assertThrows(UnsupportedOperationException.class, () -> work.cells().clear());
        var pool = Executors.newSingleThreadExecutor();
        try {
            Thread caller = Thread.currentThread();
            var done = pool.submit(() -> { assertNotSame(caller, Thread.currentThread()); return ConstructionLedger.reconcile(work); }).get();
            l.remove(p(4)); assertFalse(l.publish(done)); assertTrue(l.pending());
            assertEquals(6, work.cells().size()); assertEquals(5, l.cellCount()); settle(l);
            assertEquals(2, l.graph().activeCount());
        } finally { pool.shutdownNow(); }
    }

    @Test void restoredRegistryIsIndependentOfLoadedObservationOrderAndEmptyWorldRetires() {
        var base = beam(); byte[] saved = base.encode();
        var a = ConstructionLedger.decode(saved); var b = ConstructionLedger.decode(saved);
        for (int x = 0; x < 5; x++) a.observe(p(x), X);
        for (int x = 4; x >= 0; x--) b.observe(p(x), X);
        assertArrayEquals(saved, a.encode()); assertArrayEquals(a.encode(), b.encode());
        for (int x = 0; x < 5; x++) a.remove(p(x)); settle(a);
        assertEquals(0, a.graph().activeCount()); assertEquals(1, a.graph().records().size());
        var loaded = ConstructionLedger.decode(a.encode()); assertEquals(a.graph().namespace(), loaded.graph().namespace());
        loaded.observe(p(0), X); settle(loaded); assertEquals(2, owner(loaded, p(0)));
    }

    @Test void cellAndEpochBoundsRefuseWithoutEvictionOrWraparound() {
        var l = new ConstructionLedger();
        for (int x = 0; x < WorldCellIndex.MAX_CELLS; x++) assertTrue(l.observe(p(x), X));
        assertFalse(l.observe(p(-1), X)); assertFalse(l.failure().isEmpty()); assertEquals(WorldCellIndex.MAX_CELLS, l.cellCount());
        assertFalse(ConstructionLedger.decode(l.encode()).failure().isEmpty());
        var epoch = new ConstructionLedger(); epoch.epoch = Long.MAX_VALUE;
        assertFalse(epoch.observe(p(0), X)); assertEquals(Long.MAX_VALUE, epoch.epoch()); assertEquals(0, epoch.cellCount());
        var exhausted = beam(); exhausted.epoch = exhausted.completedEpoch = Long.MAX_VALUE;
        var savedGraph = exhausted.graph();
        assertFalse(exhausted.remove(p(0))); assertSame(savedGraph, exhausted.graph());
        var refused = ConstructionLedger.decode(exhausted.encode());
        assertFalse(refused.failure().isEmpty()); assertEquals(5, refused.cellCount());
    }

    @Test void historyAndLineageBoundsCannotEvictPublishedAncestors() {
        var records = new HashMap<Long, ConstructionLedger.Artifact>();
        for (long i=1; i<=ConstructionLedger.MAX_RECORDS; i++) records.put(i, new ConstructionLedger.Artifact(i, X, List.of(), List.of()));
        var graph = new ConstructionLedger.Graph(UUID.randomUUID(), ConstructionLedger.MAX_RECORDS+1L, records);
        var l = new ConstructionLedger(graph); l.observe(p(0), X);
        var done = ConstructionLedger.reconcile(l.work()); assertFalse(done.failure().isEmpty());
        assertTrue(l.publish(done)); assertSame(graph, l.graph()); assertFalse(l.failure().isEmpty());
        var roots = new HashMap<Long, ConstructionLedger.Artifact>(); var parents = new ArrayList<Long>();
        for (long i=1;i<=512;i++) { parents.add(i); roots.put(i,new ConstructionLedger.Artifact(i,X,List.of(),List.of())); }
        for (long i=513;i<=1536;i++) roots.put(i,new ConstructionLedger.Artifact(i,X,parents,List.of()));
        assertDoesNotThrow(() -> new ConstructionLedger.Graph(UUID.randomUUID(),1537,roots));
        roots.put(1537L,new ConstructionLedger.Artifact(1537,X,List.of(1L),List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ConstructionLedger.Graph(UUID.randomUUID(),1538,roots));
    }
}
