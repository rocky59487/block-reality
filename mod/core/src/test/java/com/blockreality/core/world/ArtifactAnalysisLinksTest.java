package com.blockreality.core.world;

import com.blockreality.api.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.blockreality.core.world.ConstructionLedgerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactAnalysisLinksTest {
    @Test void oneObjectLinksToManyNativeElementsAndNeverChangesWithSolverNumbers() {
        var l=beam();long id=owner(l,p(0));byte[] before=l.encode();
        var result=result(7,0,4);var links=ArtifactAnalysisLinks.of(l,id,result,new WorldRevision(7)).orElseThrow();
        assertEquals(List.of(0,4),links.members()); assertTrue(links.shells().isEmpty());
        assertArrayEquals(before,l.encode());
        assertEquals(List.of(10,22),ArtifactAnalysisLinks.of(l,id,result(8,10,22),new WorldRevision(8)).orElseThrow().members());
        assertArrayEquals(before,l.encode());
    }
    @Test void staleFailedOrPendingStateDoesNotPublishCurrentMappings() {
        var l=beam();long id=owner(l,p(0));
        assertTrue(ArtifactAnalysisLinks.of(l,id,result(7,0,4),new WorldRevision(8)).isEmpty());
        assertTrue(ArtifactAnalysisLinks.of(l,id,AnalysisResult.failed(new WorldRevision(7),"refused"),new WorldRevision(7)).isEmpty());
        l.observe(p(5),X);
        assertTrue(ArtifactAnalysisLinks.of(l,id,result(7,0,4),new WorldRevision(7)).isEmpty());
    }
    private static AnalysisResult result(int revision,int left,int right){
        return new AnalysisResult(new WorldRevision(revision),true,false,"",999,left,"member",1,0,0,Double.NaN,
                BucklingState.UNKNOWN,List.of(member(left,0,1,2),member(right,2,3,4),member(100,100,101)),
                List.of(),List.of(),false,false);
    }
    private static MemberSnapshot member(int id,int... cells){
        return new MemberSnapshot(id,"steel","steel_rect_200x400",2000,999,GoverningFibre.NONE,-1,
                new EndForces(0,0,0,0,0,0),new EndForces(0,0,0,0,0,0),Arrays.stream(cells).mapToObj(ConstructionLedgerTest::p).toList(),
                List.of(),Optional.empty(),false,Optional.empty());
    }
}
