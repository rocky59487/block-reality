// Block Reality structural sidecar.
//
// Runs as a separate process (DECISIONS D-013). Speaks one JSON object per line
// on stdin/stdout. The Minecraft side sends BLOCKS and MATERIALS; this process
// owns the structural model — member extraction, node management, solving and
// D/C recovery all happen here (DECISIONS D-006).
//
// Units inside FrameCore: N, mm, MPa. One Minecraft block = 1000 mm.
// Axis mapping: Minecraft Y is up, FrameCore Z is up.
//     FrameCore X = MC x      FrameCore Y = MC z      FrameCore Z = MC y
// Gravity is -Z in FrameCore, which is -y in Minecraft. Correct by construction.
//
// Exits when stdin reaches EOF: if the parent JVM dies the pipe closes and this
// process ends with it, so a crashed server cannot leave a zombie behind.

#include "json.hpp"

#include "FrameCore/FrameModel.h"
#include "FrameCore/FrameSolver.h"
#include "FrameCore/SolveOptions.h"
#include "FrameCore/SolveResult.h"
#include "FrameCore/ElasticAllowable.h"
#include "FrameCore/SelfWeight.h"
#include "FrameCore/Material.h"
#include "FrameCore/Section.h"
#include "FrameCore/StressField.h"
#include "FrameCore/StressKernel.h"
#include "FrameCore/MemberGeometry.h"

#include <iostream>
#include <string>
#include <vector>
#include <map>
#include <set>
#include <array>
#include <algorithm>
#include <cmath>

