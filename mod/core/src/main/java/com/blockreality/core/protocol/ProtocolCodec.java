package com.blockreality.core.protocol;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.BucklingState;
import com.blockreality.api.EndForces;
import com.blockreality.api.EngineCatalogue;
import com.blockreality.api.GoverningFibre;
import com.blockreality.api.Fibre;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ShellFieldSpec;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.StressFieldSpec;
import com.blockreality.api.StressStation;
import com.blockreality.api.UnassignedBlocks;
import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.geom.Vec3d;
import com.blockreality.core.json.JsonValue;
import com.blockreality.core.json.JsonWriter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Translates between the wire and the api types. One line of JSON per message.
 *
 * <p>Decoding is total: every reachable input maps to some {@link AnalysisResult},
 * including a truncated line, an empty line and a line of prose. The sidecar is a
 * separate process which may be killed at any moment, so a half-written reply is an
 * expected input to this class rather than a bug in it.
 *
 * <p>Total does not mean permissive. A reply that claims {@code ok} is held to the full
 * reply schema, and a missing or wrong-typed required field yields a <em>failed</em>
 * result naming that field — never a result with the gap papered over by a default.
 * The old behaviour substituted zero for an absent {@code maxDC} and an empty list for
 * absent members, which turned a half-written or version-skewed reply into a confident
 * "everything is fine"; a wrong answer that looks healthy is strictly worse than a
 * missing one (#31).
 */
public final class ProtocolCodec {

    private ProtocolCodec() { }

    // ------------------------------------------------------------------ encode
    public static String encodeHello() {
        return new JsonWriter().beginObj().kv("op", "hello").endObj().done();
    }

    public static String encodeBye() {
        return new JsonWriter().beginObj().kv("op", "bye").endObj().done();
    }

    /** Doorbell: ask the sidecar to map the shared region at {@code path}. */
    public static String encodeShmOpen(String path) {
        return new JsonWriter().beginObj().kv("op", "shm.open").kv("path", path).endObj().done();
    }

    /**
     * Doorbell: the request for {@code revision} is in the shared region; solve it and
     * write the reply back.
     *
     * @param bytes the request's exact byte length. The sidecar parses only that many
     *              bytes and requires the frame to be consumed exactly (#27) — without
     *              it, a truncated request would keep parsing into whatever the LAST
     *              frame left in the region, which is stale-tail corruption with a
     *              plausible face. The measured numbers still never touch this line.
     */
    public static String encodeSolveShm(long revision, long bytes) {
        return new JsonWriter().beginObj()
                .kv("op", "solve.shm").kv("revision", revision).kv("bytes", bytes)
                .endObj().done();
    }

    public static String encodeSolve(SolveRequest req) {
        JsonWriter w = new JsonWriter();
        w.beginObj();
        w.kv("op", "solve").kv("revision", req.revision().value());
        // Only ever written when OFF: absent means the engine default (on), so every
        // request byte stream that existed before this field exists unchanged after it.
        if (!req.buckling()) w.kv("buckling", false);

        w.key("blocks").beginArr();
        for (SolveRequest.Block b : req.blocks()) {
            w.beginObj()
             .kv("x", b.pos().x()).kv("y", b.pos().y()).kv("z", b.pos().z())
             .kv("mat", b.material()).kv("section", b.section())
             .kv("support", b.support())
             .endObj();
        }
        w.endArr();

        w.key("loads").beginArr();
        for (SolveRequest.PointLoad l : req.loads()) {
            w.beginObj()
             .kv("x", l.at().x()).kv("y", l.at().y()).kv("z", l.at().z())
             .kv("fx", l.fx()).kv("fy", l.fy()).kv("fz", l.fz())
             .kv("mx", l.mx()).kv("my", l.my()).kv("mz", l.mz())
             .endObj();
        }
        w.endArr();

        w.endObj();
        return w.done();
    }

    // ------------------------------------------------------------------ decode
    /**
     * @return empty if the line is not a usable hello reply.
     *
     * <p>Strict on purpose (#34): the catalogue is not UI metadata, it is the index ABI
     * of the binary wire. Materials and section/plate tokens travel over shm as indices
     * into these lists, so a duplicated token or a section/plate overlap makes the
     * index-to-token mapping ambiguous — the two ends would agree on a number and
     * disagree on what it names. A catalogue that cannot serve as that ABI is refused
     * whole rather than accepted with the bad entries "cleaned up": cleaning would
     * shift every later index by one, which corrupts silently instead of loudly.
     */
    public static Optional<EngineCatalogue> decodeHello(String line) {
        JsonValue v = JsonValue.parse(line);
        if (!v.isObject()) return Optional.empty();
        if (!v.isBool("ok") || !v.bool("ok", false)) return Optional.empty();
        if (!v.isStr("engine")) return Optional.empty();

        // Exact and int-ranged. A protocol of 2^32+1 truncated to int would read as 1
        // and pass the compatibility gate — the version check must not be defeatable
        // by the version field itself.
        if (!v.isExactInt("protocol")) return Optional.empty();
        long protocol = v.exactI64("protocol");
        if (protocol != (int) protocol) return Optional.empty();

        if (!v.isArr("materials") || !v.isArr("sections") || !v.isArr("plates")) {
            return Optional.empty();
        }

        List<String> mats = new ArrayList<>();
        for (JsonValue m : v.arr("materials")) {
            if (m.type() != JsonValue.Type.STR || m.asStr("").isEmpty()) return Optional.empty();
            mats.add(m.asStr(""));
        }
        List<String> secs = new ArrayList<>();
        for (JsonValue s : v.arr("sections")) {
            if (s.type() != JsonValue.Type.STR || s.asStr("").isEmpty()) return Optional.empty();
            secs.add(s.asStr(""));
        }
        // Plates arrive as objects because a plate carries a dimension the token alone
        // does not give the client: its thickness. A plate without a positive finite
        // thickness is not something the engine can have announced.
        List<String> plates = new ArrayList<>();
        for (JsonValue p : v.arr("plates")) {
            if (!p.isObject()) return Optional.empty();
            if (!p.isStr("id") || p.str("id", "").isEmpty()) return Optional.empty();
            if (!p.isFiniteNum("t") || p.num("t", 0) <= 0) return Optional.empty();
            plates.add(p.str("id", ""));
        }

        if (new HashSet<>(mats).size() != mats.size()) return Optional.empty();
        // Sections and plates share one index space on the binary wire (sections first,
        // then plates), and the role split — beam vs facet — is part of the contract,
        // so the UNION must be duplicate-free, not just each list on its own.
        Set<String> tokens = new HashSet<>();
        for (String s : secs) {
            if (!tokens.add(s)) return Optional.empty();
        }
        for (String p : plates) {
            if (!tokens.add(p)) return Optional.empty();
        }

        // shm is a capability: absent simply means "JSON only". Present, it must be an
        // exact non-negative int in int range — 2^32+1 truncated would read as layout 1
        // and switch on a transport the engine never offered.
        long shm = 0;
        if (v.has("shm")) {
            if (!v.isExactInt("shm")) return Optional.empty();
            shm = v.exactI64("shm");
            if (shm < 0 || shm != (int) shm) return Optional.empty();
        }

        return Optional.of(new EngineCatalogue(
                v.str("engine", ""), (int) protocol, mats, secs, plates, (int) shm));
    }

    /**
     * @param expected the revision the caller asked about. A reply carrying a different
     *                 revision is rejected outright rather than accepted with a warning:
     *                 the pipe is ordered, so a mismatch means the client and the sidecar
     *                 have lost sync, and guessing which message this is would be worse
     *                 than failing.
     */
    public static AnalysisResult decodeSolve(String line, WorldRevision expected) {
        JsonValue v = JsonValue.parse(line);
        if (!v.isObject()) return AnalysisResult.failed(expected, "malformed reply");

        // Revision goes through the exact-integer reading: a double folds every integer
        // above 2^53 onto its neighbours, and this is the one field whose whole job is
        // telling two adjacent values apart.
        if (!v.isExactInt("revision")) {
            return AnalysisResult.failed(expected, "revision missing or not a plain integer");
        }
        long rev = v.exactI64("revision");
        if (rev != expected.value()) {
            return AnalysisResult.failed(expected,
                    "revision mismatch: asked for " + expected.value() + ", got " + rev);
        }
        if (!v.isBool("ok")) {
            return AnalysisResult.failed(expected, "ok missing or not a boolean");
        }
        if (!v.bool("ok", false)) {
            return AnalysisResult.failed(expected, v.str("error", "engine reported failure"));
        }

        try {
            return decodeOkReply(v, rev);
        } catch (Reject r) {
            return AnalysisResult.failed(expected, r.getMessage());
        } catch (RuntimeException e) {
            // Backstop for the total-decoding contract. Nothing below is expected to
            // throw anything but Reject, but "expected" is not "guaranteed", and the
            // caller was promised a result, not a stack trace.
            return AnalysisResult.failed(expected, "unreadable reply: " + e.getMessage());
        }
    }

    /**
     * Validation failure inside a reply that claimed ok, carrying the offending field's
     * path (e.g. {@code members[2].i.Mz missing or not finite}). Internal control flow
     * only — {@link #decodeSolve} catches it at the boundary, so the public contract
     * (decoding is total, never throws) is unchanged.
     */
    private static final class Reject extends RuntimeException {
        // Never serialized; the id exists to keep the build's warning output clean.
        private static final long serialVersionUID = 1L;

        Reject(String path, String why) { super(path + " " + why); }
    }

    // ---------------------------------------------------- required-field readers
    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static double fin(JsonValue o, String key, String path) {
        if (!o.isFiniteNum(key)) throw new Reject(join(path, key), "missing or not finite");
        return o.num(key, 0);
    }

    private static long exact(JsonValue o, String key, String path) {
        if (!o.isExactInt(key)) throw new Reject(join(path, key), "missing or not a plain integer");
        return o.exactI64(key);
    }

    private static int exactI32(JsonValue o, String key, String path) {
        long v = exact(o, key, path);
        if (v != (int) v) throw new Reject(join(path, key), "out of int range");
        return (int) v;
    }

    private static String reqStr(JsonValue o, String key, String path) {
        if (!o.isStr(key)) throw new Reject(join(path, key), "missing or not a string");
        return o.str(key, "");
    }

    private static boolean reqBool(JsonValue o, String key, String path) {
        if (!o.isBool(key)) throw new Reject(join(path, key), "missing or not a boolean");
        return o.bool(key, false);
    }

    private static JsonValue reqObj(JsonValue o, String key, String path) {
        if (!o.isObj(key)) throw new Reject(join(path, key), "missing or not an object");
        return o.objField(key);
    }

    private static List<JsonValue> reqArr(JsonValue o, String key, String path) {
        if (!o.isArr(key)) throw new Reject(join(path, key), "missing or not an array");
        return o.arr(key);
    }

    /** A string that is absent (→ empty) or a string — but never another type. */
    private static String optStr(JsonValue o, String key, String path) {
        if (!o.has(key)) return "";
        return reqStr(o, key, path);
    }

    /** A JSON value that must be a 3-vector of finite numbers. */
    private static Vec3d vecOf(JsonValue e, String path) {
        if (e.type() != JsonValue.Type.ARR) throw new Reject(path, "missing or not an array");
        List<JsonValue> t = e.asArr();
        if (t.size() != 3) throw new Reject(path, "must have exactly 3 components");
        double[] c = new double[3];
        for (int k = 0; k < 3; k++) {
            double d = t.get(k).asNum(Double.NaN);
            // NaN doubles as the wrong-type flag here: the parser itself never yields
            // NaN, so a NaN reading means "not a number at all" — and an overflowed
            // literal like 1e999 parses to Infinity, which is equally unusable.
            if (!Double.isFinite(d)) throw new Reject(path + "[" + k + "]", "not a finite number");
            c[k] = d;
        }
        return new Vec3d(c[0], c[1], c[2]);
    }

    private static Vec3d vec3(JsonValue o, String key, String path) {
        return vecOf(o.objField(key), join(path, key));
    }

    /** A JSON value that must be a 3-vector of integers — a block coordinate. */
    private static BlockKey blockOf(JsonValue e, String path) {
        if (e.type() != JsonValue.Type.ARR) throw new Reject(path, "not an array");
        List<JsonValue> t = e.asArr();
        if (t.size() != 3) throw new Reject(path, "must have exactly 3 components");
        int[] c = new int[3];
        for (int k = 0; k < 3; k++) {
            double d = t.get(k).asNum(Double.NaN);
            if (!Double.isFinite(d) || d != Math.rint(d)
                    || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                throw new Reject(path + "[" + k + "]", "not an integer");
            }
            c[k] = (int) d;
        }
        return new BlockKey(c[0], c[1], c[2]);
    }

    private static List<BlockKey> blocksOf(List<JsonValue> a, String path) {
        List<BlockKey> out = new ArrayList<>(a.size());
        for (int k = 0; k < a.size(); k++) {
            out.add(blockOf(a.get(k), path + "[" + k + "]"));
        }
        return out;
    }

    private static GoverningFibre fibreOf(String s, String path) {
        // Same normalisation as GoverningFibre.fromWire, WITHOUT its degrade-to-NONE.
        // fromWire's tolerance exists for casual callers; inside a reply that claims ok,
        // an unknown fibre name means the engine speaks a vocabulary this build does
        // not, and mapping it to NONE would show a governing member with no governing
        // reason — a picture that answers "why is this red?" with a shrug.
        try {
            return GoverningFibre.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Reject(path, "unknown governing fibre \"" + s + "\"");
        }
    }

    // ------------------------------------------------------------ strict decode
    private static AnalysisResult decodeOkReply(JsonValue v, long rev) {
        boolean singular = reqBool(v, "singular", "");
        int islands = exactI32(v, "islands", "");
        if (islands < 0) throw new Reject("islands", "must be >= 0");
        int singularIslands = exactI32(v, "singularIslands", "");
        if (singularIslands < 0) throw new Reject("singularIslands", "must be >= 0");
        double maxDC = fin(v, "maxDC", "");
        int governing = exactI32(v, "governing", "");
        double residual = fin(reqObj(v, "equilibrium", ""), "residual", "equilibrium");

        // Optional with a closed vocabulary: absent is honest ("nothing governs yet"),
        // but a value outside member/shell would leave the governing id pointing into
        // a list this build does not know exists.
        String governingKind = "";
        if (v.has("governingKind")) {
            governingKind = reqStr(v, "governingKind", "");
            if (!governingKind.equals("member") && !governingKind.equals("shell")) {
                throw new Reject("governingKind", "must be \"member\" or \"shell\"");
            }
        }
        double bucklingFactor = 0;
        if (v.has("bucklingFactor")) {
            bucklingFactor = fin(v, "bucklingFactor", "");
            if (bucklingFactor < 0) throw new Reject("bucklingFactor", "must be >= 0");
        }

        String diagnostic = optStr(v, "diagnostic", "");
        String note = optStr(v, "note", "");
        if (diagnostic.isEmpty()) diagnostic = note;

        List<JsonValue> memberValues = reqArr(v, "members", "");
        List<MemberSnapshot> members = new ArrayList<>(memberValues.size());
        for (int k = 0; k < memberValues.size(); k++) {
            JsonValue m = memberValues.get(k);
            String path = "members[" + k + "]";
            if (!m.isObject()) throw new Reject(path, "not an object");
            members.add(decodeMember(m, path));
        }

        List<JsonValue> shellValues = reqArr(v, "shells", "");
        List<ShellSnapshot> shells = new ArrayList<>(shellValues.size());
        for (int k = 0; k < shellValues.size(); k++) {
            JsonValue s = shellValues.get(k);
            String path = "shells[" + k + "]";
            if (!s.isObject()) throw new Reject(path, "not an object");
            shells.add(decodeShell(s, path));
        }

        List<JsonValue> unassignedValues = reqArr(v, "unassigned", "");
        List<UnassignedBlocks> unassigned = new ArrayList<>(unassignedValues.size());
        for (int k = 0; k < unassignedValues.size(); k++) {
            JsonValue g = unassignedValues.get(k);
            String path = "unassigned[" + k + "]";
            if (!g.isObject()) throw new Reject(path, "not an object");
            // The token is read as a string and resolved to an enum that has an UNKNOWN
            // member, rather than rejected when unrecognised. The opposite choice is right
            // for governing fibre -- see fibreOf -- because there an unknown name would
            // leave a member coloured with no reason. Here the blocks are the payload and
            // the reason is the label: refusing the whole reply over a label this build
            // has not met would lose the blocks too, which is what N17 is closing.
            unassigned.add(UnassignedBlocks.of(reqStr(g, "why", path),
                    blocksOf(reqArr(g, "blocks", path), path + ".blocks")));
        }

        // Absent means UNKNOWN, not COMPUTED. A reply that does not say must not be read
        // as one that said the reassuring thing.
        BucklingState bucklingState = BucklingState.fromWire(optStr(v, "bucklingState", ""));
        if (bucklingState == BucklingState.COMPUTED && !(bucklingFactor > 0)) {
            throw new Reject("bucklingState", "says computed with no factor (N18-b)");
        }
        if (bucklingState != BucklingState.COMPUTED && bucklingFactor > 0) {
            throw new Reject("bucklingState", "carries a factor while claiming \""
                    + bucklingState.wire() + "\" (N18-b)");
        }

        return new AnalysisResult(
                new WorldRevision(rev),
                true,
                singular,
                diagnostic,
                maxDC,
                governing,
                governingKind,
                islands,
                singularIslands,
                residual,
                bucklingFactor,
                bucklingState,
                members,
                shells,
                unassigned);
    }

    private static MemberSnapshot decodeMember(JsonValue m, String path) {
        int id = exactI32(m, "id", path);
        String mat = reqStr(m, "mat", path);
        String section = reqStr(m, "section", path);
        double lengthMm = fin(m, "lengthMm", path);
        double dc = fin(m, "dc", path);
        GoverningFibre fibre = fibreOf(reqStr(m, "governingFibre", path), join(path, "governingFibre"));
        int governingStation = exactI32(m, "governingStation", path);
        EndForces fi = decodeForces(reqObj(m, "i", path), join(path, "i"));
        EndForces fj = decodeForces(reqObj(m, "j", path), join(path, "j"));

        // The field is REQUIRED: the sidecar always writes it, and it is what the
        // overlay evaluates. A member without one is a reply this build cannot draw
        // honestly, so it is refused rather than rendered blank.
        JsonValue f = reqObj(m, "field", path);
        String fp = join(path, "field");
        StressFieldSpec field = new StressFieldSpec(
                vec3(f, "origin", fp), vec3(f, "ax", fp), vec3(f, "ay", fp), vec3(f, "az", fp),
                lengthMm,
                fin(f, "A", fp), fin(f, "Iy", fp), fin(f, "Iz", fp),
                fin(f, "cy", fp), fin(f, "cz", fp),
                fin(f, "wy", fp), fin(f, "wz", fp),
                fi, fj);

        List<BlockKey> blocks = blocksOf(reqArr(m, "blocks", path), join(path, "blocks"));

        List<JsonValue> stationValues = reqArr(m, "stations", path);
        List<StressStation> stations = new ArrayList<>(stationValues.size());
        for (int k = 0; k < stationValues.size(); k++) {
            JsonValue s = stationValues.get(k);
            String sp = path + ".stations[" + k + "]";
            if (!s.isObject()) throw new Reject(sp, "not an object");
            stations.add(decodeStation(s, sp));
        }

        return new MemberSnapshot(id, mat, section, lengthMm, dc, fibre, governingStation,
                fi, fj, blocks, stations, Optional.of(field));
    }

    private static EndForces decodeForces(JsonValue f, String path) {
        return new EndForces(
                fin(f, "N", path), fin(f, "Vy", path), fin(f, "Vz", path),
                fin(f, "T", path), fin(f, "My", path), fin(f, "Mz", path));
    }

    private static StressStation decodeStation(JsonValue s, String path) {
        double x = fin(s, "x", path);
        Vec3d world = vec3(s, "world", path);

        List<JsonValue> fibreValues = reqArr(s, "fibres", path);
        List<Fibre> fibres = new ArrayList<>(fibreValues.size());
        for (int k = 0; k < fibreValues.size(); k++) {
            JsonValue f = fibreValues.get(k);
            String fp = path + ".fibres[" + k + "]";
            if (!f.isObject()) throw new Reject(fp, "not an object");
            fibres.add(new Fibre(
                    reqStr(f, "name", fp),
                    vec3(f, "dir", fp),
                    fin(f, "offsetMm", fp),
                    fin(f, "sigma", fp)));
        }

        double sigmaTens = fin(s, "sigmaTens", path);
        double sigmaComp = fin(s, "sigmaComp", path);
        double tau = fin(s, "tau", path);

        // Absent means "this section has no neutral axis", which is a real state
        // for a fully tensile or fully compressive section. Defaulting it to zero
        // would draw a neutral axis through the centroid of a member that has none —
        // but PRESENT and non-finite is corruption, not absence.
        Optional<Double> naY = s.has("naY") ? Optional.of(fin(s, "naY", path)) : Optional.empty();
        Optional<Double> naZ = s.has("naZ") ? Optional.of(fin(s, "naZ", path)) : Optional.empty();

        return new StressStation(x, world, fibres, sigmaTens, sigmaComp, tau, naY, naZ);
    }

    private static ShellSnapshot decodeShell(JsonValue s, String path) {
        int id = exactI32(s, "id", path);
        String mat = reqStr(s, "mat", path);
        String plate = reqStr(s, "plate", path);
        double t = fin(s, "t", path);
        double dc = fin(s, "dc", path);
        double dcRaw = fin(s, "dcRaw", path);

        String face = reqStr(s, "face", path);
        if (!face.equals("TOP") && !face.equals("BOT")) {
            throw new Reject(join(path, "face"), "must be \"TOP\" or \"BOT\"");
        }
        // Consumed for validation only: the corner is not carried into the snapshot
        // today, but a reply without one is off-schema all the same.
        exactI32(s, "corner", path);
        boolean edgeRecovered = reqBool(s, "edgeRecovered", path);

        List<JsonValue> blockValues = reqArr(s, "blocks", path);
        if (blockValues.size() != 4) throw new Reject(join(path, "blocks"), "must have exactly 4 blocks");
        List<BlockKey> blocks = blocksOf(blockValues, join(path, "blocks"));

        List<JsonValue> worldValues = reqArr(s, "world", path);
        if (worldValues.size() != 4) throw new Reject(join(path, "world"), "must have exactly 4 corners");
        List<Vec3d> corners = new ArrayList<>(4);
        for (int k = 0; k < 4; k++) {
            corners.add(vecOf(worldValues.get(k), path + ".world[" + k + "]"));
        }

        Vec3d ex = vec3(s, "ex", path);
        Vec3d ey = vec3(s, "ey", path);
        Vec3d n = vec3(s, "n", path);

        JsonValue nv = reqObj(s, "N", path);
        JsonValue mv = reqObj(s, "M", path);
        JsonValue qv = reqObj(s, "Q", path);
        String np = join(path, "N"), mp = join(path, "M"), qp = join(path, "Q");

        List<JsonValue> mcValues = reqArr(s, "Mc", path);
        if (mcValues.size() != 4) throw new Reject(join(path, "Mc"), "must have exactly 4 corners");
        List<ShellFieldSpec.Moments> moments = new ArrayList<>(4);
        for (int k = 0; k < 4; k++) {
            Vec3d c = vecOf(mcValues.get(k), path + ".Mc[" + k + "]");
            moments.add(new ShellFieldSpec.Moments(c.x(), c.y(), c.z()));
        }
        // McRaw appears only when the edge recovery fired. Not consumed here, but a
        // present-and-malformed one is validated anyway: schema drift does not get a
        // pass for landing in a field we happen not to read yet.
        if (s.has("McRaw")) {
            List<JsonValue> raw = reqArr(s, "McRaw", path);
            if (raw.size() != 4) throw new Reject(join(path, "McRaw"), "must have exactly 4 corners");
            for (int k = 0; k < 4; k++) {
                vecOf(raw.get(k), path + ".McRaw[" + k + "]");
            }
        }

        ShellFieldSpec field = new ShellFieldSpec(
                corners, ex, ey, n, t,
                fin(nv, "xx", np), fin(nv, "yy", np), fin(nv, "xy", np),
                fin(mv, "xx", mp), fin(mv, "yy", mp), fin(mv, "xy", mp),
                fin(qv, "x", qp), fin(qv, "y", qp),
                moments);

        return new ShellSnapshot(id, mat, plate, t, dc, dcRaw,
                face.equals("TOP"), edgeRecovered, blocks, Optional.of(field));
    }
}
