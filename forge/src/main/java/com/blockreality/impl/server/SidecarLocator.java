package com.blockreality.impl.server;

import com.blockreality.core.sidecar.BundledEngine;
import com.blockreality.impl.BRConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds the engine binary without the user having to be told where to put it.
 *
 * <p>The search order goes from most explicit to most convenient, and every candidate is
 * recorded so that "analysis unavailable" can say <em>where it looked</em>. A tool that
 * fails silently and a tool that fails without saying what it tried are equally useless at
 * eight in the morning.
 */
public final class SidecarLocator {

    private SidecarLocator() { }

    private static final String EXE = isWindows() ? "br-sidecar.exe" : "br-sidecar";

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Where the search looked, in order, for the diagnostic message. */
    public record Result(Optional<Path> found, List<String> tried) { }

    public static Result locate() {
        List<String> tried = new ArrayList<>();

        String configured = BRConfig.INSTANCE.sidecarPath.get();
        if (configured != null && !configured.isBlank()) {
            // An explicit setting is never silently overridden. If it is wrong the user
            // gets told about that path and no other, because a fallback here would hide
            // a typo behind a binary they did not mean to run.
            Path p = Path.of(configured);
            tried.add("config: " + p);
            return new Result(usable(p) ? Optional.of(p) : Optional.empty(), tried);
        }

        for (String prop : new String[]{ System.getProperty("br.sidecar"), System.getenv("BR_SIDECAR") }) {
            if (prop != null && !prop.isBlank()) {
                Path p = Path.of(prop);
                tried.add((prop.equals(System.getenv("BR_SIDECAR")) ? "BR_SIDECAR: " : "-Dbr.sidecar: ") + p);
                if (usable(p)) return new Result(Optional.of(p), tried);
            }
        }

        // The engine that travelled inside the jar, unpacked on first use (D-027).
        //
        // It sits AFTER the three explicit settings and BEFORE the loose binaries, and the
        // order is the whole point of both halves. A player who set sidecarPath, -Dbr.sidecar
        // or BR_SIDECAR said which engine they want and must keep getting it. A br-sidecar
        // that merely happens to be in the game directory or on PATH is any version at all —
        // most often the one an older release's installer left there — while the bundled one
        // is the exact binary this build's acceptance suite ran against.
        for (String line : bundled().tried) tried.add(line);
        Path fromJar = bundled().path;
        if (fromJar != null && usable(fromJar)) return new Result(Optional.of(fromJar), tried);

        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            for (Path p : new Path[]{ gameDir.resolve(EXE), gameDir.resolve("blockreality").resolve(EXE) }) {
                tried.add("game dir: " + p);
                if (usable(p)) return new Result(Optional.of(p), tried);
            }
        } catch (Throwable t) {
            // FMLPaths is unavailable outside a running game (unit tests, datagen).
            tried.add("game dir: unavailable");
        }

        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) continue;
                Path p = Path.of(dir).resolve(EXE);
                if (usable(p)) {
                    tried.add("PATH: " + p);
                    return new Result(Optional.of(p), tried);
                }
            }
            tried.add("PATH: " + EXE + " not on PATH");
        }

        return new Result(Optional.empty(), tried);
    }

    private static boolean usable(Path p) {
        return Files.isRegularFile(p) && Files.isExecutable(p);
    }

    /** What the unpack decided, and what it said. Computed once per launch. */
    private record Bundled(Path path, List<String> tried) { }

    private static volatile Bundled bundledOnce;

    /**
     * Unpacks the bundled engine, at most once per JVM.
     *
     * <p>Once, because there is one {@link SidecarLocator} call per dimension and the
     * check that the file on disk is the right one hashes several megabytes. Doing that
     * three times because a world has three dimensions is a cost with nothing to show for
     * it — and unlike the search itself, the answer cannot differ between calls.
     */
    private static Bundled bundled() {
        Bundled b = bundledOnce;
        if (b != null) return b;
        synchronized (SidecarLocator.class) {
            if (bundledOnce != null) return bundledOnce;
            List<String> said = new ArrayList<>();
            Path found = null;
            try {
                Path root = FMLPaths.GAMEDIR.get().resolve("blockreality").resolve("engine");
                found = BundledEngine.ensure(root, BundledEngine.class::getResourceAsStream,
                        System.getProperty("os.name"), System.getProperty("os.arch"),
                        msg -> said.add("bundled: " + msg)).orElse(null);
            } catch (Throwable t) {
                // FMLPaths outside a running game, a security manager, a broken jar: the
                // mod plays without analysis rather than failing to load (D-013).
                said.add("bundled: unavailable (" + t + ")");
            }
            bundledOnce = new Bundled(found, List.copyOf(said));
            return bundledOnce;
        }
    }

    /** One line per place looked, for the log and for {@code /br status}. */
    public static String describe(Result r) {
        StringBuilder b = new StringBuilder();
        if (r.found().isPresent()) {
            b.append("engine: ").append(r.found().get());
        } else {
            b.append("engine not found. Looked in:");
            for (String t : r.tried()) b.append("\n  ").append(t);
            // SERVER-type config: Forge writes it into the world save, not into the
            // global config/ folder — pointing players at config/ sent them editing a
            // file the mod never reads (FORGE-9).
            b.append("\nThe engine normally travels inside the mod jar and unpacks itself on first "
                    + "use; if it did not, the lines above say why. You can also build ")
             .append(EXE)
             .append(" from source and name it in 'sidecarPath' in "
                    + "<world save>/serverconfig/blockreality-server.toml, or drop it in the game "
                    + "directory.");
        }
        return b.toString();
    }
}
