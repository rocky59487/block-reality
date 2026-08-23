package com.blockreality.core.sidecar;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Turning a string somebody typed into a path, without taking the server down.
 *
 * <p>{@link Path#of} throws {@link InvalidPathException}, and it is unchecked. The engine
 * path arrives from a config file, a system property, an environment variable or an entry
 * of {@code PATH}, and the search that reads them runs inside a chunk-load event handler —
 * so one unusable string reached the server thread as an exception through
 * {@code computeIfAbsent}, which does not keep a mapping when the factory throws. The
 * world failed to load, and it failed again on the next chunk, and the next.
 *
 * <p>The strings that do it are not exotic:
 *
 * <pre>
 *   "C:\\tools\\br-sidecar.exe"   what Windows Explorer's "Copy as path" puts on the
 *                                 clipboard — quotes included
 *   C:/tools/br-sidecar.exe       with a trailing space, from a careless paste
 * </pre>
 *
 * <p>A quoted entry inside {@code PATH} is legal on Windows and is nobody's typo at all.
 * So quotes are stripped rather than merely tolerated, and anything still unusable is
 * reported as one line and treated as "not found" — which is a state the caller already
 * knows how to handle.
 */
public final class SidecarPaths {

    private SidecarPaths() { }

    /**
     * @param raw   what the user or the environment supplied
     * @param note  receives one line when the string cannot be a path
     * @return the path, or empty when the string is blank or unusable
     */
    public static Optional<Path> parse(String raw, Consumer<String> note) {
        if (raw == null) return Optional.empty();
        String s = raw.strip();
        // Paired quotes only: a lone quote is not a wrapper, it is a broken string, and
        // silently dropping half of it would change which file is meant.
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1).strip();
        }
        if (s.isEmpty()) return Optional.empty();
        try {
            return Optional.of(Path.of(s));
        } catch (InvalidPathException e) {
            note.accept("not a usable path: " + raw + " (" + e.getReason() + ")");
            return Optional.empty();
        }
    }
}
