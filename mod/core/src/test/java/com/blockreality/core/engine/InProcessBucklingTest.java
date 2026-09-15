package com.blockreality.core.engine;

import com.blockreality.api.*;
import com.blockreality.core.bsi.*;
import org.junit.jupiter.api.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InProcessBucklingTest {
    private static final String VOCAB="""
      {"version":1,"materials":[{"name":"steel","role":"member","model":"isotropic","E":2e11,"nu":0.3,"rho":7850,
      "allow":{"sigmaC":2.5e8,"sigmaT":2.5e8,"tau":1.45e8},"defaultSection":"rect","eulerBernoulli":true},
      {"name":"ground","role":"support","supportKind":"fixAll"}],"sections":[{"name":"rect","kind":"rect","p":[0.2,0.4]}]}
      """;
    private static InProcessEngine engine(){
        String file=System.getProperty("br.engine","");if(file.isBlank())file=System.getenv("BR_ENGINE");
        Assumptions.assumeTrue(file!=null&&!file.isBlank(),"BR_ENGINE required for native integration");
        var engine=InProcessEngine.open(Path.of(file),1);assertEquals(InProcessEngine.Status.READY,engine.status(),String.valueOf(engine.disabledReason()));
        assertTrue(engine.has("bsi.buckling.eigen"));assertTrue(engine.declareVocabulary(VOCAB));return engine;
    }
    private static List<BsiRecords.Block> world(boolean floating){
        var rows=new ArrayList<BsiRecords.Block>();rows.add(new BsiRecords.Block(0,-1,0,1,-1,1,0,0,1,1));
        for(int y=0;y<20;y++)rows.add(new BsiRecords.Block(0,y,0,0,-1,1,0,0,1,1));
        if(floating)for(int y=0;y<3;y++)rows.add(new BsiRecords.Block(5,y,0,0,-1,1,0,0,1,1));return rows;
    }
    @Test void actualEigenAndMixedFlagsReachTheSharedAnalysis(){
        try(var engine=engine()){
            var loads=List.of(new BsiRecords.Load(0,19,0,0,-1e6,0));
            for(boolean mixed:new boolean[]{false,true})for(var storage:BsiHeaders.Storage.values()){
                assertTrue(engine.declareWorld(17,world(mixed)));
                var r=engine.analyze(new WorldRevision(17),false,null,loads,1,Map.of(0,"steel"),Map.of(0,"rect"),storage,new BsiHeaders.EigenBuckling(0));
                assertTrue(r.ok(),r.diagnostic());assertTrue(r.bucklingCritical());assertEquals(mixed?2:1,r.islands());
                assertEquals(mixed?BucklingState.NOT_ELIGIBLE:BucklingState.COMPUTED,r.bucklingState());
                double expected=Math.PI*Math.PI*2e11*(.4*Math.pow(.2,3)/12)/(4*19*19*1e6);
                if(mixed)assertEquals(0,r.bucklingFactor());else assertEquals(expected,r.bucklingFactor(),.005*expected);
                assertEquals(mixed?2:1,r.bucklingIslands().size());
                var column=r.bucklingIslands().get(0);assertEquals(0,column.island());
                assertEquals(IslandBuckling.Kind.EIGEN,column.kind());assertEquals(BucklingState.COMPUTED,column.state());
                assertEquals(expected,column.factor(),.005*expected);
                if(mixed){var floating=r.bucklingIslands().get(1);assertEquals(1,floating.island());
                    assertEquals(BucklingState.NOT_ELIGIBLE,floating.state());assertTrue(Double.isNaN(floating.factor()));}
                assertThrows(UnsupportedOperationException.class,()->r.bucklingIslands().clear());
                var none=engine.analyze(new WorldRevision(17),false,null,loads,1,Map.of(0,"steel"),Map.of(0,"rect"),storage);
                assertTrue(none.ok(),none.diagnostic());assertEquals(BucklingState.DISABLED_BY_REQUEST,none.bucklingState());assertFalse(none.bucklingCritical());
                assertEquals(r.islands(),none.bucklingIslands().size());
                for(var island:none.bucklingIslands()){assertEquals(IslandBuckling.Kind.NONE,island.kind());
                    assertEquals(BucklingState.DISABLED_BY_REQUEST,island.state());assertTrue(Double.isNaN(island.factor()));}
            }
        }
    }
    @Test void actualBudgetRefusalAndGreenhillAreDistinct(){
        try(var engine=engine()){
            assertTrue(engine.declareWorld(17,world(false)));var precision=new BsiHeaders.Precision(BsiHeaders.Tier.COMMIT,BsiHeaders.Storage.F64);
            var green=engine.solve(true,new double[]{0,-9.81,0},List.of(),1,List.of("members"),precision,new BsiHeaders.EigenBuckling(0));
            assertNotNull(green);assertFalse(green.isError(),green.message());var r=BsiBuckling.decode(green,1);
            double expected=7.8373*2e11*(.4*Math.pow(.2,3)/12)/(Math.pow(19,3)*7850*.08*9.81);assertEquals(expected,r.factor(),expected*.005);
            var scale=engine.solve(true,new double[]{0,-9.81,0},List.of(),1,List.of("members"),precision,new BsiHeaders.EigenBuckling(1));
            assertNotNull(scale);assertFalse(scale.isError(),scale.message());assertEquals(BucklingState.NOT_ELIGIBLE_SCALE,BsiBuckling.decode(scale,1).state());
        }
    }
}
