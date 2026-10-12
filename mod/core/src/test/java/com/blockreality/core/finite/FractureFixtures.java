package com.blockreality.core.finite;

import com.blockreality.core.bsi.BsiCorot;
import com.blockreality.core.bsi.BsiCorotFracture;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiRecords;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static com.blockreality.core.finite.FiniteFixtures.GROUND;
import static com.blockreality.core.finite.FiniteFixtures.STEEL;
import static com.blockreality.core.finite.FiniteFixtures.options176;

/**
 * The W4-A wire corpus (doc §7.1): a validator-clean {@code bsi.corot.fracture.prepare}
 * receipt image for the two-block {@link FiniteFixtures} world — GROUND support at
 * (0,63,0), one STEEL member cell at (1,64,1) owned by artifact 1.
 *
 * <p>Three legal variants:
 *
 * <ul>
 *   <li>{@link Variant#RETAINED} — the steel cell stays ({@code group=0}), no events,
 *       one surviving-member failure row, empty archive. Nothing retires.</li>
 *   <li>{@link Variant#REMOVED} — the steel cell breaks ({@code group=1}) under one
 *       event and one decision; the remaining world is the support-only view and
 *       artifact 1 retires exactly.</li>
 *   <li>{@link Variant#PENDING} — like RETAINED plus one pending decision over the
 *       surviving cell: strictly legal on the wire, never commit-qualified.</li>
 * </ul>
 *
 * <p>Mutation legs start from these images and break exactly one rule.
 */
final class FractureFixtures {

    private FractureFixtures() { }

    enum Variant { RETAINED, REMOVED, PENDING }

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

    /** One valid rule against material id 0 (steel, role member). */
    static BsiCorotFracture.Rule rule() {
        return new BsiCorotFracture.Rule(0, 0, 0.0, 0.5, 0.5, 0.5);
    }

    /** The legal receipt payload for {@code variant}. */
    static byte[] payload(UUID bsiDomain, Variant variant) {
        return join(sections(bsiDomain, variant));
    }

