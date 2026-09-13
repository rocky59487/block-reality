package com.blockreality.core.bsi;

import com.blockreality.core.json.JsonValue;
import java.nio.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class BsiFractureTest {
    private static final FractureCases.Case L=FractureCases.named("L");
    @TestFactory Stream<DynamicTest> oracleReceiptDecoding() {
        return FractureCases.all().stream().filter(c -> !c.refused()).map(c -> DynamicTest.dynamicTest(c.name(),() -> {
            var r=c.receipt(); assertEquals(c.world().owners().size(),r.cells().size());
            byte[] frame=r.frame(); assertArrayEquals(c.payload(),BsiFrame.decode(frame,frame.length).payload());
            assertEquals(c.world().stamp().revision()+1,c.world().remaining(r).stamp().revision());
        }));
    }
    @Test void completeReceiptIsImmutableAndRetainsOrphanedGround() {
        var r=L.receipt(); byte[] frame=r.frame(); frame[frame.length-1]^=1;
        assertArrayEquals(L.payload(),BsiFrame.decode(r.frame(),r.frame().length).payload());
        assertThrows(UnsupportedOperationException.class,() -> r.cells().clear());
        assertThrows(UnsupportedOperationException.class,() -> r.fragments().get(0).parents().clear());
        assertThrows(UnsupportedOperationException.class,() -> r.events().get(0).cells().clear());
        var after=L.world().remaining(r); assertEquals(1,after.blocks().size()); assertTrue(after.owners().isEmpty());
        assertEquals(-1,after.blocks().get(0).y());
    }
    @Test void planHashBindsSourceIdentityOptionsAndCanonicalOrder() {
        var w=L.world(); var blocks=new ArrayList<>(w.blocks()); var owners=new ArrayList<>(w.owners());
        Collections.reverse(blocks); Collections.reverse(owners);
        assertEquals(w.planHash(L.options()),new BsiFracture.World(w.stamp(),w.artifactNamespace(),blocks,owners).planHash(L.options()));
        var first=owners.get(0); owners.set(0,new BsiFracture.Owner(first.position(),first.artifact()+1));
        assertNotEquals(w.planHash(L.options()),new BsiFracture.World(w.stamp(),w.artifactNamespace(),blocks,owners).planHash(L.options()));
        assertNotEquals(w.planHash(L.options()),new BsiFracture.World(new BsiFracture.Stamp(new UUID(19,20),w.stamp().revision()),w.artifactNamespace(),blocks,owners).planHash(L.options()));
        assertNotEquals(w.planHash(L.options()),new BsiFracture.World(w.stamp(),new UUID(21,22),w.blocks(),w.owners()).planHash(L.options()));
        assertNotEquals(w.planHash(L.options()),w.planHash(new BsiFracture.Options(0,-1,0,16,4)));
        assertNotEquals(w.planHash(L.options()),w.planHash(new BsiFracture.Options(0,-9.81,0,15,4)));
        assertNotEquals(w.planHash(L.options()),w.planHash(new BsiFracture.Options(0,-9.81,0,16,null)));
    }
    @Test void remainingRefusesDifferentSourceAtSameStamp() {
        var w=L.world(); var owners=new ArrayList<>(w.owners()); var first=owners.get(0);
        owners.set(0,new BsiFracture.Owner(first.position(),999));
        var other=new BsiFracture.World(w.stamp(),w.artifactNamespace(),w.blocks(),owners);
        assertThrows(IllegalArgumentException.class,() -> other.remaining(L.receipt()));
    }
    @TestFactory Stream<DynamicTest> malformedHeaders() {
        Map<String,UnaryOperator<String>> faults=new LinkedHashMap<>();
        faults.put("wrong id",s -> s.replace("\"id\":\"nfw\"","\"id\":\"other\""));
        faults.put("wrong method",s -> s.replace("bsi.fracture.prepare","bsi.fracture.finish"));
        faults.put("wrong envelope revision",s -> s.replaceFirst("\"revision\":7","\"revision\":8"));
        faults.put("wrong after",s -> s.replace("\"revision\":8","\"revision\":9"));
        faults.put("wrong namespace",s -> s.replace(BsiFracture.hex(L.world().artifactNamespace()),"1".repeat(32)));
        faults.put("wrong domain",s -> s.replace(BsiFracture.hex(L.world().stamp().domain()),"1".repeat(32)));
        faults.put("wrong request",s -> s.replace(BsiFracture.hex(L.request()),"1".repeat(32)));
        faults.put("zero context",s -> s.replace(L.receipt().token().context().toString().replace("-",""),"0".repeat(32)));
        faults.put("zero sequence",s -> s.replace("\"sequence\":\"0000000000000001\"","\"sequence\":\"0000000000000000\""));
        faults.put("remaining count",s -> s.replace("\"remainingBlocks\":1","\"remainingBlocks\":2"));
        faults.put("steps beyond budget",s -> s.replace("\"steps\":1","\"steps\":17"));
        faults.put("unknown flags",s -> s.replace("\"flags\":0","\"flags\":4"));
        faults.put("physical stride 81",s -> s.replace("\"bytes\":240","\"bytes\":243"));
        faults.put("cell stride 143",s -> s.replace("\"bytes\":1584","\"bytes\":1573"));
        faults.put("fragment stride 95",s -> s.replace("\"bytes\":96","\"bytes\":95"));
        faults.put("duplicate key",s -> s.replace("\"steps\":1","\"steps\":1,\"steps\":1"));
        faults.put("non-JSON number",s -> s.replace("\"steps\":1","\"steps\":01"));
        faults.put("unknown section",s -> s.replace("fractureMechanism","otherMechanism"));
        return faults.entrySet().stream().map(f -> DynamicTest.dynamicTest(f.getKey(),() -> {
            String changed=f.getValue().apply(L.header()); assertNotEquals(L.header(),changed,"fault must change input");
            assertThrows(IllegalArgumentException.class,() -> BsiFractureReceipt.decode(FractureCases.response(changed,L.payload()),L.world(),L.request(),L.options(),"nfw"));
        }));
    }
    @TestFactory Stream<DynamicTest> malformedPayloads() {
        var r=FractureCases.response(L.header(),L.payload());
        int fragment=r.sections().get("fractureFragments").offset(),parents=r.sections().get("fractureParents").offset();
        int events=r.sections().get("fractureEvents").offset(),indices=r.sections().get("fractureEventCells").offset();
        Map<String,Consumer<ByteBuffer>> faults=new LinkedHashMap<>();
        faults.put("source bytes",b -> b.putInt(240,1));
        faults.put("negative artifact",b -> b.putLong(288,-1));
        faults.put("unknown group",b -> b.putInt(284,4));
        faults.put("reserved cell",b -> b.putInt(300,1));
        faults.put("unrepresented broken",b -> b.putInt(296,1));
        faults.put("bad plane",b -> b.putInt(280,3));
        faults.put("nonfinite physical",b -> b.putDouble(0,Double.NaN));
        faults.put("negative tensor",b -> b.putDouble(32,-1));
        faults.put("fragment parent start",b -> b.putInt(fragment+80,1));
        faults.put("fragment flags",b -> b.putInt(fragment+88,8));
        faults.put("foreign parent",b -> b.putLong(parents,77));
        faults.put("duplicate parent",b -> b.putLong(parents+8,11));
        faults.put("event duplicate cell",b -> b.putInt(indices+4,0));
        faults.put("event fragment cell",b -> b.putInt(indices,2));
        faults.put("invalid capacity face",b -> b.put(events+16,(byte)6));
        faults.put("event reserved",b -> b.put(events+17,(byte)1));
        faults.put("nonfinite utilization",b -> b.putDouble(events+8,Double.POSITIVE_INFINITY));
        return faults.entrySet().stream().map(f -> DynamicTest.dynamicTest(f.getKey(),() -> {
            byte[] bytes=L.payload().clone(); f.getValue().accept(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
            assertFalse(Arrays.equals(bytes,L.payload()),"fault must change input");
            assertThrows(IllegalArgumentException.class,() -> BsiFractureReceipt.decode(FractureCases.response(L.header(),bytes),L.world(),L.request(),L.options(),"nfw"));
        }));
    }
    @Test void strictJsonRetainsExactI64AndRejectsNonJsonWhitespace() {
        assertEquals(Long.MAX_VALUE,JsonValue.parseStrict("{\"n\":9223372036854775807}").exactI64("n"));
        assertTrue(JsonValue.parseStrict("{\"n\":1,\"n\":1}").isNull());
        assertTrue(JsonValue.parseStrict("{\"n\":1}\u000b").isNull());
        assertTrue(JsonValue.parseStrict("{\"n\":\"a\nb\"}").isNull());
    }
}
