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
//
// NAMING: these are SOLID RECTANGLES and SOLID CIRCLES, and the tokens now say so.
// They were previously called steel_h400 / rc_400x600, which claimed an I-section
// and a reinforced-concrete composite that neither the geometry nor the material
// model delivers — an H-section of the same depth has a very different Iz, Iy and
// self-weight. A token that names a section the engine is not solving is a lie
// that survives every test, because the tests use the token too (issue #13).
//
// Real H-sections and real RC composites need traceable A/Iy/Iz/J/mass data and,
// for RC, a steel-plus-concrete section. Until then these are honestly named
// fixture sections.
const std::map<std::string, frame::Section>& sectionCatalogue() {
    static const std::map<std::string, frame::Section> kS = {
        { "steel_rect_200x400",    frame::Section::Rectangular(200.0, 400.0) },
        { "steel_rect_150x300",    frame::Section::Rectangular(150.0, 300.0) },
        { "steel_rect_100x200",    frame::Section::Rectangular(100.0, 200.0) },
        { "concrete_rect_400x600", frame::Section::Rectangular(400.0, 600.0) },
        { "rebar_round_d25",       frame::Section::Circular(12.5) },
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

    // A run follows MATERIAL continuity: the steel really is continuous through a change
    // of section, so breaking the run there would leave two members that do not touch,
    // and the far one would float free as a mechanism.
    //
    // The section change is instead a NODE, handled below by the same splitting that
    // handles junctions — which shares the boundary block between both segments and so
    // keeps them connected. Comparing material alone was still a bug: the old code let a
    // run keep the head block's section straight through the change, and solved a 200x400
    // that became a 100x200 halfway along as 200x400 throughout, with ok:true (issue #13).
    auto continues = [](const InBlock& a, const InBlock& b) { return a.mat == b.mat; };

    for (const BlockPos& axis : kAxes) {
        for (const auto& [pos, blk] : grid) {
            // Only start at a run head: the previous cell must not continue this run.
            auto prev = grid.find(sub(pos, axis));
            if (prev != grid.end() && continues(prev->second, blk)) continue;

            std::vector<BlockPos> run{ pos };
            BlockPos cur = add(pos, axis);
            for (;;) {
                auto it = grid.find(cur);
                if (it == grid.end() || !continues(it->second, blk)) break;
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

    // A section change is a node too. The boundary block is shared by both segments, so
    // the member stays continuous — which is what the physical steel does.
    for (const auto& run : rawRuns) {
        for (size_t k = 1; k < run.size(); ++k) {
            if (grid.at(run[k]).section != grid.at(run[k - 1]).section) nodeBlocks.insert(run[k]);
        }
    }

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
                seg.mat = runMat[r];
                // The segment's section is the one its own first block declares, not the
                // run head's. At the boundary block the two sections meet inside a single
                // one-metre cube; the block is assigned to the segment that starts there.
                // That is a real approximation of the 1 m grid, stated rather than hidden.
                seg.section = grid.at(run[start]).section;
                if (seg.blocks.size() >= 2) out.push_back(std::move(seg));
                start = k;
            }
        }
    }
    (void) runSec;

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
        // The GOVERNING FIBRE, not a load type and not a product failure event.
        // ElasticAllowable takes the argmax of five ratios, so steel in pure bending
        // reports the compression fibre — its compressive allowable is the lower one.
        // Naming it `mode` invited a downstream reader to route a steel member into a
        // concrete-crushing effect (issue #16), so the wire now says what it means.
        std::string           governingFibre = "NONE";
        int                   governingStation = -1;   // index into stations
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
        //
        // Plus half a block: a Minecraft block coordinate names the block's CORNER, and
        // the node belongs at its centre. The offset is a uniform translation of the whole
        // model, so no force, stress or D/C changes by a bit — but the `world` positions
        // this sidecar reports are what the overlay draws at, and without it every ribbon
        // would hang half a block off the beam in all three axes.
        const double h = kBlockMm / 2.0;
        frame::Node n(id, p.x * kBlockMm + h, p.z * kBlockMm + h, p.y * kBlockMm + h);
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

    // FAIL-CLOSED. A load whose block is not an analysis node used to be skipped with a
    // `continue`, and the reply still said ok — so a load dropped in the middle of a long
    // run silently vanished and the structure came back SAFER than it is. Silently
    // reporting safe is the worst failure this program can have (issue #14).
    //
    // The right long-term answer is to split the member at the load point, or to carry it
    // as a member load. Until one of those exists, the request is refused.
    for (size_t k = 0; k < pointLoads.size() && k < loadAt.size(); ++k) {
        auto it = nodeId.find(loadAt[k]);
        if (it == nodeId.end()) {
            const BlockPos& p = loadAt[k];
            out.ok    = false;
            out.error = "load at (" + std::to_string(p.x) + "," + std::to_string(p.y) + ","
                      + std::to_string(p.z) + ") is not on an analysis node; "
                        "loads inside a member are not representable yet";
            return out;
        }
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
    //
    // D/C IS TAKEN FROM THESE STATIONS, NOT FROM THE TWO ENDS. Screening only the ends
    // reports a simply supported beam under its own weight as unstressed: both end
    // moments are zero while midspan carries wL^2/8. That is a "silently safe" answer,
    // the most dangerous kind (issue #14). The uniform stations are joined by the
    // ANALYTIC extremum of the moment diagram, so the controlling section is captured
    // exactly rather than nearly.
    const int kUniformStations = 11;
    frame::ElasticAllowable strength;

    for (size_t k = 0; k < r.memberForces.size() && k < mo.size(); ++k) {
        const frame::Member&  mem = m.members[k];
        const frame::Section& sec = m.sections[mem.secIdx];
        const frame::Capacity& cap = m.materials[mem.matIdx].cap;
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
        dst.fi = mf.endI;
        dst.fj = mf.endJ;

        // Uniform stations for the picture, plus the analytic moment extremum for the
        // check. M(x) = M_i(1-t) + M_j t + (w/2) x (L-x), so dM/dx = 0 at
        //     x* = L/2 + (M_j - M_i) / (w L)
        // which lands at midspan for a symmetric simply supported beam and outside the
        // member (hence discarded) for a cantilever.
        std::vector<double> ts;
        ts.reserve(kUniformStations + 2);
        for (int q = 0; q < kUniformStations; ++q) {
            ts.push_back(static_cast<double>(q) / (kUniformStations - 1));
        }
        auto addExtremum = [&](double mi, double mj, double w) {
            if (std::fabs(w) < 1e-12 || L <= 0) return;
            const double xs = L / 2.0 + (mj - mi) / (w * L);
            const double t = xs / L;
            if (t > 1e-9 && t < 1 - 1e-9) ts.push_back(t);
        };
        addExtremum(mf.endI.Mz, mf.endJ.Mz, wy);
        addExtremum(mf.endI.My, mf.endJ.My, wz);
        std::sort(ts.begin(), ts.end());
        ts.erase(std::unique(ts.begin(), ts.end(),
                             [](double a, double b) { return std::fabs(a - b) < 1e-9; }),
                 ts.end());

        for (size_t q = 0; q < ts.size(); ++q) {
            const double t = ts[q];
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

            // Neutral axis. Stress is linear across the depth, so along the TOP_Y
            // direction sigma(y) = axial + bendY * (y / cz), which is zero at
            //     y0 = -cz * (sTop + sBot) / (sTop - sBot)
            //
            // The MINUS SIGN IS NOT OPTIONAL and was missing here. Add tension to a
            // beam and the neutral axis moves DOWN, towards the compression face; the
            // unsigned version moved it up. Pure bending has (sTop + sBot) == 0, so
            // both versions agree, and the only test that existed checked |naY| != 0 —
            // which the wrong sign passes (issue #15).
            //
            // Reported only when it really falls inside the section. A fully tensile or
            // fully compressive section has no neutral axis, and inventing one would
            // draw a line through a member that does not have one.
            if ((sTop > 0) != (sBot > 0) && std::fabs(sTop - sBot) > 1e-12) {
                st.hasNaY    = true;
                st.naOffsetY = -sec.cz * (sTop + sBot) / (sTop - sBot);
            }
            if ((sPls > 0) != (sMin > 0) && std::fabs(sPls - sMin) > 1e-12) {
                st.hasNaZ    = true;
                st.naOffsetZ = -sec.cy * (sPls + sMin) / (sPls - sMin);
            }

            // Capacity screen AT THIS STATION, from the same recovered section forces
            // the overlay draws. One recovery, one set of numbers: the picture and the
            // decision can no longer disagree.
            frame::MemberEndForces fx;
            fx.N  = Nx;
            fx.Vy = Vyx;
            fx.Vz = mf.endI.Vz * (1 - t) + mf.endJ.Vz * t;
            fx.T  = mf.endI.T  * (1 - t) + mf.endJ.T  * t;
            fx.My = Myx;
            fx.Mz = Mzx;
            const frame::DemandResult d = strength.checkSection(fx, sec, cap);
            if (d.risk > dst.dc) {
                dst.dc               = d.risk;
                dst.governingFibre   = failModeName(d.mode);
                dst.governingStation = static_cast<int>(q);
            }

            dst.stations.push_back(std::move(st));
        }

        if (dst.dc > out.maxDC) { out.maxDC = dst.dc; out.governing = dst.id; }
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

// Fail-closed input parsing.
//
// The previous version defaulted a missing material to steel and a missing section to
// the 200x400 rectangle, truncated any double to an int for coordinates, and let a
// repeated coordinate overwrite the earlier one. Each of those turns a malformed request
// into a DIFFERENT, PERFECTLY SOLVABLE STRUCTURE, and the reply still says ok — the
// caller has no way to find out it asked one question and got the answer to another
// (issue #18). Every one of them is now an error line.
std::string errorLine(const std::string& msg, long long revision);

bool readCoord(const bjson::Value& v, const char* key, int& out, std::string& err) {
    if (!v.isFiniteNum(key)) { err = std::string("field '") + key + "' missing or not a finite number"; return false; }
    const double d = v.num(key);
    if (d != std::floor(d))  { err = std::string("field '") + key + "' is not an integer"; return false; }
    if (d < -30000000.0 || d > 30000000.0) { err = std::string("field '") + key + "' out of world range"; return false; }
    out = static_cast<int>(d);
    return true;
}

bool readForce(const bjson::Value& v, const char* key, double& out, std::string& err) {
    if (!v.has(key)) { out = 0; return true; }          // absent means zero, explicitly
    if (!v.isFiniteNum(key)) { err = std::string("field '") + key + "' is not a finite number"; return false; }
    out = v.num(key);
    return true;
}

std::string handleSolve(const bjson::Value& req) {
    // revision travels as an integer field and must round-trip exactly. Rejecting a
    // non-integer here is cheap; a revision that silently changed value would defeat the
    // one mechanism that keeps stale results from causing damage.
    if (!req.isFiniteNum("revision")) return errorLine("'revision' missing or not a finite number", 0);
    const double revd = req.num("revision");
    if (revd != std::floor(revd) || revd < 0 || revd > 9.007199254740992e15) {
        return errorLine("'revision' must be a non-negative integer", 0);
    }
    const long long revision = static_cast<long long>(revd);

    const auto& matCat = materialCatalogue();
    const auto& secCat = sectionCatalogue();

    std::vector<InBlock> blocks;
    std::set<BlockPos>   seen;
    std::string          err;

    for (const auto& bv : req.arr("blocks")) {
        if (bv.t != bjson::Value::T::Obj) return errorLine("blocks[] entry is not an object", revision);
        InBlock b;
        if (!readCoord(bv, "x", b.pos.x, err)) return errorLine("block: " + err, revision);
        if (!readCoord(bv, "y", b.pos.y, err)) return errorLine("block: " + err, revision);
        if (!readCoord(bv, "z", b.pos.z, err)) return errorLine("block: " + err, revision);

        if (!seen.insert(b.pos).second) {
            return errorLine("duplicate block coordinate; the caller and the engine disagree "
                             "about what is at that position", revision);
        }
        if (!bv.isStr("mat"))     return errorLine("block: 'mat' missing", revision);
        if (!bv.isStr("section")) return errorLine("block: 'section' missing", revision);

        b.mat     = bv.str("mat");
        b.section = bv.str("section");
        b.support = bv.boolean("support", false);

        if (!matCat.count(b.mat))     return errorLine("unknown material '" + b.mat + "'", revision);
        if (!secCat.count(b.section)) return errorLine("unknown section '" + b.section + "'", revision);

        blocks.push_back(b);
    }

    std::vector<std::array<double, 6>> loads;
    std::vector<BlockPos>              loadAt;
    for (const auto& lv : req.arr("loads")) {
        if (lv.t != bjson::Value::T::Obj) return errorLine("loads[] entry is not an object", revision);
        BlockPos p;
        if (!readCoord(lv, "x", p.x, err)) return errorLine("load: " + err, revision);
        if (!readCoord(lv, "y", p.y, err)) return errorLine("load: " + err, revision);
        if (!readCoord(lv, "z", p.z, err)) return errorLine("load: " + err, revision);

        std::array<double, 6> f{};
        static const char* kComp[6] = { "fx", "fy", "fz", "mx", "my", "mz" };
        for (int i = 0; i < 6; ++i) {
            if (!readForce(lv, kComp[i], f[i], err)) return errorLine("load: " + err, revision);
        }
        loadAt.push_back(p);
        loads.push_back(f);
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
        w.kv("lengthMm", mm.lengthMm).kv("dc", mm.dc);
        // Named for what it is. `failureType` (NONE / FRACTURE / CRUSHING / MECHANISM)
        // and `handoffType` are deliberately NOT emitted yet: nothing here decides them,
        // and absence is a safer default than a guess a downstream reader would act on.
        w.kv("governingFibre", mm.governingFibre).kv("governingStation", mm.governingStation);
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
