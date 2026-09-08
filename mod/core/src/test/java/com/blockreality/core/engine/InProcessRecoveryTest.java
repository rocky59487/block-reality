package com.blockreality.core.engine;

import com.blockreality.core.bsi.BsiHeaders;
import com.blockreality.core.bsi.BsiRecords;
import com.blockreality.core.bsi.BsiResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Actual C6/C8/C12 through JNA; configured native runs require every capability. */
class InProcessRecoveryTest {
    @Test void nativeBeamSamplesAndPointLoadSidesReachTheSharedDisplay() {
        try (InProcessEngine engine = engine()) {
            assertTrue(engine.has("bsi.readback.memberGeometry"));
            assertTrue(engine.declareWorld(73, world(false, 0)));
            for (var storage : BsiHeaders.Storage.values()) {
                var r=engine.solve(true,new double[]{0,-9.81,0},
                        List.of(new BsiRecords.Load(2,0,0,0,-10000,0)),1,List.of("members","stations","memberGeometry"),
                        new BsiHeaders.Precision(BsiHeaders.Tier.COMMIT,storage));
                assertNotNull(r);assertEquals("ok",r.status(),r.message());
                var mapped=com.blockreality.core.bsi.BsiBeamDisplay.decode(r,73,java.util.Map.of(0,"steel"),java.util.Map.of(0,"rect"));
                assertEquals(r.members().size(),mapped.size());
                int duplicates=0;
                for(int k=0;k<mapped.size();k++) {
                    var m=mapped.get(k);var b=r.members().get(k);var f=m.display().orElseThrow();var g=r.memberGeometry().get(k);
                    assertEquals(b.stationCount(),m.stations().size());assertEquals(b.blockCount(),m.blocks().size());
                    assertTrue(m.field().isEmpty());assertEquals(b.overloaded(),m.overloaded());assertEquals(b.maxDC(),m.dc());
                    assertEquals(g.origin().scaled(1000),f.originMm());assertEquals(g.ey(),f.ay());assertEquals(g.faceY().get(0)*1000,f.halfYMm());
                    assertEquals(-b.endI()[0],m.endI().n());assertEquals(b.endJ()[5]*1000,m.endJ().mz());
                    for(int j=0;j<m.stations().size();j++) {
                        var s=m.stations().get(j);double[] raw=r.stations()[b.stationFirst()+j];
                        assertEquals(raw[0]*b.lengthM()*1000,s.xMm(),1e-9);
                        assertEquals(new com.blockreality.api.geom.Vec3d(raw[1]*1000,raw[2]*1000,raw[3]*1000),s.centroidMm());
                        for(int face=0;face<4;face++)assertEquals(raw[4+face]*1e-6,s.fibres().get(face).sigmaMpa());
                        assertEquals(raw[8]*1e-6,s.tauMpa());
                        assertEquals(Double.isNaN(raw[9]),s.naOffsetYMm().isEmpty());
                        if(Double.isFinite(raw[9]))assertEquals(raw[9]*1000,s.naOffsetYMm().orElseThrow());
                        if(j>0 && s.xMm()==m.stations().get(j-1).xMm())duplicates++;
                    }
                    var ribbon=com.blockreality.core.render.StressRibbonBuilder.build(m,com.blockreality.api.render.StressPalette.SIGNED_DEFAULT,m.peakMagnitudeMpa());
                    assertTrue(ribbon.bands().stream().allMatch(band->!band.from().equals(band.to())));
                    for(var s:m.stations())assertTrue(com.blockreality.core.render.SectionDiagram.sampled(s).isPresent());
                }
                // Current native recovery chooses one side per unique location (dated GATES record).
                // This leg proves full forwarding, not native dual-side readback.
                assertEquals(0,duplicates,"current native station recovery is single-sided");
            }
        }
    }
    @Test void nativeShellRecoveryFeedsTheSameDisplayAndPickingPath() {
        try (InProcessEngine engine = engine()) {
            assertTrue(engine.declareWorld(73, world(true, 0)));
            for (var storage : BsiHeaders.Storage.values()) {
                var response = solve(engine, storage, List.of("shells"));
                var display = com.blockreality.core.bsi.BsiShellDisplay.decode(response, 73, java.util.Map.of(1,"slab"));
                assertEquals(15, display.size());
                for (int k=0; k<display.size(); k++) {
                    var shell = display.get(k); var field = shell.display().orElseThrow();
                    var facet = response.facets().get(k); var surfaces = response.facetSurfaces().get(k);
                    assertTrue(shell.field().isEmpty()); assertTrue(shell.rawDc().isEmpty());
                    assertEquals(facet.dc(),shell.dc()); assertEquals(facet.overloaded(),shell.overloaded());
                    assertEquals(facet.governingTop(),shell.governingTopFace());
                    assertEquals(facet.blockCount(),shell.blocks().size());
                    var hit = com.blockreality.core.render.ShellMesh.locate(display,field.centreMm()).orElseThrow();
                    assertEquals(shell.id(), hit.shell().id()); assertEquals(0,hit.outsideMm());
                    for (int j=0; j<4; j++) {
                        double[] p = facet.corners()[j];
                        assertEquals(new com.blockreality.api.geom.Vec3d(p[0]*1000,p[1]*1000,p[2]*1000),field.cornersMm().get(j));
                        for (int side=0; side<2; side++) {
                            var actual = (side==0 ? field.top() : field.bottom()).get(j);
                            var expected = (side==0 ? surfaces.top() : surfaces.bottom()).get(j);
                            assertEquals(expected.s1()*1e-6,actual.s1()); assertEquals(expected.s2()*1e-6,actual.s2());
                            assertEquals(expected.theta(),actual.theta()); assertEquals(expected.vm()*1e-6,actual.vm());
                        }
                    }
                }
            }
        }
    }
    @Test void memberGeometryUsesActualAxesRotatedSectionAndF64WorldPositions() {
        try (InProcessEngine engine = engine()) {
            assertTrue(engine.has("bsi.readback.memberGeometry"));
            double[][] axes={{1,0,0,0,1,0,0,0,1},{0,1,0,1,0,0,0,0,-1},{0,0,1,0,1,0,-1,0,0}};
            long revision=0;
            for(int axis=0;axis<3;axis++) for(int rot=0;rot<4;rot++) for(int shift=0;shift<2;shift++) {
                int[] offset={shift*23,shift*64,shift*-31};
                List<BsiRecords.Block> world=new ArrayList<>();
                for(int k=-1;k<5;k++) world.add(new BsiRecords.Block(offset[0]+(axis==0?k:0),offset[1]+(axis==1?k:0),
                        offset[2]+(axis==2?k:0),k<0?2:0,-1,axis,0,rot,1,1));
                assertTrue(engine.declareWorld(++revision,world));
                byte[] full=null;
                for(var storage:BsiHeaders.Storage.values()) {
                    var reply=engine.solve(true,new double[]{0,-9.81,0},List.of(),1,List.of("members","stations","memberGeometry"),new BsiHeaders.Precision(BsiHeaders.Tier.COMMIT,storage));
                    assertNotNull(reply);assertFalse(reply.isError(),reply.message());assertEquals("ok",reply.status());
                    assertEquals(1,reply.members().size());assertEquals(1,reply.memberGeometry().size());
                    var g=reply.memberGeometry().get(0);assertEquals(reply.members().get(0).id(),g.id());
                    assertEquals(new com.blockreality.api.geom.Vec3d(offset[0]+.5,offset[1]+.5,offset[2]+.5),g.origin());
                    var got=List.of(g.ex(),g.ey(),g.ez());
                    for(int j=0;j<3;j++)assertArrayEquals(new double[]{axes[axis][3*j],axes[axis][3*j+1],axes[axis][3*j+2]},new double[]{got.get(j).x(),got.get(j).y(),got.get(j).z()},0.);
                    double h=(rot%2==0)?.2:.1,b=(rot%2==0)?.1:.2;
                    assertEquals(List.of(h,-h,0.,0.),g.faceY());assertEquals(List.of(0.,0.,b,-b),g.faceZ());
                    if(full==null)full=section(reply,"memberGeometry");else assertArrayEquals(full,section(reply,"memberGeometry"));
                }
                var missing=engine.solve(false,new double[]{0,0,0},List.of(),1,List.of("memberGeometry"));
                assertNotNull(missing);assertEquals("PROTOCOL_ERROR",missing.code());
            }
        }
    }
    private static final String VOCAB = """
            {"version":1,"materials":[
            {"name":"steel","role":"member","model":"isotropic","E":2e11,"nu":0.3,"rho":7850,
             "allow":{"sigmaC":2.5e8,"sigmaT":2.5e8,"tau":1.45e8},"defaultSection":"rect"},
            {"name":"slab","role":"panel","model":"isotropic","E":3e10,"nu":0.2,"rho":2400,
             "shellThickness":0.2,"allow":{"sigmaC":3e7,"sigmaT":3e6,"tau":4e6}},
            {"name":"ground","role":"support","supportKind":"fixAll"}],
            "sections":[{"name":"rect","kind":"rect","p":[0.2,0.4]}]}""";

