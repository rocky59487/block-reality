#include "bsi_reply.hpp"
#include "bsi_schema.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>

namespace bsi {

int ReplyBuilder::blocks(const bsi_block_result* r, uint32_t n) {
    if (haveBlocks_) { blocksTwice_ = true; return BSI_E_INTERNAL; }
    haveBlocks_ = true;
    blocks_.assign(r, r + n);
    return BSI_OK;
}

int ReplyBuilder::member(const bsi_member_result* m, const int32_t* xyz, uint32_t nb, const bsi_station* st, uint32_t ns) {
    if (!m) return BSI_E_INTERNAL;
    bsi_member_result copy = *m;
    copy.blockFirst = (uint32_t)(memberBlocks_.size() / 3);
    copy.blockCount = nb;
    copy.stationFirst = (uint32_t)stations_.size();
    copy.stationCount = ns;
    memberBlocks_.insert(memberBlocks_.end(), xyz, xyz + (size_t)nb * 3);
    stations_.insert(stations_.end(), st, st + ns);
    members_v_.push_back(copy);
    return BSI_OK;
}

int ReplyBuilder::facet(const bsi_facet_result* f, const int32_t* xyz, uint32_t nb, const bsi_surface top[4], const bsi_surface bottom[4]) {
    if (!f) return BSI_E_INTERNAL;
    bsi_facet_result copy = *f;
    copy.blockFirst = (uint32_t)(facetBlocks_.size() / 3);
    copy.blockCount = nb;
    facetBlocks_.insert(facetBlocks_.end(), xyz, xyz + (size_t)nb * 3);
    for (int k = 0; k < 4; ++k) surfaces_.push_back(top[k]);
    for (int k = 0; k < 4; ++k) surfaces_.push_back(bottom[k]);
    facets_v_.push_back(copy);
    return BSI_OK;
}

int ReplyBuilder::unassigned(const char* why, int32_t island, const int32_t* xyz, uint32_t nb) {
    if (!why) return BSI_E_INTERNAL;
    UnassignedGroup g; g.why = why; g.island = island;
    g.xyz.assign(xyz, xyz + (size_t)nb * 3);
    unassigned_.push_back(std::move(g));
    return BSI_OK;
}

int ReplyBuilder::warning(const char* code, uint32_t count) {
    if (!code || count == 0) return BSI_E_INTERNAL;
    for (auto& w : warnings_) if (w.code == code) { w.count += count; return BSI_OK; }
    warnings_.push_back({code, count});
    return BSI_OK;
}

int ReplyBuilder::equilibrium(const double applied[3], const double reaction[3], double residual) {
    haveEq_ = true;
    for (int k = 0; k < 3; ++k) { eq_.applied[k] = applied[k]; eq_.reaction[k] = reaction[k]; }
    eq_.residual = residual;
    return BSI_OK;
}

int ReplyBuilder::quality(double achievedRel, int32_t iterations, uint8_t tierHonoured, uint8_t warmStartUsed, uint8_t timedOut) {
    haveQuality_ = true;
    qual_.achievedRel = achievedRel; qual_.iterations = iterations; qual_.tierHonoured = tierHonoured;
    qual_.warmStartUsed = warmStartUsed; qual_.storage = storage_; qual_.timedOut = timedOut;
    return BSI_OK;
}

int ReplyBuilder::buckling(int32_t island, uint8_t state, uint8_t kind, double factor) {
    Buckling b{}; b.island = island; b.state = state; b.kind = kind; b.reserved = 0; b.factor = factor;
    buckling_.push_back(b);
    return BSI_OK;
}

int ReplyBuilder::editClass(char cls, const char* downgraded) {
    if (cls != 'A' && cls != 'B' && cls != 'C') return BSI_E_INTERNAL;
    haveEdit_ = true; editCls_ = cls; editDowngraded_ = downgraded ? downgraded : "";
    return BSI_OK;
}

int ReplyBuilder::diag(uint32_t nodes, uint32_t members, uint32_t facets, uint32_t islands, uint32_t singularIslands, uint32_t refusedBlocks) {
    haveDiag_ = true; nodes_ = nodes; members_ = members; facets_ = facets; islands_ = islands; singular_ = singularIslands; refused_ = refusedBlocks;
    return BSI_OK;
}

int ReplyBuilder::error(const char* code, const char* message, const int32_t* at) {
    hasError_ = true;
    errCode_ = code ? code : "INTERNAL";
    errMsg_ = message ? message : "";
    if (at) { errHasAt_ = true; errAt_[0] = at[0]; errAt_[1] = at[1]; errAt_[2] = at[2]; }
    return BSI_OK;
}

int ReplyBuilder::attrsEcho(const bsi_attr* a, uint32_t n) {
    attrsEcho_.assign(a, a + n);
    return BSI_OK;
}

void ReplyBuilder::appendSection(const char* name, const void* data, uint64_t bytes, uint64_t count) {
    SectionInfo s; s.name = name; s.offset = payload_.size(); s.bytes = bytes; s.count = count;
    const uint8_t* p = (const uint8_t*)data;
    payload_.insert(payload_.end(), p, p + bytes);
    sections_.push_back(s);
}

bool ReplyBuilder::islandBucklingCritical(int32_t island, bool& hasRecord) const {
    hasRecord = false;
    for (const auto& bk : buckling_) {
        if (bk.island != island) continue;
        hasRecord = true;
        return bk.state == BSI_BSTATE_COMPUTED && std::isfinite(bk.factor) && bk.factor < 1.0;
    }
    return false;
}

std::string ReplyBuilder::bucklingState(uint8_t requestedMode) const {
    static const char* names[] = {"computed", "no-positive-eigenvalue", "not-eligible", "not-eligible-scale", "disabled-by-request", "solver-failed"};
    if (requestedMode == BSI_BUCK_NONE) return "disabled-by-request";
    if (buckling_.empty()) return "not-eligible";
    for (const auto& b : buckling_) if (b.state == BSI_BSTATE_COMPUTED) return "computed";
    uint8_t s = buckling_[0].state;
    return s < 6 ? names[s] : "solver-failed";
}

static bool sortUnassigned(std::vector<UnassignedGroup>& g) {
    const auto& sch = schema::embedded();
    auto order = sch.enumValues("unassignedWhy");
    auto rank = [&](const std::string& why) { for (size_t k = 0; k < order.size(); ++k) if (order[k] == why) return (int)k; return (int)order.size(); };
    for (auto& u : g) {
        // block order inside a group: (x,y,z) ascending
        std::vector<std::array<int32_t, 3>> v;
        for (size_t k = 0; k + 2 < u.xyz.size(); k += 3) v.push_back({u.xyz[k], u.xyz[k + 1], u.xyz[k + 2]});
        std::sort(v.begin(), v.end());
        u.xyz.clear();
        for (const auto& c : v) { u.xyz.push_back(c[0]); u.xyz.push_back(c[1]); u.xyz.push_back(c[2]); }
    }
    std::stable_sort(g.begin(), g.end(), [&](const UnassignedGroup& a, const UnassignedGroup& b) {
        int ra = rank(a.why), rb = rank(b.why);
        if (ra != rb) return ra < rb;
        if (a.island != b.island) return a.island < b.island;
        return a.xyz < b.xyz;
    });
    return true;
}

bool ReplyBuilder::finalizeDeclare(std::string& why) {
    if (!haveDiag_) { why = "engine wrote no diag"; return false; }
    std::sort(warnings_.begin(), warnings_.end(), [](const WarningCount& a, const WarningCount& b) { return a.code < b.code; });
    sortUnassigned(unassigned_);
    return true;
}

bool ReplyBuilder::finalizeSolve(std::string& why) {
    if (blocksTwice_) { why = "blocks written twice"; return false; }
    if (!haveBlocks_) { why = "engine wrote no blocks section"; return false; }
    if (blocks_.size() != declared_) { why = "blocks count " + std::to_string(blocks_.size()) + " != declared " + std::to_string(declared_); return false; }
    if (!haveEq_) { why = "engine wrote no equilibrium"; return false; }
    if (!haveQuality_) { why = "engine wrote no quality"; return false; }
    if (!haveDiag_) { why = "engine wrote no diag"; return false; }
    // ids strictly ascending
    for (size_t k = 1; k < members_v_.size(); ++k) if (members_v_[k].id <= members_v_[k - 1].id) { why = "member ids not ascending"; return false; }
    for (size_t k = 1; k < facets_v_.size(); ++k) if (facets_v_[k].id <= facets_v_[k - 1].id) { why = "facet ids not ascending"; return false; }
    // stations ascending in s within a member
    for (const auto& m : members_v_)
        for (uint32_t k = 1; k < m.stationCount; ++k)
            if (stations_[m.stationFirst + k].s < stations_[m.stationFirst + k - 1].s) { why = "stations not ascending in s"; return false; }
    // ownerKind consistency with unassigned
    std::map<std::array<int32_t, 3>, int> unassignedCells;
    for (const auto& g : unassigned_) for (size_t k = 0; k + 2 < g.xyz.size(); k += 3) unassignedCells[{g.xyz[k], g.xyz[k + 1], g.xyz[k + 2]}]++;
    for (const auto& kv : unassignedCells) if (kv.second != 1) { why = "block listed in more than one unassigned group"; return false; }
    // The builder does not know block coordinates (they belong to the session);
    // the count identity is checked here, the per-cell identity by the session.
    uint32_t unassignedKind = 0;
    for (const auto& b : blocks_) {
        if (b.ownerKind > 3) { why = "ownerKind out of range"; return false; }
        if (b.ownerKind == BSI_OWNER_UNASSIGNED) { ++unassignedKind; if (b.reason == 0) { why = "unassigned block without reason"; return false; } }
        else if (b.reason != 0) { why = "owned block with a reason code"; return false; }
        if (b.ownerKind == BSI_OWNER_MEMBER || b.ownerKind == BSI_OWNER_FACET) {
            bool found = false;
            if (b.ownerKind == BSI_OWNER_MEMBER) { for (const auto& m : members_v_) if (m.id == b.owner) found = true; if (!(include_ & kIncMembers)) found = true; }
            else { for (const auto& f : facets_v_) if (f.id == b.owner) found = true; if (!(include_ & kIncShells)) found = true; }
            if (!found) { why = "block owner id not written"; return false; }
        }
    }
    if (unassignedKind != unassignedCells.size()) { why = "ownerKind=unassigned count " + std::to_string(unassignedKind) + " != unassigned listing " + std::to_string(unassignedCells.size()); return false; }
    std::sort(warnings_.begin(), warnings_.end(), [](const WarningCount& a, const WarningCount& b) { return a.code < b.code; });
    sortUnassigned(unassigned_);
    std::sort(buckling_.begin(), buckling_.end(), [](const Buckling& a, const Buckling& b) { return a.island < b.island; });

    // ---- layout, fixed order ----
    payload_.clear(); sections_.clear();
    appendSection("blocks", blocks_.data(), (uint64_t)blocks_.size() * sizeof(bsi_block_result), blocks_.size());
    appendSection("equilibrium", &eq_, sizeof eq_, 1);
    appendSection("quality", &qual_, sizeof qual_, 1);
    appendSection("buckling", buckling_.data(), (uint64_t)buckling_.size() * sizeof(Buckling), buckling_.size());
    if (include_ & kIncMembers) {
        appendSection("members", members_v_.data(), (uint64_t)members_v_.size() * sizeof(bsi_member_result), members_v_.size());
        appendSection("memberBlocks", memberBlocks_.data(), (uint64_t)memberBlocks_.size() * 4, memberBlocks_.size() / 3);
    }
    if (include_ & kIncStations) {
        if (storage_ == BSI_STORAGE_F32) {
            std::vector<StationF32> f(stations_.size());
            for (size_t k = 0; k < stations_.size(); ++k) {
                const bsi_station& s = stations_[k]; StationF32& d = f[k];
                d.s = (float)s.s; d.x = (float)s.x; d.y = (float)s.y; d.z = (float)s.z;
                for (int j = 0; j < 4; ++j) d.sigma[j] = (float)s.sigma[j];
                d.tau = (float)s.tau; d.naY = (float)s.naY; d.naZ = (float)s.naZ;
            }
            appendSection("stations:f32", f.data(), (uint64_t)f.size() * sizeof(StationF32), f.size());
        } else appendSection("stations", stations_.data(), (uint64_t)stations_.size() * sizeof(bsi_station), stations_.size());
    }
    if (include_ & kIncShells) {
        appendSection("facets", facets_v_.data(), (uint64_t)facets_v_.size() * sizeof(bsi_facet_result), facets_v_.size());
        if (storage_ == BSI_STORAGE_F32) {
            std::vector<float> f(surfaces_.size() * 4);
            for (size_t k = 0; k < surfaces_.size(); ++k) { f[4 * k] = (float)surfaces_[k].s1; f[4 * k + 1] = (float)surfaces_[k].s2; f[4 * k + 2] = (float)surfaces_[k].theta; f[4 * k + 3] = (float)surfaces_[k].vm; }
            appendSection("facetSurfaces:f32", f.data(), (uint64_t)f.size() * 4, facets_v_.size());
        } else appendSection("facetSurfaces", surfaces_.data(), (uint64_t)surfaces_.size() * sizeof(bsi_surface), facets_v_.size());
    }
    if (include_ & kIncAttrsEcho) appendSection("attrsEcho", attrsEcho_.data(), (uint64_t)attrsEcho_.size() * sizeof(bsi_attr), attrsEcho_.size());
    return true;
}

}  // namespace bsi

