// stub_engine.cpp -- a ZERO-MECHANICS test double for the host (contract artifact).
// It exists so the host's own rules (schema, ordering, packing, transport
// equivalence, hello refusal, fail-closed steps) can be exercised without an
// engine. It declares "x-bsi.stub" so the conformance runner refuses to run any
// mechanics family against it. Nothing it writes is a structural result.
//
// BSI_STUB_MUTATE=short_blocks|bad_owner|dup_member|no_equilibrium drives the
// host's INTERNAL consistency checks in host_tests (MC68-06/17).
#define BSI_ENGINE_BUILD 1
#include "../../bsi_engine.h"
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

struct Stub {
    const bsi_host* host;
    std::vector<bsi_material> materials;    // copies (name pointers stay valid: host owns them for the session)
    std::vector<bsi_block> blocks;
    bool haveVocab = false, haveWorld = false;
};

const char* const kCaps[] = {"bsi.core", "bsi.readback.members", "bsi.world.edit", "x-bsi.stub"};

#ifndef BSI_STUB_ABI
#define BSI_STUB_ABI BSI_ENGINE_ABI
#endif

const char* s_name(void) { return "bsi-stub"; }
const char* s_version(void) { return "0.0.1"; }
const char* s_build(void) { return "stub"; }
uint32_t s_caps(const char* const** out) { *out = kCaps; return 4; }
bsi_engine* s_open(const bsi_host* h) { Stub* s = new Stub(); s->host = h; return (bsi_engine*)s; }
void s_close(bsi_engine* e) { delete (Stub*)e; }

int s_vocab(bsi_engine* e, const bsi_vocab* v) {
    Stub* s = (Stub*)e;
    s->materials.assign(v->materials, v->materials + v->nMaterials);
    s->haveVocab = true;
    return BSI_OK;
}

int s_declare(bsi_engine* e, const bsi_block* b, uint32_t n, const bsi_attr* a, uint32_t na, bsi_writer* w) {
    Stub* s = (Stub*)e;
    (void)a;
    if (!s->haveVocab) return BSI_E_VOCAB;
    if (na) { bsi_writer_error(w, "UNSUPPORTED_ATTR", "stub supports no attributes", nullptr); return BSI_E_UNSUPPORTED_ATTR; }
    s->blocks.assign(b, b + n);
    s->haveWorld = true;
    uint32_t members = 0;
    for (const bsi_block& blk : s->blocks) if (s->materials[(size_t)blk.mat].role == BSI_ROLE_MEMBER) ++members;
    bsi_writer_diag(w, members ? 2 : 0, members ? 1 : 0, 0, members ? 1 : 0, 0, 0);
    return BSI_OK;
}

int s_edit(bsi_engine* e, const bsi_edit* ed, uint32_t n, bsi_writer* w) {
    Stub* s = (Stub*)e;
    if (!s->haveWorld) return BSI_E_NO_WORLD;
    for (uint32_t k = 0; k < n; ++k) {
        const bsi_block& b = ed[k].block;
        size_t idx = s->blocks.size();
        for (size_t j = 0; j < s->blocks.size(); ++j) if (s->blocks[j].x == b.x && s->blocks[j].y == b.y && s->blocks[j].z == b.z) { idx = j; break; }
        if (ed[k].op == BSI_EDIT_REMOVE) { if (idx < s->blocks.size()) s->blocks.erase(s->blocks.begin() + (long)idx); }
        else if (idx < s->blocks.size()) s->blocks[idx] = b;
        else s->blocks.push_back(b);
    }
    bsi_writer_edit_class(w, 'C', nullptr);
    bsi_writer_diag(w, 0, 0, 0, 0, 0, 0);
    return BSI_OK;
}

