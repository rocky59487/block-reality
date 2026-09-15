package com.blockreality.core.transaction;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PendingWorkTest {
    @Test void identityLimitsTotalAndIdempotentRelease() {
        PendingWork<Object> queue=new PendingWork<>(2,3);
        Object a=new String("same"),b=new String("same");
        var first=queue.acquire(a);var second=queue.acquire(a);var third=queue.acquire(b);
        assertNotNull(first);assertNotNull(second);assertNotNull(third);
        assertNull(queue.acquire(a));assertNull(queue.acquire(new Object()));
        first.close();first.close();assertEquals(2,queue.size());
        var replacement=queue.acquire(a);assertNotNull(replacement);assertNull(queue.acquire(a));
        second.close();third.close();replacement.close();assertEquals(0,queue.size());
    }
    @Test void oldLeasesCannotReleaseAfterServerReset() {
        PendingWork<Object> queue=new PendingWork<>(1,1);Object owner=new Object();
        var old=queue.acquire(owner);queue.clear();var current=queue.acquire(owner);
        old.close();assertEquals(1,queue.size());assertNull(queue.acquire(owner));
        current.close();assertEquals(0,queue.size());
    }
    @Test void concurrentNetworkThreadsCannotOverAdmit() throws Exception {
        PendingWork<Object> queue=new PendingWork<>(4,128);Object connection=new Object();
        var pool=Executors.newFixedThreadPool(8);
        try {
            List<Callable<PendingWork<Object>.Lease>> tasks=new ArrayList<>();
            for(int i=0;i<256;i++)tasks.add(()->queue.acquire(connection));
            List<PendingWork<Object>.Lease> leases=new ArrayList<>();
            for(var future:pool.invokeAll(tasks)){var lease=future.get();if(lease!=null)leases.add(lease);}
            assertEquals(4,leases.size());assertEquals(4,queue.size());leases.forEach(PendingWork.Lease::close);
            assertEquals(0,queue.size());
        } finally {pool.shutdownNow();}
    }
}
