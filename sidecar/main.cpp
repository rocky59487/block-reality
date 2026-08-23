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
#include "shm.hpp"

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
#include <limits>
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
        // Sawn-timber and masonry-pier sections for the timber/brick materials the
        // catalogue always carried but no block ever declared. Both non-square, per
        // the GATES.md fixture rule; both gated in verify.py [C15] against closed
        // forms before any block was allowed to name them.
        { "timber_rect_140x240",   frame::Section::Rectangular(140.0, 240.0) },
        { "brick_rect_230x350",    frame::Section::Rectangular(230.0, 350.0) },
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
// Each block this solve produces no element result for comes back as `unassigned`, so the
// game side can say why nothing appeared instead of leaving a floor that silently carries
// no load. Two causes share the field, deliberately (D-026): no element could be extracted,
// or the structure it belongs to is fully supported and has no internal response to report.
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

    std::vector<int> runAxis;
    for (int ax = 0; ax < 3; ++ax) {
        const BlockPos& axis = kAxes[ax];
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

            // A single block is L/h = 1 — not a valid beam element. Skipped here and
            // reported back so the game side can tell the player why nothing appeared.
            //
            // This filter now runs BEFORE the extension pass, and that ordering is the
            // fix: with it after, a lone beam block sitting on a slab was extended into
            // the slab's node and shipped as a spurious one-metre member carrying the
            // beam's full section. The bearing rule fires on all three axes, so a plate
            // merely underneath a beam grew a vertical member out of it and inflated the
            // model's self weight 2.3x (PR26_REVIEW MECH-03). Only a run that is already
            // a member may bear on anything.
            if (run.size() >= 2) {
                rawRuns.push_back(std::move(run));
                runMat.push_back(blk.mat);
                runSec.push_back(blk.section);
                runAxis.push_back(ax);
            }
        }
    }

    // Which axes' runs claim each block, from the runs AS FOUND. A run may then extend
    // into a block that already belongs to another run, and that shared block is the node
    // where the two meet.
    std::map<BlockPos, std::set<int>> claimedBy;
    for (size_t r = 0; r < rawRuns.size(); ++r)
        for (const BlockPos& p : rawRuns[r]) claimedBy[p].insert(runAxis[r]);

    // Extension pass. Two rules, both saying "end ON the shared node, not one block short":
    //
    //  1. BEARING on a plate. Without it a steel column under a concrete slab stops at its
    //     own top block, the slab hangs on nothing, and the whole model is a mechanism —
    //     while every member in it still reports a believable stress.
    //
    //  2. BUTT JOINT across a change of material (MEMBER_SEMANTICS 7.4 rule 2: two runs
    //     whose endpoint blocks are face-adjacent share a node). Runs break at a change of
    //     material because two materials are two members — but nothing then JOINED them,
    //     and nothing else could: the only other route to a shared node is a shared block,
    //     which continues() had just refused to create. A timber beam on brick piers was
    //     therefore three islands; the beam's had no support, so it left the answer
    //     entirely — no member row, no self weight in applied, and a 500 kN load a player
    //     hung on it came back ok:true with nothing moved (PR26_REVIEW A-1). That
    //     contradicts MEMBER_SEMANTICS 7.6 in as many words: joint stiffness follows from
    //     HOW it is joined, not from the material. Same-material joints have always worked
    //     exactly this way — continues() swallows the neighbouring block and useCount makes
    //     it a node — so this gives the cross-material case the same treatment rather than
    //     inventing a second mechanism for it.
    //
    // At a COLLINEAR butt joint only one of the two runs may extend, or both grow into each
    // other and the joint metre is modelled twice, in parallel, with two different sections.
    // The rule: a run extends forward into its neighbour; it extends BACKWARD only when the
    // block behind it is not claimed by a run along its own axis, because such a run is
    // already coming forward to meet it.
    for (size_t r = 0; r < rawRuns.size(); ++r) {
        std::vector<BlockPos>& run = rawRuns[r];
        const BlockPos& axis   = kAxes[runAxis[r]];
        const BlockPos  before = sub(run.front(), axis);
        const BlockPos  beyond = add(run.back(), axis);
        auto joinable = [&](const BlockPos& q, bool backward) {
            auto it = grid.find(q);
            if (it == grid.end() || isPlate(it->second.section)) return false;
            auto cb = claimedBy.find(q);
            if (cb == claimedBy.end()) return false;   // a lone block is nobody's member
            if (backward && cb->second.count(runAxis[r])) return false;   // it comes to us
            return true;
        };
        if (shellNodes.count(before))     run.insert(run.begin(), before);
        else if (joinable(before, true))  run.insert(run.begin(), before);
        if (shellNodes.count(beyond))     run.push_back(beyond);
        else if (joinable(beyond, false)) run.push_back(beyond);
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

    // A span with a support at BOTH ends and nothing between them has no interior node,
    // so the model carries no degree of freedom anywhere along it. A five-block beam
    // resting on the ground at each end came back nodes:2, dof:12, every DOF constrained;
    // FrameCore correctly reported "fully constrained (no free DOF)" and the game showed
    // that to the player as "nothing is holding this up" — the exact opposite of the
    // truth, on a shape dist/START-HERE.txt tells them to build (PR26_REVIEW A-6).
    //
    // One interior node is enough. Euler-Bernoulli elements with consistent load vectors
    // are EXACT AT THEIR NODES, so two elements reproduce a fixed-fixed beam's midspan
    // deflection and its whole moment diagram; more elements would only cost DOF.
    std::set<BlockPos> spanNodes;
    for (const auto& run : rawRuns) {
        size_t last = 0;
        for (size_t k = 1; k < run.size(); ++k) {
            if (!nodeBlocks.count(run[k]) && k + 1 != run.size()) continue;
            if (k - last >= 2 && grid.at(run[last]).support && grid.at(run[k]).support)
                spanNodes.insert(run[(last + k) / 2]);
            last = k;
        }
    }
    nodeBlocks.insert(spanNodes.begin(), spanNodes.end());

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
                //
                // It must also be a block of THIS segment's own material: the extension
                // pass can put a foreign block at either end of a run, and taking its
                // section would give a brick pier a timber section because the timber
                // beam it butts against happens to come first in the list.
                seg.section.clear();
                for (const BlockPos& p : seg.blocks) {
                    const InBlock& ib = grid.at(p);
                    if (!isPlate(ib.section) && ib.mat == seg.mat) {
                        seg.section = ib.section;
                        break;
                    }
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
    bool anyFreeNode = false;
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
        else                                         anyFreeNode = true;
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
    // EVERY node of this island is grounded. There is no system to solve — FrameCore
    // says exactly that ("fully constrained (no free DOF)") and reports it as singular,
    // which this file then counted as a mechanism and the HUD printed as "nothing is
    // holding this up", to a player who had laid a beam flat on the ground. It is the
    // opposite of the truth and it is not a mechanism (PR26_REVIEW A-6).
    //
    // Most of that case is gone before it reaches here: a span with a support at each end
    // now gets an interior node in extractRuns, so it has degrees of freedom and solves
    // normally. What is left is genuinely immobile — a run whose every block is grounded.
    // It counts as a structure, it is NOT counted as a mechanism, and its blocks are
    // reported so the game can say why no member appeared for them rather than leaving
    // the player with an empty list and no reason.
    if (!anyFreeNode) {
        ++out.islands;
        out.nodes += static_cast<int>(m.nodes.size());
        if (out.diagnostic.empty()) {
            out.diagnostic = "fully supported: every node of one structure is grounded, so "
                             "it has no degree of freedom and no internal response to solve";
        }
        std::set<BlockPos> immobile;
        for (const auto& seg : segs)
            for (const BlockPos& bp : seg.blocks) immobile.insert(bp);
        for (const auto& q : quads)
            for (const BlockPos& c : q.c) immobile.insert(c);
        for (const BlockPos& bp : immobile) out.unassigned.push_back(bp);
        return true;
    }

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
double relativeResidual(const SolveOut& s);

// Revision is the stale-result fence and is read EXACTLY (#29). The previous path
// stored it in a double, which folds every integer above 2^53 onto its neighbours —
// so two adjacent revisions could become the same value and a stale result could
// pass the one check that exists to stop it. The parser keeps plain-integer
// literals as int64; anything else — missing, fractional, exponent-form, negative,
// beyond int64 — is refused here.
bool readRevision(const bjson::Value& req, long long& out, std::string& err) {
    if (!req.isExactInt("revision")) {
        err = "'revision' missing or not a plain non-negative integer";
        return false;
    }
    const long long r = req.exactI64("revision");
    if (r < 0) {
        err = "'revision' must be a non-negative integer";
        return false;
    }
    out = r;
    return true;
}

// Unknown-key policy (#30): the wire is a fixed vocabulary, and a key the engine
// does not know is most likely a TYPO of one it does — "load" for "loads" would
// otherwise solve an unloaded model and report it safe. Refused, with the key named.
std::string unknownKeyIn(const bjson::Value& v, std::initializer_list<const char*> allowed) {
    for (const auto& [key, _] : v.o) {
        bool known = false;
        for (const char* a : allowed) {
            if (key == a) { known = true; break; }
        }
        if (!known) return key;
    }
    return "";
}

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
    long long revision = 0;
    std::string revErr;
    if (!readRevision(req, revision, revErr)) return errorLine(revErr, 0);

    // Schema is a fixed vocabulary at EVERY level (#30). The permissive accessors
    // (arr() returning empty on a wrong type, boolean() defaulting) must never be
    // the thing standing between a malformed request and a solve: "loads": {...}
    // read as zero loads, or a typo'd key silently dropped, both produce ok:true
    // for a LIGHTER model than the caller asked about — the silently-safe answer.
    {
        const std::string bad = unknownKeyIn(req, { "op", "revision", "blocks", "loads", "buckling" });
        if (!bad.empty()) return errorLine("unknown field '" + bad + "'", revision);
    }
    if (!req.isArr("blocks")) {
        return errorLine("'blocks' missing or not an array", revision);
    }
    // Policy, frozen: `loads` may be ABSENT (meaning none), but if present it must
    // be an array. Absence is an explicit vocabulary choice; a wrong type is not.
    if (req.has("loads") && !req.isArr("loads")) {
        return errorLine("'loads' is not an array", revision);
    }

    const auto& matCat = materialCatalogue();
    const auto& secCat = sectionCatalogue();

    std::vector<InBlock> blocks;
    std::set<BlockPos>   seen;
    std::string          err;

    for (const auto& bv : req.arr("blocks")) {
        if (bv.t != bjson::Value::T::Obj) return errorLine("blocks[] entry is not an object", revision);
        {
            const std::string bad = unknownKeyIn(bv, { "x", "y", "z", "mat", "section", "support" });
            if (!bad.empty()) return errorLine("block: unknown field '" + bad + "'", revision);
        }
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
        // Policy, frozen: `support` may be absent (not a support), but a present
        // field must be a real boolean — "support": "true" defaulting to false
        // turns a supported structure into a mechanism with a straight face.
        if (bv.has("support") && !bv.isBool("support")) {
            return errorLine("block: 'support' is not a boolean", revision);
        }

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
        {
            const std::string bad = unknownKeyIn(lv, { "x", "y", "z", "fx", "fy", "fz", "mx", "my", "mz" });
            if (!bad.empty()) return errorLine("load: unknown field '" + bad + "'", revision);
        }
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
    // The same function the binary encoder quotes: the two transports must not be
    // able to disagree about the residual of one solve.
    w.kv("residual", relativeResidual(s));
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

// ------------------------------------------------------- binary (shm) protocol
//
// The shared-memory transport (D-019). The stdio line becomes a DOORBELL — it
// carries the op, the revision and a byte count, never a number — and the numbers
// cross as raw little-endian IEEE-754 in a file both processes have mapped. That
// is the whole point: a double that is never textualised cannot be truncated,
// rounded or re-parsed in transit. The JSON `solve` op remains, bit-equal in
// meaning, as the fallback and the debug surface; T-gates in verify.py hold the
// two transports to identical results.
//
// Strings never cross the binary wire. Materials, sections and plates travel as
// indices into the SAME ordered lists the JSON hello announced (sections first,
// then plates, each in catalogue order), so both ends agree by construction.
// Request and reply reuse the same region front-to-back; the half-duplex doorbell
// is the mutex.

constexpr std::uint32_t kShmReqMagic  = 0x31515242;   // "BRQ1"
constexpr std::uint32_t kShmRespMagic = 0x31505242;   // "BRP1"

const std::vector<std::string>& tokenTable() {
    static const std::vector<std::string> kT = [] {
        std::vector<std::string> t;
        for (const auto& [id, _] : sectionCatalogue()) t.push_back(id);
        for (const auto& [id, _] : plateCatalogue())   t.push_back(id);
        return t;
    }();
    return kT;
}

const std::vector<std::string>& materialTable() {
    static const std::vector<std::string> kM = [] {
        std::vector<std::string> t;
        for (const auto& [id, _] : materialCatalogue()) t.push_back(id);
        return t;
    }();
    return kM;
}

int indexIn(const std::vector<std::string>& table, const std::string& s) {
    for (size_t k = 0; k < table.size(); ++k)
        if (table[k] == s) return static_cast<int>(k);
    return -1;
}

// governingFibre on the binary wire. The order is wire contract v1; verify.py's
// transport gates compare it against the JSON string field on every case.
std::uint32_t fibreEnumOf(const std::string& name) {
    static const char* kNames[] = { "NONE", "CRUSH", "TENSION", "SHEAR",
                                    "BENDING", "TORSION", "SHELL_VM" };
    for (std::uint32_t k = 0; k < 7; ++k)
        if (name == kNames[k]) return k;
    return 0;
}

// One equilibrium residual, computed once, quoted by both encoders — the JSON
// and the binary reply must not be able to disagree about the same solve.
double relativeResidual(const SolveOut& s) {
    double scale = 0, resid = 0;
    for (int d = 0; d < 3; ++d) {
        scale = std::max(scale, std::fabs(s.appliedN[d]));
        resid = std::max(resid, std::fabs(s.appliedN[d] + s.reactionN[d]));
    }
    return scale > 0 ? resid / scale : resid;
}

brshm::Mapping g_shm;

void writeVec3(brshm::Writer& w, const McVec& v) { w.f64(v.x); w.f64(v.y); w.f64(v.z); }
void writeBlock(brshm::Writer& w, const BlockPos& p) { w.i32(p.x); w.i32(p.y); w.i32(p.z); }

// Encode a SolveOut into the mapped region. Returns bytes written, or 0 if the
// region is too small — the caller reports that as an error line and the JVM
// grows the file and retries. Nothing is ever silently truncated.
size_t encodeShmReply(const SolveOut& s, long long revision) {
    brshm::Writer w{ g_shm.data(), g_shm.data() + g_shm.size() };
    w.u32(kShmRespMagic);
    w.u32(static_cast<std::uint32_t>(revision & 0xffffffffLL));
    w.u32(static_cast<std::uint32_t>((revision >> 32) & 0xffffffffLL));
    w.u32(1u);   // ok: a failed solve never reaches this encoder (doorbell errors)

    auto writeStr = [&](const std::string& t) {
        w.u32(static_cast<std::uint32_t>(t.size()));
        if (!w.need(t.size())) return;
        std::memcpy(w.p, t.data(), t.size());
        w.p += t.size();
    };

    w.u32(s.singular ? 1u : 0u);
    w.u32(static_cast<std::uint32_t>(s.islands));
    w.u32(static_cast<std::uint32_t>(s.singularIslands));
    writeStr(s.diagnostic);
    writeStr(s.error);   // the "note" field: non-fatal, e.g. "no members extracted"
    for (int d = 0; d < 3; ++d) w.f64(s.appliedN[d]);
    for (int d = 0; d < 3; ++d) w.f64(s.reactionN[d]);
    w.f64(relativeResidual(s));
    w.f64(s.maxDC);
    w.i32(s.governing);
    w.u32(s.governingKind == "member" ? 1u : s.governingKind == "shell" ? 2u : 0u);
    w.f64(s.bucklingFactor);
    w.u32(static_cast<std::uint32_t>(s.nodes));
    w.u32(static_cast<std::uint32_t>(s.nodes * 6));

    w.u32(static_cast<std::uint32_t>(s.members.size()));
    for (const auto& mm : s.members) {
        w.i32(mm.id);
        w.i32(indexIn(materialTable(), mm.mat));
        w.i32(indexIn(tokenTable(), mm.section));
        w.f64(mm.lengthMm);
        w.f64(mm.dc);
        w.u32(fibreEnumOf(mm.governingFibre));
        w.i32(mm.governingStation);
        w.f64(mm.fi.N); w.f64(mm.fi.Vy); w.f64(mm.fi.Vz);
        w.f64(mm.fi.T); w.f64(mm.fi.My); w.f64(mm.fi.Mz);
        w.f64(mm.fj.N); w.f64(mm.fj.Vy); w.f64(mm.fj.Vz);
        w.f64(mm.fj.T); w.f64(mm.fj.My); w.f64(mm.fj.Mz);
        writeVec3(w, mm.originMm);
        writeVec3(w, mm.axisX);
        writeVec3(w, mm.axisY);
        writeVec3(w, mm.axisZ);
        w.f64(mm.A); w.f64(mm.Iy); w.f64(mm.Iz); w.f64(mm.cy); w.f64(mm.cz);
        w.f64(mm.wy); w.f64(mm.wz);
        w.u32(static_cast<std::uint32_t>(mm.blocks.size()));
        for (const BlockPos& p : mm.blocks) writeBlock(w, p);
        w.u32(static_cast<std::uint32_t>(mm.stations.size()));
        for (const auto& st : mm.stations) {
            w.f64(st.xMm);
            writeVec3(w, st.worldMm);
            // Fibre order is fixed wire contract: TOP_Y, BOT_Y, PLUS_Z, MINUS_Z.
            // Directions and offsets are NOT sent — they are ±ay/±az and cz/cy from
            // the member header, which the decoder reassembles.
            double f4[4] = { 0, 0, 0, 0 };
            for (const auto& f : st.fibres) {
                if      (f.name == "TOP_Y")   f4[0] = f.sigma;
                else if (f.name == "BOT_Y")   f4[1] = f.sigma;
                else if (f.name == "PLUS_Z")  f4[2] = f.sigma;
                else if (f.name == "MINUS_Z") f4[3] = f.sigma;
            }
            for (double v : f4) w.f64(v);
            w.f64(st.sigmaTens);
            w.f64(st.sigmaComp);
            w.f64(st.tauShear);
            // NaN is the "absent" sentinel: a neutral-axis offset is always finite
            // when it exists, and JSON's missing-key semantics map onto it exactly.
            w.f64(st.hasNaY ? st.naOffsetY : std::numeric_limits<double>::quiet_NaN());
            w.f64(st.hasNaZ ? st.naOffsetZ : std::numeric_limits<double>::quiet_NaN());
        }
    }

    w.u32(static_cast<std::uint32_t>(s.shells.size()));
    for (const auto& sh : s.shells) {
        w.i32(sh.id);
        w.i32(indexIn(materialTable(), sh.mat));
        w.i32(indexIn(tokenTable(), sh.plate));
        w.f64(sh.t);
        w.f64(sh.dc);
        w.f64(sh.dcRaw);
        w.u32((sh.governingTop ? 1u : 0u) | (sh.edgeRecovered ? 2u : 0u));
        w.i32(sh.governingCorner);
        for (const BlockPos& p : sh.blocks) writeBlock(w, p);
        for (const McVec& v : sh.world) writeVec3(w, v);
        writeVec3(w, sh.ex);
        writeVec3(w, sh.ey);
        writeVec3(w, sh.normal);
        w.f64(sh.Nxx); w.f64(sh.Nyy); w.f64(sh.Nxy);
        w.f64(sh.Mxx); w.f64(sh.Myy); w.f64(sh.Mxy);
        w.f64(sh.Qx);  w.f64(sh.Qy);
        for (const auto& c : sh.Mc)    { w.f64(c[0]); w.f64(c[1]); w.f64(c[2]); }
        for (const auto& c : sh.McRaw) { w.f64(c[0]); w.f64(c[1]); w.f64(c[2]); }
        w.f64(sh.vmTop);
        w.f64(sh.vmBot);
    }

    w.u32(static_cast<std::uint32_t>(s.unassigned.size()));
    for (const BlockPos& p : s.unassigned) writeBlock(w, p);

    return w.ok ? static_cast<size_t>(w.p - g_shm.data()) : 0;
}

std::string handleShmOpen(const bjson::Value& req) {
    if (!brshm::hostIsLittleEndian()) {
        return errorLine("shm: refused on a big-endian host; use the JSON transport", 0);
    }
    if (!req.isStr("path")) return errorLine("shm.open: 'path' missing", 0);
    std::string err;
    if (!g_shm.open(req.str("path"), err)) return errorLine(err, 0);
    bjson::Writer w;
    w.beginObj();
    w.kv("ok", true).kv("op", "shm.open");
    w.kv("bytes", static_cast<long long>(g_shm.size()));
    w.endObj();
    return w.done();
}

std::string handleSolveShm(const bjson::Value& req) {
    long long revision = 0;
    std::string revErr;
    if (!readRevision(req, revision, revErr)) return errorLine(revErr, 0);
    if (!g_shm.valid()) return errorLine("solve.shm before shm.open", revision);

    // The doorbell carries the request's byte length, and that length — never the
    // mapping capacity — is the frame (#27). Reading to the end of the mapping
    // would let a truncated request keep parsing into whatever the LAST frame left
    // behind, which is exactly the stale-tail corruption this field exists to stop.
    if (!req.isExactInt("bytes")) {
        return errorLine("solve.shm: 'bytes' missing or not a plain integer", revision);
    }
    const long long reqBytes = req.exactI64("bytes");
    if (reqBytes <= 0 || static_cast<unsigned long long>(reqBytes) > g_shm.size()) {
        return errorLine("solve.shm: 'bytes' out of range for the mapped region", revision);
    }

    brshm::Reader rd{ g_shm.data(), g_shm.data() + static_cast<size_t>(reqBytes) };
    if (rd.u32() != kShmReqMagic) return errorLine("shm request: bad magic", revision);
    // hi/lo are composed UNSIGNED first and range-checked before touching a signed
    // value (#29): a corrupt hi word must be a protocol error, not a signed-shift
    // excursion into implementation-defined territory.
    const std::uint64_t revLo = rd.u32();
    const std::uint64_t revHi = rd.u32();
    const std::uint64_t shmRevU = revLo | (revHi << 32);
    if (shmRevU > 0x7fffffffffffffffULL) {
        return errorLine("shm request: revision out of range", revision);
    }
    const long long shmRev = static_cast<long long>(shmRevU);
    // The doorbell and the region must agree about WHICH request this is; a skew
    // means the two sides have lost sync, and answering would answer the wrong
    // question with confident numbers.
    if (shmRev != revision) {
        return errorLine("shm request: revision skew between doorbell and region", revision);
    }
    const std::uint32_t flags = rd.u32();
    // Unknown flag bits are refused, not ignored (#28): a future bit changes what
    // the request MEANS, and an old engine that ignores it would answer a different
    // question with a confident yes.
    if ((flags & ~1u) != 0) return errorLine("shm request: unknown flags", revision);
    const bool wantBuckling = (flags & 1u) != 0;

    const auto& toks = tokenTable();
    const auto& mats = materialTable();

    // Same world-domain guard the JSON path applies (#28): the binary wire is not a
    // side door around the coordinate contract.
    auto coordOk = [](std::int32_t c) { return c >= -30000000 && c <= 30000000; };

    std::vector<InBlock> blocks;
    std::set<BlockPos>   seen;
    const std::uint32_t nBlocks = rd.u32();
    for (std::uint32_t k = 0; k < nBlocks && rd.ok; ++k) {
        InBlock b;
        b.pos.x = rd.i32();
        b.pos.y = rd.i32();
        b.pos.z = rd.i32();
        if (!coordOk(b.pos.x) || !coordOk(b.pos.y) || !coordOk(b.pos.z)) {
            return errorLine("shm block: coordinate out of world range", revision);
        }
        const std::int32_t matIdx = rd.i32();
        const std::int32_t tokIdx = rd.i32();
        const std::uint32_t bf    = rd.u32();
        // A frame truncated mid-block must be diagnosed as a truncation. The zero-filled
        // fields a failed read returns would otherwise pass the range checks and could
        // trip the duplicate-coordinate error, pointing the debugger at block sync
        // when the actual fault is the transport length.
        if (!rd.ok) break;
        if (matIdx < 0 || matIdx >= static_cast<std::int32_t>(mats.size())) {
            return errorLine("shm block: material index out of range", revision);
        }
        if (tokIdx < 0 || tokIdx >= static_cast<std::int32_t>(toks.size())) {
            return errorLine("shm block: section/plate index out of range", revision);
        }
        if ((bf & ~1u) != 0) return errorLine("shm block: unknown flags", revision);
        b.mat     = mats[static_cast<size_t>(matIdx)];
        b.section = toks[static_cast<size_t>(tokIdx)];
        b.support = (bf & 1u) != 0;
        if (!seen.insert(b.pos).second) {
            return errorLine("duplicate block coordinate; the caller and the engine disagree "
                             "about what is at that position", revision);
        }
        blocks.push_back(b);
    }

    std::vector<std::array<double, 6>> loads;
    std::vector<BlockPos>              loadAt;
    const std::uint32_t nLoads = rd.u32();
    for (std::uint32_t k = 0; k < nLoads && rd.ok; ++k) {
        BlockPos p;
        p.x = rd.i32();
        p.y = rd.i32();
        p.z = rd.i32();
        if (!coordOk(p.x) || !coordOk(p.y) || !coordOk(p.z)) {
            return errorLine("shm load: coordinate out of world range", revision);
        }
        std::array<double, 6> f{};
        for (int c = 0; c < 6; ++c) {
            f[c] = rd.f64();
            if (!std::isfinite(f[c])) return errorLine("shm load: component is not finite", revision);
        }
        if (!rd.ok) break;   // truncated mid-load: report truncation, not a junk row
        loadAt.push_back(p);
        loads.push_back(f);
    }
    if (!rd.ok) return errorLine("shm request: truncated", revision);
    // The frame must be consumed EXACTLY (#27). Trailing bytes inside the declared
    // length mean the two ends disagree about the schema, and a schema disagreement
    // that still parses is the most dangerous kind.
    if (rd.p != rd.end) return errorLine("shm request: trailing bytes after the frame", revision);

    SolveOut s = runSolve(blocks, loads, loadAt, wantBuckling);
    // A refused solve refuses identically on both transports: the doorbell carries
    // the same error line JSON `solve` would have, and the region is not written.
    if (!s.ok) return errorLine(s.error, revision);
    const size_t bytes = encodeShmReply(s, revision);
    if (bytes == 0) {
        return errorLine("shm reply does not fit in " + std::to_string(g_shm.size())
                       + " bytes; grow the region and retry", revision);
    }

    bjson::Writer w;
    w.beginObj();
    w.kv("ok", true).kv("op", "solve.shm").kv("revision", revision);
    w.kv("bytes", static_cast<long long>(bytes));
    w.endObj();
    return w.done();
}

std::string handleHello() {
    bjson::Writer w;
    w.beginObj();
    w.kv("ok", true).kv("op", "hello").kv("engine", "FrameCore").kv("protocol", kProtocol);
    // Capability, not version: protocol 1 clients that predate the shared-memory
    // transport ignore the key and keep speaking JSON. shm=1 names the binary
    // layout (BRQ1/BRP1); a layout change bumps this number, never reuses it.
    w.kv("shm", 1);
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
            if      (op == "hello")     reply = handleHello();
            else if (op == "solve")     reply = handleSolve(req);
            else if (op == "shm.open")  reply = handleShmOpen(req);
            else if (op == "solve.shm") reply = handleSolveShm(req);
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
