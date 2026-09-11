package com.blockreality.core.transaction;

import com.blockreality.core.bsi.*;
import com.blockreality.core.engine.InProcessEngine;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.blockreality.core.transaction.ConstructionTransaction.*;
import static com.blockreality.core.transaction.FractureTransactionFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeFractureTransactionsTest {
    @TempDir Path temp;
    private Path library() {
        String path=System.getProperty("br.engine",""); Assumptions.assumeTrue(!path.isEmpty(),"Requires actual br.engine");
        assertTrue(Files.isRegularFile(Path.of(path))); return Path.of(path);
    }
    @Test void durableReceiptReplayAndFreshSessionRecovery() throws Exception {
        var c=FractureCases.named("L"); var lib=library(); var host=new FractureTransactionFixture(c); var request=request(c);
        var nativeRef=new AtomicReference<InProcessEngine>(); var sessions=new AtomicInteger();
        try (var journal=new FileTransactionJournal(temp,c.world().stamp().domain());
             var bridge=new NativeFractureTransactions(journal,() -> { sessions.incrementAndGet(); var e=c.open(lib); nativeRef.set(e); return e; })) {
            assertFalse(bridge.ready()); bridge.recover(host); assertTrue(bridge.ready());
            Receipt result=bridge.execute(request,c.options(),host);
            assertEquals(Phase.COMMITTED,result.phase()); var after=c.world().remaining(c.receipt());
            assertEquals(after,host.visible); assertEquals(after,readWorld(host.durable.get("world/source")));
            assertEquals(after,nativeRef.get().identifiedWorld()); assertEquals(1,host.prepares); assertEquals(1,host.publishes);
            byte[] proof=host.read(NativeFractureTransactions.proofPrefix(request.id())+"0").bytes();
            assertArrayEquals(c.payload(),BsiFrame.decode(proof,proof.length).payload());
            for (int i=0;i<100;i++) assertEquals(result,bridge.execute(request,c.options(),host));
            assertEquals(1,host.prepares); assertEquals(1,host.publishes);
            // A later legitimate edit is authoritative on recovery; old COMMITTED images stay historical.
            var later=new BsiFracture.World(new BsiFracture.Stamp(after.stamp().domain(),after.stamp().revision()+1),after.artifactNamespace(),c.world().blocks(),c.world().owners());
            host.live.put("world/source",worldValue(later)); host.live.put("revision",revisionValue(later.stamp().revision())); host.durable.putAll(host.live);
            bridge.recover(host); assertEquals(2,sessions.get()); assertEquals(later,nativeRef.get().identifiedWorld()); assertEquals(later,host.visible);
            assertEquals(result,bridge.execute(request,c.options(),host)); assertEquals(later,host.snapshot()); assertEquals(1,host.prepares);
        }
    }
    @TestFactory Stream<DynamicTest> interruptionsBeforeDecisionRestoreWholeSource() {
        var lib=library();
        return Stream.of("checkpoint","write","flush","omitted-world").map(fault -> DynamicTest.dynamicTest(fault,() -> {
            var c=FractureCases.named("mixed-supported"); var host=new FractureTransactionFixture(c); var nativeRef=new AtomicReference<InProcessEngine>();
            host.failCheckpoint=fault.equals("checkpoint"); host.failWrite=fault.equals("write")?2:0;
            host.failFlush=fault.equals("flush"); host.omitWorldImage=fault.equals("omitted-world");
            try (var journal=new FileTransactionJournal(temp.resolve(fault),c.world().stamp().domain());
                 var bridge=new NativeFractureTransactions(journal,() -> { var e=c.open(lib); nativeRef.set(e); return e; })) {
                bridge.recover(host);
                if (fault.equals("checkpoint")) assertThrows(IOException.class,() -> bridge.execute(request(c),c.options(),host));
                else assertEquals(Phase.ABORTED,bridge.execute(request(c),c.options(),host).phase());
                assertEquals(c.world(),host.snapshot()); assertEquals(c.world(),host.visible); assertEquals(c.world(),nativeRef.get().identifiedWorld());
                assertEquals(0,host.publishes); assertFalse(journal.pending().isPresent());
                assertNull(nativeRef.get().finishFracture(host.prepared,true),"discarded candidate must not remove material later");
            }
        }));
    }
    @TestFactory Stream<DynamicTest> committedDecisionSurvivesLostAckAndPublicationFailure() {
        var lib=library();
        return Stream.of("decision-lost-ack","native-closed","publication").map(fault -> DynamicTest.dynamicTest(fault,() -> {
            var c=FractureCases.named("L"); var host=new FractureTransactionFixture(c); var nativeRef=new AtomicReference<InProcessEngine>();
            host.failPublish=fault.equals("publication");
            try (var journal=new FileTransactionJournal(temp.resolve(fault),c.world().stamp().domain(),(stage,entry) -> {
                if (stage!=FileTransactionJournal.Stage.TARGET_FORCED || entry.phase()!=Phase.COMMITTED) return;
                if (fault.equals("native-closed")) nativeRef.get().close();
                if (fault.equals("decision-lost-ack")) throw new IOException("Lost durable decision ACK");
            }); var bridge=new NativeFractureTransactions(journal,() -> { var e=c.open(lib); nativeRef.set(e); return e; })) {
                bridge.recover(host);
                if (fault.equals("decision-lost-ack")) assertEquals(Phase.COMMITTED,bridge.execute(request(c),c.options(),host).phase());
                else { assertThrows(AtomicConstructionCoordinator.RecoveryRequired.class,() -> bridge.execute(request(c),c.options(),host)); assertFalse(bridge.ready()); }
                assertEquals(Phase.COMMITTED,journal.read(c.request()).orElseThrow().phase());
                var after=c.world().remaining(c.receipt()); assertEquals(after,host.snapshot());
                assertEquals(after,readWorld(host.durable.get("world/source"))); assertTrue(host.writes<=3,"COMMITTED cannot roll back");
                var old=nativeRef.get(); host.failPublish=false; bridge.recover(host);
                assertNotSame(old,nativeRef.get()); assertEquals(InProcessEngine.Status.CLOSED,old.status());
                assertEquals(after,nativeRef.get().identifiedWorld()); assertEquals(after,host.visible); assertTrue(bridge.ready());
                assertEquals(Phase.COMMITTED,bridge.execute(request(c),c.options(),host).phase()); assertEquals(1,host.prepares);
            }
        }));
    }
    @Test void wrongPlanIsRejectedBeforeNativePreparationAndChangedKeyConflicts() throws Exception {
        var c=FractureCases.named("L"); var lib=library(); var host=new FractureTransactionFixture(c); var original=request(c);
        try (var journal=new FileTransactionJournal(temp,c.world().stamp().domain()); var bridge=new NativeFractureTransactions(journal,() -> c.open(lib))) {
            bridge.recover(host);
            var wrong=new Request(original.id(),original.actor(),original.session(),original.domain(),original.baseRevision(),"0".repeat(64));
            assertEquals(Reason.VALIDATION_REFUSED,bridge.execute(wrong,c.options(),host).reason()); assertEquals(0,host.prepares);
            assertThrows(AtomicConstructionCoordinator.ReplayConflict.class,() -> bridge.execute(original,c.options(),host));
            assertEquals(c.world(),host.snapshot());
        }
    }
    @Test void recoveryCannotPublishAnotherJournalsWorld() throws Exception {
        var c=FractureCases.named("L"); var lib=library(); var host=new FractureTransactionFixture(c);
        var other=new BsiFracture.World(new BsiFracture.Stamp(new UUID(400,401),c.world().stamp().revision()),c.world().artifactNamespace(),c.world().blocks(),c.world().owners());
        host.live.put("world/source",worldValue(other)); host.durable.putAll(host.live);
        var nativeRef=new AtomicReference<InProcessEngine>();
        try (var journal=new FileTransactionJournal(temp,c.world().stamp().domain());
             var bridge=new NativeFractureTransactions(journal,() -> { var e=c.open(lib); nativeRef.set(e); return e; })) {
            assertThrows(AtomicConstructionCoordinator.RecoveryRequired.class,() -> bridge.recover(host));
            assertFalse(bridge.ready()); assertEquals(0,host.baselines); assertEquals(InProcessEngine.Status.CLOSED,nativeRef.get().status());
        }
    }
}
