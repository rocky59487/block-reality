package com.blockreality.core.sidecar;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The analysis engine, carried inside the mod jar and unpacked on first use.
 *
 * <p>Why this exists: on CurseForge and Modrinth a mod is one jar dropped into
 * {@code mods/}. An engine the player has to download and place separately is not a
 * distribution, it is a support burden — and the installer scripts that made it bearable
 * cannot run at all when the jar arrives through a launcher's mod browser.
 *
 * <p>What this deliberately is NOT: a downloader. Nothing here reaches the network. The
 * binaries are bytes already inside the jar the player chose to install, their SHA-256 is
 * recorded in the manifest beside them at build time, and extraction refuses anything that
 * does not match. A mod that fetches an executable at runtime is a different and much
 * worse thing, and both platforms are right to treat it as such.
 *
 * <p>The unpack path is named after the content hash — {@code <root>/<sha12>/br-sidecar} —
 * which gives three properties for free: a mod update lands beside the old engine instead
 * of overwriting it, a half-written file can never be mistaken for a good one, and a
 * binary the player put somewhere themselves is never touched.
 *
 * <p>No Minecraft imports. The caller supplies the resource loader and the target
 * directory, so the whole rule is reachable from a plain JUnit run.
 */
public final class BundledEngine {

    /** Where the build puts the manifest. */
    public static final String MANIFEST = "/blockreality/engine/engine.manifest";

    private BundledEngine() { }

    /** One shipped binary: which platform it is for, what it is called, and its hash. */
    public record Entry(String os, String arch, String fileName, String sha256, long size) {
        /** Resource path of this binary inside the jar. */
        public String resource() { return "/blockreality/engine/" + fileName; }

        /** First 12 hex characters of the hash — the unpack directory's name. */
        public String shortHash() { return sha256.substring(0, 12); }
    }

    /** Opens a resource from the mod jar, or returns null when it is not there. */
    @FunctionalInterface
    public interface Loader {
        InputStream open(String resourcePath) throws IOException;
    }

    // ------------------------------------------------------------------ manifest

