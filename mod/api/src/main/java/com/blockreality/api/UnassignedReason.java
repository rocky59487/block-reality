package com.blockreality.api;

import java.util.Locale;

/**
 * Why a block produced no element result.
 *
 * <p>The engine reports one of these per group of blocks. Every code names something the
 * extractor or the solver <em>did</em> with the block — never anything about an internal
 * force, which is what keeps this a bookkeeping field and not a back door for mechanics on
 * this side of the boundary (N17-f, D-033).
 *
 * <p>The set is <strong>open on the wire</strong>. A code this build does not know becomes
 * {@link #UNKNOWN}, which still carries its blocks and still gets a line in the HUD, so a
 * later engine can add one (D-030's {@code BULK_UNSUPPORTED} is next in line) without a
 * protocol change and without any block going quiet on the way through.
 */
public enum UnassignedReason {

    /** Whatever piece the block landed in was one block long: L/h = 1, no beam theory. */
    RUN_TOO_SHORT("RUN_TOO_SHORT", true),

    /** A plate block with no plate neighbour on any axis — no plane to lie in. */
    PLATE_LONE("PLATE_LONE", true),

    /** A plate strip one block wide: two free axes, so it cannot close a square. */
    PLATE_STRIP("PLATE_STRIP", true),

    /** Plate blocks stacked into a solid. A solid is not a shell. */
    PLATE_SOLID("PLATE_SOLID", true),

    /** The plane is well defined, but no complete 2x2 facet closed around this block. */
    PLATE_NO_FACET("PLATE_NO_FACET", true),

    /**
     * The block IS part of a structure — one with no restraint at all, which the engine's
     * factorisation reports as rank-deficient. There is no answer to give for a mechanism,
     * but the blocks are not nothing, and before N17 they were absent from the reply
     * altogether: neither a member, nor a shell, nor unassigned.
     */
    MECHANISM("MECHANISM", false),

    /**
     * The block IS part of a structure, and every node of that structure is grounded.
     * Nothing can move, so there is no internal response to report. A sound state, not a
     * failure: it shares this field with the codes above but must never share their
     * sentence (N17-e).
     */
    FULLY_SUPPORTED("FULLY_SUPPORTED", false),

    /** A code this build does not know. Its blocks are still listed and still shown. */
    UNKNOWN("", false);

    private final String wire;
    private final boolean formsNoElement;

    UnassignedReason(String wire, boolean formsNoElement) {
        this.wire = wire;
        this.formsNoElement = formsNoElement;
    }

    /** The token as the engine spells it; empty for {@link #UNKNOWN}. */
    public String wire() { return wire; }

    /**
     * Whether the block belongs to no element at all.
     *
     * <p>This is the distinction that decides whether a test load on the block can ever be
     * accepted. The engine refuses an entire request carrying a load on a block that
     * belongs to no element, and keeps refusing it every tick, so the host drops such
     * loads. For {@link #MECHANISM} and {@link #FULLY_SUPPORTED} the block IS a node of an
     * element: the load travels fine, and dropping it would be deleting the player's input
     * over a reason that does not apply.
     *
     * <p>{@link #UNKNOWN} answers {@code false} deliberately. Between a load that keeps
     * being refused until the player removes it, which is visible and recoverable, and the
     * game silently deleting something the player placed while citing a reason it could not
     * read, only the second cannot be undone.
     */
    public boolean formsNoElement() { return formsNoElement; }

    /** Translation key for the HUD and {@code /br status}. */
    public String translationKey() {
        return "br.unassigned." + name().toLowerCase(Locale.ROOT);
    }

    /** Parses a wire token, mapping anything unrecognised to {@link #UNKNOWN}. */
    public static UnassignedReason fromWire(String token) {
        if (token != null) {
            for (UnassignedReason r : values()) {
                if (!r.wire.isEmpty() && r.wire.equals(token)) return r;
            }
        }
        return UNKNOWN;
    }
}
