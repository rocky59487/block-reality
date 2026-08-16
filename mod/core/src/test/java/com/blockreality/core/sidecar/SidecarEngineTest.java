package com.blockreality.core.sidecar;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.Fibre;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.StressStation;
import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.render.StressPalette;
import com.blockreality.core.protocol.SolveRequest;
import com.blockreality.core.render.StressRibbon;
import com.blockreality.core.render.StressRibbonBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end against the real {@code br-sidecar}: Java sends blocks, FrameCore solves,
 * Java decodes, and the numbers are checked against closed-form solutions.
 *
 * <p>This is the test that makes the engine boundary reproducible for the paper. The C++
 * side already has {@code verify.py}; this covers the half that lives in Java, so a codec
 * or unit-conversion mistake introduced here cannot hide behind a correct sidecar.
 *
 * <p>Skipped when the binary is absent — see {@code mod/README.md} for how to point at it.
 */
class SidecarEngineTest {

    // steel_rect_200x400 is Rectangular(b=200, d=400): non-square by the GATES.md fixture rule,
    // which is what exposed both FrameCore issues in ENGINE_FINDINGS.md.
    private static final double B_MM = 200, D_MM = 400;
    private static final double W_MM3 = B_MM * D_MM * D_MM / 6.0;      // 5.3333e6
    private static final double A_MM2 = B_MM * D_MM;
    private static final double RHO_KG_M3 = 7850;
    private static final double G = 9.81;                              // SelfWeight.h: 9810 mm/s^2
    private static final double W_N_PER_MM = RHO_KG_M3 * 1e-9 * A_MM2 * G;   // 6.1607
    private static final double L_MM = 4000;
    private static final double P_N = 20_000;

    private SidecarClient client;

    @BeforeEach
    void start() {
        String p = System.getProperty("br.sidecar", "");
        Assumptions.assumeTrue(!p.isEmpty() && Files.isExecutable(Path.of(p)),
                "br-sidecar not available; pass -Dbr.sidecar=/path/to/br-sidecar");
        client = new SidecarClient(SidecarConfig.of(Path.of(p)), s -> { });
    }

    @AfterEach
    void stop() { if (client != null) client.close(); }

    /** Five steel blocks along +x at y=64, the first one supported. */
    private static SolveRequest cantilever(long rev, boolean supported) {
        SolveRequest.Builder b = SolveRequest.builder(new WorldRevision(rev));
        for (int x = 0; x <= 4; x++) {
            b.block(new BlockKey(x, 64, 0), "steel", "steel_rect_200x400", supported && x == 0);
        }
        return b.load(SolveRequest.PointLoad.downwards(new BlockKey(4, 64, 0), P_N)).build();
    }

    @Test
    void handshakeReportsTheCatalogue() {
        assertTrue(client.ensureReady());
        var cat = client.catalogue().orElseThrow();
        assertEquals("FrameCore", cat.engine());
        assertTrue(cat.isCompatible());
        assertTrue(cat.hasMaterial("steel"));
        assertTrue(cat.hasSection("steel_rect_200x400"));
    }

    @Test
    void aRunOfBlocksBecomesOneMemberNotFive() {
        // D-010: structure comes from the bone, not the flesh. Five collinear blocks of
        // one material are one member, and the block count must not leak into the model.
        AnalysisResult r = client.solve(cantilever(1, true));
        assertTrue(r.isUsable(), r.diagnostic());
        assertEquals(1, r.members().size());
        assertEquals(L_MM, r.members().get(0).lengthMm(), 1e-9);
        assertEquals(5, r.members().get(0).blocks().size());
    }

    @Test
    void topFibreIsInTensionAndBottomInCompression() {
        AnalysisResult r = client.solve(cantilever(1, true));
        assertTrue(r.isUsable(), r.diagnostic());
        StressStation root = r.members().get(0).stations().get(0);

        Fibre top = root.fibre("TOP_Y").orElseThrow();
        Fibre bot = root.fibre("BOT_Y").orElseThrow();

        assertTrue(top.isTension(), "top fibre should be in tension, got " + top.sigmaMpa());
        assertTrue(bot.isCompression(), "bottom fibre should be in compression, got " + bot.sigmaMpa());

        // No axial load, so the two faces are equal and opposite and the neutral axis sits
        // at the centroid. If either fails, an axis pairing is wrong somewhere.
        assertEquals(top.sigmaMpa(), -bot.sigmaMpa(), 1e-9);
        assertTrue(root.hasNeutralAxis());
        assertEquals(0.0, root.naOffsetYMm().orElseThrow(), 1e-9);
    }

