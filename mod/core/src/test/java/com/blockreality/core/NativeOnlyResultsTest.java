package com.blockreality.core;

import com.blockreality.api.*;
import com.blockreality.api.geom.Vec3d;
import com.blockreality.core.render.SectionDiagram;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeOnlyResultsTest {
    @Test void everyPublicSnapshotConstructorRequiresAnExplicitVerdict() {
        for (var constructor : MemberSnapshot.class.getConstructors()) {
            assertEquals(14, constructor.getParameterCount());
            assertEquals(boolean.class, constructor.getParameterTypes()[12]);
        }
        for (var constructor : ShellSnapshot.class.getConstructors()) {
            assertEquals(12, constructor.getParameterCount());
            assertEquals(boolean.class, constructor.getParameterTypes()[10]);
        }
        for (var constructor : AnalysisResult.class.getConstructors()) {
            assertTrue(constructor.getParameterCount() == 17 || constructor.getParameterCount() == 18);
            assertEquals(boolean.class, constructor.getParameterTypes()[15]);
            assertEquals(boolean.class, constructor.getParameterTypes()[16]);
        }
        for (var type : List.of(MemberSnapshot.class, ShellSnapshot.class)) {
            assertFalse(Arrays.stream(type.getRecordComponents()).anyMatch(c -> c.getName().equals("field")));
        }
        assertFalse(Arrays.stream(SectionDiagram.class.getDeclaredMethods()).anyMatch(m -> m.getName().equals("of")));
    }

    @Test void suppliedFlagsSurviveNumericValuesThatSuggestTheOppositeVerdict() {
        var member = new MemberSnapshot(3, "steel", "rect", 2000, 200, GoverningFibre.CRUSH, -1,
                EndForces.ZERO, EndForces.ZERO, List.of(), List.of(), Optional.empty(), false, Optional.empty());
        var shell = new ShellSnapshot(4, "panel", "panel", 200, .01, Double.NaN, true, false,
                List.of(), Optional.empty(), true, 6);
        var result = new AnalysisResult(new WorldRevision(7), true, false, "", 200, 3, "member", 1, 0,
                0, 20, BucklingState.COMPUTED, List.of(member), List.of(shell), List.of(), false, true);
        assertFalse(member.overloaded()); assertFalse(member.isOverloaded());
        assertTrue(shell.overloaded()); assertFalse(result.overCapacity()); assertTrue(result.bucklingCritical());
    }

    @Test void absentDisplayAndNeutralAxisCannotBeReconstructedFromForcesOrOppositeSigns() {
        var station = new StressStation(0, Vec3d.ZERO, List.of(
                new Fibre("TOP_Y", new Vec3d(0, 1, 0), 200, 10),
                new Fibre("BOT_Y", new Vec3d(0, -1, 0), 200, -30)),
                10, 30, 0, Optional.empty(), Optional.empty());
        var member = new MemberSnapshot(3, "steel", "rect", 2000, 0, GoverningFibre.NONE, -1,
                new EndForces(1e99, 1e99, 1e99, 1e99, 1e99, 1e99), EndForces.ZERO,
                List.of(), List.of(station), Optional.empty(), false, Optional.empty());
        assertTrue(member.display().isEmpty()); assertEquals(List.of(station), member.stations());
        assertTrue(SectionDiagram.sampled(station).orElseThrow().neutralFraction().isEmpty());
    }
}
