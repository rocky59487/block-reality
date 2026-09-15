package com.blockreality.core.transaction;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TransientOffersTest {
    @Test void connectionIdentityReplacementAndExactExpiry() {
        var book=new TransientOffers<Object,String>();Object owner=new String("same actor"),replacement=new String("same actor");
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        assertTrue(book.put(owner,first,10,64,"first"));assertTrue(book.get(replacement,first,10).isEmpty());
        assertTrue(book.put(owner,second,20,96,"second"));assertTrue(book.get(owner,first,20).isEmpty());
        assertEquals("second",book.get(owner,second,219).orElseThrow());assertEquals(96,book.bytes(219));
        assertTrue(book.get(owner,second,220).isEmpty());assertEquals(0,book.bytes(220));
    }
    @Test void capacityRefusesWithoutEvictingOthersAndAccountsReplacement() {
        var book=new TransientOffers<Object,String>();var owners=new ArrayList<Object>();var tokens=new ArrayList<UUID>();
        for(int i=0;i<64;i++){owners.add(new Object());tokens.add(UUID.randomUUID());assertTrue(book.put(owners.get(i),tokens.get(i),0,65536,"keep"));}
        assertFalse(book.put(new Object(),UUID.randomUUID(),1,1,"extra"));
        assertFalse(book.put(owners.get(0),UUID.randomUUID(),1,65537,"larger"));
        for(int i=0;i<64;i++)assertEquals("keep",book.get(owners.get(i),tokens.get(i),1).orElseThrow());
        book.remove(owners.get(0));assertEquals(63,book.size(1));assertEquals(63*65536,book.bytes(1));
        assertTrue(book.put(new Object(),UUID.randomUUID(),1,65536,"replacement"));book.clear();assertEquals(0,book.bytes(1));
    }
    @Test void expirationSurvivesTickWrapAndInvalidSizesRefuse() {
        var book=new TransientOffers<Object,String>();Object owner=new Object();UUID token=UUID.randomUUID();int start=Integer.MAX_VALUE-5;
        assertTrue(book.put(owner,token,start,1,"live"));assertTrue(book.get(owner,token,start+199).isPresent());
        assertTrue(book.get(owner,token,start+200).isEmpty());
        assertThrows(IllegalArgumentException.class,()->book.put(owner,token,0,-1,"bad"));
        assertThrows(IllegalArgumentException.class,()->book.put(owner,token,0,TransientOffers.MAX_BYTES+1,"bad"));
    }
}
