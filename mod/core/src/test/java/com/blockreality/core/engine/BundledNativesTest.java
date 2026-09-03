package com.blockreality.core.engine;

import com.blockreality.core.sidecar.BundledEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N24-a4 and the unpack rules for the shipping shape D-044 chose.
 *
 * <p>The library is fake — a few kilobytes of bytes with an ELF magic on the front. Nothing here
 * loads it; what is under test is the manifest discipline and the file that ends up on disk, and
 * a real 8 MB engine would make these tests slower without making them stronger.
 */
class BundledNativesTest {

    private static final byte[] LIB = makeLib();

    private static byte[] makeLib() {
        byte[] b = new byte[4096];
        b[0] = 0x7f; b[1] = 'E'; b[2] = 'L'; b[3] = 'F';
        for (int i = 4; i < b.length; i++) b[i] = (byte) (i * 31);
        return b;
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String CONTRACT = "a".repeat(64);

    private static String manifest() {
        return "# os arch file sha256 size engineVersion contractSha256\n"
                + "linux x86_64 libbsi_tectonic.so " + sha256(LIB) + " " + LIB.length
                + " 1.2.0 " + CONTRACT + "\n";
    }

    /** A jar carrying one Linux library. */
    private static BundledEngine.Loader jar() {
        return path -> {
            if (path.equals(BundledNatives.MANIFEST)) {
                return new ByteArrayInputStream(manifest().getBytes(StandardCharsets.UTF_8));
            }
            if (path.equals(BundledNatives.DIR + "linux-x86_64/libbsi_tectonic.so")) {
                return new ByteArrayInputStream(LIB);
            }
            return null;
        };
    }

    private static final class Log implements java.util.function.Consumer<String> {
        final List<String> lines = new ArrayList<>();
        public void accept(String s) { lines.add(s); }
        String all() { return String.join(" | ", lines); }
    }

    // ------------------------------------------------------------------ manifest

    @Test
    void sevenFieldsCarryTheEngineVersionAndTheContractItWasBuiltAgainst() {
        List<BundledNatives.Entry> e = BundledNatives.parse(manifest());
        assertEquals(1, e.size());
        assertEquals("linux", e.get(0).os());
        assertEquals("x86_64", e.get(0).arch());
        assertEquals("1.2.0", e.get(0).engineVersion());
        assertEquals(CONTRACT, e.get(0).contractSha256());
        assertEquals("/blockreality-engine/linux-x86_64/libbsi_tectonic.so", e.get(0).resource());
    }

    @Test
    void aFiveFieldManifestIsRefusedRatherThanHalfUnderstood() {
        // The sidecar manifest format, fed to the natives reader. It parses cleanly as five
        // fields and would silently lose the contract hash if the count were not checked.
        String old = "linux x86_64 br-sidecar " + sha256(LIB) + " " + LIB.length + "\n";
        assertThrows(IllegalArgumentException.class, () -> BundledNatives.parse(old));
    }

    @Test
    void aManifestThatTriesToEscapeTheUnpackDirectoryIsRefused() {
        for (String bad : List.of("../escape", "/etc/passwd", "C:/anywhere", "sub/dir")) {
            String m = "linux x86_64 " + bad + " " + sha256(LIB) + " " + LIB.length + " 1.2.0 " + CONTRACT + "\n";
            assertThrows(IllegalArgumentException.class, () -> BundledNatives.parse(m),
                    () -> bad + " was accepted as a file name");
        }
        String m = "linux ../x86_64 lib.so " + sha256(LIB) + " " + LIB.length + " 1.2.0 " + CONTRACT + "\n";
        assertThrows(IllegalArgumentException.class, () -> BundledNatives.parse(m));
    }

    @Test
    void aContractFieldThatIsNotAHashIsRefused() {
        String m = "linux x86_64 lib.so " + sha256(LIB) + " " + LIB.length + " 1.2.0 not-a-hash\n";
        assertThrows(IllegalArgumentException.class, () -> BundledNatives.parse(m));
    }

    // ------------------------------------------------------------------ unpacking

    @Test
    void firstUseWritesTheLibraryUnderAHashNamedPath(@TempDir Path root) throws IOException {
        Log log = new Log();
        Optional<Path> p = BundledNatives.ensure(root, jar(), "Linux", "amd64", log);
        assertTrue(p.isPresent(), log.all());
        assertArrayEqualsBytes(LIB, Files.readAllBytes(p.get()));
        assertEquals(sha256(LIB).substring(0, 16), p.get().getParent().getFileName().toString());
        assertEquals("lib", p.get().getParent().getParent().getFileName().toString());
    }

    /**
     * N24-a4. A shared library is opened by the dynamic loader, not executed. Setting the bit
     * would make the unpacked file look like a program, which is the one thing this shipping
     * shape exists to avoid.
     */
    @Test
    void theUnpackedLibraryIsNotExecutable(@TempDir Path root) throws IOException {
        Path p = BundledNatives.ensure(root, jar(), "Linux", "amd64", new Log()).orElseThrow();
        if (!p.getFileSystem().supportedFileAttributeViews().contains("posix")) return;
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
        for (PosixFilePermission x : List.of(PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE)) {
            assertFalse(perms.contains(x), "the unpacked library has " + x + ": it is a library, not a program");
        }
        assertTrue(Files.isReadable(p));
    }

    /** The #80 fix: a copy that is already there and already right is adopted, not fought over. */
    @Test
    void aLibraryAlreadyInPlaceIsAdoptedWithoutRewriting(@TempDir Path root) throws IOException {
        Path first = BundledNatives.ensure(root, jar(), "Linux", "amd64", new Log()).orElseThrow();
        var before = Files.getLastModifiedTime(first);
        Log log = new Log();
        Path second = BundledNatives.ensure(root, jar(), "Linux", "amd64", log).orElseThrow();
        assertEquals(first, second);
        assertEquals(before, Files.getLastModifiedTime(second));
        assertTrue(log.lines.isEmpty(), "a second launch said: " + log.all());
    }

    @Test
    void bytesThatDisagreeWithTheManifestUnpackNothing(@TempDir Path root) {
        BundledEngine.Loader tampered = path -> {
            if (path.equals(BundledNatives.MANIFEST)) {
                return new ByteArrayInputStream(manifest().getBytes(StandardCharsets.UTF_8));
            }
            if (path.startsWith(BundledNatives.DIR)) {
                byte[] other = LIB.clone();
                other[100] ^= 0x01;
                return new ByteArrayInputStream(other);
            }
            return null;
        };
        Log log = new Log();
        assertTrue(BundledNatives.ensure(root, tampered, "Linux", "amd64", log).isEmpty());
        assertTrue(log.all().contains("manifest says"), log.all());
    }

    @Test
    void aDevelopmentJarWithNoLibrarySaysSoAndCarriesOn(@TempDir Path root) {
        Log log = new Log();
        assertTrue(BundledNatives.ensure(root, path -> null, "Linux", "amd64", log).isEmpty());
        assertTrue(log.all().contains("no engine library bundled"), log.all());
    }

    @Test
    void anUnsupportedPlatformIsToldWhatDidShipAndWhereToPutItsOwn(@TempDir Path root) {
        Log log = new Log();
        assertTrue(BundledNatives.ensure(root, jar(), "Mac OS X", "aarch64", log).isEmpty());
        assertTrue(log.all().contains("linux-x86_64"), log.all());
        assertTrue(log.all().contains("blockreality/engine/"), log.all());
    }

    @Test
    void anOlderLibraryIsRemovedButNothingElseIs(@TempDir Path root) throws IOException {
        Path stale = root.resolve("lib").resolve("0123456789abcdef");
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("libbsi_tectonic.so"), "old");
        Path keep = root.resolve("lib").resolve("notahash");
        Files.createDirectories(keep);
        Files.writeString(keep.resolve("player-put-this-here"), "mine");

        BundledNatives.ensure(root, jar(), "Linux", "amd64", new Log());
        assertFalse(Files.exists(stale), "a superseded library was left behind");
        assertTrue(Files.exists(keep.resolve("player-put-this-here")),
                "pruning touched a directory that is not a content hash");
    }

