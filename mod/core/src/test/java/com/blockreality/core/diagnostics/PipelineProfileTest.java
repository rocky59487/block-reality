package com.blockreality.core.diagnostics;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.blockreality.core.diagnostics.PipelineProfile.Stage.*;
import static com.blockreality.core.diagnostics.PipelineProfile.Counter.*;

class PipelineProfileTest {
    @Test void disabledDoesNotReadClockAllocateSpansOrStoreSamples() {
        var p = new PipelineProfile(() -> { throw new AssertionError("disabled clock"); });
        var a = p.begin(APPLY); var b = p.begin(NATIVE_CALL); assertSame(a, b);
        a.add(REQUEST_BYTES, 100); a.close(); b.close();
        assertEquals(0, p.snapshot().session()); assertTrue(p.snapshot().stages().isEmpty());
    }
    @Test void exactNearestRankQuantilesAndBoundedRollingWindow() {
        var clock = new AtomicLong(); var p = new PipelineProfile(clock::get); p.start();
        for (int i=1;i<=300;i++) { var span=p.begin(APPLY); clock.addAndGet(i); span.close(); }
        var s=p.snapshot().stages().get(APPLY);
        assertEquals(300,s.observed()); assertEquals(256,s.retained());
        assertEquals(172,s.p50Ns()); assertEquals(288,s.p95Ns()); assertEquals(300,s.maxNs());
    }
    @Test void stopAndRestartRejectOldWorkAndPreserveImmutableStoppedSnapshot() {
        var clock=new AtomicLong(); var p=new PipelineProfile(clock::get); p.start();
        var pending=p.begin(NATIVE_CALL); var one=p.begin(APPLY); clock.set(3); one.close();
        var snapshot=p.snapshot(); p.stop(); pending.close();
        assertFalse(p.snapshot().active()); assertEquals(snapshot.stages(),p.snapshot().stages());
        assertThrows(UnsupportedOperationException.class,()->snapshot.stages().clear());
        p.start(); var old=p.begin(APPLY); p.start(); clock.set(8); old.add(REQUEST_BYTES,99); old.close();
        assertEquals(3,p.snapshot().session()); assertTrue(p.snapshot().stages().isEmpty());
        assertEquals(0,p.snapshot().counters().get(REQUEST_BYTES));
    }
    @Test void closeIsIdempotentAndInvalidDurationsAreNotRecorded() {
        var clock=new AtomicLong(100); var p=new PipelineProfile(clock::get); p.start();
        var zero=p.begin(APPLY); zero.close(); zero.close(); zero.add(REQUEST_BYTES,99);
        var negative=p.begin(APPLY); clock.set(99); negative.close();
        assertEquals(1,p.snapshot().stages().get(APPLY).observed());
        assertEquals(0,p.snapshot().stages().get(APPLY).maxNs());
        assertEquals(0,p.snapshot().counters().get(REQUEST_BYTES));
    }
    @Test void concurrentCompletionsAndCountersStayBounded() throws Exception {
        var clock=new AtomicLong(); var p=new PipelineProfile(clock::incrementAndGet); p.start();
        var pool=Executors.newFixedThreadPool(4);
        try {
            var tasks=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<4;i++)tasks.add(pool.submit(()->{for(int n=0;n<1000;n++){
                var span=p.begin(NATIVE_CALL); span.add(REQUEST_BYTES,8); span.add(REPLY_BYTES,-1);span.close();
            }}));
            for(var task:tasks)task.get(5,TimeUnit.SECONDS);
            var s=p.snapshot(); assertEquals(4000,s.stages().get(NATIVE_CALL).observed());
            assertEquals(256,s.stages().get(NATIVE_CALL).retained()); assertEquals(32000,s.counters().get(REQUEST_BYTES));
            assertEquals(0,s.counters().get(REPLY_BYTES));
        }finally{pool.shutdownNow();}
    }
    @Test void scheduledWorkKeepsItsOriginalSessionThroughWorkerAndDelivery() {
        var clock=new AtomicLong(); var p=new PipelineProfile(clock::incrementAndGet);
        var disabled=p.capture(); p.start(); var first=p.capture(); p.start();
        p.run(disabled,()->{try(var s=p.begin(NATIVE_CALL)){s.add(REQUEST_BYTES,9);}});
        p.run(first,()->{try(var s=p.begin(RESULT_DECODE)){
            var delivery=p.capture(); p.run(delivery,()->p.begin(APPLY).close());
        }});
        assertTrue(p.snapshot().stages().isEmpty());
        assertEquals(0,p.snapshot().counters().get(REQUEST_BYTES));
        assertThrows(IllegalStateException.class,()->p.run(first,()->{throw new IllegalStateException("original failure");}));
        p.begin(APPLY).close(); assertEquals(1,p.snapshot().stages().get(APPLY).observed());
    }
}
