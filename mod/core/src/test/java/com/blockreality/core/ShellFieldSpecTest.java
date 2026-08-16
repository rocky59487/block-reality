package com.blockreality.core;

import com.blockreality.api.ShellFieldSpec;
import com.blockreality.api.geom.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plate field is pinned against plate theory, not against the sidecar that produced
 * it.
 *
 * <p>That distinction has already cost this project twice. {@code verify.py} once asserted
 * what the sidecar computed under the same assumption the sidecar was making, so a wrong
 * end-force convention and a wrong {@code My} sign both survived every check. A test that
 * agrees with the thing it is testing is a loop, not a test.
 */
class ShellFieldSpecTest {

    private static final double T = 200;               // concrete_slab_200
    private static final double H = 500;               // half a block, in millimetres

    /** A flat unit facet in the XZ plane with its normal up, corners CCW as the engine orders them. */
    private static ShellFieldSpec facet(double nxx, double nyy, double nxy,
                                        double mxx, double myy, double mxy) {
        List<Vec3d> corners = List.of(
                new Vec3d(-H, 0, -H), new Vec3d(H, 0, -H),
                new Vec3d(H, 0, H), new Vec3d(-H, 0, H));
        ShellFieldSpec.Moments m = new ShellFieldSpec.Moments(mxx, myy, mxy);
        return new ShellFieldSpec(corners,
                new Vec3d(1, 0, 0), new Vec3d(0, 0, 1), new Vec3d(0, 1, 0),
                T, nxx, nyy, nxy, mxx, myy, mxy, 0, 0,
                List.of(m, m, m, m));
    }

    @Test
    void pureBendingIsEqualAndOppositeOnTheTwoFaces() {
        // sigma = +/- 6M/t^2. A slab hogging over a support is in tension on top and
        // compression underneath, and the plot has to show both or it is decoration.
        double mxx = 1.0e6;
        var f = facet(0, 0, 0, mxx, 0, 0);
        double expect = 6 * mxx / (T * T);
        assertEquals(expect, f.surfaceAt(0, 0, +1).sx(), 1e-12);
        assertEquals(-expect, f.surfaceAt(0, 0, -1).sx(), 1e-12);
        assertEquals(0.0, f.surfaceAt(0, 0, 0).sx(), 1e-12, "mid-surface carries no bending");
    }

    @Test
    void pureMembraneIsConstantThroughTheThickness() {
        var f = facet(4.0e4, 0, 0, 0, 0, 0);
        double expect = 4.0e4 / T;
        for (double z = -1; z <= 1; z += 0.5) {
            assertEquals(expect, f.surfaceAt(0, 0, z).sx(), 1e-12, "z=" + z);
        }
    }

    @Test
    void theNeutralSurfaceSitsWhereTheTwoTermsCancel() {
        // N/t + (6M/t^2) z = 0  ->  z = -N t / (6 M), as a fraction of the half-thickness.
        double n = 1.5e4, m = 1.0e6;   // lands at zf = -0.5, half way to the bottom face
        var f = facet(n, 0, 0, m, 0, 0);
        double zf = -n * T / (6 * m);
        assertTrue(Math.abs(zf) < 1, "the test is only meaningful if it lands inside the plate");
        assertEquals(0.0, f.surfaceAt(0, 0, zf).sx(), 1e-10);
    }

    @Test
    void signedPrincipalKeepsTheSignAndMatchesUniaxialStress() {
        // For a uniaxial state the principal stress IS the stress, which is what makes this
        // scalar directly comparable with a beam's fibre stress on one shared colour scale.
        var tension = facet(0, 0, 0, 1.0e6, 0, 0);
        double expect = 6 * 1.0e6 / (T * T);
        assertEquals(expect, tension.signedPrincipal(0, 0, +1), 1e-12);
        assertEquals(-expect, tension.signedPrincipal(0, 0, -1), 1e-12);
    }

