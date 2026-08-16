package com.blockreality.core.render;

import com.blockreality.api.EndForces;
import com.blockreality.api.GoverningFibre;
import com.blockreality.api.Fibre;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.StressStation;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.geom.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberPickTest {

    /** A member running along +x at the given height and z, in blocks. */
    private static MemberSnapshot member(int id, double y, double z, double x0, double x1) {
        List<StressStation> st = new ArrayList<>();
        for (int q = 0; q <= 10; q++) {
            double t = q / 10.0;
            double x = (x0 + (x1 - x0) * t) * 1000;
            st.add(new StressStation(0, new Vec3d(x, y * 1000, z * 1000),
                    List.of(new Fibre("TOP_Y", new Vec3d(0, 1, 0), 200, 1)),
                    1, 0, 0, Optional.empty(), Optional.empty()));
        }
        return new MemberSnapshot(id, "steel", "steel_rect_200x400", (x1 - x0) * 1000,
                0.5, GoverningFibre.CRUSH, 0, EndForces.ZERO, EndForces.ZERO,
                List.of(new BlockKey(0, (int) y, (int) z)), st, java.util.Optional.empty());
    }

    @Test
    void picksTheMemberUnderTheCrosshair() {
        List<MemberSnapshot> ms = List.of(member(1, 64, 0, 0, 10), member(2, 64, 20, 0, 10));
        // Standing at the origin looking along +x, down the first member.
        var picked = MemberPick.pick(new Vec3d(0, 64, 0), new Vec3d(1, 0, 0), ms);
        assertEquals(1, picked.orElseThrow());
    }

    @Test
    void aMemberYouAreNotLookingAtDoesNotStealFocus() {
        // Member 2 is closer in a straight line but well off to the side.
        List<MemberSnapshot> ms = List.of(member(1, 64, 0, 5, 30), member(2, 64, 3, 0, 1));
        var picked = MemberPick.pick(new Vec3d(0, 64, 0), new Vec3d(1, 0, 0), ms);
        assertEquals(1, picked.orElseThrow());
    }

    @Test
    void lookingAtNothingPicksNothing() {
        List<MemberSnapshot> ms = List.of(member(1, 64, 0, 0, 10));
        // Facing away.
        assertTrue(MemberPick.pick(new Vec3d(0, 64, 0), new Vec3d(-1, 0, 0), ms).isEmpty());
        // Facing up.
        assertTrue(MemberPick.pick(new Vec3d(0, 64, 0), new Vec3d(0, 1, 0), ms).isEmpty());
    }

    @Test
    void aDistantMemberIsContextNotASubject() {
        // Directly on axis, but past the cut-off: it must not be selected.
        List<MemberSnapshot> ms = List.of(member(1, 64, 0, 200, 210));
        assertTrue(MemberPick.pick(new Vec3d(0, 64, 0), new Vec3d(1, 0, 0), ms).isEmpty());
    }

    @Test
    void aLongMemberIsSelectableByItsMiddleNotJustItsEnds() {
        // Looking sideways at the middle of a beam that runs across the view.
        List<MemberSnapshot> ms = List.of(member(1, 64, 10, -20, 20));
        var picked = MemberPick.pick(new Vec3d(0, 64, 0), new Vec3d(0, 0, 1), ms);
        assertEquals(1, picked.orElseThrow());
    }

    @Test
    void anEmptyWorldPicksNothingAndDoesNotThrow() {
        assertTrue(MemberPick.pick(Vec3d.ZERO, new Vec3d(1, 0, 0), List.of()).isEmpty());
    }
}
