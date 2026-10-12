package com.blockreality.impl;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The placement controls must sit on a fixed anchor: bottom is recomputed from wrapped
    text rows every tick, and anchoring the buttons to it makes them teleport out from
    under the pointer whenever an offer arrives, expires, or a refresh starts. */
class PlacementScreenLayoutTest {
    @Test void controlsNeverAnchorToFloatingContentBottom() throws Exception {
        String screen=Files.readString(Path.of("src/main/java/com/blockreality/impl/client/PlacementScreen.java"));
        assertFalse(screen.contains("int controls=bottom-controlHeight"),
            "controls derived from bottom teleport buttons when row count changes");
        assertTrue(screen.contains("int controls=Math.min(height-6-controlHeight-4,top+"),
            "controls must keep a fixed anchor");
        assertTrue(screen.contains("Math.max(controls+controlHeight+4,"),
            "panel bottom must still cover the control block");
    }
}
