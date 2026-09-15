package com.blockreality.core.compliance;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Source-level packaging guard: production Java must contain no process launcher (N24-a2/GR-6). */
class NoSubprocessTest {

    /** Empty after GAME_RUNTIME; historical launchers remain only under test sources. */
    private static final Map<String, String> ALLOWED = Map.of();

    /** What counts as starting a process. */
    private static final List<String> SPAWNERS = List.of(
            "ProcessBuilder",
            "Runtime.getRuntime().exec",
            "Runtime.getRuntime( ).exec",
            "ProcessHandle.current().destroy"
    );

    /** Source roots that end up in the shipped jar. */
    private static final List<String> SHIPPING_ROOTS = List.of(
            "mod/api/src/main/java",
            "mod/core/src/main/java",
            "forge/src/main/java"
    );

    static Path repoRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && p != null; i++) {
            if (Files.isDirectory(p.resolve("mod")) && Files.isDirectory(p.resolve("forge"))
                    && Files.isDirectory(p.resolve("contract"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("could not find the repository root from "
                + System.getProperty("user.dir"));
    }

    @Test
    void theShippedCodeStartsNoProcessesExceptTheOnesNamedHere() throws IOException {
        Path root = repoRoot();
        List<String> unexpected = new ArrayList<>();
        Set<String> allowedButClean = new java.util.TreeSet<>(ALLOWED.keySet());
        int scanned = 0;

        for (String rel : SHIPPING_ROOTS) {
            Path dir = root.resolve(rel);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    scanned++;
                    String key = root.relativize(f).toString().replace('\\', '/');
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    boolean spawns = SPAWNERS.stream().anyMatch(text::contains);
                    if (spawns && !ALLOWED.containsKey(key)) {
                        unexpected.add(key);
                    }
                    if (spawns) allowedButClean.remove(key);
                }
            }
        }

        assertTrue(scanned > 20, "the scan only saw " + scanned + " source files — it is looking "
                + "in the wrong place, and a scan that finds nothing passes for the wrong reason");

        if (!unexpected.isEmpty()) {
            fail("N24-a2: these shipped classes start a child process, and the packaging rule "
                    + "(D-044) is that the distribution contains no programs to start:\n  "
                    + String.join("\n  ", unexpected)
                    + "\nProduction process launchers violate GAME_RUNTIME GR-6.");
        }

        // ...and the reverse. An allowance for a class that no longer spawns is a comment
        // claiming a debt that has been paid, which is how the list stops meaning anything.
        assertEquals(Set.of(), allowedButClean,
                "these classes are allowed to start a process and no longer do — remove the "
                        + "allowance here and in docs/GATES.md N24-a2");
    }

    /** The in-process engine is the point of D-044: it must not have acquired a spawn path. */
    @Test
    void theInProcessEngineStartsNothing() throws IOException {
        Path dir = repoRoot().resolve("mod/core/src/main/java/com/blockreality/core/engine");
        assertTrue(Files.isDirectory(dir), dir + " is missing");
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                for (String s : SPAWNERS) {
                    assertTrue(!text.contains(s), f.getFileName() + " contains " + s);
                }
            }
        }
    }
}