    @Test
    void vonMisesOfPureShearIsRootThreeTau() {
        var f = facet(0, 0, 0, 0, 0, 1.0e6);
        double tau = 6 * 1.0e6 / (T * T);
        assertEquals(Math.sqrt(3) * tau, f.vonMises(0, 0, +1), 1e-9);
        // ... and von Mises never carries a sign, unlike the principal scalar.
        assertTrue(f.vonMises(0, 0, -1) > 0);
    }

    @Test
    void biaxialVonMisesFollowsTheDefinition() {
        var f = facet(0, 0, 0, 1.0e6, -0.4e6, 0.2e6);
        double b = 6 / (T * T);
        double sx = b * 1.0e6, sy = b * -0.4e6, txy = b * 0.2e6;
        assertEquals(Math.sqrt(sx * sx - sx * sy + sy * sy + 3 * txy * txy),
                     f.vonMises(0, 0, +1), 1e-9);
    }

    @Test
    void cornerValuesAreReproducedExactlyAtTheCorners() {
        List<Vec3d> corners = List.of(
                new Vec3d(-H, 0, -H), new Vec3d(H, 0, -H),
                new Vec3d(H, 0, H), new Vec3d(-H, 0, H));
        List<ShellFieldSpec.Moments> m = List.of(
                new ShellFieldSpec.Moments(1, 0, 0),
                new ShellFieldSpec.Moments(2, 0, 0),
                new ShellFieldSpec.Moments(3, 0, 0),
                new ShellFieldSpec.Moments(4, 0, 0));
        var f = new ShellFieldSpec(corners, new Vec3d(1, 0, 0), new Vec3d(0, 0, 1),
                new Vec3d(0, 1, 0), T, 0, 0, 0, 0, 0, 0, 0, 0, m);

        double[][] nat = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };
        for (int k = 0; k < 4; k++) {
            assertEquals(m.get(k).mxx(), f.momentAt(nat[k][0], nat[k][1]).mxx(), 1e-12, "corner " + k);
        }
        // The centre of a bilinear field is the mean of its corners: (1+2+3+4)/4.
        assertEquals(2.5, f.momentAt(0, 0).mxx(), 1e-12);
    }

    @Test
    void naturalCoordinatesInvertThePositionMapping() {
        var f = facet(0, 0, 0, 1, 0, 0);
        for (double xi = -1; xi <= 1; xi += 0.5) {
            for (double eta = -1; eta <= 1; eta += 0.5) {
                double[] p = f.paramAt(f.pointAt(xi, eta));
                assertEquals(xi, p[0], 1e-12);
                assertEquals(eta, p[1], 1e-12);
            }
        }
    }

    @Test
    void aPointOutsideTheFacetReportsItRatherThanPretending() {
        // Unclamped on purpose: the renderer has to be able to ask "is this mine?" before
        // it colours a block face with a field that belongs to a facet two metres away.
        var f = facet(0, 0, 0, 1, 0, 0);
        double[] p = f.paramAt(new Vec3d(3 * H, 0, 0));
        assertEquals(3.0, p[0], 1e-12);
        assertTrue(Math.abs(p[0]) > 1);
    }

    @Test
    void offNormalDistanceMeasuresThroughTheThickness() {
        var f = facet(0, 0, 0, 1, 0, 0);
        assertEquals(250.0, f.offNormalMm(new Vec3d(0, 250, 0)), 1e-12);
        assertEquals(-250.0, f.offNormalMm(new Vec3d(0, -250, 0)), 1e-12);
    }

    @Test
    void positionsBeyondTheFacesAreClampedNotExtrapolated() {
        // There is no material outside the plate, so there is no stress there to report.
        var f = facet(0, 0, 0, 1.0e6, 0, 0);
        assertEquals(f.surfaceAt(0, 0, 1).sx(), f.surfaceAt(0, 0, 9).sx(), 1e-12);
        assertEquals(f.surfaceAt(0, 0, -1).sx(), f.surfaceAt(0, 0, -9).sx(), 1e-12);
    }

    @Test
    void peakIsTheLargestMagnitudeOnEitherFace() {
        var f = facet(0, 0, 0, 1.0e6, 0, 0);
        assertEquals(6 * 1.0e6 / (T * T), f.peakMagnitudeMpa(), 1e-9);
    }
}
