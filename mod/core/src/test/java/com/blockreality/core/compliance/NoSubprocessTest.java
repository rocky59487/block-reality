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
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * N24-a2: the shipping code starts no child processes, except where this file says it does.
 *
 * <p>This is a source scan, not a behaviour test, and that is deliberate. The rule it enforces
 * is about what a reviewer finds when they open the jar, and no runtime assertion can say
 * "nothing anywhere in this artefact spawns a process". A grep can.
 *
 * <p>The allowance below is the honest part. The mod TODAY still starts the sidecar: the game
 * flow has not moved to the in-process engine yet (that is SWAP_PROGRAM phase 3), so one class
 * spawns and the gate would be a lie if it claimed otherwise. What the gate does enforce is that
 * the list does not grow, and that when the sidecar retires the entry disappears with it rather
 * than quietly covering something new. An allowance nobody can add to is worth more than a rule
 * that had to be switched off.
 */
class NoSubprocessTest {

    /**
     * Classes permitted to start a process, and why. Every entry is a debt with a payoff date,
     * not a permanent carve-out; docs/GATES.md N24-a2 carries the same list.
     */
    private static final Map<String, String> ALLOWED = new TreeMap<>(Map.of(
            "mod/core/src/main/java/com/blockreality/core/sidecar/SidecarProcess.java",
            "the sidecar shipping shape (D-013/D-027), which D-044 retires. When the game flow "
                    + "moves to InProcessEngine this entry and this file go together."
    ));

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
                    + "\nIf one of them genuinely must, add it to ALLOWED here AND to "
                    + "docs/GATES.md N24-a2, with the reason and what retires it.");
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
