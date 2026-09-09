package com.blockreality.core.engine;

import com.blockreality.api.AnalysisResult;
import com.blockreality.core.bsi.BsiHeaders;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Worker-owned native session with nonblocking control messages from the server thread. */
public final class NativeGameRuntime {
    public enum Status { IDLE, LOADING, READY, DISABLED, CLOSING, CLOSED }
    public record State(Status status, String detail) { }
    public interface Session extends AutoCloseable {
        AnalysisResult analyze(GameWorldSnapshot world, Integer threads, BsiHeaders.EigenBuckling buckling);
        boolean ready();
        @Override void close();
    }
    @FunctionalInterface public interface Factory { Session open(); }

    private final Object io = new Object(), control = new Object();
    private final Factory factory;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private final long timeoutNs;
    private volatile State state;
    // Only the control monitor owns these; never held during native work.
    private long generation;
    private boolean enabled, closing;
    private Run running;
    private record Run(long generation, long started) { }
    // Only the worker io monitor owns these.
    private Session session;
    private long sessionGeneration = -1;

    public NativeGameRuntime(boolean enabled, Factory factory, long timeoutMs, Consumer<String> log) {
        this(enabled, factory, timeoutMs, log, System::nanoTime);
    }
    NativeGameRuntime(boolean enabled, Factory factory, long timeoutMs, Consumer<String> log, LongSupplier clock) {
        if (timeoutMs < 1 || timeoutMs > 120_000) throw new IllegalArgumentException("invalid native timeout");
        this.enabled = enabled; this.factory = Objects.requireNonNull(factory);
        this.timeoutNs = timeoutMs * 1_000_000; this.log = Objects.requireNonNull(log);
        this.clock = Objects.requireNonNull(clock);
        state = new State(enabled ? Status.IDLE : Status.DISABLED, enabled ? "" : "analysis is off");
    }

    public State state() { return state; }
    public boolean closed() { return state.status() == Status.CLOSING || state.status() == Status.CLOSED; }

    /** Main-thread safe. Does not close, load, wait for, or interrupt a native call. */
    public void requestReset(boolean enable) {
        synchronized (control) {
            if (closing) return;
            generation++; enabled = enable;
            state = new State(enable ? Status.IDLE : Status.DISABLED,
                    enable ? "reset queued until the active analysis returns" : "analysis is off");
        }
    }

    /** Main-thread safe; invalidates all results even if cleanup has not acquired the session yet. */
    public void requestClose() {
        synchronized (control) {
            if (closing) return;
            closing = true; generation++;
            state = new State(Status.CLOSING, "world closed");
        }
    }

    /** Called from the server tick. A timeout revokes authority; it cannot kill a native call. */
    public boolean pollTimeout() {
        synchronized (control) {
            if (running == null || closing || running.generation() != generation
                    || clock.getAsLong() - running.started() < timeoutNs) return false;
            generation++; running = null;
            state = new State(Status.DISABLED, "native analysis timed out; pending native work must return before reset");
            return true;
        }
    }

    /** Background thread only. Serializes every call and never serves a result after reset/close/timeout. */
    public AnalysisResult analyze(GameWorldSnapshot world, Integer threads, BsiHeaders.EigenBuckling buckling) {
        synchronized (io) {
            final Run run;
            synchronized (control) {
                if (closing || !enabled || state.status() == Status.DISABLED)
                    return AnalysisResult.failed(world.revision(), state.detail());
                run = new Run(generation, clock.getAsLong()); running = run;
                state = new State(session == null || sessionGeneration != generation ? Status.LOADING : Status.READY, "");
            }
            AnalysisResult result;
            boolean ready = false;
            try {
                if (sessionGeneration != run.generation()) release();
                if (session == null) { session = factory.open(); sessionGeneration = run.generation(); }
                synchronized (control) {
                    if (closing || generation != run.generation())
                        return AnalysisResult.failed(world.revision(), "analysis invalidated before solve");
                }
                result = session.analyze(world, threads, buckling);
                ready = session.ready();
            } catch (RuntimeException | LinkageError e) {
                log.accept("native analysis failed: " + e);
                result = AnalysisResult.failed(world.revision(), "native engine unavailable; operator log has the cause");
            } finally {
                synchronized (control) { if (running == run) running = null; }
                boolean invalid;
                synchronized (control) { invalid = closing || generation != run.generation(); }
                if (invalid) release();
            }
            boolean invalid;
            synchronized (control) {
                invalid = closing || generation != run.generation();
                if (!invalid) state = new State(ready ? Status.READY : Status.DISABLED,
                        ready ? "" : "native session is unavailable; reset to retry");
            }
            if (invalid || !ready) release();
            return invalid ? AnalysisResult.failed(world.revision(), "analysis invalidated by reset, timeout or closed world") : result;
        }
    }

    /** Background cleanup only. requestClose must run first, even when the analysis pool is stopping. */
    public void closeWhenIdle() {
        synchronized (io) {
            synchronized (control) { if (!closing && enabled) return; }
            release();
            synchronized (control) { if (closing) state = new State(Status.CLOSED, "world closed"); }
        }
    }

    private void release() {
        Session old = session; session = null; sessionGeneration = -1;
        if (old != null) {
            try { old.close(); }
            catch (RuntimeException | LinkageError e) { log.accept("native cleanup failed: " + e); }
        }
    }
}
