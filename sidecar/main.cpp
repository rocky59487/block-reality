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
// NO MECHANICS ARE COMPUTED IN THIS FILE. Every stress, force, D/C, buckling
// factor, neutral axis and recovery on the wire is the return value of a FrameCore
// function, each behind the engine's own closed-form gates (F1..F76). What this
// process owns is the MODEL — which blocks become members, plates and nodes
// (D-006, D-010, D-016) — and the wire: JSON en/decoding, the axis map, the mm
// scale, and one declared sign flip (compression-positive engine -> tension-
// positive wire). The single deliberate exception is the equilibrium residual,
// which is INDEPENDENTLY recomputed from geometry and density precisely so it can
// cross-check the engine rather than quote it.
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
#include "FrameCore/ShellEdgeRecovery.h"
#include "FrameCore/MemberGeometry.h"
#include "FrameCore/BucklingAnalysis.h"
#include "FrameCore/BucklingResult.h"

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

// --------------------------------------------------------- plate catalogue
// A PLATE token declares a shell facet, not a beam section. The token decides which
// element a block becomes — geometry never guesses.
//
// Guessing was the alternative, and it is worse than it looks. A floor built out of
// beam-token blocks extracts as a GRILLAGE: every block belongs to one run along X and
// one along Z, so its self weight is applied twice and its bending stiffness is counted
// twice — once about each axis, by two elements that also each carry the full section.
// Nothing in the reply says so. Letting the token name the element makes the two cases
// distinguishable by construction: a slab block is a slab and a beam block is a beam,
// even when they sit in the same plane.
//
// Thickness is a real engineering dimension, decoupled from the one-metre cube exactly
// as a beam section is (D-004): a 1 m block declares a 200 mm slab.
const std::map<std::string, double>& plateCatalogue() {
    static const std::map<std::string, double> kP = {
        { "concrete_slab_200", 200.0 },
        { "concrete_slab_150", 150.0 },
        { "steel_plate_20",     20.0 },
    };
    return kP;
}

inline bool isPlate(const std::string& token) { return plateCatalogue().count(token) > 0; }

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

// ------------------------------------------------------------ sheet extraction
// A shell facet is a 2x2 square of plate blocks lying in one plane, with its four
// corner nodes at those blocks' CENTRES — the same node convention members use, so a
// slab and the column bearing on it can share a node rather than merely touch.
//
// An N x M slab therefore meshes into (N-1) x (M-1) facets covering N x M nodes. The
// modelled plate is half a block short of the visible slab at each edge, which is the
// SAME centre-to-centre convention a member already has (a 5-block beam is 4 m long).
// One convention, stated once, rather than two that disagree at the joint.
struct QuadSeg {
    BlockPos    c[4];             // corner blocks, ordered CCW about +normal
    std::string mat;
    std::string plate;
    int         normalAxis = 1;   // 0 = MC x, 1 = MC y, 2 = MC z
};

// The plane a plate block belongs to is DERIVED, not declared: a sheet is one block
// thick, so its normal is the axis along which the block has no plate neighbour.
//
// Anything but exactly one such axis is refused rather than resolved:
//   * three free axes  — a lone block, no plane to lie in;
//   * two free axes    — a one-block-wide strip, which cannot close a 2x2 square;
//   * zero free axes   — plate blocks stacked into a SOLID. A solid is not a shell, and
//                        meshing it as three intersecting sheets would triple its mass
//                        and its stiffness. That is the grillage bug in another costume.
// Each refused block comes back as `unassigned`, so the game side can say why nothing
// appeared instead of leaving a floor that silently carries no load.
int sheetNormalAxis(const std::map<BlockPos, InBlock>& grid, const BlockPos& p, const InBlock& b) {
    int free = 0, axis = -1;
    for (int a = 0; a < 3; ++a) {
        bool neighbour = false;
        for (int s = -1; s <= 1; s += 2) {
            BlockPos q = p;
            (a == 0 ? q.x : a == 1 ? q.y : q.z) += s;
            auto it = grid.find(q);
            if (it != grid.end() && it->second.mat == b.mat && it->second.section == b.section) {
                neighbour = true;
                break;
            }
        }
        if (!neighbour) { ++free; axis = a; }
    }
    return free == 1 ? axis : -1;
}

std::vector<QuadSeg> extractSheets(const std::map<BlockPos, InBlock>& grid,
                                   std::set<BlockPos>&                shellNodes,
                                   std::vector<BlockPos>&             unassigned) {
    std::map<BlockPos, int> normalOf;
    for (const auto& [pos, blk] : grid) {
        if (!isPlate(blk.section)) continue;
        const int n = sheetNormalAxis(grid, pos, blk);
        if (n >= 0) normalOf[pos] = n;
    }

    std::vector<QuadSeg> out;
    for (const auto& [pos, n] : normalOf) {
        const InBlock& b = grid.at(pos);
        const BlockPos U = kAxes[(n + 1) % 3];
        const BlockPos V = kAxes[(n + 2) % 3];
        const BlockPos q[4] = { pos, add(pos, U), add(add(pos, U), V), add(pos, V) };

        bool complete = true;
        for (const BlockPos& c : q) {
            auto it = normalOf.find(c);
            if (it == normalOf.end() || it->second != n) { complete = false; break; }
            const InBlock& cb = grid.at(c);
            if (cb.mat != b.mat || cb.section != b.section) { complete = false; break; }
        }
        if (!complete) continue;

        QuadSeg s;
        for (int k = 0; k < 4; ++k) s.c[k] = q[k];
        s.mat        = b.mat;
        s.plate      = b.section;
        s.normalAxis = n;
        out.push_back(s);
        for (const BlockPos& c : q) shellNodes.insert(c);
    }

    // A plate block that closed no square is reported, never quietly ignored.
    for (const auto& [pos, blk] : grid) {
        if (!isPlate(blk.section)) continue;
        if (!shellNodes.count(pos)) unassigned.push_back(pos);
    }
    return out;
}

