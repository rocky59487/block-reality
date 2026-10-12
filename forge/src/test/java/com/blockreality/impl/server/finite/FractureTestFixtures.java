package com.blockreality.impl.server.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.BsiCorot;
import com.blockreality.core.bsi.BsiCorotFracture;
import com.blockreality.core.bsi.BsiFrame;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiRecords;
import com.blockreality.core.bsi.BsiResponse;
import com.blockreality.core.bsi.BsiVocabulary;
import com.blockreality.core.finite.FiniteLane;
import com.blockreality.core.json.JsonValue;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The W4-B Forge-side wire corpus (FINITE_FRACTURE_TRANSACTION §9.8): the same
 * validator-clean {@code bsi.corot.fracture.prepare} image the W4-A core fixtures
 * carry, trimmed to the REMOVED variant — GROUND support at (0,63,0), one STEEL
 * member cell at (1,64,1) owned by artifact 1; the cell breaks under one event and
 * one decision, the remaining world is the support-only view and artifact 1 retires
 * exactly. The scripted {@link ProbeLane} is the same native-shaped peer the core
 * session tests drive, plus calling-thread recording for the private-lane leg.
 */
final class FractureTestFixtures {

    private FractureTestFixtures() { }

    static final String VOCAB_REQUEST = """
            {"version":1,
             "materials":[
               {"name":"steel","role":"member","model":"isotropic","E":2.0e11,"nu":0.3,"rho":7850,
                "allow":{"sigmaC":2.5e8,"sigmaT":2.5e8,"tau":1.45e8},"defaultSection":"steel_rect_200x400"},
               {"name":"ground_rigid","role":"support","supportKind":"fixAll"}],
             "sections":[{"name":"steel_rect_200x400","kind":"rect","p":[0.2,0.4]}]}""";

    static final BsiRecords.Block STEEL =
            new BsiRecords.Block(1, 64, 1, 0, 0, 1, 0, 0, 1.0, 1.0);
    static final BsiRecords.Block GROUND =
            new BsiRecords.Block(0, 63, 0, 1, -1, 0, 0, 0, 1.0, 1.0);
    static final BlockKey STEEL_CELL = new BlockKey(1, 64, 1);
    static final BlockKey GROUND_CELL = new BlockKey(0, 63, 0);

    static final BsiFracture.Token TOKEN =
            new BsiFracture.Token(new UUID(0xF1, 0xF1), "f00df00df00df00d");
    static final String GENERATION = "aabbccddeeff0011";

    /** Canonical receipt order: physical group → remaining analysis → decision/archive group. */
    static final String[] ORDER = {
            "physicalTotals", "fractureCells", "fractureFragments", "fractureParents",
            "fractureEvents", "fractureEventCells", "fractureMechanism",
            "corotOptions", "corotPhysical", "corotIslands", "corotText", "corotNodes",
            "corotMembers", "corotShells", "corotJoints", "corotSources", "corotSourceIndices",
            "corotArtifacts", "corotArtifactMembers", "corotArtifactShells", "corotProfiles",
            "corotFibers", "corotStations", "corotPoints",
            "corotDecisions", "corotPhases", "corotFailures", "corotDecisionCells",
            "corotFractureLoads", "corotArchives", "corotRetiredMembers", "corotRetiredShells",
            "corotRetiredCells", "corotArchiveOwners", "corotRetiredProfiles",
            "corotRetiredFibers", "corotRetiredStations", "corotRetiredPoints"};

