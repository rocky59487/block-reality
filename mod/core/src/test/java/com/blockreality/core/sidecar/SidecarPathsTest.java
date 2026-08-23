package com.blockreality.core.sidecar;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A string that cannot be a path must not be able to stop a world loading.
 *
 * <p>{@code Path.of} throws an UNCHECKED {@code InvalidPathException}, and the engine
 * search runs inside a chunk-load handler through {@code computeIfAbsent} — which keeps no
 * mapping when the factory throws, so the crash repeats on every chunk. The strings that
 * do it are what Windows Explorer's "Copy as path" produces and what a careless paste
 * leaves behind, not anything exotic.
 */
class SidecarPathsTest {

    private final List<String> notes = new ArrayList<>();

    @Test
    void whatExplorersCopyAsPathGivesYouIsAPath() {
        // The quotes are the whole point: this is the clipboard contents, unedited.
        Optional<Path> p = SidecarPaths.parse("\"C:/tools/br-sidecar.exe\"", notes::add);
        assertEquals(Path.of("C:/tools/br-sidecar.exe"), p.orElseThrow());
        assertTrue(notes.isEmpty(), notes.toString());
        assertEquals(Path.of("C:/tools/br-sidecar.exe"),
                SidecarPaths.parse("'C:/tools/br-sidecar.exe'", notes::add).orElseThrow());
    }

    @Test
    void surroundingSpaceIsNotPartOfTheName() {
        assertEquals(Path.of("/opt/br-sidecar"),
                SidecarPaths.parse("  /opt/br-sidecar  ", notes::add).orElseThrow());
        assertEquals(Path.of("/opt/br-sidecar"),
                SidecarPaths.parse(" \" /opt/br-sidecar \" ", notes::add).orElseThrow());
    }

    @Test
    void aStringThatCannotBeAPathIsReportedAndNotThrown() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"),
                "only Windows rejects these characters");
        // A lone quote is not a wrapper — it is a broken string, and dropping half of it
        // would change which file is meant.
        assertTrue(SidecarPaths.parse("\"C:/tools/br-sidecar.exe", notes::add).isEmpty());
        assertEquals(1, notes.size(), notes.toString());
        assertTrue(notes.get(0).contains("not a usable path"), notes.get(0));
    }

    @Test
    void blankAndNullAreSimplyAbsent() {
        assertTrue(SidecarPaths.parse(null, notes::add).isEmpty());
        assertTrue(SidecarPaths.parse("   ", notes::add).isEmpty());
        assertTrue(SidecarPaths.parse("\"\"", notes::add).isEmpty());
        assertTrue(notes.isEmpty(), "an absent setting is not an error: " + notes);
    }
}
