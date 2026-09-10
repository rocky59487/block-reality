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

    @Test
    void theNativeDefaultUsesEngineDofBudgetInsteadOfBlockCounts() throws Exception {
        String config = Files.readString(repoRoot().resolve("forge/src/main/java/com/blockreality/impl/BRConfig.java"));
        String manager = Files.readString(repoRoot().resolve("forge/src/main/java/com/blockreality/impl/server/StructureManager.java"));
        assertFalse(config.contains("bucklingBlockLimit"));
        assertTrue(config.contains("defineInRange(\"bucklingDofBudget\", 2400"));
        assertTrue(manager.contains("new BsiHeaders.EigenBuckling(BRConfig.INSTANCE.bucklingDofBudget.get())"));
        assertFalse(manager.contains("BucklingPolicy.enabled"));
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
