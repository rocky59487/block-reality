package com.blockreality.core.sidecar;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.EngineCatalogue;
import com.blockreality.api.WorldRevision;
import com.blockreality.core.protocol.ProtocolCodec;
import com.blockreality.core.protocol.SolveRequest;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The engine, as the rest of the mod sees it.
 *
 * <p>Everything about the fact that the engine is a separate operating-system process
 * stops here. Callers get an {@link AnalysisResult} or a failed one; they never see an
 * {@link IOException}, a process handle, or a restart.
 *
 * <h2>Fail-safe, not fail-open</h2>
 * Four failures are handled, and the response to all of them is the same in the one
 * respect that matters — <strong>the world does not change</strong>:
 *
 * <table>
 *   <caption>Failure handling</caption>
 *   <tr><td>binary absent</td><td>analysis disabled, mod plays normally, message shown</td></tr>
 *   <tr><td>protocol mismatch</td><td>refused outright — a version skew that silently
 *       half-works is how you get results that are wrong instead of missing</td></tr>
 *   <tr><td>crash mid-request</td><td>that request fails, process restarts with backoff</td></tr>
 *   <tr><td>wedged</td><td>timeout, kill, restart</td></tr>
 * </table>
 *
 * <p>Not thread-safe by design. One instance per dimension, driven from that dimension's
 * analysis thread. A shared instance behind a lock would serialise every dimension behind
 * the slowest one, and a global singleton is exactly the mistake the previous codebase's
 * audit flagged.
 */
public final class SidecarClient implements AutoCloseable {

    public enum Status {
        /** Not started yet. The sidecar is lazy: no process until the first analysis. */
        IDLE,
        READY,
        /** Restarting after a failure; requests fail until it is back. */
        RECOVERING,
        /** Given up for this session. Only an explicit {@link #reset()} revives it. */
        DISABLED
    }

    private final SidecarConfig config;
    private final Consumer<String> log;

    private SidecarProcess process;
    private EngineCatalogue catalogue;
    private Status status = Status.IDLE;
    private String disabledReason = "";
    private int consecutiveFailures;
    private long nextAttemptAtMs;

    public SidecarClient(SidecarConfig config, Consumer<String> log) {
        this.config = config;
        this.log = log == null ? s -> { } : log;
    }

    public Status status() { return status; }

    public String disabledReason() { return disabledReason; }

    public Optional<EngineCatalogue> catalogue() { return Optional.ofNullable(catalogue); }

    /** Clears a {@link Status#DISABLED} state, e.g. after the user installs the binary. */
    public void reset() {
        status = Status.IDLE;
        disabledReason = "";
        consecutiveFailures = 0;
        nextAttemptAtMs = 0;
    }

    // ------------------------------------------------------------------ solve
    /**
     * Runs one analysis. Never throws.
     *
     * <p>The returned result is stamped with the revision the engine answered for, and
     * {@link ProtocolCodec} has already rejected any reply that does not match the
     * request. Callers still have to check it against the world's current revision before
     * applying anything — the engine cannot know that the world moved on while it worked.
     */
    public AnalysisResult solve(SolveRequest request) {
        WorldRevision rev = request.revision();

        if (!ensureReady()) {
            return AnalysisResult.failed(rev, disabledReason.isEmpty() ? "analysis unavailable" : disabledReason);
        }

        // A reply left over from a timed-out earlier request would otherwise be read as
        // this one's. The revision check in the codec would catch it, but discarding it
        // here means one stale reply cannot fail every subsequent request.
        process.drain();

        if (!process.send(ProtocolCodec.encodeSolve(request))) {
            return failAndRestart(rev, "sidecar pipe closed while sending");
        }

        String line = process.awaitLine(config.requestTimeoutMs());
        if (line == null) {
            return failAndRestart(rev, process.alive()
                    ? "sidecar timed out after " + config.requestTimeoutMs() + " ms"
                    : "sidecar exited during analysis");
        }

        AnalysisResult result = ProtocolCodec.decodeSolve(line, rev);
        if (result.ok()) consecutiveFailures = 0;
        return result;
    }

    // ------------------------------------------------------------- lifecycle
    /** Starts and handshakes if needed. @return false if analysis is unavailable. */
    public boolean ensureReady() {
        if (status == Status.DISABLED) return false;
        if (status == Status.READY && process != null && process.alive()) return true;

        if (System.currentTimeMillis() < nextAttemptAtMs) return false;

        closeProcessQuietly();

        try {
            process = SidecarProcess.start(config.executable(), l -> log.accept("[sidecar] " + l));
        } catch (IOException e) {
            return recordStartFailure(e.getMessage());
        }

        if (!process.send(ProtocolCodec.encodeHello())) {
            return recordStartFailure("sidecar closed the pipe before the handshake");
        }
        String hello = process.awaitLine(config.requestTimeoutMs());
        if (hello == null) {
            return recordStartFailure("sidecar did not answer the handshake");
        }

        Optional<EngineCatalogue> cat = ProtocolCodec.decodeHello(hello);
        if (cat.isEmpty()) {
            return recordStartFailure("sidecar sent an unreadable handshake");
        }
        if (!cat.get().isCompatible()) {
            // Fail closed, permanently. A protocol mismatch is not transient, and
            // restarting into it four times only delays the message.
            closeProcessQuietly();
            status = Status.DISABLED;
            disabledReason = "protocol mismatch: engine speaks " + cat.get().protocol()
                    + ", this build speaks " + EngineCatalogue.SUPPORTED_PROTOCOL;
            log.accept(disabledReason);
            return false;
        }

        catalogue = cat.get();
        status = Status.READY;
        consecutiveFailures = 0;
        log.accept("sidecar ready: " + catalogue.engine() + " protocol " + catalogue.protocol()
                + ", " + catalogue.materials().size() + " materials, "
                + catalogue.sections().size() + " sections");
        return true;
    }

    private boolean recordStartFailure(String why) {
        closeProcessQuietly();
        consecutiveFailures++;
        disabledReason = why;
        if (consecutiveFailures >= config.maxRestarts()) {
            status = Status.DISABLED;
            log.accept("analysis disabled after " + consecutiveFailures + " failed starts: " + why);
        } else {
            status = Status.RECOVERING;
            long wait = config.backoffMs(consecutiveFailures - 1);
            nextAttemptAtMs = System.currentTimeMillis() + wait;
            log.accept("sidecar start failed (" + why + "), retrying in " + wait + " ms");
        }
        return false;
    }

    private AnalysisResult failAndRestart(WorldRevision rev, String why) {
        log.accept(why);
        if (process != null) process.kill();
        closeProcessQuietly();
        consecutiveFailures++;
        if (consecutiveFailures >= config.maxRestarts()) {
            status = Status.DISABLED;
            disabledReason = "analysis disabled after " + consecutiveFailures + " failures: " + why;
            log.accept(disabledReason);
        } else {
            status = Status.RECOVERING;
            nextAttemptAtMs = System.currentTimeMillis() + config.backoffMs(consecutiveFailures - 1);
        }
        return AnalysisResult.failed(rev, why);
    }

    private void closeProcessQuietly() {
        if (process != null) {
            process.close();
            process = null;
        }
        if (status != Status.DISABLED) status = Status.IDLE;
    }

    @Override
    public void close() {
        if (process != null) {
            process.send(ProtocolCodec.encodeBye());
            process.close();
            process = null;
        }
        status = Status.IDLE;
    }
}
