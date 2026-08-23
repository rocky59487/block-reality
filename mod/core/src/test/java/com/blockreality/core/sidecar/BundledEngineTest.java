package com.blockreality.core.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine now travels inside the jar, so the unpacking is the install (D-027).
 *
 * <p>Everything a player used to do by hand — download the right binary, put it where the
 * mod looks, mark it executable — happens on first use instead. That makes this code the
 * install procedure, and an install procedure that can write the wrong bytes, or a
 * half-written file, or quietly overwrite a binary the player chose, is worse than the
 * manual step it replaces. Each of those is a case below.
 */
class BundledEngineTest {

    private static final byte[] WIN = "windows-engine-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LIN = "linux-engine-bytes".getBytes(StandardCharsets.UTF_8);

    private static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String manifest() {
        return "# os arch file sha256 size\n"
                + "windows x86_64 br-sidecar.exe " + sha(WIN) + " " + WIN.length + "\n"
                + "\n"
                + "linux x86_64 br-sidecar " + sha(LIN) + " " + LIN.length + "\n";
    }

    /** A jar that carries the manifest and both binaries. */
    private static BundledEngine.Loader jar() {
        return path -> switch (path) {
            case BundledEngine.MANIFEST -> new ByteArrayInputStream(manifest().getBytes(StandardCharsets.UTF_8));
            case "/blockreality/engine/br-sidecar.exe" -> new ByteArrayInputStream(WIN);
            case "/blockreality/engine/br-sidecar" -> new ByteArrayInputStream(LIN);
            default -> null;
        };
    }

    private final List<String> log = new ArrayList<>();

    // ------------------------------------------------------------------ manifest

    @Test
    void theManifestParsesAndCommentsAndBlanksAreSkipped() {
        List<BundledEngine.Entry> e = BundledEngine.parse(manifest());
        assertEquals(2, e.size());
        assertEquals("br-sidecar.exe", e.get(0).fileName());
        assertEquals(WIN.length, e.get(0).size());
        assertEquals(12, e.get(0).shortHash().length());
        assertEquals("/blockreality/engine/br-sidecar", e.get(1).resource());
    }

    @Test
    void aManifestThisCodeOnlyHalfUnderstandsIsAnError() {
        // The one way this class could unpack the wrong bytes is by guessing at a line it
        // does not recognise, so every malformed shape throws rather than being skipped.
        assertThrows(IllegalArgumentException.class,
                () -> BundledEngine.parse("windows x86_64 br-sidecar.exe " + sha(WIN)));
        assertThrows(IllegalArgumentException.class,
                () -> BundledEngine.parse("windows x86_64 br-sidecar.exe not-a-hash 12"));
        assertThrows(IllegalArgumentException.class,
                () -> BundledEngine.parse("windows x86_64 br-sidecar.exe " + sha(WIN) + " nought"));
        assertThrows(IllegalArgumentException.class,
                () -> BundledEngine.parse("windows x86_64 br-sidecar.exe " + sha(WIN) + " 0"));
    }

    @Test
    void theBinaryIsChosenByPlatformAndNeverByGuesswork() {
        List<BundledEngine.Entry> e = BundledEngine.parse(manifest());
        assertEquals("br-sidecar.exe",
                BundledEngine.select(e, "Windows 11", "amd64").orElseThrow().fileName());
        assertEquals("br-sidecar",
                BundledEngine.select(e, "Linux", "x86_64").orElseThrow().fileName());
        // No macOS engine is built, and an ARM machine must not be handed an x86-64 one:
        // it would fail as a process-start error much later and somewhere else.
        assertTrue(BundledEngine.select(e, "Mac OS X", "aarch64").isEmpty());
        assertTrue(BundledEngine.select(e, "Mac OS X", "x86_64").isEmpty());
        assertTrue(BundledEngine.select(e, "Linux", "aarch64").isEmpty());
        assertTrue(BundledEngine.select(e, "Plan 9", "amd64").isEmpty());
    }

    // ------------------------------------------------------------------ unpacking

    @Test
    void firstUseUnpacksTheRightBytesUnderAHashNamedPath(@TempDir Path root) throws IOException {
        Path p = BundledEngine.ensure(root, jar(), "Linux", "amd64", log::add).orElseThrow();
        assertArrayEqualsOnDisk(LIN, p);
        assertEquals(sha(LIN).substring(0, 12), p.getParent().getFileName().toString());
        assertTrue(log.stream().anyMatch(s -> s.contains("unpacked")), log.toString());
    }

    @Test
    void aSecondLaunchDoesNotRewriteIt(@TempDir Path root) throws IOException {
        Path first = BundledEngine.ensure(root, jar(), "Linux", "amd64", log::add).orElseThrow();
        long stamp = Files.getLastModifiedTime(first).toMillis();
        log.clear();

        Path second = BundledEngine.ensure(root, jar(), "Linux", "amd64", log::add).orElseThrow();
        assertEquals(first, second);
        assertEquals(stamp, Files.getLastModifiedTime(second).toMillis(), "file was rewritten");
        assertFalse(log.stream().anyMatch(s -> s.contains("unpacked")), log.toString());
    }

