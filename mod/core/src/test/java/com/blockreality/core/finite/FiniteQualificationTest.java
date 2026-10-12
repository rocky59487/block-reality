package com.blockreality.core.finite;

import com.blockreality.core.bsi.BsiCorot;
import com.blockreality.core.bsi.BsiFracture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.blockreality.core.finite.FiniteFixtures.*;

/**
 * The qualification read-model (spec O9): exactly the native verdicts, verbatim — never a
 * derived safety claim. Unknown statuses stay UNKNOWN, an empty island table is
 * "not assessed", and the availability census is counted, not assumed.
 */
class FiniteQualificationTest {

    private static FiniteResult result(List<FiniteResult.Island> islands,
                                       BsiCorot.Availability availability,
                                       double loadFactor, FiniteResult.ArcStep arc) {
        var domain = domainId(DIMENSION);
        var basis = new FiniteBasis(domain, 4, 2, "ab".repeat(32), "cd".repeat(32),
                "ef".repeat(32), 7);
        return new FiniteResult(basis, true, islands, Map.of(), new byte[0], loadFactor,
                domain.artifactNamespace(), "0123456789abcdef",
                new BsiFracture.Token(new java.util.UUID(9, 9), "abcd0123abcd0123"), Map.of(),
                arc, availability);
    }

    private static BsiCorot.Availability census(int memberProfiles, int material, int retained,
                                                int shellProfiles, int shellMaterial, int shellRetained) {
        return new BsiCorot.Availability(memberProfiles + shellProfiles > 0,
                memberProfiles, material, retained, shellProfiles, shellMaterial, shellRetained);
    }

    @Test void fieldsMapVerbatimFromTheNativeReply() {
        var q = FiniteQualification.of(result(
                List.of(new FiniteResult.Island(0, 0, 2, ""),
                        new FiniteResult.Island(1, 4, 0, "idle")),
                census(1, 1, 0, 0, 0, 0), 1.25, new FiniteResult.ArcStep(0.5, 1e-12)));
        assertEquals(4, q.worldRevision());
        assertEquals(2, q.historySequence());
        assertTrue(q.fullyConverged());
        assertTrue(q.loadFactorReported());
        assertEquals(1.25, q.loadFactor());
        assertEquals(2, q.islands().size());
        assertEquals("CONVERGED", q.islands().get(0).statusName());
        assertEquals("INACTIVE", q.islands().get(1).statusName());
        assertEquals("idle", q.islands().get(1).reason());
        assertTrue(q.materialHistoryPresent());
        assertNotNull(q.arc());
        assertEquals(0.5, q.arc().radius());
    }

    @Test void unknownIslandStatusStaysUnknownNeverGuessed() {
        var q = FiniteQualification.of(result(
                List.of(new FiniteResult.Island(0, 7, 0, "alien")),
                census(0, 0, 0, 0, 0, 0), Double.NaN, null));
        assertTrue(q.assessed());
        assertEquals(7, q.islands().get(0).status());
        assertEquals("UNKNOWN", q.islands().get(0).statusName());
    }

    @Test void emptyIslandTableIsNotAssessed() {
        var q = FiniteQualification.of(result(List.of(),
                census(0, 0, 0, 0, 0, 0), Double.NaN, null));
        assertFalse(q.assessed());
        assertFalse(q.loadFactorReported());          // NaN means unreported, not zero
        assertNull(q.arc());
        assertFalse(q.materialHistoryPresent());
    }

    @Test void nonConvergedIslandsAreTheirOwnStatusNotCollapse() {
        // MECHANISM and NOT_CONVERGED stay distinct wire codes — a display that merged
        // them would be a Java-invented collapse verdict.
        var q = FiniteQualification.of(result(
                List.of(new FiniteResult.Island(0, 1, 0, ""),
                        new FiniteResult.Island(1, 2, 0, "residual"),
                        new FiniteResult.Island(2, 0, 0, "")),
                census(0, 0, 0, 0, 0, 0), 1.0, null));
        assertEquals("MECHANISM", q.islands().get(0).statusName());
        assertEquals("NOT_CONVERGED", q.islands().get(1).statusName());
        assertEquals("CONVERGED", q.islands().get(2).statusName());
    }

    @Test void availabilityAbsenceAndRetainedCountsStayHonest() {
        // reported=false means no profile sections came back — displayed as unknown.
        var none = FiniteQualification.of(result(List.of(new FiniteResult.Island(0, 0, 0, "")),
                new BsiCorot.Availability(false, 0, 0, 0, 0, 0, 0), Double.NaN, null));
        assertFalse(none.availability().reported());
        assertFalse(none.materialHistoryPresent());
        assertFalse(none.shellMaterialReported());

        // Shell history with no member history: reported through the shell columns.
        var shell = FiniteQualification.of(result(List.of(new FiniteResult.Island(0, 0, 0, "")),
                census(0, 0, 0, 2, 2, 0), Double.NaN, null));
        assertTrue(shell.shellMaterialReported());
        assertTrue(shell.materialHistoryPresent());
    }

    @Test void invalidArcScalarsCannotConstruct() {
        assertThrows(IllegalArgumentException.class, () -> new FiniteResult.ArcStep(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new FiniteResult.ArcStep(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new FiniteResult.ArcStep(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new FiniteResult.ArcStep(1, -1e-12));
        assertThrows(IllegalArgumentException.class, () -> new FiniteResult.ArcStep(1, Double.NaN));
        var r = result(List.of(), census(0, 0, 0, 0, 0, 0), 1.0, null);
        assertTrue(r.loadFactorReported());
    }
}