    private static InProcessEngine engine() {
        String value = System.getProperty("br.engine", "");
        if (value.isBlank()) value = System.getenv("BR_ENGINE");
        Assumptions.assumeTrue(value != null && !value.isBlank(), "BR_ENGINE required for native integration");
        assertTrue(Files.isRegularFile(Path.of(value)), "configured native library must exist");
        InProcessEngine engine = InProcessEngine.open(Path.of(value), 1);
        try {
            assertEquals(InProcessEngine.Status.READY, engine.status(), () -> "handshake: " + engine.disabledReason());
            for (String cap : List.of("bsi.readback.members", "bsi.readback.stations", "bsi.readback.shells", "bsi.precision.f32"))
                assertTrue(engine.has(cap), "native capability required: " + cap);
            assertTrue(engine.declareVocabulary(VOCAB));
            return engine;
        } catch (Throwable failure) {
            engine.close();
            throw failure;
        }
    }

    private static BsiResponse solve(InProcessEngine engine, BsiHeaders.Storage storage, List<String> include) {
        BsiResponse r = engine.solve(true, new double[]{0, -9.81, 0}, List.of(), 1, include,
                new BsiHeaders.Precision(BsiHeaders.Tier.COMMIT, storage));
        assertNotNull(r);
        assertFalse(r.isError(), () -> r.code() + ": " + r.message());
        assertEquals("ok", r.status());
        assertTrue(r.quality().tierHonoured());
        assertEquals(storage == BsiHeaders.Storage.F32 ? 1 : 0, r.quality().storage());
        return r;
    }

