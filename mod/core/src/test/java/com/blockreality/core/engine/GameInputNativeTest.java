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
}
