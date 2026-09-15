package com.blockreality.core.engine;

import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.*;
import com.blockreality.core.world.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameFractureIdentityTest {
    final UUID domain=new UUID(10,11);
    final BlockKey origin=new BlockKey(0,0,0);
    final BsiVocabulary vocab=new BsiVocabulary(1,Map.of(17,"steel",3,"ground_rigid"),Map.of(91,"steel_rect_200x400"));
    GameWorldSnapshot snapshot(List<BsiRecords.Load> loads) {
        return new GameWorldSnapshot(new WorldRevision(9007199254740993L),
                List.of(GameWorldSnapshot.Cell.of(origin,"steel","steel_rect_200x400",0)),List.of(new BlockKey(-1,0,0)),loads);
    }
    ConstructionLedger ledger() {
        var ledger=new ConstructionLedger(); ledger.observe(origin,new ConstructionDeclaration("steel","steel_rect_200x400",0));
        assertTrue(ledger.publish(ConstructionLedger.reconcile(ledger.work()))); return ledger;
    }
    @Test void preservesExactRevisionNamespaceAndEngineAssignedIds() {
        var ledger=ledger(); var world=snapshot(List.of()).identified(domain,ledger.graph(),vocab);
        assertEquals(9007199254740993L,world.stamp().revision()); assertEquals(domain,world.stamp().domain());
        assertEquals(ledger.graph().namespace(),world.artifactNamespace()); assertEquals(2,world.blocks().size());
        assertEquals(17,world.blocks().get(1).mat()); assertEquals(91,world.blocks().get(1).sect());
        assertEquals(List.of(new BsiFracture.Owner(origin,ledger.graph().owners().get(origin))),world.owners());
    }
    @Test void missingForeignAndDuplicateOwnersAreRefused() {
        var snapshot=snapshot(List.of()); var empty=new ConstructionLedger();
        assertThrows(IllegalArgumentException.class,() -> snapshot.identified(domain,empty.graph(),vocab));
        var ledger=ledger(); ledger.observe(new BlockKey(3,0,0),new ConstructionDeclaration("steel","steel_rect_200x400",0));
        ledger.publish(ConstructionLedger.reconcile(ledger.work()));
        assertThrows(IllegalArgumentException.class,() -> snapshot.identified(domain,ledger.graph(),vocab));
        var world=snapshot.identified(domain,ledger().graph(),vocab); var owner=world.owners().get(0);
        assertThrows(IllegalArgumentException.class,() -> new BsiFracture.World(world.stamp(),world.artifactNamespace(),world.blocks(),List.of(owner,owner)));
        assertThrows(IllegalArgumentException.class,() -> new BsiFracture.Owner(origin,0));
    }
    @Test void externalLoadsAndZeroIdentitiesCannotBeSilentlyOmitted() {
        var loaded=snapshot(List.of(new BsiRecords.Load(0,0,0,0,-12,0))); var ledger=ledger();
        assertThrows(IllegalArgumentException.class,() -> loaded.identified(domain,ledger.graph(),vocab));
        assertThrows(IllegalArgumentException.class,() -> snapshot(List.of()).identified(new UUID(0,0),ledger.graph(),vocab));
    }
    @Test void staleProductOrDirectionAtSamePositionCannotOwnCurrentSource() {
        assertAll(() -> rejectStale(new ConstructionDeclaration("concrete","concrete_rect_400x600",0)),
                () -> rejectStale(new ConstructionDeclaration("steel","steel_rect_200x400",1)));
    }
    void rejectStale(ConstructionDeclaration declaration) {
        var ledger=new ConstructionLedger(); ledger.observe(origin,declaration); ledger.publish(ConstructionLedger.reconcile(ledger.work()));
        assertThrows(IllegalArgumentException.class,() -> snapshot(List.of()).identified(domain,ledger.graph(),vocab));
    }
}
