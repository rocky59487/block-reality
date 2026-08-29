package com.blockreality.core.render;

import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.geom.BlockKey;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which cells the overlay is allowed to paint.
 *
 * <p>An analysis result is a picture of the world as it was when the request was gathered.
 * The overlay used to trust it for <em>geometry</em> as well as for stress, so mining a
 * structural block left its coloured shell hanging in the air until the next solve came
 * back — the tick throttle plus the solve plus the round trip, which is half a second of
 * the game showing a wall that is not there.
 *
 * <p>That delay is avoidable and the fix is not a faster solve. The client does not need
 * to be told where the blocks are: it is standing in the world. Stress is the server's to
 * say and arrives when it arrives; <strong>presence</strong> is something the client
 * already knows this frame. So the drawable set is the answer's cells intersected with
 * the ones that still hold a structural block.
 *
 * <p>The two directions are deliberately not symmetric, because the mistakes are not:
 *
 * <ul>
 *   <li>A block that is <em>gone</em> stops being painted at once. Painting a stress on
 *       empty air is a claim about something that does not exist.
 *   <li>A block that is <em>new</em> is not painted until the next result. Nobody has
 *       computed its stress, and inventing one would be worse than leaving it plain.
 * </ul>
 *
 * <p>Keeping this here rather than in the renderer is what makes it testable: the api
 * layer carries no Minecraft types (D-015), so the predicate is just a question about a
 * coordinate and a headless test can answer it.
 */
public final class DrawableCells {

    private DrawableCells() { }

    /**
     * Packs a block coordinate into one long: 26 bits of x, 26 of z, 12 of y.
     *
     * <p>Enough for the world border and the full build height, and it keeps the occupancy
     * set free of object churn on a path that runs every frame.
     */
    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static long key(BlockKey b) { return key(b.x(), b.y(), b.z()); }

    /**
     * The cells of this answer that {@code present} still vouches for.
     *
     * <p>Used for two things at once, and they have to agree or the picture is wrong: the
     * blocks that get painted, and the occupancy that decides which faces are interior.
     * A face hidden behind a block that has since been mined must become visible in the
     * same frame the block does, or the structure looks hollowed out from the wrong side.
     */
    public static Set<Long> of(List<MemberSnapshot> members, List<ShellSnapshot> shells,
                               Predicate<BlockKey> present) {
        Set<Long> set = new HashSet<>();
        if (members != null) {
            for (MemberSnapshot m : members) {
                for (BlockKey b : m.blocks()) {
                    if (present.test(b)) set.add(key(b));
                }
            }
        }
        // Plate blocks occupy cells too. Without them the underside of a floor would be
        // drawn where a beam is buried in it, and the two surfaces would fight.
        if (shells != null) {
            for (ShellSnapshot s : shells) {
                for (BlockKey b : s.blocks()) {
                    if (present.test(b)) set.add(key(b));
                }
            }
        }
        return set;
    }

    /** Every cell of the answer, asking nothing of the world. */
    public static Set<Long> of(List<MemberSnapshot> members, List<ShellSnapshot> shells) {
        return of(members, shells, b -> true);
    }
}
