package com.blockreality.core.bsi;

import com.blockreality.api.*;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BsiAnalysisResultTest {
    private static final BsiHeaders.Precision COMMIT = new BsiHeaders.Precision(BsiHeaders.Tier.COMMIT, BsiHeaders.Storage.F64);
    private static final List<String> STATES = List.of("computed","no-positive-eigenvalue","not-eligible","not-eligible-scale","disabled-by-request","solver-failed");
    record Fixture(String header, byte[] payload) { }
    private static ByteBuffer bytes(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }

    /** Independent LE fixture; values intentionally disagree with flags to expose reclassification. */
    static Fixture fixture(double dc, int flags, double factor, int... states) {
        return fixtureFlags(dc, List.of(flags), factor, states);
    }
    private static Fixture fixtureFlags(double dc, List<Integer> flags, double factor, int... states) {
        var sections = new LinkedHashMap<String,byte[]>();
        var b=bytes(24*flags.size());
        for(int i=0;i<flags.size();i++) b.putDouble(i*24,dc).putInt(i*24+8,0).putInt(i*24+12,7).put(i*24+16,(byte)5).put(i*24+17,(byte)1).put(i*24+18,flags.get(i).byteValue());
        sections.put("blocks",b.array()); sections.put("equilibrium",new byte[56]);
        b=bytes(16);b.putInt(8,1).put(12,(byte)1);sections.put("quality",b.array());
        b=bytes(16*states.length);
        for(int i=0;i<states.length;i++)b.putInt(16*i,i).put(16*i+4,(byte)states[i]).put(16*i+5,(byte)(states[0]==4?0:1))
                .putDouble(16*i+8,states[i]==0?factor:Double.NaN);
        sections.put("buckling",b.array());
        b=bytes(160);b.putInt(0,7).putInt(12,flags.size()).putInt(20,1).putInt(24,5).putInt(28,6).putDouble(32,2)
                .putDouble(136,dc).put(152,(byte)5).put(153,(byte)2).put(154,(byte)(flags.get(0)&1));
        sections.put("members",b.array());b=bytes(12*flags.size());
        for(int i=0;i<flags.size();i++)b.putInt(i*12,i);sections.put("memberBlocks",b.array());
        b=bytes(88);b.putDouble(72,Double.NaN).putDouble(80,Double.NaN);sections.put("stations",b.array());
        b=bytes(16);b.put(8,(byte)1).put(9,(byte)1);sections.put("stationIdentity",b.array());
        b=bytes(168);b.putInt(0,7);double[] g={0,0,0,1,0,0,0,1,0,0,0,1,.2,-.2,0,0,0,0,.1,-.1};
        for(int i=0;i<g.length;i++)b.putDouble(8+8*i,g[i]);sections.put("memberGeometry",b.array());
        sections.put("facets",new byte[0]);sections.put("facetSurfaces",new byte[0]);sections.put("facetBlocks",new byte[0]);
        var stream=new ByteArrayOutputStream();var dir=new ArrayList<String>();
        for(var e:sections.entrySet()) {
            int count=switch(e.getKey()){case "blocks","memberBlocks" -> flags.size();case "buckling" -> states.length;case "facets","facetSurfaces","facetBlocks" -> 0;default -> 1;};
            dir.add("{\"name\":\""+e.getKey()+"\",\"offset\":"+stream.size()+",\"bytes\":"+e.getValue().length+",\"count\":"+count+"}");stream.writeBytes(e.getValue());
        }
        return new Fixture("{\"kind\":\"response\",\"method\":\"bsi.solve\",\"status\":\"ok\",\"revision\":17,"
                +"\"diag\":{\"blocks\":"+flags.size()+",\"members\":1,\"facets\":0,\"islands\":"+states.length+",\"singularIslands\":0},"
                +"\"buckling\":{\"kind\":\""+(states[0]==4?"none":"eigen")+"\",\"state\":\""+STATES.get(states[0])+"\"},"
                +"\"unassigned\":[],\"sections\":["+String.join(",",dir)+"]}",stream.toByteArray());
    }
    private static AnalysisResult decode(Fixture f) { return decode(f,17,COMMIT); }
    private static AnalysisResult decode(Fixture f,long rev,BsiHeaders.Precision precision) {
        return BsiAnalysisResult.decode(BsiResponse.of(new BsiFrame.Decoded(0,f.header(),f.payload())),new WorldRevision(rev),Map.of(5,"steel"),Map.of(6,"rect"),precision);
    }
    private static Fixture header(Fixture f,String from,String to){return new Fixture(f.header().replace(from,to),f.payload());}
    private static void failed(AnalysisResult r,String why) {
        assertFalse(r.ok(),why); assertTrue(r.members().isEmpty());assertTrue(r.shells().isEmpty());
        assertFalse(r.overCapacity());assertFalse(r.bucklingCritical());assertEquals(0,r.maxDc());
    }
    @Test void globalFlagsAreIndependentOfNumericThresholds() {
        var a=decode(fixture(1,1,1,0)); assertTrue(a.ok(),a.diagnostic());assertTrue(a.overCapacity());assertFalse(a.bucklingCritical());
        assertEquals(1,a.maxDc());assertEquals(1,a.bucklingFactor());assertEquals(7,a.governing());assertEquals("member",a.governingKind());
        var b=decode(fixture(Math.nextUp(1.),4,2,0)); assertTrue(b.ok(),b.diagnostic());assertFalse(b.overCapacity());assertTrue(b.bucklingCritical());
        assertEquals(Math.nextUp(1.),b.maxDc());assertEquals(2,b.bucklingFactor());
    }
    @Test void allHomogeneousStatesRetainTheirMeaning() {
        for(int state=0;state<6;state++) {
            var r=decode(fixture(.25,0,.75,state));assertTrue(r.ok(),r.diagnostic());
            assertEquals(STATES.get(state),r.bucklingState().wire());assertEquals(state==0?.75:0,r.bucklingFactor());
            assertFalse(r.bucklingCritical());
        }
    }
    @Test void allBlocksContributeEvenWhenTheLastOneIsClear() {
        var r=decode(fixtureFlags(.5,List.of(5,0),2,0));assertTrue(r.ok(),r.diagnostic());
        assertTrue(r.overCapacity());assertTrue(r.bucklingCritical());
    }
    @Test void stalePartialAndErrorRepliesCannotReuseSuccessfulFlags() {
        var f=fixture(1,5,.5,0);assertTrue(decode(f).ok());
        var stale=decode(f,18,COMMIT);failed(stale,"stale");assertEquals(new WorldRevision(18),stale.revision());
        failed(decode(header(f,"\"ok\"","\"partial\"")),"partial");
        failed(decode(header(f,"\"response\"","\"error\"")),"error");
        failed(decode(header(f,"\"revision\":17","\"revision\":17.0")),"inexact revision");
        assertTrue(decode(f).overCapacity());
    }
    @Test void incompleteQualityOrDisplayCannotBecomeCommitResults() {
        var f=fixture(.5,0,0,4);
        failed(decode(f,17,new BsiHeaders.Precision(BsiHeaders.Tier.DISPLAY,BsiHeaders.Storage.F64)),"display");
        for(int offset:new int[]{92,95}) {
            byte[] b=f.payload().clone(); b[offset]=(byte)(offset==92?0:1);failed(decode(new Fixture(f.header(),b)),"quality");
        }
        failed(decode(f,17,new BsiHeaders.Precision(BsiHeaders.Tier.COMMIT,BsiHeaders.Storage.F32)),"storage");
    }
    @Test void missingSectionsAndOwnersAreRefused() {
        var f=fixture(.5,0,0,4);
        failed(decode(header(f,"\"stationIdentity\"","\"x-identity\"")),"identity missing");
        byte[] b=f.payload().clone();ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(12,99);
        failed(decode(new Fixture(f.header(),b)),"owner missing");
        failed(decode(header(f,"\"blocks\":1","\"blocks\":2")),"blocks mismatch");
    }
    @Test void malformedValuesNeverBecomeHealthyDefaults() {
        var f=fixture(.5,0,0,4);
        for(double x:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1}) {
            byte[] b=f.payload().clone();ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putDouble(0,x);
            failed(decode(new Fixture(f.header(),b)),"invalid dc");
        }
        byte[] b=f.payload().clone();b[18]=(byte)128;failed(decode(new Fixture(f.header(),b)),"unknown flag");
    }
    @Test void unsupportedOrContradictoryBucklingIsNotFlattened() {
        var f=fixture(.5,0,.5,0);
        failed(decode(header(f,"\"computed\"","\"new-state\"")),"unknown state");
        failed(decode(header(fixture(.5,0,0,4),"\"disabled-by-request\"","\"new-state\"")),"unknown disabled state");
        failed(decode(header(f,"\"eigen\"","\"screen\"")),"screen kind");
        failed(decode(fixture(.5,0,.5,0,4)),"mixed states");
        failed(decode(fixture(.5,4,0,4)),"flag without computation");
        byte[] b=f.payload().clone();ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putDouble(104,0);
        failed(decode(new Fixture(f.header(),b)),"computed zero");
    }
    // Fixed independent truth table: computed, no-positive, refused, scale, failed.
    private static final int[][] TRUTH = {{0,0,2,3,4},{0,1,2,3,4},{2,2,2,2,4},{3,3,2,3,4},{4,4,4,4,4}};
    private static final int[] ACTIVE = {0,1,2,3,5};
    private static Fixture summary(Fixture f,int oldState,int state) {
        return header(f,"\"state\":\""+STATES.get(oldState)+"\"","\"state\":\""+STATES.get(state)+"\"");
    }
    @Test void all125MixedWorldsPreserveEveryIslandAndGlobalRefusals() {
        for(int a=0;a<5;a++)for(int b=0;b<5;b++)for(int c=0;c<5;c++) {
            int[] states={ACTIVE[a],ACTIVE[b],ACTIVE[c]};int expected=ACTIVE[TRUTH[TRUTH[a][b]][c]];
            var f=summary(fixture(.5,0,.75,states),states[0],expected);
            var r=decode(f);assertTrue(r.ok(),a+"/"+b+"/"+c+": "+r.diagnostic());
            assertEquals(STATES.get(expected),r.bucklingState().wire());
            assertEquals(expected==0?.75:0,r.bucklingFactor());
            assertEquals(3,r.bucklingIslands().size());
            for(int id=0;id<3;id++) {
                var island=r.bucklingIslands().get(id);assertEquals(id,island.island());
                assertEquals(IslandBuckling.Kind.EIGEN,island.kind());
                assertEquals(STATES.get(states[id]),island.state().wire());
                if(states[id]==0)assertEquals(.75,island.factor());else assertTrue(Double.isNaN(island.factor()));
            }
            assertThrows(UnsupportedOperationException.class,()->r.bucklingIslands().clear());
        }
    }
    @Test void refusedWorldKeepsLocalCriticalWithoutRecomputingThresholds() {
        for(int refused:new int[]{2,3,5}) {
            // Intentionally factor > 1 beside supplied true bit: consumer must forward.
            var r=decode(summary(fixture(.5,4,2,0,refused),0,refused));
            assertTrue(r.ok(),r.diagnostic());assertTrue(r.bucklingCritical());
            assertEquals(0,r.bucklingFactor());assertEquals(2,r.bucklingIslands().get(0).factor());
            failed(decode(fixture(.5,4,2,refused,0)),"critical attached to refused island");
        }
    }
    @Test void computedWorldUsesSmallestSuppliedFactorAndChecksHeader() {
        var f=fixture(.5,0,2,0,1,0);byte[] raw=f.payload().clone();
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putDouble(136,.125);
        var r=decode(new Fixture(f.header(),raw));assertTrue(r.ok(),r.diagnostic());
        assertEquals(.125,r.bucklingFactor());assertEquals(2,r.bucklingIslands().get(0).factor());
        failed(decode(summary(f,0,5)),"forged failure header");
        failed(decode(fixture(.5,0,2,0,5)),"forged computed header hides failure");
    }
    @Test void malformedIslandCollectionsAndFactorsFailClosed() {
        var f=fixture(.5,0,.5,0,0);
        for(int id:new int[]{-1,0,2}) {
            byte[] raw=f.payload().clone();ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(112,id);
            failed(decode(new Fixture(f.header(),raw)),"bad island "+id);
        }
        byte[] reversed=f.payload().clone();var b=ByteBuffer.wrap(reversed).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(96,1).putInt(112,0);failed(decode(new Fixture(f.header(),reversed)),"noncanonical order");
        failed(decode(header(f,"\"islands\":2","\"islands\":3")),"missing island");
        for(int offset:new int[]{101,102,116,117}) {
            byte[] raw=f.payload().clone();raw[offset]=(byte)255;failed(decode(new Fixture(f.header(),raw)),"kind/state/reserved");
        }
        for(double x:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            byte[] raw=f.payload().clone();ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putDouble(120,x);
            failed(decode(new Fixture(f.header(),raw)),"invalid computed factor");
        }
        var refused=fixture(.5,0,0,5);
        byte[] raw=refused.payload().clone();ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putDouble(104,0);
        failed(decode(new Fixture(refused.header(),raw)),"refused factor");
        failed(decode(fixture(.5,0,.5,0,4)),"active with disabled");
        failed(decode(fixture(.5,0,.5,4,1)),"disabled with active");
    }
    @Test void emptyCollectionsDistinguishDisabledFromUnevaluated() {
        byte[] raw=new byte[72];raw[68]=1;
        var dir=new ArrayList<String>();
        dir.add("{\"name\":\"blocks\",\"offset\":0,\"bytes\":0,\"count\":0}");
        dir.add("{\"name\":\"equilibrium\",\"offset\":0,\"bytes\":56,\"count\":1}");
        dir.add("{\"name\":\"quality\",\"offset\":56,\"bytes\":16,\"count\":1}");
        for(String name:List.of("buckling","members","memberBlocks","stations","stationIdentity",
                "memberGeometry","facets","facetSurfaces","facetBlocks"))
            dir.add("{\"name\":\""+name+"\",\"offset\":72,\"bytes\":0,\"count\":0}");
        for(String mode:List.of("none","eigen")) {
            String state=mode.equals("none")?"disabled-by-request":"not-eligible";
            var f=new Fixture("{\"kind\":\"response\",\"method\":\"bsi.solve\",\"status\":\"ok\",\"revision\":17,"
                    +"\"diag\":{\"blocks\":0,\"members\":0,\"facets\":0,\"islands\":0,\"singularIslands\":0},"
                    +"\"buckling\":{\"kind\":\""+mode+"\",\"state\":\""+state+"\"},"
                    +"\"unassigned\":[],\"sections\":["+String.join(",",dir)+"]}",raw);
            var r=decode(f);assertTrue(r.ok(),r.diagnostic());assertEquals(state,r.bucklingState().wire());
            assertEquals(0,r.bucklingFactor());assertTrue(r.bucklingIslands().isEmpty());
        }
    }
    @Test void legacyConstructorRetainsItsDistinctBoundaryConvention() {
        var legacy=new AnalysisResult(
                new WorldRevision(17), true, false, "", 1, 7, "member", 1, 0, 0, 1, BucklingState.COMPUTED,
                List.of(), List.of(), List.of(), false, true);
        assertFalse(legacy.overCapacity());assertTrue(legacy.bucklingCritical());
        assertFalse(decode(fixture(1,0,1,0)).bucklingCritical());
    }
}
