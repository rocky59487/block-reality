// bsi_canon.hpp -- block / load field validation and canonical ordering
// (BSI.md B.4, B.8). Header-only; used by the session and by tests.
#pragma once
#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>
#include "../bsi_engine.h"

namespace bsi { namespace canon {

constexpr int32_t kMaxCoord = 1073741822;

struct Verdict {
    bool ok = true;
    std::string code;        // PROTOCOL_ERROR | LOAD_UNSUPPORTED
    std::string message;
    bool hasAt = false;
    int32_t at[3] = {0, 0, 0};
    void fail(const char* c, const std::string& m, const int32_t* xyz) {
        ok = false; code = c; message = m;
        if (xyz) { hasAt = true; at[0] = xyz[0]; at[1] = xyz[1]; at[2] = xyz[2]; }
    }
};

inline bool lessXyz(int32_t ax, int32_t ay, int32_t az, int32_t bx, int32_t by, int32_t bz) {
    if (ax != bx) return ax < bx;
    if (ay != by) return ay < by;
    return az < bz;
}

// Field validation of one block record (nMaterials / nSections from the vocab).
inline Verdict checkBlock(const bsi_block& b, uint32_t nMaterials, uint32_t nSections) {
    Verdict v;
    const int32_t at[3] = {b.x, b.y, b.z};
    if (b.x < -kMaxCoord || b.x > kMaxCoord || b.y < -kMaxCoord || b.y > kMaxCoord || b.z < -kMaxCoord || b.z > kMaxCoord) { v.fail("PROTOCOL_ERROR", "coordinate out of range", at); return v; }
    if (b.axis > 2) { v.fail("PROTOCOL_ERROR", "axis out of range", at); return v; }
    if (b.joint > 1) { v.fail("PROTOCOL_ERROR", "joint out of range", at); return v; }
    if (b.axisRot > 3) { v.fail("PROTOCOL_ERROR", "axisRot out of range", at); return v; }
    if (b.attr > 1) { v.fail("PROTOCOL_ERROR", "attr out of range", at); return v; }
    if (b.mat < 0 || (uint32_t)b.mat >= nMaterials) { v.fail("PROTOCOL_ERROR", "unknown material id", at); return v; }
    if (b.sect < -1 || (b.sect >= 0 && (uint32_t)b.sect >= nSections)) { v.fail("PROTOCOL_ERROR", "unknown section id", at); return v; }
    if (!std::isfinite(b.fill) || !(b.fill > 0) || b.fill > 1) { v.fail("PROTOCOL_ERROR", "fill out of (0,1]", at); return v; }
    if (!std::isfinite(b.strength) || b.strength < 0 || b.strength > 1) { v.fail("PROTOCOL_ERROR", "strength out of [0,1]", at); return v; }
    return v;
}

// Is the array already in canonical order with no duplicates?
inline bool isCanonical(const bsi_block* b, uint32_t n, Verdict* dup = nullptr) {
    for (uint32_t k = 1; k < n; ++k) {
        const bsi_block& p = b[k - 1]; const bsi_block& q = b[k];
        if (!lessXyz(p.x, p.y, p.z, q.x, q.y, q.z)) {
            if (dup && p.x == q.x && p.y == q.y && p.z == q.z) { const int32_t at[3] = {q.x, q.y, q.z}; dup->fail("PROTOCOL_ERROR", "duplicate block", at); }
            return false;
        }
    }
    return true;
}

// Sort a copy into canonical order; reports the first duplicate.
inline Verdict canonicalise(std::vector<bsi_block>& v) {
    std::sort(v.begin(), v.end(), [](const bsi_block& a, const bsi_block& b) { return lessXyz(a.x, a.y, a.z, b.x, b.y, b.z); });
    Verdict out;
    for (size_t k = 1; k < v.size(); ++k)
        if (v[k - 1].x == v[k].x && v[k - 1].y == v[k].y && v[k - 1].z == v[k].z) {
            const int32_t at[3] = {v[k].x, v[k].y, v[k].z}; out.fail("PROTOCOL_ERROR", "duplicate block", at); return out;
        }
    return out;
}

inline Verdict checkLoad(const bsi_load& l) {
    Verdict v;
    const int32_t at[3] = {l.x, l.y, l.z};
    if (l.flags != 0) { v.fail("PROTOCOL_ERROR", "load flags must be 0", at); return v; }
    for (int k = 0; k < 3; ++k) if (!std::isfinite(l.f[k]) || !std::isfinite(l.m[k])) { v.fail("PROTOCOL_ERROR", "non-finite load component", at); return v; }
    if (l.m[0] != 0 || l.m[1] != 0 || l.m[2] != 0) { v.fail("LOAD_UNSUPPORTED", "moments must be 0 in v1", at); return v; }
    return v;
}

// (x,y,z) ascending, ties by memcmp of the raw 64-byte record (B.8 rule 2).
inline void canonicaliseLoads(std::vector<bsi_load>& v) {
    std::sort(v.begin(), v.end(), [](const bsi_load& a, const bsi_load& b) {
        if (a.x != b.x) return a.x < b.x;
        if (a.y != b.y) return a.y < b.y;
        if (a.z != b.z) return a.z < b.z;
        return std::memcmp(&a, &b, sizeof(bsi_load)) < 0;
    });
}

}}  // namespace bsi::canon