    /** Unpacking is best-effort by construction: nothing it can hit may escape into the mod load. */
    @Test
    void unpackingNeverThrowsIntoTheModLoad(@TempDir Path root) {
        BundledEngine.Loader hostile = path -> { throw new IOException("disk on fire"); };
        assertTrue(BundledNatives.ensure(root, hostile, "Linux", "amd64", new Log()).isEmpty());

        BundledEngine.Loader garbage = path -> path.equals(BundledNatives.MANIFEST)
                ? new ByteArrayInputStream("not a manifest at all".getBytes(StandardCharsets.UTF_8))
                : null;
        assertTrue(BundledNatives.ensure(root, garbage, "Linux", "amd64", new Log()).isEmpty());

        BundledEngine.Loader halfTruth = path -> path.equals(BundledNatives.MANIFEST)
                ? new ByteArrayInputStream(manifest().getBytes(StandardCharsets.UTF_8))
                : null;
        assertTrue(BundledNatives.ensure(root, halfTruth, "Linux", "amd64", new Log()).isEmpty());
    }

    /** The bundled copy is a candidate for the locator, and the override directory outranks it (N24-b4). */
    @Test
    void theOverrideDirectoryBeatsTheCopyUnpackedFromTheJar(@TempDir Path root) throws IOException {
        Path bundled = BundledNatives.ensure(root, jar(), "Linux", "amd64", new Log()).orElseThrow();
        Path override = root.resolve(EngineLocator.platform());
        Files.createDirectories(override);
        Path mine = override.resolve(EngineLocator.libraryFileName("bsi_tectonic"));
        Files.write(mine, LIB);

        EngineLocator.Located l = EngineLocator.locate(null, null, null, root, bundled);
        assertEquals(EngineLocator.Source.OVERRIDE_DIRECTORY, l.source());
        assertEquals(mine, l.path());
    }

    private static void assertArrayEqualsBytes(byte[] want, byte[] got) {
        assertEquals(want.length, got.length, "length");
        for (int i = 0; i < want.length; i++) {
            if (want[i] != got[i]) throw new AssertionError("byte " + i + " differs");
        }
    }

    /** The loader the mod actually uses, exercised so a resource-path typo cannot hide until launch. */
    @Test
    void theShippedLoaderReadsFromTheJarWhenThereIsOne() throws IOException {
        BundledEngine.Loader real = BundledNatives.class::getResourceAsStream;
        try (InputStream in = real.open(BundledNatives.MANIFEST)) {
            if (in != null) BundledNatives.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