    private static List<BsiRecords.Block> world(boolean slab, int transform) {
        List<BsiRecords.Block> blocks = new ArrayList<>();
        for (int x : new int[]{0, 4}) blocks.add(block(x, -1, 0, 2, transform));
        for (int x = 0; x < 5; x++) {
            blocks.add(block(x, 0, 0, 0, transform));
            if (slab) for (int z = -1; z <= 1; z++) blocks.add(block(x, 1, z, 1, transform));
        }
        return blocks;
    }

    private static BsiRecords.Block block(int x, int y, int z, int material, int transform) {
        return BsiRecords.Block.of(transform == 1 ? -x : transform == 2 ? z : x, y,
                transform == 2 ? -x : z, material, -1, transform == 2 ? 2 : 0);
    }

    private static byte[] section(BsiResponse r, String name) {
        BsiResponse.Section s = r.sections().get(name);
        assertNotNull(s, name);
        return Arrays.copyOfRange(r.payload(), s.offset(), s.offset() + s.bytes());
    }

    private static void f32(double full, double narrow) {
        assertEquals(Double.doubleToLongBits((double)(float)full), Double.doubleToLongBits(narrow), "IEEE f32 round trip");
    }

    private static void surfaces(BsiResponse a, BsiResponse b) {
        assertEquals(a.facetSurfaces().size(), b.facetSurfaces().size());
        for (int i = 0; i < a.facetSurfaces().size(); i++) {
            var af = a.facetSurfaces().get(i); var bf = b.facetSurfaces().get(i);
            for (int j = 0; j < 8; j++) {
                var x = j < 4 ? af.top().get(j) : af.bottom().get(j - 4);
                var y = j < 4 ? bf.top().get(j) : bf.bottom().get(j - 4);
                f32(x.s1(), y.s1()); f32(x.s2(), y.s2()); f32(x.theta(), y.theta()); f32(x.vm(), y.vm());
            }
        }
    }

