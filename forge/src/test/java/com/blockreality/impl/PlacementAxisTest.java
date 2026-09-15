package com.blockreality.impl;

import com.blockreality.impl.block.StructuralBlock.Axis;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlacementAxisTest {
    @Test void undeclaredLegacyBlocksNeverInventAWorldAxis() {
        assertThrows(IllegalStateException.class,Axis.UNDECLARED::wire);
        assertEquals("undeclared",Axis.UNDECLARED.getSerializedName());
        assertEquals(Axis.X,Axis.UNDECLARED.next());
    }
    @Test void rotationsAndCyclingKeepExplicitThreeAxisDeclarations() {
        assertEquals(0,Axis.X.wire());assertEquals(1,Axis.Y.wire());assertEquals(2,Axis.Z.wire());
        assertEquals(Axis.Y,Axis.X.next());assertEquals(Axis.Z,Axis.Y.next());assertEquals(Axis.X,Axis.Z.next());
        assertEquals(Axis.Z,Axis.X.quarterTurn());assertEquals(Axis.X,Axis.Z.quarterTurn());
        assertEquals(Axis.Y,Axis.Y.quarterTurn());assertEquals(Axis.UNDECLARED,Axis.UNDECLARED.quarterTurn());
    }
    @Test void gameSourceUsesNativeSnapshotsAndObservedContactFaces() throws Exception {
        String manager=Files.readString(Path.of("src/main/java/com/blockreality/impl/server/StructureManager.java"));
        assertFalse(manager.contains("SidecarClient"));assertFalse(manager.contains("SidecarLocator"));
        assertTrue(manager.contains("engine.analyze(request.world()"));
        assertTrue(manager.contains("Direction.values()"));assertTrue(manager.contains("face.getOpposite()"));
        assertTrue(manager.contains("!level.isLoaded(neighbour)"));
        assertTrue(manager.contains("Axis.UNDECLARED"));
        assertTrue(manager.contains("engine.closed() || BY_DIMENSION.get(dimension) != this"));
    }
}