    @Test
    void everyStationMatchesTheClosedFormBendingStress() {
        // sigma(x) = [P (L-x) + w (L-x)^2 / 2] / W
        AnalysisResult r = client.solve(cantilever(1, true));
        assertTrue(r.isUsable(), r.diagnostic());
        List<StressStation> st = r.members().get(0).stations();
        assertEquals(11, st.size());

        for (StressStation s : st) {
            double a = L_MM - s.xMm();
            double expected = (P_N * a + W_N_PER_MM * a * a / 2.0) / W_MM3;
            double actual = s.fibre("TOP_Y").orElseThrow().sigmaMpa();
            assertEquals(expected, actual, 1e-6 * Math.max(1.0, Math.abs(expected)),
                    "station x=" + s.xMm());
        }
    }

    @Test
    void theFreeEndCarriesNoBendingStress() {
        // The symptom that exposed FrameCore's UDL curvature sign: a free tip reporting
        // -18.48 MPa while its own end forces correctly said the moment there was zero.
        AnalysisResult r = client.solve(cantilever(1, true));
        List<StressStation> st = r.members().get(0).stations();
        StressStation tip = st.get(st.size() - 1);
        assertEquals(0.0, tip.fibre("TOP_Y").orElseThrow().sigmaMpa(), 1e-9);
        assertFalse(tip.hasNeutralAxis(), "an unstressed section has no neutral axis to draw");
    }

    @Test
    void anUnsupportedStructureIsReportedAsAMechanism() {
        AnalysisResult r = client.solve(cantilever(2, false));
        assertTrue(r.ok(), "a mechanism is an answer, not an engine failure");
        assertTrue(r.singular());
        assertFalse(r.isUsable(), "there are no numbers to report for a mechanism");
        assertFalse(r.diagnostic().isEmpty());
    }

    @Test
    void aSingleBlockIsNotABeam() {
        // MEMBER_SEMANTICS §1: L/h = 1 is not a beam. It comes back unassigned rather
        // than as a member with a made-up length.
        SolveRequest r = SolveRequest.builder(new WorldRevision(3))
                .block(new BlockKey(0, 64, 0), "steel", "steel_rect_200x400", true)
                .build();
        AnalysisResult a = client.solve(r);
        assertTrue(a.ok());
        assertTrue(a.members().isEmpty());
        assertEquals(1, a.unassigned().size());
    }

    @Test
    void theOverlayDrawnFromRealEngineOutputIsBlueOnTopAndRedBelow() {
        // The requirement, end to end: blocks in, drawable ribbon out, correct hues.
        AnalysisResult r = client.solve(cantilever(1, true));
        assertTrue(r.isUsable(), r.diagnostic());

        MemberSnapshot m = r.members().get(0);
        StressRibbon ribbon = StressRibbonBuilder.build(m, StressPalette.SIGNED_DEFAULT,
                StressRibbonBuilder.memberPeak(m));

        StressRibbon.Band top = ribbon.bands().stream()
                .filter(b -> b.fibre().equals("TOP_Y")).findFirst().orElseThrow();
        StressRibbon.Band bot = ribbon.bands().stream()
                .filter(b -> b.fibre().equals("BOT_Y")).findFirst().orElseThrow();

        assertTrue(top.fromColour().b() > top.fromColour().r(), "top band " + top.fromColour());
        assertTrue(bot.fromColour().r() > bot.fromColour().b(), "bottom band " + bot.fromColour());

        // And the fibres are on opposite sides of the centreline, not both on the same one.
        assertTrue(top.from().y() > bot.from().y());

        // A single flat colour along the member would mean the gradient was lost.
        long distinct = ribbon.bands().stream()
                .filter(b -> b.fibre().equals("TOP_Y"))
                .map(StressRibbon.Band::fromColour).distinct().count();
        assertTrue(distinct > 5, "expected a gradient along the member, got " + distinct + " colours");
    }

