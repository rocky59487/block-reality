package com.blockreality.core.engine;

import com.blockreality.core.sidecar.BundledEngine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The engine LIBRARY carried inside the mod jar, and unpacked so the loader can open it.
 *
 * <p>This is the sibling of {@link BundledEngine} for the shipping shape D-044 chose, and the
 * differences from it are the whole point rather than incidental:
 *
 * <ul>
 *   <li><b>No execute bit.</b> {@link BundledEngine#makeExecutable} exists because a sidecar is
 *       started as a process. A shared library is opened by the dynamic loader, which does not
 *       consult the execute bit; setting it would say "this is a program" about a file that is
 *       not one, and the compliance rule this shape exists to satisfy (N24-a1) is precisely that
 *       the distribution contains no programs. Recorded as N24-a4.</li>
 *   <li><b>Seven manifest fields, not five.</b> {@code engineVersion} and {@code contractSha256}
 *       ride along so a mismatch can be NAMED before the library is even opened. Without them a
 *       stale engine is discovered as a handshake refusal with no way to say which side is old.</li>
 *   <li><b>Platform subdirectory.</b> {@code blockreality-engine/<os>-<arch>/<file>}, which is the
 *       same layout the override directory uses, so a player replacing the bundled engine copies
 *       a directory shape they have already seen.</li>
 * </ul>
 *
 * <p>What it keeps from {@link BundledEngine}, because those were paid for in bugs: the unpack
 * directory is named after the content hash, the copy is verified as it streams AND re-read at the
 * target after the move, the temporary name is unique so two processes sharing a game directory
 * cannot rename each other's half-written file into place, and a manifest field that is not a
 * plain file name is refused rather than handed to {@code resolve()}.
 *
 * <p>Nothing here reaches the network, and nothing here starts a process.
 */
public final class BundledNatives {

    /** Hyphenated so the directory can never be read as a JPMS package name — see {@link BundledEngine#DIR}. */
    public static final String DIR = "/blockreality-engine/";

    /** Where the forge build writes the natives manifest. */
    public static final String MANIFEST = DIR + "natives.manifest";

    private BundledNatives() {}

    /**
     * One shipped library.
     *
     * @param engineVersion   the engine's own version string, for a message that can name it
     * @param contractSha256  the BSI contract that engine was built against; compared with the
     *                        mod's own pin BEFORE loading, so "your engine is older than your mod"
     *                        is a sentence rather than an opaque handshake failure
     */
    public record Entry(String os, String arch, String fileName, String sha256, long size,
                        String engineVersion, String contractSha256) {

        /** Resource path inside the jar. */
        public String resource() { return DIR + os + "-" + arch + "/" + fileName; }

        /** First 16 hex characters of the hash — the unpack directory's name. */
        public String shortHash() { return sha256.substring(0, 16); }

        public String platform() { return os + "-" + arch; }
    }

    // ------------------------------------------------------------------ manifest

    /**
     * Parses the build-generated manifest. Blank lines and {@code #} comments are skipped; any
     * other line that is not seven fields is an error, because a manifest this code
     * half-understands is the one way it could unpack bytes nobody vouched for.
     */
    public static List<Entry> parse(String manifest) {
        List<Entry> out = new ArrayList<>();
        int lineNo = 0;
        for (String raw : manifest.split("\n")) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\\s+");
            if (f.length != 7) {
                throw new IllegalArgumentException(
                        "natives manifest line " + lineNo + ": expected 7 fields, got " + f.length);
            }
            if (!isPlainSegment(f[0]) || !isPlainSegment(f[1])) {
                throw new IllegalArgumentException(
                        "natives manifest line " + lineNo + ": '" + f[0] + "-" + f[1]
                                + "' is not a plain platform name");
            }
            if (!isPlainSegment(f[2])) {
                throw new IllegalArgumentException(
                        "natives manifest line " + lineNo + ": '" + f[2] + "' is not a plain file name");
            }
            if (!isSha256(f[3])) {
                throw new IllegalArgumentException(
                        "natives manifest line " + lineNo + ": '" + f[3] + "' is not a sha256");
            }
            if (!isSha256(f[6])) {
                throw new IllegalArgumentException(
                        "natives manifest line " + lineNo + ": '" + f[6] + "' is not a contract sha256");
            }
            long size;
            try {
                size = Long.parseLong(f[4]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "natives manifest line " + lineNo + ": '" + f[4] + "' is not a size");
            }
            if (size <= 0) {
                throw new IllegalArgumentException("natives manifest line " + lineNo + ": size " + size);
            }
            out.add(new Entry(f[0], f[1], f[2], f[3].toLowerCase(Locale.ROOT), size, f[5],
                    f[6].toLowerCase(Locale.ROOT)));
        }
        return List.copyOf(out);
    }

    /**
     * One path element, and not a special one.
     *
     * <p>Same rule, and for the same reason, as {@link BundledEngine}'s: {@code resolve()} DROPS
     * THE ROOT when handed an absolute path, so a manifest naming {@code ../escape} or
     * {@code C:/anywhere} would write outside the unpack directory. Reaching it takes a modified
     * jar, which is not an escalation — but it is the difference between recompiling the mod and
     * editing one line with {@code zip -u}, and a zip-slip scanner would not find it because the
     * entry names are innocent and the manifest is the way in.
     */
    static boolean isPlainSegment(String s) {
        if (s == null || s.isEmpty() || s.equals(".") || s.equals("..")) return false;
        if (s.indexOf('/') >= 0 || s.indexOf('\\') >= 0) return false;
        if (s.indexOf(java.io.File.separatorChar) >= 0) return false;
        if (s.indexOf(':') >= 0) return false;          // C: and NTFS alternate streams
        return s.indexOf('\0') < 0;
    }

    static boolean isSha256(String s) {
        return s != null && s.length() == 64 && s.chars().allMatch(BundledNatives::isHex);
    }

    private static boolean isHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** The entry for this machine, if one shipped. Platform naming is {@link BundledEngine}'s, not a second copy of it. */
    public static Optional<Entry> select(List<Entry> entries, String osName, String osArch) {
        String os = BundledEngine.normaliseOs(osName);
        String arch = BundledEngine.normaliseArch(osArch);
        if (os == null || arch == null) return Optional.empty();
        return entries.stream().filter(e -> e.os().equals(os) && e.arch().equals(arch)).findFirst();
    }

    // ------------------------------------------------------------------ unpacking

    /** Where this entry's library belongs under {@code root}. */
    public static Path targetFor(Path root, Entry e) {
        return root.resolve("lib").resolve(e.shortHash()).resolve(e.fileName());
    }

    /**
     * Makes sure this machine's library is on disk and returns it.
     *
     * <p>Every failure is recoverable: the caller carries on down {@link EngineLocator}'s order and
     * the mod plays with analysis off. A read-only game directory, a full disk and an unsupported
     * platform must all end in a logged sentence, never an exception that takes the mod's load
     * with it.
     *
     * @param root   directory to unpack under, usually {@code <gamedir>/blockreality/engine}
     * @param loader reads resources out of the mod jar
     */
    public static Optional<Path> ensure(Path root, BundledEngine.Loader loader,
                                        String osName, String osArch, Consumer<String> log) {
        List<Entry> entries;
        try (InputStream in = loader.open(MANIFEST)) {
            if (in == null) {
                log.accept("no engine library bundled in this build");
                return Optional.empty();
            }
            entries = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            log.accept("bundled natives manifest unreadable: " + e);
            return Optional.empty();
        }

        Optional<Entry> picked = select(entries, osName, osArch);
        if (picked.isEmpty()) {
            log.accept("no bundled engine library for " + osName + " / " + osArch
                    + " (shipped: " + describe(entries) + "). Analysis stays off unless you build "
                    + "the engine yourself and put it in blockreality/engine/" + EngineLocator.platform() + "/.");
            return Optional.empty();
        }
        Entry e = picked.get();

        try {
            Path target = targetFor(root, e);
            // Already there and already right: adopt it. This is the #80 fix — two processes
            // sharing a game directory both reach this line, and the loser must not treat the
            // winner's finished file as a conflict.
            if (isGood(target, e)) return Optional.of(target);

            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
            try {
                copyVerified(loader, e, tmp);
                move(tmp, target);
            } finally {
                Files.deleteIfExists(tmp);
            }
            // Check what is AT THE TARGET, not what went past on the way there.
            if (!isGood(target, e)) {
                throw new IOException("the unpacked " + e.fileName() + " is not the file the manifest "
                        + "describes — another process may be writing here");
            }
            // Deliberately NO execute bit: see the class comment (N24-a4).
            log.accept("unpacked the bundled engine library to " + target + " (" + e.size()
                    + " bytes, engine " + e.engineVersion() + ", contract " + e.contractSha256().substring(0, 12)
                    + "\u2026)");
            prune(root, e, log);
            return Optional.of(target);
        } catch (IOException | RuntimeException ex) {
            log.accept("could not unpack the bundled engine library: " + ex);
            return Optional.empty();
        }
    }

    private static String describe(List<Entry> entries) {
        if (entries.isEmpty()) return "nothing";
        StringBuilder b = new StringBuilder();
        for (Entry e : entries) {
            if (b.length() > 0) b.append(", ");
            b.append(e.platform());
        }
        return b.toString();
    }

    /** True when the file on disk is exactly the one the manifest describes. */
    static boolean isGood(Path p, Entry e) {
        try {
            if (!Files.isRegularFile(p) || Files.size(p) != e.size()) return false;
            return sha256(p).equals(e.sha256());
        } catch (IOException io) {
            return false;
        }
    }

    static void copyVerified(BundledEngine.Loader loader, Entry e, Path dest) throws IOException {
        MessageDigest md = digest();
        long written;
        try (InputStream raw = loader.open(e.resource())) {
            if (raw == null) {
                throw new IOException("manifest lists " + e.resource() + " but the jar does not carry it");
            }
            try (DigestInputStream in = new DigestInputStream(raw, md)) {
                written = Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        String got = HexFormat.of().formatHex(md.digest());
        if (written != e.size() || !got.equals(e.sha256())) {
            Files.deleteIfExists(dest);
            throw new IOException("bundled " + e.fileName() + " is " + written + " bytes / " + got
                    + ", manifest says " + e.size() + " / " + e.sha256());
        }
    }

    private static void move(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Removes libraries this build did not ship.
     *
     * <p>Only directories under {@code lib/} whose name is 16 hex digits are touched. A library
     * still mapped by a running JVM cannot be deleted on Windows; that is expected, and the
     * failure is swallowed so the next launch tries again rather than the unpack failing.
     */
    static void prune(Path root, Entry keep, Consumer<String> log) {
        Path lib = root.resolve("lib");
        try (var dirs = Files.list(lib)) {
            dirs.filter(Files::isDirectory)
                .filter(d -> d.getFileName().toString().length() == 16)
                .filter(d -> d.getFileName().toString().chars().allMatch(BundledNatives::isHex))
                .filter(d -> !d.getFileName().toString().equals(keep.shortHash()))
                .forEach(d -> {
                    try (var files = Files.list(d)) {
                        for (Path f : files.toList()) Files.deleteIfExists(f);
                    } catch (IOException ignored) {
                        // Still mapped by a running process: leave it, try next launch.
                    }
                    try {
                        Files.deleteIfExists(d);
                        log.accept("removed a superseded engine library: " + d.getFileName());
                    } catch (IOException ignored) {
                        // ditto
                    }
                });
        } catch (IOException ignored) {
            // The directory may not exist yet on the very first launch.
        }
    }

    static String sha256(Path p) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new UncheckedIOException(new IOException("no SHA-256 in this JVM", impossible));
        }
    }
}
