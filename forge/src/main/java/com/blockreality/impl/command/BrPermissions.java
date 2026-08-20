package com.blockreality.impl.command;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The permission level of every {@code /br} subcommand, as data.
 *
 * <p>One table, consulted both by the command tree builder and by the test suite. The
 * point is structural (#45): the audit found that a NEW subcommand ({@code /br load})
 * had quietly copied the weak default of an old one ({@code /br scan} at level 0), and
 * nothing could catch that class of mistake because the levels were scattered across
 * the builder calls. With the table, {@code BrPermissionsTest} walks every registered
 * literal and asserts that anything outside the read-only whitelist requires level 2 —
 * so the next copied-weak-default fails a unit test the day it is written (6.2).
 *
 * <p>Why these levels:
 * <ul>
 *   <li><strong>Read-only diagnostics stay open</strong> — status, members, section,
 *       loads print state and change nothing.
 *   <li><strong>{@code scan}</strong> walks up to 1089 chunks on the server thread: a
 *       griefing lever if any player can spam it.
 *   <li><strong>{@code load}/{@code unload}</strong> mutate the shared model: any
 *       survival player applying megaNewtons to someone else's build is wrong on a
 *       multiplayer server even though no block changes.
 *   <li><strong>{@code resolve}</strong> forces a full re-analysis — cheap once, a
 *       denial-of-service lever repeated.
 *   <li><strong>{@code reset}</strong> restarts the engine process.
 * </ul>
 *
 * <p>No Minecraft imports, so the table is testable from a plain JUnit run.
 */
public final class BrPermissions {

    /** Vanilla operator permission level used for privileged subcommands. */
    public static final int LEVEL_OP = 2;

    /** Anybody, including survival players without operator status. */
    public static final int LEVEL_ALL = 0;

    /**
     * Every top-level literal under {@code /br} and the level it requires. Nested
     * literals ({@code unload all}) inherit their parent's gate: Brigadier checks
     * {@code requires} on every node along the executed path.
     */
    private static final Map<String, Integer> LITERALS;

    /** The literals that are deliberately open to everyone. Everything else must be OP. */
    public static final Set<String> READ_ONLY_WHITELIST =
            Set.of("status", "members", "section", "loads");

    static {
        Map<String, Integer> m = new TreeMap<>();
        m.put("status", LEVEL_ALL);
        m.put("members", LEVEL_ALL);
        m.put("section", LEVEL_ALL);
        m.put("loads", LEVEL_ALL);
        m.put("resolve", LEVEL_OP);
        m.put("scan", LEVEL_OP);
        m.put("load", LEVEL_OP);
        m.put("unload", LEVEL_OP);
        m.put("reset", LEVEL_OP);
        LITERALS = Map.copyOf(m);
    }

    private BrPermissions() { }

    /** All top-level literals, for the tree builder and the enumeration test. */
    public static Set<String> literals() { return LITERALS.keySet(); }

    /**
     * @throws IllegalArgumentException for a literal not in the table — a new
     *         subcommand must declare its level here first, which is the whole point
     */
    public static int required(String literal) {
        Integer level = LITERALS.get(literal);
        if (level == null) {
            throw new IllegalArgumentException(
                    "subcommand '" + literal + "' has no entry in BrPermissions");
        }
        return level;
    }
}
