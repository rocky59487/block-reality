package com.blockreality.core.transaction;

import java.util.IdentityHashMap;
import java.util.Objects;

/** Bounds work before crossing a thread handoff. Leases survive reset without releasing newer work. */
public final class PendingWork<K> {
    private final int perOwner, capacity;
    private final IdentityHashMap<K,Integer> counts = new IdentityHashMap<>();
    private long epoch;
    private int size;
    public PendingWork(int perOwner,int capacity) {
        if (perOwner<1 || capacity<perOwner) throw new IllegalArgumentException("Invalid pending-work capacity");
        this.perOwner=perOwner;this.capacity=capacity;
    }
    public synchronized Lease acquire(K owner) {
        Objects.requireNonNull(owner);
        int count=counts.getOrDefault(owner,0);
        if(count==perOwner || size==capacity)return null;
        counts.put(owner,count+1);size++;return new Lease(owner,epoch);
    }
    public synchronized void clear() {counts.clear();size=0;epoch++;}
    public synchronized int size() {return size;}
    public final class Lease implements AutoCloseable {
        private final K owner;
        private final long born;
        private boolean closed;
        private Lease(K owner,long born) {this.owner=owner;this.born=born;}
        @Override public void close() {
            synchronized(PendingWork.this) {
                if(closed)return;closed=true;
                if(born!=epoch)return;
                int remaining=counts.get(owner)-1;
                if(remaining==0)counts.remove(owner);else counts.put(owner,remaining);
                size--;
            }
        }
    }
}
