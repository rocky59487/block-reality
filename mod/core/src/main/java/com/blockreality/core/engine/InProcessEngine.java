package com.blockreality.core.engine;

import com.blockreality.core.bsi.BsiContract;
import com.blockreality.core.bsi.BsiFrame;
import com.blockreality.core.bsi.BsiHeaders;
import com.blockreality.core.bsi.BsiRecords;
import com.blockreality.core.bsi.BsiResponse;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One BSI session over an in-process engine: hello → vocab → world → solve.
 *
 * <p>The state machine mirrors {@code SidecarClient}'s, minus everything that only made sense for
 * a subprocess. There is no restart and no backoff here, and that is not an omission: a library
 * that has failed is not a process that can be respawned (D-044's cost). What replaces those is
 * refusal — an unknown ABI, a foreign contract hash or a broken session disables the engine with
 * a sentence the HUD can show, and the analysis simply does not run.
 *
 * <p>Calls are serialised by {@link BsiNative}. This class adds no thread of its own; the caller
 * (the analysis executor) owns the thread.
 */
public final class InProcessEngine implements AutoCloseable {

    public enum Status { NEW, READY, DISABLED, CLOSED }

    /** Why the engine is off, in words a player can be shown. Empty while it is on. */
    public record Disabled(String code, String detail) {}

    private final BsiNative native_;
    private final AtomicLong requestId = new AtomicLong();
    private Status status = Status.NEW;
    private Disabled disabled;
    private String engineName = "", engineVersion = "";
    private List<String> capabilities = List.of();
    private long revision;

    private InProcessEngine(BsiNative n) { this.native_ = n; }

    /** Load the library and complete the handshake. Never throws for a refusal: ask {@link #status()}. */
    public static InProcessEngine open(Path library, int numThreads) {
        BsiNative n;
        try {
            n = BsiNative.open(library, "{\"log\":0,\"numThreads\":" + Math.max(1, numThreads) + "}");
        } catch (BsiNative.EngineRefused e) {
            InProcessEngine dead = new InProcessEngine(null);
            dead.disable("ENGINE_LOAD", e.getMessage());
            return dead;
        }
        InProcessEngine eng = new InProcessEngine(n);
        eng.hello();
        return eng;
    }

    private void hello() {
        if (!BsiContract.available()) {
            disable("BSI_VERSION", "this build carries no contract hash: it cannot state which interface it speaks");
            return;
        }
        BsiResponse r = send(BsiHeaders.hello(nextId(), revision, "block-reality/0.4.0", BsiContract.sha256(), 256L << 20), null);
        if (r == null) { disable("PROTOCOL_ERROR", "the engine did not answer the handshake"); return; }
        if (r.isError()) {
            // The one refusal that matters most: the engine was built against a different contract.
            // Naming both hashes is what turns a silent numeric disagreement into a fixable report.
            String detail = "BSI_VERSION".equals(r.code())
                    ? "engine contract != mod contract " + BsiContract.sha256() + " (" + r.message() + ")"
                    : r.message();
            disable(r.code(), detail);
            return;
        }
        engineName = r.header().str("engine", "");
        engineVersion = r.header().str("version", "");
        capabilities = r.header().arr("capabilities").stream().map(v -> v.asStr("")).toList();
        status = Status.READY;
    }

    /** The vocabulary body, exactly as {@code bsi.vocab.declare} wants it. */
    public boolean declareVocabulary(String vocabBodyJson) {
        if (status != Status.READY) return false;
        BsiResponse r = send(BsiHeaders.vocabDeclare(nextId(), revision, vocabBodyJson), null);
        return ok(r);
    }

    public boolean declareWorld(long worldRevision, List<BsiRecords.Block> blocks) {
        if (status != Status.READY) return false;
        this.revision = worldRevision;
        byte[] payload = BsiRecords.encodeBlocks(blocks);
        BsiResponse r = send(BsiHeaders.worldDeclare(nextId(), revision, payload.length / BsiRecords.BLOCK_BYTES, 0), payload);
        return ok(r);
    }

    /** One solve. Returns the reply (which may be an error frame) or null when the engine is off. */
    public BsiResponse solve(boolean selfWeight, double[] gravity, List<BsiRecords.Load> loads,
                             Integer numThreads, List<String> include) {
        if (status != Status.READY) return null;
        byte[] payload = loads == null || loads.isEmpty() ? null : BsiRecords.encodeLoads(loads);
        int n = payload == null ? 0 : payload.length / BsiRecords.LOAD_BYTES;
        return send(BsiHeaders.solve(nextId(), revision, selfWeight, gravity, n, numThreads, include), payload);
    }

    private boolean ok(BsiResponse r) {
        if (r == null) { disable("PROTOCOL_ERROR", "the engine stopped answering"); return false; }
        if (r.isError()) { if ("BSI_VERSION".equals(r.code())) disable(r.code(), r.message()); return false; }
        return true;
    }

    private BsiResponse send(String header, byte[] payload) {
        try {
            byte[] reply = native_.call(BsiFrame.encode(header, payload));
            if (reply == null) return null;
            return BsiResponse.of(BsiFrame.decode(reply, reply.length));
        } catch (BsiNative.EngineRefused e) {
            disable("ENGINE_FAILED", e.getMessage());
            return null;
        }
    }

    private void disable(String code, String detail) {
        status = Status.DISABLED;
        disabled = new Disabled(code, detail == null ? "" : detail);
    }

    private String nextId() { return "r" + requestId.incrementAndGet(); }

    public Status status() { return status; }
    public Disabled disabledReason() { return disabled; }
    public String engineName() { return engineName; }
    public String engineVersion() { return engineVersion; }
    public List<String> capabilities() { return capabilities; }
    /** True when the engine declares a capability; the consumer must not assume one it did not. */
    public boolean has(String capability) { return capabilities.contains(capability); }

    @Override
    public void close() {
        if (native_ != null) native_.close();
        status = Status.CLOSED;
    }
}