    @Test
    void aCorruptedOrTruncatedFileOnDiskIsReplaced(@TempDir Path root) throws IOException {
        Path p = BundledEngine.ensure(root, jar(), "Linux", "amd64", log::add).orElseThrow();
        Files.write(p, "half a file".getBytes(StandardCharsets.UTF_8));

        Path again = BundledEngine.ensure(root, jar(), "Linux", "amd64", log::add).orElseThrow();
        assertEquals(p, again);
        assertArrayEqualsOnDisk(LIN, again);
    }

    @Test
    void aJarWhoseBytesDisagreeWithItsManifestUnpacksNothing(@TempDir Path root) {
        // Not a paranoid case: it is what a partially-downloaded or repackaged jar looks
        // like, and the wrong outcome is an executable that runs.
        BundledEngine.Loader tampered = path -> path.equals(BundledEngine.MANIFEST)
                ? new ByteArrayInputStream(manifest().getBytes(StandardCharsets.UTF_8))
                : new ByteArrayInputStream("something else entirely".getBytes(StandardCharsets.UTF_8));

        assertTrue(BundledEngine.ensure(root, tampered, "Linux", "amd64", log::add).isEmpty());
        assertTrue(log.stream().anyMatch(s -> s.contains("could not unpack")), log.toString());
        assertFalse(Files.exists(root.resolve(sha(LIN).substring(0, 12)).resolve("br-sidecar")));
    }

    @Test
    void aManifestWithoutTheFileItPromisesUnpacksNothing(@TempDir Path root) {
        BundledEngine.Loader empty = path -> path.equals(BundledEngine.MANIFEST)
                ? new ByteArrayInputStream(manifest().getBytes(StandardCharsets.UTF_8))
                : null;
        assertTrue(BundledEngine.ensure(root, empty, "Linux", "amd64", log::add).isEmpty());
    }

    @Test
    void aDevelopmentJarWithNoEngineSaysSoAndCarriesOn(@TempDir Path root) {
        assertTrue(BundledEngine.ensure(root, path -> null, "Linux", "amd64", log::add).isEmpty());
        assertEquals(List.of("no engine bundled in this build"), log);
    }

    @Test
    void anUnsupportedPlatformIsToldWhatDidShip(@TempDir Path root) {
        assertTrue(BundledEngine.ensure(root, jar(), "Mac OS X", "aarch64", log::add).isEmpty());
        String said = String.join(" ", log);
        assertTrue(said.contains("windows/x86_64") && said.contains("linux/x86_64"), said);
        assertTrue(said.contains("sidecarPath"), "the message must say what to do instead: " + said);
    }

    @Test
    void anOlderEngineIsRemovedButNothingElseIs(@TempDir Path root) throws IOException {
        // A mod update leaves several megabytes behind on every past version otherwise.
        Path stale = root.resolve("0123456789ab");
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("br-sidecar"), "an engine from two versions ago");
        Path mine = root.resolve("notahash");
        Files.createDirectories(mine);
        Files.writeString(mine.resolve("keep-me"), "not this code's business");

        BundledEngine.ensure(root, jar(), "Linux", "amd64", log::add).orElseThrow();

        assertFalse(Files.exists(stale), "the superseded engine should be gone");
        assertTrue(Files.exists(mine.resolve("keep-me")), "an unrelated folder must be left alone");
    }

    @Test
    void unpackingNeverThrowsIntoTheModLoad(@TempDir Path root) throws IOException {
        // A read-only game directory, a full disk, a jar that cannot be read: each has to
        // end in a logged sentence. An exception here would take the mod's load with it,
        // and the mod is supposed to play fine with no engine at all.
        Path notADirectory = root.resolve("engine");
        Files.writeString(notADirectory, "in the way");
        assertTrue(BundledEngine.ensure(notADirectory, jar(), "Linux", "amd64", log::add).isEmpty());
        assertTrue(BundledEngine.ensure(root, path -> { throw new IOException("boom"); },
                "Linux", "amd64", log::add).isEmpty());
    }

    private static void assertArrayEqualsOnDisk(byte[] expected, Path p) throws IOException {
        assertEquals(HexFormat.of().formatHex(expected),
                HexFormat.of().formatHex(Files.readAllBytes(p)), p.toString());
    }

    @Test
    void theShippedLoaderReadsFromTheJarWhenThereIsOne() throws IOException {
        // Guards the wiring rather than the rule: whatever the real jar carries, opening
        // the manifest must either give bytes or null, never blow up.
        BundledEngine.Loader real = BundledEngine.class::getResourceAsStream;
        try (InputStream in = real.open(BundledEngine.MANIFEST)) {
            Optional<List<BundledEngine.Entry>> parsed = in == null
                    ? Optional.empty()
                    : Optional.of(BundledEngine.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            parsed.ifPresent(list -> assertFalse(list.isEmpty(), "a bundled manifest must list something"));
        }
    }
}
