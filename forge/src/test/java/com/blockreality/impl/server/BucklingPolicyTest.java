package com.blockreality.impl.server;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The size policy's boundaries, pinned: equality runs, one past does not, zero never. */
class BucklingPolicyTest {

    @Test
    void theLimitIsInclusiveAndZeroMeansNever() {
        assertTrue(BucklingPolicy.enabled(299, 300));
        assertTrue(BucklingPolicy.enabled(300, 300), "the limit itself still runs");
        assertFalse(BucklingPolicy.enabled(301, 300), "one past the limit is skipped");
        assertFalse(BucklingPolicy.enabled(1, 0), "zero disables the screen outright");
        assertTrue(BucklingPolicy.enabled(0, 1), "an empty request is trivially within");
    }

    @Test
    void aNegativeLimitIsOffRatherThanInverted() {
        // Forge clamps the config to the declared range, but the policy is also called
        // from tests and from any future caller that has its own number. `limit > 0` is
        // the whole guard, so a negative can only mean off -- never "everything runs".
        assertFalse(BucklingPolicy.enabled(1, -1));
        assertFalse(BucklingPolicy.enabled(0, -1));
    }

    /** The block limit BRConfig actually ships, read out of the source. */
    private static final int SHIPPED_DEFAULT = 600;

    @Test
    void theShippedDefaultIsTheOneTheCostTableWasReadFor() throws Exception {
        // The number in BRConfig and the number its cost table justifies have to be the
        // same one. They were not: the comment quoted a fit that predates forcing the
        // sparse eigensolver and claimed 300 blocks cost about 2 s, where the measurement
        // is 1 ms for a beam and 27 ms for a floor of that size. A limit chosen against a
        // cost that is wrong by 80x is a limit chosen by accident.
        //
        // Read out of the source rather than asserted against itself. Instantiating
        // BRConfig needs Forge's builder, and a test that only compared 600 to 600 would
        // pass whatever the shipped file said -- which is the failure it exists to catch.
        Path config = repoRoot().resolve(
                "forge/src/main/java/com/blockreality/impl/BRConfig.java");
        String src = Files.readString(config, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile(
                "defineInRange\\(\"bucklingBlockLimit\", *(\\d+)").matcher(src);
        assertTrue(m.find(), "no bucklingBlockLimit default found in " + config);
        assertEquals(SHIPPED_DEFAULT, Integer.parseInt(m.group(1)),
                "the shipped default moved without this test and its cost table moving with it");

        // And the table that justifies it is still beside it, with the shape that costs
        // the most named. A number with its reasoning deleted is a number nobody can
        // re-derive when it next needs changing.
        assertTrue(src.contains("portal frame"),
                "the cost table the limit was chosen against is gone from the comment");

        assertTrue(BucklingPolicy.enabled(SHIPPED_DEFAULT, SHIPPED_DEFAULT));
        assertFalse(BucklingPolicy.enabled(SHIPPED_DEFAULT + 1, SHIPPED_DEFAULT));
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && p != null; i++) {
            if (Files.isDirectory(p.resolve("mod/api")) && Files.isDirectory(p.resolve("forge"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
    }
}