    @Test void cantileverFaceSignsAndStorageMatchClosedForm() {
        try (InProcessEngine engine = engine()) {
            List<BsiRecords.Block> blocks = new ArrayList<>();
            blocks.add(BsiRecords.Block.of(-1, 0, 0, 2, -1, 0));
            for (int x = 0; x < 5; x++) blocks.add(BsiRecords.Block.of(x, 0, 0, 0, -1, 0));
            assertTrue(engine.declareWorld(1, blocks));
            BsiResponse full = solve(engine, BsiHeaders.Storage.F64, List.of("members", "stations"));
            BsiResponse narrow = solve(engine, BsiHeaders.Storage.F32, List.of("members", "stations"));
            assertEquals(1, full.members().size());
            var m = full.members().get(0); double[] root = full.stations()[m.stationFirst()];
            assertEquals(0, root[0], 1e-12);
            assertEquals(9_241_020, root[4], 9_241_020e-9); // rho*A*g*L^2/2 / (b*d^2/6), L=4
            assertEquals(-9_241_020, root[5], 9_241_020e-9);
            assertTrue(Double.isFinite(root[9]));
            assertEquals(full.stations().length, narrow.stations().length);
            for (int i = 0; i < full.stations().length; i++) for (int j = 0; j < 11; j++)
                f32(full.stations()[i][j], narrow.stations()[i][j]);
            for (String name : List.of("blocks", "members", "memberBlocks", "equilibrium"))
                assertArrayEquals(section(full, name), section(narrow, name), name);
            BsiResponse only = solve(engine, BsiHeaders.Storage.F64, List.of("stations"));
            assertTrue(only.members().isEmpty());
            assertArrayEquals(section(full, "stations"), section(only, "stations"));
        }
    }

    @Test void coupledSlabWeightAndTransformsAreInvariant() {
        double[] reactions = new double[4];
        for (int i = 0; i < 4; i++) try (InProcessEngine engine = engine()) {
            assertTrue(engine.declareWorld(1, world(i != 0, Math.max(0, i - 1))));
            BsiResponse r = solve(engine, BsiHeaders.Storage.F64, List.of("members", "shells"));
            reactions[i] = r.equilibrium().reaction()[1];
            assertTrue(r.equilibrium().residual() <= 1e-9);
            assertEquals(i == 0 ? 0 : 15, r.facets().size());
            assertEquals(r.facets().size(), r.facetSurfaces().size());
            assertEquals(r.facets().size(), r.facetBlocks().size());
        }
        assertEquals(70_632, reactions[1] - reactions[0], 70_632e-9);
        assertEquals(reactions[1], reactions[2], reactions[1] * 1e-12);
        assertEquals(reactions[1], reactions[3], reactions[1] * 1e-12);
    }

    @Test void nativeShellFramesAndSurfaceStorageAreCoherent() {
        try (InProcessEngine engine = engine()) {
            assertTrue(engine.declareWorld(1, world(true, 0)));
            BsiResponse full = solve(engine, BsiHeaders.Storage.F64, List.of("shells"));
            BsiResponse narrow = solve(engine, BsiHeaders.Storage.F32, List.of("shells"));
            assertTrue(full.members().isEmpty());
            assertEquals(15, full.facets().size());
            for (var f : full.facets()) {
                double[] x = f.ex(), y = f.ey(), n = f.n();
                assertArrayEquals(n, new double[]{x[1]*y[2]-x[2]*y[1], x[2]*y[0]-x[0]*y[2], x[0]*y[1]-x[1]*y[0]}, 1e-12);
                assertEquals(f.dc() > 1, f.overloaded());
                assertEquals(.2, f.thicknessM());
                assertEquals(1, f.blockCount());
            }
            surfaces(full, narrow);
            for (String name : List.of("blocks", "facets", "facetBlocks", "equilibrium"))
                assertArrayEquals(section(full, name), section(narrow, name), name);
        }
    }
}
