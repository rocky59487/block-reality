package com.blockreality.core.finite;

import com.blockreality.core.bsi.BsiCorot;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiRecords;
import com.blockreality.core.bsi.BsiResponse;
import com.blockreality.core.bsi.BsiFrame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import static com.blockreality.core.finite.FiniteFixtures.*;

/**
 * A canonical, validator-clean {@code bsi.corot.arc.advance} reply image for the
 * {@link FiniteFixtures} world (GROUND support at (0,63,0), one STEEL member block at
 * (1,64,1) owned by artifact 1). Every offset below is the contract's record layout
 * ({@code bsi_corot_*}); mutation tests start from this image and break exactly one rule.
 */
final class CorotArcFixtures {

    private CorotArcFixtures() { }

    /** Canonical section names in wire order (no shell tail: the fixture declares none). */
    static final String[] ORDER = {
            "corotOptions", "corotPhysical", "corotIslands", "corotText", "corotNodes",
            "corotMembers", "corotShells", "corotJoints", "corotSources", "corotSourceIndices",
            "corotArtifacts", "corotArtifactMembers", "corotArtifactShells", "corotProfiles",
            "corotFibers", "corotStations", "corotPoints"};

    static int offset(String name) {
        int cursor = 0;
        for (String n : ORDER) {
            if (n.equals(name)) return cursor;
            cursor += bytes(n);
        }
        throw new IllegalArgumentException(name);
    }

    private static int bytes(String name) {
        return switch (name) {
            case "corotOptions" -> BsiCorot.OPTIONS_BYTES;
            case "corotPhysical" -> 7 * BsiCorot.PHYSICAL_BYTES;
            case "corotIslands" -> BsiCorot.ISLAND_BYTES;
            case "corotText" -> 0;
            case "corotNodes" -> 2 * BsiCorot.NODE_BYTES;
            case "corotMembers" -> BsiCorot.MEMBER_BYTES;
            case "corotShells", "corotJoints", "corotArtifactShells" -> 0;
            case "corotSources" -> 2 * BsiCorot.SOURCE_BYTES;
            case "corotSourceIndices" -> 4;
            case "corotArtifacts" -> BsiCorot.ARTIFACT_BYTES;
            case "corotArtifactMembers" -> 4;
            case "corotProfiles" -> BsiCorot.PROFILE_BYTES;
            case "corotFibers" -> BsiCorot.FIBER_BYTES;
            case "corotStations" -> BsiCorot.STATION_BYTES;
            case "corotPoints" -> BsiCorot.POINT_BYTES;
            default -> throw new IllegalArgumentException(name);
        };
    }

    static int count(String name) {
        return switch (name) {
            case "corotOptions" -> 1;
            case "corotPhysical" -> 7;
            case "corotIslands" -> 1;
            case "corotText" -> 0;
            case "corotNodes" -> 2;
            case "corotMembers" -> 1;
            case "corotShells", "corotJoints", "corotArtifactShells" -> 0;
            case "corotSources" -> 2;
            case "corotSourceIndices", "corotArtifacts", "corotArtifactMembers",
                 "corotProfiles", "corotFibers", "corotStations", "corotPoints" -> 1;
            default -> throw new IllegalArgumentException(name);
        };
    }

    static int length() { return offset("corotPoints") + BsiCorot.POINT_BYTES; }