namespace {

constexpr double kBlockMm = 1000.0;   // one Minecraft block
constexpr int    kProtocol = 1;

// ------------------------------------------------------------------ inputs
struct BlockPos {
    int x = 0, y = 0, z = 0;
    bool operator<(const BlockPos& o) const {
        if (x != o.x) return x < o.x;
        if (y != o.y) return y < o.y;
        return z < o.z;
    }
    bool operator==(const BlockPos& o) const { return x == o.x && y == o.y && z == o.z; }
};

struct InBlock {
    BlockPos    pos;
    std::string mat;
    std::string section;
    bool        support = false;
};

// ------------------------------------------------------- material catalogue
// Values from DefaultMaterial (DECISIONS D-012). Steel E is pinned to the ACI
// 200 GPa figure, not the Eurocode 210 — one number, one chain, no drift.
struct MatSpec {
    double E, nu, rho, rcomp, rtens, rshear;
};

const std::map<std::string, MatSpec>& materialCatalogue() {
    static const std::map<std::string, MatSpec> kM = {
        // id            E(MPa)    nu     rho     Rcomp  Rtens  Rshear
        { "steel",    { 200000.0, 0.29, 7850.0, 350.0, 500.0, 200.0 } },
        { "rebar",    { 200000.0, 0.29, 7850.0, 250.0, 400.0, 150.0 } },
        { "concrete", {  30000.0, 0.20, 2350.0,  30.0,   3.0,   4.0 } },
        { "timber",   {  11000.0, 0.35,  600.0,   5.0,   8.0,   2.0 } },
        { "brick",    {   5000.0, 0.15, 1800.0,  10.0,   0.5,   1.5 } },
    };
    return kM;
}

// ------------------------------------------------------- section catalogue
// Every entry is deliberately NON-SQUARE (GATES.md fixture rule): a square
// section hides local-axis mix-ups, because -Y and -Z then give the same answer.
const std::map<std::string, frame::Section>& sectionCatalogue() {
    static const std::map<std::string, frame::Section> kS = {
        { "steel_h400", frame::Section::Rectangular(200.0, 400.0) },
        { "steel_h300", frame::Section::Rectangular(150.0, 300.0) },
        { "steel_h200", frame::Section::Rectangular(100.0, 200.0) },
        { "rc_400x600", frame::Section::Rectangular(400.0, 600.0) },
        { "rebar_d25",  frame::Section::Circular(12.5) },
    };
    return kS;
}

// FrameCore Z is up, Minecraft Y is up. One conversion, used everywhere.
struct McVec { double x, y, z; };
inline McVec fcToMc(const frame::Vec3& v) { return McVec{ v.x, v.z, v.y }; }

// SIGN CONVENTION — converted exactly once, here.
// FrameCore's fibre kernel is COMPRESSION-POSITIVE. Everything this sidecar
// emits is TENSION-POSITIVE, the convention used in engineering teaching and in
// every textbook a reader of the paper will have seen:
//     sigma > 0  ->  tension
//     sigma < 0  ->  compression
// The renderer must never flip it again (TEACHING_PORT: convert once at the
// engine boundary, the UI does not re-convert).
inline double toTensionPositive(double compressionPositive) { return -compressionPositive; }

const char* failModeName(frame::FailMode m) {
    switch (m) {
        case frame::FailMode::Crush:         return "CRUSH";
        case frame::FailMode::Tension:       return "TENSION";
        case frame::FailMode::Shear:         return "SHEAR";
        case frame::FailMode::Bending:       return "BENDING";
        case frame::FailMode::Torsion:       return "TORSION";
        case frame::FailMode::ShellVonMises: return "SHELL_VM";
        default:                             return "NONE";
    }
}

// ---------------------------------------------------------------- extraction
// A member is a maximal collinear run of same-material blocks (DECISIONS D-010).
// Runs are split at junction blocks so two members that meet share one node.
struct RunSeg {
    std::vector<BlockPos> blocks;   // ordered along the run
    std::string           mat;
    std::string           section;
};

const std::array<BlockPos, 3> kAxes = { BlockPos{ 1, 0, 0 }, BlockPos{ 0, 1, 0 }, BlockPos{ 0, 0, 1 } };

BlockPos add(const BlockPos& a, const BlockPos& d) { return BlockPos{ a.x + d.x, a.y + d.y, a.z + d.z }; }
BlockPos sub(const BlockPos& a, const BlockPos& d) { return BlockPos{ a.x - d.x, a.y - d.y, a.z - d.z }; }

// Pass 1: maximal runs along each axis. Pass 2: any block used by more than one
// run, or carrying a support, becomes a node and splits the runs through it.
std::vector<RunSeg> extractRuns(const std::map<BlockPos, InBlock>& grid,
                                std::vector<BlockPos>&             unassigned) {
    std::vector<std::vector<BlockPos>> rawRuns;
    std::vector<std::string>           runMat, runSec;

    for (const BlockPos& axis : kAxes) {
        for (const auto& [pos, blk] : grid) {
            // Only start at a run head: the previous cell must not continue this run.
            auto prev = grid.find(sub(pos, axis));
            if (prev != grid.end() && prev->second.mat == blk.mat) continue;

            std::vector<BlockPos> run{ pos };
            BlockPos cur = add(pos, axis);
            for (;;) {
                auto it = grid.find(cur);
                if (it == grid.end() || it->second.mat != blk.mat) break;
                run.push_back(cur);
                cur = add(cur, axis);
            }
            // A single block is L/h = 1 — not a valid beam element. Skipped here and
            // reported back so the game side can tell the player why nothing appeared.
            if (run.size() >= 2) {
                rawRuns.push_back(std::move(run));
                runMat.push_back(blk.mat);
                runSec.push_back(blk.section);
            }
        }
    }

    // Junction detection: a block shared by two runs, or a support, is a node.
    std::map<BlockPos, int> useCount;
    for (const auto& r : rawRuns)
        for (const BlockPos& p : r) useCount[p]++;

    std::set<BlockPos> nodeBlocks;
    for (const auto& [pos, n] : useCount)
        if (n > 1) nodeBlocks.insert(pos);
    for (const auto& [pos, blk] : grid)
        if (blk.support) nodeBlocks.insert(pos);

    std::vector<RunSeg> out;
    for (size_t r = 0; r < rawRuns.size(); ++r) {
        const auto& run = rawRuns[r];
        size_t start = 0;
        for (size_t k = 1; k < run.size(); ++k) {
            const bool isEnd  = (k + 1 == run.size());
            const bool isNode = nodeBlocks.count(run[k]) > 0;
            if (isNode || isEnd) {
                RunSeg seg;
                seg.blocks.assign(run.begin() + static_cast<long>(start),
                                  run.begin() + static_cast<long>(k) + 1);
                seg.mat     = runMat[r];
                seg.section = runSec[r];
                if (seg.blocks.size() >= 2) out.push_back(std::move(seg));
                start = k;
            }
        }
    }

    std::set<BlockPos> covered;
    for (const auto& s : out)
        for (const BlockPos& p : s.blocks) covered.insert(p);
    for (const auto& [pos, blk] : grid)
        if (!covered.count(pos)) unassigned.push_back(pos);

    return out;
}

// ------------------------------------------------------------------ solving
struct SolveOut {
    bool        ok = false;
    std::string error;
    bool        singular = false;
    std::string diagnostic;
    double      maxDC = 0;
    int         governing = -1;

