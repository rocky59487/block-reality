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
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id
                + "\",\"method\":\"bsi.corot.arc.advance\",\"revision\":" + revision
                + ",\"status\":\"advanced\",\"basis\":{\"domain\":\"" + BsiFracture.hex(domain)
                + "\",\"revision\":" + revision + "},\"artifactNamespace\":\"" + BsiFracture.hex(namespace)
                + "\",\"generation\":\"" + generation + "\",\"token\":{\"context\":\""
                + BsiFracture.hex(token.context()) + "\",\"sequence\":\"" + token.sequence()
                + "\"},\"fullyConverged\":true,\"loadFactor\":" + loadFactor
                + ",\"radius\":" + radius + ",\"constraintResidual\":" + residual
                + ",\"sections\":" + directory() + "}";
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
}
