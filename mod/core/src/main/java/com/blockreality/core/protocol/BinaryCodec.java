package com.blockreality.core.protocol;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.EndForces;
import com.blockreality.api.EngineCatalogue;
import com.blockreality.api.Fibre;
import com.blockreality.api.GoverningFibre;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ShellFieldSpec;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.StressFieldSpec;
import com.blockreality.api.StressStation;
import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.geom.Vec3d;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The binary wire (shm layout 1): little-endian IEEE-754, no text, no parse.
 *
 * <p>This is the SAME conversation as {@link ProtocolCodec}, over a different medium.
 * The layout mirrors the sidecar's {@code encodeShmReply} field for field, and
 * {@code verify.py}'s T-gates hold the two transports to bit-identical results, so a
 * drift between this file and the C++ encoder cannot survive a verification run.
 *
 * <p>Strings never cross this wire. Materials and section/plate tokens travel as
 * indices into the ordered lists the JSON hello announced (sections first, then
 * plates), which both ends hold; the governing fibre travels as an enum ordinal.
 *
 * <p>Fibre directions and offsets are not transmitted either: they are {@code ±ay},
 * {@code ±az}, {@code cz} and {@code cy} from the member's own field spec, reassembled
 * here exactly as a JSON client would have read them off the wire.
 *
 * <p>Decoding is defensive in the same sense {@link ProtocolCodec#decodeSolve} is
 * total: a truncated or corrupt region yields a failed {@link AnalysisResult}, never
 * an exception into the caller and never a half-filled result. And it is framed (#27):
 * the doorbell's byte count — not the mapping capacity — bounds every read, and the
 * frame must be consumed exactly. Decoding to "wherever the reads happen to stop"
 * would let a stale previous reply supply the tail of a truncated new one.
 */
public final class BinaryCodec {

    /** Request magic "BRQ1". */
    public static final int REQ_MAGIC = 0x31515242;
    /** Reply magic "BRP1". */
    public static final int RESP_MAGIC = 0x31505242;

    private static final String[] FIBRE_NAMES =
            { "NONE", "CRUSH", "TENSION", "SHEAR", "BENDING", "TORSION", "SHELL_VM" };

    // Fixed byte footprints of the reply records, used to refuse an implausible count
    // BEFORE allocating for it: a hostile count field costs at most one comparison, not
    // a ten-million-slot ArrayList. Members and stations quote their fixed portion
    // (their variable parts are guarded by their own inner counts); shells are entirely
    // fixed-size.
    //
    // MEMBER:  id/mat/tok (12) + lengthMm/dc (16) + fibre/station (8) + end forces
    //          (2×48) + frame vectors (4×24) + A..wz (56) + two counts (8)      = 292
    // STATION: x (8) + world (24) + 4 fibre sigmas (32) + tens/comp/tau (24)
    //          + naY/naZ (16)                                                   = 104
    // SHELL:   id/mat/tok (12) + t/dc/dcRaw (24) + flags/corner (8) + blocks (48)
    //          + world (96) + ex/ey/n (72) + N/M (48) + Q (16) + Mc (96)
    //          + McRaw (96) + vmTop/vmBot (16)                                  = 532
    private static final int MEMBER_MIN_BYTES = 292;
    private static final int STATION_BYTES = 104;
    private static final int SHELL_BYTES = 532;
    private static final int BLOCK_BYTES = 12;

    private BinaryCodec() { }

    // ------------------------------------------------------------------ encode
    /**
     * Exact request size, for growing the region before encoding. Long on purpose
     * (#32): the arithmetic must not be able to wrap for any list a caller can build,
     * because a wrapped size that passes the region check would overflow the encode.
     */
    public static long requestBytes(SolveRequest req) {
        return 4L + 8 + 4 + 4 + req.blocks().size() * 24L + 4 + req.loads().size() * 60L;
    }

    /**
     * Encodes the request at the buffer's position 0. The buffer must be little-endian
     * and at least {@link #requestBytes} long; the caller sized it, so overflow here is
     * a bug, not an input.
     *
     * @return the number of bytes written — the doorbell's {@code bytes} field, which
     *         the sidecar uses as the frame boundary (#27) — or {@code -1} if a material
     *         or token is not in the catalogue. In that case the request cannot be
     *         expressed on the binary wire, and the caller falls back to JSON, whose
     *         own fail-closed validation then names the offending token.
     */
    public static int encodeSolve(SolveRequest req, EngineCatalogue cat, ByteBuffer buf) {
        List<String> mats = cat.materials();
        List<String> toks = tokenTable(cat);

        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.clear();
        buf.putInt(REQ_MAGIC);
        long rev = req.revision().value();
        buf.putInt((int) (rev & 0xffffffffL));
        buf.putInt((int) ((rev >>> 32) & 0xffffffffL));
        buf.putInt(1);   // flags bit0: buckling on, matching the JSON default
        buf.putInt(req.blocks().size());
        for (SolveRequest.Block b : req.blocks()) {
            int mat = mats.indexOf(b.material());
            int tok = toks.indexOf(b.section());
            if (mat < 0 || tok < 0) return -1;
            buf.putInt(b.pos().x()).putInt(b.pos().y()).putInt(b.pos().z());
            buf.putInt(mat).putInt(tok);
            buf.putInt(b.support() ? 1 : 0);
        }
        buf.putInt(req.loads().size());
        for (SolveRequest.PointLoad l : req.loads()) {
            buf.putInt(l.at().x()).putInt(l.at().y()).putInt(l.at().z());
            buf.putDouble(l.fx()).putDouble(l.fy()).putDouble(l.fz());
            buf.putDouble(l.mx()).putDouble(l.my()).putDouble(l.mz());
        }
        return buf.position();
    }

    // ------------------------------------------------------------------ decode
    /** Sections first, then plates — the combined token index space of the wire. */
    private static List<String> tokenTable(EngineCatalogue cat) {
        List<String> t = new ArrayList<>(cat.sections());
        t.addAll(cat.plates());
        return t;
    }

    /**
     * @param frameBytes the reply length the doorbell announced. Every read stays
     *                   inside it and it must be consumed exactly; the caller has
     *                   already checked {@code 0 < frameBytes <= region size}.
     */
    public static AnalysisResult decodeSolve(ByteBuffer raw, int frameBytes,
                                             WorldRevision expected, EngineCatalogue cat) {
        try {
            return decode(raw, frameBytes, expected, cat);
        } catch (RuntimeException e) {
            // Truncation, a bad index, a hostile count — the region did not contain
            // what the doorbell promised. Same contract as the JSON codec: every
            // reachable input maps to some result.
            return AnalysisResult.failed(expected, "unreadable shm reply: " + e.getMessage());
        }
    }

    private static AnalysisResult decode(ByteBuffer raw, int frameBytes,
                                         WorldRevision expected, EngineCatalogue cat) {
        ByteBuffer b = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        b.clear();
        // The frame IS the reply. Reads past it underflow into the catch above rather
        // than into whatever the previous, larger reply left behind in the region.
        b.limit(frameBytes);

        if (b.getInt() != RESP_MAGIC) {
            return AnalysisResult.failed(expected, "shm reply: bad magic");
        }
        long rev = (b.getInt() & 0xffffffffL) | ((long) b.getInt() << 32);
        if (rev != expected.value()) {
            return AnalysisResult.failed(expected,
                    "revision mismatch: asked for " + expected.value() + ", got " + rev);
        }
        if (b.getInt() != 1) {
            // A failed solve errors on the doorbell and never writes the region, so a
            // non-ok flag here means the two sides disagree about the conversation.
            return AnalysisResult.failed(expected, "shm reply: region carries a non-ok frame");
        }

        List<String> mats = cat.materials();
        List<String> toks = tokenTable(cat);
        // The boundary between beam sections and plates inside the shared token space.
        // The wire does not carry the role, so the index range IS the role check.
        int nSections = cat.sections().size();

        boolean singular = wireBool(b.getInt(), "singular");
        int islands = nonNegative(b.getInt(), "islands");
        int singularIslands = nonNegative(b.getInt(), "singularIslands");
        String diagnostic = str(b);
        String note = str(b);
        if (diagnostic.isEmpty()) diagnostic = note;
        // applied[3] + reaction[3] are the cross-check inputs; the wire quotes the
        // residual they produced, which is the number the JSON path exposes too. They
        // are skipped WITHOUT a finite check deliberately: the JSON decoder does not
        // consume them either, and the two transports must accept and reject the same
        // solves — a check only one of them applies would make them disagree.
        b.position(b.position() + 6 * 8);
        double residual = f64(b, "residual");
        double maxDC = f64(b, "maxDC");
        int governing = b.getInt();
        int gk = b.getInt();
        if (gk != 0 && gk != 1 && gk != 2) {
            // An unknown kind cannot be mapped to "" and shipped: the governing id
            // would then point into a list this build cannot identify.
            return AnalysisResult.failed(expected, "shm reply: unknown governingKind " + gk);
        }
        String governingKind = gk == 1 ? "member" : gk == 2 ? "shell" : "";
        double bucklingFactor = f64(b, "bucklingFactor");
        if (bucklingFactor < 0) {
            return AnalysisResult.failed(expected, "shm reply: bucklingFactor must be >= 0");
        }
        b.getInt();   // nodes: not consumed by the Java side today
        b.getInt();   // dof

        int nMembers = guardedCount(b, MEMBER_MIN_BYTES, "member");
        List<MemberSnapshot> members = new ArrayList<>(nMembers);
        for (int k = 0; k < nMembers; k++) {
            members.add(member(b, mats, toks, nSections));
        }

        int nShells = guardedCount(b, SHELL_BYTES, "shell");
        List<ShellSnapshot> shells = new ArrayList<>(nShells);
        for (int k = 0; k < nShells; k++) {
            shells.add(shell(b, mats, toks, nSections));
        }

        int nUn = guardedCount(b, BLOCK_BYTES, "unassigned");
        List<BlockKey> unassigned = new ArrayList<>(nUn);
        for (int k = 0; k < nUn; k++) {
            unassigned.add(new BlockKey(b.getInt(), b.getInt(), b.getInt()));
        }

        // Consumed exactly, or not a reply. Bytes left inside the declared frame mean
        // the two ends disagree about the schema, and a schema disagreement that still
        // parses is the most dangerous kind — every field after the divergence point
        // was read as the wrong thing.
        if (b.position() != b.limit()) {
            return AnalysisResult.failed(expected, "shm reply: frame length mismatch ("
                    + (b.limit() - b.position()) + " bytes left in the frame)");
        }

        return new AnalysisResult(new WorldRevision(rev), true, singular, diagnostic,
                maxDC, governing, governingKind, islands, singularIslands,
                residual, bucklingFactor, members, shells, unassigned);
    }

    private static MemberSnapshot member(ByteBuffer b, List<String> mats, List<String> toks,
                                         int nSections) {
        int id = b.getInt();
        String mat = at(mats, b.getInt(), "material");
        // Role check, not just range check: a member carrying a plate token would be
        // reassembled with a plate's name and a beam's numbers, and every downstream
        // lookup keyed on the token would consult the wrong catalogue entry.
        int tokIdx = b.getInt();
        if (tokIdx < 0 || tokIdx >= nSections) {
            throw new IllegalStateException("member section index " + tokIdx + " is not a beam section");
        }
        String section = toks.get(tokIdx);
        double lengthMm = f64(b, "lengthMm");
        double dc = f64(b, "member dc");
        // Strict ordinal (#28). The old floorMod mapping would read a corrupt or
        // future ordinal as some arbitrary known fibre — a confident wrong label on
        // the exact member the player is being told to fix.
        int fibre = b.getInt();
        if (fibre < 0 || fibre >= FIBRE_NAMES.length) {
            throw new IllegalStateException("unknown fibre ordinal " + fibre);
        }
        String fibreName = FIBRE_NAMES[fibre];
        int governingStation = b.getInt();
        EndForces fi = forces(b);
        EndForces fj = forces(b);
        Vec3d origin = vec(b), ax = vec(b), ay = vec(b), az = vec(b);
        double a = f64(b, "A"), iy = f64(b, "Iy"), iz = f64(b, "Iz");
        double cy = f64(b, "cy"), cz = f64(b, "cz");
        double wy = f64(b, "wy"), wz = f64(b, "wz");

        int nBlocks = guardedCount(b, BLOCK_BYTES, "member block");
        List<BlockKey> blocks = new ArrayList<>(nBlocks);
        for (int k = 0; k < nBlocks; k++) blocks.add(new BlockKey(b.getInt(), b.getInt(), b.getInt()));

        Vec3d dTop = ay, dBot = neg(ay), dPlus = az, dMin = neg(az);
        int nStations = guardedCount(b, STATION_BYTES, "station");
        List<StressStation> stations = new ArrayList<>(nStations);
        for (int k = 0; k < nStations; k++) {
            double x = f64(b, "station x");
            Vec3d world = vec(b);
            double sTop = f64(b, "fibre sigma"), sBot = f64(b, "fibre sigma");
            double sPls = f64(b, "fibre sigma"), sMin = f64(b, "fibre sigma");
            List<Fibre> fibres = List.of(
                    new Fibre("TOP_Y", dTop, cz, sTop),
                    new Fibre("BOT_Y", dBot, cz, sBot),
                    new Fibre("PLUS_Z", dPlus, cy, sPls),
                    new Fibre("MINUS_Z", dMin, cy, sMin));
            double sigmaTens = f64(b, "sigmaTens");
            double sigmaComp = f64(b, "sigmaComp");
            double tau = f64(b, "tau");
            // The ONE place a non-finite double is data rather than corruption: NaN is
            // the wire's "no neutral axis" sentinel, matching the JSON absent key.
            // Infinity is not part of that convention and stays an error.
            Optional<Double> naY = neutralAxis(b, "naY");
            Optional<Double> naZ = neutralAxis(b, "naZ");
            stations.add(new StressStation(x, world, fibres, sigmaTens, sigmaComp, tau, naY, naZ));
        }

        StressFieldSpec field = new StressFieldSpec(origin, ax, ay, az, lengthMm,
                a, iy, iz, cy, cz, wy, wz, fi, fj);
        return new MemberSnapshot(id, mat, section, lengthMm, dc,
                GoverningFibre.fromWire(fibreName), governingStation,
                fi, fj, blocks, stations, Optional.of(field));
    }

    private static ShellSnapshot shell(ByteBuffer b, List<String> mats, List<String> toks,
                                       int nSections) {
        int id = b.getInt();
        String mat = at(mats, b.getInt(), "material");
        // Mirror of the member's role check: a shell must carry a plate token, i.e.
        // an index in the plates half of the shared table.
        int tokIdx = b.getInt();
        if (tokIdx < nSections || tokIdx >= toks.size()) {
            throw new IllegalStateException("shell plate index " + tokIdx + " is not a plate");
        }
        String plate = toks.get(tokIdx);
        double t = f64(b, "t");
        double dc = f64(b, "shell dc");
        double dcRaw = f64(b, "dcRaw");
        int flags = b.getInt();
        // bit0 face, bit1 edge recovery — and nothing else (#28). Ignoring unknown
        // bits would let a future flag change what this facet MEANS while an old
        // client keeps rendering the old meaning with full confidence.
        if ((flags & ~3) != 0) {
            throw new IllegalStateException("unknown shell flags 0x" + Integer.toHexString(flags));
        }
        boolean top = (flags & 1) != 0;
        boolean recovered = (flags & 2) != 0;
        b.getInt();   // governing corner: not consumed by the Java side today

        List<BlockKey> blocks = new ArrayList<>(4);
        for (int k = 0; k < 4; k++) blocks.add(new BlockKey(b.getInt(), b.getInt(), b.getInt()));
        List<Vec3d> corners = new ArrayList<>(4);
        for (int k = 0; k < 4; k++) corners.add(vec(b));
        Vec3d ex = vec(b), ey = vec(b), n = vec(b);
        double nxx = f64(b, "Nxx"), nyy = f64(b, "Nyy"), nxy = f64(b, "Nxy");
        double mxx = f64(b, "Mxx"), myy = f64(b, "Myy"), mxy = f64(b, "Mxy");
        double qx = f64(b, "Qx"), qy = f64(b, "Qy");
        List<ShellFieldSpec.Moments> mc = new ArrayList<>(4);
        for (int k = 0; k < 4; k++) {
            mc.add(new ShellFieldSpec.Moments(f64(b, "Mc"), f64(b, "Mc"), f64(b, "Mc")));
        }
        // McRaw + vmTop/vmBot: documentation of the recovery, not consumed — skipped
        // unchecked for the same transport-equivalence reason as applied/reaction.
        b.position(b.position() + 12 * 8);
        b.position(b.position() + 2 * 8);

        ShellFieldSpec field = new ShellFieldSpec(corners, ex, ey, n, t,
                nxx, nyy, nxy, mxx, myy, mxy, qx, qy, mc);
        return new ShellSnapshot(id, mat, plate, t, dc, dcRaw, top, recovered,
                blocks, Optional.of(field));
    }

    private static EndForces forces(ByteBuffer b) {
        return new EndForces(f64(b, "N"), f64(b, "Vy"), f64(b, "Vz"),
                f64(b, "T"), f64(b, "My"), f64(b, "Mz"));
    }

    private static Vec3d vec(ByteBuffer b) {
        return new Vec3d(f64(b, "vector"), f64(b, "vector"), f64(b, "vector"));
    }

    private static Vec3d neg(Vec3d v) { return new Vec3d(-v.x(), -v.y(), -v.z()); }

    /**
     * Every consumed double must be finite. The engine only writes finite values into
     * these fields, so a NaN or Infinity here is corruption — and a NaN that slips into
     * a D/C or a stress poisons every comparison downstream while looking like data.
     */
    private static double f64(ByteBuffer b, String what) {
        double v = b.getDouble();
        if (!Double.isFinite(v)) throw new IllegalStateException(what + " is not finite");
        return v;
    }

    private static Optional<Double> neutralAxis(ByteBuffer b, String what) {
        double v = b.getDouble();
        if (Double.isNaN(v)) return Optional.empty();
        if (!Double.isFinite(v)) throw new IllegalStateException(what + " is not finite");
        return Optional.of(v);
    }

    private static boolean wireBool(int v, String what) {
        if (v != 0 && v != 1) {
            // "Anything non-zero is true" would read a corrupt word as a confident
            // boolean; a flag that is not exactly 0 or 1 is not a flag.
            throw new IllegalStateException(what + " flag " + v + " is not 0/1");
        }
        return v == 1;
    }

    private static int nonNegative(int v, String what) {
        if (v < 0) throw new IllegalStateException(what + " is negative");
        return v;
    }

    private static String str(ByteBuffer b) {
        int n = b.getInt();
        // Bound BEFORE allocating: the length must fit inside the remaining frame, so
        // a hostile length can cost at most one comparison, never a giant byte[].
        if (n < 0 || n > b.remaining()) {
            throw new IllegalStateException("string length " + n + " exceeds the frame");
        }
        byte[] bytes = new byte[n];
        b.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String at(List<String> table, int idx, String what) {
        if (idx < 0 || idx >= table.size()) {
            throw new IllegalStateException(what + " index " + idx + " out of range");
        }
        return table.get(idx);
    }

    /**
     * Reads a count and refuses it unless {@code count × minBytesPerItem} fits in what
     * is left of the frame. The multiply is in long, so the guard itself cannot wrap.
     * This runs before the list allocation on purpose: the old order allocated first
     * and discovered the truncation while reading, which made the cost of a corrupt
     * count proportional to the count instead of to the data actually present.
     */
    private static int guardedCount(ByteBuffer b, int minBytesPerItem, String what) {
        int n = b.getInt();
        if (n < 0 || n > 10_000_000) {
            throw new IllegalStateException("implausible " + what + " count " + n);
        }
        if ((long) n * minBytesPerItem > b.remaining()) {
            throw new IllegalStateException(what + " count " + n + " cannot fit the frame");
        }
        return n;
    }
}
