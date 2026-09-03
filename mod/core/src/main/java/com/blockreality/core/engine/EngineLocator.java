package com.blockreality.core.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Where the engine library comes from, in the order it is looked for (D-044 §4).
 *
 * <p>config → {@code -Dbr.engine} → {@code BR_ENGINE} → <b>override directory</b> → the copy
 * unpacked from the jar. The override directory comes BEFORE the bundled copy on purpose: that
 * ordering is the whole mechanism by which an engine can be updated without touching the mod, and
 * a mod updated without touching the engine (N24-b4).
 *
 * <p>Nothing here loads anything. It answers "which file", and says so when the answer is none —
 * an engine that is absent must be reported, not guessed at.
 */
public final class EngineLocator {

    /** Where a candidate came from, so a log line can say why this file and not another. */
    public enum Source { CONFIG, SYSTEM_PROPERTY, ENVIRONMENT, OVERRIDE_DIRECTORY, BUNDLED, NONE }

    public record Located(Path path, Source source) {}

    private EngineLocator() {}

    /** {@code linux-x86_64}, {@code windows-x86_64}, {@code macos-aarch64} … as the manifest spells it. */
    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String o = os.contains("win") ? "windows" : os.contains("mac") || os.contains("darwin") ? "macos" : os.contains("linux") ? "linux" : "unknown";
        String a = switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch.isEmpty() ? "unknown" : arch;
        };
        return o + "-" + a;
    }

    /** The file name a native library takes on this platform. */
    public static String libraryFileName(String base) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return base + ".dll";
        if (os.contains("mac") || os.contains("darwin")) return "lib" + base + ".dylib";
        return "lib" + base + ".so";
    }

    /**
     * The ordering, with every input explicit. The system property and the environment are
     * PARAMETERS rather than globals so the order itself can be tested without a test having to
     * mutate the JVM it runs in — the ordering is the mechanism (D-044 §4), so it is the thing
     * that most needs a leg.
     *
     * @param configured  the value from the mod's own config, or null
     * @param property    {@code -Dbr.engine}, or null
     * @param environment {@code BR_ENGINE}, or null
     * @param overrideDir {@code <gamedir>/blockreality/engine}, or null when there is no game dir
     * @param bundled     the copy unpacked from the jar, or null when the jar carries none
     */
    public static Located locate(String configured, String property, String environment, Path overrideDir, Path bundled) {
        List<Located> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) candidates.add(new Located(Path.of(configured.trim()), Source.CONFIG));
        if (property != null && !property.isBlank()) candidates.add(new Located(Path.of(property.trim()), Source.SYSTEM_PROPERTY));
        if (environment != null && !environment.isBlank()) candidates.add(new Located(Path.of(environment.trim()), Source.ENVIRONMENT));
        if (overrideDir != null) {
            findInDirectory(overrideDir.resolve(platform())).ifPresent(p -> candidates.add(new Located(p, Source.OVERRIDE_DIRECTORY)));
        }
        if (bundled != null) candidates.add(new Located(bundled, Source.BUNDLED));

        for (Located c : candidates) {
            if (Files.isRegularFile(c.path()) && Files.isReadable(c.path())) return c;
        }
        return new Located(null, Source.NONE);
    }

    /** The same ordering with the property and environment read from this JVM. */
    public static Located locateFromSystem(String configured, Path overrideDir, Path bundled) {
        return locate(configured, System.getProperty("br.engine"), System.getenv("BR_ENGINE"), overrideDir, bundled);
    }

    /** The first library file in a directory, by name, so two copies cannot make the choice random. */
    static Optional<Path> findInDirectory(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return Optional.empty();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".so") || n.endsWith(".dll") || n.endsWith(".dylib");
                    })
                    .sorted()
                    .findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
