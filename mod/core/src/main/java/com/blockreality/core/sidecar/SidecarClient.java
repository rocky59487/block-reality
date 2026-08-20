package com.blockreality.core.sidecar;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.EngineCatalogue;
import com.blockreality.api.WorldRevision;
import com.blockreality.core.json.JsonValue;
import com.blockreality.core.protocol.BinaryCodec;
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
 * Failures are sorted into kinds, and the response to all of them is the same in the one
 * respect that matters — <strong>the world does not change</strong>:
 *
 * <table>
 *   <caption>Failure handling</caption>
 *   <tr><td>binary absent</td><td>analysis disabled, mod plays normally, message shown</td></tr>
 *   <tr><td>protocol mismatch</td><td>refused outright — a version skew that silently
 *       half-works is how you get results that are wrong instead of missing</td></tr>
 *   <tr><td>engine refusal</td><td>that request fails, conversation continues — a
 *       refused solve is an <em>answer</em>, not a transport problem</td></tr>
 *   <tr><td>crash mid-request</td><td>that request fails, process restarts with backoff</td></tr>
 *   <tr><td>protocol desync</td><td>kill and restart with backoff — once the two sides
 *       disagree about which reply answers which request, every later reply is suspect</td></tr>
 *   <tr><td>wedged</td><td>timeout, kill, restart</td></tr>
 * </table>
 *
 * <p>The refusal/desync split (#33) is what keeps a malformed <em>world</em> from
 * burning restart budget: an engine that answers "this model cannot be solved" a
 * thousand times in a row is healthy, and restarting it would turn a player's odd
 * build into a disabled engine. Desyncs and transport failures, by contrast, all go
 * through one backoff path whose counter is capped — so a sidecar that desyncs on
 * every request converges to DISABLED instead of a restart storm.
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
    private ShmRegion shm;
    private Status status = Status.IDLE;
    private String disabledReason = "";
    private int consecutiveFailures;
    private long nextAttemptAtMs;

    /** Initial shared region: comfortably above a building-scale reply (~0.5 MB). */
    private static final long SHM_INITIAL_BYTES = 4L << 20;
    private static final long SHM_MAX_BYTES = 256L << 20;

    public SidecarClient(SidecarConfig config, Consumer<String> log) {
        this.config = config;
        this.log = log == null ? s -> { } : log;
    }

    public Status status() { return status; }

    /** Which wire the next solve will use: {@code "shm"} or {@code "json"}. */
    public String transport() { return shm != null ? "shm" : "json"; }

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
     * a reply that cannot be matched to the request — wrong revision, unreadable line,
     * broken frame — never becomes a result at all; it restarts the conversation.
     * Callers still have to check the result against the world's current revision
     * before applying anything — the engine cannot know that the world moved on while
     * it worked.
     */
    public AnalysisResult solve(SolveRequest request) {
        WorldRevision rev = request.revision();

        if (!ensureReady()) {
            return AnalysisResult.failed(rev, disabledReason.isEmpty() ? "analysis unavailable" : disabledReason);
        }

        // A reply left over from a timed-out earlier request would otherwise be read as
        // this one's. The revision check in the triage would catch it, but discarding it
        // here means one stale reply cannot fail every subsequent request.
        process.drain();

        // The shared-memory wire when both ends speak it; JSON otherwise. A TRANSPORT
        // failure of the region itself (cannot be created, grown or opened) downgrades
        // to JSON and keeps playing — the JSON path is the contract, shm is the
        // optimisation, and losing it may cost speed but never an analysis (#32).
        if (shm != null) {
            AnalysisResult viaShm = solveViaShm(request);
            if (viaShm != null) return viaShm;
        }
        return solveViaJson(request);
    }

    // ---------------------------------------------------------------- triage
    /**
     * The three kinds of reply line (#33). What separates them is whether the
     * conversation can be trusted afterwards:
     *
     * <ul>
     *   <li>{@link #REFUSAL} — a well-formed "this model cannot be solved" for the right
     *       revision. That is an <em>answer</em>; the next request proceeds normally.
     *   <li>{@link #DESYNC} — the line cannot be matched to the request (malformed, wrong
     *       revision, off-schema). Once "which reply is this?" has no answer, every later
     *       reply on this pipe is suspect, so the conversation must end.
     *   <li>{@link #OK} — a well-formed success doorbell for the right revision; the
     *       payload still has to survive its own strict decode.
     * </ul>
     */
    private enum Verdict { OK, REFUSAL, DESYNC }

    private record Triage(Verdict verdict, String detail, JsonValue value) { }

    private Triage triage(String line, WorldRevision rev) {
        JsonValue v = JsonValue.parse(line);
        if (!v.isObject()) {
            return new Triage(Verdict.DESYNC, "malformed reply line", v);
        }
        // Exact-integer on purpose: revision is the field whose whole job is telling
        // adjacent values apart, and a double reading folds values above 2^53.
        if (!v.isExactInt("revision")) {
            return new Triage(Verdict.DESYNC, "reply carries no plain-integer revision", v);
        }
        long got = v.exactI64("revision");
        if (got != rev.value()) {
            return new Triage(Verdict.DESYNC,
                    "revision mismatch: asked for " + rev.value() + ", got " + got, v);
        }
        if (!v.isBool("ok")) {
            return new Triage(Verdict.DESYNC, "reply carries no boolean ok", v);
        }
        if (!v.bool("ok", false)) {
            // A refusal without a reason is not something our peer produces; treating
            // it as a refusal anyway would hide a desync behind an empty message.
            if (!v.isStr("error")) {
                return new Triage(Verdict.DESYNC, "refusal without an error message", v);
            }
            return new Triage(Verdict.REFUSAL, v.str("error", ""), v);
        }
        return new Triage(Verdict.OK, "", v);
    }

    // ------------------------------------------------------------- transports
    private AnalysisResult solveViaJson(SolveRequest request) {
        WorldRevision rev = request.revision();

        if (!process.send(ProtocolCodec.encodeSolve(request))) {
            return failAndRestart(rev, "sidecar pipe closed while sending");
        }
        String line = process.awaitLine(config.requestTimeoutMs());
        if (line == null) {
            return failAndRestart(rev, process.alive()
                    ? "sidecar timed out after " + config.requestTimeoutMs() + " ms"
                    : "sidecar exited during analysis");
        }

        Triage t = triage(line, rev);
        return switch (t.verdict()) {
            case DESYNC -> failAndRestart(rev, "protocol desync: " + t.detail());
            // No restart and no failure count: the engine is healthy, the model is not,
            // and burning restart budget on a player's odd build would converge on
            // DISABLED for an engine that answered every question it was asked.
            case REFUSAL -> AnalysisResult.failed(rev, t.detail());
            case OK -> {
                AnalysisResult result = ProtocolCodec.decodeSolve(line, rev);
                if (!result.ok()) {
                    // The line said ok and then failed the strict schema: the two ends
                    // disagree about what a reply looks like, which is a desync, not a
                    // solve failure.
                    yield failAndRestart(rev, "protocol desync: " + result.diagnostic());
                }
                consecutiveFailures = 0;
                yield result;
            }
        };
    }

    /**
     * One solve over the shared region. Returns {@code null} only when the shm
     * transport itself is unusable — the caller then falls through to JSON, so a shm
     * problem costs the speed of the fast path, never the analysis (#32).
     *
     * <p><strong>Idempotency caveat:</strong> the grow-and-resend loop below re-sends
     * the same request after a region overflow. That is safe solely because solve is a
     * pure function of the request — asking twice cannot change the world. If a verb
     * with side effects is ever added to this protocol, it must NOT inherit this
     * automatic resend.
     */
    private AnalysisResult solveViaShm(SolveRequest request) {
        WorldRevision rev = request.revision();

        long need = BinaryCodec.requestBytes(request);
        if (need > shm.size() && !reopenShm(Math.max(need * 2, shm.size() * 2))) {
            return null;
        }

        while (true) {
            int wrote = BinaryCodec.encodeSolve(request, catalogue, shm.buffer());
            if (wrote < 0) {
                // A token the catalogue does not list cannot travel as an index. Let
                // the JSON path answer; its fail-closed validation names the token.
                return null;
            }
            // The doorbell carries the request's exact length; the sidecar reads that
            // many bytes and no more, so a stale longer frame cannot lend its tail to
            // a truncated new one (#27).
            if (!process.send(ProtocolCodec.encodeSolveShm(rev.value(), wrote))) {
                return failAndRestart(rev, "sidecar pipe closed while sending");
            }
            String line = process.awaitLine(config.requestTimeoutMs());
            if (line == null) {
                return failAndRestart(rev, process.alive()
                        ? "sidecar timed out after " + config.requestTimeoutMs() + " ms"
                        : "sidecar exited during analysis");
            }

            Triage t = triage(line, rev);
            if (t.verdict() == Verdict.DESYNC) {
                return failAndRestart(rev, "protocol desync: " + t.detail());
            }
            if (t.verdict() == Verdict.REFUSAL) {
                // The one transport-shaped refusal on this path: the reply outgrew the
                // region. Grow and resend until it fits; when the ceiling or the
                // filesystem says no, fall back to JSON rather than reporting failure —
                // a reply too big for the fast path is not a reply too big to have.
                if (t.detail().contains("grow")) {
                    if (!reopenShm(shm.size() * 2)) {
                        closeShm();
                        return null;
                    }
                    continue;
                }
                // Same reasoning as the JSON path: a refusal is an answer, and it is
                // deliberately NOT retried over JSON — the engine refuses identically
                // on both transports, so the retry would only refuse again, slower.
                return AnalysisResult.failed(rev, t.detail());
            }

            // A success doorbell must say how long the reply frame is; without that
            // length the frame boundary would be "wherever the reads stop", which is
            // exactly the stale-tail reading the byte count exists to prevent.
            JsonValue bell = t.value();
            if (!bell.isExactInt("bytes")) {
                return failAndRestart(rev, "protocol desync: doorbell carries no byte count");
            }
            long bytes = bell.exactI64("bytes");
            if (bytes <= 0 || bytes > shm.size()) {
                return failAndRestart(rev,
                        "protocol desync: doorbell byte count " + bytes + " out of range");
            }
            AnalysisResult result = BinaryCodec.decodeSolve(shm.buffer(), (int) bytes, rev, catalogue);
            if (!result.ok()) {
                // The doorbell said ok but the region does not decode: bad magic, wrong
                // revision, truncated frame, off-schema content. The two sides no
                // longer agree about the region's contents, and the region itself is
                // discarded with the process (closeProcessQuietly closes it) so the
                // next conversation starts with a fresh mapping.
                return failAndRestart(rev, "protocol desync: " + result.diagnostic());
            }
            consecutiveFailures = 0;
            return result;
        }
    }

    /** Creates (or replaces) the region and asks the sidecar to map it. */
    private boolean reopenShm(long bytes) {
        closeShm();
        if (bytes > SHM_MAX_BYTES) {
            log.accept("shm region would exceed " + SHM_MAX_BYTES + " bytes; staying on JSON");
            return false;
        }
        ShmRegion next;
        try {
            next = ShmRegion.create(bytes);
        } catch (IOException e) {
            log.accept("shm region unavailable (" + e.getMessage() + "); staying on JSON");
            return false;
        }
        if (!process.send(ProtocolCodec.encodeShmOpen(next.path().toAbsolutePath().toString()))) {
            next.close();
            return false;
        }
        String line = process.awaitLine(config.requestTimeoutMs());
        JsonValue v = line == null ? JsonValue.parse("") : JsonValue.parse(line);
        if (!v.isObject() || !v.bool("ok", false)) {
            log.accept("sidecar could not map the shm region ("
                    + (v.isObject() ? v.str("error", "no detail") : "no reply") + "); staying on JSON");
            next.close();
            return false;
        }
        shm = next;
        return true;
    }

    private void closeShm() {
        if (shm != null) {
            shm.close();
            shm = null;
        }
    }

    /**
     * Drops the shared region so the next solve runs over JSON. Test hook: the
     * transport-equivalence test needs the SAME client to answer the SAME request on
     * both wires, and the transport is otherwise chosen automatically.
     */
    void forceJsonTransportForTest() { closeShm(); }

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
        // Zero-copy transport when the engine offers the layout this build decodes.
        // Failure here costs nothing but the optimisation: JSON remains the contract.
        if (catalogue.supportsShm()) {
            reopenShm(SHM_INITIAL_BYTES);
        }
        log.accept("sidecar ready: " + catalogue.engine() + " protocol " + catalogue.protocol()
                + ", " + catalogue.materials().size() + " materials, "
                + catalogue.sections().size() + " sections, transport " + transport());
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

    /**
     * Transport failures and protocol desyncs both land here: kill the process, count
     * the failure, back off, and disable after the cap. The shared region is discarded
     * with the process ({@link #closeProcessQuietly} closes it), so a desync can never
     * leave a poisoned mapping behind for the next conversation to read.
     */
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
        closeShm();
        if (process != null) {
            process.close();
            process = null;
        }
        if (status != Status.DISABLED) status = Status.IDLE;
    }

    @Override
    public void close() {
        closeShm();
        if (process != null) {
            process.send(ProtocolCodec.encodeBye());
            process.close();
            process = null;
        }
        status = Status.IDLE;
    }
}
