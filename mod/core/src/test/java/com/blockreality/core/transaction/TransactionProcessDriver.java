package com.blockreality.core.transaction;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;
import static com.blockreality.core.transaction.ConstructionTransaction.*;
import static com.blockreality.core.transaction.TransactionFixtures.*;

/** A real process-interruption fixture using file participants, NOT Minecraft restart evidence. */
public final class TransactionProcessDriver {
    private TransactionProcessDriver() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("mode scenario owned-directory");
        String mode = args[0], scenario = args[1]; Path root = Path.of(args[2]).toAbsolutePath();
        Intent intent = intent(request(1, 0)); Path marker = root.resolve("CT_CORE_PROCESS_OWNED");
        if (mode.equals("crash")) {
            Files.createDirectories(root);
            Files.writeString(marker, "construction-core-process-v1", CREATE_NEW);
        } else if (!Files.readString(marker).equals("construction-core-process-v1")) throw new IOException("Ownership marker required");
        if (mode.equals("lock-contender")) {
            try (var unexpected = new FileTransactionJournal(root.resolve("journal"), DOMAIN)) {
                throw new AssertionError("Concurrent process acquired journal " + unexpected.domain());
            } catch (IOException locked) {
                if (!locked.getMessage().equals("Journal already owned")) throw locked;
                System.out.println("PASS concurrent JVM denied by exclusive journal owner"); return;
            }
        }
        var host = new FileHost(root, mode.equals("crash") ? scenario : "");
        if (mode.equals("crash")) host.initialize(intent); else host.load(intent);
        try (var journal = new FileTransactionJournal(root.resolve("journal"), DOMAIN, (stage, entry) -> {
            if (!mode.equals("crash")) return;
            String prefix = entry.phase() == Phase.PREPARED ? "prepare-" : entry.phase() == Phase.COMMITTED ? "commit-" : "abort-";
            haltWhen(scenario, prefix + stage.name().toLowerCase(Locale.ROOT));
        })) {
            var coordinator = new AtomicConstructionCoordinator(journal); coordinator.recover(host);
            if (mode.equals("crash")) {
                coordinator.execute(intent.request(), r -> intent, host);
                throw new AssertionError("Requested process interruption was not reached: " + scenario);
            }
            boolean committed = scenario.startsWith("commit-") && !scenario.endsWith("temp_forced") || scenario.equals("publish");
            Map<String, Value> expected = committed ? after(intent) : before(intent);
            host.load(intent); equal(expected, host.live, "Durable participant images");
            equal(expected, host.published(), "Published recovered baseline");
            if (!coordinator.ready() || journal.pending().isPresent()) throw new AssertionError("Recovery remains pending");
            Optional<Entry> outcome = journal.read(intent.request().id());
            if (scenario.equals("prepare-temp_forced")) {
                if (outcome.isPresent()) throw new AssertionError("Incomplete temp became a decision");
                if (journal.orphanFiles().size() != 1) throw new AssertionError("Crash temp was not preserved");
            } else {
                Phase expectedPhase = committed ? Phase.COMMITTED : Phase.ABORTED;
                equal(expectedPhase, outcome.orElseThrow().phase(), "Terminal phase");
                Receipt receipt = outcome.orElseThrow().receipt();
                for (int i = 0; i < 100; i++) equal(receipt, coordinator.execute(intent.request(), r -> {
                    throw new AssertionError("Replay executed preparer");
                }, host), "Restart replay " + i);
            }
            String report = "{\"scenario\":\"" + scenario + "\",\"phase\":\"" + outcome.map(e -> e.phase().name()).orElse("ABSENT")
                    + "\",\"images\":" + intent.changes().size() + ",\"replays\":" + (outcome.isPresent() ? 100 : 0)
                    + ",\"directory_sync\":" + journal.directorySyncAvailable() + ",\"status\":\"PASS\"}\n";
            Files.writeString(root.resolve("recovery.json"), report, CREATE_NEW); System.out.print(report);
        }
    }
    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + ": expected " + expected + ", actual " + actual);
    }
    private static void haltWhen(String requested, String reached) {
        if (requested.equals(reached)) {
            System.out.println("Interrupting JVM at " + reached); System.out.flush(); Runtime.getRuntime().halt(73);
        }
    }

    private static final class FileHost implements AtomicConstructionCoordinator.Host {
        private final Path root;
        private final String scenario;
        private final Map<String, Value> live = new TreeMap<>();
        private int writes, flushes;
        private int flushCalls;
        FileHost(Path root, String scenario) { this.root = root; this.scenario = scenario; }
        void initialize(Intent intent) throws IOException {
            live.putAll(before(intent));
            for (var entry : live.entrySet()) save(file(entry.getKey()), entry.getValue());
        }
        void load(Intent intent) throws IOException {
            for (var change : intent.changes()) live.put(change.resource(), readFile(file(change.resource())));
        }
        Map<String, Value> published() throws IOException {
            var result = new TreeMap<String, Value>();
            for (String key : live.keySet()) result.put(key, readFile(root.resolve("published-" + file(key).getFileName())));
            return result;
        }
        @Override public void checkAccess() { }
        @Override public void checkpoint(List<String> resources) throws IOException {
            for (String resource : resources) {
                if (!live.get(resource).equals(readFile(file(resource))))
                    throw new IOException("Process fixture baseline was not durable");
            }
        }
        @Override public long revision() { return Long.parseLong(new String(live.get("revision").bytes(), StandardCharsets.UTF_8)); }
        @Override public Value read(String resource) { return live.getOrDefault(resource, Value.missing()); }
        @Override public void write(String resource, Value value) {
            live.put(resource, value); haltWhen(scenario, "write-" + ++writes);
            if (writes > 6) haltWhen(scenario, "rollback-write-" + (writes - 6));
        }
        @Override public void flush(List<String> resources) throws IOException {
            flushCalls++;
            for (String resource : resources) {
                save(file(resource), live.get(resource)); haltWhen(scenario, "flush-" + ++flushes);
                if (flushes > 6) haltWhen(scenario, "rollback-flush-" + (flushes - 6));
            }
            if (flushCalls == 1 && (scenario.startsWith("rollback-") || scenario.startsWith("abort-")))
                throw new IOException("Injected apply failure after participant flush to exercise interrupted rollback");
        }
        @Override public void publish(Receipt receipt) { haltWhen(scenario, "publish"); }
        @Override public void publishRecoveredState() throws IOException {
            for (var entry : live.entrySet()) save(root.resolve("published-" + file(entry.getKey()).getFileName()), entry.getValue());
        }
        private Path file(String resource) {
            return root.resolve(Base64.getUrlEncoder().withoutPadding().encodeToString(resource.getBytes(StandardCharsets.UTF_8)) + ".value");
        }
        private static void save(Path path, Value value) throws IOException {
            try (var out = FileChannel.open(path, CREATE, TRUNCATE_EXISTING, WRITE)) {
                var bytes = ByteBuffer.allocate(4 + Math.max(0, value.size())); value.put(bytes); bytes.flip();
                while (bytes.hasRemaining()) out.write(bytes); out.force(true);
            }
        }
        private static Value readFile(Path path) throws IOException {
            var bytes = ByteBuffer.wrap(Files.readAllBytes(path)); int size = bytes.getInt();
            if (size == -1 && !bytes.hasRemaining()) return Value.missing();
            if (size < 0 || size != bytes.remaining()) throw new IOException("Invalid participant file");
            byte[] value = new byte[size]; bytes.get(value); return Value.of(value);
        }
    }
}
