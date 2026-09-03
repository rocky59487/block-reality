package com.blockreality.core.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The override directory beating the bundled copy IS the mechanism that lets an engine be
 * updated without the mod and a mod without the engine (D-044 §3-4). It is tested here rather
 * than assumed, because the day it silently reverses, both halves look fine and neither updates.
 */
class EngineLocatorTest {

    @Test
    void theOverrideDirectoryWinsOverTheBundledCopy(@TempDir Path dir) throws Exception {
        Path override = Files.createDirectories(dir.resolve("engine").resolve(EngineLocator.platform()));
        Path newer = Files.write(override.resolve(EngineLocator.libraryFileName("bsi_tectonic")), new byte[]{1});
        Path bundled = Files.write(dir.resolve("bundled.so"), new byte[]{2});

        EngineLocator.Located found = EngineLocator.locate(null, null, null, dir.resolve("engine"), bundled);
        assertEquals(newer, found.path());
        assertEquals(EngineLocator.Source.OVERRIDE_DIRECTORY, found.source());
    }

    @Test
    void theBundledCopyIsUsedWhenNothingOverridesIt(@TempDir Path dir) throws Exception {
        Path bundled = Files.write(dir.resolve("bundled.so"), new byte[]{2});
        EngineLocator.Located found = EngineLocator.locate(null, null, null, dir.resolve("engine"), bundled);
        assertEquals(bundled, found.path());
        assertEquals(EngineLocator.Source.BUNDLED, found.source());
    }

    @Test
    void configAndPropertyOutrankEverythingAndAbsenceIsReportedNotGuessed(@TempDir Path dir) throws Exception {
        Path configured = Files.write(dir.resolve("configured.so"), new byte[]{3});
        Path bundled = Files.write(dir.resolve("bundled.so"), new byte[]{2});
        assertEquals(EngineLocator.Source.CONFIG,
                EngineLocator.locate(configured.toString(), null, null, dir.resolve("engine"), bundled).source());

        EngineLocator.Located none = EngineLocator.locate(null, null, null, dir.resolve("absent"), dir.resolve("missing.so"));
        assertNull(none.path());
        assertEquals(EngineLocator.Source.NONE, none.source(), "an absent engine is reported, never guessed at");
    }

    @Test
    void aConfiguredPathThatDoesNotExistFallsThroughRatherThanFailing(@TempDir Path dir) throws Exception {
        Path bundled = Files.write(dir.resolve("bundled.so"), new byte[]{2});
        EngineLocator.Located found = EngineLocator.locate(dir.resolve("nope.so").toString(), null, null, null, bundled);
        assertEquals(bundled, found.path(), "a stale config entry must not disable an engine that is present");
    }

    @Test
    void thePropertyOutranksTheOverrideDirectoryAndTheEnvironmentOutranksTheBundledCopy(@TempDir Path dir) throws Exception {
        Path viaProperty = Files.write(dir.resolve("property.so"), new byte[]{4});
        Path viaEnv = Files.write(dir.resolve("env.so"), new byte[]{5});
        Path override = Files.createDirectories(dir.resolve("engine").resolve(EngineLocator.platform()));
        Files.write(override.resolve(EngineLocator.libraryFileName("bsi_tectonic")), new byte[]{1});
        Path bundled = Files.write(dir.resolve("bundled.so"), new byte[]{2});

        assertEquals(EngineLocator.Source.SYSTEM_PROPERTY,
                EngineLocator.locate(null, viaProperty.toString(), viaEnv.toString(), dir.resolve("engine"), bundled).source());
        assertEquals(EngineLocator.Source.ENVIRONMENT,
                EngineLocator.locate(null, null, viaEnv.toString(), dir.resolve("engine"), bundled).source());
        assertEquals(EngineLocator.Source.OVERRIDE_DIRECTORY,
                EngineLocator.locate(null, null, null, dir.resolve("engine"), bundled).source());
    }

    @Test
    void theSystemFormReadsTheSamePropertyTheBuildForwards(@TempDir Path dir) throws Exception {
        Path bundled = Files.write(dir.resolve("bundled.so"), new byte[]{2});
        String prop = System.getProperty("br.engine", "");
        EngineLocator.Located found = EngineLocator.locateFromSystem(null, null, bundled);
        if (prop.isBlank()) {
            assertEquals(EngineLocator.Source.BUNDLED, found.source());
        } else {
            assertEquals(EngineLocator.Source.SYSTEM_PROPERTY, found.source(), "-Dbr.engine=" + prop + " must win");
        }
    }

    @Test
    void platformAndFileNameFollowTheManifestSpelling() {
        String p = EngineLocator.platform();
        assertTrue(p.matches("(linux|windows|macos|unknown)-(x86_64|aarch64|[a-z0-9_]+)"), p);
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String f = EngineLocator.libraryFileName("bsi_tectonic");
        if (os.contains("win")) assertEquals("bsi_tectonic.dll", f);
        else if (os.contains("mac")) assertEquals("libbsi_tectonic.dylib", f);
        else assertEquals("libbsi_tectonic.so", f);
    }
}
