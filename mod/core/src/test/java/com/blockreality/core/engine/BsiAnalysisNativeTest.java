package com.blockreality.core.engine;

import com.blockreality.api.*;
import com.blockreality.core.bsi.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BsiAnalysisNativeTest {
    private static final String VOCAB="""
        {"version":1,"materials":[
          {"name":"steel","role":"member","model":"isotropic","E":2e11,"nu":0.3,"rho":7850,
           "allow":{"sigmaC":2.5e8,"sigmaT":2.5e8,"tau":1.45e8},"defaultSection":"rect"},
          {"name":"ground","role":"support","supportKind":"fixAll"}],
         "sections":[{"name":"rect","kind":"rect","p":[0.2,0.4]}]}
        """;
    private static Path library() {
        String p=System.getProperty("br.engine",System.getenv("BR_ENGINE"));
        Assumptions.assumeTrue(p!=null&&!p.isBlank()&&Files.isRegularFile(Path.of(p)),"real engine required");return Path.of(p);
    }
    private static List<BsiRecords.Block> world(boolean mixed, boolean fixed) {
        List<BsiRecords.Block> out=new ArrayList<>();out.add(BsiRecords.Block.of(-1,0,0,1,-1,0));
        for(int i=0;i<=4;i++)out.add(BsiRecords.Block.of(i,0,0,0,-1,0));
        if(fixed)out.add(BsiRecords.Block.of(5,0,0,1,-1,0));
        if(mixed)for(int i=20;i<=24;i++)out.add(BsiRecords.Block.of(i,0,0,0,-1,0));
        return out;
    }
    @Test void realCommitResultsShareNativeFlagsSamplesAndLifecycle() {
        try(var engine=InProcessEngine.open(library(),4)) {
            assertEquals(InProcessEngine.Status.READY,engine.status());assertTrue(engine.declareVocabulary(VOCAB));
            for(boolean mixed:new boolean[]{false,true})for(boolean fixed:new boolean[]{false,true}) {
                assertTrue(engine.declareWorld(17,world(mixed,fixed)));
                for(var storage:BsiHeaders.Storage.values()) for(double force:new double[]{0,-10000,10000,-1e8}) {
                    var p=new BsiHeaders.Precision(BsiHeaders.Tier.COMMIT,storage);
                    var loads=force==0?List.<BsiRecords.Load>of():List.of(new BsiRecords.Load(2,0,0,0,force,0));
                    var raw=engine.solve(true,new double[]{0,-9.81,0},loads,4,BsiAnalysisResult.INCLUDE,p);
                    var result=engine.analyze(new WorldRevision(17),true,new double[]{0,-9.81,0},loads,4,Map.of(0,"steel"),Map.of(0,"rect"),storage);
                    assertTrue(result.ok(),result.diagnostic());assertEquals(mixed,result.singular());assertTrue(result.isUsable());
                    assertEquals(raw.blocks().stream().anyMatch(BsiResponse.BlockResult::overloaded),result.overCapacity());
                    if(force==-1e8)assertTrue(result.overCapacity(),"real native overloaded fixture");
                    assertFalse(result.bucklingCritical());assertEquals(BucklingState.DISABLED_BY_REQUEST,result.bucklingState());
                    assertEquals(raw.members().size(),result.members().size());assertEquals(raw.members().get(0).maxDC(),result.members().get(0).dc());
                    assertEquals(raw.equilibrium().residual(),result.equilibriumResidual());
                    assertEquals(raw.stations().length,result.members().stream().mapToInt(m->m.stations().size()).sum());
                    assertEquals(raw.blocks().stream().filter(b->b.ownerKind()==1||b.ownerKind()==2).mapToDouble(BsiResponse.BlockResult::dc).max().orElse(0),result.maxDc());
                }
            }
            var stale=engine.analyze(new WorldRevision(18),true,null,List.of(),4,Map.of(0,"steel"),Map.of(0,"rect"),BsiHeaders.Storage.F64);
            assertFalse(stale.ok());assertTrue(stale.members().isEmpty());assertFalse(stale.overCapacity());
            // EMPTY_WORLD must not leave the previous successful world eligible for the high-level API.
            assertFalse(engine.declareWorld(18,List.of()));
            assertFalse(engine.analyze(new WorldRevision(18),true,null,List.of(),4,Map.of(),Map.of(),BsiHeaders.Storage.F64).ok());
            assertTrue(engine.declareWorld(19,world(false,false)));
            assertTrue(engine.analyze(new WorldRevision(19),true,null,List.of(),4,Map.of(0,"steel"),Map.of(0,"rect"),BsiHeaders.Storage.F64).ok());
        }
    }
}
