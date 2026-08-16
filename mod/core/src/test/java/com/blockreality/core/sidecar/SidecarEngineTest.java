package com.blockreality.core.sidecar;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.Fibre;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ShellFieldSpec;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.geom.Vec3d;
import com.blockreality.core.render.ShellMesh;
import com.blockreality.api.StressStation;
import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.render.StressPalette;
import com.blockreality.core.protocol.SolveRequest;
import com.blockreality.core.render.StressRibbon;
import com.blockreality.core.render.StressRibbonBuilder;

import java.util.Optional;
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

    // ======================================================== PLATES =========
    //
    // Everything below goes through the real engine and is checked against plate theory,
    // never against the reply itself. A codec that dropped a corner moment or mixed up
    // Mxx and Myy would still round-trip perfectly against the sidecar; only an external
    // reference catches it.

    private static final double SLAB_T = 200;                // concrete_slab_200
    private static final double SLAB_RHO = 2350;
    private static final double SLAB_NU = 0.20;
    private static final double Q_SLAB = SLAB_RHO * SLAB_T * 9810.0 * 1e-12;   // N/mm^2

    private static SolveRequest clampedSlab(long rev, int n) {
        SolveRequest.Builder b = SolveRequest.builder(new WorldRevision(rev));
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                boolean edge = i == 0 || j == 0 || i == n - 1 || j == n - 1;
                b.block(new BlockKey(i, 64, j), "concrete", "concrete_slab_200", edge);
            }
        }
        return b.build();
    }

    @Test
    void aSlabBecomesPlateFacetsAndNotAGridOfBeams() {
        AnalysisResult r = client.solve(clampedSlab(40, 7));
        assertTrue(r.isUsable(), r.diagnostic());
        assertEquals(36, r.shells().size(), "one facet per 2x2 block square");
        assertTrue(r.members().isEmpty(), "a slab is a plate, not a grillage");
        assertTrue(r.unassigned().isEmpty());
        assertEquals(1, r.islands());
    }

    @Test
    void theSpanMomentMatchesTimoshenko() {
        // Clamped square plate under a uniform load. The tabulated centre coefficient
        // 0.0231 is for nu = 0.3; at the centre the two curvatures are equal by symmetry,
        // so M = D*k*(1+nu) and it rescales as (1+nu)/1.3 for our nu = 0.2.
        int n = 13;
        AnalysisResult r = client.solve(clampedSlab(41, n));
        double a = (n - 1) * 1000.0;
        double centre = (n - 1) / 2.0 * 1000.0 + 500.0;
        double expect = 0.0231 / 1.3 * (1 + SLAB_NU) * Q_SLAB * a * a;

        Optional<ShellMesh.Hit> hit = ShellMesh.locate(r.shells(), new Vec3d(centre, 64500, centre));
        assertTrue(hit.isPresent());
        assertEquals(0.0, hit.get().outsideMm(), 1e-9, "the plate centre is inside a facet");
        double got = hit.get().field().momentAt(hit.get().xi(), hit.get().eta()).mxx();
        assertEquals(expect, Math.abs(got), 0.01 * expect,
                "span moment within 1% at " + (n - 1) + " elements");
    }

    @Test
    void aFloorCarriesNoMembraneForceUnderItsOwnWeight() {
        AnalysisResult r = client.solve(clampedSlab(42, 7));
        for (ShellSnapshot s : r.shells()) {
            ShellFieldSpec f = s.field().orElseThrow();
            assertEquals(0.0, f.nxx(), 1e-9);
            assertEquals(0.0, f.nyy(), 1e-9);
            // ... so the two faces are exactly equal and opposite, which is the visible
            // signature of pure bending and the thing the overlay has to show.
            assertEquals(f.signedPrincipal(0, 0, 1), -f.signedPrincipal(0, 0, -1), 1e-9);
        }
    }

    @Test
    void theSupportMomentIsRecoveredAndNeverLowersADemand() {
        AnalysisResult r = client.solve(clampedSlab(43, 13));
        assertTrue(r.shells().stream().anyMatch(ShellSnapshot::edgeRecovered),
                "the clamped boundary must trigger the recovery");
        for (ShellSnapshot s : r.shells()) {
            assertTrue(s.dc() >= s.dcRaw() - 1e-12,
                    "recovery may only raise a demand, never lower one");
        }
    }

    @Test
    void everyBlockCentreIsInsideTheMeshAndOnlyTheOuterHalfBlockIsClamped() {
        // Facet corners are at block CENTRES, so the mesh reaches every block's centre —
        // including the outer ring's. What lies outside is the outer HALF of the edge
        // blocks, and there the contour is clamped to the boundary value rather than
        // extrapolated past the last node.
        int n = 7;
        AnalysisResult r = client.solve(clampedSlab(44, n));
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                Vec3d centre = new Vec3d(i * 1000 + 500, 64500, j * 1000 + 500);
                ShellMesh.Hit h = ShellMesh.locate(r.shells(), centre).orElseThrow();
                assertEquals(0.0, h.outsideMm(), 1e-6, "centre of block " + i + "," + j);
            }
        }
        // The outer face of the first block is half a block beyond the last node, and no
        // further: a larger overshoot would mean the wrong facet had been picked.
        ShellMesh.Hit outer = ShellMesh.locate(r.shells(), new Vec3d(0, 64500, 3500)).orElseThrow();
        assertEquals(500.0, outer.outsideMm(), 1e-6);
        assertEquals(-1.0, outer.xi(), 1e-12, "clamped onto the facet edge, not past it");
    }

    @Test
    void aColumnSharesItsTopNodeWithTheFloorItHoldsUp() {
        // Four columns under the corners of an unsupported 3x3 slab. If the bearing did
        // not make them ONE node, this is a mechanism — so a solved result IS the
        // connection, stated as a result rather than as a comment.
        SolveRequest.Builder b = SolveRequest.builder(new WorldRevision(45));
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) b.block(new BlockKey(i, 68, j), "concrete", "concrete_slab_200", false);
        }
        for (int[] c : new int[][] { { 0, 0 }, { 0, 2 }, { 2, 0 }, { 2, 2 } }) {
            for (int y = 64; y < 68; y++) {
                b.block(new BlockKey(c[0], y, c[1]), "steel", "steel_rect_200x400", y == 64);
            }
        }
        AnalysisResult r = client.solve(b.build());
        assertTrue(r.isUsable(), r.diagnostic());
        assertFalse(r.singular(), r.diagnostic());
        assertEquals(1, r.islands(), "the slab and its columns are one structure");
        assertEquals(4, r.members().size());
        assertEquals(4, r.shells().size());
    }

    @Test
    void oneUnsupportedBuildingDoesNotSilenceTheOthers() {
        // The failure a player reported: several buildings in a world and the overlay went
        // dead. One rank-deficient structure used to make the whole global factorisation
        // fail, so every building lost its answer.
        SolveRequest.Builder b = SolveRequest.builder(new WorldRevision(46));
        for (int x = 0; x <= 4; x++) b.block(new BlockKey(x, 64, 0), "steel", "steel_rect_200x400", x == 0);
        for (int x = 0; x <= 4; x++) b.block(new BlockKey(x + 100, 64, 0), "steel", "steel_rect_200x400", false);
        AnalysisResult r = client.solve(b.build());

        assertTrue(r.ok());
        assertTrue(r.singular(), "one of the two really is a mechanism");
        assertFalse(r.allSingular(), "but not both, so there is something to draw");
        assertTrue(r.isUsable());
        assertEquals(2, r.islands());
        assertEquals(1, r.singularIslands());

        // The same cantilever ALONE — same blocks, same (absent) loads — must give a
        // bit-identical answer. A comparison against a differently loaded cantilever would
        // only be testing that two different problems have two different answers.
        SolveRequest.Builder solo = SolveRequest.builder(new WorldRevision(47));
        for (int x = 0; x <= 4; x++) solo.block(new BlockKey(x, 64, 0), "steel", "steel_rect_200x400", x == 0);
        AnalysisResult alone = client.solve(solo.build());
        assertEquals(alone.maxDc(), r.maxDc(), 0.0,
                "the sound building's answer must not depend on its neighbours");
    }

    @Test
    void everySolveCarriesItsOwnEquilibriumCheck() {
        // applied is recomputed from geometry and density on the engine side, not read out
        // of the assembled load vector, so this is an independent statement that nothing
        // was lost, doubled or rotated into the wrong axis.
        for (AnalysisResult r : List.of(client.solve(cantilever(48, true)),
                                        client.solve(clampedSlab(49, 9)))) {
            assertTrue(r.equilibriumResidual() < 1e-10,
                    "residual " + r.equilibriumResidual());
        }
    }
}