    @Test
    void overloadingTheMemberPushesDemandOverCapacity() {
        SolveRequest.Builder b = SolveRequest.builder(new WorldRevision(4));
        for (int x = 0; x <= 4; x++) b.block(new BlockKey(x, 64, 0), "steel", "steel_rect_200x400", x == 0);
        AnalysisResult r = client.solve(
                b.load(SolveRequest.PointLoad.downwards(new BlockKey(4, 64, 0), 2_000_000)).build());

        assertTrue(r.isUsable(), r.diagnostic());
        assertTrue(r.maxDc() > 1.0, "expected failure, got D/C " + r.maxDc());
        assertTrue(r.members().get(0).isOverloaded());
        assertEquals(r.members().get(0).id(), r.governing());
    }

    @Test
    void theWorstSectionCanBeInTheMiddleAndTheScreenMustSeeIt() {
        // Screening only the two ends reports this member as unstressed. With an upward
        // tip load of w L / 2 the moment is exactly zero at BOTH ends and peaks at
        // midspan at w L^2 / 8 — so an end-only D/C says "safe" about the one section
        // that is actually working. Silently safe is the worst answer this system can
        // give, which is why it has its own test on this side of the boundary too.
        final int n = 9;
        final double L = (n - 1) * 1000.0;
        SolveRequest.Builder b = SolveRequest.builder(new WorldRevision(11));
        for (int x = 0; x < n; x++) {
            b.block(new BlockKey(x, 64, 0), "steel", "steel_rect_200x400", x == 0);
        }
        AnalysisResult r = client.solve(b.load(new SolveRequest.PointLoad(
                new BlockKey(n - 1, 64, 0), 0, W_N_PER_MM * L / 2.0, 0, 0, 0, 0)).build());

        assertTrue(r.isUsable(), r.diagnostic());
        MemberSnapshot m = r.members().get(0);

        double expected = (W_N_PER_MM * L * L / 8.0) / W_MM3;
        assertEquals(expected, m.peakMagnitudeMpa(), 1e-6 * expected);
        assertEquals(expected / 350.0, m.dc(), 1e-6 * expected / 350.0);
        assertTrue(m.governingStation() >= 0, "the governing station must be reported");

        StressStation gov = m.stations().get(m.governingStation());
        assertTrue(gov.xMm() > 0.1 * L && gov.xMm() < 0.9 * L,
                "the governing section is interior, at x=" + gov.xMm());
    }

    @Test
    void aLoadInsideAMemberIsRefusedRatherThanDropped() {
        // There is no node in the middle of a run, so this load cannot be represented.
        // Dropping it would answer a question about a lighter structure than the one
        // that was asked about, with ok:true on the reply.
        SolveRequest.Builder b = SolveRequest.builder(new WorldRevision(12));
        for (int x = 0; x <= 4; x++) {
            b.block(new BlockKey(x, 64, 0), "steel", "steel_rect_200x400", x == 0);
        }
        AnalysisResult r = client.solve(
                b.load(SolveRequest.PointLoad.downwards(new BlockKey(2, 64, 0), P_N)).build());

        assertFalse(r.ok(), "a load that cannot be placed must fail the request");
        assertTrue(r.diagnostic().contains("not on an analysis node"), r.diagnostic());
    }

    @Test
    void anUnknownSectionIsRefusedRatherThanDefaulted() {
        SolveRequest r = SolveRequest.builder(new WorldRevision(13))
                .block(new BlockKey(0, 64, 0), "steel", "steel_h400", true)
                .block(new BlockKey(1, 64, 0), "steel", "steel_h400", false)
                .build();
        AnalysisResult a = client.solve(r);
        assertFalse(a.ok(), "the old token must not silently resolve to anything");
        assertTrue(a.diagnostic().contains("unknown section"), a.diagnostic());
    }

