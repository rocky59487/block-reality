package com.blockreality.core.engine;

import com.blockreality.api.*;
import com.blockreality.core.bsi.BsiHeaders;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeGameRuntimeTest {
    private static final GameWorldSnapshot WORLD = new GameWorldSnapshot(new WorldRevision(17),List.of(),List.of(),List.of());
    private static final class Fake implements NativeGameRuntime.Session {
        final CountDownLatch entered = new CountDownLatch(1), proceed;
        final AtomicInteger calls=new AtomicInteger(), closes=new AtomicInteger(), active=new AtomicInteger(), max=new AtomicInteger();
        final AtomicBoolean closedDuringCall=new AtomicBoolean();
        Fake(boolean blocked) { proceed=new CountDownLatch(blocked?1:0); }
        @Override public AnalysisResult analyze(GameWorldSnapshot world,Integer threads,BsiHeaders.EigenBuckling buckling) {
            calls.incrementAndGet();max.accumulateAndGet(active.incrementAndGet(),Math::max);entered.countDown();
            try { if(!proceed.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test barrier never released"); }
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            finally {active.decrementAndGet();}
            return AnalysisResult.failed(world.revision(),"native fixture result");
        }
        @Override public boolean ready(){return true;}
        @Override public void close(){closes.incrementAndGet();if(active.get()!=0)closedDuringCall.set(true);}
    }
    private static NativeGameRuntime runtime(boolean enabled,NativeGameRuntime.Factory factory,AtomicLong clock) {
        return new NativeGameRuntime(enabled,factory,10,s->{},clock::get);
    }

    @Test void offNeverCallsTheLoader() {
        var opens=new AtomicInteger();var fake=new Fake(false);
        var runtime=runtime(false,()->{opens.incrementAndGet();return fake;},new AtomicLong());
        assertFalse(runtime.analyze(WORLD,1,null).ok());assertEquals(0,opens.get());assertEquals(0,fake.calls.get());
        assertEquals(NativeGameRuntime.Status.DISABLED,runtime.state().status());
    }

    @Test void loadsLazilyReusesTheSessionAndKeepsDimensionsSeparate() {
        var opens=new AtomicInteger();var a=new Fake(false);var b=new Fake(false);
        var first=runtime(true,()->{opens.incrementAndGet();return a;},new AtomicLong());
        var second=runtime(true,()->{opens.incrementAndGet();return b;},new AtomicLong());
        assertEquals(0,opens.get());first.analyze(WORLD,1,null);first.analyze(WORLD,1,null);second.analyze(WORLD,1,null);
        assertEquals(2,opens.get());assertEquals(2,a.calls.get());assertEquals(1,b.calls.get());
        first.requestClose();first.closeWhenIdle();assertEquals(1,a.closes.get());assertEquals(0,b.closes.get());
        second.requestClose();second.closeWhenIdle();
    }

    @Test void closeRevokesBlockedResultsWithoutClosingAnActiveNativeCall() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var fake=new Fake(true);var opens=new AtomicInteger();
        var runtime=runtime(true,()->{opens.incrementAndGet();return fake;},new AtomicLong());
        try {
            var result=pool.submit(()->runtime.analyze(WORLD,1,null));assertTrue(fake.entered.await(1,TimeUnit.SECONDS));
            runtime.requestClose();assertTrue(runtime.closed());assertFalse(result.isDone());
            var cleanup=pool.submit(runtime::closeWhenIdle);
            assertEquals(0,fake.closes.get());fake.proceed.countDown();
            assertTrue(result.get(1,TimeUnit.SECONDS).diagnostic().contains("invalidated"));cleanup.get(1,TimeUnit.SECONDS);
            assertEquals(1,fake.closes.get());assertFalse(fake.closedDuringCall.get());
            assertEquals(NativeGameRuntime.Status.CLOSED,runtime.state().status());
            runtime.requestReset(true);runtime.analyze(WORLD,1,null);assertEquals(1,opens.get());
        } finally {fake.proceed.countDown();pool.shutdownNow();}
    }

    @Test void resetWaitsForTheOldCallAndOpensAFreshSession() throws Exception {
        var pool=Executors.newSingleThreadExecutor();var old=new Fake(true);var next=new Fake(false);var opens=new AtomicInteger();
        var runtime=runtime(true,()->opens.getAndIncrement()==0?old:next,new AtomicLong());
        try {
            var result=pool.submit(()->runtime.analyze(WORLD,1,null));assertTrue(old.entered.await(1,TimeUnit.SECONDS));
            runtime.requestReset(true);assertEquals(0,old.closes.get());old.proceed.countDown();
            assertTrue(result.get(1,TimeUnit.SECONDS).diagnostic().contains("invalidated"));
            assertEquals(1,old.closes.get());runtime.analyze(WORLD,1,null);assertEquals(2,opens.get());
            assertEquals(1,next.calls.get());assertFalse(old.closedDuringCall.get());
            runtime.requestClose();runtime.closeWhenIdle();assertEquals(1,next.closes.get());
        } finally {old.proceed.countDown();pool.shutdownNow();}
    }

    @Test void timeoutRevokesAuthorityButDoesNotPretendToKillNativeWork() throws Exception {
        var pool=Executors.newSingleThreadExecutor();var fake=new Fake(true);var clock=new AtomicLong();var opens=new AtomicInteger();
        var runtime=runtime(true,()->{opens.incrementAndGet();return fake;},clock);
        try {
            var result=pool.submit(()->runtime.analyze(WORLD,1,null));assertTrue(fake.entered.await(1,TimeUnit.SECONDS));
            clock.set(10_000_000);assertTrue(runtime.pollTimeout());assertFalse(runtime.pollTimeout());
            assertEquals(NativeGameRuntime.Status.DISABLED,runtime.state().status());assertFalse(result.isDone());
            assertEquals(0,fake.closes.get());fake.proceed.countDown();
            assertTrue(result.get(1,TimeUnit.SECONDS).diagnostic().contains("invalidated"));
            assertEquals(1,fake.closes.get());runtime.analyze(WORLD,1,null);assertEquals(1,opens.get());
            assertFalse(fake.closedDuringCall.get());
        } finally {fake.proceed.countDown();pool.shutdownNow();}
    }

    @Test void aLoadFailureStaysDisabledUntilExplicitReset() {
        var attempts=new AtomicInteger();var runtime=runtime(true,()->{attempts.incrementAndGet();throw new IllegalStateException("missing library");},new AtomicLong());
        runtime.analyze(WORLD,1,null);runtime.analyze(WORLD,1,null);assertEquals(1,attempts.get());
        assertEquals(NativeGameRuntime.Status.DISABLED,runtime.state().status());
        runtime.requestReset(true);runtime.analyze(WORLD,1,null);assertEquals(2,attempts.get());
    }

    @Test void concurrentSubmissionsNeverEnterTheSameSessionTogether() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var fake=new Fake(true);
        var runtime=runtime(true,()->fake,new AtomicLong());
        try {
            var a=pool.submit(()->runtime.analyze(WORLD,1,null));assertTrue(fake.entered.await(1,TimeUnit.SECONDS));
            var b=pool.submit(()->runtime.analyze(WORLD,1,null));fake.proceed.countDown();
            a.get(1,TimeUnit.SECONDS);b.get(1,TimeUnit.SECONDS);assertEquals(2,fake.calls.get());assertEquals(1,fake.max.get());
            runtime.requestClose();runtime.closeWhenIdle();assertEquals(1,fake.closes.get());
        } finally {fake.proceed.countDown();pool.shutdownNow();}
    }
}
