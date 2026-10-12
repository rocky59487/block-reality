package com.blockreality.core.bsi;

import com.blockreality.core.json.JsonValue;
import com.blockreality.core.json.JsonWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ABI7/ABI8/ABI10 finite wire (contract {@code bsi_corot.h}, {@code bsi_corot_wire.h},
 * {@code bsi_corot_checkpoint.h} and the schema {@code corot.*} bodies).
 *
 * <p>Java never computes mechanics here: it encodes exactly what the schema names, decodes
 * exactly what the schema returns, and refuses anything else. The ordered input payload for
 * {@code bsi.corot.solve} is {@code materials152, cellLoads64, prescribed64, loads144} —
 * optionally extended by {@code shellMaterials56, shellLayers16} — and the durable
 * {@code corot.options} is the contract's 176-byte typed record, decoded field-by-field
 * into the schema body. Checkpoint bodies carry the engine's opaque blob untouched.
 */
public final class BsiCorot {

    public static final int OPTIONS_BYTES = 176, MATERIAL_BYTES = 152, PRESCRIBED_BYTES = 64,
            LOAD_BYTES = 144, ISLAND_BYTES = 96, SHELL_LAW_BYTES = 56, SHELL_LAYER_BYTES = 16;

    private BsiCorot() { }

    // ---------------------------------------------------------------- options (176B typed record → schema body)

    /** Decoded {@code bsi_corot_options}; the wire body mirrors every field. */
    public record Options(UUID expectedDomain, long expectedRevision, double loadFactor,
                          double[] gravity, int flags, double relativeTolerance, double forceTolerance,
                          double momentTolerance, double initialStep, double minStep, double maxStep,
                          double linearTolerance, double pathTolerance, int budgetDof, int numThreads,
                          int maxIterations, int maxAttempts, int maxBacktracks, int linearIterations,
                          int restart, int pointBudget, int fiberCells, int radialCells, int angularCells) {
        public boolean selfWeight() { return (flags & 1) != 0; }
        public boolean followerLocal() { return (flags & 2) != 0; }
        public boolean retainLoadPath() { return (flags & 4) != 0; }
        public boolean transferTopology() { return (flags & 8) != 0; }
        public boolean useMetis() { return (flags & 16) != 0; }
        public boolean reinstallRetired() { return (flags & 32) != 0; }
    }

