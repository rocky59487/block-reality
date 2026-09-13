// Shared fracture boundary: identity, source validation and owning wire receipt.
// Physics remains in the engine; this file never reconstructs mass or fracture.
#pragma once
#include "bsi_canon.hpp"
#include "bsi_json.hpp"
#include "bsi_reply.hpp"
#include <array>
#include <limits>
#include <map>
#include <set>

namespace bsi { namespace fracture_wire {
constexpr size_t kHeaderLimit = 4096;
constexpr size_t kPayloadLimit = 256u * 1024u * 1024u - kHeaderLimit - 12;
using Key = std::array<int32_t, 3>;
template<class T> Key key(const T& v) { return {{v.x, v.y, v.z}}; }
inline bool same(bsi_id128 a, bsi_id128 b) { return a.hi == b.hi && a.lo == b.lo; }
inline bool same(bsi_world_stamp a, bsi_world_stamp b) { return same(a.domain, b.domain) && a.revision == b.revision; }
inline bool same(bsi_trial_token a, bsi_trial_token b) { return same(a.context, b.context) && a.sequence == b.sequence; }
inline bool nonzero(bsi_id128 a) { return a.hi || a.lo; }
inline uint64_t hexWord(const std::string& s, size_t offset = 0) {
    uint64_t v = 0;
    for (size_t i = offset; i < offset + 16; ++i) v = (v << 4) | uint64_t(s[i] <= '9' ? s[i] - '0' : s[i] - 'a' + 10);
    return v;
}
inline std::string hex(uint64_t v) {
    std::string s(16, '0');
    for (size_t i = 16; i; v >>= 4) s[--i] = "0123456789abcdef"[v & 15];
    return s;
}
inline std::string hex(bsi_id128 id) { return hex(id.hi) + hex(id.lo); }
// Call only after schema validation of the exact lowercase hex widths.
inline bsi_id128 id(const json::Value& v) { return {hexWord(v.str), hexWord(v.str, 16)}; }
inline bsi_world_stamp stamp(const json::Value& v) { return {id(*v.find("domain")), v.find("revision")->i64}; }
inline bsi_trial_token token(const json::Value& v) { return {id(*v.find("context")), hexWord(v.find("sequence")->str)}; }
inline void writeStamp(json::Writer& w, bsi_world_stamp s) {
    w.beginObj(); w.kv("domain", hex(s.domain)); w.kv("revision", (long long)s.revision); w.endObj();
}
inline void writeIdentity(json::Writer& w, bsi_world_stamp s, bsi_id128 ns) {
    w.beginObj(); w.kv("domain", hex(s.domain)); w.kv("revision", (long long)s.revision);
    w.kv("artifactNamespace", hex(ns)); w.endObj();
}
inline void writeToken(json::Writer& w, bsi_trial_token t) {
    w.beginObj(); w.kv("context", hex(t.context)); w.kv("sequence", hex(t.sequence)); w.endObj();
}
inline bool uniqueKeys(const json::Value& v) {
    if (v.isObj()) {
        std::set<std::string> keys;
        for (const auto& kv : v.obj) if (!keys.insert(kv.first).second || !uniqueKeys(kv.second)) return false;
    } else if (v.isArr()) for (const auto& e : v.arr) if (!uniqueKeys(e)) return false;
    return true;
}
inline bool structural(const bsi_block& b, const std::vector<bsi_material>& materials) {
    auto role = materials[size_t(b.mat)].role;
    return role == BSI_ROLE_MEMBER || role == BSI_ROLE_PANEL || role == BSI_ROLE_MONOLITH;
}
inline bool ownersMatch(std::vector<bsi_artifact_owner>& owners, const std::vector<bsi_block>& world,
                        const std::vector<bsi_material>& materials) {
    std::sort(owners.begin(), owners.end(), [](const bsi_artifact_owner& a, const bsi_artifact_owner& b) { return key(a) < key(b); });
    size_t i = 0;
    for (const auto& b : world) if (structural(b, materials)) {
        if (i == owners.size() || key(owners[i]) != key(b) || owners[i].artifact <= 0) return false;
        ++i;
    }
    return i == owners.size();
}
inline std::vector<bsi_block> editedWorld(const std::vector<bsi_block>& world, const std::vector<bsi_edit>& edits) {
    // Only touched coordinates need a tree; the persistent world stays contiguous.
    std::map<Key, std::pair<bool, bsi_block>> changes;
    for (const auto& e : edits) {
        const Key k = key(e.block);
        auto inserted = changes.emplace(k, std::make_pair(false, bsi_block{}));
        auto& state = inserted.first->second;
        if (inserted.second) {
            auto it = std::lower_bound(world.begin(), world.end(), k, [](const bsi_block& b, const Key& q) { return key(b) < q; });
            if (it != world.end() && key(*it) == k) state = {true, *it};
        }
        if (e.op == BSI_EDIT_REMOVE) state.first = false;
        else if (e.op == BSI_EDIT_ADD || state.first) state = {true, e.block};
    }
    std::vector<bsi_block> result; result.reserve(world.size() + changes.size());
    auto change = changes.begin();
    for (const auto& b : world) {
        while (change != changes.end() && change->first < key(b)) {
            if (change->second.first) result.push_back(change->second.second);
            ++change;
        }
        if (change != changes.end() && change->first == key(b)) {
            if (change->second.first) result.push_back(change->second.second);
            ++change;
        } else result.push_back(b);
    }
    for (; change != changes.end(); ++change) if (change->second.first) result.push_back(change->second.second);
    return result;
}
struct Candidate {
    bsi_trial_token token{};
    bsi_id128 request{};
    bsi_world_stamp before{}, after{};
    bool committed = false;
    std::vector<bsi_block> remaining;
    std::vector<bsi_artifact_owner> owners;
};
inline bool physical(const bsi_physical_properties& p) {
    if (!std::isfinite(p.mass) || p.mass < 0) return false;
    for (double x : p.center) if (!std::isfinite(x)) return false;
    for (double x : p.inertia) if (!std::isfinite(x)) return false;
    return p.inertia[0] >= 0 && p.inertia[1] >= 0 && p.inertia[2] >= 0;
}
inline bool sections(const bsi_fracture_view& v, std::vector<SectionInfo>& out, size_t& bytes) {
    struct Span { const char* name; const void* data; uint64_t count, stride; };
    const Span spans[] = {{"physicalTotals", &v.beforeMass, 3, 80}, {"fractureCells", v.cells, v.nCells, 144},
        {"fractureFragments", v.fragments, v.nFragments, 96}, {"fractureParents", v.parents, v.nParents, 8},
        {"fractureEvents", v.events, v.nEvents, 24}, {"fractureEventCells", v.eventCells, v.nEventCells, 4},
        {"fractureMechanism", v.mechanismXyz, v.nMechanismCells, 12}};
    bytes = 0;
    for (const auto& s : spans) {
        const uint64_t n = s.count * s.stride;
        if ((s.count && !s.data) || n > kPayloadLimit - bytes) return false;
        out.push_back({s.name, bytes, n, s.count}); bytes += size_t(n);
    }
    return true;
}
inline bool receipt(const bsi_fracture_view* ptr, const bsi_fracture_options& options, bsi_id128 ns,
                    const std::vector<bsi_block>& world, const std::vector<bsi_artifact_owner>& owners,
                    const std::vector<bsi_material>& materials, Candidate& next,
                    std::vector<SectionInfo>& table, std::vector<uint8_t>& payload, std::string& why) {
    auto fail = [&](const char* text) { why = text; return false; };
    if (!ptr || ptr->struct_size < offsetof(bsi_fracture_view, flags) + sizeof(ptr->flags)) return fail("short fracture view");
    const auto& v = *ptr;
    size_t bytes = 0;
    if (!sections(v, table, bytes)) return fail("fracture spans exceed wire budget or contain NULL");
    if (!same(v.request, options.request) || !same(v.artifactNamespace, ns) || !same(v.before, options.expected) ||
        options.expected.revision == INT64_MAX || !same(v.after.domain, v.before.domain) || v.after.revision != v.before.revision + 1 ||
        !nonzero(v.token.context) || !v.token.sequence || v.flags > 3 || v.steps > options.budget) return fail("fracture identity mismatch");
    if (v.nCells != owners.size() || v.nFragments > v.nCells || v.nMechanismCells > v.nCells) return fail("fracture source count mismatch");
    if (!physical(v.beforeMass) || !physical(v.remainingMass) || !physical(v.brokenMass)) return fail("invalid physical totals");
    next.token = v.token; next.request = v.request; next.before = v.before; next.after = v.after;
    next.remaining.reserve(world.size()); next.owners.reserve(owners.size());
    std::vector<std::vector<int64_t>> parents(v.nFragments);
    size_t i = 0;
    for (const auto& b : world) {
        if (!structural(b, materials)) { next.remaining.push_back(b); continue; }
        const auto& c = v.cells[i];
#ifndef BSI_TEST_NFW_SOURCE
        if (std::memcmp(&c.source, &b, sizeof b) || c.artifact != owners[i].artifact) return fail("fracture source changed");
#endif
#ifndef BSI_TEST_NFW_PARTITION
        if (c.reserved || c.flags > 1 || ((c.flags & 1) && c.group) || c.group > uint64_t(v.nFragments) + 1 ||
            !physical(c.physical)) return fail("invalid fracture partition");
#endif
        const bool panel = materials[size_t(b.mat)].role == BSI_ROLE_PANEL;
        if (panel ? (c.panelNormal < 0 || c.panelNormal > 2) : c.panelNormal != -1) return fail("invalid panel normal");
        if (!c.group) { next.remaining.push_back(b); next.owners.push_back(owners[i]); }
        else if (c.group >= 2) parents[c.group - 2].push_back(c.artifact);
        ++i;
    }
    uint64_t parentOffset = 0;
    for (uint32_t f = 0; f < v.nFragments; ++f) {
        const auto& frag = v.fragments[f]; auto& ids = parents[f];
        if (ids.empty() || frag.reserved || frag.flags > 7 || !physical(frag.physical)) return fail("invalid or empty fragment");
        std::sort(ids.begin(), ids.end()); ids.erase(std::unique(ids.begin(), ids.end()), ids.end());
        if (frag.parentFirst != parentOffset || frag.parentCount != ids.size() || parentOffset + ids.size() > v.nParents) return fail("invalid parent range");
        for (auto id : ids) {
#ifndef BSI_TEST_NFW_PARENTS
            if (v.parents[parentOffset] != id) return fail("fragment parent mismatch");
#else
            (void)id;
#endif
            ++parentOffset;
        }
    }
    if (parentOffset != v.nParents) return fail("unclaimed parents");
    std::vector<bool> broken(v.nCells, false);
    uint64_t eventOffset = 0;
    for (uint32_t e = 0; e < v.nEvents; ++e) {
        const auto& event = v.events[e];
        if (event.cellFirst != eventOffset || !event.cellCount || eventOffset + event.cellCount > v.nEventCells ||
            !std::isfinite(event.utilization) || event.utilization < 0) return fail("invalid fracture event");
#ifndef BSI_TEST_NFW_EVENT
        if (event.face > 5) return fail("invalid fracture face");
#endif
        for (auto r : event.reserved) if (r) return fail("nonzero event reserved");
        for (uint32_t j = 0; j < event.cellCount; ++j) {
            auto cell = v.eventCells[eventOffset++];
            if (cell >= v.nCells || v.cells[cell].group != 1 || broken[cell]) return fail("invalid event source");
            broken[cell] = true;
        }
    }
    if (eventOffset != v.nEventCells) return fail("unclaimed event cells");
    for (uint32_t c = 0; c < v.nCells; ++c) if ((v.cells[c].group == 1) != broken[c]) return fail("broken cell missing event");
    for (uint32_t m = 0; m < v.nMechanismCells; ++m) {
        const auto* xyz = v.mechanismXyz + size_t(m) * 3;
        Key k{{xyz[0], xyz[1], xyz[2]}};
        auto it = std::lower_bound(owners.begin(), owners.end(), k, [](const bsi_artifact_owner& o, const Key& q) { return key(o) < q; });
        if (it == owners.end() || key(*it) != k) return fail("foreign mechanism cell");
    }
    static_assert(sizeof(bsi_physical_properties) == 80 && sizeof(bsi_fracture_cell) == 144 &&
                  sizeof(bsi_fracture_fragment) == 96 && sizeof(bsi_fracture_event) == 24, "fracture wire strides");
    // Contract platforms are little endian. Refuse another representation explicitly.
    const uint16_t endian = 1;
    if (*reinterpret_cast<const uint8_t*>(&endian) != 1) return fail("fracture wire requires little endian host");
    payload.resize(bytes);
    std::memcpy(payload.data(), &v.beforeMass, 80);
    std::memcpy(payload.data() + 80, &v.remainingMass, 80);
    std::memcpy(payload.data() + 160, &v.brokenMass, 80);
    const void* spans[] = {v.cells, v.fragments, v.parents, v.events, v.eventCells, v.mechanismXyz};
    for (size_t s = 1; s < table.size(); ++s) if (table[s].bytes)
        std::memcpy(payload.data() + table[s].offset, spans[s - 1], size_t(table[s].bytes));
    return true;
}
}} // namespace bsi::fracture_wire
