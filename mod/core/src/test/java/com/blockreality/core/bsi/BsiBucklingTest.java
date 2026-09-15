package com.blockreality.core.bsi;

import com.blockreality.api.*;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BsiBucklingTest {
    private static final String[] STATES={"computed","no-positive-eigenvalue","not-eligible","not-eligible-scale","disabled-by-request","solver-failed"};
    private static BsiResponse reply(BsiAnalysisResultTest.Fixture f){return BsiResponse.of(new BsiFrame.Decoded(0,f.header(),f.payload()));}
    private static BsiAnalysisResultTest.Fixture summary(BsiAnalysisResultTest.Fixture f,int first,int expected){return new BsiAnalysisResultTest.Fixture(f.header().replace("\"state\":\""+STATES[first]+"\"","\"state\":\""+STATES[expected]+"\""),f.payload());}
    @Test void frozen125WorldRowsPreserveEveryIsland() throws Exception {
        try(var stream=getClass().getResourceAsStream("/bsi-buckling-truth.csv")){
            assertNotNull(stream);var rows=new String(stream.readAllBytes(),StandardCharsets.UTF_8).lines().toList();assertEquals(125,rows.size());
            for(var line:rows){int[] v=Arrays.stream(line.split(",")).mapToInt(Integer::parseInt).toArray();
                var f=summary(BsiAnalysisResultTest.fixture(.25,0,.75,v[0],v[1],v[2]),v[0],v[3]);var r=BsiBuckling.decode(reply(f),3);
                assertEquals(STATES[v[3]],r.state().wire(),line);assertEquals(v[3]==0?.75:0,r.factor(),line);
                assertEquals(List.of(v[0],v[1],v[2]),r.islands().stream().map(BsiResponse.Buckling::state).toList());
                assertThrows(UnsupportedOperationException.class,()->r.islands().clear());
            }
        }
    }
    @Test void localCriticalSurvivesWorldRefusalWithoutReclassification(){
        // The engine owns the flag. A factor >1 with a set bit deliberately detects Java reclassification.
        var f=summary(BsiAnalysisResultTest.fixture(.25,4,2,0,2),0,2);
        var r=BsiAnalysisResult.decode(reply(f),new WorldRevision(17),Map.of(5,"steel"),Map.of(6,"rect"),new BsiHeaders.Precision(BsiHeaders.Tier.COMMIT,BsiHeaders.Storage.F64));
        assertTrue(r.ok(),r.diagnostic());assertTrue(r.bucklingCritical());assertEquals(0,r.bucklingFactor());assertEquals(BucklingState.NOT_ELIGIBLE,r.bucklingState());
        var bad=BsiAnalysisResultTest.fixture(.25,4,2,2,0); // flag belongs to refused island 0
        assertFalse(BsiAnalysisResult.decode(reply(bad),new WorldRevision(17),Map.of(5,"steel"),Map.of(6,"rect"),new BsiHeaders.Precision(BsiHeaders.Tier.COMMIT,BsiHeaders.Storage.F64)).ok());
    }
    @Test void malformedIdentityKindStateAndFactorsAreRefused(){
        var base=BsiAnalysisResultTest.fixture(.25,0,.75,0,1);
        for(int change=0;change<11;change++){
            byte[] payload=base.payload().clone();var b=ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            switch(change){case 0->b.putInt(112,0);case 1->b.putInt(96,-1);case 2->b.put(100,(byte)255);case 3->b.put(101,(byte)2);case 4->b.put(116,(byte)4);
                case 5->b.putDouble(104,0);case 6->b.putDouble(104,-1);case 7->b.putDouble(104,Double.POSITIVE_INFINITY);case 8->b.putDouble(104,Double.NaN);case 9->b.putDouble(120,1);case 10->b.putShort(102,(short)1);}
            assertThrows(IllegalArgumentException.class,()->BsiBuckling.decode(reply(new BsiAnalysisResultTest.Fixture(base.header(),payload)),2),"change "+change);
        }
        assertThrows(IllegalArgumentException.class,()->BsiBuckling.decode(reply(base),1));
        assertThrows(IllegalArgumentException.class,()->BsiBuckling.decode(reply(summary(base,0,2)),2));
    }
    @Test void worldMinimumOnlyExistsForComputedSummary(){
        var f=BsiAnalysisResultTest.fixture(.25,0,2,0,0,1);var bytes=f.payload().clone();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putDouble(120,.5);
        assertEquals(.5,BsiBuckling.decode(reply(new BsiAnalysisResultTest.Fixture(f.header(),bytes)),3).factor());
    }
    @Test void eigenIsAdditiveAndBudgetIsBounded(){
        var precision=new BsiHeaders.Precision(BsiHeaders.Tier.COMMIT,BsiHeaders.Storage.F64);
        String old=BsiHeaders.solve("id",17,false,null,0,1,List.of("members"),precision);
        assertEquals("{\"bsi\":1,\"kind\":\"request\",\"id\":\"id\",\"method\":\"bsi.solve\",\"revision\":17,\"body\":{\"selfWeight\":false,\"numThreads\":1,\"precision\":{\"tier\":\"commit\",\"storage\":\"f64\"},\"include\":[\"members\"]}}",old);
        assertEquals(old,BsiHeaders.solve("id",17,false,null,0,1,List.of("members"),precision,null));
        for(int budget:new int[]{0,1,Integer.MAX_VALUE})assertTrue(BsiHeaders.solve("id",17,false,null,0,1,List.of("members"),precision,new BsiHeaders.EigenBuckling(budget)).contains("\"buckling\":{\"mode\":\"eigen\",\"budgetDof\":"+budget+"}"));
        assertThrows(IllegalArgumentException.class,()->new BsiHeaders.EigenBuckling(-1));
    }
}
