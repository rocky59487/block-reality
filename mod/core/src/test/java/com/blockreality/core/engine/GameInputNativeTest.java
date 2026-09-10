package com.blockreality.core.engine;

import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import static org.junit.jupiter.api.Assertions.*;

class GameInputNativeTest {
    private static Path library() {
        String path=System.getProperty("br.engine",System.getenv("BR_ENGINE"));
        Assumptions.assumeTrue(path!=null&&!path.isBlank()&&Files.isRegularFile(Path.of(path)),"real native engine required");
        return Path.of(path);
    }
    private static GameWorldSnapshot world(long revision) {
        var cells=new ArrayList<GameWorldSnapshot.Cell>();
        for(int x=0;x<5;x++)cells.add(GameWorldSnapshot.Cell.of(new BlockKey(x,0,0),"steel","steel_rect_200x400",0));
        return new GameWorldSnapshot(new WorldRevision(revision),cells,List.of(new BlockKey(-1,0,0)),
                List.of(new BsiRecords.Load(4,0,0,0,-10000,0)));
    }

    @Test void fullGameVocabularyAndSnapshotReachTheRealEngine() {
        try(var engine=InProcessEngine.open(library(),1)) {
            assertEquals(InProcessEngine.Status.READY,engine.status(),()->String.valueOf(engine.disabledReason()));
            assertTrue(engine.declareVocabulary(GameVocabulary.declaration()),()->String.valueOf(engine.disabledReason()));
            var vocabulary=engine.vocabulary();assertNotNull(vocabulary);
            for(var binding:GameVocabulary.products().values()) {binding.materialId(vocabulary);binding.sectionId(vocabulary);}
            for(var storage:BsiHeaders.Storage.values()) {
                var world=world(9007199254740993L);
                var actual=engine.analyze(world,1,storage,new BsiHeaders.EigenBuckling(0));
                assertTrue(actual.ok(),actual.diagnostic()); assertEquals(1,actual.members().size());
                assertEquals("steel",actual.members().get(0).material());
                assertEquals("steel_rect_200x400",actual.members().get(0).section());
                var precision=new BsiHeaders.Precision(BsiHeaders.Tier.COMMIT,storage);
                var raw=engine.solve(true,new double[]{0,-9.81,0},world.loads(),1,BsiAnalysisResult.INCLUDE,precision,new BsiHeaders.EigenBuckling(0));
                assertNotNull(raw);assertFalse(raw.isError(),raw.message());
                var expected=BsiAnalysisResult.decode(raw,world.revision(),vocabulary.materials(),vocabulary.sections(),precision);
                assertTrue(expected.ok(),expected.diagnostic());
                assertEquals(expected.maxDc(),actual.maxDc());assertEquals(expected.overCapacity(),actual.overCapacity());
                assertEquals(expected.bucklingCritical(),actual.bucklingCritical());assertEquals(expected.bucklingIslands(),actual.bucklingIslands());
                var a=actual.members().get(0);var e=expected.members().get(0);
                assertEquals(e.stations(),a.stations());assertEquals(e.blocks(),a.blocks());
                var ad=a.display().orElseThrow();var ed=e.display().orElseThrow();
                assertEquals(ed.originMm(),ad.originMm());assertEquals(ed.ax(),ad.ax());
                assertEquals(ed.ay(),ad.ay());assertEquals(ed.az(),ad.az());
                assertEquals(ed.lengthMm(),ad.lengthMm());assertEquals(ed.halfYMm(),ad.halfYMm());
                assertEquals(ed.halfZMm(),ad.halfZMm());assertEquals(ed.stations(),ad.stations());
                assertEquals(7850*.08*9.81*4+10000,raw.equilibrium().reaction()[1],1e-6,"C4 convention; existing half-cell issue remains");
            }
        }
    }

    @Test void declaredRectangularPresentationAxesAgreeWithDeliveredNativeFrames() {
        String[] materials = {"steel", "steel", "steel", "timber"};
        String[] sections = {"steel_rect_200x400", "steel_rect_150x300", "steel_rect_100x200", "timber_rect_140x240"};
        double[] depths = {200, 150, 100, 120}, widths = {100, 75, 50, 70};
        for (int product = 0; product < 4; product++) for (int axis = 0; axis < 3; axis++) {
            try (var engine = InProcessEngine.open(library(), 1)) {
                assertTrue(engine.declareVocabulary(GameVocabulary.declaration()));
                var cells = new ArrayList<GameWorldSnapshot.Cell>();
                for (int n = 0; n < 5; n++) cells.add(GameWorldSnapshot.Cell.of(
                        new BlockKey(axis == 0 ? n : 0, axis == 1 ? n : 0, axis == 2 ? n : 0),
                        materials[product], sections[product], axis));
                var input = new GameWorldSnapshot(new WorldRevision(41), cells,
                        List.of(new BlockKey(axis == 0 ? -1 : 0, axis == 1 ? -1 : 0, axis == 2 ? -1 : 0)), List.of());
                var result = engine.analyze(input, 1, BsiHeaders.Storage.F64, new BsiHeaders.EigenBuckling(0));
                assertTrue(result.ok(), result.diagnostic()); assertEquals(1, result.members().size());
                var f = result.members().get(0).display().orElseThrow();
                double[] ax = {Math.abs(f.ax().x()), Math.abs(f.ax().y()), Math.abs(f.ax().z())};
                double[] ay = {Math.abs(f.ay().x()), Math.abs(f.ay().y()), Math.abs(f.ay().z())};
                double[] az = {Math.abs(f.az().x()), Math.abs(f.az().y()), Math.abs(f.az().z())};
                assertArrayEquals(axis == 0 ? new double[]{1,0,0} : axis == 1 ? new double[]{0,1,0} : new double[]{0,0,1}, ax, 1e-12);
                assertArrayEquals(axis == 1 ? new double[]{1,0,0} : new double[]{0,1,0}, ay, 1e-12);
                assertArrayEquals(axis == 2 ? new double[]{1,0,0} : new double[]{0,0,1}, az, 1e-12);
                assertEquals(depths[product], f.halfYMm(), 1e-9); assertEquals(widths[product], f.halfZMm(), 1e-9);
                var form = ProductForm.of(GameVocabulary.geometry(materials[product], sections[product]), axis);
                assertEquals(f.halfYMm(), form.depthHalfMetres()*1000, 1e-9);
                assertEquals(f.halfZMm(), form.widthHalfMetres()*1000, 1e-9);
            }
        }
    }

