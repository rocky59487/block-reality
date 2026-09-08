// Independent little-endian layout and binary32 constants; no engine/BLAS.
#include "../bsi_reply.hpp"
#include "../bsi_schema.hpp"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <map>
#include <string>
#include <vector>
using Bytes = std::vector<uint8_t>;
static int checks = 0, failures = 0;
static uint64_t digest = 1469598103934665603ULL;
static void check(const std::string& id, bool ok) {
    ++checks; if (!ok) ++failures;
    std::printf("%s: %s\n", id.c_str(), ok ? "PASS" : "FAIL");
}
static void put(Bytes& b, size_t at, uint64_t v, size_t n) {
    for (size_t k = 0; k < n; ++k) b.at(at + k) = uint8_t(v >> (8 * k));
}
static uint64_t bits(double v) { uint64_t u; std::memcpy(&u, &v, 8); return u; }
static const double values[] = {0., -0., 1., .5, 1. + 0x1p-24, 1. + 3 * 0x1p-24,
    0x1p-149, 0x1p-150, 3 * 0x1p-150, -0x1p-150, 0x1.fffffep127};
static const uint32_t floatBits[] = {0, 0x80000000u, 0x3f800000u, 0x3f000000u,
    0x3f800000u, 0x3f800002u, 1, 0, 2, 0x80000000u, 0x7f7fffffu};
