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
 * an exception into the caller and never a half-filled result.
 */
public final class BinaryCodec {

    /** Request magic "BRQ1". */
    public static final int REQ_MAGIC = 0x31515242;
    /** Reply magic "BRP1". */
    public static final int RESP_MAGIC = 0x31505242;

    private static final String[] FIBRE_NAMES =
            { "NONE", "CRUSH", "TENSION", "SHEAR", "BENDING", "TORSION", "SHELL_VM" };

    private BinaryCodec() { }

    // ------------------------------------------------------------------ encode
    /** Worst-case request size, for growing the region before encoding. */
    public static int requestBytes(SolveRequest req) {
        return 4 + 8 + 4 + 4 + req.blocks().size() * 24 + 4 + req.loads().size() * 60;
    }

    /**
     * Encodes the request at the buffer's position 0. The buffer must be little-endian
     * and at least {@link #requestBytes} long; the caller sized it, so overflow here is
     * a bug, not an input.
     *
     * @return false if a material or token is not in the catalogue — the request cannot
     *         be expressed on the binary wire, and the caller falls back to JSON, whose
     *         own fail-closed validation then names the offending token.
     */
    public static boolean encodeSolve(SolveRequest req, EngineCatalogue cat, ByteBuffer buf) {
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
            if (mat < 0 || tok < 0) return false;
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
        return true;
    }

    // ------------------------------------------------------------------ decode
    /** Sections first, then plates — the combined token index space of the wire. */
    private static List<String> tokenTable(EngineCatalogue cat) {
        List<String> t = new ArrayList<>(cat.sections());
        t.addAll(cat.plates());
        return t;
    }

    public static AnalysisResult decodeSolve(ByteBuffer raw, WorldRevision expected, EngineCatalogue cat) {
        try {
            return decode(raw, expected, cat);
        } catch (RuntimeException e) {
            // Truncation, a bad index, a negative count — the region did not contain
            // what the doorbell promised. Same contract as the JSON codec: every
            // reachable input maps to some result.
            return AnalysisResult.failed(expected, "unreadable shm reply: " + e.getMessage());
        }
    }

    private static AnalysisResult decode(ByteBuffer raw, WorldRevision expected, EngineCatalogue cat) {
        ByteBuffer b = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        b.clear();

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

        boolean singular = b.getInt() == 1;
        int islands = b.getInt();
        int singularIslands = b.getInt();
        String diagnostic = str(b);
        String note = str(b);
        if (diagnostic.isEmpty()) diagnostic = note;
        // applied[3] + reaction[3] are the cross-check inputs; the wire quotes the
        // residual they produced, which is the number the JSON path exposes too.
        b.position(b.position() + 6 * 8);
        double residual = b.getDouble();
        double maxDC = b.getDouble();
        int governing = b.getInt();
        int gk = b.getInt();
        String governingKind = gk == 1 ? "member" : gk == 2 ? "shell" : "";
        double bucklingFactor = b.getDouble();
        b.getInt();   // nodes: not consumed by the Java side today
        b.getInt();   // dof

        int nMembers = b.getInt();
        List<MemberSnapshot> members = new ArrayList<>(checkCount(nMembers));
        for (int k = 0; k < nMembers; k++) {
            members.add(member(b, mats, toks));
        }

        int nShells = b.getInt();
        List<ShellSnapshot> shells = new ArrayList<>(checkCount(nShells));
        for (int k = 0; k < nShells; k++) {
            shells.add(shell(b, mats, toks));
        }

        int nUn = b.getInt();
        List<BlockKey> unassigned = new ArrayList<>(checkCount(nUn));
        for (int k = 0; k < nUn; k++) {
            unassigned.add(new BlockKey(b.getInt(), b.getInt(), b.getInt()));
        }

        return new AnalysisResult(new WorldRevision(rev), true, singular, diagnostic,
                maxDC, governing, governingKind, islands, singularIslands,
                residual, bucklingFactor, members, shells, unassigned);
    }

