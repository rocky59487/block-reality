package com.blockreality.core.engine;

import com.blockreality.core.diagnostics.PipelineProfile;
import static com.blockreality.core.diagnostics.PipelineProfile.Stage.*;

import com.blockreality.core.bsi.BsiContract;
import com.blockreality.core.bsi.BsiFrame;
import com.blockreality.core.bsi.BsiHeaders;
import com.blockreality.core.bsi.BsiRecords;
import com.blockreality.core.bsi.BsiResponse;
import com.blockreality.core.bsi.BsiAnalysisResult;
import com.blockreality.core.bsi.BsiVocabulary;
import com.blockreality.core.json.JsonValue;
import com.blockreality.api.AnalysisResult;
import com.blockreality.api.WorldRevision;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    private boolean worldDeclared;
    private BsiVocabulary vocabulary;

    private final PipelineProfile profile;
    private InProcessEngine(BsiNative n, PipelineProfile profile) { this.native_ = n; this.profile = profile; }

    /** Load the library and complete the handshake. Never throws for a refusal: ask {@link #status()}. */
    public static InProcessEngine open(Path library, int numThreads) {
        return open(library, numThreads, PipelineProfile.disabled());
    }

    public static InProcessEngine open(Path library, int numThreads, PipelineProfile profile) {
        BsiNative n;
        try {
            n = BsiNative.open(library, openOptions(numThreads), profile);
        } catch (BsiNative.EngineRefused e) {
            InProcessEngine dead = new InProcessEngine(null, profile);
            dead.disable("ENGINE_LOAD", e.getMessage());
            return dead;
        }
        InProcessEngine eng = new InProcessEngine(n, profile);
        eng.hello();
        return eng;
    }

    /**
     * The open options this mod sends. The contract bounds {@code numThreads} to 1..256 and
     * an out-of-range or unknown key now fails the open outright (BSI_ADD1 G-D/G-E) — before
     * that batch every key here was accepted and none of them did anything.
     *
     * <p>"Let the engine decide" is spelled by leaving the key out, not by sending 0: the old
     * {@code Math.max(1, n)} turned "no preference" into "exactly one thread", which is a
     * different request.
     */
    static String openOptions(int numThreads) {
        if (numThreads <= 0) return "{\"log\":0}";
        return "{\"log\":0,\"numThreads\":" + Math.min(256, numThreads) + "}";
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
        worldDeclared = false;
        vocabulary = null;
        if (status != Status.READY) return false;
        String id = nextId();
        BsiResponse r = send(BsiHeaders.vocabDeclare(id, revision, vocabBodyJson), null);
        if (!ok(r)) return false;
        try {
            var body = JsonValue.parse(vocabBodyJson);
            if (!body.isExactInt("version") || body.exactI64("version") < 1 || body.exactI64("version") > Integer.MAX_VALUE)
                throw new IllegalArgumentException("invalid declared vocabulary version");
            vocabulary = BsiVocabulary.decode(r, id, revision, (int) body.exactI64("version"));
            return true;
        } catch (IllegalArgumentException e) {
            disable("PROTOCOL_ERROR", e.getMessage());
            return false;
        }
    }

    public BsiVocabulary vocabulary() { return vocabulary; }

    public boolean declareWorld(long worldRevision, List<BsiRecords.Block> blocks) {
        worldDeclared = false;
        if (status != Status.READY || vocabulary == null) return false;
        this.revision = worldRevision;
        byte[] payload;
        try (var ignored = profile.begin(WORLD_ENCODE)) { payload = BsiRecords.encodeBlocks(blocks); }
        BsiResponse r = send(BsiHeaders.worldDeclare(nextId(), revision, payload.length / BsiRecords.BLOCK_BYTES, 0), payload);
        worldDeclared = ok(r);
        return worldDeclared;
    }

    /** Complete commit analysis for the declared world; never falls back to a previous result. */
    public AnalysisResult analyze(GameWorldSnapshot snapshot, Integer numThreads,
            BsiHeaders.Storage storage, BsiHeaders.EigenBuckling buckling) {
        if (status != Status.READY || vocabulary == null)
            return AnalysisResult.failed(snapshot.revision(), "BSI analysis: no accepted vocabulary");
        try {
            List<BsiRecords.Block> blocks;
            try (var ignored = profile.begin(WORLD_MAP)) { blocks = snapshot.blocks(vocabulary); }
            if (!declareWorld(snapshot.revision().value(), blocks))
                return AnalysisResult.failed(snapshot.revision(), "BSI world declaration refused");
            return analyze(snapshot.revision(), true, new double[]{0, -9.81, 0}, snapshot.loads(), numThreads,
                    vocabulary.materials(), vocabulary.sections(), storage, buckling, BsiHeaders.MassModel.PHYSICAL);
        } catch (IllegalArgumentException e) {
            worldDeclared = false;
            return AnalysisResult.failed(snapshot.revision(), "BSI input: " + e.getMessage());
        }
    }

    /** Complete commit analysis for the declared world; never falls back to a previous result. */
    public AnalysisResult analyze(WorldRevision expected, boolean selfWeight, double[] gravity,
            List<BsiRecords.Load> loads, Integer numThreads, Map<Integer,String> materials,
            Map<Integer,String> sections, BsiHeaders.Storage storage) {
        return analyze(expected, selfWeight, gravity, loads, numThreads, materials, sections, storage, null);
    }

    /** Opt-in same-solve eigen analysis; existing calls keep buckling disabled. */
    public AnalysisResult analyze(WorldRevision expected, boolean selfWeight, double[] gravity,
            List<BsiRecords.Load> loads, Integer numThreads, Map<Integer,String> materials,
            Map<Integer,String> sections, BsiHeaders.Storage storage, BsiHeaders.EigenBuckling buckling) {
        return analyze(expected, selfWeight, gravity, loads, numThreads, materials, sections, storage, buckling, null);
    }

    public AnalysisResult analyze(WorldRevision expected, boolean selfWeight, double[] gravity,
            List<BsiRecords.Load> loads, Integer numThreads, Map<Integer,String> materials,
            Map<Integer,String> sections, BsiHeaders.Storage storage, BsiHeaders.EigenBuckling buckling,
            BsiHeaders.MassModel massModel) {
        if (status != Status.READY || !worldDeclared || expected.value() != revision)
            return AnalysisResult.failed(expected, "BSI analysis: no matching declared world");
        var precision = new BsiHeaders.Precision(BsiHeaders.Tier.COMMIT, storage);
        var response = solve(selfWeight, gravity, loads, numThreads, BsiAnalysisResult.INCLUDE, precision, buckling, massModel);
        try (var ignored = profile.begin(RESULT_DECODE)) {
            return BsiAnalysisResult.decode(response, expected, materials, sections, precision);
        }
    }

    /** One solve. Returns the reply (which may be an error frame) or null when the engine is off. */
    public BsiResponse solve(boolean selfWeight, double[] gravity, List<BsiRecords.Load> loads,
                             Integer numThreads, List<String> include) {
        return solve(selfWeight, gravity, loads, numThreads, include, null);
    }

    /** Explicit storage/tier request; the engine's capability gate owns any refusal. */
    public BsiResponse solve(boolean selfWeight, double[] gravity, List<BsiRecords.Load> loads,
                             Integer numThreads, List<String> include, BsiHeaders.Precision precision) {
        return solve(selfWeight, gravity, loads, numThreads, include, precision, null);
    }

    public BsiResponse solve(boolean selfWeight, double[] gravity, List<BsiRecords.Load> loads,
                             Integer numThreads, List<String> include, BsiHeaders.Precision precision,
                             BsiHeaders.EigenBuckling buckling) {
        return solve(selfWeight, gravity, loads, numThreads, include, precision, buckling, null);
    }

    public BsiResponse solve(boolean selfWeight, double[] gravity, List<BsiRecords.Load> loads,
                             Integer numThreads, List<String> include, BsiHeaders.Precision precision,
                             BsiHeaders.EigenBuckling buckling, BsiHeaders.MassModel massModel) {
        if (status != Status.READY || !worldDeclared || vocabulary == null) return null;
        byte[] payload = loads == null || loads.isEmpty() ? null : BsiRecords.encodeLoads(loads);
        int n = payload == null ? 0 : payload.length / BsiRecords.LOAD_BYTES;
        return send(BsiHeaders.solve(nextId(), revision, selfWeight, gravity, n, numThreads, include, precision, buckling, massModel), payload);
    }

    private boolean ok(BsiResponse r) {
        if (r == null) { disable("PROTOCOL_ERROR", "the engine stopped answering"); return false; }
        if (r.isError()) { if ("BSI_VERSION".equals(r.code())) disable(r.code(), r.message()); return false; }
        return true;
    }

    private BsiResponse send(String header, byte[] payload) {
        try {
            byte[] request;
            try (var ignored = profile.begin(FRAME_ENCODE)) { request = BsiFrame.encode(header, payload); }
            byte[] reply = native_.call(request);
            if (reply == null) return null;
            try (var ignored = profile.begin(FRAME_DECODE)) {
                return BsiResponse.of(BsiFrame.decode(reply, reply.length));
            }
        } catch (BsiNative.EngineRefused e) {
            disable("ENGINE_FAILED", e.getMessage());
            return null;
        } catch (IllegalArgumentException e) {
            disable("PROTOCOL_ERROR", e.getMessage());
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
