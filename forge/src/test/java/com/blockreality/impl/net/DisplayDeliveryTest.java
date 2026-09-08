package com.blockreality.impl.net;

import com.blockreality.api.*;
import com.blockreality.api.geom.BlockKey;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class DisplayDeliveryTest {
    private static FriendlyByteBuf bytes(StressResultPacket p) {
        var b=new FriendlyByteBuf(Unpooled.buffer()); StressResultPacket.encode(p,b); return b;
    }
    private static StressResultPacket trip(AnalysisResult r) {
        var b=bytes(StressResultPacket.of(r,"minecraft:overworld",false));
        try { var p=StressResultPacket.decode(b); assertTrue(p.valid(),p.invalidReason()); return p; }
        finally { b.release(); }
    }
    private static DisplayDelivery prepare(AnalysisResult r, int bytes, int blocks, int stations) {
        return DisplayDelivery.prepare(r,Set.of(),Set.of(),new DisplayDelivery.Limits(bytes,blocks,stations));
    }
    @Test void wholeElementsFitExactBytesAndOneLessOmitsThem() {
        var m=DisplayDeliveryFixtures.member(0,4,32);var r=DisplayDeliveryFixtures.result(List.of(m),List.of(),0,"member");
        var wire=new FriendlyByteBuf(Unpooled.buffer());
        try {
            MemberPacketCodec.write(wire,m,false);int n=wire.readableBytes();
            var exact=prepare(r,n,32,4);assertEquals(List.of(m),exact.members());assertEquals(n,exact.payloadBytes());
            var shortByOne=prepare(r,n-1,32,4);assertTrue(shortByOne.members().isEmpty());
            assertTrue(exact.scratchPeak()<=n);assertTrue(shortByOne.scratchPeak()<=n-1);
        } finally { wire.release(); }
    }
    @Test void countsAreSharedAcrossElementsAndFamilies() {
        var m=DisplayDeliveryFixtures.member(0,4,32);var s=DisplayDeliveryFixtures.shell(0,4);
        var r=DisplayDeliveryFixtures.result(List.of(m),List.of(s),0,"member");
        assertEquals(1,prepare(r,10000,36,4).shells().size());
        assertTrue(prepare(r,10000,35,4).shells().isEmpty());
        var r2=DisplayDeliveryFixtures.result(List.of(m,DisplayDeliveryFixtures.member(1,4,32)),List.of(),0,"member");
        assertEquals(2,prepare(r2,10000,64,8).members().size());
        assertEquals(1,prepare(r2,10000,64,7).members().size());
    }
    @Test void controllingElementReservesBudgetBeforeBothFamilies() {
        var source=DisplayDeliveryFixtures.fixture("shell-heavy");var out=trip(source);
        var expected=IntStream.range(0,63).boxed().collect(java.util.stream.Collectors.toCollection(ArrayList::new));expected.add(511);
        assertEquals(expected,out.shells().stream().map(ShellSnapshot::id).toList());
        assertEquals(16384,out.shells().stream().mapToInt(s->s.blocks().size()).sum());
        assertFalse(out.governingOmitted());assertEquals(511,out.governing());assertEquals("shell",out.governingKind());
        var m=DisplayDeliveryFixtures.member(0,0,1);var s=DisplayDeliveryFixtures.shell(9,1);
        var r=DisplayDeliveryFixtures.result(List.of(m),List.of(s),9,"shell");
        var selected=prepare(r,10000,1,0);assertTrue(selected.members().isEmpty());assertEquals(List.of(s),selected.shells());
    }
    @Test void oversizedControllerIsExplicitAndNeverChangesSummary() {
        var r=DisplayDeliveryFixtures.fixture("oversize");var p=trip(r);
        assertEquals(4,p.members().size());assertTrue(p.governingOmitted());assertEquals(999,p.governing());
        assertEquals(r.maxDc(),p.maxDc());assertEquals(r.overCapacity(),p.overCapacity());assertEquals(r.revision().value(),p.revision());
        var huge=DisplayDeliveryFixtures.member(9,4096,65536);
        var mixed=new AnalysisResult(new WorldRevision(31),true,true,"one mechanism",1,9,"member",2,1,0,0,
                BucklingState.DISABLED_BY_REQUEST,List.of(huge),List.of(),List.of(),true,false);
        var empty=trip(mixed);assertTrue(empty.members().isEmpty());assertTrue(empty.hasSummary());assertFalse(empty.allMechanism());
        assertTrue(empty.governingOmitted());assertTrue(empty.overCapacity());assertEquals(1,empty.singularIslands());
    }
    @Test void selectedRecordsAreCompleteAndThreeEncodesAreIdentical() {
        for(String name:List.of("small","dense","oversize","shell-heavy")) {
            var r=DisplayDeliveryFixtures.fixture(name);var p=StressResultPacket.of(r,"minecraft:overworld",false);byte[] previous=null;
            for(int run=0;run<3;run++) {
                var b=bytes(p);
                try {
                    assertTrue(b.readableBytes()<=262144);byte[] raw=new byte[b.readableBytes()];b.getBytes(0,raw);
                    if(previous!=null)assertArrayEquals(previous,raw);previous=raw;
                    var q=StressResultPacket.decode(b);assertTrue(q.valid(),q.invalidReason());
                    for(var m:q.members()) {
                        var original=r.members().stream().filter(v->v.id()==m.id()).findFirst().orElseThrow();
                        assertEquals(original.blocks(),m.blocks());assertEquals(original.stations(),m.stations());
                        assertEquals(original.governingStation(),m.governingStation());assertEquals(original.overloaded(),m.overloaded());
                        assertEquals(original.display().orElseThrow().breaksMm(),m.display().orElseThrow().breaksMm());
                    }
                    for(var s:q.shells()) {
                        var original=r.shells().stream().filter(v->v.id()==s.id()).findFirst().orElseThrow();
                        assertEquals(original.blocks(),s.blocks());assertEquals(original.display().orElseThrow().top(),s.display().orElseThrow().top());
                        assertEquals(original.display().orElseThrow().bottom(),s.display().orElseThrow().bottom());assertEquals(original.overloaded(),s.overloaded());
                    }
                    assertEquals(r.overCapacity(),q.overCapacity());assertEquals(r.bucklingCritical(),q.bucklingCritical());
                } finally { b.release(); }
            }
        }
    }
    @Test void oversizedFrameIsRejectedBeforeParsing() {
        var b=new FriendlyByteBuf(Unpooled.buffer());b.writeZero(262145);
        try { var p=StressResultPacket.decode(b);assertFalse(p.valid());assertEquals("display frame budget exceeded",p.invalidReason()); }
        finally { b.release(); }
    }
    /** Direct channel10 layout bypasses sender policy; the receiver must defend itself. */
    private static FriendlyByteBuf raw(List<MemberSnapshot> m,List<ShellSnapshot> s,int totalM,int gov,boolean omitted) {
        var b=new FriendlyByteBuf(Unpooled.buffer());b.writeVarLong(19);b.writeUtf("minecraft:overworld");b.writeBoolean(false);
        b.writeDouble(1);b.writeBoolean(true);b.writeVarInt(1);b.writeVarInt(0);b.writeDouble(0);b.writeBoolean(false);
        b.writeByte(BucklingState.DISABLED_BY_REQUEST.ordinal());for(var reason:UnassignedReason.values())b.writeVarInt(0);
        b.writeVarInt(0);b.writeVarInt(totalM);b.writeVarInt(s.size());b.writeVarInt(m.size());
        for(var member:m)MemberPacketCodec.write(b,member,false);b.writeVarInt(s.size());
        for(var shell:s)ShellPacketCodec.write(b,shell,false);
        b.writeByte(gov<0?0:1);b.writeVarInt(gov);b.writeBoolean(omitted);return b;
    }
    private static void rejected(FriendlyByteBuf b,String reason) {
        try { assertTrue(b.readableBytes()<=262144);var p=StressResultPacket.decode(b);assertFalse(p.valid());assertTrue(p.invalidReason().contains(reason),p.invalidReason()); }
        finally { b.release(); }
    }
    @Test void receivingChargesAggregateBlocksBeforeAllocation() {
        var m=DisplayDeliveryFixtures.member(0,0,8192);
        var exact=raw(List.of(m),List.of(DisplayDeliveryFixtures.shell(0,8192)),1,0,false);
        try { assertTrue(StressResultPacket.decode(exact).valid()); } finally { exact.release(); }
        rejected(raw(List.of(m),List.of(DisplayDeliveryFixtures.shell(0,8193)),1,0,false),"block budget");
    }
    @Test void receivingChargesAggregateStationsBeforeAllocation() {
        var m=DisplayDeliveryFixtures.member(0,1024,0);
        var exact=raw(List.of(m,DisplayDeliveryFixtures.member(1,1024,0)),List.of(),2,0,false);
        try { assertTrue(StressResultPacket.decode(exact).valid()); } finally { exact.release(); }
        rejected(raw(List.of(m,DisplayDeliveryFixtures.member(1,1025,0)),List.of(),2,0,false),"station budget");
    }
    @Test void countIdentityAndOmissionContradictionsAreRejected() {
        var m=DisplayDeliveryFixtures.member(0,4,1);
        rejected(raw(List.of(m),List.of(),0,0,false),"count contradiction");
        rejected(raw(List.of(m,m),List.of(),2,0,false),"duplicate member");
        rejected(raw(List.of(m),List.of(),1,9,false),"governing delivery contradiction");
        rejected(raw(List.of(m),List.of(),1,0,true),"governing delivery contradiction");
    }
}
