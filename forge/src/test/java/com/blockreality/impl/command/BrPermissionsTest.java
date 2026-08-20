package com.blockreality.impl.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The structural lock for #45: every {@code /br} subcommand outside the read-only
 * whitelist must require operator level 2.
 *
 * <p>This is an enumeration test, not a spot check. The audit's finding was not "scan
 * has the wrong level" — it was that a NEW command copied an old command's weak
 * default and nothing was positioned to notice (6.2's (b) failure class). Walking the
 * whole table means the next copied default fails here on the day it is written, and
 * a subcommand added to the tree without a table entry fails registration outright.
 */
class BrPermissionsTest {

    @Test
    void everythingOutsideTheReadOnlyWhitelistRequiresOp() {
        for (String literal : BrPermissions.literals()) {
            if (BrPermissions.READ_ONLY_WHITELIST.contains(literal)) {
                assertEquals(BrPermissions.LEVEL_ALL, BrPermissions.required(literal),
                        "whitelisted subcommand '" + literal + "' should be open");
            } else {
                assertTrue(BrPermissions.required(literal) >= BrPermissions.LEVEL_OP,
                        "subcommand '" + literal + "' is not whitelisted read-only and must "
                                + "require permission level >= 2 (#45)");
            }
        }
    }

    @Test
    void theWhitelistIsExactlyTheReadOnlyDiagnostics() {
        // Locked by name: growing this set is a security decision, and the diff that
        // does it should have to touch a test spelling out what the set means.
        assertEquals(java.util.Set.of("status", "members", "section", "loads"),
                BrPermissions.READ_ONLY_WHITELIST);
        assertTrue(BrPermissions.literals().containsAll(BrPermissions.READ_ONLY_WHITELIST),
                "every whitelisted name must be a real subcommand");
    }

    @Test
    void privilegedSubcommandsAreAllPresent() {
        // The commands the audit called out by name (#45): scan, load, unload, resolve
        // — plus reset, which was already gated. Each must be in the table AND above 0.
        for (String cmd : new String[] { "scan", "load", "unload", "resolve", "reset" }) {
            assertTrue(BrPermissions.literals().contains(cmd), cmd + " missing from table");
            assertTrue(BrPermissions.required(cmd) >= BrPermissions.LEVEL_OP,
                    cmd + " must require op");
        }
    }

    @Test
    void anUnknownLiteralIsARegistrationError() {
        // The tree builder consults the table; a subcommand with no entry must fail
        // loudly at registration, never fall back to an implicit level 0.
        assertThrows(IllegalArgumentException.class, () -> BrPermissions.required("nuke"));
    }
}
