package com.blockreality.impl.server;

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
            b.append("\nSet 'sidecarPath' in <world save>/serverconfig/blockreality-server.toml, or drop ")
             .append(EXE).append(" in the game directory.");
        }
        return b.toString();
    }
}
