package com.blockreality.impl.net;

import com.blockreality.api.*;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GlobalVerdictPacketTest {
    private static AnalysisResult result(double dc,boolean overloaded,double factor,boolean critical) {
        return new AnalysisResult(new WorldRevision(41),true,false,"",dc,-1,"",1,0,0,factor,
                factor>0?BucklingState.COMPUTED:BucklingState.DISABLED_BY_REQUEST,List.of(),List.of(),List.of(),overloaded,critical);
    }
    private static StressResultPacket trip(AnalysisResult r) {
        var b=new FriendlyByteBuf(Unpooled.buffer());
        try { StressResultPacket.encode(StressResultPacket.of(r,"minecraft:overworld",false),b);return StressResultPacket.decode(b); }
        finally { b.release(); }
    }
    @Test void explicitFlagsSurviveWithoutChangingTheirNumbers() {
        for(boolean flag:new boolean[]{false,true})for(double dc:new double[]{1,Math.nextUp(1.)}) {
            var p=trip(result(dc,flag,1,!flag));assertTrue(p.valid(),p.invalidReason());
            assertEquals(flag,p.overCapacity());assertEquals(!flag,p.bucklingCritical());
            assertEquals(Double.doubleToRawLongBits(dc),Double.doubleToRawLongBits(p.maxDc()));assertEquals(1,p.bucklingFactor());
        }
    }
    @Test void fullDoubleRangeSurvivesWithoutNarrowingOrRepair() {
        for(double x:new double[]{Double.MIN_VALUE,Double.MIN_NORMAL,Math.nextDown(1.),Math.nextUp(1.),Double.MAX_VALUE}) {
            var p=trip(result(x,false,x,false));assertTrue(p.valid(),p.invalidReason());
            assertEquals(Double.doubleToRawLongBits(x),Double.doubleToRawLongBits(p.maxDc()));
            assertEquals(Double.doubleToRawLongBits(x),Double.doubleToRawLongBits(p.bucklingFactor()));
        }
    }
    @Test void globalDecisionDoesNotDependOnDisplayedElements() {
        var p=trip(result(.25,true,2,true));assertTrue(p.valid(),p.invalidReason());
        assertTrue(p.members().isEmpty());assertTrue(p.shells().isEmpty());assertTrue(p.overCapacity());assertTrue(p.bucklingCritical());
    }
    @Test void invalidGlobalValuesRejectTheWholePacket() {
        for(double bad:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            assertFalse(trip(result(bad,false,0,false)).valid());assertFalse(trip(result(0,false,bad,false)).valid());
        }
    }
}
