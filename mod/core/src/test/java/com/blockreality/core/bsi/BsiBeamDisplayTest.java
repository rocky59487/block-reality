package com.blockreality.core.bsi;

import com.blockreality.api.*;
import com.blockreality.api.geom.Vec3d;
import com.blockreality.api.render.StressPalette;
import com.blockreality.core.render.*;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BsiBeamDisplayTest {
    private static String section(String name, int offset, int bytes, int count) {
        return "{\"name\":\""+name+"\",\"offset\":"+offset+",\"bytes\":"+bytes+",\"count\":"+count+"}";
    }
    private static String header(int n) {
        return "{\"kind\":\"response\",\"method\":\"bsi.solve\",\"status\":\"ok\",\"revision\":17,\"sections\":["
                +section("members",0,160*n,n)+","+section("memberBlocks",160*n,12*n,n)+","
                +section("stations",172*n,352*n,4*n)+","+section("memberGeometry",524*n,168*n,n)+"]}";
    }
    private static byte[] fixture() {
        var b=ByteBuffer.allocate(692).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0,7).putInt(12,1).putInt(20,4).putInt(24,5).putInt(28,6).putDouble(32,2);
        for(int k=0;k<12;k++) b.putDouble(40+8*k,7+k);
        b.putDouble(136,1).putDouble(144,.5).put(152,(byte)5).put(153,(byte)2).put(154,(byte)1);
        b.putInt(160,2).putInt(164,64).putInt(168,-3);
        double[] positions={0,.5,.5,1};
        for(int k=0;k<4;k++) {
            double[] s={positions[k],2.5+2*positions[k],64.5,-3.5,
                    (2+10*k)*1e6,(-4-10*k)*1e6,(6+10*k)*1e6,(-8-10*k)*1e6,
                    (9+k)*1e6,k==1?Double.NaN:.05,Double.NaN};
            for(int j=0;j<11;j++) b.putDouble(172+88*k+8*j,s[j]);
        }
        b.putInt(524,7);
        double[] g={2.5,64.5,-3.5, 1,0,0, 0,1,0, 0,0,1, .2,-.2,0,0, 0,0,.1,-.1};
        for(int k=0;k<20;k++)b.putDouble(532+8*k,g[k]);
        return b.array();
    }
    private static BsiResponse reply(String h, byte[] b) { return BsiResponse.of(new BsiFrame.Decoded(0,h,b)); }
    private static MemberSnapshot decode(byte[] b) { return BsiBeamDisplay.decode(reply(header(1),b),17,Map.of(5,"steel"),Map.of(6,"rect")).get(0); }
    private static MemberSnapshot sample() { return decode(fixture()); }

    @Test void samplesUnitsAndDiagnosticConventionComeFromWire() {
        var m=sample();var f=m.display().orElseThrow();
        assertTrue(com.blockreality.testfixtures.NativeSnapshotChecks.hasNoLegacyField(m));assertEquals(2000,m.lengthMm());assertEquals("rect",m.section());
        assertEquals(new Vec3d(2500,64500,-3500),f.originMm());assertEquals(200,f.halfYMm());assertEquals(100,f.halfZMm());
        assertEquals(new EndForces(-7,8,9,10000,11000,12000),m.endI());
        for(int k=0;k<4;k++) {
            var s=m.stations().get(k);double[] expected={2+10*k,-4-10*k,6+10*k,-8-10*k};
            for(int j=0;j<4;j++)assertEquals(expected[j],s.fibres().get(j).sigmaMpa());
            assertEquals(9+k,s.tauMpa());assertTrue(s.naOffsetZMm().isEmpty());
        }
        assertEquals(new Vec3d(2500,64700,-3500),m.stations().get(0).fibres().get(0).positionMm(f.originMm()));
        assertEquals(38,m.peakMagnitudeMpa());assertEquals(GoverningFibre.TENSION,m.governingFibre());
    }
    @Test void boundedInterpolationUsesFaceCentresAndClamps() {
        var f=sample().display().orElseThrow();var side=BeamDisplayField.Side.FIRST;
        assertEquals(2,f.sampleMpa(0,1,0,side));assertEquals(-4,f.sampleMpa(0,-1,0,side));
        assertEquals(6,f.sampleMpa(0,0,1,side));assertEquals(-8,f.sampleMpa(0,0,-1,side));
        assertEquals(-1,f.sampleMpa(0,0,0,side));assertEquals(4,f.sampleMpa(0,1,1,side));
        assertEquals(4,f.sampleMpa(-100,99,99,side));assertEquals(7,f.sampleMpa(500,1,0,side));
        for(double y:new double[]{-2,-1,-.25,0,.25,1,2}) for(double z:new double[]{-2,-1,0,1,2})
            assertTrue(f.sampleMpa(500,y,z,side)>=-18 && f.sampleMpa(500,y,z,side)<=16);
    }
    @Test void duplicateStationsRetainBothSidesAndNeverInventAGoverningSide() {
        var m=sample();var f=m.display().orElseThrow();
        assertEquals(4,m.stations().size());assertEquals(List.of(1000.),f.breaksMm());
        assertEquals(12,f.faceSigmaMpa(1000,0,BeamDisplayField.Side.FIRST));
        assertEquals(22,f.faceSigmaMpa(1000,0,BeamDisplayField.Side.LAST));
        assertEquals(27,f.faceSigmaMpa(1500,0,BeamDisplayField.Side.LAST));
        assertEquals(-1,m.governingStation());assertEquals(1000,m.governingPositionMm().orElseThrow());
        var ribbon=StressRibbonBuilder.build(m,StressPalette.SIGNED_DEFAULT,38);
        assertEquals(8,ribbon.bands().size());
        assertEquals(List.of(2.,22.),ribbon.bands().stream().filter(b->b.fibre().equals("TOP_Y")).map(StressRibbon.Band::fromSigma).toList());
        byte[] bytes=fixture();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putDouble(144,1);
        assertEquals(3,decode(bytes).governingStation());
        // Independent f32 packing: station width halves, geometry remains untouched f64.
        var small=ByteBuffer.allocate(516).order(ByteOrder.LITTLE_ENDIAN);
        small.put(fixture(),0,172);
        var original=ByteBuffer.wrap(fixture()).order(ByteOrder.LITTLE_ENDIAN);
        for(int k=0;k<44;k++)small.putFloat(172+4*k,(float)original.getDouble(172+8*k));
        small.position(348);small.put(fixture(),524,168);
        String h=header(1).replace(section("stations",172,352,4),section("stations:f32",172,176,4))
                .replace(section("memberGeometry",524,168,1),section("memberGeometry",348,168,1));
        var narrow=BsiBeamDisplay.decode(reply(h,small.array()),17,Map.of(5,"steel"),Map.of(6,"rect")).get(0);
        assertEquals(-1,narrow.governingStation());assertEquals(4,narrow.stations().size());
        assertEquals(22,narrow.display().orElseThrow().faceSigmaMpa(1000,0,BeamDisplayField.Side.LAST));
    }
    @Test void neutralAxisIsSuppliedDataAndSegmentsDoNotCrossMissingOrDuplicateStations() {
        var m=sample();
        assertTrue(SectionDiagram.sampled(m.stations().get(1)).orElseThrow().neutralFraction().isEmpty());
        // Supplied +50 mm is deliberately unrelated to this fixture's stress-zero estimate.
        assertEquals(.375,SectionDiagram.sampled(m.stations().get(0)).orElseThrow().neutralFraction().orElseThrow());
        var segments=StressRibbonBuilder.build(m,StressPalette.SIGNED_DEFAULT,38).neutralSegments();
        assertEquals(List.of(1,2),segments.stream().map(List::size).toList());
        byte[] bytes=fixture();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putDouble(172+88+72,.05);
        assertEquals(List.of(2,2),StressRibbonBuilder.build(decode(bytes),StressPalette.SIGNED_DEFAULT,38).neutralSegments().stream().map(List::size).toList());
    }
    @Test void surfaceTilesSplitWithoutSmearingAcrossTheJump() {
        var f=sample().display().orElseThrow();
        assertThrows(IllegalArgumentException.class,()->BeamSurfacePatch.sample(f,List.of(Vec3d.ZERO,Vec3d.ZERO,Vec3d.ZERO),0));
        var polygons=BeamSurfacePatch.sample(f,List.of(new Vec3d(3000,65000,-3600),new Vec3d(4000,65000,-3600),
                new Vec3d(4000,65000,-3400),new Vec3d(3000,65000,-3400)),500);
        assertEquals(2,polygons.size());
        for(int k=0;k<2;k++) for(var v:polygons.get(k)) if(v.positionMm().x()==3500) {
            // y=1,z=±.2; the neighboring Z face contributes 1/6, independently on each side.
            double z=(v.positionMm().z()+3500)/500;
            assertEquals(f.sampleMpa(1000,1,z,k==0?BeamDisplayField.Side.FIRST:BeamDisplayField.Side.LAST),v.sigmaMpa());
        }
        var tinySamples=f.stations().stream().map(s->new StressStation(s.xMm()*1e-11,
                new Vec3d(s.xMm()*1e-11,0,0),s.fibres(),s.sigmaTensMpa(),s.sigmaCompMpa(),s.tauMpa(),s.naOffsetYMm(),s.naOffsetZMm())).toList();
        var tiny=new BeamDisplayField(Vec3d.ZERO,f.ax(),f.ay(),f.az(),2e-8,200,100,tinySamples);
        var thin=BeamSurfacePatch.sample(tiny,List.of(new Vec3d(0,500,0),new Vec3d(2e-8,500,0),
                new Vec3d(2e-8,500,100),new Vec3d(0,500,100)),500);
        assertEquals(2,thin.size());
        assertEquals(2,thin.get(0).stream().filter(v->v.positionMm().x()==0 && v.positionMm().z()==0).findFirst().orElseThrow().sigmaMpa());
        assertEquals(32,thin.get(1).stream().filter(v->v.positionMm().x()==2e-8 && v.positionMm().z()==0).findFirst().orElseThrow().sigmaMpa());
    }
    @Test void nativeVerdictNeverDependsOnDisplayedDc() {
        for(int flag:new int[]{0,1}) {
            byte[] bytes=fixture();bytes[154]=(byte)flag;var m=decode(bytes);
            assertEquals(1,m.dc());assertEquals(flag==1,m.isOverloaded());
        }
    }
    @Test void revisionSectionsAndCatalogueAreRequiredButRequestedZeroIsValid() {
        var r=reply(header(1),fixture());
        assertThrows(IllegalArgumentException.class,()->BsiBeamDisplay.decode(r,18,Map.of(5,"steel"),Map.of(6,"rect")));
        assertThrows(IllegalArgumentException.class,()->BsiBeamDisplay.decode(r,17,Map.of(),Map.of(6,"rect")));
        assertThrows(IllegalArgumentException.class,()->BsiBeamDisplay.decode(r,17,Map.of(5,"steel"),Map.of()));
        for(String h:List.of(header(1).replace("response","error"),header(1).replace("bsi.solve","bsi.hello"),header(1).replace("ok","singular"),
                header(1).replace("\"memberGeometry\"","\"x-other\""),header(1).replace("\"stations\"","\"x-other\"")))
            assertThrows(IllegalArgumentException.class,()->BsiBeamDisplay.decode(reply(h,fixture()),17,Map.of(5,"steel"),Map.of(6,"rect")));
        assertTrue(BsiBeamDisplay.decode(reply(header(0),new byte[0]),17,Map.of(),Map.of()).isEmpty());
    }
    @Test void malformedDataAndExternalMutationCannotReachTheDisplay() {
        byte[] bytes=fixture();var m=decode(bytes);Arrays.fill(bytes,(byte)0);
        assertEquals(2,m.stations().get(0).fibres().get(0).sigmaMpa());
        assertThrows(UnsupportedOperationException.class,()->m.stations().clear());
        var f=m.display().orElseThrow();var list=new ArrayList<>(f.stations());
        var copy=new BeamDisplayField(f.originMm(),f.ax(),f.ay(),f.az(),2000,200,100,list);list.clear();assertEquals(4,copy.stations().size());
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,()->new BeamDisplayField(f.originMm(),f.ax(),f.ay(),f.az(),bad,200,100,f.stations()));
            byte[] poison=fixture();ByteBuffer.wrap(poison).order(ByteOrder.LITTLE_ENDIAN).putDouble(172+32,bad);
            assertThrows(IllegalArgumentException.class,()->decode(poison));
        }
        assertThrows(IllegalArgumentException.class,()->new BeamDisplayField(f.originMm(),Vec3d.ZERO,f.ay(),f.az(),2000,200,100,f.stations()));
        byte[] order=fixture();ByteBuffer.wrap(order).order(ByteOrder.LITTLE_ENDIAN).putDouble(172+88,.75);
        assertThrows(IllegalArgumentException.class,()->decode(order));
        byte[] overflow=fixture();ByteBuffer.wrap(overflow).order(ByteOrder.LITTLE_ENDIAN).putDouble(64,Double.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,()->decode(overflow));
    }
}
