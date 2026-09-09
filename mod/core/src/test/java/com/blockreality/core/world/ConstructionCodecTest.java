package com.blockreality.core.world;

import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.blockreality.core.world.ConstructionLedgerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionCodecTest {
    @Test void encoderPreservesCatalogueLineageAndModifiedUtfGolden() throws Exception {
        byte[] expected;
        try (var in=getClass().getResourceAsStream("/construction-codec-v1.bin")) {
            assertNotNull(in,"pre-optimization codec golden must exist");expected=in.readAllBytes();
        }
        var fixture=ConstructionCodecGolden.fixture();byte[] encoded=fixture.encode();
        assertArrayEquals(expected,encoded);
        var restored=ConstructionLedger.decode(encoded);
        assertArrayEquals(expected,restored.encode());assertEquals(fixture.failure(),restored.failure());
        assertEquals(fixture.graph().records(),restored.graph().records());
        assertEquals(fixture.work().destroyed(),restored.work().destroyed());
        encoded[0]^=1;assertArrayEquals(expected,fixture.encode(),"returned arrays are caller-owned");
    }
    @Test void corruptionTruncationAndTrailingDataAlwaysRefuse() {
        byte[] valid = beam().encode();
        for (int i=0;i<valid.length;i++) {
            byte[] corrupt = valid.clone(); corrupt[i] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> ConstructionLedger.decode(corrupt));
            byte[] cut = Arrays.copyOf(valid,i);
            assertThrows(IllegalArgumentException.class, () -> ConstructionLedger.decode(cut));
        }
        assertThrows(IllegalArgumentException.class, () -> ConstructionLedger.decode(Arrays.copyOf(valid,valid.length+1)));
    }
    @Test void resignedUnknownSchemaAndInvalidEpochCounterAndCountsStillRefuse() throws Exception {
        byte[] valid = beam().encode();
        for (int offset : new int[]{4,24,32,40,50}) {
            byte[] bad = valid.clone();
            if (offset==4) ByteBuffer.wrap(bad).putInt(offset,2);
            else if(offset==50) ByteBuffer.wrap(bad).putInt(offset,WorldCellIndex.MAX_CELLS+1);
            else ByteBuffer.wrap(bad).putLong(offset,offset==32 ? Long.MAX_VALUE : -1);
            resign(bad); assertThrows(IllegalArgumentException.class, () -> ConstructionLedger.decode(bad));
        }
    }
    @Test void duplicateIdsOwnershipAndOrphanParentsRefuseEvenWithValidChecksum() throws Exception {
        var l=beam(); l.remove(p(2)); settle(l); byte[] valid=l.encode(); var offsets=offsets(valid);
        for (String field : List.of("id","parent","cell")) {
            byte[] bad=valid.clone(); var b=ByteBuffer.wrap(bad);
            if(field.equals("id")) b.putLong(offsets.get(2).id(),2);
            if(field.equals("parent")) b.putLong(offsets.get(1).parent(),999);
            if(field.equals("cell")) System.arraycopy(valid,offsets.get(1).cell(),bad,offsets.get(2).cell(),12);
            resign(bad); assertThrows(IllegalArgumentException.class, () -> ConstructionLedger.decode(bad),field);
        }
    }
    private record Offsets(int id,int parent,int cell) { }
    private static List<Offsets> offsets(byte[] bytes) throws Exception {
        var in=new DataInputStream(new ByteArrayInputStream(bytes));in.skipNBytes(48);in.readUTF();
        int n=in.readInt();for(int i=0;i<n;i++){in.skipNBytes(12);declaration(in);}
        n=in.readInt();in.skipNBytes(12L*n);n=in.readInt();var result=new ArrayList<Offsets>();
        for(int i=0;i<n;i++){
            int id=bytes.length-in.available();in.readLong();declaration(in);int parents=in.readInt();
            int parent=bytes.length-in.available();in.skipNBytes(8L*parents);int cells=in.readInt();
            int cell=bytes.length-in.available();in.skipNBytes(12L*cells);result.add(new Offsets(id,parent,cell));
        }
        return result;
    }
    private static void declaration(DataInputStream in)throws Exception{in.readUTF();in.readUTF();in.readInt();}
    private static void resign(byte[] bytes)throws Exception{
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes,bytes.length-32));
        System.arraycopy(hash,0,bytes,bytes.length-32,32);
    }
}