    private static MemberSnapshot member(ByteBuffer b, List<String> mats, List<String> toks) {
        int id = b.getInt();
        String mat = at(mats, b.getInt(), "material");
        String section = at(toks, b.getInt(), "section");
        double lengthMm = b.getDouble();
        double dc = b.getDouble();
        String fibreName = FIBRE_NAMES[Math.floorMod(b.getInt(), FIBRE_NAMES.length)];
        int governingStation = b.getInt();
        EndForces fi = forces(b);
        EndForces fj = forces(b);
        Vec3d origin = vec(b), ax = vec(b), ay = vec(b), az = vec(b);
        double a = b.getDouble(), iy = b.getDouble(), iz = b.getDouble();
        double cy = b.getDouble(), cz = b.getDouble();
        double wy = b.getDouble(), wz = b.getDouble();

        int nBlocks = b.getInt();
        List<BlockKey> blocks = new ArrayList<>(checkCount(nBlocks));
        for (int k = 0; k < nBlocks; k++) blocks.add(new BlockKey(b.getInt(), b.getInt(), b.getInt()));

        Vec3d dTop = ay, dBot = neg(ay), dPlus = az, dMin = neg(az);
        int nStations = b.getInt();
        List<StressStation> stations = new ArrayList<>(checkCount(nStations));
        for (int k = 0; k < nStations; k++) {
            double x = b.getDouble();
            Vec3d world = vec(b);
            double sTop = b.getDouble(), sBot = b.getDouble();
            double sPls = b.getDouble(), sMin = b.getDouble();
            List<Fibre> fibres = List.of(
                    new Fibre("TOP_Y", dTop, cz, sTop),
                    new Fibre("BOT_Y", dBot, cz, sBot),
                    new Fibre("PLUS_Z", dPlus, cy, sPls),
                    new Fibre("MINUS_Z", dMin, cy, sMin));
            double sigmaTens = b.getDouble();
            double sigmaComp = b.getDouble();
            double tau = b.getDouble();
            double naY = b.getDouble();
            double naZ = b.getDouble();
            stations.add(new StressStation(x, world, fibres, sigmaTens, sigmaComp, tau,
                    Double.isNaN(naY) ? Optional.empty() : Optional.of(naY),
                    Double.isNaN(naZ) ? Optional.empty() : Optional.of(naZ)));
        }

        StressFieldSpec field = new StressFieldSpec(origin, ax, ay, az, lengthMm,
                a, iy, iz, cy, cz, wy, wz, fi, fj);
        return new MemberSnapshot(id, mat, section, lengthMm, dc,
                GoverningFibre.fromWire(fibreName), governingStation,
                fi, fj, blocks, stations, Optional.of(field));
    }

    private static ShellSnapshot shell(ByteBuffer b, List<String> mats, List<String> toks) {
        int id = b.getInt();
        String mat = at(mats, b.getInt(), "material");
        String plate = at(toks, b.getInt(), "plate");
        double t = b.getDouble();
        double dc = b.getDouble();
        double dcRaw = b.getDouble();
        int flags = b.getInt();
        boolean top = (flags & 1) != 0;
        boolean recovered = (flags & 2) != 0;
        b.getInt();   // governing corner: not consumed by the Java side today

        List<BlockKey> blocks = new ArrayList<>(4);
        for (int k = 0; k < 4; k++) blocks.add(new BlockKey(b.getInt(), b.getInt(), b.getInt()));
        List<Vec3d> corners = new ArrayList<>(4);
        for (int k = 0; k < 4; k++) corners.add(vec(b));
        Vec3d ex = vec(b), ey = vec(b), n = vec(b);
        double nxx = b.getDouble(), nyy = b.getDouble(), nxy = b.getDouble();
        double mxx = b.getDouble(), myy = b.getDouble(), mxy = b.getDouble();
        double qx = b.getDouble(), qy = b.getDouble();
        List<ShellFieldSpec.Moments> mc = new ArrayList<>(4);
        for (int k = 0; k < 4; k++) {
            mc.add(new ShellFieldSpec.Moments(b.getDouble(), b.getDouble(), b.getDouble()));
        }
        b.position(b.position() + 12 * 8);   // McRaw: documentation of the recovery, not consumed
        b.position(b.position() + 2 * 8);    // vmTop, vmBot: not consumed

        ShellFieldSpec field = new ShellFieldSpec(corners, ex, ey, n, t,
                nxx, nyy, nxy, mxx, myy, mxy, qx, qy, mc);
        return new ShellSnapshot(id, mat, plate, t, dc, dcRaw, top, recovered,
                blocks, Optional.of(field));
    }

    private static EndForces forces(ByteBuffer b) {
        return new EndForces(b.getDouble(), b.getDouble(), b.getDouble(),
                b.getDouble(), b.getDouble(), b.getDouble());
    }

    private static Vec3d vec(ByteBuffer b) {
        return new Vec3d(b.getDouble(), b.getDouble(), b.getDouble());
    }

    private static Vec3d neg(Vec3d v) { return new Vec3d(-v.x(), -v.y(), -v.z()); }

    private static String str(ByteBuffer b) {
        int n = checkCount(b.getInt());
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

    private static int checkCount(int n) {
        if (n < 0 || n > 10_000_000) throw new IllegalStateException("implausible count " + n);
        return n;
    }
}