// Pass 1: maximal runs along each axis. Pass 2: any block used by more than one
// run, or carrying a support, becomes a node and splits the runs through it.
//
// Plate blocks take no part in run finding — they are already shells. A run may,
// however, BEAR ON one: if the block immediately beyond a run's end is a plate block
// that carries a shell node, the run is extended into it and the two elements share
// that node. That is how a column supports a floor, and it is the only coupling
// offered: a beam merely running ALONGSIDE a slab is not attached to it, because
// nothing in the geometry says it should be.
std::vector<RunSeg> extractRuns(const std::map<BlockPos, InBlock>& grid,
                                const std::set<BlockPos>&          shellNodes,
                                const std::set<BlockPos>&          loadBlocks,
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
    auto continues = [&](const InBlock& a, const InBlock& b) {
        return !isPlate(a.section) && !isPlate(b.section) && a.mat == b.mat;
    };
    auto isFrame = [&](const std::map<BlockPos, InBlock>::const_iterator& it) {
        return it != grid.end() && !isPlate(it->second.section);
    };

    for (const BlockPos& axis : kAxes) {
        for (const auto& [pos, blk] : grid) {
            if (isPlate(blk.section)) continue;
            // Only start at a run head: the previous cell must not continue this run.
            auto prev = grid.find(sub(pos, axis));
            if (isFrame(prev) && continues(prev->second, blk)) continue;

            std::vector<BlockPos> run{ pos };
            BlockPos cur = add(pos, axis);
            for (;;) {
                auto it = grid.find(cur);
                if (!isFrame(it) || !continues(it->second, blk)) break;
                run.push_back(cur);
                cur = add(cur, axis);
            }
            // Bearing: extend into an adjacent plate block at either end so the run ENDS
            // on the shell node instead of one block short of it. Without this a steel
            // column under a concrete slab stops at its own top block, the slab hangs on
            // nothing, and the whole model is a mechanism — while every member in it still
            // reports a believable stress.
            const BlockPos before = sub(run.front(), axis);
            if (shellNodes.count(before)) run.insert(run.begin(), before);
            const BlockPos beyond = add(run.back(), axis);
            if (shellNodes.count(beyond)) run.push_back(beyond);

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

    // A LOADED BLOCK IS A NODE. Without this a load anywhere but a member's end had no
    // representation, and the whole request was refused rather than answered — so a player
    // hanging a weight on the middle of a beam, which is the single most obvious thing to
    // do with this mod, killed the analysis of every structure in the world.
    //
    // Refusing was still the right call at the time: the alternative then in place dropped
    // such a load silently and reported the structure SAFER than it is. Splitting the run
    // is the answer both of those were standing in for. The two halves share the loaded
    // block, so the member stays continuous and the load lands exactly where the player
    // put it — no lever arm invented, nothing moved to the nearest end.
    for (const BlockPos& p : loadBlocks)
        if (grid.count(p)) nodeBlocks.insert(p);

    // A section change is a node too. The boundary block is shared by both segments, so
    // the member stays continuous — which is what the physical steel does. A bearing
    // plate block is skipped here: it is not a change of beam section, it is the end of
    // the beam, and it is already a node because it is a shell corner.
    for (const auto& run : rawRuns) {
        for (size_t k = 1; k < run.size(); ++k) {
            const InBlock& a = grid.at(run[k - 1]);
            const InBlock& b = grid.at(run[k]);
            if (isPlate(a.section) || isPlate(b.section)) continue;
            if (a.section != b.section) nodeBlocks.insert(run[k]);
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
                //
                // A bearing plate block declares a PLATE token, which names no beam
                // section, so the search walks past it to the first block that does.
                seg.section.clear();
                for (const BlockPos& p : seg.blocks) {
                    const std::string& s = grid.at(p).section;
                    if (!isPlate(s)) { seg.section = s; break; }
                }
                if (seg.blocks.size() >= 2 && !seg.section.empty()) out.push_back(std::move(seg));
                start = k;
            }
        }
    }
    (void) runSec;

    std::set<BlockPos> covered;
    for (const auto& s : out)
        for (const BlockPos& p : s.blocks) covered.insert(p);
    for (const auto& [pos, blk] : grid) {
        if (isPlate(blk.section)) continue;       // plate blocks are answered by the sheet pass
        if (!covered.count(pos)) unassigned.push_back(pos);
    }

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

        // The stress FIELD, not samples of it. With the member's local frame, its
        // section properties and the uniform load, the client can evaluate
        //     sigma(x, y, z) = -N/A + Mz*y/Iz - My*z/Iy
        // exactly at any point, which is what a surface contour needs. Sending eleven
        // stations instead would force the renderer to interpolate between samples of a
        // function it could simply evaluate.
        McVec originMm{ 0, 0, 0 };            // node i, Minecraft world mm
        McVec axisX{ 1, 0, 0 };               // member axis, unit, Minecraft space
        McVec axisY{ 0, 1, 0 };               // local y ("top") direction
        McVec axisZ{ 0, 0, 1 };               // local z direction
        double A = 0, Iy = 0, Iz = 0, cy = 0, cz = 0;
        double wy = 0, wz = 0;                // uniform load in local axes, N/mm
    };

    // One MITC4 facet, with everything a client needs to evaluate its surface stress.
    //
    // A shell's state is a 2-D stress TENSOR, not a scalar, so what travels is the
    // tensor: membrane resultants (N/mm) and bending resultants (N*mm/mm) from which
    //     sigma = N/t  +/-  6M/t^2
    // gives the top and bottom faces. Bending is sent PER CORNER as well as at the
    // centre, because the field varies across a facet and the centre value alone would
    // flatten the very peaks a design check exists to find.
    struct ShellOut {
        int         id = 0;
        std::string mat, plate;
        double      t  = 0;
        double      dc = 0;
        bool        governingTop = true;      // which face carries the worst von Mises
        int         governingCorner = -1;     // -1 = centre, 0..3 = corner
        std::array<BlockPos, 4> blocks{};
        std::array<McVec, 4>    world{};      // corner node positions, Minecraft mm
        McVec       ex{ 1, 0, 0 }, ey{ 0, 0, 1 }, normal{ 0, 1, 0 };   // facet local frame
        double      Nxx = 0, Nyy = 0, Nxy = 0;
        double      Mxx = 0, Myy = 0, Mxy = 0;
        double      Qx = 0, Qy = 0;
        // Per-corner {Mxx, Myy, Mxy}. `Mc` is what the demand and the contour are based
        // on, so it carries the recovered edge values where recovery fired; `McRaw` is
        // always the element's own output. Both travel: a document that quotes the size of
        // a correction has to be able to show the number before it as well as after.
        std::array<std::array<double, 3>, 4> Mc{};
        std::array<std::array<double, 3>, 4> McRaw{};
        double      vmTop = 0, vmBot = 0;     // von Mises at the centre, both faces
        double      dcRaw = 0;                // before support-moment recovery
        bool        edgeRecovered = false;    // a clamped edge was extrapolated to
    };

    std::vector<MemberOut> members;
    std::vector<ShellOut>  shells;
    std::vector<BlockPos>  unassigned;
    std::string            governingKind;     // "member" | "shell" | ""

    // A world holds many structures, and they are solved one at a time. `singular` stays
    // true if ANY of them is a mechanism, but the others still report their results.
    int islands         = 0;
    int singularIslands = 0;

    // Global force equilibrium, summed over every island that solved, in Minecraft axes.
    // `applied` is recomputed from geometry and density — NOT read back from whatever the
    // assembly happened to put into the load vector — so it is an INDEPENDENT statement of
    // what should have been carried. A solver that lost a load, double-counted a mass or
    // mis-rotated gravity moves the residual off zero, and the residual ships in every
    // single reply rather than only in the test suite.
    double appliedN[3]  = { 0, 0, 0 };
    double reactionN[3] = { 0, 0, 0 };

    // Smallest linear-buckling load factor over every structure solved. <= 1 means some
    // structure is already at or past its buckling load; 0 means "not computed".
    double bucklingFactor = 0;

    // Model size, summed over the islands actually solved. Reported because it is what the
    // cost scales with — member count is a poor proxy once shells are involved, and the
    // buckling eigensolver switches from a dense to a sparse path at a DOF threshold, so a
    // performance table without this column shows an unexplained jump.
    int nodes = 0;
};

