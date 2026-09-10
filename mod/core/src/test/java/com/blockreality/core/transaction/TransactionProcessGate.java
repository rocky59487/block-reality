package com.blockreality.core.transaction;

import java.io.File;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Standalone evidence runner. Raw crash images, recovered files and process logs are retained. */
public final class TransactionProcessGate {
    private TransactionProcessGate() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Fresh output directory required");
        Path output = Path.of(args[0]).toAbsolutePath();
        if (Files.exists(output)) throw new IllegalArgumentException("Refusing to overwrite earlier process evidence");
        Files.createDirectories(output);
        List<String> scenarios = new ArrayList<>();
        for (String phase : List.of("prepare", "commit", "abort")) for (var stage : FileTransactionJournal.Stage.values())
            scenarios.add(phase + "-" + stage.name().toLowerCase(Locale.ROOT));
        for (int i = 1; i <= 6; i++) { scenarios.add("write-" + i); scenarios.add("flush-" + i); }
        for (int i = 1; i <= 6; i++) { scenarios.add("rollback-write-" + i); scenarios.add("rollback-flush-" + i); }
        scenarios.add("publish");
        long begin = System.nanoTime();
        for (String scenario : scenarios) {
            Path directory = output.resolve(scenario); Files.createDirectories(directory);
            run("crash", scenario, directory, 73);
            Path snapshot = directory.resolve("before-recovery"); Files.createDirectory(snapshot);
            try (var files = Files.walk(directory)) {
                for (Path file : files.filter(Files::isRegularFile).filter(p -> !p.startsWith(snapshot)).toList()) {
                    Path target = snapshot.resolve(directory.relativize(file)); Files.createDirectories(target.getParent()); Files.copy(file, target);
                }
            }
            run("recover", scenario, directory, 0);
            System.out.println("PASS " + scenario);
        }
        Path contested = output.resolve("exclusive-owner"); Files.createDirectory(contested);
        Files.writeString(contested.resolve("CT_CORE_PROCESS_OWNED"), "construction-core-process-v1", StandardOpenOption.CREATE_NEW);
        try (var owner = new FileTransactionJournal(contested.resolve("journal"), TransactionFixtures.DOMAIN)) {
            run("lock-contender", "exclusive-owner", contested, 0);
            if (owner.keyCount() != 0) throw new AssertionError("Contending process changed journal");
        }
        long millis = Duration.ofNanos(System.nanoTime() - begin).toMillis();
        String report = "{\"status\":\"PASS\",\"crashed_jvms\":" + scenarios.size() + ",\"fresh_recovery_jvms\":"
                + scenarios.size() + ",\"concurrent_owner_checks\":1,\"elapsed_ms\":" + millis + ",\"scope\":\"file participants, not Minecraft\"}\n";
        Files.writeString(output.resolve("summary.json"), report, StandardOpenOption.CREATE_NEW); System.out.print(report);
    }
    private static void run(String mode, String scenario, Path directory, int expectedExit) throws Exception {
        String suffix = System.getProperty("os.name", "").startsWith("Windows") ? ".exe" : "";
        Path java = Path.of(System.getProperty("java.home"), "bin", "java" + suffix);
        String classpath = Path.of(TransactionProcessDriver.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + Path.of(AtomicConstructionCoordinator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> command = List.of(java.toString(), "-cp", classpath, TransactionProcessDriver.class.getName(), mode, scenario, directory.toString());
        Files.write(directory.resolve(mode + "-command.txt"), command, StandardOpenOption.CREATE_NEW);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(mode + ".log").toFile()).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new AssertionError("Process timeout: " + mode + " " + scenario);
            if (process.exitValue() != expectedExit) throw new AssertionError("Process exit " + process.exitValue() + ": " + mode + " " + scenario);
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); } }
    }
}