    /** Read the durable 176-byte options record; struct size and trailing padding are checked. */
    public static Options options(byte[] record) {
        if (record == null || record.length != OPTIONS_BYTES) throw invalid("invalid corot options length");
        var b = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != OPTIONS_BYTES || b.getInt(172) != 0) throw invalid("invalid corot options header");
        int flags = b.getInt(4);
        if (flags < 0 || flags > 63) throw invalid("invalid corot options flags");
        var uuid = new UUID(b.getLong(8), b.getLong(16));
        long revision = b.getLong(24);
        double loadFactor = b.getDouble(32);
        var gravity = new double[]{b.getDouble(40), b.getDouble(48), b.getDouble(56)};
        var o = new Options(uuid, revision, loadFactor, gravity, flags,
                b.getDouble(64), b.getDouble(72), b.getDouble(80),
                b.getDouble(88), b.getDouble(96), b.getDouble(104), b.getDouble(112), b.getDouble(120),
                b.getInt(128), b.getInt(132), b.getInt(136), b.getInt(140), b.getInt(144),
                b.getInt(148), b.getInt(152), b.getInt(156), b.getInt(160), b.getInt(164), b.getInt(168));
        if (o.expectedDomain().equals(new UUID(0, 0)) || o.expectedRevision() < 0) throw invalid("invalid corot options stamp");
        anyFinite(o.loadFactor);
        for (double g : gravity) anyFinite(g);          // gravity is signed — down is negative
        finite(o.relativeTolerance()); finite(o.forceTolerance()); finite(o.momentTolerance());
        finite(o.initialStep()); finite(o.minStep()); finite(o.maxStep());
        finite(o.linearTolerance()); finite(o.pathTolerance());
        return o;
    }

    /** Unsigned finite — tolerances and step bounds may never be negative or non-finite. */
    private static void finite(double v) { if (!Double.isFinite(v) || v < 0) throw invalid("invalid corot options number"); }
    /** Signed finite — physical vectors carry direction. */
    private static void anyFinite(double v) { if (!Double.isFinite(v)) throw invalid("invalid corot options number"); }

    private static void options(JsonWriter w, Options o) {
        w.key("options").beginObj().key("expected").beginObj()
                .kv("domain", BsiFracture.hex(o.expectedDomain())).kv("revision", o.expectedRevision()).endObj()
                .kv("loadFactor", o.loadFactor())
                .key("gravity").beginArr().val(o.gravity()[0]).val(o.gravity()[1]).val(o.gravity()[2]).endArr()
                .kv("selfWeight", o.selfWeight()).kv("followerLocal", o.followerLocal())
                .kv("retainLoadPath", o.retainLoadPath()).kv("transferTopology", o.transferTopology())
                .kv("useMetis", o.useMetis())
                .kv("relativeTolerance", o.relativeTolerance()).kv("forceTolerance", o.forceTolerance())
                .kv("momentTolerance", o.momentTolerance())
                .kv("initialStep", o.initialStep()).kv("minStep", o.minStep()).kv("maxStep", o.maxStep())
                .kv("linearTolerance", o.linearTolerance()).kv("pathTolerance", o.pathTolerance())
                .kv("maxIterations", o.maxIterations()).kv("maxAttempts", o.maxAttempts())
                .kv("maxBacktracks", o.maxBacktracks()).kv("linearIterations", o.linearIterations())
                .kv("budgetDof", o.budgetDof()).kv("restart", o.restart())
                .kv("pointBudget", o.pointBudget()).kv("fiberCells", o.fiberCells())
                .kv("radialCells", o.radialCells()).kv("angularCells", o.angularCells());
        if (o.reinstallRetired()) w.kv("reinstallRetired", true);
        if (o.numThreads() > 0) w.kv("numThreads", o.numThreads());
        w.endObj();
    }

    // ---------------------------------------------------------------- requests

    /**
     * {@code bsi.corot.solve}: counts are the schema-declared record counts of the ordered
     * payload groups; shell counts must both be zero or both nonzero (ABI10).
     */
    public static String solve(String id, long revision, byte[] optionsRecord,
                               int materials, int cellLoads, int prescribed, int loads,
                               int shellMaterials, int shellLayers) {
        var o = options(optionsRecord);
        counts(materials, cellLoads, prescribed, loads, shellMaterials, shellLayers);
        var w = BsiHeadersBase(id, "bsi.corot.solve", revision).key("body").beginObj();
        options(w, o);
        w.kv("materials", materials).kv("cellLoads", cellLoads).kv("prescribed", prescribed).kv("loads", loads);
        if (shellMaterials != 0 || shellLayers != 0) w.kv("shellMaterials", shellMaterials).kv("shellLayers", shellLayers);
        return w.endObj().endObj().done();
    }

    private static void counts(int materials, int cellLoads, int prescribed, int loads, int shellMaterials, int shellLayers) {
        if (materials < 1 || cellLoads < 0 || prescribed < 0 || loads < 0 || shellMaterials < 0 || shellLayers < 0
                || (shellMaterials == 0) != (shellLayers == 0)) throw invalid("invalid corot input counts");
    }

    /** {@code bsi.corot.checkpoint.export}: budgets bound the engine's decode/encode work. */
    public static String checkpointExport(String id, long revision, UUID domain, long expected,
                                          int format, long byteBudget, long allocationBudget, long elementBudget) {
        checkpointBody(domain, expected, format, byteBudget, allocationBudget, elementBudget);
        var w = BsiHeadersBase(id, "bsi.corot.checkpoint.export", revision).key("body").beginObj();
        stamp(w, domain, expected);
        w.kv("format", format).kv("byteBudget", byteBudget)
                .kv("allocationBudget", allocationBudget).kv("elementBudget", elementBudget);
        return w.endObj().endObj().done();
    }

    /** {@code bsi.corot.checkpoint.import}: the complete opaque blob rides as the frame payload. */
    public static String checkpointImport(String id, long revision, UUID domain, long expected,
                                          int format, long byteBudget, long allocationBudget, long elementBudget,
                                          long bytes) {
        checkpointBody(domain, expected, format, byteBudget, allocationBudget, elementBudget);
        if (bytes < 32 || bytes > 268431360) throw invalid("invalid checkpoint byte count");
        var w = BsiHeadersBase(id, "bsi.corot.checkpoint.import", revision).key("body").beginObj();
        stamp(w, domain, expected);
        w.kv("format", format).kv("byteBudget", byteBudget)
                .kv("allocationBudget", allocationBudget).kv("elementBudget", elementBudget).kv("bytes", bytes);
        return w.endObj().endObj().done();
    }

    private static void checkpointBody(UUID domain, long expected, int format,
                                       long byteBudget, long allocationBudget, long elementBudget) {
        if (domain == null || domain.equals(new UUID(0, 0)) || expected < 0) throw invalid("invalid checkpoint stamp");
        if (format < 1 || format > 3) throw invalid("invalid checkpoint format");
        if (byteBudget < 32 || byteBudget > 268431360 || allocationBudget < 1 || allocationBudget > 536870912
                || elementBudget < 1 || elementBudget > 16777216) throw invalid("invalid checkpoint budget");
    }

    private static void stamp(JsonWriter w, UUID domain, long revision) {
        w.key("expected").beginObj().kv("domain", BsiFracture.hex(domain)).kv("revision", revision).endObj();
    }

    private static JsonWriter BsiHeadersBase(String id, String method, long revision) {
        return new JsonWriter().beginObj()
                .kv("bsi", BsiContract.MAJOR).kv("kind", "request")
                .kv("id", id).kv("method", method).kv("revision", revision);
    }

    // ---------------------------------------------------------------- responses

    /** One island verdict row decoded from {@code corotIslands} + {@code corotText}. */
    public record Island(int index, long status, long flags, String reason) { }

    /**
     * The validated finite response identity shared by {@code analyzed} and {@code restored}:
     * envelope, world basis, artifact namespace, session generation and the fresh
     * process-local token. Sections stay complete for the caller's typed views.
     */
    public record Identity(String generation, BsiFracture.Token token, boolean fullyConverged,
                           List<Island> islands, Map<String, BsiResponse.Section> sections, byte[] payload) {
        public Identity { islands = List.copyOf(islands); sections = Map.copyOf(sections); payload = payload.clone(); }
    }

    /**
     * Validate a {@code bsi.corot.solve}/{@code ...import} reply against the exact declared
     * basis. Any field that would let a foreign answer pose as this world's answer is checked;
     * a mismatch is a protocol violation, never a usable result.
     */
    public static Identity checkAnalysis(BsiResponse response, String id, String method, long revision,
                                         UUID expectedDomain, long expectedRevision, UUID artifactNamespace) {
        if (response == null || response.isError()) throw invalid("corot response refused");
        var h = response.header();
        String expected = switch (method) {
            case "bsi.corot.solve" -> "analyzed";
            case "bsi.corot.checkpoint.import" -> "restored";
            default -> throw invalid("unknown corot method");
        };
        if (!"response".equals(h.str("kind", "")) || !expected.equals(response.status())
                || !method.equals(h.str("method", "")) || !id.equals(h.str("id", ""))
                || !h.isExactInt("bsi") || h.exactI64("bsi") != BsiContract.MAJOR
                || !h.isExactInt("revision") || h.exactI64("revision") != revision)
            throw invalid("corot response envelope mismatch");
        var basis = h.objField("basis");
        if (!basis.isObject() || !expectedDomain.equals(readUuid(basis.str("domain", "")))
                || !basis.isExactInt("revision") || basis.exactI64("revision") != expectedRevision)
            throw invalid("corot response basis mismatch");
        if (!artifactNamespace.equals(readUuid(h.str("artifactNamespace", ""))))
            throw invalid("corot response namespace mismatch");
        var generation = h.str("generation", "");
        if (!generation.matches("[0-9a-f]{16}")) throw invalid("corot response generation mismatch");
        if (!h.isBool("fullyConverged")) throw invalid("corot response convergence missing");
        var tokenValue = h.objField("token");
        var token = new BsiFracture.Token(readUuid(tokenValue.str("context", "")), tokenValue.str("sequence", ""));
        return new Identity(generation, token, h.bool("fullyConverged", false),
                islands(response), response.sections(), response.payload());
    }

    /** The {@code exported} reply: basis + format + byte count over the opaque payload. */
    public record Exported(UUID domain, long revision, int format, byte[] blob) {
        public Exported { blob = blob.clone(); }
    }

    public static Exported checkExport(BsiResponse response, String id, long revision,
                                       UUID expectedDomain, long expectedRevision) {
        if (response == null || response.isError()) throw invalid("checkpoint export refused");
        var h = response.header();
        if (!"response".equals(h.str("kind", "")) || !"exported".equals(response.status())
                || !"bsi.corot.checkpoint.export".equals(h.str("method", "")) || !id.equals(h.str("id", ""))
                || !h.isExactInt("bsi") || h.exactI64("bsi") != BsiContract.MAJOR
                || !h.isExactInt("revision") || h.exactI64("revision") != revision)
            throw invalid("export response envelope mismatch");
        var basis = h.objField("basis");
        if (!basis.isObject() || !expectedDomain.equals(readUuid(basis.str("domain", "")))
                || !basis.isExactInt("revision") || basis.exactI64("revision") != expectedRevision)
            throw invalid("export response basis mismatch");
        int format = (int) h.exactI64("format");
        long bytes = h.exactI64("bytes");
        if (format < 1 || format > 3 || bytes < 32 || bytes > 268431360
                || response.payload().length != bytes) throw invalid("export response payload mismatch");
        return new Exported(expectedDomain, expectedRevision, format, response.payload());
    }

    private static List<Island> islands(BsiResponse response) {
        var out = new ArrayList<Island>();
        var table = response.sections().get("corotIslands");
        if (table == null) return List.of();
        if (table.bytes() != (long) table.count() * ISLAND_BYTES || table.offset() < 0
                || (long) table.offset() + table.bytes() > response.payload().length) throw invalid("corot islands section");
        var records = ByteBuffer.wrap(response.payload(), table.offset(), table.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        var text = response.sections().get("corotText");
        for (int i = 0; i < table.count(); i++) {
            long status = Integer.toUnsignedLong(records.getInt());
            records.getInt(); // freeDof
            long flags = Integer.toUnsignedLong(records.getInt());
            // iterations..dissipatedEnergy: 7 u32 counters then 6 f64 = 76 bytes.
            records.position(records.position() + 76);
            long first = Integer.toUnsignedLong(records.getInt()), count = Integer.toUnsignedLong(records.getInt());
            String reason = "";
            if (count != 0) {
                if (text == null || first + count > text.bytes() || first > Integer.MAX_VALUE || count > Integer.MAX_VALUE)
                    throw invalid("corot island reason range");
                reason = new String(response.payload(), text.offset() + (int) first, (int) count,
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            out.add(new Island(i, status, flags, reason));
        }
        return List.copyOf(out);
    }

    private static UUID readUuid(String hex) {
        return BsiFractureUuid(hex);
    }

    private static UUID BsiFractureUuid(String hex) {
        try {
            var bytes = java.util.HexFormat.of().parseHex(hex);
            var buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (IllegalArgumentException bad) { throw invalid("invalid id128"); }
    }

    static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("BSI corot: " + message); }
}
