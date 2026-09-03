package com.blockreality.core.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    @org.junit.jupiter.api.Test
    void platformSupportAnswersFromTheManifestAndNeverCriesWolf() {
        // The shipped pair is supported; a platform with no row is not.
        org.junit.jupiter.api.Assertions.assertTrue(
                BundledEngine.platformSupported(jar(), "Windows 11", "amd64"));
        org.junit.jupiter.api.Assertions.assertFalse(
                BundledEngine.platformSupported(jar(), "Mac OS X", "aarch64"));
        // A development jar with no manifest is NOT a platform problem — saying it is
        // would send the player to the wrong explanation.
        org.junit.jupiter.api.Assertions.assertTrue(
                BundledEngine.platformSupported(path -> null, "Mac OS X", "aarch64"));
    }

    /** A jar that carries the manifest and both binaries. */
    private static BundledEngine.Loader jar() {
        return path -> switch (path) {
            case BundledEngine.MANIFEST -> new ByteArrayInputStream(manifest().getBytes(StandardCharsets.UTF_8));
            case "/blockreality-engine/br-sidecar.exe" -> new ByteArrayInputStream(WIN);
            case "/blockreality-engine/br-sidecar" -> new ByteArrayInputStream(LIN);
            default -> null;
        };
    }

    private final List<String> log = new ArrayList<>();

    // ------------------------------------------------------------------ manifest

    /**
     * #80, on this class too. `whatComesBackIsWhatIsONDISK` fails on Windows because the thread
     * that loses the race gets an AccessDeniedException from Files.move -- an IOException, not
     * AtomicMoveNotSupportedException, so the fallback never catches it -- and falls out to the
     * caller as "could not unpack the bundled engine", leaving that JVM with no engine and one
     * line of log. Two JVMs sharing a game directory is the real case.
     *
     * <p>Driven through the Replace seam so the rule is checked on every platform. A leg that
     * only bites on the box nobody runs CI on is not a leg.
     */
    @Test
    void aReplaceRefusedByAnOpenHandleAdoptsWhatIsAlreadyThere(@TempDir Path dir) throws Exception {
        BundledEngine.Entry e = BundledEngine.parse(manifest()).stream()
                .filter(x -> "linux".equals(x.os())).findFirst().orElseThrow();
        Path target = dir.resolve("br-sidecar");
        Path tmp = Files.createFile(dir.resolve("x.part"));

        Files.write(target, LIN);                       // the winner already finished
        BundledEngine.Replace refuse = (a, b) -> { throw new AccessDeniedException(b.toString()); };
        assertDoesNotThrow(() -> BundledEngine.move(tmp, target, e, refuse),
                "the loser's answer was on disk; refusing to overwrite it is not a failure");
        assertEquals(new String(LIN, StandardCharsets.UTF_8),
                Files.readString(target), "and nothing was overwritten");

        Files.write(target, "not the engine".getBytes(StandardCharsets.UTF_8));
        assertThrows(AccessDeniedException.class, () -> BundledEngine.move(tmp, target, e, refuse),
                "a refusal over the WRONG bytes is still a failure and must not be swallowed");
    }

    @Test
    void theManifestParsesAndCommentsAndBlanksAreSkipped() {
        List<BundledEngine.Entry> e = BundledEngine.parse(manifest());
        assertEquals(2, e.size());
        assertEquals("br-sidecar.exe", e.get(0).fileName());
        assertEquals(WIN.length, e.get(0).size());
        assertEquals(12, e.get(0).shortHash().length());
        assertEquals("/blockreality-engine/br-sidecar", e.get(1).resource());
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

    // ------------------------------------------------------ the manifest is untrusted

    @Test
    void aFileNameThatIsAPathIsRefused() {
        // The manifest's third field went straight into resolve(), and Path.resolve drops
        // the root when handed an absolute path. A modified jar could write anywhere the
        // process can write. That is not a privilege escalation — somebody who can edit
        // the jar can already run Java — but it is the difference between "recompile the
        // mod" and "zip -u one line of text", it falsifies the promise that a binary the
        // player placed is never touched, and no zip-slip scanner would see it, because
        // the attack surface is the manifest and not the entry names.
        for (String evil : new String[] { "../escape", "sub/dir", "sub" + java.io.File.separator + "x",
                                          "/abs/path", "C:/abs", "..", ".", "" }) {
            assertThrows(IllegalArgumentException.class,
                    () -> BundledEngine.parse("linux x86_64 " + evil + " " + sha(LIN) + " " + LIN.length),
                    "accepted: " + evil);
        }
        // ...and a plain name still is one.
        assertEquals("br-sidecar", BundledEngine.parse(
                "linux x86_64 br-sidecar " + sha(LIN) + " " + LIN.length).get(0).fileName());
    }

    @Test
    void darwinIsNotWindows() {
        // "darwin" contains "win". The macOS branch below it could never run, and the
        // author wrote that branch, so the string is expected to turn up. The day a macOS
        // binary enters the manifest, every environment reporting Darwin would be handed
        // an .exe — which is the one thing the platform rule exists to prevent.
        assertEquals("macos", BundledEngine.normaliseOs("Darwin"));
        assertEquals("macos", BundledEngine.normaliseOs("Mac OS X"));
        assertEquals("windows", BundledEngine.normaliseOs("Windows 11"));
        assertEquals("linux", BundledEngine.normaliseOs("Linux"));
        // Not Linux, and there is no AIX binary: saying "linux" would hand it one.
        assertEquals(null, BundledEngine.normaliseOs("AIX"));
    }

    // ------------------------------------------------------ concurrency and permissions

    @Test
    void whatComesBackIsWhatIsONDISK(@TempDir Path root) throws Exception {
        // The digest was taken on the SOURCE stream while the move took whatever happened
        // to be sitting at the fixed .part name — so two processes sharing a game
        // directory could rename each other's half-written file into place and have it
        // declared correct. Reproduced at 87% failure with eight threads; the fix is a
        // unique temporary name AND a re-read of the target after the move, so the bytes
        // that were checked are the bytes that stayed.
        int threads = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var results = new java.util.concurrent.ConcurrentLinkedQueue<Path>();
        var latch = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                latch.await();
                BundledEngine.ensure(root, jar(), "Linux", "amd64", s -> { }).ifPresent(results::add);
                return null;
            });
        }
        latch.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(threads, results.size(), "every caller must get an engine");
        for (Path p : results) assertArrayEqualsOnDisk(LIN, p);
        try (var stray = Files.walk(root)) {
            assertTrue(stray.noneMatch(f -> f.getFileName().toString().contains(".part")),
                    "a temporary file was left behind");
        }
    }

    @Test
    void theUnpackedEngineIsExecutable(@TempDir Path root) throws IOException {
        // The one step of the manual install this code replaces that nothing checked.
        // With chmod turned into a no-op the whole suite stayed green, while the engine
        // came out rw-r--r-- and the locator rejected it — a new Linux player would get
        // analysis silently switched off with no error to search for.
        assumeTrue(root.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions only");
        Path p = BundledEngine.ensure(root, jar(), "Linux", "amd64", log::add).orElseThrow();
        assertTrue(Files.isExecutable(p), "unpacked engine is not executable");
        var perms = Files.getPosixFilePermissions(p);
        assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE),
                "an executable this process wrote must not be world-writable");
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