    /**
     * Parses the build-generated manifest.
     *
     * <p>Deliberately not JSON. The file is written by the build and read by exactly this
     * method; a format with a schema, an escaping story and a parser is a liability here,
     * not an asset. Blank lines and {@code #} comments are skipped; anything else that is
     * not five fields is an error rather than a silently ignored line, because a manifest
     * this code half-understands is the one way it could unpack the wrong bytes.
     */
    public static List<Entry> parse(String manifest) {
        List<Entry> out = new ArrayList<>();
        int lineNo = 0;
        for (String raw : manifest.split("\n")) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\\s+");
            if (f.length != 5) {
                throw new IllegalArgumentException(
                        "engine manifest line " + lineNo + ": expected 5 fields, got " + f.length);
            }
            if (f[3].length() != 64 || !f[3].chars().allMatch(BundledEngine::isHex)) {
                throw new IllegalArgumentException(
                        "engine manifest line " + lineNo + ": '" + f[3] + "' is not a sha256");
            }
            long size;
            try {
                size = Long.parseLong(f[4]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "engine manifest line " + lineNo + ": '" + f[4] + "' is not a size");
            }
            if (size <= 0) {
                throw new IllegalArgumentException("engine manifest line " + lineNo + ": size " + size);
            }
            out.add(new Entry(f[0], f[1], f[2], f[3].toLowerCase(Locale.ROOT), size));
        }
        return List.copyOf(out);
    }

    private static boolean isHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * The entry for this machine, if one shipped.
     *
     * <p>Only x86-64 Windows and Linux binaries are built today. An Apple Silicon Mac, an
     * ARM server or a 32-bit JVM gets {@link Optional#empty()} and a clear reason, never a
     * binary for the wrong architecture — which would fail as a confusing process-start
     * error much later and somewhere else.
     */
    public static Optional<Entry> select(List<Entry> entries, String osName, String osArch) {
        String os = normaliseOs(osName);
        String arch = normaliseArch(osArch);
        if (os == null || arch == null) return Optional.empty();
        return entries.stream().filter(e -> e.os().equals(os) && e.arch().equals(arch)).findFirst();
    }

    /** {@code windows} / {@code linux} / {@code macos}, or null for anything else. */
    public static String normaliseOs(String osName) {
        String s = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (s.contains("win")) return "windows";
        if (s.contains("mac") || s.contains("darwin")) return "macos";
        if (s.contains("nux") || s.contains("nix") || s.contains("aix")) return "linux";
        return null;
    }

    /** {@code x86_64} / {@code aarch64}, or null for anything else. */
    public static String normaliseArch(String osArch) {
        String s = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        if (s.equals("amd64") || s.equals("x86_64") || s.equals("x86-64")) return "x86_64";
        if (s.equals("aarch64") || s.equals("arm64")) return "aarch64";
        return null;
    }

    // ------------------------------------------------------------------ unpacking

    /** Where this entry's binary belongs under {@code root}. */
    public static Path targetFor(Path root, Entry e) {
        return root.resolve(e.shortHash()).resolve(e.fileName());
    }

    /**
     * Makes sure this machine's engine is on disk and returns it.
     *
     * <p>Every failure here is recoverable: the caller carries on down its own search
     * order, and the mod plays with analysis off. A read-only game directory, a full disk
     * and an unsupported platform must all end in a logged sentence, never an exception
     * that takes the mod's load with it.
     *
     * @param root    directory to unpack under, usually {@code <gamedir>/blockreality/engine}
     * @param loader  reads resources out of the mod jar
     * @param osName  {@code os.name}
     * @param osArch  {@code os.arch}
     * @param log     one line per interesting decision, for the game log
     */
    public static Optional<Path> ensure(Path root, Loader loader,
                                        String osName, String osArch, Consumer<String> log) {
        List<Entry> entries;
        try (InputStream in = loader.open(MANIFEST)) {
            if (in == null) {
                // A jar built without the engines — the development flow, and a legitimate
                // one. Say so once; the locator's other candidates still apply.
                log.accept("no engine bundled in this build");
                return Optional.empty();
            }
            entries = parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            log.accept("bundled engine manifest unreadable: " + e);
            return Optional.empty();
        }

        Optional<Entry> picked = select(entries, osName, osArch);
        if (picked.isEmpty()) {
            log.accept("no bundled engine for " + osName + " / " + osArch
                    + " (shipped: " + describe(entries) + "). Analysis stays off unless you "
                    + "build br-sidecar from source and point sidecarPath at it.");
            return Optional.empty();
        }
        Entry e = picked.get();

        try {
            Path target = targetFor(root, e);
            if (isGood(target, e)) {
                prune(root, e, log);
                return Optional.of(makeExecutable(target));
            }
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            copyVerified(loader, e, tmp);
            move(tmp, target);
            makeExecutable(target);
            log.accept("unpacked the bundled engine to " + target + " (" + e.size() + " bytes, sha256 "
                    + e.shortHash() + ")");
            prune(root, e, log);
            return Optional.of(target);
        } catch (IOException | RuntimeException ex) {
            log.accept("could not unpack the bundled engine: " + ex);
            return Optional.empty();
        }
    }

    private static String describe(List<Entry> entries) {
        if (entries.isEmpty()) return "nothing";
        StringBuilder b = new StringBuilder();
        for (Entry e : entries) {
            if (b.length() > 0) b.append(", ");
            b.append(e.os()).append('/').append(e.arch());
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

    /**
     * Streams the resource to {@code dest}, hashing as it goes, and deletes it unless the
     * hash matches. The check is on the bytes actually written, so a truncated read or a
     * corrupted jar cannot leave a plausible-looking executable behind.
     */
    static void copyVerified(Loader loader, Entry e, Path dest) throws IOException {
        MessageDigest md = digest();
        long written;
        try (InputStream raw = loader.open(e.resource())) {
            if (raw == null) {
                throw new IOException("manifest lists " + e.fileName() + " but the jar does not carry it");
            }
            try (DigestInputStream in = new DigestInputStream(raw, md)) {
                Files.deleteIfExists(dest);
                written = Files.copy(in, dest);
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

    /** chmod +x where the filesystem has an opinion about it. */
    static Path makeExecutable(Path p) {
        try {
            if (p.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(p));
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(p, perms);
            }
        } catch (IOException | RuntimeException ignored) {
            // Best effort. If it is not executable the locator will not accept it, and the
            // player gets the "looked here, found nothing usable" message rather than a
            // process that fails to start for a reason nobody can see.
        }
        return p;
    }

    /**
     * Removes engines this build did not ship.
     *
     * <p>Each one is several megabytes and a mod update would otherwise leave every past
     * version behind forever. Only directories whose name is a 12-hex-digit hash are
     * touched — nothing else under the folder is this code's business.
     */
    static void prune(Path root, Entry keep, Consumer<String> log) {
        try (var dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory)
                .filter(d -> d.getFileName().toString().length() == 12)
                .filter(d -> d.getFileName().toString().chars().allMatch(BundledEngine::isHex))
                .filter(d -> !d.getFileName().toString().equals(keep.shortHash()))
                .forEach(d -> {
                    try (var files = Files.list(d)) {
                        for (Path f : files.toList()) Files.deleteIfExists(f);
                    } catch (IOException ignored) {
                        // A file still held open by a running engine: leave it, try next launch.
                    }
                    try {
                        Files.deleteIfExists(d);
                        log.accept("removed a superseded engine: " + d.getFileName());
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
