package com.blockreality.impl.net;

import com.blockreality.api.*;
import com.blockreality.api.geom.*;
import com.blockreality.api.render.StressPalette;
import com.blockreality.core.render.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemberPacketCodecTest {
    private static MemberSnapshot member(boolean verdict, boolean display) {
        Vec3d origin=new Vec3d(29_999_000_500.,64500,-3500),ax=new Vec3d(1,0,0),ay=new Vec3d(0,1,0),az=new Vec3d(0,0,1);
        List<StressStation> samples=new ArrayList<>();
        for(int i=0;i<4;i++) {
            double x=new double[]{0,1000,1000,2000}[i];
            samples.add(new StressStation(x,origin.plus(ax.scaled(x)),List.of(
                    new Fibre("TOP_Y",ay,200,2+10*i),new Fibre("BOT_Y",ay.scaled(-1),200,-4-10*i),
                    new Fibre("PLUS_Z",az,100,6+10*i),new Fibre("MINUS_Z",az.scaled(-1),100,-8-10*i)),
                    6+10*i,8+10*i,9+i,i==1?Optional.empty():Optional.of(50.),Optional.empty()));
        }
        var field=new BeamDisplayField(origin,ax,ay,az,2000,200,100,samples);
        var blocks=new ArrayList<BlockKey>();for(int k=0;k<300;k++)blocks.add(new BlockKey(29_999_000+k,64,-3));
        return new MemberSnapshot(4,"steel","rect",2000,1,GoverningFibre.TENSION,2,
                new EndForces(-7,8,9,10000,11000,12000),EndForces.ZERO,blocks,samples,Optional.empty(),
                display?Optional.of(field):Optional.empty(),verdict,Optional.of(1000.));
    }
    private static FriendlyByteBuf bytes(MemberSnapshot m) {
        var r=new AnalysisResult(new WorldRevision(19),true,false,"",1,4,"member",1,0,0,0,
                BucklingState.DISABLED_BY_REQUEST,List.of(m),List.of(),List.of());
        var b=new FriendlyByteBuf(Unpooled.buffer());StressResultPacket.encode(StressResultPacket.of(r,"minecraft:overworld",false),b);return b;
    }
    @Test void fullPacketPreservesEverySampleCellFlagAndGoverningIndex() {
        for(boolean flag:new boolean[]{false,true}) {
            var source=member(flag,true);var b=bytes(source);
            try {
                var packet=StressResultPacket.decode(b);assertTrue(packet.valid(),packet.invalidReason());var m=packet.members().get(0);
                assertEquals(1,m.dc());assertEquals(flag,m.overloaded());assertEquals(2,m.governingStation());
                assertEquals(source.governingPositionMm(),m.governingPositionMm());assertEquals(source.blocks(),m.blocks());
                assertEquals(source.stations(),m.stations());assertEquals(source.endI(),m.endI());assertEquals(source.endJ(),m.endJ());
                assertTrue(m.field().isEmpty());var f=m.display().orElseThrow();
                assertEquals(source.display().orElseThrow().originMm(),f.originMm());
                assertEquals(12,f.faceSigmaMpa(1000,0,BeamDisplayField.Side.FIRST));
                assertEquals(22,f.faceSigmaMpa(1000,0,BeamDisplayField.Side.LAST));
                assertEquals(8,StressRibbonBuilder.build(m,StressPalette.SIGNED_DEFAULT,38).bands().size());
                assertTrue(SectionDiagram.sampled(m.stations().get(1)).orElseThrow().neutralFraction().isEmpty());
            } finally { b.release(); }
        }
    }
    @Test void absentDisplayDoesNotEraseSamplesOrDiagnostics() {
        var source=member(true,false);var b=bytes(source);
        try {
            var p=StressResultPacket.decode(b);assertTrue(p.valid(),p.invalidReason());var m=p.members().get(0);
            assertTrue(m.display().isEmpty());assertEquals(source.stations(),m.stations());assertEquals(source.endI(),m.endI());
        } finally { b.release(); }
    }
    @Test void allSampledPacketTruncationsAreRejected() {
        var full=bytes(member(true,true));
        try {
            for(int n=0;n<full.readableBytes();n++) {
                var b=new FriendlyByteBuf(full.copy(0,n));
                try { assertFalse(StressResultPacket.decode(b).valid(),"cut "+n); } finally { b.release(); }
            }
        } finally { full.release(); }
    }
    @Test void nonFiniteMetadataAndSamplesAreRejected() {
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            // Independent channel-7 offsets: id varint (4 fits one byte), then length f64 and dc f64.
            for(int offset:new int[]{1,9}) {
                var b=new FriendlyByteBuf(Unpooled.buffer());MemberPacketCodec.write(b,member(true,true),false);
                try { b.setDouble(offset,bad);assertThrows(IllegalArgumentException.class,()->MemberPacketCodec.read(b)); }
                finally { b.release(); }
            }
            var b=new FriendlyByteBuf(Unpooled.buffer());MemberPacketCodec.write(b,member(true,true),false);
            try {
                // Final sample: tau f64, present naY byte + f64, absent naZ byte.
                b.setDouble(b.writerIndex()-18,bad);assertThrows(IllegalArgumentException.class,()->MemberPacketCodec.read(b));
            } finally { b.release(); }
        }
    }
    @Test void oversizedCollectionsRejectInsteadOfDroppingTailSamples() {
        var m=member(true,false);
        var b=new FriendlyByteBuf(Unpooled.buffer());
        try {
            var oversized=new MemberSnapshot(m.id(),m.material(),m.section(),m.lengthMm(),m.dc(),m.governingFibre(),-1,m.endI(),m.endJ(),
                    Collections.nCopies(65537,new BlockKey(0,0,0)),m.stations(),Optional.empty(),Optional.empty(),true,Optional.empty());
            var tooManyBlocks=oversized;
            assertThrows(IllegalArgumentException.class,()->MemberPacketCodec.write(b,tooManyBlocks,false));
            b.clear();
            oversized=new MemberSnapshot(m.id(),m.material(),m.section(),m.lengthMm(),m.dc(),m.governingFibre(),-1,m.endI(),m.endJ(),
                    List.of(),Collections.nCopies(65537,m.stations().get(0)),Optional.empty(),Optional.empty(),true,Optional.empty());
            var tooManyStations=oversized;
            assertThrows(IllegalArgumentException.class,()->MemberPacketCodec.write(b,tooManyStations,false));
        } finally { b.release(); }
    }
}
