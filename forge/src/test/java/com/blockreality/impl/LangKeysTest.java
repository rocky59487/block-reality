package com.blockreality.impl;

import com.blockreality.api.BucklingState;
import com.blockreality.api.GoverningFibre;
import com.blockreality.api.ScanMode;
import com.blockreality.api.UnassignedReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every translation key the game can ask for resolves, in both languages.
 *
 * <p>A missing key is not an exception and not a log line: Minecraft renders the key
 * itself, so the HUD reads {@code br.hud.model_incomplete} at the player. Nothing in the
 * build noticed that until this test existed, and the failure mode is exactly the kind
 * this project keeps running into — a rename that compiles, ships, and only shows up when
 * someone looks at the screen. It has already happened here once: a new key collided with
 * one that meant something else and the sed that fixed it renamed the wrong usage too.
 *
 * <p>The key set is derived, never listed. Literals come out of the sources, and the keys
 * that are built at runtime from an enum name come out of the enum, so adding a constant
 * is enough to make this test demand its string.
 */
class LangKeysTest {

    /**
     * System property names, not translation keys.
     *
     * <p>The scanner takes every {@code "br.*"} literal in the sources as a key the game
     * can ask for, which is what makes it derived rather than listed — a new key demands
     * its string without anyone remembering to add it here. The cost is these: the two
     * {@code -D} properties that name where an engine is, which have the same shape and
     * are never rendered at a player.
     *
     * <p>Adding to this set is how the gate is WEAKENED, so it takes a reason. The bar is
     * that the literal is passed to {@link System#getProperty} or an equivalent, and never
     * to a translation lookup — verifiable by grep, which is why the property is named
     * here rather than a prefix being excluded wholesale.
     */
    private static final Set<String> NOT_LANG_KEYS = Set.of("br.sidecar", "br.engine");

