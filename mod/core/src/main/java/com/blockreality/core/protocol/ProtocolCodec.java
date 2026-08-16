package com.blockreality.core.protocol;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.EndForces;
import com.blockreality.api.EngineCatalogue;
import com.blockreality.api.GoverningFibre;
import com.blockreality.api.Fibre;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.StressFieldSpec;
import com.blockreality.api.StressStation;
import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.geom.Vec3d;
import com.blockreality.core.json.JsonValue;
import com.blockreality.core.json.JsonWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Translates between the wire and the api types. One line of JSON per message.
 *
 * <p>Decoding is total: every reachable input maps to some {@link AnalysisResult},
 * including a truncated line, an empty line and a line of prose. The sidecar is a
 * separate process which may be killed at any moment, so a half-written reply is an
 * expected input to this class rather than a bug in it.
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

    public static String encodeSolve(SolveRequest req) {
        JsonWriter w = new JsonWriter();
        w.beginObj();
        w.kv("op", "solve").kv("revision", req.revision().value());

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
    /** @return empty if the line is not a usable hello reply */
    public static Optional<EngineCatalogue> decodeHello(String line) {
        JsonValue v = JsonValue.parse(line);
        if (!v.isObject() || !v.bool("ok", false)) return Optional.empty();
        List<String> mats = new ArrayList<>();
        for (JsonValue m : v.arr("materials")) mats.add(m.asStr(""));
        List<String> secs = new ArrayList<>();
        for (JsonValue s : v.arr("sections")) secs.add(s.asStr(""));
        return Optional.of(new EngineCatalogue(
                v.str("engine", "unknown"), v.i32("protocol", -1), mats, secs));
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

        long rev = v.i64("revision", -1);
        if (rev != expected.value()) {
            return AnalysisResult.failed(expected,
                    "revision mismatch: asked for " + expected.value() + ", got " + rev);
        }
        if (!v.bool("ok", false)) {
            return AnalysisResult.failed(expected, v.str("error", "engine reported failure"));
        }

        boolean singular = v.bool("singular", false);
        String diagnostic = v.str("diagnostic", v.str("note", ""));

        List<MemberSnapshot> members = new ArrayList<>();
        for (JsonValue m : v.arr("members")) members.add(decodeMember(m));

        List<BlockKey> unassigned = decodeBlocks(v.arr("unassigned"));

        return new AnalysisResult(
                new WorldRevision(rev),
                true,
                singular,
                diagnostic,
                v.num("maxDC", 0),
                v.i32("governing", -1),
                members,
                unassigned);
    }

    private static MemberSnapshot decodeMember(JsonValue m) {
        List<StressStation> stations = new ArrayList<>();
        for (JsonValue s : m.arr("stations")) stations.add(decodeStation(s));
        return new MemberSnapshot(
                m.i32("id", -1),
                m.str("mat", ""),
                m.str("section", ""),
                m.num("lengthMm", 0),
                m.num("dc", 0),
                GoverningFibre.fromWire(m.str("governingFibre", "NONE")),
                m.i32("governingStation", -1),
                decodeForces(m.objField("i")),
                decodeForces(m.objField("j")),
                decodeBlocks(m.arr("blocks")),
                stations,
                decodeField(m, m.objField("i"), m.objField("j")));
    }

    private static Optional<StressFieldSpec> decodeField(JsonValue m, JsonValue fi, JsonValue fj) {
        JsonValue f = m.objField("field");
        if (!f.isObject()) return Optional.empty();
        return Optional.of(new StressFieldSpec(
                decodeVec(f.arr("origin")), decodeVec(f.arr("ax")),
                decodeVec(f.arr("ay")), decodeVec(f.arr("az")),
                m.num("lengthMm", 0),
                f.num("A", 0), f.num("Iy", 0), f.num("Iz", 0),
                f.num("cy", 0), f.num("cz", 0),
                f.num("wy", 0), f.num("wz", 0),
                decodeForces(fi), decodeForces(fj)));
    }

    private static EndForces decodeForces(JsonValue f) {
        if (!f.isObject()) return EndForces.ZERO;
        return new EndForces(f.num("N", 0), f.num("Vy", 0), f.num("Vz", 0),
                f.num("T", 0), f.num("My", 0), f.num("Mz", 0));
    }

    private static StressStation decodeStation(JsonValue s) {
        List<Fibre> fibres = new ArrayList<>();
        for (JsonValue f : s.arr("fibres")) {
            fibres.add(new Fibre(
                    f.str("name", "?"),
                    decodeVec(f.arr("dir")),
                    f.num("offsetMm", 0),
                    f.num("sigma", 0)));
        }
        return new StressStation(
                s.num("x", 0),
                decodeVec(s.arr("world")),
                fibres,
                s.num("sigmaTens", 0),
                s.num("sigmaComp", 0),
                s.num("tau", 0),
                // Absent means "this section has no neutral axis", which is a real state
                // for a fully tensile or fully compressive section. Defaulting it to zero
                // would draw a neutral axis through the centroid of a member that has none.
                s.has("naY") ? Optional.of(s.num("naY", 0)) : Optional.empty(),
                s.has("naZ") ? Optional.of(s.num("naZ", 0)) : Optional.empty());
    }

    private static Vec3d decodeVec(List<JsonValue> a) {
        if (a.size() < 3) return Vec3d.ZERO;
        return new Vec3d(a.get(0).asNum(0), a.get(1).asNum(0), a.get(2).asNum(0));
    }

    private static List<BlockKey> decodeBlocks(List<JsonValue> a) {
        List<BlockKey> out = new ArrayList<>(a.size());
        for (JsonValue b : a) {
            List<JsonValue> t = b.asArr();
            if (t.size() < 3) continue;
            out.add(new BlockKey((int) t.get(0).asNum(0), (int) t.get(1).asNum(0), (int) t.get(2).asNum(0)));
        }
        return out;
    }
}
