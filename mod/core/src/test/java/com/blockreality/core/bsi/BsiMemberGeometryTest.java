package com.blockreality.core.bsi;

import com.blockreality.api.geom.Vec3d;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BsiMemberGeometryTest {
    static byte[] fixture() {
        ByteBuffer b=ByteBuffer.allocate(328).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0,7).putDouble(32,4).putInt(160,7);
        double[] g={29999000.5,64.5,-3.5, 0,0,1, 0,1,0, -1,0,0, .2,-.2,0,0, 0,0,.1,-.1};
        for(int i=0;i<g.length;i++)b.putDouble(168+8*i,g[i]);
        return b.array();
    }
    static BsiResponse decode(byte[] b) {
        return BsiResponse.of(new BsiFrame.Decoded(0,"{\"method\":\"bsi.solve\",\"sections\":["
                +"{\"name\":\"members\",\"offset\":0,\"bytes\":160,\"count\":1},"
                +"{\"name\":\"memberGeometry\",\"offset\":160,\"bytes\":168,\"count\":1}]}",b));
    }
    @Test void handPackedGeometryKeepsSiAndSampleOrder() {
        var r=decode(fixture());var g=r.memberGeometry().get(0);
        assertEquals(7,g.id());assertEquals(new Vec3d(29999000.5,64.5,-3.5),g.origin());
        assertEquals(new Vec3d(0,0,1),g.ex());assertEquals(new Vec3d(0,1,0),g.ey());assertEquals(new Vec3d(-1,0,0),g.ez());
        assertEquals(List.of(.2,-.2,0.,0.),g.faceY());assertEquals(List.of(0.,0.,.1,-.1),g.faceZ());
        assertEquals(new Vec3d(29999000.5,64.7,-1.5),g.samplePosition(new Vec3d(29999000.5,64.5,-1.5),0));
        assertEquals(29999000.4,g.samplePosition(g.origin(),2).x(),1e-9);
    }
    @Test void snapshotsAndSourceListsAreImmutable() {
        byte[] bytes=fixture();var r=decode(bytes);bytes[160]=99;
        var g=r.memberGeometry().get(0);assertEquals(7,g.id());
        assertThrows(UnsupportedOperationException.class,()->g.faceY().set(0,9.));
        assertThrows(UnsupportedOperationException.class,()->r.memberGeometry().clear());
        var y=new ArrayList<>(g.faceY());var copy=new BsiMemberGeometry(7,g.origin(),g.ex(),g.ey(),g.ez(),y,g.faceZ());
        y.set(0,9.);assertEquals(.2,copy.faceY().get(0));
    }
    @Test void idsAndReservedBitsMustMatch() {
        byte[] bytes=fixture();ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(160,8);assertThrows(IllegalArgumentException.class,()->decode(bytes),"member id pairing");
        b.putInt(160,7).putInt(164,1);assertThrows(IllegalArgumentException.class,()->decode(bytes),"reserved");
    }
    @Test void invalidFramesAreRejected() {
        byte[] bytes=fixture();ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.putDouble(208,2);assertThrows(IllegalArgumentException.class,()->decode(bytes),"non-unit frame");
        b.putDouble(208,1).putDouble(240,1);assertThrows(IllegalArgumentException.class,()->decode(bytes),"left handed frame");
    }
    @Test void everyNonfiniteAndMisorderedSampleIsRejected() {
        for(int k=0;k<20;k++)for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            byte[] bytes=fixture();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putDouble(168+8*k,bad);
            assertThrows(IllegalArgumentException.class,()->decode(bytes),"nonfinite field "+k);
        }
        for(int offset:new int[]{264,272,280,288,296,304,312,320}) {
            byte[] bytes=fixture();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putDouble(offset,9);
            assertThrows(IllegalArgumentException.class,()->decode(bytes),"face order "+offset);
        }
    }
    @Test void missingAndEmptyAreDistinctAndTruncationsFail() {
        for(int n=0;n<328;n++) {byte[] bytes=java.util.Arrays.copyOf(fixture(),n);assertThrows(IllegalArgumentException.class,()->decode(bytes));}
        var empty=BsiResponse.of(new BsiFrame.Decoded(0,"{\"sections\":[{\"name\":\"members\",\"offset\":0,\"bytes\":0,\"count\":0},{\"name\":\"memberGeometry\",\"offset\":0,\"bytes\":0,\"count\":0}]}",new byte[0]));
        assertTrue(empty.memberGeometry().isEmpty());assertTrue(empty.sections().containsKey("memberGeometry"));
        var absent=BsiResponse.of(new BsiFrame.Decoded(0,"{}",new byte[0]));assertTrue(absent.memberGeometry().isEmpty());assertFalse(absent.sections().containsKey("memberGeometry"));
        assertThrows(IllegalArgumentException.class,()->BsiResponse.of(new BsiFrame.Decoded(0,"{\"sections\":[{\"name\":\"memberGeometry\",\"offset\":0,\"bytes\":0,\"count\":0}]}",new byte[0])));
    }
}
