package com.blockreality.impl.net;

import com.blockreality.api.*;
import com.blockreality.core.bsi.*;
import com.blockreality.core.engine.InProcessEngine;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeResultPacketTest {
    @Test void sharedContractResourcesDescribeThisBuild() throws Exception {
        assertTrue(BsiContract.available());
        for(String name:List.of("CONTRACT_SHA256","bsi.schema.json"))
            try(var resource=BsiContract.class.getResourceAsStream("/blockreality/contract/"+name)) {
                assertNotNull(resource,name);
                assertArrayEquals(Files.readAllBytes(Path.of("../contract",name)),resource.readAllBytes(),name);
            }
    }
    @Test void realNativeAnalysisReachesThePacketWithTheSameFlagsAndDoubles() {
        String path=System.getenv("BR_ENGINE");
        Assumptions.assumeTrue(path!=null&&!path.isBlank()&&Files.isRegularFile(Path.of(path)),"real native library required");
        try(var engine=InProcessEngine.open(Path.of(path),4)) {
            assertEquals(InProcessEngine.Status.READY,engine.status(),()->String.valueOf(engine.disabledReason()));
            assertTrue(engine.declareVocabulary("""
                {"version":1,"materials":[{"name":"steel","role":"member","model":"isotropic",
                 "E":2e11,"nu":0.3,"rho":7850,"allow":{"sigmaC":2.5e8,"sigmaT":2.5e8,"tau":1.45e8},"defaultSection":"rect"},
                 {"name":"ground","role":"support","supportKind":"fixAll"}],
                 "sections":[{"name":"rect","kind":"rect","p":[0.2,0.4]}]}
                """));
            var world=new ArrayList<BsiRecords.Block>();world.add(BsiRecords.Block.of(-1,0,0,1,-1,0));
            for(int i=0;i<=4;i++)world.add(BsiRecords.Block.of(i,0,0,0,-1,0));
            assertTrue(engine.declareWorld(73,world));
            for(var storage:BsiHeaders.Storage.values())for(double force:new double[]{0,-1e8}) {
                var result=engine.analyze(new WorldRevision(73),true,new double[]{0,-9.81,0},
                        List.of(new BsiRecords.Load(2,0,0,0,force,0)),4,Map.of(0,"steel"),Map.of(0,"rect"),storage);
                assertTrue(result.ok(),result.diagnostic());assertEquals(force<0,result.overCapacity());
                var bytes=new FriendlyByteBuf(Unpooled.buffer());
                try {
                    StressResultPacket.encode(StressResultPacket.of(result,"minecraft:overworld",false),bytes);
                    var decoded=StressResultPacket.decode(bytes);assertTrue(decoded.valid(),decoded.invalidReason());
                    assertEquals(result.overCapacity(),decoded.overCapacity());assertEquals(result.bucklingCritical(),decoded.bucklingCritical());
                    assertEquals(result.maxDc(),decoded.maxDc());assertEquals(result.bucklingState(),decoded.bucklingState());
                    assertEquals(result.members().get(0).stations(),decoded.members().get(0).stations());
                } finally { bytes.release(); }
            }
        }
    }
    @Test void failedAnalysisCannotBeEncodedAsAHealthyEmptyResult() {
        var packet=StressResultPacket.of(AnalysisResult.failed(new WorldRevision(74),"stale reply"),"minecraft:overworld",false);
        assertFalse(packet.valid());var bytes=new FriendlyByteBuf(Unpooled.buffer());
        try { assertThrows(IllegalArgumentException.class,()->StressResultPacket.encode(packet,bytes));assertEquals(0,bytes.readableBytes()); }
        finally { bytes.release(); }
    }
}
