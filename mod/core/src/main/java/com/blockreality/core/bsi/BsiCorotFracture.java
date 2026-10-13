package com.blockreality.core.bsi;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.json.JsonWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The {@code bsi.corot.fracture.prepare}/{@code bsi.fracture.finish} wire pair for the W4-A
 * finite fracture transaction (docs/FINITE_FRACTURE_TRANSACTION.md §2–§3).
 *
 * <p>This class is a strict mirror of the contract validators — never a re-derivation:
 *
 * <ul>
 *   <li>Request: {@code corot_wire::input(..., fracture=true)} reads the payload in the
 *       exact order {@code materials ‖ cellLoads ‖ prescribed ‖ loads ‖ rules ‖
 *       shellLaws ‖ shellLayers} ({@code bsi_corot_input.hpp:43-47}); {@code rules} are
 *       {@code bsi_corot_failure_rule} records and the generalized-load and shell-material
 *       groups are capability-gated before the native call.
 *   <li>Prepared receipt: {@code corot_wire::fracture} ({@code bsi_corot_fracture_wire.hpp})
 *       = the physical fracture group ({@code fracture_wire::receipt}), the decision chain,
 *       the failure/phase chains, the fracture-load echo, the archive group
 *       ({@code archiveValid}), the retired material view ({@code materialValid}), the
 *       optional retired tensor view ({@code tensorMaterial}) and — last — the complete
 *       remaining {@code bsi_corot_view} re-validated by the shared
 *       {@link BsiCorot#viewBody} against the <em>remaining</em> world the receipt itself
 *       derives ({@code candidate.remaining}/{@code candidate.owners}).
 *   <li>Finish: {@code bsi.fracture.finish} is the shared authority; the corot path stores
 *       the same in-memory candidate, so the strict echo applies unchanged.
 * </ul>
 *
 * <p>The prepared candidate lives only in session memory; this decoder keeps the raw
 * response frame as historical evidence and never exposes it as construction authority.
 */
public final class BsiCorotFracture {

    public static final int RULE_BYTES = 40;            // bsi_corot_failure_rule
    public static final int CELL_BYTES = 144;           // bsi_fracture_cell
    public static final int FRAGMENT_BYTES = 96;        // bsi_fracture_fragment
    public static final int PARENT_BYTES = 8;           // int64 artifact id
    public static final int EVENT_BYTES = 24;           // bsi_fracture_event
    public static final int EVENT_CELL_BYTES = 4;       // uint32 cell index
    public static final int MECHANISM_BYTES = 12;       // int32 xyz
    public static final int DECISION_BYTES = 48;        // bsi_corot_decision
    public static final int PHASE_BYTES = 88;           // bsi_corot_phase_failure
    public static final int FAILURE_BYTES = 40;         // bsi_corot_failure
    public static final int FRACTURE_LOAD_BYTES = 72;   // bsi_pdelta_fracture_load
    public static final int ARCHIVE_BYTES = 592;        // bsi_corot_archive
    public static final int RETIRED_MEMBER_BYTES = 1248;// bsi_corot_retired_member
    public static final int RETIRED_SHELL_BYTES = 1624; // bsi_corot_retired_shell
    public static final int OWNER_BYTES = 20;           // bsi_artifact_owner

    public static final int MAX_EVENTS = 4096;          // contract budget bound

    private static final List<String> PHYSICAL_ORDER = List.of("physicalTotals", "fractureCells",
            "fractureFragments", "fractureParents", "fractureEvents", "fractureEventCells",
            "fractureMechanism");
    private static final List<String> ANALYSIS_ORDER = List.of("corotOptions", "corotPhysical",
            "corotIslands", "corotText", "corotNodes", "corotMembers", "corotShells",
            "corotJoints", "corotSources", "corotSourceIndices", "corotArtifacts",
            "corotArtifactMembers", "corotArtifactShells", "corotProfiles", "corotFibers",
            "corotStations", "corotPoints");
    private static final List<String> TENSOR_TAIL = List.of("corotShellProfiles",
            "corotShellLayers", "corotTensorPoints");
    private static final List<String> COROT_GROUP = List.of("corotDecisions", "corotPhases",
            "corotFailures", "corotDecisionCells", "corotFractureLoads", "corotArchives",
            "corotRetiredMembers", "corotRetiredShells", "corotRetiredCells",
            "corotArchiveOwners", "corotRetiredProfiles", "corotRetiredFibers",
            "corotRetiredStations", "corotRetiredPoints");
    private static final List<String> RETIRED_TAIL = List.of("corotRetiredShellProfiles",
            "corotRetiredShellLayers", "corotRetiredTensorPoints");

    private BsiCorotFracture() { }

    // ---------------------------------------------------------------- request

    /**
     * The deterministic wire id of one fracture prepare: the request id bound into the
     * header echo. The request frame becomes a pure function of
     * (source, requestId, rules, budget) — durable evidence the registry can re-derive
     * byte for byte after a restart without any session-local counter.
     */
    public static String prepareId(UUID requestId) {
        if (requestId == null || requestId.equals(new UUID(0, 0))) throw invalid("zero request id");
        return "fx" + BsiFracture.hex(requestId);
    }

    /**
     * One {@code bsi_corot_failure_rule} (40 B): vocabulary material id + constituent id,
     * then the scalar thresholds. Java-side hygiene bounds are the schema-declared ones;
     * the engine owns their mechanical meaning.
     */
    public record Rule(int material, long constituent, double accumulatedPlastic,
                       double damageT, double damageC, double minimumAreaFraction) {
        public Rule {
            if (material < 0 || constituent < 0 || constituent > 0xFFFF_FFFFL
                    || !Double.isFinite(accumulatedPlastic) || accumulatedPlastic < 0
                    || !Double.isFinite(damageT) || damageT < 0 || damageT > 1
                    || !Double.isFinite(damageC) || damageC < 0 || damageC > 1
                    || !Double.isFinite(minimumAreaFraction)
                    || minimumAreaFraction < 0 || minimumAreaFraction > 1)
                throw invalid("invalid corot failure rule");
        }
    }

    /** Encode the rules group; the wire order is the caller's list order, unchanged. */
    public static byte[] encodeRules(List<Rule> rules) {
        Objects.requireNonNull(rules);
        if (rules.size() > (1 << 24)) throw invalid("corot failure rule count");
        var out = ByteBuffer.allocate(rules.size() * RULE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (var r : rules) {
            out.putInt(r.material()).putInt((int) r.constituent())
                    .putDouble(r.accumulatedPlastic()).putDouble(r.damageT())
                    .putDouble(r.damageC()).putDouble(r.minimumAreaFraction());
        }
        return out.array();
    }

    /** Strictly decode a stored rules section; every rule is re-hygiened. */
    public static List<Rule> decodeRules(byte[] section) {
        if (section == null || section.length % RULE_BYTES != 0
                || section.length > (long) RULE_BYTES * (1 << 24)) throw invalid("corot failure rules");
        var data = ByteBuffer.wrap(section).order(ByteOrder.LITTLE_ENDIAN);
        var out = new ArrayList<Rule>(section.length / RULE_BYTES);
        while (data.hasRemaining()) {
            out.add(new Rule(data.getInt(), Integer.toUnsignedLong(data.getInt()),
                    data.getDouble(), data.getDouble(), data.getDouble(), data.getDouble()));
        }
        return List.copyOf(out);
    }

    /**
     * {@code bsi.corot.fracture.prepare} request header. {@code optionsRecord} is the
     * durable source's exact 176 B options image; {@code initialAnalysis} is never sent —
     * the corot private session holds no verifiable live token, so {@code useInitial}
     * stays zero and a response {@code corotFlags&2} is a protocol violation.
     */
    public static String prepare(String id, long revision, byte[] optionsRecord,
                                 int materials, int cellLoads, int prescribed, int loads,
                                 UUID requestId, int rules, int budget,
                                 int shellMaterials, int shellLayers) {
        var o = BsiCorot.options(optionsRecord);
        if (materials < 1 || cellLoads < 0 || prescribed < 0 || loads < 0 || rules < 0
                || shellMaterials < 0 || shellLayers < 0
                || (shellMaterials == 0) != (shellLayers == 0)
                || budget < 0 || budget > MAX_EVENTS) throw invalid("invalid corot fracture counts");
        if (requestId == null || requestId.equals(new UUID(0, 0))) throw invalid("zero request id");
        var w = BsiHeadersBase(id, "bsi.corot.fracture.prepare", revision).key("body").beginObj();
        BsiCorot.options(w, o);
        w.kv("materials", materials).kv("cellLoads", cellLoads)
                .kv("prescribed", prescribed).kv("loads", loads)
                .kv("requestId", BsiFracture.hex(requestId))
                .kv("rules", rules).kv("budget", budget);
        if (shellMaterials != 0 || shellLayers != 0)
            w.kv("shellMaterials", shellMaterials).kv("shellLayers", shellLayers);
        return w.endObj().endObj().done();
    }

    /** {@code bsi.fracture.finish} — the shared authority; identical body for the corot path. */
    public static String finish(String id, long revision, Prepared prepared, boolean commit) {
        var w = BsiHeadersBase(id, "bsi.fracture.finish", revision).key("body").beginObj()
                .key("expected").beginObj()
                .kv("domain", BsiFracture.hex(prepared.before().domain()))
                .kv("revision", prepared.before().revision()).endObj()
                .kv("requestId", BsiFracture.hex(prepared.requestId()))
                .key("token").beginObj()
                .kv("context", BsiFracture.hex(prepared.token().context()))
                .kv("sequence", prepared.token().sequence()).endObj()
                .kv("resultRevision", prepared.after().revision())
                .kv("action", commit ? "commit" : "discard");
        return w.endObj().endObj().done();
    }

    private static JsonWriter BsiHeadersBase(String id, String method, long revision) {
        return BsiHeaders.base(id, method, revision);
    }

    // ---------------------------------------------------------------- response

    /**
     * The durable-world context the receipt is checked against — everything the native
     * {@code Input} had plus the vocabulary views the validators consult. {@code roles}
     * is material id → role ordinal ({@code member=1, panel=2, support=3, monolith=4,
     * nonstructural=0}); {@code lawMaterials} are the material ids named by the declared
     * shell laws; {@code cellLoads} is the source's raw {@code bsi_load} section (64 B
     * each) for the byte-exact fracture-load echo.
     */
    public record FractureWorld(List<BsiRecords.Block> blocks, List<BsiFracture.Owner> owners,
                                BsiVocabulary vocabulary, Map<Integer, Integer> defaultSections,
                                Map<Integer, Integer> roles, byte[] cellLoads, byte[] shellLaws) {
        public FractureWorld {
            blocks = List.copyOf(blocks); owners = List.copyOf(owners);
            vocabulary = Objects.requireNonNull(vocabulary);
            defaultSections = Map.copyOf(defaultSections); roles = Map.copyOf(roles);
            cellLoads = cellLoads == null ? new byte[0] : cellLoads.clone();
            if (shellLaws == null || shellLaws.length % BsiCorot.SHELL_LAW_BYTES != 0)
                throw invalid("invalid shell law section");
            shellLaws = shellLaws.clone();
        }

        /** Material ids named by the declared shell laws ({@code bsi_corot_shell_law.material}). */
        public Set<Integer> lawMaterials() {
            var out = new HashSet<Integer>();
            var data = ByteBuffer.wrap(shellLaws).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < shellLaws.length / BsiCorot.SHELL_LAW_BYTES; i++)
                out.add(data.getInt(i * BsiCorot.SHELL_LAW_BYTES));
            return Set.copyOf(out);
        }

        public boolean shellMaterials() { return shellLaws.length != 0; }
    }

    /** A decision row ({@code bsi_corot_decision}, 48 B) after strict validation. */
    public record Decision(long microStep, long cellFirst, long cellCount, long phaseFirst,
                           long phaseCount, int member, int station, double coordinate,
                           double utilization) { }

    /** A phase row ({@code bsi_corot_phase_failure}, 88 B) after strict validation. */
    public record Phase(long constituent, long flags, long mode, int station, int fiber,
                        double coordinate, double value, double limit, double utilization,
                        double failedArea, double totalArea, double fraction,
                        double requiredFraction) { }

    /** A failure row ({@code bsi_corot_failure}, 40 B), one per remaining member. */
    public record Failure(int member, int station, long flags, long phaseFirst,
                          long phaseCount, double coordinate, double utilization) { }

    /** The archive summary a commit stores durably (its full bytes stay in the frame). */
    public record Archive(long flags, long memberFirst, long memberCount, long shellFirst,
                          long shellCount, long removedFirst, long removedCount,
                          long installedFirst, long installedCount, long historyPoints) { }

    /**
     * The strictly validated {@code prepared} receipt: the physical partition, the derived
     * remaining world, the complete decision/archive record census, the remaining analysis
     * view's availability, and the raw response frame as historical evidence.
     */
    public record Prepared(UUID requestId, BsiFracture.Stamp before, BsiFracture.Stamp after,
                           UUID artifactNamespace, BsiFracture.Token token,
                           int steps, int flags, int remainingBlocks, int end, int corotFlags,
                           int pending, boolean fullyConverged, String remainingGeneration,
                           List<BsiFractureReceipt.Physical> totals,
                           List<BsiFractureReceipt.Cell> cells,
                           List<BsiFractureReceipt.Fragment> fragments,
                           List<BsiFractureReceipt.Event> events,
                           List<BlockKey> mechanism,
                           List<BsiRecords.Block> remaining,
                           List<BsiFracture.Owner> remainingOwners,
                           List<Long> retiredOwnership,
                           List<BsiFracture.Owner> bornBodies,
                           byte[] afterOptions,
                           BsiCorot.Availability availability, List<BsiCorot.Island> islands,
                           int members,
                           List<Decision> decisions, List<Phase> phases, List<Failure> failures,
                           List<Archive> archives,
                           byte[] loadLedger, byte[] archiveRecords,
                           BsiResponse response) {
        public Prepared {
            totals = List.copyOf(totals); cells = List.copyOf(cells);
            fragments = List.copyOf(fragments); events = List.copyOf(events);
            mechanism = List.copyOf(mechanism);
            remaining = List.copyOf(remaining); remainingOwners = List.copyOf(remainingOwners);
            retiredOwnership = List.copyOf(retiredOwnership);
            bornBodies = List.copyOf(bornBodies);
            afterOptions = afterOptions.clone();
            islands = List.copyOf(islands);
            decisions = List.copyOf(decisions); phases = List.copyOf(phases);
            failures = List.copyOf(failures); archives = List.copyOf(archives);
            loadLedger = loadLedger.clone(); archiveRecords = archiveRecords.clone();
            Objects.requireNonNull(response);
        }

        /**
         * The frozen commit qualification (doc §3.8): within capacity, no pending decision,
         * no physical diagnostic flags, shell material history present when required, and
         * the remaining world fully converged. Anything else is a named incompletion —
         * never a commit, never a world write.
         */
        public boolean qualified() {
            return end == 0 && pending == -1 && flags == 0 && (corotFlags & 8) == 0 && fullyConverged;
        }

        /** The complete raw frame — historical evidence, never construction authority. */
        public byte[] frame() {
            byte[] bytes = BsiFrame.encode(response.headerText(), response.payload());
            bytes[2] |= BsiFrame.FLAG_END_OF_RESPONSE;
            return bytes;
        }
    }

    private static List<String> directoryOrder(boolean liveTail, boolean retiredTail) {
        var out = new ArrayList<String>(64);
        out.addAll(PHYSICAL_ORDER);
        out.addAll(ANALYSIS_ORDER);
        if (liveTail) out.addAll(TENSOR_TAIL);
        out.addAll(COROT_GROUP);
        if (retiredTail) out.addAll(RETIRED_TAIL);
        return out;
    }

    private static boolean trioPresent(Map<String, BsiResponse.Section> sections, List<String> trio) {
        boolean any = false, all = true;
        for (String name : trio) { boolean p = sections.containsKey(name); any |= p; all &= p; }
        if (any && !all) throw invalid("partial corot material tail");
        return all;
    }

    /**
     * Decode and fully validate a {@code prepared} reply against the committed world and the
     * request binding. Anything the native validator would refuse is refused here — a strict
     * superset of the physical {@link BsiFractureReceipt} rules, extended by the decision,
     * archive, retired-material and remaining-view groups.
     */
    public static Prepared checkPrepared(BsiResponse response, String id, long revision,
                                         UUID expectedDomain, long expectedRevision,
                                         UUID artifactNamespace, FractureWorld world,
                                         UUID requestId, int budget) {
        if (response == null || response.isError()) throw invalid("corot fracture refused"
                + (response == null ? "" : " code=" + response.code() + " msg=" + response.message()));
        var h = response.header();
        if (!"response".equals(h.str("kind", "")) || !"prepared".equals(response.status())
                || !"bsi.corot.fracture.prepare".equals(h.str("method", ""))
                || !id.equals(h.str("id", ""))
                || !h.isExactInt("bsi") || h.exactI64("bsi") != BsiContract.MAJOR
                || !h.isExactInt("revision") || h.exactI64("revision") != revision)
            throw invalid("corot fracture envelope mismatch");
        if (!requestId.equals(BsiCorot.readUuid(h.str("requestId", ""))))
            throw invalid("corot fracture request binding");
        var before = BsiFracture.readStamp(h.objField("before"));
        var after = BsiFracture.readStamp(h.objField("after"));
        var token = BsiFracture.readToken(h.objField("token"));
        if (!before.domain().equals(expectedDomain) || before.revision() != expectedRevision
                || before.revision() == Long.MAX_VALUE
                || !after.domain().equals(before.domain()) || after.revision() != before.revision() + 1
                || !artifactNamespace.equals(BsiCorot.readUuid(h.str("artifactNamespace", ""))))
            throw invalid("corot fracture world identity");
        if (token.context() == null || token.context().equals(new UUID(0, 0))
                || token.sequence() == null || !token.sequence().matches("[0-9a-f]{16}")
                || token.sequence().equals("0000000000000000"))
            throw invalid("corot fracture token");
        if (!h.isExactInt("steps") || !h.isExactInt("flags") || !h.isExactInt("remainingBlocks")
                || !h.isExactInt("end") || !h.isExactInt("corotFlags") || !h.isExactInt("pending")
                || !h.isBool("fullyConverged"))
            throw invalid("corot fracture header type");
        long steps = h.exactI64("steps"), flags = h.exactI64("flags"),
                remainingBlocks = h.exactI64("remainingBlocks"), end = h.exactI64("end"),
                corotFlags = h.exactI64("corotFlags"), pending = h.exactI64("pending");
        if (steps < 0 || steps > budget + 1L || flags < 0 || flags > 3
                || remainingBlocks < 0 || remainingBlocks > 16777216 || end < 0 || end > 2
                || corotFlags < 0 || corotFlags > 15 || pending < -1 || pending > 4096)
            throw invalid("corot fracture header range");
        // useInitial is never sent: a reused-initial marker is a protocol violation.
        if ((corotFlags & 2) != 0) throw invalid("corot fracture reused-initial flag");
        boolean fullyConverged = h.bool("fullyConverged", false);
        var remainingGeneration = h.str("remainingGeneration", "");
        if (!remainingGeneration.matches("[0-9a-f]{16}")) throw invalid("corot generation");

        byte[] payload = response.payload();
        var sections = response.sections();
        boolean liveTail = trioPresent(sections, TENSOR_TAIL);
        boolean retiredTail = trioPresent(sections, RETIRED_TAIL);
        BsiCorot.checkDirectory(payload, sections, directoryOrder(liveTail, retiredTail));
        var data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

        // ---------------- physical group: fracture_wire::receipt ----------------
        var totalsSection = sections.get("physicalTotals");
        if (totalsSection.count() != 3) throw invalid("corot fracture totals");
        var totals = List.of(physical(data, totalsSection.offset()),
                physical(data, totalsSection.offset() + 80),
                physical(data, totalsSection.offset() + 160));
        var cellsSection = sections.get("fractureCells");
        var fragmentsSection = sections.get("fractureFragments");
        var parentsSection = sections.get("fractureParents");
        var eventsSection = sections.get("fractureEvents");
        var eventCellsSection = sections.get("fractureEventCells");
        var mechanismSection = sections.get("fractureMechanism");
        int nCells = world.owners().size();
        if (cellsSection.count() != nCells || fragmentsSection.count() > nCells
                || mechanismSection.count() > nCells)
            throw invalid("corot fracture source count");
        int nFragments = fragmentsSection.count();

        var orderedBlocks = BsiRecords.canonical(world.blocks());
        byte[] worldImage = BsiRecords.encodeBlocks(orderedBlocks);
        var ownerKeys = new HashSet<BlockKey>();
        for (var owner : world.owners()) {
            if (owner.artifact() <= 0) throw invalid("corot owner identity");
            if (!ownerKeys.add(owner.position())) throw invalid("corot owner duplicate");
        }
        var cells = new ArrayList<BsiFractureReceipt.Cell>(nCells);
        var parentSets = new ArrayList<TreeSet<Long>>(nFragments);
        for (int i = 0; i < nFragments; i++) parentSets.add(new TreeSet<>());
        var remaining = new ArrayList<BsiRecords.Block>(orderedBlocks.size());
        var remainingOwners = new ArrayList<BsiFracture.Owner>(nCells);
        var removedCells = new BitSet(nCells);
        int cellIndex = 0;
        for (int b = 0; b < orderedBlocks.size(); b++) {
            var block = orderedBlocks.get(b);
            Integer role = world.roles().get(block.mat());
            if (role == null) throw invalid("corot block material");
            if (role != 1 && role != 2 && role != 4) { remaining.add(block); continue; }
            if (cellIndex >= nCells) throw invalid("corot fracture cell count");
            var owner = world.owners().get(cellIndex);
            if (!owner.position().equals(BsiFracture.key(block))) throw invalid("corot owner order");
            int at = cellsSection.offset() + cellIndex * CELL_BYTES;
            for (int k = 0; k < 40; k++)
                if (data.get(at + k) != worldImage[b * 40 + k]) throw invalid("corot cell source drift");
            int panelNormal = data.getInt(at + 40);
            long group = Integer.toUnsignedLong(data.getInt(at + 44));
            long artifact = data.getLong(at + 48);
            long cellFlags = Integer.toUnsignedLong(data.getInt(at + 56));
            boolean panel = role == 2;
            if ((panel ? panelNormal < 0 || panelNormal > 2 : panelNormal != -1)
                    || group > nFragments + 1L || artifact != owner.artifact()
                    || cellFlags > 1 || (cellFlags & 1) != 0 && group != 0
                    || data.getInt(at + 60) != 0)
                throw invalid("corot cell partition or ownership");
            var cell = new BsiFractureReceipt.Cell(block, panelNormal, (int) group, artifact,
                    cellFlags != 0, physical(data, at + 64));
            cells.add(cell);
            if (group == 0) { remaining.add(block); remainingOwners.add(owner); }
            else removedCells.set(cellIndex);
            if (group >= 2) parentSets.get((int) group - 2).add(artifact);
            cellIndex++;
        }
        if (cellIndex != nCells || remaining.size() != remainingBlocks)
            throw invalid("corot fracture coverage");
        var parsedFragments = new ArrayList<BsiFractureReceipt.Fragment>(nFragments);
        long parentOffset = 0;
        for (int f = 0; f < nFragments; f++) {
            int at = fragmentsSection.offset() + f * FRAGMENT_BYTES;
            var expected = parentSets.get(f);
            long parentFirst = Integer.toUnsignedLong(data.getInt(at + 80));
            long parentCount = Integer.toUnsignedLong(data.getInt(at + 84));
            long fragmentFlags = Integer.toUnsignedLong(data.getInt(at + 88));
            if (expected.isEmpty() || data.getInt(at + 92) != 0 || fragmentFlags > 7
                    || parentFirst != parentOffset || parentCount != expected.size()
                    || parentOffset + parentCount > parentsSection.count())
                throw invalid("corot fragment parent range");
            for (long parent : expected)
                if (data.getLong(parentsSection.offset() + 8 * (int) parentOffset++) != parent)
                    throw invalid("corot fragment parents");
            parsedFragments.add(new BsiFractureReceipt.Fragment(
                    physical(data, at), List.copyOf(expected), (int) fragmentFlags));
        }
        if (parentOffset != parentsSection.count()) throw invalid("corot fragment parents");

        var parsedEvents = new ArrayList<BsiFractureReceipt.Event>(eventsSection.count());
        var broken = new BitSet(nCells);
        long eventOffset = 0;
        for (int e = 0; e < eventsSection.count(); e++) {
            int at = eventsSection.offset() + e * EVENT_BYTES;
            long cellFirst = Integer.toUnsignedLong(data.getInt(at));
            long cellCount = Integer.toUnsignedLong(data.getInt(at + 4));
            double utilization = data.getDouble(at + 8);
            int face = Byte.toUnsignedInt(data.get(at + 16));
            if (cellFirst != eventOffset || cellCount == 0
                    || eventOffset + cellCount > eventCellsSection.count()
                    || !Double.isFinite(utilization) || utilization < 0 || face > 5)
                throw invalid("corot fracture event");
            for (int k = 17; k < 24; k++) if (data.get(at + k) != 0) throw invalid("corot event reserved");
            var indices = new ArrayList<Integer>((int) cellCount);
            for (long k = 0; k < cellCount; k++) {
                int cell = (int) Integer.toUnsignedLong(
                        data.getInt(eventCellsSection.offset() + 4 * (int) eventOffset++));
                if (cell >= nCells || broken.get(cell) || cells.get(cell).group() != 1)
                    throw invalid("corot event cell coverage");
                broken.set(cell); indices.add(cell);
            }
            parsedEvents.add(new BsiFractureReceipt.Event(indices, utilization, face));
        }
        if (eventOffset != eventCellsSection.count()) throw invalid("corot event cells");
        for (int c = 0; c < nCells; c++)
            if ((cells.get(c).group() == 1) != broken.get(c))
                throw invalid("corot broken source without event");
        var mechanism = new ArrayList<BlockKey>(mechanismSection.count());
        for (int m = 0; m < mechanismSection.count(); m++) {
            int at = mechanismSection.offset() + m * MECHANISM_BYTES;
            var pos = new BlockKey(data.getInt(at), data.getInt(at + 4), data.getInt(at + 8));
            if (!ownerKeys.contains(pos)) throw invalid("corot foreign mechanism cell");
            mechanism.add(pos);
        }

        // ---------------- decision group: corot_wire::fracture ----------------
        var decisionsSection = sections.get("corotDecisions");
        var phasesSection = sections.get("corotPhases");
        var failuresSection = sections.get("corotFailures");
        var decisionCellsSection = sections.get("corotDecisionCells");
        var loadsSection = sections.get("corotFractureLoads");
        int nEvents = eventsSection.count();
        boolean pendingFlag = (corotFlags & 1) != 0;
        if (nEvents > budget || decisionsSection.count() != nEvents + (pendingFlag ? 1 : 0)
                || pending != (pendingFlag ? nEvents : -1))
            throw invalid("corot fracture decision count");
        int requestCellLoads = world.cellLoads().length / 64;
        if (loadsSection.count() != requestCellLoads) throw invalid("corot fracture loads count");
        var decisions = new ArrayList<Decision>(decisionsSection.count());
        long cells2 = 0, phases2 = 0;
        for (int i = 0; i < decisionsSection.count(); i++) {
            int at = decisionsSection.offset() + i * DECISION_BYTES;
            long microStep = Integer.toUnsignedLong(data.getInt(at));
            long cellFirst = Integer.toUnsignedLong(data.getInt(at + 4));
            long cellCount = Integer.toUnsignedLong(data.getInt(at + 8));
            long phaseFirst = Integer.toUnsignedLong(data.getInt(at + 12));
            long phaseCount = Integer.toUnsignedLong(data.getInt(at + 16));
            int member = data.getInt(at + 20), station = data.getInt(at + 24);
            double coordinate = data.getDouble(at + 32), utilization = data.getDouble(at + 40);
            if (microStep != i || member < 0 || station < 0 || data.getInt(at + 28) != 0
                    || !Double.isFinite(coordinate) || coordinate < 0 || coordinate > 1
                    || !BsiCorot.nonnegative(utilization) || utilization < 1
                    || cellFirst != cells2 || cellCount == 0
                    || cells2 + cellCount > decisionCellsSection.count()
                    || phaseFirst != phases2 || phases2 + phaseCount > phasesSection.count())
                throw invalid("corot fracture decision");
            for (long j = 0; j < cellCount; j++) {
                int cell = (int) Integer.toUnsignedLong(
                        data.getInt(decisionCellsSection.offset() + 4 * (int) (cells2 + j)));
                if (cell >= nCells || cells.get(cell).group() != (i < nEvents ? 1 : 0))
                    throw invalid("corot decision cell");
                if (i < nEvents) {
                    var event = parsedEvents.get(i);
                    if (event.cells().size() != cellCount || event.utilization() != utilization
                            || event.cells().get((int) j) != cell)
                        throw invalid("corot decision event link");
                }
            }
            cells2 += cellCount; phases2 += phaseCount;
            decisions.add(new Decision(microStep, cellFirst, cellCount, phaseFirst, phaseCount,
                    member, station, coordinate, utilization));
        }
        if (cells2 != decisionCellsSection.count()) throw invalid("corot decision cells");
        var failures = new ArrayList<Failure>(failuresSection.count());
        for (int i = 0; i < failuresSection.count(); i++) {
            int at = failuresSection.offset() + i * FAILURE_BYTES;
            int member = data.getInt(at), station = data.getInt(at + 4);
            long fflags = Integer.toUnsignedLong(data.getInt(at + 8));
            long phaseFirst = Integer.toUnsignedLong(data.getInt(at + 12));
            long phaseCount = Integer.toUnsignedLong(data.getInt(at + 16));
            double coordinate = data.getDouble(at + 24), utilization = data.getDouble(at + 32);
            if (member != i || station < -1 || fflags > 7 || data.getInt(at + 20) != 0
                    || phaseFirst != phases2 || phases2 + phaseCount > phasesSection.count()
                    || !BsiCorot.nonnegative(coordinate) || coordinate > 1
                    || !BsiCorot.nonnegative(utilization)
                    || (fflags & 4) != 0 && ((fflags & 1) == 0 || station < 0 || utilization < 1))
                throw invalid("corot failure record");
            phases2 += phaseCount;
            failures.add(new Failure(member, station, fflags, phaseFirst, phaseCount,
                    coordinate, utilization));
        }
        if (phases2 != phasesSection.count()) throw invalid("corot phases coverage");
        var phases = new ArrayList<Phase>(phasesSection.count());
        for (int i = 0; i < phasesSection.count(); i++) phases.add(phase(data, phasesSection.offset() + i * PHASE_BYTES));
        for (int i = 0; i < loadsSection.count(); i++) {
            int at = loadsSection.offset() + i * FRACTURE_LOAD_BYTES;
            for (int k = 0; k < 64; k++)
                if (data.get(at + k) != world.cellLoads()[i * 64 + k])
                    throw invalid("corot fracture load echo");
            long cell = Integer.toUnsignedLong(data.getInt(at + 64));
            long group = Integer.toUnsignedLong(data.getInt(at + 68));
            if (cell >= nCells || group != cells.get((int) cell).group()
                    || data.getInt(at) != cells.get((int) cell).source().x()
                    || data.getInt(at + 4) != cells.get((int) cell).source().y()
                    || data.getInt(at + 8) != cells.get((int) cell).source().z())
                throw invalid("corot fracture load link");
        }
        boolean unrepresented = false;
        for (var cell : cells) unrepresented |= cell.unrepresented();
        if (((corotFlags & 4) != 0) != unrepresented) throw invalid("corot unrepresented flag");

        // ---------------- archive group: archiveValid (+ optional retired tensor view) ----------------
        var archives = archiveGroup(data, sections, artifactNamespace, nCells);
        if (retiredTail) retiredTensor(data, sections);

        // ---------------- remaining view: corot_wire::analysis(current=false) ----------------
        var shellsSection = sections.get("corotShells");
        int tensorExpected = 0;
        var tensorShells = new HashSet<Integer>();
        if (world.shellMaterials()) {
            var lawMaterials = world.lawMaterials();
            for (int i = 0; i < shellsSection.count(); i++) {
                int at = shellsSection.offset() + i * BsiCorot.SHELL_BYTES;
                if (data.getInt(at + 36) != 0
                        && lawMaterials.contains(data.getInt(at + 20))) {
                    tensorExpected++; tensorShells.add(i);
                }
            }
            if (!liveTail) {
                if (tensorExpected != 0) throw invalid("corot tensor view missing");
            } else {
                var sp = sections.get("corotShellProfiles");
                if (sp.count() != tensorExpected) throw invalid("corot tensor profile count");
                for (int i = 0; i < sp.count(); i++)
                    if (!tensorShells.remove(data.getInt(sp.offset() + i * BsiCorot.SHELL_PROFILE_BYTES)))
                        throw invalid("corot tensor profile coverage");
            }
        }
        var remainingWorld = new BsiCorot.CorotWorld(remaining, remainingOwners,
                world.vocabulary(), world.defaultSections(), liveTail);
        var view = BsiCorot.viewBody(payload, sections, remainingWorld,
                after.domain(), after.revision(), false, fullyConverged);
        var membersSection = sections.get("corotMembers");
        if (failuresSection.count() != membersSection.count())
            throw invalid("corot failure member order");

        // Retired ownership: committed owner artifacts with every owned cell removed.
        var artifactCells = new TreeMap<Long, int[]>();
        for (int i = 0; i < nCells; i++) {
            long artifact = cells.get(i).artifact();
            var pair = artifactCells.computeIfAbsent(artifact, k -> new int[2]);
            pair[0]++;
            if (cells.get(i).group() != 0) pair[1]++;
        }
        var retired = new ArrayList<Long>();
        for (var e : artifactCells.entrySet()) if (e.getValue()[0] == e.getValue()[1]) retired.add(e.getKey());

        var optionsSection = sections.get("corotOptions");
        byte[] afterOptions = Arrays.copyOfRange(payload, optionsSection.offset(),
                optionsSection.offset() + BsiCorot.OPTIONS_BYTES);

        return new Prepared(requestId, before, after, artifactNamespace, token,
                (int) steps, (int) flags, (int) remainingBlocks, (int) end, (int) corotFlags,
                (int) pending, fullyConverged, remainingGeneration,
                totals, cells, parsedFragments, parsedEvents, mechanism,
                remaining, remainingOwners, retired, bornBodies(archives, data, sections), afterOptions,
                view.availability(), view.islands(), membersSection.count(),
                decisions, phases, failures, archives,
                Arrays.copyOfRange(payload, loadsSection.offset(), loadsSection.offset() + loadsSection.bytes()),
                Arrays.copyOfRange(payload, sections.get("corotArchives").offset(),
                        sections.get("corotArchives").offset() + sections.get("corotArchives").bytes()),
                response);
    }

    /** {@code archiveValid}: archives chain the retired tables and carry only self-consistent stamps. */
    private static List<Archive> archiveGroup(ByteBuffer data,
                                              Map<String, BsiResponse.Section> sections,
                                              UUID artifactNamespace, int nCells) {
        var archivesSection = sections.get("corotArchives");
        var membersSection = sections.get("corotRetiredMembers");
        var shellsSection = sections.get("corotRetiredShells");
        var cellsSection = sections.get("corotRetiredCells");
        var ownersSection = sections.get("corotArchiveOwners");
        var profilesSection = sections.get("corotRetiredProfiles");
        // materialValid over the always-present retired material view.
        retiredMaterial(data, sections);
        var out = new ArrayList<Archive>(archivesSection.count());
        var claimed = new boolean[profilesSection.count()];
        long members = 0, shells = 0, owners = 0, cells = 0;
        for (int i = 0; i < archivesSection.count(); i++) {
            int at = archivesSection.offset() + i * ARCHIVE_BYTES;
            long flags = Integer.toUnsignedLong(data.getInt(at + 80));
            if (flags > 7 || data.getInt(at + 92) != 0) throw invalid("corot archive flags");
            // artifactNamespace echo (u64 hi,lo little-endian).
            if (data.getLong(at + 48) != artifactNamespace.getMostSignificantBits()
                    || data.getLong(at + 56) != artifactNamespace.getLeastSignificantBits())
                throw invalid("corot archive namespace");
            if (data.getLong(at + 24) == 0 && data.getLong(at + 32) == 0)
                throw invalid("corot archive domain");
            if (data.getLong(at + 40) < 0) throw invalid("corot archive revision");
            if ((flags & 1) != 0) {
                for (int k = 0; k < 16; k++)
                    if (data.get(at + k) != data.get(at + 24 + k)) throw invalid("corot archive before domain");
                if (data.getLong(at + 16) > data.getLong(at + 40))
                    throw invalid("corot archive before revision");
            }
            long memberFirst = Integer.toUnsignedLong(data.getInt(at + 84));
            long memberCount = Integer.toUnsignedLong(data.getInt(at + 88));
            long shellFirst = Integer.toUnsignedLong(data.getInt(at + 96));
            long shellCount = Integer.toUnsignedLong(data.getInt(at + 100));
            long removedFirst = Integer.toUnsignedLong(data.getInt(at + 104));
            long removedCount = Integer.toUnsignedLong(data.getInt(at + 108));
            long installedFirst = Integer.toUnsignedLong(data.getInt(at + 112));
            long installedCount = Integer.toUnsignedLong(data.getInt(at + 116));
            if (memberFirst != members || members + memberCount > membersSection.count()
                    || shellFirst != shells || shells + shellCount > shellsSection.count()
                    || removedFirst != owners || owners + removedCount > ownersSection.count())
                throw invalid("corot archive range");
            owners += removedCount;
            if (installedFirst != owners || owners + installedCount > ownersSection.count())
                throw invalid("corot archive installed range");
            owners += installedCount;
            for (int k = 0; k < 8; k++) {
                double e = data.getDouble(at + 128 + 8 * k);
                if (!Double.isFinite(e) || e < 0) throw invalid("corot archive energy");
            }
            for (int k = 0; k < 5; k++) physical(data, at + 192 + 80 * k);
            for (long j = 0; j < memberCount; j++) {
                int mAt = membersSection.offset() + (int) (members + j) * RETIRED_MEMBER_BYTES;
                long archive = Integer.toUnsignedLong(data.getInt(mAt + 44));
                int member = data.getInt(mAt), island = data.getInt(mAt + 4), profile = data.getInt(mAt + 8);
                long cellFirst = Integer.toUnsignedLong(data.getInt(mAt + 48));
                long cellCount = Integer.toUnsignedLong(data.getInt(mAt + 52));
                if (archive != i || member < 0 || island < 0 || data.getInt(mAt + 56) != 0
                        || data.getInt(mAt + 60) != 0      // implicit alignment pad must be zero
                        || cellFirst != cells || cells + cellCount > cellsSection.count())
                    throw invalid("corot retired member link");
                cells += cellCount;
                memberValid(data, mAt + 360);
                BsiCorot.checkFinite(data, mAt + 224, 4);   // referenceMaterial
                BsiCorot.checkFinite(data, mAt + 256, 10);  // referenceSection
                BsiCorot.checkFinite(data, mAt + 336, 3);   // referenceVector
                for (int e = 0; e < 2; e++) {
                    BsiCorot.checkFinite(data, mAt + 64 + 24 * e, 3);   // referenceNodes
                    BsiCorot.checkFinite(data, mAt + 112 + 24 * e, 3);  // position
                    BsiCorot.checkRotation(data, mAt + 160 + 32 * e);   // orientation
                    int jAt = mAt + 832 + e * BsiCorot.JOINT_BYTES;
                    if (data.getInt(jAt + 4) != 0) jointValid(data, jAt);
                }
                if (profile != -1) {
                    if (profile < 0 || profile >= profilesSection.count() || claimed[profile]
                            || data.getInt(profilesSection.offset() + profile * BsiCorot.PROFILE_BYTES) != member)
                        throw invalid("corot retired member profile");
                    claimed[profile] = true;
                }
            }
            for (long j = 0; j < shellCount; j++) {
                int sAt = shellsSection.offset() + (int) (shells + j) * RETIRED_SHELL_BYTES;
                long archive = Integer.toUnsignedLong(data.getInt(sAt + 72));
                int shell = data.getInt(sAt), island = data.getInt(sAt + 4);
                long cellFirst = Integer.toUnsignedLong(data.getInt(sAt + 76));
                long cellCount = Integer.toUnsignedLong(data.getInt(sAt + 80));
                if (archive != i || shell < 0 || island < 0 || data.getInt(sAt + 84) != 0
                        || cellFirst != cells || cells + cellCount > cellsSection.count())
                    throw invalid("corot retired shell link");
                cells += cellCount;
                shellValid(data, sAt + 440);
                BsiCorot.checkFinite(data, sAt + 408, 4);   // referenceMaterial
                for (int e = 0; e < 4; e++) {
                    BsiCorot.checkFinite(data, sAt + 88 + 24 * e, 3);
                    BsiCorot.checkFinite(data, sAt + 184 + 24 * e, 3);
                    BsiCorot.checkRotation(data, sAt + 280 + 32 * e);
                }
            }
            members += memberCount; shells += shellCount;
            out.add(new Archive(flags, memberFirst, memberCount, shellFirst, shellCount,
                    removedFirst, removedCount, installedFirst, installedCount,
                    data.getLong(at + 120)));
        }
        if (members != membersSection.count() || shells != shellsSection.count()
                || owners != ownersSection.count() || cells != cellsSection.count())
            throw invalid("corot archive coverage");
        for (boolean c : claimed) if (!c) throw invalid("corot retired profile unclaimed");
        for (int i = 0; i < cellsSection.count(); i++)
            if (data.getLong(cellsSection.offset() + i * OWNER_BYTES + 12) <= 0)
                throw invalid("corot retired cell artifact");
        for (int i = 0; i < ownersSection.count(); i++)
            if (data.getLong(ownersSection.offset() + i * OWNER_BYTES + 12) <= 0)
                throw invalid("corot archive owner artifact");
        return List.copyOf(out);
    }

    /**
     * The archive {@code corotArchiveOwners} installed rows — the born body artifacts —
     * in archive order. Every row was already range-checked by {@link #archiveGroup}.
     */
    private static List<BsiFracture.Owner> bornBodies(List<Archive> archives, ByteBuffer data,
                                                      Map<String, BsiResponse.Section> sections) {
        var ownersSection = sections.get("corotArchiveOwners");
        var out = new ArrayList<BsiFracture.Owner>();
        for (var archive : archives)
            for (long i = archive.installedFirst(); i < archive.installedFirst() + archive.installedCount(); i++) {
                int at = ownersSection.offset() + (int) i * OWNER_BYTES;
                out.add(new BsiFracture.Owner(new BlockKey(data.getInt(at), data.getInt(at + 4),
                        data.getInt(at + 8)), data.getLong(at + 12)));
            }
        return List.copyOf(out);
    }

    /** {@code materialValid} over the retired material view (member is a bare index ≥ 0). */
    private static void retiredMaterial(ByteBuffer data, Map<String, BsiResponse.Section> sections) {
        var profiles = sections.get("corotRetiredProfiles");
        var fibers = sections.get("corotRetiredFibers");
        var stations = sections.get("corotRetiredStations");
        var points = sections.get("corotRetiredPoints");
        long fiber = 0, station = 0, point = 0;
        for (int i = 0; i < profiles.count(); i++) {
            int at = profiles.offset() + i * BsiCorot.PROFILE_BYTES;
            int member = data.getInt(at);
            long available = Integer.toUnsignedLong(data.getInt(at + 4));
            if (member < 0 || available > 7 || data.getInt(at + 36) != 0) throw invalid("corot retired profile flags");
            long physicalSection = Integer.toUnsignedLong(data.getInt(at + 32));
            long fFirst = Integer.toUnsignedLong(data.getInt(at + 8));
            long fCount = Integer.toUnsignedLong(data.getInt(at + 12));
            long sFirst = Integer.toUnsignedLong(data.getInt(at + 16));
            long sCount = Integer.toUnsignedLong(data.getInt(at + 20));
            long pFirst = Integer.toUnsignedLong(data.getInt(at + 24));
            long pCount = Integer.toUnsignedLong(data.getInt(at + 28));
            double area = data.getDouble(at + 40), iy = data.getDouble(at + 48),
                    iz = data.getDouble(at + 56), stored = data.getDouble(at + 64),
                    dissipated = data.getDouble(at + 72), work = data.getDouble(at + 80);
            if (physicalSection > 1 || !BsiCorot.nonnegative(area) || !BsiCorot.nonnegative(iy)
                    || !BsiCorot.nonnegative(iz) || !BsiCorot.nonnegative(stored)
                    || !BsiCorot.nonnegative(dissipated) || !BsiCorot.nonnegative(work)
                    || fCount == 0 || sCount == 0
                    || fFirst != fiber || fiber + fCount > fibers.count()
                    || sFirst != station || station + sCount > stations.count()
                    || pFirst != point || point + pCount > points.count())
                throw invalid("corot retired profile range");
            if (available != 0) {
                if ((available & 6) != 6 || fCount * sCount != pCount) throw invalid("corot retired profile history");
            } else if (pCount != 0 || stored != 0 || dissipated != 0 || work != 0)
                throw invalid("corot retired profile residue");
            if (physicalSection == 1 && (area <= 0 || iy <= 0 || iz <= 0))
                throw invalid("corot retired profile section");
            double previousStation = -1;
            for (long k = sFirst; k < sFirst + sCount; k++) {
                int sat = stations.offset() + (int) k * BsiCorot.STATION_BYTES;
                double s = data.getDouble(sat), weight = data.getDouble(sat + 8);
                if (!Double.isFinite(s) || s <= previousStation || s > 1
                        || !Double.isFinite(weight) || weight <= 0)
                    throw invalid("corot retired station order");
                previousStation = s;
                BsiCorot.checkFinite(data, sat + 16, 3);
                if (available == 0)
                    for (int d = 0; d < 3; d++)
                        if (data.getDouble(sat + 16 + 8 * d) != 0) throw invalid("corot retired station residue");
            }
            fiber += fCount; station += sCount; point += pCount;
        }
        if (fiber != fibers.count() || station != stations.count() || point != points.count())
            throw invalid("corot retired material cursor");
        for (int i = 0; i < fibers.count(); i++) {
            int at = fibers.offset() + i * BsiCorot.FIBER_BYTES;
            if (data.getInt(at + 28) != 0 || !Double.isFinite(data.getDouble(at))
                    || !Double.isFinite(data.getDouble(at + 8))
                    || !(data.getDouble(at + 16) > 0)) throw invalid("corot retired fiber");
            BsiCorot.checkLaw(data, at + 32);
        }
        for (int i = 0; i < points.count(); i++) {
            int at = points.offset() + i * BsiCorot.POINT_BYTES;
            BsiCorot.checkFinite(data, at, 14);
            int loading = data.getInt(at + 112);
            if (loading < -1 || loading > 1 || data.getInt(at + 116) != 0)
                throw invalid("corot retired point flags");
            for (int k : new int[]{16, 24, 32, 40, 48, 72, 80, 88})
                if (!BsiCorot.nonnegative(data.getDouble(at + k))) throw invalid("corot retired point value");
            if (data.getDouble(at + 96) < 0 || data.getDouble(at + 96) > 1
                    || data.getDouble(at + 104) < 0 || data.getDouble(at + 104) > 1)
                throw invalid("corot retired point damage");
        }
    }

    /** {@code tensorMaterial(retired=true)}: the optional retired shell material view. */
    static void retiredTensor(ByteBuffer data, Map<String, BsiResponse.Section> sections) {
        var profiles = sections.get("corotRetiredShellProfiles");
        var layers = sections.get("corotRetiredShellLayers");
        var points = sections.get("corotRetiredTensorPoints");
        int shellCount = sections.get("corotRetiredShells").count();
        if (profiles.count() > shellCount) throw invalid("corot retired tensor profile count");
        var seen = new HashSet<Integer>();
        long layerCursor = 0, pointCursor = 0;
        for (int i = 0; i < profiles.count(); i++) {
            int at = profiles.offset() + i * BsiCorot.SHELL_PROFILE_BYTES;
            int shell = data.getInt(at);
            if (shell < 0 || shell >= shellCount || !seen.add(shell))
                throw invalid("corot retired tensor profile link");
            long available = Integer.toUnsignedLong(data.getInt(at + 4));
            if (available != 0 && available != 6 && available != 7)
                throw invalid("corot retired tensor profile flags");
            double E = data.getDouble(at + 24), nu = data.getDouble(at + 32),
                    yield = data.getDouble(at + 40), isotropic = data.getDouble(at + 48),
                    kinematic = data.getDouble(at + 56), stored = data.getDouble(at + 64),
                    dissipated = data.getDouble(at + 72), work = data.getDouble(at + 80);
            if (!(E > 0) || !(nu > -1) || !(nu < 0.5) || !(yield > 0)
                    || !(isotropic >= 0) || !(kinematic >= 0)
                    || !BsiCorot.nonnegative(stored) || !BsiCorot.nonnegative(dissipated)
                    || !BsiCorot.nonnegative(work)) throw invalid("corot retired tensor profile value");
            long lFirst = Integer.toUnsignedLong(data.getInt(at + 8));
            long lCount = Integer.toUnsignedLong(data.getInt(at + 12));
            long pFirst = Integer.toUnsignedLong(data.getInt(at + 16));
            long pCount = Integer.toUnsignedLong(data.getInt(at + 20));
            if (lFirst != layerCursor || layerCursor + lCount > layers.count() || lCount == 0
                    || pFirst != pointCursor || pointCursor + pCount > points.count()
                    || pCount != (available != 0 ? 4 * lCount : 0))
                throw invalid("corot retired tensor profile range");
            layerCursor += lCount; pointCursor += pCount;
            double previous = -1.0, sumW = 0, sumWP = 0, sumWPP = 0;
            for (long k = lFirst; k < lFirst + lCount; k++) {
                int lat = layers.offset() + (int) k * BsiCorot.SHELL_LAYER_BYTES;
                double position = data.getDouble(lat), weight = data.getDouble(lat + 8);
                if (!(position >= -0.5 && position <= 0.5) || !(position > previous)
                        || !(weight > 0) || !Double.isFinite(weight))
                    throw invalid("corot retired tensor layer");
                previous = position; sumW += weight; sumWP += weight * position;
                sumWPP += weight * position * position;
            }
            if (Math.abs(sumW - 1) > 1e-12 || Math.abs(sumWP) > 1e-12
                    || Math.abs(sumWPP - 1.0 / 12) > 1e-12)
                throw invalid("corot retired tensor layer moments");
            for (long k = pFirst; k < pFirst + pCount; k++) {
                int pat = points.offset() + (int) k * BsiCorot.TENSOR_POINT_BYTES;
                BsiCorot.checkFinite(data, pat, 59);
                int loading = data.getInt(pat + 464);
                if (loading < 0 || loading > 1 || data.getInt(pat + 468) != 0)
                    throw invalid("corot retired tensor point flags");
                double accumulated = data.getDouble(pat + 96), dissipation = data.getDouble(pat + 104);
                if (!BsiCorot.nonnegative(accumulated) || !BsiCorot.nonnegative(dissipation)
                        || !BsiCorot.nonnegative(data.getDouble(pat + 448))
                        || !BsiCorot.nonnegative(data.getDouble(pat + 456)))
                    throw invalid("corot retired tensor point value");
                double trace = data.getDouble(pat + 48) + data.getDouble(pat + 56) + data.getDouble(pat + 64);
                if (Math.abs(trace) > 2e-10 * Math.max(1, accumulated))
                    throw invalid("corot retired tensor point plasticity");
                if (Math.abs(dissipation - yield * accumulated) > 2e-10 * Math.max(1, dissipation))
                    throw invalid("corot retired tensor point dissipation");
                double maxStrain = 0;
                for (int d = 0; d < 6; d++)
                    maxStrain = Math.max(maxStrain, Math.abs(data.getDouble(pat + 8 * d)));
                if (Math.abs(data.getDouble(pat + 128)) > 2e-12 * (yield + E * maxStrain)
                        || data.getDouble(pat + 144) != 0 || data.getDouble(pat + 152) != 0)
                    throw invalid("corot retired tensor point stress");
            }
        }
        if (layerCursor != layers.count() || pointCursor != points.count())
            throw invalid("corot retired tensor cursor");
    }

    // ---------------------------------------------------------------- record checks

    /** {@code fracture_wire::physical}: all ten f64 finite, mass ≥ 0, inertia diagonal ≥ 0. */
    static BsiFractureReceipt.Physical physical(ByteBuffer b, int at) {
        for (int k = 0; k < 10; k++)
            if (!Double.isFinite(b.getDouble(at + 8 * k))) throw invalid("nonfinite physical record");
        if (b.getDouble(at) < 0 || b.getDouble(at + 32) < 0 || b.getDouble(at + 40) < 0
                || b.getDouble(at + 48) < 0) throw invalid("negative physical record");
        return new BsiFractureReceipt.Physical(b.getDouble(at), b.getDouble(at + 8),
                b.getDouble(at + 16), b.getDouble(at + 24), b.getDouble(at + 32),
                b.getDouble(at + 40), b.getDouble(at + 48), b.getDouble(at + 56),
                b.getDouble(at + 64), b.getDouble(at + 72));
    }

    /** {@code phaseValid}. */
    private static Phase phase(ByteBuffer data, int at) {
        long constituent = Integer.toUnsignedLong(data.getInt(at));
        long flags = Integer.toUnsignedLong(data.getInt(at + 4));
        long mode = Integer.toUnsignedLong(data.getInt(at + 8));
        int station = data.getInt(at + 12), fiber = data.getInt(at + 16);
        double coordinate = data.getDouble(at + 24), value = data.getDouble(at + 32),
                limit = data.getDouble(at + 40), utilization = data.getDouble(at + 48),
                failedArea = data.getDouble(at + 56), totalArea = data.getDouble(at + 64),
                fraction = data.getDouble(at + 72), requiredFraction = data.getDouble(at + 80);
        if (flags > 3 || mode > 3 || station < 0 || fiber < -1 || data.getInt(at + 20) != 0
                || !Double.isFinite(coordinate) || coordinate < 0 || coordinate > 1
                || !BsiCorot.nonnegative(value) || !BsiCorot.nonnegative(limit)
                || !BsiCorot.nonnegative(utilization) || !BsiCorot.nonnegative(failedArea)
                || !Double.isFinite(totalArea) || totalArea <= 0 || failedArea > totalArea
                || !Double.isFinite(fraction) || fraction < 0 || fraction > 1
                || !Double.isFinite(requiredFraction) || requiredFraction <= 0 || requiredFraction > 1
                || (flags & 2) != 0 && (flags & 1) == 0)
            throw invalid("corot phase record");
        return new Phase(constituent, flags, mode, station, fiber, coordinate, value, limit,
                utilization, failedArea, totalArea, fraction, requiredFraction);
    }

    /** {@code memberValid} over a record start offset. */
    static void memberValid(ByteBuffer data, int at) {
        long available = Integer.toUnsignedLong(data.getInt(at + 24));
        long active = Integer.toUnsignedLong(data.getInt(at + 36));
        if (available > 7 || active > 1
                || !BsiCorot.nonnegative(data.getDouble(at + 40))
                || !BsiCorot.nonnegative(data.getDouble(at + 48)))
            throw invalid("corot member record");
        BsiCorot.checkFinite(data, at + 56, 52);            // frame + 4×12 channels
        if ((available & 1) != 0) {
            if (!(data.getDouble(at + 40) > 0)) throw invalid("corot member geometry");
            BsiCorot.checkRotation(data, at + 56);
        }
    }

    /** {@code shellValid} over a record start offset. */
    static void shellValid(ByteBuffer data, int at) {
        long available = Integer.toUnsignedLong(data.getInt(at + 24));
        long active = Integer.toUnsignedLong(data.getInt(at + 36));
        if (available > 3 || active > 1
                || !BsiCorot.nonnegative(data.getDouble(at + 40))
                || !BsiCorot.nonnegative(data.getDouble(at + 48))
                || !BsiCorot.nonnegative(data.getDouble(at + 56)))
            throw invalid("corot shell record");
        BsiCorot.checkFinite(data, at + 64, 140);           // frame..sections
        if ((available & 1) != 0) {
            if (!(data.getDouble(at + 40) > 0) || !(data.getDouble(at + 48) > 0))
                throw invalid("corot shell geometry");
            BsiCorot.checkRotation(data, at + 64);
        }
    }

    /** {@code jointValid} over a record start offset. */
    static void jointValid(ByteBuffer data, int at) {
        long kind = Integer.toUnsignedLong(data.getInt(at));
        long available = Integer.toUnsignedLong(data.getInt(at + 4));
        if (kind > 4 || available > 3 || data.getInt(at + 8) < 0)
            throw invalid("corot joint record");
        BsiCorot.checkFinite(data, at + 24, 23);            // position..action
        if (available != 0 && kind <= 1) {
            BsiCorot.checkRotation(data, at + 48);
            BsiCorot.checkRotation(data, at + 80);
        }
    }

    /**
     * Strict finish echo ({@code bsi_fracture_session.hpp:181-203}): requestId + token echo,
     * identity = {@code after} for commit/replayed, the declared world stamp for discard.
     */
    public static BsiFracture.Finish checkFinish(BsiResponse response, String id, long revision,
                                               Prepared prepared, boolean commit) {
        BsiFracture.checkEnvelope(response, id, "bsi.fracture.finish", revision);
        var h = response.header();
        var identity = h.objField("identity");
        var expected = commit ? prepared.after() : prepared.before();
        if (response.payload().length != 0
                || !prepared.requestId().equals(BsiCorot.readUuid(h.str("requestId", "")))
                || !prepared.token().equals(BsiFracture.readToken(h.objField("token")))
                || !expected.equals(BsiFracture.readStamp(identity))
                || !prepared.artifactNamespace()
                        .equals(BsiCorot.readUuid(identity.str("artifactNamespace", ""))))
            throw invalid("corot fracture finish identity");
        return switch (response.status()) {
            case "committed" -> { if (!commit) throw invalid("unexpected commit"); yield BsiFracture.Finish.COMMITTED; }
            case "replayed" -> { if (!commit) throw invalid("unexpected replay"); yield BsiFracture.Finish.REPLAYED; }
            case "discarded" -> { if (commit) throw invalid("unexpected discard"); yield BsiFracture.Finish.DISCARDED; }
            default -> throw invalid("unknown finish status");
        };
    }

    static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("BSI corot fracture: " + message);
    }
}