static uint32_t expected32(double v) {
    for (size_t k = 0; k < 11; ++k) if (bits(v) == bits(values[k])) return floatBits[k];
    // NaN payload is implementation-defined; checked separately by isnan.
    return 0x7fc00000u;
}
struct Fixture {
    bsi_member_result members[2]{};
    bsi_station stations[3]{};
    bsi_facet_result facets[2]{};
    bsi_surface surfaces[16]{};
    const int32_t xyz[9] = {-7, 8, 9, 10, -11, 12, 13, 14, -15};
    Fixture() {
        for (int e = 0; e < 2; ++e) {
            auto& m = members[e]; m.id = 7 + 4 * e; m.island = e; m.material = 3; m.section = 4;
            m.lengthM = 4.5 + e; m.maxDC = 1. / 3; m.governingS = .5;
            m.mode = 5; m.governingFibre = 2; m.flags = 3;
            m.blockFirst = m.stationFirst = 999; m.blockCount = m.stationCount = 777;
            for (int k = 0; k < 6; ++k) { m.endI[k] = 10.5 + k + e; m.endJ[k] = -20.5 - k - e; }
            auto& f = facets[e]; f.id = 3 + 6 * e; f.island = e; f.material = 5;
            f.blockFirst = 999; f.blockCount = 777; f.thicknessM = .2;
            for (int k = 0; k < 4; ++k) for (int j = 0; j < 3; ++j) f.corners[k][j] = 100 * e + 3 * k + j + .5;
            f.ex[0] = f.ey[1] = f.n[2] = 1;
            for (int k = 0; k < 3; ++k) { f.N[k] = 1.5 + k; f.M[k] = -4.5 - k; }
            f.Q[0] = 7.5; f.Q[1] = -8.5; f.dc = 1. / 3; f.flags = 3; f.governingFibre = 6;
        }
        for (int k = 0; k < 3; ++k) {
            double v[11]; for (int j = 0; j < 11; ++j) v[j] = values[(j + k) % 11];
            v[0] = k == 1 ? 1 : 0;
            std::memcpy(&stations[k], v, sizeof v);
        }
        for (int k = 0; k < 16; ++k) {
            double v[4]; for (int j = 0; j < 4; ++j) v[j] = values[(4 * k + j) % 11];
            std::memcpy(&surfaces[k], v, sizeof v);
        }
    }
    void write(bsi::ReplyBuilder& w, bool recovery = true) const {
        bsi_block_result b[2]{}; b[0].dc = 1. / 3; b[1].dc = 1. + 0x1p-30; b[1].flags = 1;
        w.blocks(b, 2);
        const double zero[3] = {}; w.equilibrium(zero, zero, 0); w.quality(0, 0, 1, 0, 0);
        w.diag(8, recovery ? 2 : 0, recovery ? 2 : 0, 2, 0, 0);
        if (!recovery) return;
        w.member(&members[0], xyz, 2, stations, 2);
        w.member(&members[1], xyz + 6, 1, stations + 2, 1);
        w.facet(&facets[0], xyz, 1, surfaces, surfaces + 4);
        w.facet(&facets[1], xyz + 3, 2, surfaces + 8, surfaces + 12);
    }
};
static std::map<std::string, Bytes> sections(const bsi::ReplyBuilder& w) {
    std::map<std::string, Bytes> out;
    for (auto& s : w.sections()) out[s.name] = Bytes(w.payload().begin() + s.offset, w.payload().begin() + s.offset + s.bytes);
    return out;
}
static Bytes coordinates(const Fixture& f) {
    Bytes b(36); for (int k = 0; k < 9; ++k) put(b, 4 * k, uint32_t(f.xyz[k]), 4); return b;
}
static Bytes members(const Fixture& f) {
    Bytes b(320);
    for (int e = 0; e < 2; ++e) {
        const auto& m = f.members[e]; size_t o = 160 * e;
        const int32_t ints[] = {m.id, e, e ? 2 : 0, e ? 1 : 2, e ? 2 : 0, e ? 1 : 2, 3, 4};
        for (int k = 0; k < 8; ++k) put(b, o + 4 * k, uint32_t(ints[k]), 4);
        put(b, o + 32, bits(m.lengthM), 8);
        for (int k = 0; k < 6; ++k) { put(b, o + 40 + 8 * k, bits(m.endI[k]), 8); put(b, o + 88 + 8 * k, bits(m.endJ[k]), 8); }
        put(b, o + 136, bits(1. / 3), 8); put(b, o + 144, bits(.5), 8);
        b[o + 152] = 5; b[o + 153] = 2; b[o + 154] = 3;
    }
    return b;
}
static Bytes facets(const Fixture& f) {
    Bytes b(560);
    for (int e = 0; e < 2; ++e) {
        const auto& v = f.facets[e]; size_t o = 280 * e;
        const int32_t ints[] = {v.id, e, e ? 1 : 0, e ? 2 : 1, 5};
        for (int k = 0; k < 5; ++k) put(b, o + 4 * k, uint32_t(ints[k]), 4);
        put(b, o + 24, bits(.2), 8);
        for (int k = 0; k < 4; ++k) for (int j = 0; j < 3; ++j) put(b, o + 32 + (3 * k + j) * 8, bits(v.corners[k][j]), 8);
        for (int k = 0; k < 3; ++k) {
            put(b, o + 128 + 8 * k, bits(v.ex[k]), 8); put(b, o + 152 + 8 * k, bits(v.ey[k]), 8);
            put(b, o + 176 + 8 * k, bits(v.n[k]), 8); put(b, o + 200 + 8 * k, bits(v.N[k]), 8);
            put(b, o + 224 + 8 * k, bits(v.M[k]), 8);
        }
        put(b, o + 248, bits(7.5), 8); put(b, o + 256, bits(-8.5), 8); put(b, o + 264, bits(1. / 3), 8);
        b[o + 272] = 3; b[o + 273] = 6;
    }
    return b;
}
static Bytes scalars(const void* data, size_t count, bool f32) {
    Bytes b(count * (f32 ? 4 : 8));
    for (size_t k = 0; k < count; ++k) {
        double v; std::memcpy(&v, static_cast<const uint8_t*>(data) + 8 * k, 8);
        put(b, k * (f32 ? 4 : 8), f32 ? expected32(v) : bits(v), f32 ? 4 : 8);
    }
    return b;
}
static void layout() {
    Fixture f;
    for (unsigned mask : {0u, 1u, 2u, 3u, 4u, 7u, 15u}) for (unsigned storage : {0u, 1u}) {
        std::string p = "layout-" + std::to_string(mask) + "-" + std::to_string(storage) + "-";
        bsi::ReplyBuilder w(2, mask, uint8_t(storage)); f.write(w); std::string why;
        check(p + "finalize", w.finalizeSolve(why)); auto s = sections(w);
        Bytes blocks(48); put(blocks, 0, bits(1. / 3), 8); put(blocks, 24, bits(1. + 0x1p-30), 8); blocks[42] = 1;
        check(p + "dc-f64", s["blocks"] == blocks);
        std::vector<std::string> names = {"blocks", "equilibrium", "quality", "buckling"};
        if (mask & 1) {
            names.insert(names.end(), {"members", "memberBlocks"});
            check(p + "members", s["members"] == members(f)); check(p + "member-index", s["memberBlocks"] == coordinates(f));
        }
        if (mask & 2) {
            const std::string n = storage ? "stations:f32" : "stations"; names.push_back(n);
            check(p + "stations", s[n] == scalars(f.stations, 33, storage != 0));
        }
        if (mask & 4) {
            const std::string n = storage ? "facetSurfaces:f32" : "facetSurfaces";
            names.insert(names.end(), {"facets", n, "facetBlocks"});
            check(p + "facets", s["facets"] == facets(f)); check(p + "surfaces", s[n] == scalars(f.surfaces, 64, storage != 0));
            check(p + "facet-index", s.count("facetBlocks") && s["facetBlocks"] == coordinates(f));
        }
        if (mask & 8) names.push_back("attrsEcho");
        std::vector<std::string> actual; bool ranges = true; uint64_t at = 0;
        for (const auto& sec : w.sections()) {
            actual.push_back(sec.name); const auto rec = bsi::schema::embedded().recordBytes(sec.name);
            ranges = ranges && sec.offset == at && rec > 0 && sec.bytes == sec.count * uint64_t(rec); at += sec.bytes;
        }
        check(p + "independent-includes", actual == names);
        check(p + "record-ranges", ranges && at == w.payload().size());
        for (auto byte : w.payload()) { digest ^= byte; digest *= 1099511628211ULL; }
    }
}
static void invalid() {
    const double nan = std::numeric_limits<double>::quiet_NaN(), inf = std::numeric_limits<double>::infinity();
    for (int storage = 0; storage < 2; ++storage) for (int kind = 0; kind < 4; ++kind) {
        int count = kind == 0 ? 11 : kind == 1 ? 15 : kind == 2 ? 31 : 4;
        for (int field = 0; field < count; ++field) for (int poison = 0; poison < 3; ++poison) {
            Fixture f; double v = poison == 0 ? nan : poison == 1 ? inf : -inf;
            void* obj = kind == 0 ? static_cast<void*>(&f.stations[2]) : kind == 1 ? static_cast<void*>(&f.members[1]) :
                kind == 2 ? static_cast<void*>(&f.facets[1]) : static_cast<void*>(&f.surfaces[15]);
            size_t off = 8 * field + (kind == 1 ? 32 : kind == 2 ? 24 : 0);
            std::memcpy(static_cast<uint8_t*>(obj) + off, &v, 8);
            bsi::ReplyBuilder w(2, 7, uint8_t(storage)); f.write(w); std::string why;
            bool ok = w.finalizeSolve(why), allowed = kind == 0 && field >= 9 && poison == 0;
            if (allowed && ok) {
                auto bytes = sections(w).at(storage ? "stations:f32" : "stations");
                double read;
                if (storage) { float fv; std::memcpy(&fv, bytes.data() + 4 * (22 + field), 4); read = fv; }
                else std::memcpy(&read, bytes.data() + 8 * (22 + field), 8);
                ok = std::isnan(read);
            }
            check("finite-" + std::to_string(storage) + "-" + std::to_string(kind) + "-" + std::to_string(field) + "-" + std::to_string(poison),
                allowed ? ok : !ok && !why.empty() && w.payload().empty() && w.sections().empty());
        }
    }
    for (int kind : {0, 1}) for (int field = 0; field < (kind ? 4 : 11); ++field) for (int sign : {-1, 1}) {
        Fixture f; double v = sign * 0x1p128;
        void* obj = kind ? static_cast<void*>(&f.surfaces[15]) : static_cast<void*>(&f.stations[2]);
        std::memcpy(static_cast<uint8_t*>(obj) + 8 * field, &v, 8);
        bsi::ReplyBuilder w(2, 7, 1); f.write(w); std::string why;
        check("overflow-" + std::to_string(kind) + "-" + std::to_string(field) + "-" + std::to_string(sign),
            !w.finalizeSolve(why) && w.payload().empty() && w.sections().empty());
    }
    Fixture f; f.stations[0].s = 1; f.stations[1].s = 0;
    bsi::ReplyBuilder badOrder(2, 2, 0); f.write(badOrder); std::string why;
    check("station-order", !badOrder.finalizeSolve(why) && badOrder.payload().empty());
    Fixture valid; bsi::ReplyBuilder repeated(2, 7, 0); valid.write(repeated);
    check("repeat-first", repeated.finalizeSolve(why)); repeated.blocks(nullptr, 0);
    check("repeat-failure-atomic", !repeated.finalizeSolve(why) && repeated.payload().empty() && repeated.sections().empty());
    for (int storage = 0; storage < 2; ++storage) {
        bsi::ReplyBuilder empty(0, 7, uint8_t(storage)); const double z[3] = {};
        empty.blocks(nullptr, 0); empty.equilibrium(z, z, 0); empty.quality(0, 0, 1, 0, 0); empty.diag(0, 0, 0, 0, 0, 0);
        check("empty-" + std::to_string(storage), empty.finalizeSolve(why) && sections(empty).count("facetBlocks") == 1);
    }
}
int main(int argc, char** argv) {
    if (argc == 2 && std::string(argv[1]) == "--legacy") {
        Fixture f;
        for (unsigned mask : {0u, 1u, 2u, 3u, 4u, 7u}) for (unsigned storage : {0u, 1u}) {
            bsi::ReplyBuilder w(2, mask, uint8_t(storage)); f.write(w); std::string why;
            if (!w.finalizeSolve(why)) return 2;
            for (auto& s : w.sections()) if (s.name != "facetBlocks") {
                std::printf("%u/%u/%s/%llu: ", mask, storage, s.name.c_str(), static_cast<unsigned long long>(s.count));
                for (uint64_t k = s.offset; k < s.offset + s.bytes; ++k) std::printf("%02x", w.payload()[k]);
                std::puts("");
            }
        }
        return 0;
    }
    check("schema-facet-blocks", bsi::schema::embedded().recordBytes("facetBlocks") == 12);
    layout(); invalid();
    std::printf("RECOVERY payload FNV=%016llx\n", static_cast<unsigned long long>(digest));
    std::printf("RECOVERY-SUITE checks=%d failures=%d\n", checks, failures);
    return failures ? 1 : 0;
}