    /** All 38 sections of the receipt, in wire order — mutation legs edit these slices. */
    static LinkedHashMap<String, byte[]> sections(UUID bsiDomain, Variant variant) {
        var sections = new LinkedHashMap<String, byte[]>();
        sections.put("physicalTotals", new byte[3 * 80]);
        sections.put("fractureCells", cell(variant == Variant.REMOVED));
        sections.put("fractureFragments", new byte[0]);
        sections.put("fractureParents", new byte[0]);
        if (variant == Variant.REMOVED) {
            var event = ByteBuffer.allocate(BsiCorotFracture.EVENT_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN);
            event.putInt(0).putInt(1).putDouble(1.5).put((byte) 0); // cellFirst, cellCount, util, face
            sections.put("fractureEvents", event.array());
            sections.put("fractureEventCells", new byte[4]);        // cell index 0
        } else {
            sections.put("fractureEvents", new byte[0]);
            sections.put("fractureEventCells", new byte[0]);
        }
        sections.put("fractureMechanism", new byte[0]);
        // The remaining corot analysis view, stamped with the AFTER revision.
        sections.putAll(analysisSections(bsiDomain, variant == Variant.REMOVED));
        var decision = ByteBuffer.allocate(BsiCorotFracture.DECISION_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        if (variant == Variant.REMOVED) {
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
        } else if (variant == Variant.PENDING) {
            decision.putInt(0);            // microStep
            decision.putInt(0);            // cellFirst
            decision.putInt(1);            // cellCount — the surviving group-0 cell
            decision.putInt(0);            // phaseFirst
            decision.putInt(0);            // phaseCount
            decision.putInt(0);            // member
            decision.putInt(0);            // station
            decision.putInt(0);            // reserved
            decision.putDouble(0.5);       // coordinate
            decision.putDouble(1.5);       // utilization
        }
        sections.put("corotDecisions", variant == Variant.RETAINED ? new byte[0] : decision.array());
        sections.put("corotPhases", new byte[0]);
        var failure = ByteBuffer.allocate(BsiCorotFracture.FAILURE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        if (variant != Variant.REMOVED) failure.putInt(0).putInt(-1);   // member 0, no station
        sections.put("corotFailures", variant == Variant.REMOVED ? new byte[0] : failure.array());
        sections.put("corotDecisionCells",
                variant == Variant.RETAINED ? new byte[0] : new byte[4]); // cell index 0
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
        if (!java.util.Arrays.equals(names, ORDER))
            throw new AssertionError("fracture fixture section order drift");
        return sections;
    }

    /**
     * The 17 remaining-analysis sections. RETAINED/PENDING reuse the canonical solved
     * image sliced by its own directory; REMOVED is the support-only view.
     */
    private static LinkedHashMap<String, byte[]> analysisSections(UUID bsiDomain, boolean removed) {
        var out = new LinkedHashMap<String, byte[]>();
        if (!removed) {
            var analysis = CorotArcFixtures.payload(bsiDomain, 1);
            for (int i = 0; i < CorotArcFixtures.ORDER.length; i++) {
                var name = CorotArcFixtures.ORDER[i];
                int at = CorotArcFixtures.offset(name);
                int end = i + 1 < CorotArcFixtures.ORDER.length
                        ? CorotArcFixtures.offset(CorotArcFixtures.ORDER[i + 1]) : analysis.length;
                out.put(name, java.util.Arrays.copyOfRange(analysis, at, end));
            }
            return out;
        }
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
        // artifact 0, disposition 0, reserved 0
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

    /** The exact STEEL cell image (40 B canonical) + partition fields, one 144 B record. */
    private static byte[] cell(boolean removed) {
        var cell = ByteBuffer.allocate(BsiCorotFracture.CELL_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        var worldImage = BsiRecords.encodeBlocks(BsiRecords.canonical(List.of(GROUND, STEEL)));
        cell.put(worldImage, 40, 40);                        // canonical STEEL block image
        cell.putInt(-1);                                     // panelNormal — member cell
        cell.putInt(removed ? 1 : 0);                        // group
        cell.putLong(1);                                     // artifact
        // cellFlags, reserved, physical totals: all zeros
        return cell.array();
    }

    private static byte[] join(Map<String, byte[]> sections) {
        int total = sections.values().stream().mapToInt(s -> s.length).sum();
        var out = new byte[total];
        int p = 0;
        for (var slice : sections.values()) { System.arraycopy(slice, 0, out, p, slice.length); p += slice.length; }
        return out;
    }

    /** The strict-order {@code sections} array matching {@link #payload}. */
    static String directory(UUID bsiDomain, Variant variant) {
        var sections = sections(bsiDomain, variant);
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

    /**
     * The complete {@code prepared} reply envelope for the request bound to
     * {@code requestId} on before-revision {@code 0}.
     */
    static String preparedHeader(UUID requestId, UUID bsiDomain, UUID namespace,
                                 Variant variant) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\""
                + BsiCorotFracture.prepareId(requestId)
                + "\",\"method\":\"bsi.corot.fracture.prepare\",\"revision\":0,"
                + "\"status\":\"prepared\",\"requestId\":\"" + BsiFracture.hex(requestId)
                + "\",\"before\":{\"domain\":\"" + BsiFracture.hex(bsiDomain)
                + "\",\"revision\":0},\"after\":{\"domain\":\"" + BsiFracture.hex(bsiDomain)
                + "\",\"revision\":1},\"artifactNamespace\":\"" + BsiFracture.hex(namespace)
                + "\",\"token\":{\"context\":\"" + BsiFracture.hex(TOKEN.context())
                + "\",\"sequence\":\"" + TOKEN.sequence() + "\"},\"steps\":1,\"flags\":0,"
                + "\"remainingBlocks\":" + (variant == Variant.REMOVED ? 1 : 2)
                + ",\"end\":0,\"corotFlags\":" + (variant == Variant.PENDING ? 1 : 0)
                + ",\"pending\":" + (variant == Variant.PENDING ? 0 : -1)
                + ",\"fullyConverged\":true,"
                + "\"remainingGeneration\":\"" + GENERATION + "\","
                + "\"sections\":" + directory(bsiDomain, variant) + "}";
    }

    /** The {@code bsi.fracture.finish} reply for commit or discard. */
    static String finishReply(String id, long revision, UUID requestId, UUID bsiDomain,
                              UUID namespace, boolean commit) {
        long identityRevision = commit ? 1 : 0;
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id
                + "\",\"method\":\"bsi.fracture.finish\",\"revision\":" + revision
                + ",\"status\":\"" + (commit ? "committed" : "discarded")
                + "\",\"requestId\":\"" + BsiFracture.hex(requestId)
                + "\",\"token\":{\"context\":\"" + BsiFracture.hex(TOKEN.context())
                + "\",\"sequence\":\"" + TOKEN.sequence()
                + "\"},\"identity\":{\"domain\":\"" + BsiFracture.hex(bsiDomain)
                + "\",\"revision\":" + identityRevision
                + ",\"artifactNamespace\":\"" + BsiFracture.hex(namespace) + "\"}}";
    }

    /** The {@code exported} reply the checkpoint export expects at the after revision. */
    static String exportReply(String id, UUID bsiDomain, long revision, int format, int bytes) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id
                + "\",\"method\":\"bsi.corot.checkpoint.export\",\"revision\":" + revision
                + ",\"status\":\"exported\",\"basis\":{\"domain\":\"" + BsiFracture.hex(bsiDomain)
                + "\",\"revision\":" + revision + "},\"format\":" + format
                + ",\"bytes\":" + bytes + "}";
    }
}
