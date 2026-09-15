package com.blockreality.core.diagnostics;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Optional, bounded duration observations. It never participates in result or world authority. */
public final class PipelineProfile {
    public static final int CAPACITY = 256;
    public enum Stage {
        MANAGER_TICK, METADATA_CAPTURE, METADATA_QUEUE, METADATA_RECONCILE, METADATA_PUBLISH,
        GATHER_PREPARE, GATHER_STEP, GATHER_FINISH, ANALYSIS_QUEUE, ANALYSIS_WORKER,
        WORLD_MAP, WORLD_ENCODE, FRAME_ENCODE, NATIVE_CALL, REPLY_COPY, FRAME_DECODE, RESULT_DECODE,
        PACKET_BUILD, APPLY, NETWORK_DISPATCH
    }
    public enum Counter { REQUEST_BYTES, REPLY_BYTES, BUFFER_GROWTH }
    public interface Span extends AutoCloseable {
        @Override void close();
        /** Counters share the span's session and are rejected after stop/restart. */
        default void add(Counter counter, long value) { }
    }
    private static final Span NONE = () -> { };
    public interface Context { }
    private static final Context OFF = new Context() { };
    private static final PipelineProfile DISABLED = new PipelineProfile(System::nanoTime, true);
    private final LongSupplier clock;
    private final boolean permanentOff;
    private final ThreadLocal<Context> inherited = new ThreadLocal<>();
    private volatile Session active;
    private Session last;
    private long sequence;

    public PipelineProfile() { this(System::nanoTime); }
    public PipelineProfile(LongSupplier clock) { this(clock, false); }
    private PipelineProfile(LongSupplier clock, boolean permanentOff) {
        this.clock = Objects.requireNonNull(clock); this.permanentOff = permanentOff;
    }
    public static PipelineProfile disabled() { return DISABLED; }
    public synchronized void start() {
        if (permanentOff) throw new IllegalStateException("disabled recorder");
        long next = Math.incrementExact(sequence);
        stop(); sequence = next; last = new Session(next); active = last;
    }
    public synchronized void stop() {
        Session old = active; active = null;
        if (old != null) synchronized (old) { old.accepting = false; }
    }
    public boolean active() { return active != null; }
    /** Capture when work is scheduled, then use on its worker and delivery callback. */
    public Context capture() {
        Context scoped = inherited.get();
        if (scoped != null) return scoped;
        Session session = active; return session == null ? OFF : session;
    }
    public void run(Context context, Runnable work) { call(context, () -> { work.run(); return null; }); }
    public <T> T call(Context context, Supplier<T> work) {
        Objects.requireNonNull(context);
        Context previous = inherited.get(); inherited.set(context);
        try { return work.get(); }
        finally { if (previous == null) inherited.remove(); else inherited.set(previous); }
    }
    public Span begin(Stage stage) {
        Context context = capture();
        if (!(context instanceof Session session) || !session.accepting) return NONE;
        long started = clock.getAsLong();
        return new Span() {
            private boolean closed;
            @Override public void close() {
                synchronized (session) {
                    if (closed) return;
                    closed = true;
                    if (!session.accepting) return;
                    long elapsed = clock.getAsLong() - started;
                    if (elapsed >= 0) session.record(stage, elapsed);
                }
            }
            @Override public void add(Counter counter, long value) {
                synchronized (session) {
                    if (!closed && session.accepting && value >= 0)
                        session.counters[counter.ordinal()] = saturatedAdd(session.counters[counter.ordinal()], value);
                }
            }
        };
    }
    public record Summary(long observed, int retained, long p50Ns, long p95Ns, long maxNs) { }
    public record Snapshot(long session, boolean active, Map<Stage, Summary> stages, Map<Counter, Long> counters) {
        public Snapshot { stages = Map.copyOf(stages); counters = Map.copyOf(counters); }
    }
    public synchronized Snapshot snapshot() {
        Session session = last;
        if (session == null) return new Snapshot(0, false, Map.of(), Map.of());
        synchronized (session) {
            var stages = new EnumMap<Stage, Summary>(Stage.class);
            for (Stage stage : Stage.values()) {
                int index = stage.ordinal(), count = (int) Math.min(CAPACITY, session.observed[index]);
                if (count == 0) continue;
                long[] values = Arrays.copyOf(session.samples[index], count); Arrays.sort(values);
                stages.put(stage, new Summary(session.observed[index], count,
                        values[(count - 1) / 2], values[(int) Math.ceil(count * .95) - 1], values[count - 1]));
            }
            var counters = new EnumMap<Counter, Long>(Counter.class);
            for (Counter counter : Counter.values()) counters.put(counter, session.counters[counter.ordinal()]);
            return new Snapshot(session.id, session.accepting, stages, counters);
        }
    }
    private static long saturatedAdd(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
    private static final class Session implements Context {
        final long id;
        volatile boolean accepting = true;
        final long[][] samples = new long[Stage.values().length][CAPACITY];
        final long[] observed = new long[Stage.values().length], counters = new long[Counter.values().length];
        final int[] cursor = new int[Stage.values().length];
        Session(long id) { this.id = id; }
        void record(Stage stage, long elapsed) {
            int index = stage.ordinal(); samples[index][cursor[index]] = elapsed;
            cursor[index] = (cursor[index] + 1) % CAPACITY;
            observed[index] = saturatedAdd(observed[index], 1);
        }
    }
}