// ---- the C ABI the engine calls ---------------------------------------------
extern "C" {

BSI_EXPORT int bsi_host_cancelled(const bsi_host* h) { return (h && h->s) ? h->s->cancelled : 0; }
BSI_EXPORT void bsi_host_log(const bsi_host* h, int level, const char* msg) {
    if (h && h->s && msg && level <= h->s->logLevel) std::fprintf(stderr, "[engine] %s\n", msg);
}

BSI_EXPORT int bsi_writer_blocks(bsi_writer* w, const bsi_block_result* r, uint32_t n) { return (w && w->b && (r || n == 0)) ? w->b->blocks(r, n) : BSI_E_INTERNAL; }
BSI_EXPORT int bsi_writer_member(bsi_writer* w, const bsi_member_result* m, const int32_t* xyz, uint32_t nb, const bsi_station* st, uint32_t ns) {
    return (w && w->b && (xyz || nb == 0) && (st || ns == 0)) ? w->b->member(m, xyz, nb, st, ns) : BSI_E_INTERNAL;
}
BSI_EXPORT int bsi_writer_facet(bsi_writer* w, const bsi_facet_result* f, const int32_t* xyz, uint32_t nb, const bsi_surface top[4], const bsi_surface bottom[4]) {
    return (w && w->b && top && bottom && (xyz || nb == 0)) ? w->b->facet(f, xyz, nb, top, bottom) : BSI_E_INTERNAL;
}
BSI_EXPORT int bsi_writer_unassigned(bsi_writer* w, const char* why, int32_t island, const int32_t* xyz, uint32_t nb) {
    return (w && w->b && (xyz || nb == 0)) ? w->b->unassigned(why, island, xyz, nb) : BSI_E_INTERNAL;
}
BSI_EXPORT int bsi_writer_warning(bsi_writer* w, const char* code, uint32_t count) { return (w && w->b) ? w->b->warning(code, count) : BSI_E_INTERNAL; }
BSI_EXPORT int bsi_writer_equilibrium(bsi_writer* w, const double applied[3], const double reaction[3], double residual) {
    return (w && w->b && applied && reaction) ? w->b->equilibrium(applied, reaction, residual) : BSI_E_INTERNAL;
}
BSI_EXPORT int bsi_writer_quality(bsi_writer* w, double achievedRel, int32_t iterations, uint8_t tierHonoured, uint8_t warmStartUsed, uint8_t timedOut) {
    return (w && w->b) ? w->b->quality(achievedRel, iterations, tierHonoured, warmStartUsed, timedOut) : BSI_E_INTERNAL;
}
BSI_EXPORT int bsi_writer_buckling(bsi_writer* w, int32_t island, uint8_t state, uint8_t kind, double factor) { return (w && w->b) ? w->b->buckling(island, state, kind, factor) : BSI_E_INTERNAL; }
BSI_EXPORT int bsi_writer_edit_class(bsi_writer* w, char cls, const char* downgradedOrNull) { return (w && w->b) ? w->b->editClass(cls, downgradedOrNull) : BSI_E_INTERNAL; }
BSI_EXPORT int bsi_writer_diag(bsi_writer* w, uint32_t nodes, uint32_t members, uint32_t facets, uint32_t islands, uint32_t singularIslands, uint32_t refusedBlocks) {
    return (w && w->b) ? w->b->diag(nodes, members, facets, islands, singularIslands, refusedBlocks) : BSI_E_INTERNAL;
}
BSI_EXPORT int bsi_writer_error(bsi_writer* w, const char* code, const char* message, const int32_t* atXyzOrNull) { return (w && w->b) ? w->b->error(code, message, atXyzOrNull) : BSI_E_INTERNAL; }

}  // extern "C"
