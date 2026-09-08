package com.blockreality.core.bsi;

import com.blockreality.api.*;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BsiStationIdentityTest {
    private static final double[] S={0,.5,.5,.50000001,1};
    private static String section(String name,int offset,int size,int n) {return "{\"name\":\""+name+"\",\"offset\":"+offset+",\"bytes\":"+size+",\"count\":"+n+"}";}
    private static int identities(boolean f32) {return 172+5*(f32?44:88);}
    private static String header(boolean f32) {
        int n=identities(f32);return "{\"kind\":\"response\",\"method\":\"bsi.solve\",\"status\":\"ok\",\"revision\":17,\"sections\":["
            +section("members",0,160,1)+","+section("memberBlocks",160,12,1)+","+section(f32?"stations:f32":"stations",172,n-172,5)+","
            +section("stationIdentity",n,80,5)+","+section("memberGeometry",n+80,168,1)+"]}";
    }
    private static byte[] fixture(boolean f32) {
        int io=identities(f32),go=io+80;var b=ByteBuffer.allocate(go+168).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0,7).putInt(12,1).putInt(20,5).putInt(24,5).putInt(28,6).putDouble(32,2).putDouble(136,1).putDouble(144,.5).put(154,(byte)1);
        for(int k=0;k<5;k++) {
            double[] a={S[k],2.5+2*S[k],64.5,-3.5,(10+k)*1e6,(-20-k)*1e6,(30+k)*1e6,(-40-k)*1e6,3e6,Double.NaN,Double.NaN};
            for(int j=0;j<11;j++){int at=172+k*(f32?44:88)+j*(f32?4:8);if(f32)b.putFloat(at,(float)a[j]);else b.putDouble(at,a[j]);}
            b.putDouble(io+16*k,S[k]).put(io+16*k+8,(byte)(k==2?1:-1)).put(io+16*k+9,(byte)(k==2?1:0));
        }
        b.putInt(go,7);double[] g={2.5,64.5,-3.5,1,0,0,0,1,0,0,0,1,.2,-.2,0,0,0,0,.1,-.1};
        for(int k=0;k<20;k++)b.putDouble(go+8+8*k,g[k]);return b.array();
    }
    private static BsiResponse reply(boolean f32,byte[] b) {return BsiResponse.of(new BsiFrame.Decoded(0,header(f32),b));}
    private static MemberSnapshot display(boolean f32,byte[] b) {return BsiBeamDisplay.decode(reply(f32,b),17,Map.of(5,"steel"),Map.of(6,"rect")).get(0);}
    @Test void exactIdentityAndSourceGoverningReachTheSharedSamples() {
        for(boolean f32:new boolean[]{false,true}) {
            var response=reply(f32,fixture(f32));var m=display(f32,fixture(f32));assertEquals(5,m.stations().size());assertEquals(2,m.governingStation());
            assertTrue(m.overloaded());assertEquals(1,m.dc());assertEquals(List.of(1000.),m.display().orElseThrow().breaksMm());
            for(int k=0;k<5;k++) {
                var s=m.stations().get(k);var id=s.identity().orElseThrow();assertEquals(S[k],id.s());assertEquals(k==2?1:-1,id.side());
                assertEquals(S[k]*2000,s.xMm());assertEquals(response.stations()[k][4]*1e-6,s.fibres().get(0).sigmaMpa());assertTrue(s.naOffsetYMm().isEmpty());
            }
            assertNotEquals(m.stations().get(2).xMm(),m.stations().get(3).xMm());
            if(f32)assertEquals(response.stations()[2][0],response.stations()[3][0]);
            var b=fixture(f32);b[identities(f32)+2*16+9]=0;b[identities(f32)+16+9]=1;
            assertEquals(1,display(f32,b).governingStation());
        }
    }
    @Test void missingGoverningAndRequestedZeroAreExplicit() {
        for(boolean f32:new boolean[]{false,true}) {var b=fixture(f32);b[identities(f32)+2*16+9]=0;assertEquals(-1,display(f32,b).governingStation());}
        String h="{\"kind\":\"response\",\"method\":\"bsi.solve\",\"status\":\"ok\",\"revision\":17,\"sections\":[";
        var names=List.of("members","memberBlocks","stations","stationIdentity","memberGeometry");
        h+=String.join(",",names.stream().map(n->section(n,0,0,0)).toList())+"]}";
        var r=BsiResponse.of(new BsiFrame.Decoded(0,h,new byte[0]));assertTrue(r.stationIdentity().isEmpty());assertTrue(BsiBeamDisplay.decode(r,17,Map.of(),Map.of()).isEmpty());
    }
    @Test void invalidNumbersSidesFlagsAndReservedAreRejected() {
        for(boolean f32:new boolean[]{false,true})for(int kind=0;kind<9;kind++) {
            byte[] bytes=fixture(f32);var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);int o=identities(f32);
            switch(kind){case 0->b.putDouble(o,Double.NaN);case 1->b.putDouble(o,Double.POSITIVE_INFINITY);case 2->b.putDouble(o,-1);case 3->b.putDouble(o,1.1);case 4->b.put(o+8,(byte)0);case 5->b.put(o+8,(byte)2);case 6->b.put(o+9,(byte)2);case 7->b.putShort(o+10,(short)1);case 8->b.putInt(o+12,1);}
            assertThrows(IllegalArgumentException.class,()->reply(f32,bytes));
        }
    }
    @Test void pairingOrderAndGoverningMustBeConsistent() {
        for(boolean f32:new boolean[]{false,true})for(int kind=0;kind<6;kind++) {
            byte[] bytes=fixture(f32);var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);int o=identities(f32);
            switch(kind){case 0->b.putDouble(o,.1);case 1->b.put(o+16+8,(byte)1);case 2->b.put(o+16+9,(byte)1);case 3->b.putDouble(144,.6);case 4->b.putInt(16,1);case 5->b.putInt(20,4);}
            assertThrows(IllegalArgumentException.class,()->reply(f32,bytes));
        }
        String h=header(false).replace("\"name\":\"members\"","\"name\":\"not-members\"");
        assertThrows(IllegalArgumentException.class,()->BsiResponse.of(new BsiFrame.Decoded(0,h,fixture(false))));
    }
    @Test void immutableIdentityAndAllTruncations() {
        var ids=reply(false,fixture(false)).stationIdentity();assertThrows(UnsupportedOperationException.class,()->ids.clear());
        for(boolean f32:new boolean[]{false,true}) {byte[] b=fixture(f32);for(int n=0;n<b.length;n++){byte[] cut=Arrays.copyOf(b,n);assertThrows(IllegalArgumentException.class,()->reply(f32,cut));}}
    }
}