    static String vocabReply(String id, long revision) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id + "\",\"method\":\"bsi.vocab.declare\","
                + "\"revision\":" + revision + ",\"status\":\"ok\",\"version\":1,"
                + "\"materials\":[{\"id\":0,\"name\":\"steel\"},{\"id\":1,\"name\":\"ground_rigid\"}],"
                + "\"sections\":[{\"id\":0,\"name\":\"steel_rect_200x400\"}]}";
    }

    static byte[] options176(UUID bsiDomain, long revision) {
        var b = ByteBuffer.allocate(176).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(176).putInt(5);                          // struct_size, flags bit0 selfWeight | bit2 retainLoadPath
        b.putLong(bsiDomain.getMostSignificantBits()).putLong(bsiDomain.getLeastSignificantBits());
        b.putLong(revision);
        b.putDouble(1.0);                                 // loadFactor
        b.putDouble(0).putDouble(-9.81).putDouble(0);     // gravity
        b.putDouble(1e-4).putDouble(1e-3).putDouble(1e-3);// rel/force/moment tolerance
        b.putDouble(1.0).putDouble(1e-6).putDouble(1.0);  // initial/min/max step
        b.putDouble(1e-8).putDouble(1e-4);                // linear/path tolerance
        b.putInt(1 << 20).putInt(0);                      // budgetDof, numThreads (host)
        b.putInt(50).putInt(4).putInt(8).putInt(25).putInt(1); // iterations/attempts/backtracks/linear/restart
        b.putInt(4096).putInt(8).putInt(2).putInt(8);     // point/fiber/radial/angular budgets
        b.putInt(0);                                      // trailing pad
        return b.array();
    }

    /** One valid failure rule against material id 0 (steel, role member). */
    static BsiCorotFracture.Rule rule() {
        return new BsiCorotFracture.Rule(0, 0, 0.0, 0.5, 0.5, 0.5);
    }

    // ---------------------------------------------------------------- REMOVED receipt

    /** The legal REMOVED receipt payload: the steel cell breaks, support view remains. */
    static byte[] payload(UUID bsiDomain) {
        return join(sections(bsiDomain));
    }

    /** All 38 sections of the REMOVED receipt, in wire order. */
    static LinkedHashMap<String, byte[]> sections(UUID bsiDomain) {
        var sections = new LinkedHashMap<String, byte[]>();
        sections.put("physicalTotals", new byte[3 * 80]);
        sections.put("fractureCells", brokenCell());
        sections.put("fractureFragments", new byte[0]);
        sections.put("fractureParents", new byte[0]);
        var event = ByteBuffer.allocate(BsiCorotFracture.EVENT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        event.putInt(0).putInt(1).putDouble(1.5).put((byte) 0); // cellFirst, cellCount, util, face
        sections.put("fractureEvents", event.array());
        sections.put("fractureEventCells", new byte[4]);       // cell index 0
        sections.put("fractureMechanism", new byte[0]);
        sections.putAll(remainingSections(bsiDomain));
        var decision = ByteBuffer.allocate(BsiCorotFracture.DECISION_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        decision.putInt(0);            // microStep
        decision.putInt(0);            // cellFirst
        decision.putInt(1);            // cellCount
        decision.putInt(0);            // phaseFirst
        decision.putInt(0);            // phaseCount
        decision.putInt(0);            // member
        decision.putInt(0);            // station
        decision.putInt(0);            // reserved
        decision.putDouble(0.5);       // coordinate
        decision.putDouble(1.5);       // utilization — echoes the event
        sections.put("corotDecisions", decision.array());
        sections.put("corotPhases", new byte[0]);
        sections.put("corotFailures", new byte[0]);
        sections.put("corotDecisionCells", new byte[4]);       // cell index 0
        sections.put("corotFractureLoads", new byte[0]);
        sections.put("corotArchives", new byte[0]);
        sections.put("corotRetiredMembers", new byte[0]);
        sections.put("corotRetiredShells", new byte[0]);
        sections.put("corotRetiredCells", new byte[0]);
        sections.put("corotArchiveOwners", new byte[0]);
        sections.put("corotRetiredProfiles", new byte[0]);
        sections.put("corotRetiredFibers", new byte[0]);
        sections.put("corotRetiredStations", new byte[0]);
        sections.put("corotRetiredPoints", new byte[0]);
        var names = sections.keySet().toArray(new String[0]);
        if (!Arrays.equals(names, ORDER))
            throw new AssertionError("fracture fixture section order drift");
        return sections;
    }

    /** The 17 remaining-analysis sections: the support-only view stamped with AFTER revision 1. */
    private static LinkedHashMap<String, byte[]> remainingSections(UUID bsiDomain) {
        var out = new LinkedHashMap<String, byte[]>();
        out.put("corotOptions", options176(bsiDomain, 1));
        out.put("corotPhysical", new byte[7 * BsiCorot.PHYSICAL_BYTES]);
        out.put("corotIslands", new byte[0]);
        out.put("corotText", new byte[0]);
        out.put("corotNodes", new byte[0]);
        out.put("corotMembers", new byte[0]);
        out.put("corotShells", new byte[0]);
        out.put("corotJoints", new byte[0]);
        var source = ByteBuffer.allocate(BsiCorot.SOURCE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        var worldImage = BsiRecords.encodeBlocks(BsiRecords.canonical(List.of(GROUND, STEEL)));
        source.put(worldImage, 0, 40);                       // canonical GROUND image
        out.put("corotSources", source.array());
        out.put("corotSourceIndices", new byte[0]);
        out.put("corotArtifacts", new byte[0]);
        out.put("corotArtifactMembers", new byte[0]);
        out.put("corotArtifactShells", new byte[0]);
        out.put("corotProfiles", new byte[0]);
        out.put("corotFibers", new byte[0]);
        out.put("corotStations", new byte[0]);
        out.put("corotPoints", new byte[0]);
        return out;
    }

    /** The STEEL cell image (144 B): canonical block image at offset 40, group=1, artifact 1. */
    private static byte[] brokenCell() {
        var cell = ByteBuffer.allocate(BsiCorotFracture.CELL_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        var worldImage = BsiRecords.encodeBlocks(BsiRecords.canonical(List.of(GROUND, STEEL)));
        cell.put(worldImage, 40, 40);                        // canonical STEEL block image
        cell.putInt(-1);                                     // panelNormal — member cell
        cell.putInt(1);                                      // group — broken
        cell.putLong(1);                                     // artifact
        return cell.array();
    }

    private static byte[] join(Map<String, byte[]> sections) {
        int total = sections.values().stream().mapToInt(s -> s.length).sum();
        var out = new byte[total];
        int p = 0;
        for (var slice : sections.values()) { System.arraycopy(slice, 0, out, p, slice.length); p += slice.length; }
        return out;
    }

    /** The strict-order {@code sections} directory matching {@link #payload}. */
    static String directory(UUID bsiDomain) {
        var sections = sections(bsiDomain);
        var sb = new StringBuilder("[");
        int cursor = 0;
        for (int i = 0; i < ORDER.length; i++) {
            if (i != 0) sb.append(',');
            var slice = sections.get(ORDER[i]);
            sb.append("{\"name\":\"").append(ORDER[i]).append("\",\"offset\":").append(cursor)
                    .append(",\"bytes\":").append(slice.length)
                    .append(",\"count\":").append(slice.length / stride(ORDER[i])).append('}');
            cursor += slice.length;
        }
        return sb.append(']').toString();
    }

    private static int stride(String name) {
        return switch (name) {
            case "physicalTotals" -> BsiCorot.PHYSICAL_BYTES;
            case "fractureCells" -> BsiCorotFracture.CELL_BYTES;
            case "fractureFragments" -> BsiCorotFracture.FRAGMENT_BYTES;
            case "fractureParents" -> BsiCorotFracture.PARENT_BYTES;
            case "fractureEvents" -> BsiCorotFracture.EVENT_BYTES;
            case "fractureEventCells", "corotDecisionCells" -> BsiCorotFracture.EVENT_CELL_BYTES;
            case "fractureMechanism" -> BsiCorotFracture.MECHANISM_BYTES;
            case "corotOptions" -> BsiCorot.OPTIONS_BYTES;
            case "corotPhysical" -> BsiCorot.PHYSICAL_BYTES;
            case "corotIslands" -> BsiCorot.ISLAND_BYTES;
            case "corotText" -> 1;
            case "corotNodes" -> BsiCorot.NODE_BYTES;
            case "corotMembers" -> BsiCorot.MEMBER_BYTES;
            case "corotShells" -> BsiCorot.SHELL_BYTES;
            case "corotJoints" -> BsiCorot.JOINT_BYTES;
            case "corotSources" -> BsiCorot.SOURCE_BYTES;
            case "corotSourceIndices", "corotArtifactMembers", "corotArtifactShells" -> 4;
            case "corotArtifacts" -> BsiCorot.ARTIFACT_BYTES;
            case "corotProfiles" -> BsiCorot.PROFILE_BYTES;
            case "corotFibers" -> BsiCorot.FIBER_BYTES;
            case "corotStations" -> BsiCorot.STATION_BYTES;
            case "corotPoints" -> BsiCorot.POINT_BYTES;
            case "corotDecisions" -> BsiCorotFracture.DECISION_BYTES;
            case "corotPhases" -> BsiCorotFracture.PHASE_BYTES;
            case "corotFailures" -> BsiCorotFracture.FAILURE_BYTES;
            case "corotFractureLoads" -> BsiCorotFracture.FRACTURE_LOAD_BYTES;
            case "corotArchives" -> BsiCorotFracture.ARCHIVE_BYTES;
            case "corotRetiredMembers" -> BsiCorotFracture.RETIRED_MEMBER_BYTES;
            case "corotRetiredShells" -> BsiCorotFracture.RETIRED_SHELL_BYTES;
            case "corotRetiredCells", "corotArchiveOwners" -> BsiCorotFracture.OWNER_BYTES;
            case "corotRetiredProfiles" -> BsiCorot.PROFILE_BYTES;
            case "corotRetiredFibers" -> BsiCorot.FIBER_BYTES;
            case "corotRetiredStations" -> BsiCorot.STATION_BYTES;
            case "corotRetiredPoints" -> BsiCorot.POINT_BYTES;
            default -> throw new IllegalArgumentException(name);
        };
    }

    /** The {@code prepared} reply envelope for {@code requestId} on before-revision 0. */
    static String preparedHeader(UUID requestId, UUID bsiDomain, UUID namespace) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\""
                + BsiCorotFracture.prepareId(requestId)
                + "\",\"method\":\"bsi.corot.fracture.prepare\",\"revision\":0,"
                + "\"status\":\"prepared\",\"requestId\":\"" + BsiFracture.hex(requestId)
                + "\",\"before\":{\"domain\":\"" + BsiFracture.hex(bsiDomain)
                + "\",\"revision\":0},\"after\":{\"domain\":\"" + BsiFracture.hex(bsiDomain)
                + "\",\"revision\":1},\"artifactNamespace\":\"" + BsiFracture.hex(namespace)
                + "\",\"token\":{\"context\":\"" + BsiFracture.hex(TOKEN.context())
                + "\",\"sequence\":\"" + TOKEN.sequence() + "\"},\"steps\":1,\"flags\":0,"
                + "\"remainingBlocks\":1,\"end\":0,\"corotFlags\":0,\"pending\":-1,"
                + "\"fullyConverged\":true,\"remainingGeneration\":\"" + GENERATION + "\","
                + "\"sections\":" + directory(bsiDomain) + "}";
    }

    /** The {@code bsi.fracture.finish} commit reply. */
    static String finishReply(String id, long revision, UUID requestId, UUID bsiDomain,
                              UUID namespace) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id
                + "\",\"method\":\"bsi.fracture.finish\",\"revision\":" + revision
                + ",\"status\":\"committed\",\"requestId\":\"" + BsiFracture.hex(requestId)
                + "\",\"token\":{\"context\":\"" + BsiFracture.hex(TOKEN.context())
                + "\",\"sequence\":\"" + TOKEN.sequence()
                + "\"},\"identity\":{\"domain\":\"" + BsiFracture.hex(bsiDomain)
                + "\",\"revision\":1,\"artifactNamespace\":\"" + BsiFracture.hex(namespace) + "\"}}";
    }

    /** The {@code exported} reply the checkpoint export expects at the after revision. */
    static String exportReply(String id, UUID bsiDomain, long revision, int format, int bytes) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id
                + "\",\"method\":\"bsi.corot.checkpoint.export\",\"revision\":" + revision
                + ",\"status\":\"exported\",\"basis\":{\"domain\":\"" + BsiFracture.hex(bsiDomain)
                + "\",\"revision\":" + revision + "},\"format\":" + format
                + ",\"bytes\":" + bytes + "}";
    }

    /** The {@code ok} reply an identified world declare expects, from domain parts. */
    static String worldOk(String id, com.blockreality.core.finite.FiniteDomainId domain, long revision) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id
                + "\",\"method\":\"bsi.world.declare\",\"revision\":" + revision
                + ",\"status\":\"ok\",\"identity\":{\"domain\":\""
                + BsiFracture.hex(domain.bsiDomain()) + "\",\"revision\":" + revision
                + ",\"artifactNamespace\":\"" + BsiFracture.hex(domain.artifactNamespace()) + "\"}}";
    }

    /** The {@code ok} reply an identified world declare expects. */
    static String worldOk(String id, BsiFracture.World world) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id
                + "\",\"method\":\"bsi.world.declare\",\"revision\":" + world.stamp().revision()
                + ",\"status\":\"ok\",\"identity\":{\"domain\":\""
                + BsiFracture.hex(world.stamp().domain()) + "\",\"revision\":"
                + world.stamp().revision() + ",\"artifactNamespace\":\""
                + BsiFracture.hex(world.artifactNamespace()) + "\"}}";
    }

    // ---------------------------------------------------------------- scripted lane

    /**
     * The scripted native-shaped peer: each {@code request} pops one scripted answer,
     * {@code declareFiniteWorld} speaks the same identified world-declare wire the
     * production engine does, and every call records its calling thread name — the
     * private-lane leg asserts native work never lands on the caller thread.
     */
    static final class ProbeLane implements FiniteLane {
        record Call(String method, String header, byte[] payload) { }
        record Reply(String header, byte[] payload) { }

        final List<Call> calls = new ArrayList<>();
        final List<String> threads = new ArrayList<>();
        final Deque<Reply> script = new ArrayDeque<>();
        /** The scripted engine's declared capabilities — the W4-A core set by default. */
        final Set<String> capabilities = new HashSet<>(List.of(
                "bsi.corot", "bsi.corot.fracture", "bsi.fracture",
                "bsi.corot.fracture.loads", "bsi.corot.shell.materials"));
        private BsiFracture.World pendingWorld;
        int worldSequence;

        void reply(String header) { script.add(new Reply(header, new byte[0])); }
        void reply(String header, byte[] payload) { script.add(new Reply(header, payload)); }

        @Override public BsiResponse request(String headerJson, byte[] payload) {
            threads.add(Thread.currentThread().getName());
            var header = JsonValue.parse(headerJson);
            String method = header.str("method", "");
            calls.add(new Call(method, headerJson, payload == null ? new byte[0] : payload));
            var next = script.poll();
            if (next == null) throw new AssertionError("unscripted native call: " + headerJson);
            return respond(next.header(), next.payload());
        }

        @Override public boolean adoptVocabulary(BsiVocabulary vocabulary) {
            threads.add(Thread.currentThread().getName());
            return true;
        }

        @Override public boolean declareFiniteWorld(BsiFracture.World world) {
            pendingWorld = world;
            String id = "w" + (++worldSequence);
            var response = request(BsiFracture.declare(id, world), world.payload());
            try {
                BsiFracture.checkWorld(response, id, world);
                return true;
            } catch (IllegalArgumentException failure) {
                return false;
            }
        }

        @Override public boolean ready() { threads.add(Thread.currentThread().getName()); return true; }

        @Override public boolean has(String capability) {
            threads.add(Thread.currentThread().getName());
            return capabilities.contains(capability);
        }

        static BsiResponse respond(String header, byte[] payload) {
            return BsiResponse.of(new BsiFrame.Decoded(0, header, payload));
        }
    }
}