    private static final Pattern LITERAL = Pattern.compile("\"(br\\.[a-z0-9_.]*)\"");
    private static final Pattern ENTRY = Pattern.compile("^ {2}\"([^\"]+)\": \"(.*)\",?$");

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && p != null; i++) {
            if (Files.isDirectory(p.resolve("mod/api")) && Files.isDirectory(p.resolve("forge"))) {
                return p;
            }
            p = p.getParent();
        }
        // Not a skip. A gate that cannot find what it checks has to say so.
        throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
    }

    /**
     * The keys in one lang file, in file order.
     *
     * <p>Line-shaped rather than JSON-parsed, and strict about it: any line that is not
     * an opening brace, a closing brace, or exactly one entry fails the test. A lenient
     * scanner could skip a line it did not understand and report a key as missing that is
     * really there — or worse, miss a duplicate.
     */
    private static Map<String, String> readLang(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (int i = 0; i < lines.size(); i++) {
            String ln = lines.get(i);
            if (ln.isBlank() || ln.equals("{") || ln.equals("}")) continue;
            Matcher m = ENTRY.matcher(ln);
            if (!m.matches()) {
                fail(file.getFileName() + ":" + (i + 1) + " is not one plain entry: " + ln);
            }
            String key = m.group(1);
            if (out.put(key, m.group(2)) != null) {
                fail(file.getFileName() + ":" + (i + 1) + " repeats the key " + key);
            }
        }
        assertTrue(out.size() > 40, file + " parsed as only " + out.size() + " keys");
        return out;
    }

    /** Every {@code "br.…"} literal in the shipped sources, prefixes excluded. */
    private static Set<String> literalKeys(Path root) {
        Set<String> keys = new TreeSet<>();
        for (String dir : List.of("forge/src/main/java", "mod/api/src/main/java",
                                  "mod/core/src/main/java")) {
            Path base = root.resolve(dir);
            assertTrue(Files.isDirectory(base), "missing source tree " + base);
            try (Stream<Path> walk = Files.walk(base)) {
                for (Path f : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Matcher m = LITERAL.matcher(Files.readString(f, StandardCharsets.UTF_8));
                    while (m.find()) {
                        String k = m.group(1);
                        // A literal ending in "." is a prefix a runtime name is appended
                        // to. Those families are covered by the enum walks below, which
                        // know the whole set rather than the stem.
                        if (k.endsWith(".")) continue;
                        if (NOT_LANG_KEYS.contains(k)) continue;
                        keys.add(k);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return keys;
    }

    /** The lenses the glasses can actually reach, by walking the cycle rather than trusting it. */
    private static Set<ScanMode> reachableModes() {
        Set<ScanMode> seen = new LinkedHashSet<>();
        ScanMode m = ScanMode.UTILIZATION;
        while (seen.add(m)) m = m.nextDemoMode();
        return seen;
    }

    private static Set<String> expectedKeys(Path root) {
        Set<String> keys = new TreeSet<>(literalKeys(root));
        // Built at runtime from a name, so no literal exists to find.
        for (UnassignedReason r : UnassignedReason.values()) keys.add(r.translationKey());
        for (BucklingState s : BucklingState.values()) keys.add(s.translationKey());
        for (GoverningFibre f : GoverningFibre.values()) {
            keys.add("br.fibre." + f.name().toLowerCase(Locale.ROOT));
        }
        // Only the lenses a player can switch to. The four later ones are named in the
        // enum but unreachable, and giving them display strings now would read as a claim
        // that they exist. When one joins the cycle this test starts demanding its key.
        for (ScanMode m : reachableModes()) {
            keys.add("br.scan.mode." + m.name().toLowerCase(Locale.ROOT));
        }
        return keys;
    }

    @Test
    @DisplayName("every key the game can ask for has an English string")
    void englishIsComplete() {
        Path root = repoRoot();
        Map<String, String> en = readLang(
                root.resolve("forge/src/main/resources/assets/blockreality/lang/en_us.json"));
        Set<String> missing = new TreeSet<>(expectedKeys(root));
        missing.removeAll(en.keySet());
        assertEquals(Set.of(), missing, "keys with no en_us string");
    }

    @Test
    @DisplayName("the two languages carry the same keys, so neither is half translated")
    void bothLanguagesAgree() {
        Path root = repoRoot();
        Path lang = root.resolve("forge/src/main/resources/assets/blockreality/lang");
        Set<String> en = new TreeSet<>(readLang(lang.resolve("en_us.json")).keySet());
        Set<String> zh = new TreeSet<>(readLang(lang.resolve("zh_tw.json")).keySet());
        Set<String> onlyEn = new TreeSet<>(en);
        onlyEn.removeAll(zh);
        Set<String> onlyZh = new TreeSet<>(zh);
        onlyZh.removeAll(en);
        assertEquals(Set.of(), onlyEn, "in en_us but not zh_tw");
        assertEquals(Set.of(), onlyZh, "in zh_tw but not en_us");
    }

    @Test
    @DisplayName("no br.* string is carried that nothing can ask for")
    void noDeadKeys() {
        Path root = repoRoot();
        Map<String, String> en = readLang(
                root.resolve("forge/src/main/resources/assets/blockreality/lang/en_us.json"));
        Set<String> dead = new TreeSet<>();
        for (String k : en.keySet()) {
            if (k.startsWith("br.")) dead.add(k);
        }
        dead.removeAll(expectedKeys(root));
        // Not cosmetic: a string nothing reaches is a string nobody rereads, and the next
        // person to see it assumes the feature behind it works. br.hud.buckling_skipped
        // outlived the boolean it described by exactly one commit.
        assertEquals(Set.of(), dead, "lang keys nothing in the sources can ask for");
    }

    @Test
    @DisplayName("a format string with a placeholder has one in both languages")
    void placeholdersMatch() {
        Path root = repoRoot();
        Path lang = root.resolve("forge/src/main/resources/assets/blockreality/lang");
        Map<String, String> en = readLang(lang.resolve("en_us.json"));
        Map<String, String> zh = readLang(lang.resolve("zh_tw.json"));
        Set<String> mismatched = new TreeSet<>();
        for (Map.Entry<String, String> e : en.entrySet()) {
            String other = zh.get(e.getKey());
            if (other == null) continue;   // bothLanguagesAgree owns that failure
            // Minecraft substitutes positionally. A translation that dropped the %s shows
            // the sentence without its number, which is a quieter bug than a missing key
            // and just as wrong: "block(s) left out" with no count says nothing.
            if (count(e.getValue()) != count(other)) mismatched.add(e.getKey());
        }
        assertEquals(Set.of(), mismatched, "placeholder count differs between languages");
    }

    private static int count(String s) {
        int n = 0;
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) == '%' && s.charAt(i + 1) == 's') n++;
        }
        return n;
    }
}
