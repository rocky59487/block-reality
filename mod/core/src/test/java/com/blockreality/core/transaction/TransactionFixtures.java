package com.blockreality.core.transaction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.blockreality.core.transaction.ConstructionTransaction.*;

final class TransactionFixtures {
    static final UUID DOMAIN = new UUID(1, 1), ACTOR = new UUID(2, 2), SESSION = new UUID(3, 3);
    static final UUID PIECE_A = new UUID(4, 4), PIECE_B = new UUID(5, 5);
    private TransactionFixtures() { }
    static Request request(long key, long revision) {
        return new Request(new UUID(10, key), ACTOR, SESSION, DOMAIN, revision, "ab".repeat(32));
    }
    static Value value(String s) { return Value.of(s.getBytes(StandardCharsets.UTF_8)); }
    static Intent intent(Request request) {
        return new Intent(request, List.of(
                new Change("world/1", Value.missing(), value("steel/x")),
                new Change("inventory/0", value("steel:10"), value("steel:8")),
                new Change("world/2", Value.missing(), value("steel/y")),
                new Change("revision", value("" + request.baseRevision()), value("" + (request.baseRevision() + 1))),
                new Change("registry/1", Value.missing(), value("transaction-born:" + PIECE_A)),
                new Change("registry/2", Value.missing(), value("transaction-born:" + PIECE_B))),
                List.of(PIECE_B, PIECE_A), List.of());
    }
    static Map<String, Value> before(Intent intent) { return images(intent, false); }
    static Map<String, Value> after(Intent intent) { return images(intent, true); }
    private static Map<String, Value> images(Intent intent, boolean after) {
        var values = new TreeMap<String, Value>();
        for (Change change : intent.changes()) values.put(change.resource(), after ? change.after() : change.before());
        return values;
    }

    static class MemoryHost implements AtomicConstructionCoordinator.Host {
        final Map<String, Value> live = new TreeMap<>(), durable = new TreeMap<>(), visible = new TreeMap<>();
        final List<String> calls = new ArrayList<>();
        int writes, flushes, publications, baselines;
        int failWrite = -1, failFlush = -1;
        boolean failPublish, failBaseline, permanentWriteFailure;
        Runnable onWrite = () -> { };
        MemoryHost(Intent intent) { live.putAll(before(intent)); durable.putAll(live); visible.putAll(live); }
        @Override public void checkAccess() { }
        @Override public long revision() { return Long.parseLong(new String(live.get("revision").bytes(), StandardCharsets.UTF_8)); }
        @Override public Value read(String resource) { calls.add("read:" + resource); return live.getOrDefault(resource, Value.missing()); }
        @Override public void write(String resource, Value value) throws IOException {
            calls.add("write:" + resource); writes++;
            if (writes == failWrite || permanentWriteFailure) throw new IOException("Injected participant write");
            live.put(resource, value); onWrite.run();
        }
        @Override public void flush(List<String> resources) throws IOException {
            calls.add("flush"); flushes++;
            if (flushes == failFlush) throw new IOException("Injected participant flush");
            for (String resource : resources) durable.put(resource, live.getOrDefault(resource, Value.missing()));
        }
        @Override public void publish(Receipt receipt) throws IOException {
            calls.add("publish"); publications++;
            if (failPublish) throw new IOException("Injected publication");
            visible.clear(); visible.putAll(live);
        }
        @Override public void publishRecoveredState() throws IOException {
            calls.add("baseline"); baselines++;
            if (failBaseline) throw new IOException("Injected baseline publication");
            visible.clear(); visible.putAll(live);
        }
    }
}
