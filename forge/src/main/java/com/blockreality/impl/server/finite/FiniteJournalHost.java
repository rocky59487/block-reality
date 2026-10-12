package com.blockreality.impl.server.finite;

import com.blockreality.core.transaction.AtomicConstructionCoordinator;
import com.blockreality.core.transaction.ConstructionTransaction.Change;
import com.blockreality.core.transaction.ConstructionTransaction.Entry;
import com.blockreality.core.transaction.ConstructionTransaction.Intent;
import com.blockreality.core.transaction.ConstructionTransaction.Phase;
import com.blockreality.core.transaction.ConstructionTransaction.Receipt;
import com.blockreality.core.transaction.ConstructionTransaction.Value;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The coordinator {@link AtomicConstructionCoordinator.Host} for finite transactions
 * (design §5). Finite resources live only inside the journal — {@code finite/*} keys have
 * no world image — so this host is a verification harness: it serves the recorded
 * participant images back to the coordinator's CAS/rollback checks, bounds the resource
 * barrier, and publishes committed state through {@code FiniteRegistry.applyCommitted}.
 *
 * <p>Two bound forms:
 *
 * <ul>
 *   <li>execute path — created unbound, then {@link #bind} is called by the runtime's
 *       preparer once {@code FiniteRegistry.prepare*} produced the proposal;
 *   <li>recovery path — created bound to a pending journal entry and re-validated through
 *       {@code FiniteRegistry.validatePrepared} before any of its images are trusted.
 * </ul>
 *
 * Non-finite resources are refused outright: a finite intent that claims world/chunk
 * participants is out of scope for this unit and fails closed rather than guessing.
 */
final class FiniteJournalHost implements AtomicConstructionCoordinator.Host {

    private final FiniteRuntime runtime;
    private Entry bound;
    private final Map<String, Value> working = new HashMap<>();

    /** Execute-path host: unbound until the preparer produces the proposal. */
    FiniteJournalHost(FiniteRuntime runtime) {
        this.runtime = runtime;
    }

    /** Recovery-path host: bound to the pending entry and re-validated at construction. */
    FiniteJournalHost(FiniteRuntime runtime, Entry pending) throws IOException {
        this.runtime = runtime;
        bind(pending);
    }

    /** Validate the proposal against committed authority and take ownership of its images. */
    void bind(Entry entry) throws IOException {
        runtime.registry().validatePrepared(entry);
        for (Change change : entry.intent().changes()) {
            if (!change.resource().startsWith("finite/"))
                throw new IOException("Finite host cannot serve foreign resource " + change.resource());
            working.put(change.resource(), change.before());
        }
        this.bound = entry;
    }

    /**
     * True iff <em>every</em> change lives under {@code finite/} — the pure-finite
     * transaction shape this host can serve. A mixed intent (finite plus world
     * resources, e.g. a committed fracture) is deliberately not finite: routing it
     * here would only fail closed in {@link #bind} on the foreign participant
     * (doc §9.6).
     */
    static boolean isFinite(Entry entry) {
        Intent intent = entry.intent();
        if (intent == null) return false;
        for (Change change : intent.changes())
            if (!change.resource().startsWith("finite/")) return false;
        return true;
    }

    /**
     * True iff any change carries fracture evidence ({@code finite/fracture/…}) — the
     * durable signature of a {@code CommitFracture} intent. Such an entry routes to
     * the fracture host through {@code FiniteFractureDriver.recoveryHost}, whether it
     * is pure-finite (plan/root/tx only) or mixed with world participants (doc §9.6).
     */
    static boolean isFracture(Entry entry) {
        Intent intent = entry.intent();
        if (intent == null) return false;
        for (Change change : intent.changes())
            if (change.resource().startsWith("finite/fracture/")) return true;
        return false;
    }

    @Override public void checkAccess() {
        runtime.threadGuard().run();
        if (!runtime.armed() || !runtime.failure().isEmpty())
            throw new IllegalStateException("Finite transaction owner is not available");
    }

    /** The finite total order is the revision participant: before-bind it is the committed order. */
    @Override public long revision() {
        Value order = working.get("finite/order");
        return order == null ? runtime.order() : number(order);
    }

    private static long number(Value value) {
        if (value == null || !value.present() || value.bytes().length != 8)
            throw new IllegalArgumentException("Invalid finite order image");
        var buffer = ByteBuffer.wrap(value.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        long n = buffer.getLong();
        if (n < 0) throw new IllegalArgumentException("Invalid finite order image");
        return n;
    }

    @Override public Value read(String resource) throws IOException {
        checkAccess();
        if (bound == null) throw new IOException("Finite host has no bound proposal");
        if (!bound.intent().changes().stream().anyMatch(c -> c.resource().equals(resource)))
            throw new IOException("Unknown finite resource " + resource);
        Value value = working.get(resource);
        return value == null ? Value.missing() : value;
    }

    private void requireResources(List<String> resources) throws IOException {
        checkAccess();
        if (bound == null) throw new IOException("Finite host has no bound proposal");
        var expected = new HashSet<String>();
        for (Change change : bound.intent().changes()) expected.add(change.resource());
        if (resources.size() != expected.size() || !expected.equals(new HashSet<>(resources)))
            throw new IOException("Incomplete finite participant barrier");
    }

    /** Finite before-images live in the durable intent itself; the barrier is verification only. */
    @Override public void checkpoint(List<String> resources) throws IOException {
        requireResources(resources);
    }

    @Override public void write(String resource, Value value) throws IOException {
        checkAccess();
        Change change = bound == null ? null : bound.intent().changes().stream()
                .filter(c -> c.resource().equals(resource)).findFirst().orElse(null);
        if (change == null || !value.equals(change.before()) && !value.equals(change.after()))
            throw new IOException("Foreign finite write " + resource);
        working.put(resource, value);
    }

    @Override public void flush(List<String> resources) throws IOException {
        requireResources(resources);
    }

    @Override public void publish(Receipt receipt) throws Exception {
        checkAccess();
        if (bound == null || receipt.phase() != Phase.COMMITTED
                || !receipt.request().equals(bound.request())
                || receipt.revision() != bound.intent().resultRevision())
            throw new IOException("Finite publication binding mismatch");
        runtime.committed(receipt);
    }

    @Override public void publishRecoveredState() throws IOException {
        checkAccess();
        runtime.republish();
    }
}