    @Test
    void aSectionChangeSplitsTheMemberButKeepsItConnected() {
        // The steel is physically continuous through a change of section, so the two
        // segments must share a node. Breaking the run outright would leave the far
        // segment floating, and carrying the head block's section through would solve
        // a member that is not there.
        SolveRequest.Builder b = SolveRequest.builder(new WorldRevision(14));
        for (int x = 0; x < 6; x++) {
            b.block(new BlockKey(x, 64, 0), "steel",
                    x < 3 ? "steel_rect_200x400" : "steel_rect_100x200", x == 0);
        }
        AnalysisResult r = client.solve(b.build());

        assertTrue(r.isUsable(), r.diagnostic());
        assertEquals(2, r.members().size());
        assertEquals(List.of("steel_rect_100x200", "steel_rect_200x400"),
                r.members().stream().map(MemberSnapshot::section).sorted().toList());
    }

    @Test
    void aBeamHeldAtBothEndsHogsAtTheSupportAndSagsBetween() {
        // The bug this pins: FrameCore reports end J as an element END ACTION, whose sign
        // flips with the member's direction, so interpolating between end I and a raw
        // end J produces a diagram that never changes sign. A beam held at both ends came
        // out reading tension along its entire length.
        //
        // A cantilever cannot catch it — its free end carries no moment, so the wrong term
        // is multiplied by zero. This needs moment at BOTH ends, hence the interior node,
        // hence the stub.
        final int n = 9;
        final double L = (n - 1) * 1000.0;
        SolveRequest.Builder b = SolveRequest.builder(new WorldRevision(21));
        for (int x = 0; x < n; x++) {
            b.block(new BlockKey(x, 64, 0), "steel", "steel_rect_200x400", x == 0 || x == n - 1);
        }
        b.block(new BlockKey(4, 65, 0), "steel", "steel_rect_200x400", false);
        b.block(new BlockKey(4, 66, 0), "steel", "steel_rect_200x400", false);

        AnalysisResult r = client.solve(b.build());
        assertTrue(r.isUsable(), r.diagnostic());

        List<MemberSnapshot> halves = r.members().stream()
                .filter(m -> m.blocks().get(0).y() == 64 && m.blocks().get(m.blocks().size() - 1).y() == 64)
                .sorted(java.util.Comparator.comparingInt(m -> m.blocks().get(0).x()))
                .toList();
        assertEquals(2, halves.size());
        MemberSnapshot left = halves.get(0);

        double first = left.stations().get(0).fibre("TOP_Y").orElseThrow().sigmaMpa();
        double last = left.stations().get(left.stations().size() - 1).fibre("TOP_Y").orElseThrow().sigmaMpa();
        assertTrue(first > 0, "hogging at the support puts the top in tension, got " + first);
        assertTrue(last < 0, "sagging between supports puts the top in compression, got " + last);

        // Two members meeting at one node must report the same section moment there.
        assertEquals(left.endJ().mz(), halves.get(1).endI().mz(), 1e-9);

        // Closed form for what this is: fixed-fixed under self weight plus the stub's
        // weight as a central point load.
        double pStub = W_N_PER_MM * 2000.0;
        assertEquals(W_N_PER_MM * L * L / 12.0 + pStub * L / 8.0, left.endI().mz(), 1e-3);
        assertEquals(-(W_N_PER_MM * L * L / 24.0 + pStub * L / 8.0), left.endJ().mz(), 1e-3);
    }

    @Test
    void repeatedSolvesAtTheSameRevisionAreIdentical() {
        // Determinism is a precondition for the two-track precision split: the display
        // and commit tracks must not disagree because the solver wandered.
        AnalysisResult a = client.solve(cantilever(5, true));
        AnalysisResult b = client.solve(cantilever(5, true));
        assertEquals(a.maxDc(), b.maxDc(), 0.0);
        assertEquals(a.members().get(0).stations().get(0).fibre("TOP_Y").orElseThrow().sigmaMpa(),
                b.members().get(0).stations().get(0).fibre("TOP_Y").orElseThrow().sigmaMpa(), 0.0);
    }
}
