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

    /** The ABI10 analysis-section strides (schema {@code x-records}), frozen by contract. */
    public static final int PHYSICAL_BYTES = 80, NODE_BYTES = 208, MEMBER_BYTES = 472,
            SHELL_BYTES = 1184, JOINT_BYTES = 208, SOURCE_BYTES = 56, ARTIFACT_BYTES = 24,
            PROFILE_BYTES = 88, FIBER_BYTES = 104, STATION_BYTES = 40, POINT_BYTES = 120,
            SHELL_PROFILE_BYTES = 88, TENSOR_POINT_BYTES = 472;

    /** The mandatory section order every corot analysis response must carry exactly. */
    private static final List<String> COROT_ORDER = List.of(
            "corotOptions", "corotPhysical", "corotIslands", "corotText", "corotNodes",
            "corotMembers", "corotShells", "corotJoints", "corotSources", "corotSourceIndices",
            "corotArtifacts", "corotArtifactMembers", "corotArtifactShells", "corotProfiles",
            "corotFibers", "corotStations", "corotPoints");
    /** The ABI10 owning tail, present iff the world declared shell material laws. */
    private static final List<String> COROT_TAIL = List.of(
            "corotShellProfiles", "corotShellLayers", "corotTensorPoints");

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

    // ---------------------------------------------------------------- arc advance (ABI9, bsi_corot_arc.h)

    /**
     * The schema {@code corot.arc.advance.body} fields, validated client-side before the
     * request ever reaches the wire: radii/scales strictly positive and finite, tolerances
     * unsigned finite, solver/path budgets integers in {@code [1, 1_000_000]} and the
     * initial direction exactly {@code -1} or {@code +1}.
     */
    public record ArcOptions(double radius, double minRadius, double lengthScale, double loadScale,
                             double relativeTolerance, double forceTolerance, double momentTolerance,
                             double linearTolerance, double pathTolerance, int maxIterations,
                             int maxAttempts, int maxBacktracks, int linearIterations, int restart,
                             int initialDirection) {
        public ArcOptions {
            arcPositive(radius); arcPositive(minRadius); arcPositive(lengthScale); arcPositive(loadScale);
            arcUnsigned(relativeTolerance); arcUnsigned(forceTolerance); arcUnsigned(momentTolerance);
            arcUnsigned(linearTolerance); arcUnsigned(pathTolerance);
            arcBudget(maxIterations); arcBudget(maxAttempts); arcBudget(maxBacktracks);
            arcBudget(linearIterations); arcBudget(restart);
            if (initialDirection != -1 && initialDirection != 1) throw invalid("invalid arc direction");
        }
    }

    private static void arcPositive(double v) {
        if (!Double.isFinite(v) || v <= 0) throw invalid("invalid arc scale");
    }
    private static void arcUnsigned(double v) {
        if (!Double.isFinite(v) || v < 0) throw invalid("invalid arc tolerance");
    }
    private static void arcBudget(int v) {
        if (v < 1 || v > 1_000_000) throw invalid("invalid arc budget");
    }

    /**
     * {@code bsi.corot.arc.advance}: the binary payload is empty by contract — every step
     * parameter is a JSON body field. {@code initialAnalysis} is the live fully-converged
     * token this step starts from; the caller proves convergence before calling.
     */
    public static String arcAdvance(String id, long revision, UUID domain, long expected,
                                    BsiFracture.Token initialAnalysis, ArcOptions o) {
        if (domain == null || domain.equals(new UUID(0, 0)) || expected < 0) throw invalid("invalid arc stamp");
        if (initialAnalysis == null || initialAnalysis.context() == null
                || initialAnalysis.context().equals(new UUID(0, 0))
                || initialAnalysis.sequence() == null
                || !initialAnalysis.sequence().matches("[0-9a-f]{16}")
                || initialAnalysis.sequence().equals("0000000000000000"))
            throw invalid("invalid arc initial token");
        var w = BsiHeadersBase(id, "bsi.corot.arc.advance", revision).key("body").beginObj();
        stamp(w, domain, expected);
        w.key("initialAnalysis").beginObj().kv("context", BsiFracture.hex(initialAnalysis.context()))
                .kv("sequence", initialAnalysis.sequence()).endObj()
                .kv("radius", o.radius()).kv("minRadius", o.minRadius())
                .kv("lengthScale", o.lengthScale()).kv("loadScale", o.loadScale())
                .kv("relativeTolerance", o.relativeTolerance()).kv("forceTolerance", o.forceTolerance())
                .kv("momentTolerance", o.momentTolerance()).kv("linearTolerance", o.linearTolerance())
                .kv("pathTolerance", o.pathTolerance())
                .kv("maxIterations", o.maxIterations()).kv("maxAttempts", o.maxAttempts())
                .kv("maxBacktracks", o.maxBacktracks()).kv("linearIterations", o.linearIterations())
                .kv("restart", o.restart()).kv("initialDirection", o.initialDirection());
        return w.endObj().endObj().done();
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

    // ---------------------------------------------------------------- strict analysis validation

    /**
     * The declared world the strict validator checks a corot analysis response against:
     * the canonical blocks, the artifact owner table, the adopted vocabulary, each
     * material's vocabulary-declared default section (id→id, absent when the vocabulary
     * declared none) and whether shell material laws were declared (ABI10 tail).
     */
    public record CorotWorld(List<BsiRecords.Block> blocks, List<BsiFracture.Owner> owners,
                             BsiVocabulary vocabulary, java.util.Map<Integer, Integer> defaultSections,
                             boolean shellMaterials) {
        public CorotWorld {
            blocks = List.copyOf(blocks);
            owners = List.copyOf(owners);
            java.util.Objects.requireNonNull(vocabulary);
            defaultSections = java.util.Map.copyOf(defaultSections);
        }
    }

    /**
     * The {@code available}-bit census over the material-view sections. {@code reported}
     * is false when no profile section was returned at all; otherwise each profile's
     * {@code &4} (material history present) and {@code &2}-without-{@code &4} (retained
     * but not freshly solved) bits are counted. Unavailable is never "zero force".
     */
    public record Availability(boolean reported, int memberProfiles, int memberMaterial,
                               int memberRetained, int shellProfiles, int shellMaterial,
                               int shellRetained) {
        public boolean materialPresent() { return memberMaterial + shellMaterial > 0; }
    }

    /** The validated {@code advanced} reply: identity, arc scalars and the availability census. */
    public record ArcResult(String generation, BsiFracture.Token token, List<Island> islands,
                            Map<String, BsiResponse.Section> sections, byte[] payload,
                            double loadFactor, double radius, double constraintResidual,
                            Availability availability) {
        public ArcResult {
            islands = List.copyOf(islands); sections = Map.copyOf(sections); payload = payload.clone();
        }
    }

    /**
     * Strictly validate a {@code bsi.corot.arc.advance} reply: the exact envelope, the world
     * basis, the fresh (non-reused) token, the JSON scalar exceptions — {@code loadFactor},
     * {@code radius ∈ (0, request.radius]}, {@code constraintResidual ∈ [0, 1e-10]} — then the
     * complete canonical section directory and every record semantic the native validator
     * enforces ({@code bsi_corot_validate.hpp}). {@code fullyConverged} must be true: the
     * engine refuses to emit an advanced reply for a non-converged step, so a false here is
     * a protocol violation, not a result. {@code previous} is the token this step consumed;
     * an engine that echoes it back has produced no new state.
     */
    public static ArcResult checkArc(BsiResponse response, String id, long revision,
                                     UUID expectedDomain, long expectedRevision,
                                     UUID artifactNamespace, CorotWorld world,
                                     ArcOptions request, BsiFracture.Token previous) {
        if (response == null || response.isError()) throw invalid("corot arc refused");
        var h = response.header();
        if (!"response".equals(h.str("kind", "")) || !"advanced".equals(response.status())
                || !"bsi.corot.arc.advance".equals(h.str("method", "")) || !id.equals(h.str("id", ""))
                || !h.isExactInt("bsi") || h.exactI64("bsi") != BsiContract.MAJOR
                || !h.isExactInt("revision") || h.exactI64("revision") != revision)
            throw invalid("corot arc envelope mismatch");
        var basis = h.objField("basis");
        if (!basis.isObject() || !expectedDomain.equals(readUuid(basis.str("domain", "")))
                || !basis.isExactInt("revision") || basis.exactI64("revision") != expectedRevision)
            throw invalid("corot arc basis mismatch");
        if (!artifactNamespace.equals(readUuid(h.str("artifactNamespace", ""))))
            throw invalid("corot arc namespace mismatch");
        var generation = h.str("generation", "");
        if (!generation.matches("[0-9a-f]{16}")) throw invalid("corot arc generation mismatch");
        if (!h.isBool("fullyConverged") || !h.bool("fullyConverged", false))
            throw invalid("corot arc did not converge");
        var tokenValue = h.objField("token");
        var token = new BsiFracture.Token(readUuid(tokenValue.str("context", "")),
                tokenValue.str("sequence", ""));
        if (token.equals(previous)) throw invalid("corot arc reused its source token");
        if (!h.isNum("loadFactor") || !Double.isFinite(h.num("loadFactor", Double.NaN)))
            throw invalid("corot arc load factor");
        if (!h.isNum("radius") || !Double.isFinite(h.num("radius", Double.NaN))
                || h.num("radius", 0) <= 0 || h.num("radius", 0) > request.radius())
            throw invalid("corot arc radius");
        if (!h.isNum("constraintResidual") || !Double.isFinite(h.num("constraintResidual", Double.NaN))
                || h.num("constraintResidual", -1) < 0 || h.num("constraintResidual", -1) > 1e-10)
            throw invalid("corot arc residual");
        var view = validateAnalysis(response, world, expectedDomain, expectedRevision);
        return new ArcResult(generation, token, view.islands(), response.sections(),
                response.payload(), h.num("loadFactor", 0), h.num("radius", 0),
                h.num("constraintResidual", 0), view.availability());
    }

    private record AnalysisView(List<Island> islands, Availability availability) { }

    private static int sectionStride(String name) {
        return switch (name) {
            case "corotOptions" -> OPTIONS_BYTES; case "corotPhysical" -> PHYSICAL_BYTES;
            case "corotIslands" -> ISLAND_BYTES; case "corotText" -> 1;
            case "corotNodes" -> NODE_BYTES; case "corotMembers" -> MEMBER_BYTES;
            case "corotShells" -> SHELL_BYTES; case "corotJoints" -> JOINT_BYTES;
            case "corotSources" -> SOURCE_BYTES; case "corotSourceIndices" -> 4;
            case "corotArtifacts" -> ARTIFACT_BYTES;
            case "corotArtifactMembers", "corotArtifactShells" -> 4;
            case "corotProfiles" -> PROFILE_BYTES; case "corotFibers" -> FIBER_BYTES;
            case "corotStations" -> STATION_BYTES; case "corotPoints" -> POINT_BYTES;
            case "corotShellProfiles" -> SHELL_PROFILE_BYTES;
            case "corotShellLayers" -> SHELL_LAYER_BYTES;
            case "corotTensorPoints" -> TENSOR_POINT_BYTES;
            default -> throw invalid("unknown corot section " + name);
        };
    }

    private record Pos(int x, int y, int z) {
        static Pos of(ByteBuffer b, int offset) { return new Pos(b.getInt(offset), b.getInt(offset + 4), b.getInt(offset + 8)); }
    }

    /** The whole canonical directory plus every record the native validator checks. */
    private static AnalysisView validateAnalysis(BsiResponse response, CorotWorld world,
                                                 UUID expectedDomain, long expectedRevision) {
        var payload = response.payload();
        var sections = response.sections();
        var order = new ArrayList<>(COROT_ORDER);
        if (world.shellMaterials()) order.addAll(COROT_TAIL);
        if (sections.size() != order.size()) throw invalid("corot section set");
        int cursor = 0;
        var names = sections.values().iterator();
        for (String name : order) {
            var s = names.next();
            if (!s.name().equals(name)) throw invalid("corot section order");
            var declared = sections.get(name);
            if (declared.offset() != cursor) throw invalid("corot section gap " + name);
            if (s.bytes() != (long) s.count() * sectionStride(name)) throw invalid("corot section size " + name);
            cursor += s.bytes();
        }
        if (cursor != payload.length) throw invalid("corot payload trailing bytes");
        var data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

        // The world the response must account for, exactly once each — the 40-byte block
        // image is what the contract memcmp binds, so we keep the canonical encoding.
        var orderedBlocks = BsiRecords.canonical(world.blocks());
        var worldBlocks = new java.util.HashMap<Pos, Integer>();
        byte[] worldImage = BsiRecords.encodeBlocks(orderedBlocks);
        for (int i = 0; i < orderedBlocks.size(); i++) {
            var block = orderedBlocks.get(i);
            worldBlocks.put(new Pos(block.x(), block.y(), block.z()), i);
        }
        var worldOwners = new java.util.HashMap<Pos, Long>();
        for (var owner : world.owners())
            worldOwners.put(new Pos(owner.position().x(), owner.position().y(), owner.position().z()), owner.artifact());

        var options = sections.get("corotOptions");
        if (options.count() != 1) throw invalid("corot options count");
        var o = options(java.util.Arrays.copyOfRange(payload, options.offset(), options.offset() + OPTIONS_BYTES));
        if (!o.expectedDomain().equals(expectedDomain) || o.expectedRevision() != expectedRevision)
            throw invalid("corot options stamp");

        var physical = sections.get("corotPhysical");
        if (physical.count() != 7) throw invalid("corot physical count");
        for (int i = 0; i < 7; i++) {
            int at = physical.offset() + i * PHYSICAL_BYTES;
            checkFinite(data, at, 10);                    // mass, center[3], inertia[6]
            if (data.getDouble(at) < 0 || data.getDouble(at + 32) < 0
                    || data.getDouble(at + 40) < 0 || data.getDouble(at + 48) < 0)
                throw invalid("corot physical value");
        }

        var islands = sections.get("corotIslands");
        var text = sections.get("corotText");
        var verdicts = new ArrayList<Island>();
        var statuses = new int[islands.count()];
        for (int i = 0; i < islands.count(); i++) {
            int at = islands.offset() + i * ISLAND_BYTES;
            int status = data.getInt(at);
            if (status < 0 || status > 4) throw invalid("corot island status");
            // The reply claims full convergence; any island that says otherwise contradicts it.
            if (status != 0 && status != 4) throw invalid("corot island convergence");
            int flags = data.getInt(at + 8);
            if (flags < 0 || flags > 7) throw invalid("corot island flags");
            if (!nonnegative(data.getDouble(at + 40)) || !nonnegative(data.getDouble(at + 48))
                    || !nonnegative(data.getDouble(at + 72)) || !nonnegative(data.getDouble(at + 80)))
                throw invalid("corot island residual");
            double historyError = data.getDouble(at + 56);
            if (Double.isNaN(historyError) || historyError < 0
                    || !Double.isFinite(historyError))       // converged/inactive: must be finite
                throw invalid("corot island history");
            if (!Double.isFinite(data.getDouble(at + 64))) throw invalid("corot island history factor");
            long first = Integer.toUnsignedLong(data.getInt(at + 88));
            long count = Integer.toUnsignedLong(data.getInt(at + 92));
            if (first + count > text.count()) throw invalid("corot island reason");
            statuses[i] = status;
            verdicts.add(new Island(i, status, flags,
                    new String(payload, text.offset() + (int) first, (int) count,
                            java.nio.charset.StandardCharsets.UTF_8)));
        }

        var nodes = sections.get("corotNodes");
        for (int i = 0; i < nodes.count(); i++) {
            int at = nodes.offset() + i * NODE_BYTES;
            int island = data.getInt(at + 16);
            if (island < 0 || island >= islands.count()) throw invalid("corot node island");
            int available = data.getInt(at + 20);
            if (available != (statuses[island] == 0 ? 1 : 0)) throw invalid("corot node availability");
            for (int k = 0; k < 6; k++) {
                int fixed = data.get(at + 200 + k) & 0xFF;
                if (fixed > 1) throw invalid("corot node fixed");
            }
            if (data.get(at + 206) != 0 || data.get(at + 207) != 0) throw invalid("corot node reserved");
            checkFinite(data, at + 24, 22);               // reference..applied
            if (available != 0) checkRotation(data, at + 72);
            else {
                for (int k = 0; k < 19; k++)              // position..applied stay zeroed
                    if (data.getDouble(at + 48 + 8 * k) != 0) throw invalid("corot node echo");
            }
        }

        var members = sections.get("corotMembers");
        var shells = sections.get("corotShells");
        var sources = sections.get("corotSources");
        var sourceIndices = sections.get("corotSourceIndices");
        var profiles = sections.get("corotProfiles");
        long sourceCursor = 0;
        var claimedProfiles = new boolean[profiles.count()];
        for (int i = 0; i < members.count(); i++) {
            int at = members.offset() + i * MEMBER_BYTES;
            int active = data.getInt(at + 36);
            if (active != 0 && active != 1) throw invalid("corot member active");
            int island = data.getInt(at);
            if (!(island >= 0 && island < islands.count()) && !(active == 0 && island == -1))
                throw invalid("corot member island");
            for (int n = 0; n < 2; n++) {
                int node = data.getInt(at + 4 + 4 * n);
                if (node < 0 || node >= nodes.count()) throw invalid("corot member node");
            }
            int available = data.getInt(at + 24);
            if (available < 0 || available > 7) throw invalid("corot member available");
            if ((available & 1) != (active != 0 && statuses[island] == 0 ? 1 : 0))
                throw invalid("corot member solved flag");
            long first = Integer.toUnsignedLong(data.getInt(at + 28));
            long count = Integer.toUnsignedLong(data.getInt(at + 32));
            if (first != sourceCursor || first + count > sourceIndices.count())
                throw invalid("corot member sources");
            sourceCursor = first + count;
            int material = data.getInt(at + 12), section = data.getInt(at + 16);
            if (count == 0) {
                if (material != -1 || section != -1) throw invalid("corot member material");
            } else {
                if (!world.vocabulary().materials().containsKey(material)
                        || !world.vocabulary().sections().containsKey(section))
                    throw invalid("corot member vocabulary");
                int sourceIndex = data.getInt(sourceIndices.offset() + 4 * (int) first);
                if (sourceIndex < 0 || sourceIndex >= sources.count())
                    throw invalid("corot member source index");
                int sat = sources.offset() + sourceIndex * SOURCE_BYTES;
                if (material != data.getInt(sat + 12)) throw invalid("corot member material drift");
                int declared = data.getInt(sat + 16);
                int expectedSection = declared < 0
                        ? world.defaultSections().getOrDefault(material, -1) : declared;
                if (section != expectedSection) throw invalid("corot member section drift");
            }
            int profile = data.getInt(at + 20);
            if (profile == -1) {
                if ((available & 6) != 0) throw invalid("corot member profile flags");
            } else {
                if (profile < 0 || profile >= profiles.count() || claimedProfiles[profile])
                    throw invalid("corot member profile");
                claimedProfiles[profile] = true;
                int pat = profiles.offset() + profile * PROFILE_BYTES;
                if (data.getInt(pat) != i || data.getInt(pat + 4) != (available & 7))
                    throw invalid("corot member profile link");
            }
            if (!nonnegative(data.getDouble(at + 40)) || !nonnegative(data.getDouble(at + 48)))
                throw invalid("corot member length");
            checkFinite(data, at + 56, 52);               // frame + 4x12 channels
            if ((available & 1) != 0) {
                if (data.getDouble(at + 40) <= 0) throw invalid("corot member geometry");
                checkRotation(data, at + 56);
            }
        }
        for (boolean claimed : claimedProfiles)
            if (!claimed) throw invalid("corot profile unclaimed");
        for (int i = 0; i < shells.count(); i++) {
            int at = shells.offset() + i * SHELL_BYTES;
            int active = data.getInt(at + 36);
            if (active != 0 && active != 1) throw invalid("corot shell active");
            int island = data.getInt(at);
            if (!(island >= 0 && island < islands.count()) && !(active == 0 && island == -1))
                throw invalid("corot shell island");
            for (int n = 0; n < 4; n++) {
                int node = data.getInt(at + 4 + 4 * n);
                if (node < 0 || node >= nodes.count()) throw invalid("corot shell node");
            }
            int available = data.getInt(at + 24);
            if (available < 0 || available > 3) throw invalid("corot shell available");
            if ((available & 1) != (active != 0 && statuses[island] == 0 ? 1 : 0))
                throw invalid("corot shell solved flag");
            long first = Integer.toUnsignedLong(data.getInt(at + 28));
            long count = Integer.toUnsignedLong(data.getInt(at + 32));
            if (first != sourceCursor || first + count > sourceIndices.count())
                throw invalid("corot shell sources");
            sourceCursor = first + count;
            int material = data.getInt(at + 20);
            if (count == 0) {
                if (material != -1) throw invalid("corot shell material");
            } else {
                if (!world.vocabulary().materials().containsKey(material))
                    throw invalid("corot shell vocabulary");
                int sourceIndex = data.getInt(sourceIndices.offset() + 4 * (int) first);
                if (sourceIndex < 0 || sourceIndex >= sources.count())
                    throw invalid("corot shell source index");
                if (material != data.getInt(sources.offset() + sourceIndex * SOURCE_BYTES + 12))
                    throw invalid("corot shell material drift");
            }
            if (!nonnegative(data.getDouble(at + 40)) || !nonnegative(data.getDouble(at + 48))
                    || !nonnegative(data.getDouble(at + 56))) throw invalid("corot shell geometry");
            checkFinite(data, at + 64, 140);              // frame..sections
            if ((available & 1) != 0) {
                if (data.getDouble(at + 40) <= 0 || data.getDouble(at + 48) <= 0)
                    throw invalid("corot shell geometry");
                checkRotation(data, at + 64);
            }
        }
        if (sourceCursor != sourceIndices.count()) throw invalid("corot source cursor");

        var joints = sections.get("corotJoints");
        for (int i = 0; i < joints.count(); i++) {
            int at = joints.offset() + i * JOINT_BYTES;
            int kind = data.getInt(at), available = data.getInt(at + 4);
            if (kind < 0 || kind > 4 || available < 0 || available > 3) throw invalid("corot joint kind");
            int index = data.getInt(at + 8), node = data.getInt(at + 12), end = data.getInt(at + 16);
            if (index < 0 || node < 0 || node >= nodes.count()) throw invalid("corot joint index");
            if (kind == 1 && (index >= members.count() || (end != 0 && end != 1)))
                throw invalid("corot joint release");
            checkFinite(data, at + 24, 23);               // position..action
            // bsi_corot_validate.hpp jointValid: unit quaternions are required only when
            // the joint is available AND kind ∈ {0 node chart, 1 member release}; a
            // kind ≥ 2 joint carries an arbitrary finite orientation.
            if (available != 0 && kind <= 1) { checkRotation(data, at + 48); checkRotation(data, at + 80); }
        }

        var found = new java.util.HashSet<Pos>();
        int foundBlocks = 0;
        for (int i = 0; i < sources.count(); i++) {
            int at = sources.offset() + i * SOURCE_BYTES;
            var pos = Pos.of(data, at);
            if (!found.add(pos)) throw invalid("corot source duplicate");
            int mat = data.getInt(at + 12);
            if (!world.vocabulary().materials().containsKey(mat)) throw invalid("corot source material");
            long artifact = data.getLong(at + 40);
            int disposition = data.getInt(at + 48);
            if (disposition < 0 || disposition > 6 || data.getInt(at + 52) != 0)
                throw invalid("corot source disposition");
            var block = worldBlocks.get(pos);
            if (block == null) {
                if (disposition != 5 || artifact != 0) throw invalid("corot source retired fields");
                continue;
            }
            foundBlocks++;
            if (disposition == 5) throw invalid("corot source retired present block");
            byte[] declared = java.util.Arrays.copyOfRange(payload, at, at + 40);
            byte[] canonical = java.util.Arrays.copyOfRange(worldImage, block * 40, block * 40 + 40);
            if (!java.util.Arrays.equals(declared, canonical)) throw invalid("corot source block drift");
            if (artifact != worldOwners.getOrDefault(pos, 0L)) throw invalid("corot source artifact");
        }
        if (foundBlocks != world.blocks().size()) throw invalid("corot source coverage");

        for (int i = 0; i < sourceIndices.count(); i++) {
            int index = data.getInt(sourceIndices.offset() + 4 * i);
            if (index < 0 || index >= sources.count()) throw invalid("corot source index");
        }

        // The artifact tables must reproduce the ownership graph of active elements.
        var artifacts = sections.get("corotArtifacts");
        var artifactMembers = sections.get("corotArtifactMembers");
        var artifactShells = sections.get("corotArtifactShells");
        // bsi_pdelta_wire.hpp: each element resolves to a DISTINCT set of member/shell
        // indices per owning artifact — one member spanning several source blocks of the
        // same artifact is listed once, not once per block.
        var expected = new java.util.TreeMap<Long, Links>();
        for (int i = 0; i < members.count(); i++) {
            int at = members.offset() + i * MEMBER_BYTES;
            if (data.getInt(at + 36) == 0) continue;
            int first = data.getInt(at + 28), count = data.getInt(at + 32);
            for (int s = 0; s < count; s++) {
                int src = data.getInt(sourceIndices.offset() + 4 * (first + s));
                long a = data.getLong(sources.offset() + src * SOURCE_BYTES + 40);
                if (a != 0) expected.computeIfAbsent(a, k -> new Links()).members.add(i);
            }
        }
        for (int i = 0; i < shells.count(); i++) {
            int at = shells.offset() + i * SHELL_BYTES;
            if (data.getInt(at + 36) == 0) continue;
            int first = data.getInt(at + 28), count = data.getInt(at + 32);
            for (int s = 0; s < count; s++) {
                int src = data.getInt(sourceIndices.offset() + 4 * (first + s));
                long a = data.getLong(sources.offset() + src * SOURCE_BYTES + 40);
                if (a != 0) expected.computeIfAbsent(a, k -> new Links()).shells.add(i);
            }
        }
        if (artifacts.count() != expected.size()) throw invalid("corot artifact set");
        long previousArtifact = 0;
        int memberCursor = 0, shellCursor = 0;
        var artifactIterator = expected.keySet().iterator();
        for (int i = 0; i < artifacts.count(); i++) {
            int at = artifacts.offset() + i * ARTIFACT_BYTES;
            long artifact = data.getLong(at);
            long want = artifactIterator.next();
            if (artifact != want || artifact <= 0 || artifact <= previousArtifact)
                throw invalid("corot artifact identity");
            previousArtifact = artifact;
            int mFirst = data.getInt(at + 8), mCount = data.getInt(at + 12);
            int sFirst = data.getInt(at + 16), sCount = data.getInt(at + 20);
            if (mFirst != memberCursor || mFirst + mCount > artifactMembers.count()
                    || sFirst != shellCursor || sFirst + sCount > artifactShells.count())
                throw invalid("corot artifact range");
            memberCursor = mFirst + mCount; shellCursor = sFirst + sCount;
            var linked = expected.get(artifact);
            if (linked.members.size() != mCount || linked.shells.size() != sCount)
                throw invalid("corot artifact membership");
            int previousMember = -1;
            for (int k = 0; k < mCount; k++) {
                int member = data.getInt(artifactMembers.offset() + 4 * (mFirst + k));
                if (member <= previousMember || member >= members.count()) throw invalid("corot artifact member");
                previousMember = member;
                int at2 = members.offset() + member * MEMBER_BYTES;
                if (data.getInt(at2 + 36) == 0) throw invalid("corot artifact inactive member");
                if (!memberLinks(data, at2, sourceIndices, sources, artifact))
                    throw invalid("corot artifact member link");
            }
            int previousShell = -1;
            for (int k = 0; k < sCount; k++) {
                int shell = data.getInt(artifactShells.offset() + 4 * (sFirst + k));
                if (shell <= previousShell || shell >= shells.count()) throw invalid("corot artifact shell");
                previousShell = shell;
                int at2 = shells.offset() + shell * SHELL_BYTES;
                if (data.getInt(at2 + 36) == 0) throw invalid("corot artifact inactive shell");
                if (!shellLinks(data, at2, sourceIndices, sources, artifact))
                    throw invalid("corot artifact shell link");
            }
        }
        if (memberCursor != artifactMembers.count() || shellCursor != artifactShells.count())
            throw invalid("corot artifact cursor");

        var fibers = sections.get("corotFibers");
        var stations = sections.get("corotStations");
        var points = sections.get("corotPoints");
        int memberProfiles = 0, memberMaterial = 0, memberRetained = 0;
        int fiberCursor = 0, stationCursor = 0, pointCursor = 0;
        for (int i = 0; i < profiles.count(); i++) {
            int at = profiles.offset() + i * PROFILE_BYTES;
            int member = data.getInt(at);
            if (member < 0 || member >= members.count()) throw invalid("corot profile member");
            int available = data.getInt(at + 4);
            if (available < 0 || available > 7 || data.getInt(at + 36) != 0)
                throw invalid("corot profile flags");
            int physicalSection = data.getInt(at + 32);
            if (physicalSection != 0 && physicalSection != 1) throw invalid("corot profile kind");
            long fFirst = Integer.toUnsignedLong(data.getInt(at + 8));
            long fCount = Integer.toUnsignedLong(data.getInt(at + 12));
            long sFirst = Integer.toUnsignedLong(data.getInt(at + 16));
            long sCount = Integer.toUnsignedLong(data.getInt(at + 20));
            long pFirst = Integer.toUnsignedLong(data.getInt(at + 24));
            long pCount = Integer.toUnsignedLong(data.getInt(at + 28));
            if (fCount == 0 || sCount == 0
                    || fFirst != fiberCursor || fFirst + fCount > fibers.count()
                    || sFirst != stationCursor || sFirst + sCount > stations.count()
                    || pFirst != pointCursor || pFirst + pCount > points.count())
                throw invalid("corot profile range");
            fiberCursor = (int) (fFirst + fCount); stationCursor = (int) (sFirst + sCount);
            pointCursor = (int) (pFirst + pCount);
            memberProfiles++;
            // Frozen census rule (design §5.1): &4 is a live material history; a
            // checkpoint-carried &2 without &4 is a retained one — never "solved".
            if ((available & 4) != 0) memberMaterial++; else if ((available & 2) != 0) memberRetained++;
            double area = data.getDouble(at + 40), iy = data.getDouble(at + 48), iz = data.getDouble(at + 56);
            double stored = data.getDouble(at + 64), dissipated = data.getDouble(at + 72),
                    work = data.getDouble(at + 80);
            if (available == 0) {
                if (pCount != 0 || stored != 0 || dissipated != 0 || work != 0)
                    throw invalid("corot profile residue");
            } else {
                if ((available & 6) != 6 || pCount != fCount * sCount) throw invalid("corot profile history");
            }
            if (!nonnegative(area) || !nonnegative(iy) || !nonnegative(iz)
                    || !nonnegative(stored) || !nonnegative(dissipated) || !nonnegative(work)
                    || (physicalSection == 1 && (area <= 0 || iy <= 0 || iz <= 0)))
                throw invalid("corot profile value");
            for (long k = fFirst; k < fFirst + fCount; k++) {
                int fat = fibers.offset() + (int) k * FIBER_BYTES;
                if (!Double.isFinite(data.getDouble(fat)) || !Double.isFinite(data.getDouble(fat + 8))
                        || !nonnegative(data.getDouble(fat + 16)) || data.getDouble(fat + 16) <= 0
                        || data.getInt(fat + 28) != 0)
                    throw invalid("corot fiber");
                checkLaw(data, fat + 32);
            }
            // Station positions/weights are always checked; only the section rows zero out
            // when the profile carries no material state (native rule, not a relaxation).
            // bsi_corot_validate.hpp:22 — the open lower sentinel is -1, so a station at
            // the member end 0 is legal; the interval is (-1, 1] strictly increasing.
            double previousStation = -1;
            for (long k = sFirst; k < sFirst + sCount; k++) {
                int sat = stations.offset() + (int) k * STATION_BYTES;
                double station = data.getDouble(sat), weight = data.getDouble(sat + 8);
                if (!Double.isFinite(station) || station <= previousStation || station > 1
                        || !Double.isFinite(weight) || weight <= 0)
                    throw invalid("corot station order");
                previousStation = station;
                checkFinite(data, sat + 16, 3);
                if (available == 0)
                    for (int d = 0; d < 3; d++)
                        if (data.getDouble(sat + 16 + 8 * d) != 0) throw invalid("corot station residue");
            }
            for (long k = pFirst; k < pFirst + pCount; k++) {
                int pat = points.offset() + (int) k * POINT_BYTES;
                checkFinite(data, pat, 14);
                int loading = data.getInt(pat + 112);
                if (loading < -1 || loading > 1 || data.getInt(pat + 116) != 0)
                    throw invalid("corot point flags");
                if (!nonnegative(data.getDouble(pat + 16)) || !nonnegative(data.getDouble(pat + 24))
                        || !nonnegative(data.getDouble(pat + 32)) || !nonnegative(data.getDouble(pat + 40))
                        || !nonnegative(data.getDouble(pat + 48)) || !nonnegative(data.getDouble(pat + 72))
                        || !nonnegative(data.getDouble(pat + 80)) || !nonnegative(data.getDouble(pat + 88)))
                    throw invalid("corot point value");
                if (data.getDouble(pat + 96) < 0 || data.getDouble(pat + 96) > 1
                        || data.getDouble(pat + 104) < 0 || data.getDouble(pat + 104) > 1)
                    throw invalid("corot point damage");
            }
        }
        if (fiberCursor != fibers.count() || stationCursor != stations.count()
                || pointCursor != points.count()) throw invalid("corot material cursor");

        int shellProfiles = 0, shellMaterial = 0, shellRetained = 0;
        if (world.shellMaterials()) {
            var shellProfilesTable = sections.get("corotShellProfiles");
            var shellLayers = sections.get("corotShellLayers");
            var tensorPoints = sections.get("corotTensorPoints");
            var seenShells = new java.util.HashSet<Integer>();
            int layerCursor = 0, tensorCursor = 0;
            for (int i = 0; i < shellProfilesTable.count(); i++) {
                int at = shellProfilesTable.offset() + i * SHELL_PROFILE_BYTES;
                int shell = data.getInt(at);
                if (shell < 0 || shell >= shells.count() || !seenShells.add(shell))
                    throw invalid("corot shell profile link");
                int available = data.getInt(at + 4);
                if (available != 0 && available != 6 && available != 7)
                    throw invalid("corot shell profile flags");
                double E = data.getDouble(at + 24), nu = data.getDouble(at + 32),
                        yield = data.getDouble(at + 40), isotropic = data.getDouble(at + 48),
                        kinematic = data.getDouble(at + 56), stored = data.getDouble(at + 64),
                        dissipated = data.getDouble(at + 72), work = data.getDouble(at + 80);
                if (!(E > 0) || !(nu > -1) || !(nu < 0.5) || !(yield > 0)
                        || !(isotropic >= 0) || !(kinematic >= 0)
                        || !nonnegative(stored) || !nonnegative(dissipated) || !nonnegative(work))
                    throw invalid("corot shell profile value");
                shellProfiles++;
                if ((available & 4) != 0) shellMaterial++; else if ((available & 2) != 0) shellRetained++;
                long lFirst = Integer.toUnsignedLong(data.getInt(at + 8));
                long lCount = Integer.toUnsignedLong(data.getInt(at + 12));
                long pFirst = Integer.toUnsignedLong(data.getInt(at + 16));
                long pCount = Integer.toUnsignedLong(data.getInt(at + 20));
                if (lFirst != layerCursor || lFirst + lCount > shellLayers.count()
                        || pFirst != tensorCursor || pFirst + pCount > tensorPoints.count()
                        || lCount == 0 || pCount != (available != 0 ? 4 * lCount : 0))
                    throw invalid("corot shell profile range");
                layerCursor = (int) (lFirst + lCount); tensorCursor = (int) (pFirst + pCount);
                // bsi_corot_tensor_wire.hpp: the open lower sentinel is -1, so a first
                // layer exactly at the Gauss–Lobatto endpoint -0.5 is legal; bounds are
                // the closed interval [-0.5, 0.5] and the order is strictly increasing.
                double previous = -1.0, sumW = 0, sumWP = 0, sumWPP = 0;
                for (long k = lFirst; k < lFirst + lCount; k++) {
                    int lat = shellLayers.offset() + (int) k * SHELL_LAYER_BYTES;
                    double position = data.getDouble(lat), weight = data.getDouble(lat + 8);
                    if (!(position >= -0.5 && position <= 0.5) || !(position > previous)
                            || !(weight > 0) || !Double.isFinite(weight))
                        throw invalid("corot shell layer");
                    previous = position; sumW += weight; sumWP += weight * position;
                    sumWPP += weight * position * position;
                }
                if (Math.abs(sumW - 1) > 1e-12 || Math.abs(sumWP) > 1e-12
                        || Math.abs(sumWPP - 1.0 / 12) > 1e-12) throw invalid("corot shell layer moments");
                for (long k = pFirst; k < pFirst + pCount; k++) {
                    int pat = tensorPoints.offset() + (int) k * TENSOR_POINT_BYTES;
                    checkFinite(data, pat, 59);                       // strain..tangent
                    int loading = data.getInt(pat + 464);
                    if (loading < 0 || loading > 1 || data.getInt(pat + 468) != 0)
                        throw invalid("corot tensor point flags");
                    double accumulated = data.getDouble(pat + 96), dissipation = data.getDouble(pat + 104);
                    if (!nonnegative(accumulated) || !nonnegative(dissipation)
                            || !nonnegative(data.getDouble(pat + 448))
                            || !nonnegative(data.getDouble(pat + 456)))
                        throw invalid("corot tensor point value");
                    double plasticTrace = data.getDouble(pat + 48) + data.getDouble(pat + 56)
                            + data.getDouble(pat + 64);
                    if (Math.abs(plasticTrace) > 2e-10 * Math.max(1, accumulated))
                        throw invalid("corot tensor point plasticity");
                    if (Math.abs(dissipation - yield * accumulated) > 2e-10 * Math.max(1, dissipation))
                        throw invalid("corot tensor point dissipation");
                    double maxStrain = 0;
                    for (int d = 0; d < 6; d++) maxStrain = Math.max(maxStrain, Math.abs(data.getDouble(pat + 8 * d)));
                    if (Math.abs(data.getDouble(pat + 112 + 16)) > 2e-12 * (yield + E * maxStrain))
                        throw invalid("corot tensor point normal stress");
                    if (data.getDouble(pat + 112 + 32) != 0 || data.getDouble(pat + 112 + 40) != 0)
                        throw invalid("corot tensor point shear");
                }
            }
            if (layerCursor != shellLayers.count() || tensorCursor != tensorPoints.count())
                throw invalid("corot shell material cursor");
        }
        return new AnalysisView(verdicts, new Availability(true, memberProfiles, memberMaterial,
                memberRetained, shellProfiles, shellMaterial, shellRetained));
    }

    /** The distinct element-index sets one artifact owns (bsi_pdelta_wire expectedLinks). */
    private static final class Links {
        final java.util.Set<Integer> members = new java.util.HashSet<>();
        final java.util.Set<Integer> shells = new java.util.HashSet<>();
    }

    private static boolean memberLinks(ByteBuffer data, int memberAt, BsiResponse.Section sourceIndices,
                                       BsiResponse.Section sources, long artifact) {
        int first = data.getInt(memberAt + 28), count = data.getInt(memberAt + 32);
        for (int s = 0; s < count; s++) {
            int src = data.getInt(sourceIndices.offset() + 4 * (first + s));
            if (data.getLong(sources.offset() + src * SOURCE_BYTES + 40) == artifact) return true;
        }
        return false;
    }

    private static boolean shellLinks(ByteBuffer data, int shellAt, BsiResponse.Section sourceIndices,
                                      BsiResponse.Section sources, long artifact) {
        return memberLinks(data, shellAt, sourceIndices, sources, artifact);
    }

    private static void checkLaw(ByteBuffer data, int at) {
        int kind = data.getInt(at);
        if (kind < 0 || kind > 2 || data.getInt(at + 4) != 0) throw invalid("corot law kind");
        checkFinite(data, at + 8, 8);
        double e = data.getDouble(at + 8), tension = data.getDouble(at + 16),
                compression = data.getDouble(at + 24), kinematic = data.getDouble(at + 32),
                isotropic = data.getDouble(at + 40), fET = data.getDouble(at + 48),
                fEC = data.getDouble(at + 56), cp = data.getDouble(at + 64);
        if (!(e > 0) || !(tension > 0) || !(compression > 0) || !(kinematic >= 0) || !(isotropic >= 0))
            throw invalid("corot law value");
        if (kind == 0) {
            if (fET != 0 || fEC != 0 || cp != 0) throw invalid("corot elastic law");
        } else {
            if (kinematic != 0 || isotropic != 0 || !(fET > 0) || !(fEC > 0) || cp < 0 || cp > 1
                    || (kind == 2 && cp != 0)) throw invalid("corot inelastic law");
        }
    }

    private static void checkFinite(ByteBuffer data, int offset, int count) {
        for (int i = 0; i < count; i++)
            if (!Double.isFinite(data.getDouble(offset + 8 * i))) throw invalid("nonfinite corot value");
    }

    private static void checkRotation(ByteBuffer data, int offset) {
        double w = data.getDouble(offset), x = data.getDouble(offset + 8),
                y = data.getDouble(offset + 16), z = data.getDouble(offset + 24);
        double norm = w * w + x * x + y * y + z * z;
        if (!Double.isFinite(norm) || Math.abs(norm - 1) > 1e-8) throw invalid("corot rotation");
    }

    private static boolean nonnegative(double v) { return Double.isFinite(v) && v >= 0; }

    /**
     * Best-effort {@code available}-bit census for the lenient solve path: profile sections
     * that are structurally present are counted; anything absent or mis-shaped simply means
     * "not reported" — the strict arc path is where the full census is proven.
     */
    public static Availability availability(Map<String, BsiResponse.Section> sections, byte[] payload) {
        var profiles = sections.get("corotProfiles");
        var shellProfiles = sections.get("corotShellProfiles");
        var data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int memberProfiles = 0, memberMaterial = 0, memberRetained = 0;
        int shells = 0, shellMaterial = 0, shellRetained = 0;
        if (profiles != null && (long) profiles.offset() + profiles.bytes() <= payload.length
                && profiles.bytes() == (long) profiles.count() * PROFILE_BYTES) {
            memberProfiles = profiles.count();
            for (int i = 0; i < profiles.count(); i++) {
                int available = data.getInt(profiles.offset() + i * PROFILE_BYTES + 4);
                if ((available & 4) != 0) memberMaterial++; else if ((available & 2) != 0) memberRetained++;
            }
        }
        if (shellProfiles != null && (long) shellProfiles.offset() + shellProfiles.bytes() <= payload.length
                && shellProfiles.bytes() == (long) shellProfiles.count() * SHELL_PROFILE_BYTES) {
            shells = shellProfiles.count();
            for (int i = 0; i < shells; i++) {
                int available = data.getInt(shellProfiles.offset() + i * SHELL_PROFILE_BYTES + 4);
                if ((available & 4) != 0) shellMaterial++; else if ((available & 2) != 0) shellRetained++;
            }
        }
        boolean reported = memberProfiles > 0 || shells > 0;
        return new Availability(reported, memberProfiles, memberMaterial, memberRetained,
                shells, shellMaterial, shellRetained);
    }

    private static UUID readUuid(String hex) {
        return BsiFractureUuid(hex);
    }

    private static UUID BsiFractureUuid(String hex) {
        try {
            var bytes = java.util.HexFormat.of().parseHex(hex);
            if (bytes.length != 16) throw invalid("invalid id128");
            var buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (IllegalArgumentException bad) { throw invalid("invalid id128"); }
    }

    static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("BSI corot: " + message); }
}
