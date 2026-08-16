package com.blockreality.core;

import com.blockreality.api.EndForces;
import com.blockreality.api.StressFieldSpec;
import com.blockreality.api.geom.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The field is the thing the contour is drawn from, so it is pinned against closed-form
 * beam theory rather than against the sidecar that produced it.
 */
class StressFieldSpecTest {

    // steel_rect_200x400
    private static final double B = 200, D = 400;
    private static final double A = B * D;
    private static final double IZ = B * D * D * D / 12.0;
    private static final double IY = D * B * B * B / 12.0;
    private static final double CZ = D / 2, CY = B / 2;
    private static final double WZ_MOD = B * D * D / 6.0;      // section modulus about z
    private static final double L = 4000;

    private static StressFieldSpec field(EndForces i, EndForces j, double wy, double wz) {
        return new StressFieldSpec(
                Vec3d.ZERO, new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, -1),
                L, A, IY, IZ, CY, CZ, wy, wz, i, j);
    }

    @Test
    void aCantileverReproducesTheClosedFormAtEveryPoint() {
        // Tip load P down: M(x) = P (L - x), hogging, so the top is in tension.
        double p = 20_000;
        var f = field(new EndForces(0, 0, 0, 0, 0, p * L), EndForces.ZERO, 0, 0);

        for (int k = 0; k <= 20; k++) {
            double x = L * k / 20.0;
            double expected = p * (L - x) / WZ_MOD;
            assertEquals(expected, f.sigmaAt(x, CZ, 0), 1e-9 * Math.max(1, expected), "x=" + x);
            assertEquals(-expected, f.sigmaAt(x, -CZ, 0), 1e-9 * Math.max(1, expected));
        }
    }

    @Test
    void theUniformLoadParabolaIsExactNotSampled() {
        // Self weight only, cantilever: M(x) = w (L-x)^2 / 2.
        double w = 6.16068;
        var f = field(new EndForces(0, 0, 0, 0, 0, w * L * L / 2), EndForces.ZERO, -w, 0);

        for (int k = 0; k <= 20; k++) {
            double x = L * k / 20.0;
            double a = L - x;
            assertEquals(w * a * a / 2 / WZ_MOD, f.sigmaAt(x, CZ, 0), 1e-9);
        }
        // And exactly zero at the free end, where the moment genuinely vanishes.
        assertEquals(0.0, f.sigmaAt(L, CZ, 0), 1e-9);
    }

    @Test
    void theTwoBendingTermsCarryOppositeSigns() {
        // +Mz puts +y in tension. +My puts +z in COMPRESSION. Measured, not assumed:
        // a cantilever pushed along local -z deflects that way and is in tension on +z,
        // while its My comes out negative there.
        var mz = field(new EndForces(0, 0, 0, 0, 0, 1e8), EndForces.ZERO, 0, 0);
        assertTrue(mz.sigmaAt(0, CZ, 0) > 0, "+Mz must put +y in tension");

        var my = field(new EndForces(0, 0, 0, 0, 1e8, 0), EndForces.ZERO, 0, 0);
        assertTrue(my.sigmaAt(0, 0, CY) < 0, "+My must put +z in compression");
        assertEquals(-my.sigmaAt(0, 0, CY), my.sigmaAt(0, 0, -CY), 1e-12);
    }

    @Test
    void axialIsCompressionPositiveOnTheWireAndTensionPositiveOut() {
        // N = -100000 on the wire means the bar is being pulled.
        var f = field(new EndForces(-100_000, 0, 0, 0, 0, 0),
                      new EndForces(-100_000, 0, 0, 0, 0, 0), 0, 0);
        assertEquals(100_000 / A, f.sigmaAt(L / 2, 0, 0), 1e-12);
        assertTrue(f.sigmaAt(L / 2, 0, 0) > 0, "a pulled bar must read tension");
    }

    @Test
    void biaxialBendingSuperposes() {
        var f = field(new EndForces(0, 0, 0, 0, 1e8, 1e8), EndForces.ZERO, 0, 0);
        double corner = f.sigmaAt(0, CZ, CY);
        double onlyMz = 1e8 * CZ / IZ;
        double onlyMy = -1e8 * CY / IY;
        assertEquals(onlyMz + onlyMy, corner, 1e-9);
    }

    @Test
    void worldPointsMapOntoTheSectionRatherThanOutsideIt() {
        // The block is a magnified section: a point on the cube's face is proportional,
        // not extrapolated. A cube corner 500 mm out must read the extreme fibre, not
        // a stress 2.5x larger that no material is carrying.
        var f = field(new EndForces(0, 0, 0, 0, 0, 1e8), EndForces.ZERO, 0, 0);
        double atFibre = f.sigmaAt(0, CZ, 0);
        double atCubeFace = f.sigmaAtWorldMm(new Vec3d(0, 500, 0), 500);
        assertEquals(atFibre, atCubeFace, 1e-9);

        // Beyond the cube it is clamped rather than extrapolated.
        assertEquals(atFibre, f.sigmaAtWorldMm(new Vec3d(0, 5000, 0), 500), 1e-9);
    }

    @Test
    void positionsAlongTheMemberAreClampedToItsLength() {
        var f = field(new EndForces(0, 0, 0, 0, 0, 1e8), EndForces.ZERO, 0, 0);
        assertEquals(f.sigmaAt(0, CZ, 0), f.sigmaAtWorldMm(new Vec3d(-999, 500, 0), 500), 1e-9);
        assertEquals(f.sigmaAt(L, CZ, 0), f.sigmaAtWorldMm(new Vec3d(L + 999, 500, 0), 500), 1e-9);
    }

    @Test
    void peakIsFoundEvenWhenItIsInTheMiddle() {
        // Both ends zero, parabola in between: sampling only the ends would report zero.
        double w = 6.16068;
        var f = field(EndForces.ZERO, EndForces.ZERO, -w, 0);
        double expected = w * L * L / 8 / WZ_MOD;
        assertEquals(expected, f.peakMagnitudeMpa(21), 1e-6 * expected);
    }
}
