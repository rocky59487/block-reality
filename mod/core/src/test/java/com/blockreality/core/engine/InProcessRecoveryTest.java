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