    /**
     * The canonical payload: one converged island, two nodes, one active solved member
     * whose single source is the STEEL block (artifact 1), one profile chain with one
     * fibre/station/point, and the complete source table covering both world blocks.
     */
    static byte[] payload(UUID bsiDomain, long revision) {
        var out = new byte[length()];
        var b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        // corotOptions: the durable record verbatim — the stamp names this world.
        System.arraycopy(options176(bsiDomain, revision), 0, out, offset("corotOptions"),
                BsiCorot.OPTIONS_BYTES);
        // corotPhysical: 7 finite records, all zeros.
        // corotIslands: one island, status 0 CONVERGED, empty reason — all zeros.
        int nodes = offset("corotNodes");
        for (int i = 0; i < 2; i++) {
            int at = nodes + i * BsiCorot.NODE_BYTES;
            b.putInt(at + 16, 0);                       // island
            b.putInt(at + 20, 1);                       // available == converged
            b.putDouble(at + 72, 1.0);                  // orientation quaternion w
        }
        int m = offset("corotMembers");
        b.putInt(m, 0);                                 // island
        b.putInt(m + 4, 0).putInt(m + 8, 1);            // nodes
        b.putInt(m + 12, 0);                            // material steel
        b.putInt(m + 16, 0);                            // section = declared source sect
        b.putInt(m + 20, 0);                            // profile 0
        b.putInt(m + 24, 7);                            // solved|checkpoint|material
        b.putInt(m + 28, 0).putInt(m + 32, 1);          // source range [0,1)
        b.putInt(m + 36, 1);                            // active
        b.putDouble(m + 40, 1.0);                       // length > 0
        b.putDouble(m + 56, 1.0);                       // frame quaternion w
        int sources = offset("corotSources");
        byte[] worldImage = BsiRecords.encodeBlocks(BsiRecords.canonical(java.util.List.of(GROUND, STEEL)));
        for (int i = 0; i < 2; i++) {
            int at = sources + i * BsiCorot.SOURCE_BYTES;
            System.arraycopy(worldImage, i * 40, out, at, 40);   // the block image verbatim
            b.putLong(at + 40, i == 1 ? 1 : 0);         // artifact: only STEEL is owned
            b.putInt(at + 48, 0);                       // disposition (present, not retired)
            b.putInt(at + 52, 0);                       // reserved
        }
        b.putInt(offset("corotSourceIndices"), 1);      // the member's source is STEEL
        int artifacts = offset("corotArtifacts");
        b.putLong(artifacts, 1);                        // artifact id
        b.putInt(artifacts + 8, 0).putInt(artifacts + 12, 1);    // member range
        b.putInt(artifacts + 16, 0).putInt(artifacts + 20, 0);   // shell range
        b.putInt(offset("corotArtifactMembers"), 0);    // member 0
        int p = offset("corotProfiles");
        b.putInt(p, 0);                                 // member 0
        b.putInt(p + 4, 7);                             // available == member & 7
        b.putInt(p + 8, 0).putInt(p + 12, 1);           // fibre range
        b.putInt(p + 16, 0).putInt(p + 20, 1);          // station range
        b.putInt(p + 24, 0).putInt(p + 28, 1);          // point range = f*s
        b.putInt(p + 32, 1);                            // physical section
        b.putDouble(p + 40, 0.08).putDouble(p + 48, 1.0e-3).putDouble(p + 56, 2.0e-4);
        int f = offset("corotFibers");
        b.putDouble(f + 16, 0.001);                     // fibre area > 0
        b.putInt(f + 32, 0);                            // elastic law
        b.putDouble(f + 40, 2.0e11).putDouble(f + 48, 2.5e8).putDouble(f + 56, 2.5e8);
        int s = offset("corotStations");
        b.putDouble(s, 0.5).putDouble(s + 8, 1.0);      // station in (0,1], weight > 0
        // corotPoints: one all-zero point — loading 0, damage [0,1] ✓.
        return out;
    }

    /** The strict-order {@code sections} array for {@link #payload}. */
    static String directory() {
        var sb = new StringBuilder("[");
        for (int i = 0; i < ORDER.length; i++) {
            if (i != 0) sb.append(',');
            sb.append("{\"name\":\"").append(ORDER[i]).append("\",\"offset\":").append(offset(ORDER[i]))
                    .append(",\"bytes\":").append(bytes(ORDER[i]))
                    .append(",\"count\":").append(count(ORDER[i])).append('}');
        }
        return sb.append(']').toString();
    }

