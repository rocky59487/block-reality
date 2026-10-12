package com.blockreality.core.finite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;
import static com.blockreality.core.finite.FiniteFixtures.*;

/**
 * Real-JVM interruption fixture for the finite object store. The child process publishes a
 * source object through the {@link FiniteStore.FaultHook} seam and {@code Runtime.halt}s at
 * the named stage; a second, fresh JVM then proves the durable outcome is exactly one of
 * "nothing published, evidence retained" or "object fully durable" — never a half object.
 *
 * <p>This is filesystem evidence, not a Minecraft restart qualification.
 */
public final class FiniteProcessDriver {

    private FiniteProcessDriver() { }

    private static final UUID REQUEST = new UUID(90, 90);

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("mode scenario owned-directory");
        String mode = args[0], scenario = args[1];
        Path root = Path.of(args[2]).toAbsolutePath().resolve("finite-store");
        Path marker = Path.of(args[2]).toAbsolutePath().resolve("FINITE_PROCESS_OWNED");

        if (mode.equals("crash")) {
            Files.createDirectories(root);
            Files.writeString(marker, "finite-store-process-v1", StandardOpenOption.CREATE_NEW);
        } else if (!Files.readString(marker).equals("finite-store-process-v1")) {
            throw new IOException("Ownership marker required");
        }

        if (mode.equals("lock-contender")) {
            try (var unexpected = FiniteStore.open(root)) {
                throw new AssertionError("Concurrent process acquired finite store");
            } catch (IOException locked) {
                System.out.println("PASS concurrent JVM denied by exclusive store owner");
                return;
            }
        }

        var domain = domainId(DIMENSION);
        var source = FiniteFixtures.source(domain.bsiDomain(), 0);
        var payload = source.serialize();
        var expectedSha = FiniteSource.sha256(FiniteCodec.pack(FiniteCodec.KIND_SOURCE, payload));
        var objectPath = root.resolve("objects/" + expectedSha + ".blob");

        if (mode.equals("crash")) {
            try (var store = FiniteStore.open(root, stage -> {
                if (scenario.equals(stage)) {
                    System.out.println("Interrupting JVM at " + stage);
                    System.out.flush();
                    Runtime.getRuntime().halt(73);
                }
            })) {
                store.publish(REQUEST, FiniteCodec.KIND_SOURCE, payload);
                throw new AssertionError("Requested process interruption was not reached: " + scenario);
            }
        }

        // ---- recover: a fresh JVM observes only durable state.
        try (var store = FiniteStore.open(root)) {
            boolean moved = scenario.equals("moved");
            if (Files.isRegularFile(objectPath) != moved)
                throw new AssertionError("Post-crash object visibility wrong for stage " + scenario);
            var ref = new FiniteBlobRef(expectedSha,
                    FiniteCodec.pack(FiniteCodec.KIND_SOURCE, payload).length);

            if (!moved) {
                // The staged bytes must still be visible as bounded evidence, never silently dropped.
                var evidence = store.stagingEvidence();
                if (evidence.isEmpty()) throw new AssertionError("Crash staging evidence was lost");
                boolean found = evidence.stream().anyMatch(d -> d.getFileName().toString().equals(REQUEST.toString()));
                if (!found) throw new AssertionError("Request staging directory missing");
                try (var inside = Files.newDirectoryStream(
                        root.resolve("staging").resolve(REQUEST.toString()))) {
                    if (!inside.iterator().hasNext()) throw new AssertionError("Staging evidence is empty");
                }
                store.discardEvidence(root.resolve("staging").resolve(REQUEST.toString()));
            } else {
                // The move completed: the object must read back byte-identical under its content name.
                var body = store.read(ref);
                if (body.kind() != FiniteCodec.KIND_SOURCE || !java.util.Arrays.equals(body.payload(), payload))
                    throw new AssertionError("Moved object did not survive the crash");
            }

            // Re-publish is idempotent in either outcome and always returns the same durable ref.
            var republished = store.publish(REQUEST, FiniteCodec.KIND_SOURCE, payload);
            if (!republished.equals(ref)) throw new AssertionError("Republished object identity changed");
            var again = store.read(republished);
            if (!java.util.Arrays.equals(again.payload(), payload))
                throw new AssertionError("Recovered object payload mismatch");
            if (!store.stagingEvidence().isEmpty())
                throw new AssertionError("Republish left staging residue");

            String report = "{\"scenario\":\"" + scenario + "\",\"object_durable\":" + moved
                    + ",\"republished\":\"" + republished.sha256() + "\",\"status\":\"PASS\"}\n";
            Files.writeString(Path.of(args[2]).resolve("recovery.json"), report, StandardOpenOption.CREATE_NEW);
            System.out.print(report);
        }
    }
}