// Solve ONE island — one connected structure — and append its results to `out`.
//
// Returns false only on a FATAL error (an unknown token, an invalid model): those mean
// the request itself cannot be answered. A MECHANISM is not fatal and not global; it is
// a property of the one structure that is a mechanism, recorded on `out` and left behind
// while the rest of the world is still solved.
bool solveIsland(const std::map<BlockPos, InBlock>& grid,
                 const std::vector<RunSeg>&                segs,
                 const std::vector<QuadSeg>&               quads,
                 const std::vector<std::array<double, 6>>& pointLoads,
                 const std::vector<BlockPos>&              loadAt,
                 bool wantBuckling,
                 int& nextMember, int& nextShell,
                 SolveOut& out) {
    frame::FrameModel m;

    // Materials and sections are referenced by index, so build the tables first
    // and remember where each id landed.
    std::map<std::string, int> matIdx, secIdx;
    auto materialFor = [&](const std::string& id) -> bool {
        if (matIdx.count(id)) return true;
        auto it = materialCatalogue().find(id);
        if (it == materialCatalogue().end()) return false;
        const MatSpec& ms = it->second;
        frame::Material fm(ms.E, ms.E / (2.0 * (1.0 + ms.nu)), ms.rho);
        fm.nu  = ms.nu;
        fm.cap = frame::Capacity::make(ms.rcomp, ms.rtens, ms.rshear);
        matIdx[id] = static_cast<int>(m.materials.size());
        m.materials.push_back(fm);
        return true;
    };

    for (const auto& seg : segs) {
        if (!materialFor(seg.mat)) {
            out.error = "unknown material: " + seg.mat;
            return false;
        }
        if (!secIdx.count(seg.section)) {
            auto it = sectionCatalogue().find(seg.section);
            if (it == sectionCatalogue().end()) {
                out.error = "unknown section: " + seg.section;
                return false;
            }
            secIdx[seg.section] = static_cast<int>(m.sections.size());
            m.sections.push_back(it->second);
        }
    }
    for (const auto& q : quads) {
        if (!materialFor(q.mat)) {
            out.error = "unknown material: " + q.mat;
            return false;
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

    // ---- shell facets -------------------------------------------------------
    // Corner order must be counter-clockwise about the facet's +normal, and it is
    // CHECKED rather than reasoned about. The Minecraft-to-FrameCore map (x,y,z) ->
    // (x,z,y) swaps two axes, so it is a REFLECTION: an ordering that is counter-
    // clockwise in Minecraft comes out clockwise in the engine. Deriving the order by
    // hand would be one sign slip away from inside-out facets that still solve, so the
    // orientation is measured against the intended normal and flipped if it disagrees.
    std::vector<SolveOut::ShellOut> so;
    for (const auto& q : quads) {
        int c[4];
        for (int k = 0; k < 4; ++k) c[k] = nodeFor(q.c[k]);
        if (c[0] == c[1] || c[0] == c[2] || c[0] == c[3] ||
            c[1] == c[2] || c[1] == c[3] || c[2] == c[3]) continue;

        frame::Vec3 want{ 0, 0, 0 };
        (q.normalAxis == 0 ? want.x : q.normalAxis == 1 ? want.z : want.y) = 1.0;

        auto at = [&](int id) { return m.nodes[static_cast<size_t>(m.nodeIndex(id))].pos; };
        const frame::Vec3 d = frame::cross(at(c[2]) - at(c[0]), at(c[3]) - at(c[1]));
        if (frame::dot(d, want) < 0) std::swap(c[1], c[3]);

        frame::ShellQuad sq(nextShell, c[0], c[1], c[2], c[3],
                            matIdx[q.mat], plateCatalogue().at(q.plate));
        m.shells.push_back(sq);

        SolveOut::ShellOut o;
        o.id    = nextShell;
        o.mat   = q.mat;
        o.plate = q.plate;
        o.t     = plateCatalogue().at(q.plate);
        for (int k = 0; k < 4; ++k) {
            o.world[static_cast<size_t>(k)] = fcToMc(at(c[k]));
            // Report the BLOCK behind each corner in the same order the corners ended up
            // in, so a client can key a block to its facet without redoing the flip.
            for (const BlockPos& bp : q.c) {
                if (nodeId.at(bp) == c[k]) { o.blocks[static_cast<size_t>(k)] = bp; break; }
            }
        }
        so.push_back(std::move(o));
        ++nextShell;
    }

    if (m.members.empty() && m.shells.empty()) return true;

    // Self-weight is always on; FrameCore's helper does the kg/m^3 -> tonne/mm^3
    // bridge, rotates gravity into each member's local axes, and lumps each facet's
    // body load to its four corners.
    frame::addSelfWeight(m);

    // FAIL-CLOSED. A load whose block is not an analysis node used to be skipped with a
    // `continue`, and the reply still said ok — so a load dropped in the middle of a long
    // run silently vanished and the structure came back SAFER than it is. Silently
    // reporting safe is the worst failure this program can have (issue #14).
    //
    // The right long-term answer is to split the member at the load point, or to carry it
    // as a member load. Until one of those exists, the request is refused.
    // A load whose block belongs to a DIFFERENT island simply is not this island's load;
    // the global check in runSolve has already refused any load that belongs to no island
    // at all, so skipping here cannot lose one.
    for (size_t k = 0; k < pointLoads.size() && k < loadAt.size(); ++k) {
        auto it = nodeId.find(loadAt[k]);
        if (it == nodeId.end()) continue;
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
        return false;
    }

    // assembleAndFactor + solveLoad, rather than solve(), so the SAME factorisation can be
    // reused for the buckling eigensolve. frame::solve() is literally those two calls, so
    // the displacement results are unchanged.
    // QM6 incompatible membrane modes, on.
    //
    // A shear wall is the case D-005 chose MITC4 for in the first place: it carries lateral
    // load as in-plane shear and an overturning couple, which a grillage cannot represent
    // at all because it restrains the in-plane DOFs at every node. Having made that choice,
    // it is worth using the element that does the job well.
    //
    // The plain bilinear membrane is poor in bending — a four-node quad in pure in-plane
    // bending has no way to curve — and it showed: a wall two elements wide reported its
    // overturning fibre force 12.3% low, and four elements wide 3.4% low, LOW being the
    // unsafe direction again. The two bubble modes fix exactly that, and they are
    // statically condensed so they add no global DOF.
    //
    // Measured against cantilever bending of the wall's own cross-section:
    //     width x height   plain      QM6
    //       2 x 20        12.3%      0.0%
    //       4 x 12         3.4%      0.0%
    //       4 x 20         3.4%      0.0%
    //
    // FrameCore keeps it opt-in to preserve a bit-identical OpenSees ShellMITC4 PLATE gate;
    // it touches the membrane block only, and the plate-bending convergence table in
    // evidence/VERIFICATION.md is unchanged to every digit with it on.
    frame::SolveOptions sopts;
    sopts.useIncompatibleMembrane = true;
    // Shell geometric stiffness, on. Without it the buckling analysis is blind to plates:
    // a thin wall in compression reports no stability risk at all, which is the same
    // unsafe-direction gap the column check exists to close, one element type over.
    // Measured against a cantilever plate strip, P_cr = pi^2 D w / (2h)^2 — D and not EI,
    // because a plate resists anticlastic curvature and the difference is a factor
    // 1/(1-nu^2). Two elements across the width lands 3% low and eight under 1%, and the
    // 1/h^2 law holds to 0.25% at fixed width (verify.py C14).
    sopts.shellGeometricStiffness = true;
    frame::PreparedSystem prepared = frame::assembleAndFactor(m, sopts);
    frame::SolveResult r = frame::solveLoad(prepared, m);
    ++out.islands;
    out.nodes += static_cast<int>(m.nodes.size());

    // Independent statement of the total applied force, in FrameCore axes. Shell body
    // load arrives as nodal loads, member self weight as element UDLs, so the members'
    // share is recomputed here from rho, A and L rather than read out of the model.
    if (!r.singular) {
        double appFc[3] = { 0, 0, 0 };
        for (const frame::NodalLoad& nl : m.nodalLoads) {
            appFc[0] += nl.comp[frame::Ux];
            appFc[1] += nl.comp[frame::Uy];
            appFc[2] += nl.comp[frame::Uz];
        }
        for (const frame::Member& mem : m.members) {
            const int ia = m.nodeIndex(mem.i), ib = m.nodeIndex(mem.j);
            if (ia < 0 || ib < 0) continue;
            const frame::Vec3 d = m.nodes[static_cast<size_t>(ib)].pos - m.nodes[static_cast<size_t>(ia)].pos;
            const double L   = frame::norm(d);
            const double rho = m.materials[static_cast<size_t>(mem.matIdx)].rho;
            const double A   = m.sections[static_cast<size_t>(mem.secIdx)].A;
            appFc[2] -= rho * A * L * 9810.0 * 1e-12;      // gravity is -Z in FrameCore
        }
        double rxFc[3] = { 0, 0, 0 };
        for (size_t nI = 0; nI < m.nodes.size(); ++nI) {
            for (int d = 0; d < 3; ++d) rxFc[d] += r.reaction(static_cast<int>(nI), d);
        }
        // FrameCore (X, Y, Z) -> Minecraft (x, z, y), the same map used for every vector.
        const McVec app = fcToMc(frame::Vec3{ appFc[0], appFc[1], appFc[2] });
        const McVec rxn = fcToMc(frame::Vec3{ rxFc[0], rxFc[1], rxFc[2] });
        out.appliedN[0] += app.x;  out.appliedN[1] += app.y;  out.appliedN[2] += app.z;
        out.reactionN[0] += rxn.x; out.reactionN[1] += rxn.y; out.reactionN[2] += rxn.z;
    }

    // A singular system means "THIS structure is a mechanism". Its own forces are
    // meaningless, so it reports nothing beyond the diagnostic — and, crucially, it takes
    // nothing else down with it. One unsupported shed used to make every building in the
    // world report nothing at all.
    if (r.singular) {
        out.singular = true;
        ++out.singularIslands;
        if (out.diagnostic.empty()) out.diagnostic = r.diagnostic;
        return true;
    }

    // ---- member stress field: every number comes from the engine -------------
    //
    // This block used to rebuild the moment diagram, the fibre stresses, the shear
    // peak, the neutral axis and the moment extremum BY HAND, because the engine's
    // own field carried two measured defects: StressKernel paired cy with Iz (half
    // the extreme-fibre stress on a 200x400), and computeStressField's UDL terms
    // had inverted signs (a free tip reconstructed 2wL and wL^2 instead of zero).
    //
    // Both are now FIXED IN THE ENGINE, behind the engine's own gates — FrameCore
    // F72 (non-square fiber closed form), F73 (UDL closed form on all three local
    // axes), F74 (analytic extremum station), F75 (neutral axis) — carried as
    // sidecar/patches/ until upstream merges them. So this process computes
    // NOTHING here: it maps the engine's stations onto the wire. What remains on
    // this side, all declared at the top of this file, is the axis map fcToMc, the
    // block-to-mm scale, and the single compression-to-tension sign flip.
    //
    // Station layout: kUniformStations uniform samples PLUS the analytic interior
    // extremum of each bending diagram (F74). Screening only uniform stations
    // reports a propped cantilever's span peak (at x = 5L/8) low; screening only
    // the two ends reports a simply supported beam under its own weight as
    // UNSTRESSED — the silently-safe answer, the worst kind (issue #14).
    //
    // D/C at each station goes through ElasticAllowable::checkSection on the
    // station's own section forces — the same engine screen, fed the same numbers
    // the overlay draws. One recovery, one set of numbers: the picture and the
    // decision cannot disagree.
    const int kUniformStations = 11;
    frame::ElasticAllowable strength;
    const frame::StressField field = frame::computeStressField(m, r, kUniformStations,
                                                               /*includeMomentExtrema=*/true);

    for (const frame::MemberStressTrace& tr : field.members) {
        const size_t k = static_cast<size_t>(tr.memberIdx);
        if (k >= mo.size() || k >= m.members.size()) continue;
        const frame::Member&  mem = m.members[k];
        const frame::Section& sec = m.sections[mem.secIdx];
        const frame::Capacity& cap = m.materials[mem.matIdx].cap;

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

        // The member's UDL, read back for the wire's field spec so the client
        // evaluates the same distributed load the solver carried.
        double wy = 0, wz = 0;
        for (const frame::MemberUDL& u : m.memberUDLs)
            if (u.member == mem.id) { wy += u.w_local.y; wz += u.w_local.z; }

        auto& dst = mo[k];
        dst.originMm = fcToMc(pi);
        dst.axisX = fcToMc(ax);
        dst.axisY = dTopY;
        dst.axisZ = dPlusZ;
        dst.A  = sec.A;
        dst.Iy = sec.Iy;
        dst.Iz = sec.Iz;
        dst.cy = sec.cy;
        dst.cz = sec.cz;
        dst.wy = wy;
        dst.wz = wz;

        // Both wire ends are SECTION forces, taken from the engine's reconstruction
        // at x = 0 and x = L. At x = 0 that IS endI verbatim; at x = L it equals the
        // engine's end action with the section-convention sign, but the number now
        // comes out of the gated reconstruction (F73) instead of a hand-flip here.
        auto endForcesAt = [](const frame::MemberStressSample& s) {
            frame::MemberEndForces f;
            f.N = s.N; f.Vy = s.Vy; f.Vz = s.Vz; f.T = s.T; f.My = s.My; f.Mz = s.Mz;
            return f;
        };
        if (!tr.samples.empty()) {
            dst.fi = endForcesAt(tr.samples.front());
            dst.fj = endForcesAt(tr.samples.back());
        }

        for (size_t q = 0; q < tr.samples.size(); ++q) {
            const frame::MemberStressSample& s = tr.samples[q];
            const double t = (dst.lengthMm > 0) ? s.x / dst.lengthMm : 0.0;

            SolveOut::Station st;
            st.xMm = s.x;
            const frame::Vec3 wp{ pi.x + (pj.x - pi.x) * t,
                                  pi.y + (pj.y - pi.y) * t,
                                  pi.z + (pj.z - pi.z) * t };
            st.worldMm = fcToMc(wp);

            // Engine fibre sigmas are COMPRESSION-POSITIVE; the wire is
            // TENSION-POSITIVE. One flip, here, per the convention at the top.
            st.fibres.push_back({ "TOP_Y",   dTopY,  sec.cz, toTensionPositive(s.sigmaFiberTopY) });
            st.fibres.push_back({ "BOT_Y",   dBotY,  sec.cz, toTensionPositive(s.sigmaFiberBotY) });
            st.fibres.push_back({ "PLUS_Z",  dPlusZ, sec.cy, toTensionPositive(s.sigmaFiberPlusZ) });
            st.fibres.push_back({ "MINUS_Z", dMinZ,  sec.cy, toTensionPositive(s.sigmaFiberMinusZ) });

            // Worst-corner demand magnitudes, same numbers ElasticAllowable screens
            // (a corner governs under biaxial bending, where a face midpoint is low).
            st.sigmaTens = s.sigmaTensMax;
            st.sigmaComp = s.sigmaCompMax;
            st.tauShear  = s.tauShear;   // k*V/A with the section's own k: 1.5 rect, 4/3 circle

            // Neutral axis, where the engine says the linear profile crosses zero
            // (F75). Absent means the section is fully tensile or fully compressive.
            st.hasNaY    = s.hasNaY;
            st.naOffsetY = s.naY;
            st.hasNaZ    = s.hasNaZ;
            st.naOffsetZ = s.naZ;

            // Capacity screen AT THIS STATION, on the engine's own station forces.
            frame::MemberEndForces fx;
            fx.N  = s.N;
            fx.Vy = s.Vy;
            fx.Vz = s.Vz;
            fx.T  = s.T;
            fx.My = s.My;
            fx.Mz = s.Mz;
            const frame::DemandResult d = strength.checkSection(fx, sec, cap);
            if (d.risk > dst.dc) {
                dst.dc               = d.risk;
                dst.governingFibre   = failModeName(d.mode);
                dst.governingStation = static_cast<int>(q);
            }

            dst.stations.push_back(std::move(st));
        }

        if (dst.dc > out.maxDC) {
            out.maxDC = dst.dc;
            out.governing = dst.id;
            out.governingKind = "member";
        }
    }

    // ---- shell facets: surface stress and the von Mises screen ---------------
    //
    // A beam's D/C is the argmax of five one-dimensional ratios. A plate's is not: the
    // state at a point on its surface is a 2-D stress TENSOR, so the screen goes through
    // the principal stresses and von Mises, on BOTH faces, at the centre and at all four
    // corners. Reporting only the centre would flatten the peaks — for a clamped slab the
    // support moment is more than twice the span moment.
    //
    // Honest boundary, carried into the docs: this is an ELASTIC SURFACE SCREEN.
    // Transverse shear Qx/Qy is recovered and reported but NOT screened, there is no
    // plate buckling check, and there is no plate ultimate strength.
    std::vector<double> cenX(so.size()), cenY(so.size()), cenZ(so.size());
    std::vector<char>   frameOk(so.size(), 0);
    std::vector<std::array<frame::Vec3, 2>> facetAxes(so.size());

    for (size_t k = 0; k < r.shellForces.size() && k < so.size(); ++k) {
        const frame::ShellQuad&          sh = m.shells[k];
        const frame::ShellElementForces& f  = r.shellForces[k];
        auto& dst = so[k];

        dst.Nxx = f.Nxx; dst.Nyy = f.Nyy; dst.Nxy = f.Nxy;
        dst.Mxx = f.Mxx; dst.Myy = f.Myy; dst.Mxy = f.Mxy;
        dst.Qx  = f.Qx;  dst.Qy  = f.Qy;
        for (int c = 0; c < 4; ++c) {
            dst.Mc[static_cast<size_t>(c)]    = { f.MxxC[c], f.MyyC[c], f.MxyC[c] };
            dst.McRaw[static_cast<size_t>(c)] = { f.MxxC[c], f.MyyC[c], f.MxyC[c] };
        }

        // The facet frame, rebuilt exactly as MITC4ShellElement::prepare builds it, so the
        // axes on the wire are the axes the resultants are actually expressed in.
        //
        // NOTE for any reader of the wire: ex, ey, n form a right-handed triad in the
        // ENGINE. The Minecraft axis map (x,y,z) -> (x,z,y) is a reflection, so the same
        // three vectors read in Minecraft space are LEFT-handed: ex x ey = -n. Use them to
        // project a point onto the facet, never to rebuild the normal by a cross product.
        const int i0 = m.nodeIndex(sh.n[0]), i1 = m.nodeIndex(sh.n[1]);
        const int i2 = m.nodeIndex(sh.n[2]), i3 = m.nodeIndex(sh.n[3]);
        if (i0 < 0 || i1 < 0 || i2 < 0 || i3 < 0) continue;
        const frame::Vec3 P0 = m.nodes[i0].pos, P1 = m.nodes[i1].pos;
        const frame::Vec3 P2 = m.nodes[i2].pos, P3 = m.nodes[i3].pos;
        frame::Vec3 n = frame::cross(P2 - P0, P3 - P1);
        const double nl = frame::norm(n);
        if (nl <= 0) continue;
        n = n * (1.0 / nl);
        frame::Vec3 e1 = P1 - P0;
        e1 = e1 - n * frame::dot(e1, n);
        const double e1l = frame::norm(e1);
        if (e1l <= 0) continue;
        e1 = e1 * (1.0 / e1l);
        const frame::Vec3 e2 = frame::cross(n, e1);
        dst.ex     = fcToMc(e1);
        dst.ey     = fcToMc(e2);
        dst.normal = fcToMc(n);
        facetAxes[k] = { e1, e2 };
        frameOk[k]   = 1;
        cenX[k] = 0.25 * (P0.x + P1.x + P2.x + P3.x);
        cenY[k] = 0.25 * (P0.y + P1.y + P2.y + P3.y);
        cenZ[k] = 0.25 * (P0.z + P1.z + P2.z + P3.z);

        double sx = 0, sy = 0, txy = 0;
        frame::shellLayerSigma(f.Nxx, f.Nyy, f.Nxy, f.Mxx, f.Myy, f.Mxy, sh.t,
                               frame::ShellLayer::Top, sx, sy, txy);
        dst.vmTop = frame::principalStress(sx, sy, txy).vonMises;
        frame::shellLayerSigma(f.Nxx, f.Nyy, f.Nxy, f.Mxx, f.Myy, f.Mxy, sh.t,
                               frame::ShellLayer::Bot, sx, sy, txy);
        dst.vmBot = frame::principalStress(sx, sy, txy).vonMises;
    }

    // ---- shell facets: resultants, surface stress, and the engine's screen ---
    //
    // A beam's D/C is the argmax of five one-dimensional ratios. A plate's is not: the
    // state at a point on its surface is a 2-D stress TENSOR, so the screen goes through
    // the principal stresses and von Mises, on BOTH faces, at the centre and at all four
    // corners — frame::checkShellSurface, inside frame::recoverShellEdgeMoments below.
    //
    // Honest boundary, carried into the docs: this is an ELASTIC SURFACE SCREEN.
    // Transverse shear Qx/Qy is recovered and reported but NOT screened, there is no
    // plate buckling check, and there is no plate ultimate strength.
    for (size_t k = 0; k < r.shellForces.size() && k < so.size(); ++k) {
        const frame::ShellQuad&          sh = m.shells[k];
        const frame::ShellElementForces& f  = r.shellForces[k];
        auto& dst = so[k];

        dst.Nxx = f.Nxx; dst.Nyy = f.Nyy; dst.Nxy = f.Nxy;
        dst.Mxx = f.Mxx; dst.Myy = f.Myy; dst.Mxy = f.Mxy;
        dst.Qx  = f.Qx;  dst.Qy  = f.Qy;

        // The facet frame, rebuilt exactly as MITC4ShellElement::prepare builds it, so the
        // axes on the wire are the axes the resultants are actually expressed in.
        //
        // NOTE for any reader of the wire: ex, ey, n form a right-handed triad in the
        // ENGINE. The Minecraft axis map (x,y,z) -> (x,z,y) is a reflection, so the same
        // three vectors read in Minecraft space are LEFT-handed: ex x ey = -n. Use them to
        // project a point onto the facet, never to rebuild the normal by a cross product.
        const int i0 = m.nodeIndex(sh.n[0]), i1 = m.nodeIndex(sh.n[1]);
        const int i2 = m.nodeIndex(sh.n[2]), i3 = m.nodeIndex(sh.n[3]);
        if (i0 < 0 || i1 < 0 || i2 < 0 || i3 < 0) continue;
        const frame::Vec3 P0 = m.nodes[i0].pos, P1 = m.nodes[i1].pos;
        const frame::Vec3 P2 = m.nodes[i2].pos, P3 = m.nodes[i3].pos;
        frame::Vec3 n = frame::cross(P2 - P0, P3 - P1);
        const double nl = frame::norm(n);
        if (nl <= 0) continue;
        n = n * (1.0 / nl);
        frame::Vec3 e1 = P1 - P0;
        e1 = e1 - n * frame::dot(e1, n);
        const double e1l = frame::norm(e1);
        if (e1l <= 0) continue;
        e1 = e1 * (1.0 / e1l);
        const frame::Vec3 e2 = frame::cross(n, e1);
        dst.ex     = fcToMc(e1);
        dst.ey     = fcToMc(e2);
        dst.normal = fcToMc(n);

        // Centre-face von Mises on both faces, through the engine's own layer kernel.
        double sx = 0, sy = 0, txy = 0;
        frame::shellLayerSigma(f.Nxx, f.Nyy, f.Nxy, f.Mxx, f.Myy, f.Mxy, sh.t,
                               frame::ShellLayer::Top, sx, sy, txy);
        dst.vmTop = frame::principalStress(sx, sy, txy).vonMises;
        frame::shellLayerSigma(f.Nxx, f.Nyy, f.Nxy, f.Mxx, f.Myy, f.Mxy, sh.t,
                               frame::ShellLayer::Bot, sx, sy, txy);
        dst.vmBot = frame::principalStress(sx, sy, txy).vonMises;
    }

    // ---- support-moment recovery: owned by the engine ------------------------
    //
    // MITC4's corner moments at a clamped edge are badly low — measured ~40% under
    // Timoshenko's clamped-plate support moment at 8 elements across — and under-
    // reporting the one moment that governs a slab's supports is a silently-safe
    // answer, the worst kind. The interior-to-boundary extrapolation that fixes it
    // (M_edge ~= 1.5*M1 - 0.5*M2, adopted only where it RAISES the screened demand)
    // used to be implemented here, in the adapter; it now lives in the engine as
    // frame::recoverShellEdgeMoments, behind FrameCore's own gate (F76: raw 39.7%
    // low -> recovered 14.0% low at 8 elements). This side copies the verdicts —
    // demand, governing face and corner, per-corner recovered and raw moments —
    // onto the wire, and computes nothing.
    const std::vector<frame::ShellEdgeRecoveryResult> rec = frame::recoverShellEdgeMoments(m, r);
    for (const frame::ShellEdgeRecoveryResult& e : rec) {
        const size_t k = static_cast<size_t>(e.shellIdx);
        if (k >= so.size()) continue;
        auto& dst = so[k];
        dst.dcRaw           = e.riskRaw;
        dst.dc              = e.risk;
        dst.governingTop    = e.top;
        dst.governingCorner = e.corner;
        dst.edgeRecovered   = e.recovered;
        for (size_t c = 0; c < 4; ++c) {
            dst.Mc[c]    = { e.Mc[c][0],    e.Mc[c][1],    e.Mc[c][2] };
            dst.McRaw[c] = { e.McRaw[c][0], e.McRaw[c][1], e.McRaw[c][2] };
        }

        if (dst.dc > out.maxDC) {
            out.maxDC = dst.dc;
            out.governing = dst.id;
            out.governingKind = "shell";
        }
    }

    // ---- linear buckling -----------------------------------------------------
    //
    // A stress check alone is not a stability check, and the gap is not small. A slender
    // steel column carrying a load far below its crushing stress reports a comfortable
    // D/C and would nonetheless fold: Euler's critical load falls with the SQUARE of the
    // length while the stress check does not know the length exists at all. Reporting only
    // the stress ratio is therefore unsafe in exactly the case a player is most likely to
    // build — a tall thin column.
    //
    // lambda_cr is the factor on the CURRENT load at which this structure becomes
    // unstable, so lambda_cr <= 1 means it is already past its buckling load. It is a
    // property of the whole structure, not of one member: a frame buckles as a frame.
    //
    // Honest boundary: this is the LINEAR (eigenvalue) onset. It is an upper bound —
    // imperfections and post-buckling softening pull the real capacity below it — and it
    // says nothing about what happens after. No knockdown factor is applied here; the
    // number reported is the eigenvalue, named as such.
    if (wantBuckling && (!m.members.empty() || !m.shells.empty())) {
        // Force the SPARSE eigensolver at every size. FrameCore's default switches to it
        // only above 500 free DOF, which put the worst relative cost squarely on ordinary
        // buildings: a 59-member structure paid 3.7x for buckling while a 199-member one
        // paid nothing, because the big one was already on the sparse path. The sparse
        // iteration reuses the factorisation the linear solve just computed instead of
        // building and decomposing a dense matrix.
        //
        // Its own contract makes this safe rather than a gamble: it is tolerance-level
        // rather than bit-identical, and it falls back to dense on any non-convergence, so
        // the worst case is the cost we already had. The measured agreement is pinned by a
        // gate (verify.py C12), not assumed.
        frame::BucklingOptions bopts;
        bopts.denseThreshold = 0;
        const frame::BucklingResult bk = frame::solveBuckling(prepared, m, bopts);
        if (!bk.singular && std::isfinite(bk.criticalFactor) && bk.criticalFactor > 0) {
            if (out.bucklingFactor <= 0 || bk.criticalFactor < out.bucklingFactor) {
                out.bucklingFactor = bk.criticalFactor;
            }
        }
    }

    for (auto& x : mo) out.members.push_back(std::move(x));
    for (auto& x : so) out.shells.push_back(std::move(x));
    return true;
}

// ---------------------------------------------------------------- islands
// A world is not one structure. Two buildings that share no node share no equilibrium
// either, and assembling them into a single stiffness matrix only couples their FATES:
// one unsupported shed anywhere in the dimension makes the global matrix rank-deficient,
// the factorisation fails, and every building in the world reports nothing. Measured, not
// theorised — a supported cantilever solved at D/C 0.026 alone, and reported `singular`
// with no results at all once an unanchored beam was placed a hundred blocks away.
//
// So the elements are partitioned into connected components first and each is solved on
// its own. A mechanism is then a property of the structure that is one. It also makes the
// solve cheaper: direct factorisation is superlinear in the model, so N small models cost
// less than one N-times-larger model — the opposite of what batching usually buys.
struct DisjointSet {
    std::map<BlockPos, BlockPos> parent;
    BlockPos find(const BlockPos& a) {
        auto it = parent.find(a);
        if (it == parent.end()) { parent[a] = a; return a; }
        if (it->second == a) return a;
        const BlockPos root = find(it->second);
        parent[a] = root;
        return root;
    }
    void unite(const BlockPos& a, const BlockPos& b) {
        const BlockPos ra = find(a), rb = find(b);
        if (!(ra == rb)) parent[ra] = rb;
    }
};

SolveOut runSolve(const std::vector<InBlock>& blocks,
                  const std::vector<std::array<double, 6>>& pointLoads,
                  const std::vector<BlockPos>&              loadAt,
                  bool wantBuckling) {
    SolveOut out;

    std::map<BlockPos, InBlock> grid;
    for (const auto& b : blocks) grid[b.pos] = b;

    // Sheets first: the shell nodes they claim are what a run is allowed to bear on.
    std::set<BlockPos>   shellNodes;
    std::vector<QuadSeg> quads = extractSheets(grid, shellNodes, out.unassigned);
    std::set<BlockPos> loadBlocks(loadAt.begin(), loadAt.end());
    std::vector<RunSeg>  segs  = extractRuns(grid, shellNodes, loadBlocks, out.unassigned);

    // Connectivity is through SHARED NODES, which is exactly what the extraction already
    // guarantees: runs are split at junctions so two members that meet share a block, and
    // a run bearing on a plate ends on one of that plate's corner blocks.
    DisjointSet ds;
    std::set<BlockPos> nodeBlocks;
    for (const auto& s : segs) {
        ds.unite(s.blocks.front(), s.blocks.back());
        nodeBlocks.insert(s.blocks.front());
        nodeBlocks.insert(s.blocks.back());
    }
    for (const auto& q : quads) {
        for (int k = 1; k < 4; ++k) ds.unite(q.c[0], q.c[k]);
        for (const BlockPos& c : q.c) nodeBlocks.insert(c);
    }

    // FAIL-CLOSED, and checked GLOBALLY before any island is solved. Extraction has already
    // made every loaded block that belongs to a member into a node, so what remains here is
    // a load on a block that belongs to no element at all — a lone block, or a plate block
    // that closed no facet. That one still refuses the whole request.
    //
    // The check must be global. Letting each island decide "not mine" and skip would make a
    // load nobody claims vanish with ok:true, and reporting a structure safer than it is is
    // the worst answer this program can give (issue #14).
    for (size_t k = 0; k < pointLoads.size() && k < loadAt.size(); ++k) {
        if (nodeBlocks.count(loadAt[k])) continue;
        const BlockPos& p = loadAt[k];
        out.ok    = false;
        out.error = "load at (" + std::to_string(p.x) + "," + std::to_string(p.y) + ","
                  + std::to_string(p.z) + ") is on no structural element; "
                    "it belongs to no member and closes no plate facet";
        return out;
    }

    // Only now: nothing structural was placed. This is deliberately AFTER the load check
    // and not before it. Returning early here used to swallow the load entirely — a
    // request carrying a load on a block that formed no element came back ok:true with no
    // mention of it, which is the silently-safe answer wearing the "nothing to do" costume.
    if (segs.empty() && quads.empty()) {
        out.ok    = true;
        out.error = "no members or plates extracted";
        return out;
    }

    std::map<BlockPos, size_t>        islandOf;
    std::vector<std::vector<RunSeg>>  islandSegs;
    std::vector<std::vector<QuadSeg>> islandQuads;
    auto slotFor = [&](const BlockPos& any) -> size_t {
        const BlockPos root = ds.find(any);
        auto it = islandOf.find(root);
        if (it != islandOf.end()) return it->second;
        const size_t slot = islandSegs.size();
        islandOf[root] = slot;
        islandSegs.emplace_back();
        islandQuads.emplace_back();
        return slot;
    };
    for (const auto& s : segs)  islandSegs[slotFor(s.blocks.front())].push_back(s);
    for (const auto& q : quads) islandQuads[slotFor(q.c[0])].push_back(q);

    int nextMember = 1, nextShell = 1;
    out.ok = true;
    for (size_t k = 0; k < islandSegs.size(); ++k) {
        if (!solveIsland(grid, islandSegs[k], islandQuads[k], pointLoads, loadAt, wantBuckling,
                         nextMember, nextShell, out)) {
            out.ok = false;
            return out;
        }
    }
    if (out.members.empty() && out.shells.empty() && out.error.empty()) {
        out.error = "no members or plates extracted";
    }
    return out;
}

// ------------------------------------------------------------------ protocol
void writeForces(bjson::Writer& w, const char* key, const frame::MemberEndForces& f) {
    w.key(key).beginObj();
    w.kv("N", f.N).kv("Vy", f.Vy).kv("Vz", f.Vz).kv("T", f.T).kv("My", f.My).kv("Mz", f.Mz);
    w.endObj();
}

void writeVec(bjson::Writer& w, const char* key, const McVec& v) {
    w.key(key).beginArr().val(v.x).val(v.y).val(v.z).endArr();
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

        if (!matCat.count(b.mat)) return errorLine("unknown material '" + b.mat + "'", revision);
        // The token names either a beam section or a plate. Anything else is refused
        // rather than defaulted: a block whose token the engine does not recognise would
        // otherwise become some other, perfectly solvable structure (issue #18).
        if (!secCat.count(b.section) && !isPlate(b.section)) {
            return errorLine("unknown section or plate '" + b.section + "'", revision);
        }

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

    // Buckling is an extra eigensolve on top of the linear solve and it is not free — see
    // the measured cost in evidence/VERIFICATION.md. It is ON by default because a stress
    // check alone is unsafe for slender members, and a default that is cheap and wrong is
    // not a good trade. The switch exists so the cost can be MEASURED (the performance
    // sweep runs both ways) and so a server that does not want it can say so explicitly
    // rather than discovering it as unexplained latency.
    //
    // Deliberately NOT screened per member. A frame can sway-buckle at a load far below any
    // individual member's Euler load, so "no member is near its own critical load" is not a
    // safe reason to skip the global eigensolve — it is the silently-safe trap again.
    if (req.has("buckling") && !req.isBool("buckling")) {
        return errorLine("'buckling' must be a boolean", revision);
    }
    const bool wantBuckling = req.boolean("buckling", true);

    SolveOut s = runSolve(blocks, loads, loadAt, wantBuckling);

    bjson::Writer w;
    w.beginObj();
    w.kv("ok", s.ok).kv("op", "solve").kv("revision", revision);
    if (!s.ok) {
        w.kv("error", s.error);
        w.endObj();
        return w.done();
    }
    // `singular` now means "at least one structure in this world is a mechanism", and the
    // counts say how many — the results of the sound ones are in this same reply. A client
    // that blanked its whole overlay on `singular` would be throwing away good answers.
    w.kv("singular", s.singular);
    w.kv("islands", s.islands).kv("singularIslands", s.singularIslands);
    if (!s.diagnostic.empty()) w.kv("diagnostic", s.diagnostic);
    // Named `bucklingFactor` and not `safetyFactor`: it is the eigenvalue of the linear
    // onset problem, an upper bound on the real critical load, and calling it a safety
    // factor would invite a reader to treat an upper bound as a margin.
    if (s.bucklingFactor > 0) w.kv("bucklingFactor", s.bucklingFactor);
    w.kv("nodes", s.nodes).kv("dof", s.nodes * 6);
    w.key("equilibrium").beginObj();
    w.key("applied").beginArr().val(s.appliedN[0]).val(s.appliedN[1]).val(s.appliedN[2]).endArr();
    w.key("reaction").beginArr().val(s.reactionN[0]).val(s.reactionN[1]).val(s.reactionN[2]).endArr();
    {
        double scale = 0, resid = 0;
        for (int d = 0; d < 3; ++d) {
            scale = std::max(scale, std::fabs(s.appliedN[d]));
            resid = std::max(resid, std::fabs(s.appliedN[d] + s.reactionN[d]));
        }
        w.kv("residual", scale > 0 ? resid / scale : resid);
    }
    w.endObj();
    if (!s.error.empty())      w.kv("note", s.error);
    w.kv("maxDC", s.maxDC).kv("governing", s.governing);
    // Members and plates number from 1 independently, so the id alone does not say what
    // it refers to. The kind travels with it rather than being inferred from a lookup
    // that would silently pick the wrong element when both exist.
    if (!s.governingKind.empty()) w.kv("governingKind", s.governingKind);

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

        // Everything a client needs to evaluate the stress field itself.
        w.key("field").beginObj();
        writeVec(w, "origin", mm.originMm);
        writeVec(w, "ax", mm.axisX);
        writeVec(w, "ay", mm.axisY);
        writeVec(w, "az", mm.axisZ);
        w.kv("A", mm.A).kv("Iy", mm.Iy).kv("Iz", mm.Iz).kv("cy", mm.cy).kv("cz", mm.cz);
        w.kv("wy", mm.wy).kv("wz", mm.wz);
        w.endObj();
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

    w.key("shells").beginArr();
    for (const auto& sh : s.shells) {
        w.beginObj();
        w.kv("id", sh.id).kv("mat", sh.mat).kv("plate", sh.plate).kv("t", sh.t);
        w.kv("dc", sh.dc).kv("face", sh.governingTop ? "TOP" : "BOT");
        w.kv("corner", sh.governingCorner);
        w.key("blocks").beginArr();
        for (const BlockPos& p : sh.blocks) w.beginArr().val(p.x).val(p.y).val(p.z).endArr();
        w.endArr();
        w.key("world").beginArr();
        for (const McVec& v : sh.world) w.beginArr().val(v.x).val(v.y).val(v.z).endArr();
        w.endArr();
        writeVec(w, "ex", sh.ex);
        writeVec(w, "ey", sh.ey);
        writeVec(w, "n",  sh.normal);
        w.key("N").beginObj().kv("xx", sh.Nxx).kv("yy", sh.Nyy).kv("xy", sh.Nxy).endObj();
        w.key("M").beginObj().kv("xx", sh.Mxx).kv("yy", sh.Myy).kv("xy", sh.Mxy).endObj();
        w.key("Q").beginObj().kv("x", sh.Qx).kv("y", sh.Qy).endObj();
        w.key("Mc").beginArr();
        for (const auto& c : sh.Mc) w.beginArr().val(c[0]).val(c[1]).val(c[2]).endArr();
        w.endArr();
        if (sh.edgeRecovered) {
            w.key("McRaw").beginArr();
            for (const auto& c : sh.McRaw) w.beginArr().val(c[0]).val(c[1]).val(c[2]).endArr();
            w.endArr();
        }
        w.kv("vmTop", sh.vmTop).kv("vmBot", sh.vmBot);
        w.kv("dcRaw", sh.dcRaw).kv("edgeRecovered", sh.edgeRecovered);
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
    w.key("plates").beginArr();
    for (const auto& [id, t] : plateCatalogue()) {
        w.beginObj().kv("id", id).kv("t", t).endObj();
    }
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
