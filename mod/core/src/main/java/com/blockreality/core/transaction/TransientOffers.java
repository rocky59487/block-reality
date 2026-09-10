package com.blockreality.core.transaction;

import java.util.*;

/** Server-thread preview admission. Owners are authenticated connection objects, never client-supplied IDs. */
public final class TransientOffers<K,V> {
    public static final int MAX_OFFERS=64, MAX_BYTES=4<<20, LIFETIME_TICKS=200;
    private record Offer<V>(UUID token,int createdTick,int bytes,V value) { }
    private final IdentityHashMap<K,Offer<V>> offers=new IdentityHashMap<>();
    private int bytes;

    public boolean put(K owner,UUID token,int now,int size,V value) {
        Objects.requireNonNull(owner);Objects.requireNonNull(token);Objects.requireNonNull(value);expire(now);
        if(size<0 || size>MAX_BYTES)throw new IllegalArgumentException("Invalid offer size");
        Offer<V> previous=offers.get(owner);int oldBytes=previous==null?0:previous.bytes();
        if(previous==null && offers.size()>=MAX_OFFERS || size>MAX_BYTES-(bytes-oldBytes))return false;
        offers.put(owner,new Offer<>(token,now,size,value));bytes=bytes-oldBytes+size;return true;
    }
    public Optional<V> get(K owner,UUID token,int now) {
        expire(now);Offer<V> offer=offers.get(owner);
        return offer!=null && offer.token().equals(token)?Optional.of(offer.value()):Optional.empty();
    }
    public void remove(K owner) {Offer<V> old=offers.remove(owner);if(old!=null)bytes-=old.bytes();}
    public void clear() {offers.clear();bytes=0;}
    public int size(int now) {expire(now);return offers.size();}
    public int bytes(int now) {expire(now);return bytes;}
    private void expire(int now) {
        var it=offers.entrySet().iterator();
        while(it.hasNext()) {
            Offer<V> offer=it.next().getValue();
            // Unsigned elapsed ticks remains correct across Minecraft's signed int tick wrap.
            if(Integer.toUnsignedLong(now-offer.createdTick())>=LIFETIME_TICKS) {bytes-=offer.bytes();it.remove();}
        }
    }
}
