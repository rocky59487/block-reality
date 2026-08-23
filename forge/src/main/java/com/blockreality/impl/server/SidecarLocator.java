package com.blockreality.impl.server;

import com.blockreality.core.sidecar.BundledEngine;
import com.blockreality.core.sidecar.SidecarPaths;
import com.blockreality.impl.BRConfig;
import com.blockreality.impl.BlockRealityMod;
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

        // The three EXPLICIT settings, each of which somebody typed. Any one of them
        // present ends the search whether or not it works: "never quietly overridden" is
        // what README and D-027 promise, and only sidecarPath was actually keeping it —
        // a wrong -Dbr.sidecar or BR_SIDECAR fell through to some other binary and ran it
        // without a word.
        String[][] explicit = {
                { "config", BRConfig.INSTANCE.sidecarPath.get() },
                { "-Dbr.sidecar", System.getProperty("br.sidecar") },
                { "BR_SIDECAR", System.getenv("BR_SIDECAR") },
        };
        for (String[] e : explicit) {
            if (e[1] == null || e[1].isBlank()) continue;
            Optional<Path> parsed = SidecarPaths.parse(e[1], msg -> tried.add(e[0] + ": " + msg));
            if (parsed.isEmpty()) return new Result(Optional.empty(), tried);
            tried.add(e[0] + ": " + parsed.get());
            return new Result(usable(parsed.get()) ? parsed : Optional.empty(), tried);
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
        if (fromJar == null) {
            // Falling past this point means something looser is about to be run: a
            // br-sidecar of unknown vintage in the game directory or on PATH, most often
            // the one an older release's installer left there. The protocol version has
            // never changed, so the handshake will wave it through. Say so at WARN.
            BlockRealityMod.LOG.warn("[engine] the bundled engine could not be unpacked; if an "
                    + "older br-sidecar is present it will be used instead. Reasons above; "
                    + "`/br status` lists every path tried.");
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
                // A quoted entry in PATH is legal on Windows and is nobody's typo, and an
                // unparseable one is somebody else's problem, not a reason to stop.
                Optional<Path> base = SidecarPaths.parse(dir, msg -> { });
                if (base.isEmpty()) continue;
                Path p;
                try {
                    p = base.get().resolve(EXE);
                } catch (RuntimeException bad) {
                    continue;
                }
                if (usable(p)) {
                    tried.add("PATH: " + p);
                    return new Result(Optional.of(p), tried);
                }
            }
            tried.add("PATH: " + EXE + " not on PATH");
        }

        return new Result(Optional.empty(), tried);
    }

    /**
     * Forgets the unpack decision so the next {@link #locate()} tries again.
     *
     * <p>The memo cached failures as well as successes for the life of the JVM, so a
     * player whose antivirus quarantined the engine once had to restart the game after
     * adding an exclusion — {@code /br reset} would not do it, and nothing said so.
     */
    public static void forgetBundled() {
        synchronized (SidecarLocator.class) {
            bundledOnce = null;
        }
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
            // ...and everything tried on the way, which used to be thrown away on success.
            // That silence hid the case this whole search order exists to prevent: the
            // unpack failing and the engine quietly coming from a game directory instead —
            // a different binary, from an older release's installer, waved through by a
            // protocol version that has never changed. It also swallowed the one-time
            // "unpacked the bundled engine to ..." line, which is the single most
            // interesting thing this mod does on a player's first launch.
            for (String s : r.tried()) b.append("\n  ").append(s);
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