int s_solve(bsi_engine* e, const bsi_solve_options* o, const bsi_load* loads, uint32_t nLoads, bsi_writer* w) {
    Stub* s = (Stub*)e;
    if (!s->haveWorld) return BSI_E_NO_WORLD;
    const char* mut = std::getenv("BSI_STUB_MUTATE");
    std::string m = mut ? mut : "";
    if (o->maxTimeMs) { /* stub: honoured trivially */ }
    // loads must land on a member-role block, else LOAD_TARGET (the host cannot know roles per block)
    for (uint32_t k = 0; k < nLoads; ++k) {
        bool found = false;
        for (const bsi_block& b : s->blocks)
            if (b.x == loads[k].x && b.y == loads[k].y && b.z == loads[k].z && s->materials[(size_t)b.mat].role == BSI_ROLE_MEMBER) found = true;
        if (!found) { const int32_t at[3] = {loads[k].x, loads[k].y, loads[k].z}; bsi_writer_error(w, "LOAD_TARGET", "no member owns this block", at); return BSI_E_LOAD_TARGET; }
    }
    std::vector<bsi_block_result> res(s->blocks.size());
    std::vector<int32_t> memberXyz;
    std::vector<int32_t> nonstructXyz, supportless;
    for (size_t i = 0; i < s->blocks.size(); ++i) {
        const bsi_block& b = s->blocks[i];
        bsi_block_result& r = res[i];
        std::memset(&r, 0, sizeof r);
        uint8_t role = s->materials[(size_t)b.mat].role;
        r.dc = 0; r.island = 0;
        if (role == BSI_ROLE_MEMBER) { r.owner = 0; r.ownerKind = BSI_OWNER_MEMBER; r.mode = BSI_MODE_NONE; memberXyz.push_back(b.x); memberXyz.push_back(b.y); memberXyz.push_back(b.z); }
        else if (role == BSI_ROLE_SUPPORT) { r.owner = -1; r.ownerKind = BSI_OWNER_NONE; }
        else { r.owner = -1; r.ownerKind = BSI_OWNER_UNASSIGNED; r.reason = 7; /* NON_STRUCTURAL */ nonstructXyz.push_back(b.x); nonstructXyz.push_back(b.y); nonstructXyz.push_back(b.z); }
    }
    if (m == "bad_owner" && !res.empty()) res[0].ownerKind = BSI_OWNER_UNASSIGNED;     // listed nowhere => INTERNAL
    if (m == "short_blocks") { if (!res.empty()) res.pop_back(); }
    // blocks.flags bit2 escapes (BSI_ADD1 ADD1-02). Three ways to lie about
    // stability, one per direction the host has to watch:
    //   bit2_orphan  set the bit with no buckling record behind it
    //   bit2_lie     set the bit while the record says the lane was disabled
    //   bit2_missing the island IS critical and the blocks stay unflagged
    //   bit2_ground  set the bit on a cell that owns no element
    for (auto& r : res)
        if ((m == "bit2_orphan" || m == "bit2_lie") && r.ownerKind == BSI_OWNER_MEMBER) r.flags |= 4u;
    if (m == "bit2_ground") for (auto& r : res) if (r.ownerKind != BSI_OWNER_MEMBER) r.flags |= 4u;
    bsi_writer_blocks(w, res.data(), (uint32_t)res.size());
    if (!nonstructXyz.empty()) bsi_writer_unassigned(w, "NON_STRUCTURAL", -1, nonstructXyz.data(), (uint32_t)(nonstructXyz.size() / 3));
    if ((o->includeMask & 1u) && !memberXyz.empty()) {
        bsi_member_result mr; std::memset(&mr, 0, sizeof mr);
        mr.id = 0; mr.island = 0; mr.material = s->blocks[0].mat; mr.section = -1; mr.lengthM = (double)(memberXyz.size() / 3);
        mr.mode = BSI_MODE_NONE; mr.governingFibre = BSI_FIBRE_NONE;
        bsi_writer_member(w, &mr, memberXyz.data(), (uint32_t)(memberXyz.size() / 3), nullptr, 0);
        if (m == "dup_member") bsi_writer_member(w, &mr, memberXyz.data(), (uint32_t)(memberXyz.size() / 3), nullptr, 0);
    }
    const double z[3] = {0, 0, 0};
    if (m != "no_equilibrium") bsi_writer_equilibrium(w, z, z, 0.0);
    bsi_writer_quality(w, 0.0, 0, 1, 0, 0);
    if (m == "bit2_missing") bsi_writer_buckling(w, 0, BSI_BSTATE_COMPUTED, BSI_BUCK_EIGEN, 0.5);
    else if (m != "bit2_orphan") bsi_writer_buckling(w, 0, BSI_BSTATE_DISABLED, BSI_BUCK_NONE, std::nan(""));
    bsi_writer_diag(w, memberXyz.empty() ? 0 : 2, memberXyz.empty() ? 0 : 1, 0, memberXyz.empty() ? 0 : 1, 0, 0);
    return BSI_OK;
}

int s_cancel(bsi_engine*) { return BSI_OK; }

const bsi_engine_vtable kVt = {
    BSI_STUB_ABI, s_name, s_version, s_build, s_caps, s_open, s_close, s_vocab, s_declare, s_edit, s_solve, s_cancel};

}  // namespace

extern "C" BSI_EXPORT const bsi_engine_vtable* bsi_engine_entry(uint32_t hostAbi) {
    return hostAbi == BSI_STUB_ABI ? &kVt : nullptr;
}