    @Test void refusedVocabularyRedeclarationInvalidatesOldWorldAndNames() {
        try(var engine=InProcessEngine.open(library(),1)) {
            assertTrue(engine.declareVocabulary(GameVocabulary.declaration()));
            assertTrue(engine.analyze(world(7),1,BsiHeaders.Storage.F64,null).ok());
            assertFalse(engine.declareVocabulary(GameVocabulary.declaration()),"BSI refuses vocab after world");
            assertNull(engine.vocabulary());
            assertNull(engine.solve(true,null,List.of(),1,List.of()));
            assertFalse(engine.analyze(world(8),1,BsiHeaders.Storage.F64,null).ok());
            assertFalse(engine.analyze(new WorldRevision(7),true,null,List.of(),1,Map.of(),Map.of(),BsiHeaders.Storage.F64).ok());
            assertFalse(engine.declareWorld(8,List.of(BsiRecords.Block.of(0,0,0,0,0,0))));
        }
    }

    @Test void profilingObservesTheRealBridgeWithoutChangingNativeResults() {
        var profile=new com.blockreality.core.diagnostics.PipelineProfile();
        try(var engine=InProcessEngine.open(library(),1,profile)) {
            assertTrue(engine.declareVocabulary(GameVocabulary.declaration()));
            var input=world(12); var baseline=engine.analyze(input,1,BsiHeaders.Storage.F64,new BsiHeaders.EigenBuckling(2400));
            assertTrue(baseline.ok(),baseline.diagnostic()); assertTrue(profile.snapshot().stages().isEmpty());
            profile.start(); var measured=engine.analyze(input,1,BsiHeaders.Storage.F64,new BsiHeaders.EigenBuckling(2400)); profile.stop();
            assertTrue(measured.ok(),measured.diagnostic());
            assertEquals(baseline.maxDc(),measured.maxDc()); assertEquals(baseline.bucklingIslands(),measured.bucklingIslands());
            assertEquals(baseline.overCapacity(),measured.overCapacity()); assertEquals(baseline.bucklingCritical(),measured.bucklingCritical());
            assertEquals(baseline.members().get(0).stations(),measured.members().get(0).stations());
            assertEquals(baseline.members().get(0).display().orElseThrow().stations(),measured.members().get(0).display().orElseThrow().stations());
            var s=profile.snapshot();
            assertEquals(2,s.stages().get(com.blockreality.core.diagnostics.PipelineProfile.Stage.NATIVE_CALL).observed());
            assertEquals(1,s.stages().get(com.blockreality.core.diagnostics.PipelineProfile.Stage.RESULT_DECODE).observed());
            assertTrue(s.counters().get(com.blockreality.core.diagnostics.PipelineProfile.Counter.REQUEST_BYTES)>0);
            assertTrue(s.counters().get(com.blockreality.core.diagnostics.PipelineProfile.Counter.REPLY_BYTES)>0);

            // Force the production bridge's real 64 KiB reply buffer to grow while profiling.
            var cells=new ArrayList<GameWorldSnapshot.Cell>();var ground=new ArrayList<BlockKey>();
            for(int r=0;r<8;r++)for(int c=0;c<8;c++) {
                int x=c*12,z=r*4;ground.add(new BlockKey(x-1,0,z));
                for(int n=0;n<9;n++)cells.add(GameWorldSnapshot.Cell.of(new BlockKey(x+n,0,z),"steel","steel_rect_200x400",0));
            }
            var large=new GameWorldSnapshot(new WorldRevision(13),cells,ground,List.of());
            profile.start();var grown=engine.analyze(large,1,BsiHeaders.Storage.F64,new BsiHeaders.EigenBuckling(2400));profile.stop();
            assertTrue(grown.ok(),grown.diagnostic());var growth=profile.snapshot();
            long attempts=growth.counters().get(com.blockreality.core.diagnostics.PipelineProfile.Counter.BUFFER_GROWTH);
            assertTrue(attempts>0,"real reply exceeds the initial bridge buffer");
            assertEquals(2+attempts,growth.stages().get(com.blockreality.core.diagnostics.PipelineProfile.Stage.NATIVE_CALL).observed());
            var repeat=engine.analyze(large,1,BsiHeaders.Storage.F64,new BsiHeaders.EigenBuckling(2400));
            assertTrue(repeat.ok(),repeat.diagnostic());assertEquals(64,grown.members().size());
            assertEquals(grown.maxDc(),repeat.maxDc());assertEquals(grown.bucklingIslands(),repeat.bucklingIslands());
            for(int i=0;i<grown.members().size();i++) {
                assertEquals(grown.members().get(i).stations(),repeat.members().get(i).stations());
                assertEquals(grown.members().get(i).display().orElseThrow().stations(),repeat.members().get(i).display().orElseThrow().stations());
            }
            assertEquals(growth,profile.snapshot(),"disabled repeat cannot change stopped observations");
        }
    }
}
