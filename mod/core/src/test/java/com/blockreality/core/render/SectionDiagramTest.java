package com.blockreality.core.render;

import com.blockreality.api.Fibre;
import com.blockreality.api.StressStation;
import com.blockreality.api.geom.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionDiagramTest {

    private static StressStation station(double top, double bot) {
        return new StressStation(0, Vec3d.ZERO,
                List.of(new Fibre("TOP_Y", new Vec3d(0, 1, 0), 200, top),
                        new Fibre("BOT_Y", new Vec3d(0, -1, 0), 200, bot)),
                Math.max(0, Math.max(top, bot)), Math.max(0, Math.max(-top, -bot)), 0,
                Optional.empty(), Optional.empty());
    }

    @Test
    void aHoggingSectionHasTensionOnTop() {
        // A cantilever pushed down at its tip. The support end hogs.
        SectionDiagram d = SectionDiagram.of(station(+24.24, -24.24)).orElseThrow();
        assertEquals("br.section.tension", d.topLabelKey());
        assertEquals("br.section.compression", d.bottomLabelKey());
        assertTrue(d.isBending());
        assertEquals(0.5, d.neutralFraction().orElseThrow(), 1e-12);
    }

    @Test
    void aSaggingSectionHasCompressionOnTopAndThatIsAlsoCorrect() {
        // A beam on two supports. Opposite signs to the cantilever, same physics.
        // This is the case that makes colour alone unreadable: without the words, a
        // reader who has only ever seen the cantilever concludes the tool is inverted.
        SectionDiagram d = SectionDiagram.of(station(-18.0, +18.0)).orElseThrow();
        assertEquals("br.section.compression", d.topLabelKey());
        assertEquals("br.section.tension", d.bottomLabelKey());
        assertEquals(0.5, d.neutralFraction().orElseThrow(), 1e-12);
    }

    @Test
    void axialForceMovesTheNeutralAxisOffCentre() {
        // Bending plus tension: the zero crossing shifts towards the compression face.
        SectionDiagram d = SectionDiagram.of(station(+30.0, -10.0)).orElseThrow();
        assertEquals(0.75, d.neutralFraction().orElseThrow(), 1e-12);
        assertEquals(0.0, d.sigmaAt(0.75), 1e-12);
    }

    @Test
    void aSectionEntirelyInTensionHasNoNeutralAxis() {
        SectionDiagram d = SectionDiagram.of(station(+30.0, +10.0)).orElseThrow();
        assertFalse(d.isBending());
        assertTrue(d.neutralFraction().isEmpty());
        assertEquals("br.section.tension", d.topLabelKey());
        assertEquals("br.section.tension", d.bottomLabelKey());
    }

    @Test
    void theProfileIsLinearAcrossTheDepth() {
        SectionDiagram d = SectionDiagram.of(station(+20.0, -20.0)).orElseThrow();
        assertEquals(+20.0, d.sigmaAt(0.0), 1e-12);
        assertEquals(+10.0, d.sigmaAt(0.25), 1e-12);
        assertEquals(0.0, d.sigmaAt(0.5), 1e-12);
        assertEquals(-20.0, d.sigmaAt(1.0), 1e-12);
        assertEquals(20.0, d.peakMagnitudeMpa(), 1e-12);
    }

    @Test
    void outOfRangeDepthsAreClampedRatherThanExtrapolated() {
        SectionDiagram d = SectionDiagram.of(station(+20.0, -20.0)).orElseThrow();
        assertEquals(+20.0, d.sigmaAt(-5), 1e-12);
        assertEquals(-20.0, d.sigmaAt(5), 1e-12);
    }

    @Test
    void aStationWithoutTheYFibresProducesNothing() {
        StressStation s = new StressStation(0, Vec3d.ZERO, List.of(), 0, 0, 0,
                Optional.empty(), Optional.empty());
        assertTrue(SectionDiagram.of(s).isEmpty());
    }
}
