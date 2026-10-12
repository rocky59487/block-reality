package com.blockreality.core.finite;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Standalone finite-store crash/recovery evidence runner: one fresh JVM per crash stage and
 * one per recovery, plus an exclusive-lock contender. Raw crash images and process logs are
 * retained under the given fresh output directory.
 */
public final class FiniteProcessGate {

    private FiniteProcessGate() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Fresh output directory required");
        Path output = Path.of(args[0]).toAbsolutePath();
        if (Files.exists(output)) throw new IllegalArgumentException("Refusing to overwrite earlier process evidence");
        Files.createDirectories(output);

        var scenarios = List.of("forced", "moved");
        long begin = System.nanoTime();
        for (String scenario : scenarios) {
            Path directory = output.resolve(scenario);
            Files.createDirectories(directory);
            run("crash", scenario, directory, 73);
            Path snapshot = directory.resolve("before-recovery");
            Files.createDirectory(snapshot);
            try (var files = Files.walk(directory)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(p -> !p.startsWith(snapshot)).toList()) {
                    Path target = snapshot.resolve(directory.relativize(file));
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target);
                }
            }
            run("recover", scenario, directory, 0);
            System.out.println("PASS " + scenario);
        }

        // A second writer in another JVM can never share the store.
        Path contested = output.resolve("exclusive-owner");
        Files.createDirectories(contested);
        Files.writeString(contested.resolve("FINITE_PROCESS_OWNED"), "finite-store-process-v1",
                StandardOpenOption.CREATE_NEW);
        try (var owner = FiniteStore.open(contested.resolve("finite-store"))) {
            run("lock-contender", "exclusive-owner", contested, 0);
            if (!Files.isDirectory(contested.resolve("finite-store/objects")))
                throw new AssertionError("Store layout missing after contention");
        }

        long millis = Duration.ofNanos(System.nanoTime() - begin).toMillis();
        String report = "{\"status\":\"PASS\",\"crashed_jvms\":" + scenarios.size()
                + ",\"fresh_recovery_jvms\":" + scenarios.size()
                + ",\"concurrent_owner_checks\":1,\"elapsed_ms\":" + millis
                + ",\"scope\":\"finite object store, not Minecraft\"}\n";
        Files.writeString(output.resolve("summary.json"), report, StandardOpenOption.CREATE_NEW);
        System.out.print(report);
    }

    private static void run(String mode, String scenario, Path directory, int expectedExit) throws Exception {
        String suffix = System.getProperty("os.name", "").startsWith("Windows") ? ".exe" : "";
        Path java = Path.of(System.getProperty("java.home"), "bin", "java" + suffix);
        String classpath = Path.of(FiniteProcessDriver.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator
                + Path.of(FiniteStore.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator
                + Path.of(com.blockreality.api.geom.BlockKey.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> command = List.of(java.toString(), "-cp", classpath,
                FiniteProcessDriver.class.getName(), mode, scenario, directory.toString());
        Files.write(directory.resolve(mode + "-command.txt"), command, StandardOpenOption.CREATE_NEW);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve(mode + ".log").toFile())
                .start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS))
                throw new AssertionError("Process timeout: " + mode + " " + scenario);
            if (process.exitValue() != expectedExit)
                throw new AssertionError("Process exit " + process.exitValue() + ": " + mode + " " + scenario);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }
}