    // One fibre of the section at one station along the member. `dir` is the unit
    // vector, IN MINECRAFT AXES, from the member centreline out to that fibre, so
    // the renderer never has to reconstruct FrameCore's local frame — the single
    // most common source of sign errors in this kind of bridge.
    struct Fibre {
        std::string name;      // TOP_Y | BOT_Y | PLUS_Z | MINUS_Z
        McVec       dir{ 0, 0, 0 };
        double      offsetMm = 0;   // distance from centreline to the fibre
        double      sigma    = 0;   // MPa, TENSION-POSITIVE
    };
    struct Station {
        double             xMm = 0;         // arc length from end i
        McVec              worldMm{ 0, 0, 0 };
        std::vector<Fibre> fibres;
        double             sigmaTens = 0;   // worst tensile magnitude at a corner
        double             sigmaComp = 0;   // worst compressive magnitude at a corner
        double             tauShear  = 0;
        bool               hasNaY = false;  // neutral axis crosses the local-y depth
        double             naOffsetY = 0;   // offset from centreline where sigma = 0
        bool               hasNaZ = false;
        double             naOffsetZ = 0;
    };
    struct MemberOut {
        int                   id = 0;
        std::string           mat, section;
        double                lengthMm = 0;
        double                dc = 0;
        std::string           mode = "NONE";
        frame::MemberEndForces fi, fj;
        std::vector<BlockPos> blocks;
        std::vector<Station>  stations;
    };
    std::vector<MemberOut> members;
    std::vector<BlockPos>  unassigned;
};

SolveOut runSolve(const std::vector<InBlock>& blocks,
                  const std::vector<std::array<double, 6>>& pointLoads,
                  const std::vector<BlockPos>&              loadAt) {
    SolveOut out;

    std::map<BlockPos, InBlock> grid;
    for (const auto& b : blocks) grid[b.pos] = b;

    std::vector<RunSeg> segs = extractRuns(grid, out.unassigned);
    if (segs.empty()) {
        out.ok    = true;                 // not an error: nothing structural was placed
        out.error = "no members extracted";
        return out;
    }

    frame::FrameModel m;

    // Materials and sections are referenced by index, so build the tables first
    // and remember where each id landed.
    std::map<std::string, int> matIdx, secIdx;
    for (const auto& seg : segs) {
        if (!matIdx.count(seg.mat)) {
            auto it = materialCatalogue().find(seg.mat);
            if (it == materialCatalogue().end()) {
                out.error = "unknown material: " + seg.mat;
                return out;
            }
            const MatSpec& ms = it->second;
            frame::Material fm(ms.E, ms.E / (2.0 * (1.0 + ms.nu)), ms.rho);
            fm.nu  = ms.nu;
            fm.cap = frame::Capacity::make(ms.rcomp, ms.rtens, ms.rshear);
            matIdx[seg.mat] = static_cast<int>(m.materials.size());
            m.materials.push_back(fm);
        }
        if (!secIdx.count(seg.section)) {
            auto it = sectionCatalogue().find(seg.section);
            if (it == sectionCatalogue().end()) {
                out.error = "unknown section: " + seg.section;
                return out;
            }
            secIdx[seg.section] = static_cast<int>(m.sections.size());
            m.sections.push_back(it->second);
        }
    }

    // Nodes sit at block centres, so a block shared by two runs is one node.
    std::map<BlockPos, int> nodeId;
    auto nodeFor = [&](const BlockPos& p) -> int {
        auto it = nodeId.find(p);
        if (it != nodeId.end()) return it->second;
        const int id = static_cast<int>(m.nodes.size()) + 1;
        // MC (x, y, z) with y up  ->  FrameCore (x, z, y) with Z up.
        frame::Node n(id, p.x * kBlockMm, p.z * kBlockMm, p.y * kBlockMm);
        auto blk = grid.find(p);
        if (blk != grid.end() && blk->second.support) n.fixAll();
        m.nodes.push_back(n);
        nodeId[p] = id;
        return id;
    };

    int nextMember = 1;
    std::vector<SolveOut::MemberOut> mo;
    for (const auto& seg : segs) {
        const BlockPos& a = seg.blocks.front();
        const BlockPos& b = seg.blocks.back();
        const int ni = nodeFor(a), nj = nodeFor(b);
        if (ni == nj) continue;

        frame::Member mem(nextMember, ni, nj, matIdx[seg.mat], secIdx[seg.section]);
        // A vertical run must not use the default refVec (0,0,1) — it would be
        // parallel to the member axis and the local frame would be degenerate.
        const bool vertical = (a.x == b.x && a.z == b.z);
        if (vertical) mem.refVec = frame::Vec3{ 1, 0, 0 };
        m.members.push_back(mem);

        SolveOut::MemberOut o;
        o.id       = nextMember;
        o.mat      = seg.mat;
        o.section  = seg.section;
        o.blocks   = seg.blocks;
        o.lengthMm = static_cast<double>(seg.blocks.size() - 1) * kBlockMm;
        mo.push_back(std::move(o));
        ++nextMember;
    }

    if (m.members.empty()) {
        out.ok    = true;
        out.error = "no members extracted";
        return out;
    }

    // Self-weight is always on; FrameCore's helper does the kg/m^3 -> tonne/mm^3
    // bridge and rotates gravity into each member's local axes.
    frame::addSelfWeight(m);

    for (size_t k = 0; k < pointLoads.size() && k < loadAt.size(); ++k) {
        auto it = nodeId.find(loadAt[k]);
        if (it == nodeId.end()) continue;    // load on a block that is not a node: ignored
        frame::NodalLoad nl;
        nl.node = it->second;
        // Caller sends Minecraft axes; map into FrameCore's.
        nl.comp[frame::Ux] = pointLoads[k][0];
        nl.comp[frame::Uy] = pointLoads[k][2];
        nl.comp[frame::Uz] = pointLoads[k][1];
        nl.comp[frame::Rx] = pointLoads[k][3];
        nl.comp[frame::Ry] = pointLoads[k][5];
        nl.comp[frame::Rz] = pointLoads[k][4];
        m.nodalLoads.push_back(nl);
    }

    std::string why;
    if (!m.validate(why)) {
        out.error = "model invalid: " + why;
        return out;
    }

    frame::SolveResult r = frame::solve(m);
    out.singular   = r.singular;
    out.diagnostic = r.diagnostic;
    out.ok         = true;

    // A singular system means "this is a mechanism, not a structure". The forces
    // are meaningless, so nothing is reported beyond the diagnostic.
    if (r.singular) return out;

    frame::ElasticAllowable strength;
    for (size_t k = 0; k < r.memberForces.size() && k < mo.size(); ++k) {
        const frame::MemberForcePair& mf = r.memberForces[k];
        const frame::Section&  sec = m.sections[m.members[k].secIdx];
        const frame::Capacity& cap = m.materials[m.members[k].matIdx].cap;

        const frame::DemandResult di = strength.checkSection(mf.endI, sec, cap);
        const frame::DemandResult dj = strength.checkSection(mf.endJ, sec, cap);
        const frame::DemandResult& worst = (dj.risk > di.risk) ? dj : di;

        mo[k].dc   = worst.risk;
        mo[k].mode = failModeName(worst.mode);
        mo[k].fi   = mf.endI;
        mo[k].fj   = mf.endJ;

        if (worst.risk > out.maxDC) { out.maxDC = worst.risk; out.governing = mo[k].id; }
    }

    // ---- fibre stress field: the data the stress overlay actually draws ----
    // Built here rather than taken from computeStressField.
    //
    // Two reasons, both measured (see verify.py C1c):
    //
    //  1. StressKernel::memberFiberSigma pairs Mz with cy and My with cz, while
    //     the rest of the engine pairs them the other way: ElasticAllowable
    //     screens against Wz = Iz/cz, and a cantilever's tip deflection is
    //     exactly P L^3 / (3 E Iz) to machine precision. On a square section
    //     cy == cz and the difference is invisible; on a 200x400 section the
    //     kernel returns half the extreme-fibre stress.
    //
    //  2. computeStressField's station moments carry the distributed-load
    //     curvature with the wrong sign: on a cantilever it reconstructs a
    //     non-zero moment at the free tip, where the member's own end forces
    //     (which ARE correct) report zero.
    //
    // The member end forces are trustworthy, so the diagram is rebuilt from them
    // by textbook superposition:
    //     M(x) = M_i (1-t) + M_j t + (w/2) x (L-x)          t = x/L
    // — the straight line between the two verified end moments, plus the
    // parabola a uniform load adds, which vanishes at both ends by construction.
    // Shear is linear under a uniform load, so interpolating it is exact.
    const int kStations = 11;
    for (size_t k = 0; k < r.memberForces.size() && k < mo.size(); ++k) {
        const frame::Member&  mem = m.members[k];
        const frame::Section& sec = m.sections[mem.secIdx];
        const frame::MemberForcePair& mf = r.memberForces[k];

        const int ii = m.nodeIndex(mem.i), ij = m.nodeIndex(mem.j);
        if (ii < 0 || ij < 0) continue;
        const frame::Vec3& pi = m.nodes[ii].pos;
        const frame::Vec3& pj = m.nodes[ij].pos;

        frame::Vec3 ax, ay, az;
        frame::memberLocalAxes(pi, pj, mem.refVec, ax, ay, az);
        const McVec dTopY  = fcToMc(ay);
        const McVec dBotY  = McVec{ -dTopY.x, -dTopY.y, -dTopY.z };
        const McVec dPlusZ = fcToMc(az);
        const McVec dMinZ  = McVec{ -dPlusZ.x, -dPlusZ.y, -dPlusZ.z };

        // Self-weight was added as a member UDL in local axes; read it back so the
        // parabolic term uses the same numbers the solver did.
        double wy = 0, wz = 0;
        for (const frame::MemberUDL& u : m.memberUDLs)
            if (u.member == mem.id) { wy = u.w_local.y; wz = u.w_local.z; }

        auto& dst = mo[k];
        const double L = dst.lengthMm;
        for (int q = 0; q < kStations; ++q) {
            const double t = (kStations > 1) ? static_cast<double>(q) / (kStations - 1) : 0.0;
            const double x = t * L;

            const double Nx  = mf.endI.N  * (1 - t) + mf.endJ.N  * t;
            const double Vyx = mf.endI.Vy * (1 - t) + mf.endJ.Vy * t;
            const double Mzx = mf.endI.Mz * (1 - t) + mf.endJ.Mz * t + (wy / 2.0) * x * (L - x);
            const double Myx = mf.endI.My * (1 - t) + mf.endJ.My * t + (wz / 2.0) * x * (L - x);

            SolveOut::Station st;
            st.xMm = x;
            const frame::Vec3 wp{ pi.x + (pj.x - pi.x) * t,
                                  pi.y + (pj.y - pi.y) * t,
                                  pi.z + (pj.z - pi.z) * t };
            st.worldMm = fcToMc(wp);

            // FrameCore's N is compression-positive; the axial term is negated for
            // the tension-positive output. The bending sign is pinned by physics
            // and locked by C1c: a cantilever pushed down must read TENSION on top.
            const double axial = toTensionPositive(Nx / sec.A);
            const double bendY = (sec.Iz > 0) ? Mzx * sec.cz / sec.Iz : 0.0;  // varies along local y
            const double bendZ = (sec.Iy > 0) ? Myx * sec.cy / sec.Iy : 0.0;  // varies along local z

            const double sTop = axial + bendY;
            const double sBot = axial - bendY;
            const double sPls = axial + bendZ;
            const double sMin = axial - bendZ;

            st.fibres.push_back({ "TOP_Y",   dTopY,  sec.cz, sTop });
            st.fibres.push_back({ "BOT_Y",   dBotY,  sec.cz, sBot });
            st.fibres.push_back({ "PLUS_Z",  dPlusZ, sec.cy, sPls });
            st.fibres.push_back({ "MINUS_Z", dMinZ,  sec.cy, sMin });

            st.sigmaTens = std::max({ 0.0, sTop, sBot, sPls, sMin });
            st.sigmaComp = std::max({ 0.0, -sTop, -sBot, -sPls, -sMin });
            st.tauShear  = (sec.A > 0) ? 1.5 * std::fabs(Vyx) / sec.A : 0.0;

            // Neutral axis: sigma is linear across the depth, so where two opposing
            // fibres differ in sign it crosses at cz (sTop + sBot) / (sTop - sBot),
            // measured from the centroid. Reported only when it really falls inside
            // the section — a fully tensile or fully compressive section has no
            // neutral axis and inventing one would be a lie.
            if ((sTop > 0) != (sBot > 0) && std::fabs(sTop - sBot) > 1e-12) {
                st.hasNaY    = true;
                st.naOffsetY = sec.cz * (sTop + sBot) / (sTop - sBot);
            }
            if ((sPls > 0) != (sMin > 0) && std::fabs(sPls - sMin) > 1e-12) {
                st.hasNaZ    = true;
                st.naOffsetZ = sec.cy * (sPls + sMin) / (sPls - sMin);
            }
            dst.stations.push_back(std::move(st));
        }
    }

    out.members = std::move(mo);
    return out;
}

// ------------------------------------------------------------------ protocol
void writeForces(bjson::Writer& w, const char* key, const frame::MemberEndForces& f) {
    w.key(key).beginObj();
    w.kv("N", f.N).kv("Vy", f.Vy).kv("Vz", f.Vz).kv("T", f.T).kv("My", f.My).kv("Mz", f.Mz);
    w.endObj();
}

void writeBlocks(bjson::Writer& w, const char* key, const std::vector<BlockPos>& v) {
    w.key(key).beginArr();
    for (const BlockPos& p : v) {
        w.beginArr().val(p.x).val(p.y).val(p.z).endArr();
    }
    w.endArr();
}

std::string handleSolve(const bjson::Value& req) {
    const long long revision = req.i64("revision", 0);

    std::vector<InBlock> blocks;
    for (const auto& bv : req.arr("blocks")) {
        if (bv.t != bjson::Value::T::Obj) continue;
        InBlock b;
        b.pos.x   = static_cast<int>(bv.num("x"));
        b.pos.y   = static_cast<int>(bv.num("y"));
        b.pos.z   = static_cast<int>(bv.num("z"));
        b.mat     = bv.str("mat", "steel");
        b.section = bv.str("section", "steel_h400");
        b.support = bv.boolean("support", false);
        blocks.push_back(b);
    }

    std::vector<std::array<double, 6>> loads;
    std::vector<BlockPos>              loadAt;
    for (const auto& lv : req.arr("loads")) {
        if (lv.t != bjson::Value::T::Obj) continue;
        BlockPos p{ static_cast<int>(lv.num("x")),
                    static_cast<int>(lv.num("y")),
                    static_cast<int>(lv.num("z")) };
        loadAt.push_back(p);
        loads.push_back({ lv.num("fx"), lv.num("fy"), lv.num("fz"),
                          lv.num("mx"), lv.num("my"), lv.num("mz") });
    }

    SolveOut s = runSolve(blocks, loads, loadAt);

    bjson::Writer w;
    w.beginObj();
    w.kv("ok", s.ok).kv("op", "solve").kv("revision", revision);
    if (!s.ok) {
        w.kv("error", s.error);
        w.endObj();
        return w.done();
    }
    w.kv("singular", s.singular);
    if (!s.diagnostic.empty()) w.kv("diagnostic", s.diagnostic);
    if (!s.error.empty())      w.kv("note", s.error);
    w.kv("maxDC", s.maxDC).kv("governing", s.governing);

    w.key("members").beginArr();
    for (const auto& mm : s.members) {
        w.beginObj();
        w.kv("id", mm.id).kv("mat", mm.mat).kv("section", mm.section);
        w.kv("lengthMm", mm.lengthMm).kv("dc", mm.dc).kv("mode", mm.mode);
        writeForces(w, "i", mm.fi);
        writeForces(w, "j", mm.fj);
        writeBlocks(w, "blocks", mm.blocks);

        w.key("stations").beginArr();
        for (const auto& st : mm.stations) {
            w.beginObj();
            w.kv("x", st.xMm);
            w.key("world").beginArr().val(st.worldMm.x).val(st.worldMm.y).val(st.worldMm.z).endArr();
            w.key("fibres").beginArr();
            for (const auto& f : st.fibres) {
                w.beginObj();
                w.kv("name", f.name).kv("offsetMm", f.offsetMm).kv("sigma", f.sigma);
                w.key("dir").beginArr().val(f.dir.x).val(f.dir.y).val(f.dir.z).endArr();
                w.endObj();
            }
            w.endArr();
            w.kv("sigmaTens", st.sigmaTens).kv("sigmaComp", st.sigmaComp).kv("tau", st.tauShear);
            if (st.hasNaY) w.kv("naY", st.naOffsetY);
            if (st.hasNaZ) w.kv("naZ", st.naOffsetZ);
            w.endObj();
        }
        w.endArr();
        w.endObj();
    }
    w.endArr();
    writeBlocks(w, "unassigned", s.unassigned);
    w.endObj();
    return w.done();
}

std::string handleHello() {
    bjson::Writer w;
    w.beginObj();
    w.kv("ok", true).kv("op", "hello").kv("engine", "FrameCore").kv("protocol", kProtocol);
    w.key("materials").beginArr();
    for (const auto& [id, _] : materialCatalogue()) w.val(id);
    w.endArr();
    w.key("sections").beginArr();
    for (const auto& [id, _] : sectionCatalogue()) w.val(id);
    w.endArr();
    w.endObj();
    return w.done();
}

std::string errorLine(const std::string& msg, long long revision) {
    bjson::Writer w;
    w.beginObj();
    w.kv("ok", false).kv("error", msg).kv("revision", revision);
    w.endObj();
    return w.done();
}

}  // namespace

int main() {
    std::ios::sync_with_stdio(false);
    std::string line;

    // EOF ends the process: when the parent JVM dies the pipe closes, so no
    // zombie sidecar can outlive the server.
    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;

        const bjson::Value req = bjson::parse(line);
        if (req.isNull() || req.t != bjson::Value::T::Obj) {
            std::cout << errorLine("malformed request", 0) << '\n' << std::flush;
            continue;
        }

        const std::string op = req.str("op");
        std::string reply;

        // Any failure below is reported as a normal error line. Nothing throws
        // across the protocol boundary, so a bad request never kills the sidecar.
        try {
            if      (op == "hello") reply = handleHello();
            else if (op == "solve") reply = handleSolve(req);
            else if (op == "bye")   break;
            else                    reply = errorLine("unknown op: " + op, req.i64("revision", 0));
        } catch (const std::exception& e) {
            reply = errorLine(std::string("engine exception: ") + e.what(), req.i64("revision", 0));
        } catch (...) {
            reply = errorLine("engine exception: unknown", req.i64("revision", 0));
        }

        std::cout << reply << '\n' << std::flush;
    }
    return 0;
}
