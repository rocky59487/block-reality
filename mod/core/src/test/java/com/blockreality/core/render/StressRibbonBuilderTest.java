package com.blockreality.core.render;

import com.blockreality.api.EndForces;
import com.blockreality.api.GoverningFibre;
import com.blockreality.api.Fibre;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.StressStation;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.geom.Vec3d;
import com.blockreality.api.render.StressPalette;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StressRibbonBuilderTest {

    /** A cantilever along +x: linear moment, so linear stress, zero at the free end. */
    private static MemberSnapshot cantilever(int stations, double rootSigma) {
        List<StressStation> st = new ArrayList<>();
        for (int q = 0; q < stations; q++) {
            double t = (double) q / (stations - 1);
            double sigma = rootSigma * (1 - t);
            Vec3d centre = new Vec3d(t * 4000, 64_000, 0);
            List<Fibre> f = List.of(
                    new Fibre("TOP_Y", new Vec3d(0, 1, 0), 200, +sigma),
                    new Fibre("BOT_Y", new Vec3d(0, -1, 0), 200, -sigma),
                    new Fibre("PLUS_Z", new Vec3d(0, 0, 1), 100, 0),
                    new Fibre("MINUS_Z", new Vec3d(0, 0, -1), 100, 0));
            st.add(new StressStation(t * 4000, centre, f, Math.max(0, sigma), Math.max(0, sigma), 0,
                    sigma != 0 ? Optional.of(0.0) : Optional.empty(), Optional.empty()));
        }
        return new MemberSnapshot(1, "steel", "steel_rect_200x400", 4000, 0.5, GoverningFibre.CRUSH, 0,
                EndForces.ZERO, EndForces.ZERO, List.of(new BlockKey(0, 64, 0)), st);
    }

    @Test
    void oneStripPerFibrePerSpan() {
        StressRibbon r = StressRibbonBuilder.build(cantilever(11, 24.24),
                StressPalette.SIGNED_DEFAULT, 24.24);
        assertEquals(4 * 10, r.bands().size());
    }

    @Test
    void topAndBottomFacesGetOppositeHues() {
        // The whole requirement, expressed as an assertion on drawable output.
        StressRibbon r = StressRibbonBuilder.build(cantilever(11, 24.24),
                StressPalette.SIGNED_DEFAULT, 24.24);

        StressRibbon.Band top = r.bands().stream()
                .filter(b -> b.fibre().equals("TOP_Y")).findFirst().orElseThrow();
        StressRibbon.Band bot = r.bands().stream()
                .filter(b -> b.fibre().equals("BOT_Y")).findFirst().orElseThrow();

        assertTrue(top.fromSigma() > 0, "top fibre of a downward-loaded cantilever is in tension");
        assertTrue(bot.fromSigma() < 0, "bottom fibre is in compression");
        assertTrue(top.fromColour().b() > top.fromColour().r(), "top must draw blue");
        assertTrue(bot.fromColour().r() > bot.fromColour().b(), "bottom must draw red");
    }

    @Test
    void positionsComeOutInBlocksAndOffsetToTheRightFace() {
        StressRibbon r = StressRibbonBuilder.build(cantilever(3, 10),
                StressPalette.SIGNED_DEFAULT, 10);

        StressRibbon.Band top = r.bands().stream()
                .filter(b -> b.fibre().equals("TOP_Y")).findFirst().orElseThrow();
        StressRibbon.Band bot = r.bands().stream()
                .filter(b -> b.fibre().equals("BOT_Y")).findFirst().orElseThrow();

        // Centroid at y = 64000 mm, fibre 200 mm above and below -> 64.2 and 63.8 blocks.
        assertEquals(64.2, top.from().y(), 1e-12);
        assertEquals(63.8, bot.from().y(), 1e-12);
        assertEquals(0.0, top.from().x(), 1e-12);
        assertEquals(2.0, top.to().x(), 1e-12);
    }

    @Test
    void stressFallsOffAlongTheMemberInsteadOfBeingOneFlatColour() {
        StressRibbon r = StressRibbonBuilder.build(cantilever(11, 24.24),
                StressPalette.SIGNED_DEFAULT, 24.24);
        List<StressRibbon.Band> top = r.bands().stream()
                .filter(b -> b.fibre().equals("TOP_Y")).toList();

        double prev = Double.MAX_VALUE;
        for (StressRibbon.Band b : top) {
            assertTrue(b.fromSigma() < prev + 1e-9, "stress must not rise towards the free end");
            prev = b.fromSigma();
        }
        assertEquals(0.0, top.get(top.size() - 1).toSigma(), 1e-9);
        assertFalse(top.get(0).fromColour().equals(top.get(top.size() - 1).fromColour()),
                "root and tip must not draw the same colour");
    }

    @Test
    void neutralAxisIsOmittedWhereThereIsNone() {
        // The tip station of this cantilever has zero stress everywhere, so no neutral
        // axis. Filling it in at the centroid would draw a line that is not there.
        StressRibbon r = StressRibbonBuilder.build(cantilever(11, 24.24),
                StressPalette.SIGNED_DEFAULT, 24.24);
        assertEquals(10, r.neutralAxis().size());
        assertEquals(64.0, r.neutralAxis().get(0).y(), 1e-12);
    }

    @Test
    void scenePeakMakesMembersComparableAndMemberPeakDoesNot() {
        MemberSnapshot loaded = cantilever(5, 100);
        MemberSnapshot light = cantilever(5, 1);

        // Under each member's own peak, the lightly loaded member looks just as vivid.
        var a = StressRibbonBuilder.build(loaded, StressPalette.SIGNED_DEFAULT,
                StressRibbonBuilder.memberPeak(loaded));
        var b = StressRibbonBuilder.build(light, StressPalette.SIGNED_DEFAULT,
                StressRibbonBuilder.memberPeak(light));
        assertEquals(a.bands().get(0).fromColour(), b.bands().get(0).fromColour());

        // Under a shared scale it correctly reads as nearly unstressed.
        var c = StressRibbonBuilder.build(light, StressPalette.SIGNED_DEFAULT, 100);
        assertEquals(StressPalette.SIGNED_DEFAULT.zeroColour(), c.bands().get(0).fromColour());
    }
}
