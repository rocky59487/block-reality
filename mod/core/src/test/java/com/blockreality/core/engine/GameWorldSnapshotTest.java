package com.blockreality.core.engine;

import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.*;
import com.blockreality.core.json.JsonValue;
import java.util.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameWorldSnapshotTest {
    private static final BlockKey ORIGIN = new BlockKey(0,0,0);
    private static final BsiVocabulary VOCAB = new BsiVocabulary(1,
            Map.of(17,"steel",3,"ground_rigid"),Map.of(91,"steel_rect_200x400"));
    private static GameWorldSnapshot.Cell cell(BlockKey p) {
        return GameWorldSnapshot.Cell.of(p,"steel","steel_rect_200x400",0);
    }
    private static GameWorldSnapshot snapshot(List<GameWorldSnapshot.Cell> cells, List<BlockKey> ground) {
        return new GameWorldSnapshot(new WorldRevision(7),cells,ground,List.of());
    }

    @Test void preservesDeclaredAxesRotationJointAndSiValues() {
        for (int axis=0;axis<3;axis++) for (int rotation=0;rotation<4;rotation++) for(int joint=0;joint<2;joint++) {
            var c=new GameWorldSnapshot.Cell(ORIGIN,"steel","steel_rect_200x400",axis,joint,rotation,.625,.875);
            var force=new BsiRecords.Load(0,0,0,-0.0,-20000.125,137.25);
            var snapshot=new GameWorldSnapshot(new WorldRevision(9007199254740993L),List.of(c),List.of(),List.of(force));
            var block=snapshot.blocks(VOCAB).get(0);
            assertEquals(17,block.mat()); assertEquals(91,block.sect());
            assertEquals(axis,block.axis()); assertEquals(rotation,block.axisRot()); assertEquals(joint,block.joint());
            assertEquals(.625,block.fill()); assertEquals(.875,block.strength());
            assertEquals(9007199254740993L,snapshot.revision().value());
            assertEquals(Double.doubleToRawLongBits(-0.0),Double.doubleToRawLongBits(snapshot.loads().get(0).fx()));
            assertEquals(force,snapshot.loads().get(0));
        }
    }

    @Test void includesAllSixObservedContactsOnceInCanonicalOrder() {
        var observations=new ArrayList<>(GameWorldSnapshot.neighbours(ORIGIN));
        observations.add(new BlockKey(0,-1,0)); Collections.reverse(observations);
        var s=snapshot(List.of(cell(ORIGIN)),observations);
        var blocks=s.blocks(VOCAB);
        assertEquals(7,blocks.size()); assertEquals(6,s.ground().size());
        assertEquals(List.of(new BlockKey(-1,0,0),new BlockKey(0,-1,0),new BlockKey(0,0,-1),
                new BlockKey(0,0,1),new BlockKey(0,1,0),new BlockKey(1,0,0)),s.ground());
        assertEquals(6,blocks.stream().filter(b->b.mat()==3&&b.sect()==-1).count());
        assertEquals(1,snapshot(List.of(cell(ORIGIN)),List.of()).blocks(VOCAB).size(),"no invented ground");
    }

    @Test void contactsCommuteWithMirrorAndRotation() {
        var p=new BlockKey(2,3,5); var contacts=List.of(new BlockKey(1,3,5),new BlockKey(2,4,5),new BlockKey(2,3,6));
        for (UnaryOperator<BlockKey> transform:List.<UnaryOperator<BlockKey>>of(
                q->new BlockKey(-q.x(),q.y(),q.z()),q->new BlockKey(q.z(),q.y(),-q.x()),q->new BlockKey(q.y(),q.z(),q.x()))) {
            var result=snapshot(List.of(cell(transform.apply(p))),contacts.stream().map(transform).toList());
            assertEquals(new HashSet<>(contacts.stream().map(transform).toList()),new HashSet<>(result.ground()));
        }
    }

    @Test void rejectsUnknownOrUnobservedInputsBeforeSending() {
        assertThrows(IllegalArgumentException.class,()->snapshot(List.of(cell(ORIGIN),cell(ORIGIN)),List.of()));
        assertThrows(IllegalArgumentException.class,()->snapshot(List.of(cell(ORIGIN)),List.of(ORIGIN)));
        assertThrows(IllegalArgumentException.class,()->snapshot(List.of(cell(ORIGIN)),List.of(new BlockKey(1,1,0))));
        assertThrows(IllegalArgumentException.class,()->GameWorldSnapshot.Cell.of(ORIGIN,"unknown","unknown",0));
        assertThrows(IllegalArgumentException.class,()->GameWorldSnapshot.Cell.of(ORIGIN,"steel","steel_rect_200x400",3));
        assertThrows(IllegalArgumentException.class,()->new GameWorldSnapshot.Cell(ORIGIN,"steel","steel_rect_200x400",0,0,4,1,1));
        assertThrows(IllegalArgumentException.class,()->new GameWorldSnapshot.Cell(ORIGIN,"steel","steel_rect_200x400",0,0,0,Double.NaN,1));
        assertThrows(IllegalArgumentException.class,()->new GameWorldSnapshot(new WorldRevision(7),List.of(cell(ORIGIN)),List.of(),List.of(new BsiRecords.Load(0,0,0,Double.NaN,0,0))));
        assertThrows(IllegalArgumentException.class,()->new GameWorldSnapshot(new WorldRevision(7),List.of(cell(ORIGIN)),List.of(),List.of(new BsiRecords.Load(1,0,0,1,0,0))));
        assertThrows(IllegalArgumentException.class,()->snapshot(List.of(cell(ORIGIN)),List.of()).blocks(new BsiVocabulary(1,Map.of(0,"other"),Map.of())));
    }

    @Test void snapshotOwnsItsCollections() {
        var cells=new ArrayList<>(List.of(cell(ORIGIN))); var ground=new ArrayList<>(List.of(new BlockKey(0,-1,0)));
        var loads=new ArrayList<>(List.of(new BsiRecords.Load(0,0,0,0,-1,0)));
        var s=new GameWorldSnapshot(new WorldRevision(7),cells,ground,loads);
        cells.clear();ground.clear();loads.clear();
        assertEquals(1,s.cells().size());assertEquals(1,s.ground().size());assertEquals(1,s.loads().size());
        assertThrows(UnsupportedOperationException.class,()->s.cells().clear());
        assertThrows(UnsupportedOperationException.class,()->s.ground().clear());
        assertThrows(UnsupportedOperationException.class,()->s.loads().clear());
        assertThrows(UnsupportedOperationException.class,()->s.blocks(VOCAB).clear());
    }

    @Test void shippedVocabularyUsesSiAndExplicitProductRoles() {
        var doc=JsonValue.parse(GameVocabulary.declaration());
        var materials=new HashMap<String,JsonValue>();
        for(var m:doc.arr("materials")) assertNull(materials.put(m.str("name",""),m));
        var sections=new HashMap<String,JsonValue>();
        for(var s:doc.arr("sections")) {assertNull(sections.put(s.str("name",""),s));for(String computed:List.of("A","Iy","Iz","J"))assertFalse(s.has(computed));}
        var steel=materials.get("steel");assertEquals(2e11,steel.num("E",0));assertEquals(7850,steel.num("rho",0));
        assertEquals(350e6,steel.objField("allow").num("sigmaC",0));assertFalse(steel.bool("eulerBernoulli",false));
        assertEquals(.2,sections.get("steel_rect_200x400").arr("p").get(0).asNum(0));
        assertEquals(.4,sections.get("steel_rect_200x400").arr("p").get(1).asNum(0));
        assertEquals(.025,sections.get("rebar_round_d25").arr("p").get(0).asNum(0));
        for(var pair:Map.of("concrete_slab_200",.2,"concrete_slab_150",.15,"steel_plate_20",.02).entrySet()) {
            assertEquals("panel",materials.get(pair.getKey()).str("role",""));
            assertEquals(pair.getValue(),materials.get(pair.getKey()).num("shellThickness",0));
        }
        for(String material:List.of("concrete","brick"))assertEquals("monolith",materials.get(material).str("role",""));
        for(var entry:GameVocabulary.products().entrySet()) {
            var b=entry.getValue();assertTrue(materials.containsKey(b.material()),entry.toString());
            if(b.section()!=null)assertTrue(sections.containsKey(b.section()),entry.toString());
        }
        assertEquals("fixAll",materials.get("ground_rigid").str("supportKind",""));
    }
}
