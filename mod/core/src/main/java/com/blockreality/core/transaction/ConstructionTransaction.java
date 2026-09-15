package com.blockreality.core.transaction;

import java.util.*;
import java.util.regex.Pattern;

/** Module-owned material/world metadata transactions, never engine results or physical fields. */
public final class ConstructionTransaction {
    public static final int MAX_CELLS = 4096, MAX_PIECES = 256, MAX_RESOURCES = 8192;
    public static final int MAX_VALUE_BYTES = 1 << 20, MAX_RECORD_BYTES = 16 << 20;
    public static final int MAX_KEYS = 262144;
    public static final long MAX_JOURNAL_BYTES = 1L << 30;
    private static final Pattern PLAN_HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9][a-z0-9_./:+-]{0,255}");
    private ConstructionTransaction() { }

    /** All fields bind an idempotency key. Actor/session must come from authenticated server context. */
    public record Request(UUID id, UUID actor, UUID session, UUID domain, long baseRevision, String planHash) {
        public Request {
            Objects.requireNonNull(id); Objects.requireNonNull(actor);
            Objects.requireNonNull(session); Objects.requireNonNull(domain);
            if (baseRevision < 0 || baseRevision == Long.MAX_VALUE
                    || planHash == null || !PLAN_HASH.matcher(planHash).matches())
                throw new IllegalArgumentException("Invalid transaction binding");
        }
    }

    /** Missing and present-but-empty are different states; arrays never escape by reference. */
    public static final class Value {
        private static final Value MISSING = new Value(null);
        private final byte[] data;
        private Value(byte[] data) { this.data = data; }
        public static Value missing() { return MISSING; }
        public static Value of(byte[] data) {
            Objects.requireNonNull(data);
            if (data.length > MAX_VALUE_BYTES) throw new IllegalArgumentException("Value capacity");
            return new Value(data.clone());
        }
        public boolean present() { return data != null; }
        public byte[] bytes() {
            if (data == null) throw new IllegalStateException("Missing value");
            return data.clone();
        }
        int size() { return data == null ? -1 : data.length; }
        void put(java.nio.ByteBuffer out) { out.putInt(size()); if (present()) out.put(data); }
        @Override public boolean equals(Object other) {
            return other instanceof Value v && Arrays.equals(data, v.data);
        }
        @Override public int hashCode() { return Arrays.hashCode(data); }
        @Override public String toString() { return present() ? "Value[" + data.length + " bytes]" : "Missing"; }
    }

    /** Server-captured canonical participant images. This type is not a C2S packet. */
    public record Change(String resource, Value before, Value after) {
        public Change {
            if (resource == null || !RESOURCE.matcher(resource).matches())
                throw new IllegalArgumentException("Invalid participant resource");
            Objects.requireNonNull(before); Objects.requireNonNull(after);
            if (before.equals(after)) throw new IllegalArgumentException("Unchanged participant");
        }
    }

    /** Prepared images include the revision participant itself. The host verifies its exact encoding. */
    public record Intent(Request request, List<Change> changes, List<UUID> created, List<UUID> retired) {
        public Intent {
            Objects.requireNonNull(request); Objects.requireNonNull(changes);
            if (changes.isEmpty() || changes.size() > MAX_RESOURCES)
                throw new IllegalArgumentException("Participant capacity");
            var sorted = new ArrayList<>(changes); sorted.sort(Comparator.comparing(Change::resource));
            String previous = null; long size = 162; // Fixed v1 envelope, counts and checksum.
            for (Change change : sorted) {
                if (change.resource().equals(previous)) throw new IllegalArgumentException("Duplicate participant");
                previous = change.resource();
                size += 12L + previous.length() + Math.max(0, change.before().size()) + Math.max(0, change.after().size());
            }
            changes = List.copyOf(sorted); created = ids(created); retired = ids(retired);
            if (!Collections.disjoint(created, retired)) throw new IllegalArgumentException("Reused piece ID");
            size += 16L * (created.size() + retired.size());
            if (size > MAX_RECORD_BYTES) throw new IllegalArgumentException("Record capacity");
        }
        public long resultRevision() { return request.baseRevision() + 1; }
        private static List<UUID> ids(List<UUID> ids) {
            Objects.requireNonNull(ids);
            if (ids.size() > MAX_PIECES) throw new IllegalArgumentException("Piece capacity");
            var sorted = new TreeSet<UUID>();
            for (UUID id : ids) if (!sorted.add(Objects.requireNonNull(id)))
                throw new IllegalArgumentException("Duplicate piece ID");
            return List.copyOf(sorted);
        }
    }

    public enum Phase { PREPARED, COMMITTED, ABORTED, REJECTED }
    /** Stable codes only: detailed exceptions/logs never become attacker-readable receipts. */
    public enum Reason { NONE, STALE_REVISION, VALIDATION_REFUSED, PARTICIPANT_CONFLICT, APPLY_FAILED, RECOVERED }

    /** Terminal outcomes retain their original revision; aborts expose no provisional piece UUIDs. */
    public record Receipt(Request request, Phase phase, Reason reason, long revision,
                          List<UUID> created, List<UUID> retired) {
        public Receipt { created = List.copyOf(created); retired = List.copyOf(retired); }
    }

    public record Entry(Request request, Phase phase, Reason reason, Intent intent) {
        public Entry {
            Objects.requireNonNull(request); Objects.requireNonNull(phase); Objects.requireNonNull(reason);
            if (phase == Phase.REJECTED ? intent != null : intent == null || !request.equals(intent.request()))
                throw new IllegalArgumentException("Invalid transaction images");
            if ((phase == Phase.PREPARED || phase == Phase.COMMITTED) != (reason == Reason.NONE))
                throw new IllegalArgumentException("Invalid transaction reason");
        }
        public static Entry prepared(Intent intent) { return new Entry(intent.request(), Phase.PREPARED, Reason.NONE, intent); }
        public static Entry rejected(Request request, Reason reason) { return new Entry(request, Phase.REJECTED, reason, null); }
        public Entry finish(Phase next, Reason why) {
            if (phase != Phase.PREPARED || (next != Phase.COMMITTED && next != Phase.ABORTED))
                throw new IllegalStateException("Terminal decision is immutable");
            return new Entry(request, next, why, intent);
        }
        public Receipt receipt() {
            if (phase == Phase.PREPARED) throw new IllegalStateException("No decision yet");
            boolean committed = phase == Phase.COMMITTED;
            return new Receipt(request, phase, reason, committed ? intent.resultRevision() : request.baseRevision(),
                    committed ? intent.created() : List.of(), committed ? intent.retired() : List.of());
        }
    }
}
