package com.blockreality.core.bsi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The contract this build speaks, read from the resource the Gradle build copies out of
 * {@code contract/CONTRACT_SHA256}.
 *
 * <p>There is exactly one way an engine and a mod can disagree about the interface without
 * anyone noticing: both compile, both run, and the bytes mean different things. The hash
 * closes that. It is exchanged in {@code bsi.hello}; a mismatch disables the engine with a
 * sentence naming both sides (D-044 §3, N24-b2) instead of producing numbers nobody can trust.
 *
 * <p>The resource is a build output, not a checked-in copy: {@code processResources} takes it
 * from {@code contract/} so it cannot drift from the directory it describes.
 */
public final class BsiContract {

    /** BSI major version this codec implements. A different major is not negotiable. */
    public static final int MAJOR = 1;

    /** ABI generation of {@code bsi_capi.h} this codec binds. Append-only; a different value is refused. */
    public static final int CAPI_ABI = 1;

    private static final String RESOURCE = "/blockreality/contract/CONTRACT_SHA256";

    private static final String SHA = load();

    private BsiContract() {}

    /** The pinned contract hash, lowercase hex, 64 chars. */
    public static String sha256() { return SHA; }

    /** True when this build carries a contract hash at all (a source checkout without the resource does not). */
    public static boolean available() { return SHA != null && SHA.length() == 64; }

    private static String load() {
        try (InputStream in = BsiContract.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return null;
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return s.length() == 64 ? s : null;
        } catch (IOException e) {
            return null;
        }
    }
}
