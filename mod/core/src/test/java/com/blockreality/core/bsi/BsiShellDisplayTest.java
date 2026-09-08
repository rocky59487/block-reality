package com.blockreality.core.bsi;

import com.blockreality.api.ShellDisplayField;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.geom.Vec3d;
import com.blockreality.core.render.ShellMesh;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BsiShellDisplayTest {
    @Test void legacyDegenerateGeometryKeepsDiagnosticsWithoutADrawableField() {
        var old = new com.blockreality.api.ShellFieldSpec(List.of(Vec3d.ZERO,Vec3d.ZERO,Vec3d.ZERO,Vec3d.ZERO),
                new Vec3d(1,0,0),new Vec3d(0,0,1),new Vec3d(0,-1,0),200,0,0,0,0,0,0,0,0,
                java.util.Collections.nCopies(4,com.blockreality.api.ShellFieldSpec.Moments.ZERO));
        var snapshot = new ShellSnapshot(1,"slab","slab",200,0,0,true,false,List.of(),java.util.Optional.of(old));
        assertSame(old,snapshot.field().orElseThrow()); assertTrue(snapshot.display().isEmpty());
        assertTrue(ShellMesh.locate(List.of(snapshot),Vec3d.ZERO).isEmpty());
        assertThrows(IllegalArgumentException.class,old::sampledDisplay);
    }
    private static String header(String method, String status, int count) {
        return "{\"kind\":\"response\",\"method\":\"" + method + "\",\"status\":\"" + status
                + "\",\"revision\":17,\"sections\":["
                + section("facets", 0, 280 * count, count) + ","
                + section("facetSurfaces", 280 * count, 256 * count, count) + ","
                + section("facetBlocks", 536 * count, 72 * count, 6 * count) + "]}";
    }
    private static String section(String name, int offset, int bytes, int count) {
        return "{\"name\":\"" + name + "\",\"offset\":" + offset + ",\"bytes\":" + bytes + ",\"count\":" + count + "}";
    }
    private static byte[] fixture() {
        ByteBuffer b = ByteBuffer.allocate(608).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, 9).putInt(12, 6).putInt(16, 5).putDouble(24, .2);
        double[] corners = {2.5,8.5,-3.5, 4.5,8.5,-3.5, 4.5,8.5,.5, 2.5,8.5,.5};
        for (int k = 0; k < 12; k++) b.putDouble(32 + 8*k, corners[k]);
        b.putDouble(128, 1).putDouble(168, 1).putDouble(184, -1);
        // Deliberately unrelated resultants. Display must read recovery samples, never use N/M/t.
        for (int k = 0; k < 8; k++) b.putDouble(200 + 8*k, 1e88);
        b.putDouble(264, 1).put(272, (byte)3).put(273, (byte)6);
        for (int k = 0; k < 8; k++) {
            int o = 280 + 32*k, j = k%4;
            b.putDouble(o, k < 4 ? (j+1)*2e6 : 1e6);
            b.putDouble(o+8, k < 4 ? -1e6 : -(j+1)*10e6);
            b.putDouble(o+16, (j+1)*.1).putDouble(o+24, (100+k)*1e6);
        }
        for (int k = 0; k < 6; k++) b.putInt(536+12*k, k+2).putInt(540+12*k, 8).putInt(544+12*k, -3);
        return b.array();
    }
    private static BsiResponse reply(byte[] data) {
        return BsiResponse.of(new BsiFrame.Decoded(0, header("bsi.solve", "ok", 1), data));
    }
    private static ShellSnapshot sample() { return BsiShellDisplay.decode(reply(fixture()), 17, Map.of(5,"slab")).get(0); }

    @Test void unitsCornerOrderFacesAndFrameComeFromRecovery() {
        var s = sample(); var f = s.display().orElseThrow();
        assertEquals(200, s.thicknessMm()); assertEquals("slab", s.material());
        assertTrue(s.field().isEmpty()); assertTrue(s.rawDc().isEmpty());
        assertEquals(new Vec3d(2500,8500,-3500), f.cornersMm().get(0));
        assertEquals(new Vec3d(4500,8500,500), f.cornersMm().get(2));
        assertEquals(new Vec3d(0,-1,0), f.normal()); assertEquals(6, s.blocks().size());
        assertEquals(new BlockKey(7,8,-3), s.blocks().get(5));
        int[] u = {-1,1,1,-1}, v = {-1,-1,1,1};
        for (int k=0; k<4; k++) {
            assertEquals((k+1)*2, f.signedPrincipal(u[k],v[k],1));
            assertEquals(-(k+1)*10, f.signedPrincipal(u[k],v[k],-1));
            assertEquals((k+1)*.1, f.top().get(k).theta());
            assertEquals(100+k, f.vonMises(u[k],v[k],1));
        }
    }
    @Test void scalarInterpolationClampsAndSharesTheMeshScale() {
        var s = sample(); var f = s.display().orElseThrow();
        assertEquals(5, f.signedPrincipal(0,0,1)); assertEquals(-25, f.signedPrincipal(0,0,-1));
        assertEquals(-10, f.signedPrincipal(0,0,0));
        assertEquals(6, f.signedPrincipal(20,30,40));
        assertEquals(40, ShellMesh.peakMpa(List.of(s)));
        assertEquals(103.5, f.vonMises(0,0,0));
        assertEquals(1, new ShellDisplayField.Surface(1,-1,0,8).signedPrincipal());
    }
    @Test void meshPickingUsesSuppliedGeometryAndNormal() {
        var s = sample(); var f = s.display().orElseThrow();
        var hit = ShellMesh.locate(List.of(s), new Vec3d(3500,8000,-1500)).orElseThrow();
        assertSame(f, hit.field()); assertEquals(0, hit.xi()); assertEquals(0, hit.eta());
        assertEquals(0, hit.outsideMm()); assertEquals(500, f.offNormalMm(new Vec3d(3500,8000,-1500)));
        assertEquals(f.centreMm(), f.pointAt(0,0));
        var outside = ShellMesh.locate(List.of(s), new Vec3d(5000,8500,-1500)).orElseThrow();
        assertEquals(1, outside.xi()); assertEquals(500, outside.outsideMm());
        var wall = new ShellDisplayField(List.of(new Vec3d(0,0,0),new Vec3d(0,0,1000),
                new Vec3d(0,2000,1000),new Vec3d(0,2000,0)), new Vec3d(0,0,1),
                new Vec3d(0,1,0),new Vec3d(-1,0,0),f.top(),f.bottom());
        var other = new ShellSnapshot(8,"slab","slab",200,1,Double.NaN,true,false,List.of(),
                java.util.Optional.empty(),java.util.Optional.of(wall),true,6);
        assertEquals(8, ShellMesh.locate(List.of(s,other),new Vec3d(0,1000,500)).orElseThrow().shell().id());
    }
    @Test void verdictIsIndependentOfTheDisplayedDc() {
        assertNotEquals(com.blockreality.api.render.StressPalette.utilization(1,false),
                com.blockreality.api.render.StressPalette.utilization(1,true));
        for (int flags : new int[]{0,1,2,3}) {
            byte[] data = fixture(); data[272]=(byte)flags;
            var s = BsiShellDisplay.decode(reply(data),17,Map.of(5,"slab")).get(0);
            assertEquals(1,s.dc()); assertEquals((flags&1)!=0,s.overloaded());
            assertEquals((flags&2)!=0,s.governingTopFace()); assertEquals(6,s.governingFibre());
        }
    }
    @Test void wrongRevisionMissingResponseAndUnresolvedVocabularyAreRejected() {
        assertThrows(IllegalArgumentException.class,()->BsiShellDisplay.decode(reply(fixture()),18,Map.of(5,"slab")));
        assertThrows(IllegalArgumentException.class,()->BsiShellDisplay.decode(reply(fixture()),17,Map.of()));
        for (String h : List.of(header("bsi.world.declare","ok",1),header("bsi.solve","singular",1),
                "{\"method\":\"bsi.solve\",\"kind\":\"response\",\"status\":\"ok\",\"revision\":17}")) {
            var r = BsiResponse.of(new BsiFrame.Decoded(0,h,fixture()));
            assertThrows(IllegalArgumentException.class,()->BsiShellDisplay.decode(r,17,Map.of(5,"slab")));
        }
        var zero = BsiResponse.of(new BsiFrame.Decoded(0,header("bsi.solve","ok",0),new byte[0]));
        assertTrue(BsiShellDisplay.decode(zero,17,Map.of()).isEmpty());
    }
    @Test void displaySamplesAndGeometryAreImmutable() {
        byte[] bytes = fixture(); var r = reply(bytes);
        var list = BsiShellDisplay.decode(r,17,Map.of(5,"slab")); var f = list.get(0).display().orElseThrow();
        java.util.Arrays.fill(bytes,(byte)0); r.facets().get(0).corners()[0][0]=999;
        assertEquals(2, f.signedPrincipal(-1,-1,1));
        var top = new ArrayList<>(f.top()); var corners = new ArrayList<>(f.cornersMm());
        var copy = new ShellDisplayField(corners,f.ex(),f.ey(),f.normal(),top,f.bottom());
        top.clear(); corners.clear(); assertEquals(4,copy.top().size()); assertEquals(4,copy.cornersMm().size());
        assertThrows(UnsupportedOperationException.class,()->list.clear());
        assertThrows(UnsupportedOperationException.class,()->f.top().clear());
        assertThrows(UnsupportedOperationException.class,()->f.cornersMm().clear());
    }
    @Test void nonFiniteSamplesAndDegenerateFramesAreRejected() {
        for (double v : new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->new ShellDisplayField.Surface(v,0,0,0));
        var f = sample().display().orElseThrow();
        assertThrows(IllegalArgumentException.class,()->new ShellDisplayField(f.cornersMm(),Vec3d.ZERO,f.ey(),f.normal(),f.top(),f.bottom()));
        assertThrows(IllegalArgumentException.class,()->new ShellDisplayField(List.of(Vec3d.ZERO,Vec3d.ZERO,Vec3d.ZERO,Vec3d.ZERO),f.ex(),f.ey(),f.normal(),f.top(),f.bottom()));
        assertThrows(IllegalArgumentException.class,()->new ShellDisplayField(f.cornersMm().subList(0,3),f.ex(),f.ey(),f.normal(),f.top(),f.bottom()));
        assertThrows(IllegalArgumentException.class,()->f.signedPrincipal(Double.NaN,0,1));
    }
}