    /**
     * A complete {@code advanced} reply envelope. Scalars land inside the contract bands:
     * radius ∈ (0, request.radius], residual ∈ [0, 1e-10]; the token must differ from the
     * consumed one.
     */
    static String arcReply(String id, long revision, UUID domain, UUID namespace,
                           String generation, BsiFracture.Token token,
                           double loadFactor, double radius, double residual) {
        return arcReply(id, revision, domain, namespace, generation, token,
                loadFactor, radius, residual, directory());
    }

    static String arcReply(String id, long revision, UUID domain, UUID namespace,
                           String generation, BsiFracture.Token token,
                           double loadFactor, double radius, double residual, String sections) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id
                + "\",\"method\":\"bsi.corot.arc.advance\",\"revision\":" + revision
                + ",\"status\":\"advanced\",\"basis\":{\"domain\":\"" + BsiFracture.hex(domain)
                + "\",\"revision\":" + revision + "},\"artifactNamespace\":\"" + BsiFracture.hex(namespace)
                + "\",\"generation\":\"" + generation + "\",\"token\":{\"context\":\""
                + BsiFracture.hex(token.context()) + "\",\"sequence\":\"" + token.sequence()
                + "\"},\"fullyConverged\":true,\"loadFactor\":" + loadFactor
                + ",\"radius\":" + radius + ",\"constraintResidual\":" + residual
                + ",\"sections\":" + sections + "}";
    }

    static BsiResponse respond(String header, byte[] payload) {
        return BsiResponse.of(new BsiFrame.Decoded(0, header, payload));
    }

    /** Reasonable arc controls for the scripted world. */
    static BsiCorot.ArcOptions arcOptions() {
        return new BsiCorot.ArcOptions(0.5, 1e-3, 1.0, 1.0,
                1e-4, 1e-3, 1e-3, 1e-8, 1e-4, 50, 4, 8, 25, 1, 1);
    }

    static final BsiFracture.Token TOKEN_A = new BsiFracture.Token(new UUID(0xA1, 0xA1), "aaaa1111aaaa1111");
    static final BsiFracture.Token TOKEN_B = new BsiFracture.Token(new UUID(0xB2, 0xB2), "bbbb2222bbbb2222");
    static final BsiFracture.Token TOKEN_C = new BsiFracture.Token(new UUID(0xC3, 0xC3), "cccc3333cccc3333");
    static final String GENERATION = "0123456789abcdef";

    // ---------------------------------------------------------------- rich variant
    //
    // A second canonical reply over a larger declared world, shaped to exercise the
    // record rules the single-block fixture cannot express:
    //
    //   - member 0 spans TWO source blocks of the same artifact (distinct-set
    //     artifact membership, bsi_pdelta_wire), member 1 is a second member row so
    //     artifact-member ordering has real content;
    //   - shell 0 carries the ABI10 shell-material tail whose first layer sits exactly
    //     at the Gauss–Lobatto endpoint -0.5;
    //   - three joints cover the rotation contract: kind 0 and kind 1 with unit
    //     quaternions, kind 2 with a legal non-unit finite orientation.

    static final BsiRecords.Block STEEL_B =
            new BsiRecords.Block(2, 64, 1, 0, 0, 1, 0, 0, 1.0, 1.0);
    static final BsiRecords.Block PLATE =
            new BsiRecords.Block(3, 64, 1, 0, 0, 1, 0, 0, 1.0, 1.0);
    static final BsiRecords.Block STEEL_C =
            new BsiRecords.Block(4, 64, 1, 0, 0, 1, 0, 0, 1.0, 1.0);

    /** Canonical section names in wire order, ABI10 shell-material tail included. */
    static final String[] RICH_ORDER = {
            "corotOptions", "corotPhysical", "corotIslands", "corotText", "corotNodes",
            "corotMembers", "corotShells", "corotJoints", "corotSources", "corotSourceIndices",
            "corotArtifacts", "corotArtifactMembers", "corotArtifactShells", "corotProfiles",
            "corotFibers", "corotStations", "corotPoints",
            "corotShellProfiles", "corotShellLayers", "corotTensorPoints"};

    static int richOffset(String name) {
        int cursor = 0;
        for (String n : RICH_ORDER) {
            if (n.equals(name)) return cursor;
            cursor += richBytes(n);
        }
        throw new IllegalArgumentException(name);
    }

    private static int richBytes(String name) {
        return switch (name) {
            case "corotOptions" -> BsiCorot.OPTIONS_BYTES;
            case "corotPhysical" -> 7 * BsiCorot.PHYSICAL_BYTES;
            case "corotIslands" -> BsiCorot.ISLAND_BYTES;
            case "corotText" -> 0;
            case "corotNodes" -> 8 * BsiCorot.NODE_BYTES;
            case "corotMembers" -> 2 * BsiCorot.MEMBER_BYTES;
            case "corotShells" -> BsiCorot.SHELL_BYTES;
            case "corotJoints" -> 3 * BsiCorot.JOINT_BYTES;
            case "corotSources" -> 5 * BsiCorot.SOURCE_BYTES;
            case "corotSourceIndices" -> 4 * 4;
            case "corotArtifacts" -> 2 * BsiCorot.ARTIFACT_BYTES;
            case "corotArtifactMembers" -> 2 * 4;
            case "corotArtifactShells" -> 4;
            case "corotProfiles" -> 2 * BsiCorot.PROFILE_BYTES;
            case "corotFibers" -> 2 * BsiCorot.FIBER_BYTES;
            case "corotStations" -> 2 * BsiCorot.STATION_BYTES;
            case "corotPoints" -> 2 * BsiCorot.POINT_BYTES;
            case "corotShellProfiles" -> BsiCorot.SHELL_PROFILE_BYTES;
            case "corotShellLayers" -> 3 * BsiCorot.SHELL_LAYER_BYTES;
            case "corotTensorPoints" -> 12 * BsiCorot.TENSOR_POINT_BYTES;
            default -> throw new IllegalArgumentException(name);
        };
    }

    static int richCount(String name) {
        return switch (name) {
            case "corotOptions" -> 1;
            case "corotPhysical" -> 7;
            case "corotIslands" -> 1;
            case "corotText" -> 0;
            case "corotNodes" -> 8;
            case "corotMembers" -> 2;
            case "corotShells" -> 1;
            case "corotJoints" -> 3;
            case "corotSources" -> 5;
            case "corotSourceIndices" -> 4;
            case "corotArtifacts" -> 2;
            case "corotArtifactMembers" -> 2;
            case "corotArtifactShells" -> 1;
            case "corotProfiles", "corotFibers", "corotStations", "corotPoints" -> 2;
            case "corotShellProfiles" -> 1;
            case "corotShellLayers" -> 3;
            case "corotTensorPoints" -> 12;
            default -> throw new IllegalArgumentException(name);
        };
    }

    static int richLength() { return richOffset("corotTensorPoints") + 12 * BsiCorot.TENSOR_POINT_BYTES; }

    /** The rich declared world: supports unowned, members owned by artifact 1, plate by 2. */
    static BsiCorot.CorotWorld richWorld() {
        var vocabulary = new com.blockreality.core.bsi.BsiVocabulary(1,
                java.util.Map.of(0, "steel", 1, "ground_rigid"),
                java.util.Map.of(0, "steel_rect_200x400"));
        var owners = java.util.List.of(
                new BsiFracture.Owner(new com.blockreality.api.geom.BlockKey(1, 64, 1), 1),
                new BsiFracture.Owner(new com.blockreality.api.geom.BlockKey(2, 64, 1), 1),
                new BsiFracture.Owner(new com.blockreality.api.geom.BlockKey(3, 64, 1), 2),
                new BsiFracture.Owner(new com.blockreality.api.geom.BlockKey(4, 64, 1), 1));
        return new BsiCorot.CorotWorld(java.util.List.of(GROUND, STEEL, STEEL_B, PLATE, STEEL_C),
                owners, vocabulary, java.util.Map.of(0, 0), true);
    }

    static byte[] richPayload(UUID bsiDomain, long revision) {
        var out = new byte[richLength()];
        var b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        System.arraycopy(options176(bsiDomain, revision), 0, out, richOffset("corotOptions"),
                BsiCorot.OPTIONS_BYTES);
        // One converged island; eight available nodes, each with a unit quaternion.
        int nodes = richOffset("corotNodes");
        for (int i = 0; i < 8; i++) {
            int at = nodes + i * BsiCorot.NODE_BYTES;
            b.putInt(at + 16, 0);
            b.putInt(at + 20, 1);
            b.putDouble(at + 72, 1.0);
        }
        int m = richOffset("corotMembers");
        // member 0: two source blocks (STEEL, STEEL_B) owned by artifact 1.
        b.putInt(m, 0).putInt(m + 4, 0).putInt(m + 8, 1);
        b.putInt(m + 12, 0).putInt(m + 16, 0).putInt(m + 20, 0);
        b.putInt(m + 24, 7);
        b.putInt(m + 28, 0).putInt(m + 32, 2);
        b.putInt(m + 36, 1);
        b.putDouble(m + 40, 1.0).putDouble(m + 56, 1.0);
        // member 1: one source block (STEEL_C), same artifact.
        int m1 = m + BsiCorot.MEMBER_BYTES;
        b.putInt(m1, 0).putInt(m1 + 4, 2).putInt(m1 + 8, 3);
        b.putInt(m1 + 12, 0).putInt(m1 + 16, 0).putInt(m1 + 20, 1);
        b.putInt(m1 + 24, 7);
        b.putInt(m1 + 28, 2).putInt(m1 + 32, 1);
        b.putInt(m1 + 36, 1);
        b.putDouble(m1 + 40, 1.0).putDouble(m1 + 56, 1.0);
        // shell 0: the PLATE block owned by artifact 2, solved quad.
        int sh = richOffset("corotShells");
        b.putInt(sh, 0);
        b.putInt(sh + 4, 4).putInt(sh + 8, 5).putInt(sh + 12, 6).putInt(sh + 16, 7);
        b.putInt(sh + 20, 0);                           // material == source block mat
        b.putInt(sh + 24, 1);                           // solved
        b.putInt(sh + 28, 3).putInt(sh + 32, 1);
        b.putInt(sh + 36, 1);
        b.putDouble(sh + 40, 0.01).putDouble(sh + 48, 1.0);
        b.putDouble(sh + 64, 1.0);                      // frame quaternion w
        int j = richOffset("corotJoints");
        // joint 0: node chart (kind 0), unit quaternions required and present.
        b.putInt(j, 0).putInt(j + 4, 1).putInt(j + 8, 0).putInt(j + 12, 0).putInt(j + 16, 0);
        b.putDouble(j + 48, 1.0).putDouble(j + 80, 1.0);
        // joint 1: member release (kind 1) on member 1 end 1, unit quaternions.
        int j1 = j + BsiCorot.JOINT_BYTES;
        b.putInt(j1, 1).putInt(j1 + 4, 1).putInt(j1 + 8, 1).putInt(j1 + 12, 2).putInt(j1 + 16, 1);
        b.putDouble(j1 + 48, 1.0).putDouble(j1 + 80, 1.0);
        // joint 2: kind 2 — a non-unit finite orientation is legal (all zeros here).
        int j2 = j + 2 * BsiCorot.JOINT_BYTES;
        b.putInt(j2, 2).putInt(j2 + 4, 1).putInt(j2 + 8, 0).putInt(j2 + 12, 4).putInt(j2 + 16, 0);
        int sources = richOffset("corotSources");
        byte[] worldImage = BsiRecords.encodeBlocks(
                BsiRecords.canonical(java.util.List.of(GROUND, STEEL, STEEL_B, PLATE, STEEL_C)));
        long[] sourceArtifacts = {0, 1, 1, 2, 1};
        for (int i = 0; i < 5; i++) {
            int at = sources + i * BsiCorot.SOURCE_BYTES;
            System.arraycopy(worldImage, i * 40, out, at, 40);
            b.putLong(at + 40, sourceArtifacts[i]);
            b.putInt(at + 48, 0).putInt(at + 52, 0);
        }
        int si = richOffset("corotSourceIndices");
        b.putInt(si, 1).putInt(si + 4, 2).putInt(si + 8, 4).putInt(si + 12, 3);
        int a = richOffset("corotArtifacts");
        b.putLong(a, 1);                                        // artifact 1
        b.putInt(a + 8, 0).putInt(a + 12, 2);                   // members [0,2)
        b.putInt(a + 16, 0).putInt(a + 20, 0);                  // no shells
        int a2 = a + BsiCorot.ARTIFACT_BYTES;
        b.putLong(a2, 2);                                       // artifact 2
        b.putInt(a2 + 8, 2).putInt(a2 + 12, 0);
        b.putInt(a2 + 16, 0).putInt(a2 + 20, 1);                // shells [0,1)
        b.putInt(richOffset("corotArtifactMembers"), 0);
        b.putInt(richOffset("corotArtifactMembers") + 4, 1);
        b.putInt(richOffset("corotArtifactShells"), 0);
        int p = richOffset("corotProfiles");
        for (int i = 0; i < 2; i++) {
            int at = p + i * BsiCorot.PROFILE_BYTES;
            b.putInt(at, i).putInt(at + 4, 7);
            b.putInt(at + 8, i).putInt(at + 12, 1);             // fibre range
            b.putInt(at + 16, i).putInt(at + 20, 1);            // station range
            b.putInt(at + 24, i).putInt(at + 28, 1);            // point range
            b.putInt(at + 32, 1);
            b.putDouble(at + 40, 0.08).putDouble(at + 48, 1.0e-3).putDouble(at + 56, 2.0e-4);
        }
        int f = richOffset("corotFibers");
        for (int i = 0; i < 2; i++) {
            int at = f + i * BsiCorot.FIBER_BYTES;
            b.putDouble(at + 16, 0.001);
            b.putInt(at + 32, 0);
            b.putDouble(at + 40, 2.0e11).putDouble(at + 48, 2.5e8).putDouble(at + 56, 2.5e8);
        }
        int s = richOffset("corotStations");
        for (int i = 0; i < 2; i++)
            b.putDouble(s + i * BsiCorot.STATION_BYTES, 0.5)
                    .putDouble(s + i * BsiCorot.STATION_BYTES + 8, 1.0);
        // corotPoints: two all-zero points.
        // The ABI10 shell-material tail: one profile on shell 0 with the exact
        // Gauss–Lobatto layer positions {-0.5, 0, 0.5} and weights {1/6, 4/6, 1/6}.
        int sp = richOffset("corotShellProfiles");
        b.putInt(sp, 0).putInt(sp + 4, 7);
        b.putInt(sp + 8, 0).putInt(sp + 12, 3);                 // layers [0,3)
        b.putInt(sp + 16, 0).putInt(sp + 20, 12);               // points [0,12) = 4*3
        b.putDouble(sp + 24, 2.0e11).putDouble(sp + 32, 0.3).putDouble(sp + 40, 2.5e8);
        int sl = richOffset("corotShellLayers");
        double[] positions = {-0.5, 0.0, 0.5}, weights = {1.0 / 6, 4.0 / 6, 1.0 / 6};
        for (int i = 0; i < 3; i++)
            b.putDouble(sl + i * BsiCorot.SHELL_LAYER_BYTES, positions[i])
                    .putDouble(sl + i * BsiCorot.SHELL_LAYER_BYTES + 8, weights[i]);
        // corotTensorPoints: twelve all-zero points.
        return out;
    }

    static String richDirectory() {
        var sb = new StringBuilder("[");
        for (int i = 0; i < RICH_ORDER.length; i++) {
            if (i != 0) sb.append(',');
            sb.append("{\"name\":\"").append(RICH_ORDER[i]).append("\",\"offset\":")
                    .append(richOffset(RICH_ORDER[i]))
                    .append(",\"bytes\":").append(richBytes(RICH_ORDER[i]))
                    .append(",\"count\":").append(richCount(RICH_ORDER[i])).append('}');
        }
        return sb.append(']').toString();
    }
}
