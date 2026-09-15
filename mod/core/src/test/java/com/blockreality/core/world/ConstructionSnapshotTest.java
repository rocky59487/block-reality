package com.blockreality.core.world;

import com.blockreality.api.geom.BlockKey;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionSnapshotTest {
    private static final BlockKey FIRST = new BlockKey(4096,128,0), NEXT = new BlockKey(4097,128,0);
    private static final ConstructionDeclaration X = new ConstructionDeclaration("steel", "steel_rect_200x400", 0);

    @Test void workSnapshotDoesNotAliasCallerCollections() {
        var cells = new HashMap<BlockKey,ConstructionDeclaration>(); cells.put(FIRST,X);
        var destroyed = new HashSet<BlockKey>(); destroyed.add(FIRST);
        var work = new ConstructionLedger.Work(2,cells,destroyed,new ConstructionLedger().graph());
        cells.clear(); cells.put(NEXT,X); destroyed.clear(); destroyed.add(NEXT);
        assertEquals(Map.of(FIRST,X),work.cells()); assertEquals(Set.of(FIRST),work.destroyed());
    }

    @Test void workAndGraphViewsRejectEveryMutableEntryPoint() {
        var ledger = new ConstructionLedger(); ledger.observe(FIRST,X);
        assertTrue(ledger.publish(ConstructionLedger.reconcile(ledger.work())));
        var owners = ledger.graph().owners(); assertReadOnly(owners,FIRST,2L);
        ledger.remove(FIRST); ledger.observe(FIRST,X); var work = ledger.work();
        assertReadOnly(work.cells(),FIRST,new ConstructionDeclaration("steel","steel_rect_200x400",2));
        assertThrows(UnsupportedOperationException.class, () -> work.destroyed().clear());
        assertThrows(UnsupportedOperationException.class, () -> work.destroyed().remove(FIRST));
        assertThrows(UnsupportedOperationException.class, () -> work.destroyed().removeIf(p -> true));
        var iterator = work.destroyed().iterator(); iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertTrue(ledger.publish(ConstructionLedger.reconcile(work)));
        assertEquals(Set.of(FIRST),work.destroyed(),"publication cannot clear captured destruction");
        assertEquals(Map.of(FIRST,1L),owners,"old graph cannot observe a replacement's ownership");
        assertEquals(2L,ledger.graph().owners().get(FIRST));
    }

    @Test void graphRecordsAndRangeViewsNeverExposeMutableEntries() {
        var one = new ConstructionLedger.Artifact(1,X,List.of(),List.of(FIRST));
        var two = new ConstructionLedger.Artifact(2,X,List.of(),List.of(NEXT));
        var input = new HashMap<Long,ConstructionLedger.Artifact>(); input.put(1L,one); input.put(2L,two);
        var graph = new ConstructionLedger.Graph(UUID.randomUUID(),3,input); input.clear();
        assertEquals(2,graph.records().size());
        for (var view : List.of(graph.records(),graph.records().descendingMap(),graph.records().headMap(2L,false),
                graph.records().tailMap(1L,false),graph.records().subMap(1L,true,2L,true))) {
            long key = view.firstKey(); assertReadOnly(view,key,view.get(key));
            assertThrows(UnsupportedOperationException.class, () -> view.firstEntry().setValue(one));
            assertThrows(UnsupportedOperationException.class, () -> view.lastEntry().setValue(two));
        }
        assertEquals(Map.of(1L,one,2L,two),graph.records());
    }

    private static <K,V> void assertReadOnly(Map<K,V> map, K key, V replacement) {
        assertThrows(UnsupportedOperationException.class, () -> map.put(key,replacement));
        assertThrows(UnsupportedOperationException.class, map::clear);
        assertThrows(UnsupportedOperationException.class, () -> map.remove(key));
        assertThrows(UnsupportedOperationException.class, () -> map.replaceAll((p,v) -> replacement));
        assertThrows(UnsupportedOperationException.class, () -> map.compute(key,(p,v) -> replacement));
        assertThrows(UnsupportedOperationException.class, () -> map.merge(key,replacement,(a,b) -> b));
        assertThrows(UnsupportedOperationException.class, () -> map.keySet().remove(key));
        assertThrows(UnsupportedOperationException.class, () -> map.values().clear());
        var iterator = map.entrySet().iterator(); var entry = iterator.next();
        assertThrows(UnsupportedOperationException.class, () -> entry.setValue(replacement));
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        var arrayEntry = map.entrySet().toArray(Map.Entry<?,?>[]::new)[0];
        assertThrows(UnsupportedOperationException.class, () -> arrayEntry.setValue(null));
        var typedEntry = map.entrySet().toArray(new Map.Entry<?,?>[0])[0];
        assertThrows(UnsupportedOperationException.class, () -> typedEntry.setValue(null));
        var objectEntry = (Map.Entry<?,?>) map.entrySet().toArray()[0];
        assertThrows(UnsupportedOperationException.class, () -> objectEntry.setValue(null));
        map.entrySet().stream().forEach(e -> assertThrows(UnsupportedOperationException.class, () -> e.setValue(replacement)));
        map.entrySet().spliterator().forEachRemaining(e -> assertThrows(UnsupportedOperationException.class, () -> e.setValue(replacement)));
        assertThrows(UnsupportedOperationException.class, () -> map.entrySet().removeIf(e -> true));
    }

    @Test void snapshotNullRejectionSurvivesHashBackedStorage() {
        var cells = new HashMap<BlockKey,ConstructionDeclaration>(); var graph = new ConstructionLedger().graph();
        cells.put(null,X);
        assertThrows(NullPointerException.class, () -> new ConstructionLedger.Work(0,cells,Set.of(),graph));
        cells.clear(); cells.put(FIRST,null);
        assertThrows(NullPointerException.class, () -> new ConstructionLedger.Work(0,cells,Set.of(),graph));
        cells.put(FIRST,X); var destroyed = new HashSet<BlockKey>(); destroyed.add(null);
        assertThrows(NullPointerException.class, () -> new ConstructionLedger.Work(0,cells,destroyed,graph));
    }

    @Test void backgroundSnapshotRemainsStableDuringEditsAndStaleResultCannotPublish() throws Exception {
        var ledger = new ConstructionLedger(); for (int x=4096;x<4224;x++) ledger.observe(new BlockKey(x,128,0),X);
        assertTrue(ledger.publish(ConstructionLedger.reconcile(ledger.work())));
        ledger.remove(FIRST); ledger.observe(FIRST,X); var work = ledger.work();
        var started = new CountDownLatch(1); var edited = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(() -> {
                started.countDown(); assertTrue(edited.await(10,TimeUnit.SECONDS));
                assertEquals(128,work.cells().size()); assertEquals(X,work.cells().get(FIRST));
                assertEquals(Set.of(FIRST),work.destroyed());
                var completion = ConstructionLedger.reconcile(work); assertEquals("",completion.failure()); return completion;
            });
            assertTrue(started.await(10,TimeUnit.SECONDS));
            for (int x=4096;x<4160;x++) ledger.remove(new BlockKey(x,128,0));
            edited.countDown();
            assertFalse(ledger.publish(future.get(10,TimeUnit.SECONDS))); assertTrue(ledger.pending());
            assertTrue(ledger.publish(ConstructionLedger.reconcile(ledger.work())));
            assertTrue(ledger.ready()); assertEquals(64,ledger.cellCount());
        } finally { edited.countDown(); pool.shutdownNow(); }
    }
}
