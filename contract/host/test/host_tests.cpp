// host_tests.cpp -- the host's own gate (contract artifact). Zero mechanics:
// every check here is about bytes, ordering, validation and the state machine.
// Prints one line per check in the tectonic gate style and ends with
//   HOST-SUITE ALL PASS (failures=0)
#include "../bsi_host.h"
#include "../bsi_arena.hpp"
#include "../bsi_base64.hpp"
#include "../bsi_canon.hpp"
#include "../bsi_frame.hpp"
#include "../bsi_json.hpp"
#include "../bsi_reply.hpp"
#include "../bsi_schema.hpp"
#include "../bsi_sha256.hpp"
#include "../bsi_vocab.hpp"
#include "../../bsi_capi.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

extern "C" const bsi_engine_vtable* bsi_engine_entry(uint32_t);

static int gFail = 0, gCount = 0;
static void chk(const char* id, const char* what, bool ok) {
    ++gCount; if (!ok) ++gFail;
    std::printf("%-8s %-72s -> %s\n", id, what, ok ? "PASS" : "**FAIL**");
}

using namespace bsi;

static const char* kVocab =
 R"({"version":1,"materials":[
   {"name":"steel","role":"member","model":"isotropic","E":2.0e11,"nu":0.3,"rho":7850,"allow":{"sigmaC":2.5e8,"sigmaT":2.5e8,"tau":1.45e8},"defaultSection":"steel_rect_200x400","x-acme":{"colour":"blue"}},
   {"name":"ground_rigid","role":"support","supportKind":"fixAll"},
   {"name":"decor","role":"nonstructural"}],
  "sections":[{"name":"steel_rect_200x400","kind":"rect","p":[0.2,0.4]}]})";

static std::string req(const char* method, const char* body, const char* id = "r1", long long rev = 7) {
    std::string s = "{\"bsi\":1,\"kind\":\"request\",\"id\":\"" + std::string(id) + "\",\"method\":\"" + method + "\",\"revision\":" + std::to_string(rev);
    if (body) s += std::string(",\"body\":") + body;
    return s + "}";
}
static std::string helloBody(const char* sha = nullptr) {
    return std::string("{\"bsi\":1,\"client\":\"host_tests/0\",\"contractSha256\":\"") + (sha ? sha : contractSha256()) + "\"}";
}
static std::string field(const std::string& header, const char* key) {
    json::Value v; if (!json::parse(header, v)) return "<unparseable>";
    const json::Value* f = v.find(key); if (!f) return "<absent>";
    if (f->isStr()) return f->str;
    if (f->isNum()) return f->isInt ? std::to_string(f->i64) : std::to_string(f->num);
    if (f->isBool()) return f->b ? "true" : "false";
    std::string s; json::serialize(*f, s); return s;
}
static std::vector<bsi_block> world5() {
    std::vector<bsi_block> w;
    auto add = [&](int x, int y, int z, int mat) { bsi_block b{}; b.x = x; b.y = y; b.z = z; b.mat = mat; b.sect = -1; b.axis = 0; b.fill = 1; b.strength = 1; w.push_back(b); };
    add(-1, 0, 0, 1);
    for (int x = 0; x < 5; ++x) add(x, 0, 0, 0);
    add(9, 0, 0, 2);       // a decor block -> NON_STRUCTURAL (canonical: after x=4)
    return w;
}

int main() {
    // ---- H1 record layouts are the contract's byte sizes ----
    chk("H1-a", "sizeof(bsi_block)==40", sizeof(bsi_block) == 40);
    chk("H1-b", "sizeof(bsi_attr)==16 / bsi_edit==41 / bsi_load==64", sizeof(bsi_attr) == 16 && sizeof(bsi_edit) == 41 && sizeof(bsi_load) == 64);
    chk("H1-c", "sizeof(bsi_block_result)==24 / station 88 / member 160 / facet 280 / surface 32", sizeof(bsi_block_result) == 24 && sizeof(bsi_station) == 88 && sizeof(bsi_member_result) == 160 && sizeof(bsi_facet_result) == 280 && sizeof(bsi_surface) == 32);
    chk("H1-d", "arena header 128 B", sizeof(arena::Header) == 128);
    const auto& sch = schema::embedded();
    chk("H1-e", "schema x-records agree with the structs", sch.recordBytes("blocks") == 24 && sch.recordBytes("members") == 160 && sch.recordBytes("stations") == 88 && sch.recordBytes("stations:f32") == 44 && sch.recordBytes("facets") == 280 && sch.recordBytes("facetSurfaces:f32") == 128 && sch.recordBytes("equilibrium") == 56 && sch.recordBytes("quality") == 16 && sch.recordBytes("buckling") == 16);

    // ---- H2 JSON ----
    { json::Value v; bool ok = json::parse("{\"a\":[1,2.5,\"x\\u00e9\",true,null],\"b\":{\"c\":-9223372036854775808}}", v);
      chk("H2-a", "strict parse keeps insertion order and exact int64", ok && v.obj[0].first == "a" && v.obj[1].first == "b" && v.find("b")->find("c")->isInt && v.find("b")->find("c")->i64 == INT64_MIN);
      chk("H2-b", "\\u escape becomes UTF-8", v.find("a")->arr[2].str == "x\xc3\xa9");
      json::Value bad; chk("H2-c", "trailing garbage / bad escape / leading zero refused", !json::parse("{} x", bad) && !json::parse("\"\\x\"", bad) && !json::parse("[01]", bad));
      std::string deep(100, '['); chk("H2-d", "nesting deeper than 64 refused, no crash", !json::parse(deep, bad));
      json::Writer w; w.beginObj(); w.kv("k", "v\"q"); w.key("n"); w.val(3LL); w.endObj();
      chk("H2-e", "writer emits in call order with escaping", w.str() == "{\"k\":\"v\\\"q\",\"n\":3}");
    }

    // ---- H3 schema ----
    { json::Value v; json::parse("{\"selfWeight\":true,\"gravityy\":[0,-9.81,0]}", v);
      schema::Result r = sch.validate("solve.request.body", v);
      chk("H3-a", "unknown non-x- key is a problem flagged unknownKey", !r.ok && !r.problems.empty() && r.problems[0].unknownKey);
      json::parse("{\"selfWeight\":true,\"x-acme\":{\"foo\":1}}", v);
      r = sch.validate("solve.request.body", v);
      chk("H3-b", "x- key ignored and counted", r.ok && r.ignoredExtensions == 1);
      json::parse("{\"precision\":{\"tier\":\"nope\"}}", v);
      chk("H3-c", "enum via $ref x-enums enforced", !sch.validate("solve.request.body", v).ok);
      auto order = sch.propertyOrder("solve.response");
      chk("H3-d", "solve.response property order starts bsi,kind,id,method,revision,status,diag", order.size() >= 7 && order[0] == "bsi" && order[5] == "status" && order[6] == "diag");
      chk("H3-e", "x-capabilities / x-errors / x-verbs membership", sch.isCapability("bsi.core") && !sch.isCapability("bsi.magic") && sch.isError("BSI_VERSION") && sch.isVerb("bsi.solve") && !sch.isVerb("solve"));
    }

    // ---- H4 canonical order ----
    { std::vector<bsi_block> w = world5();
      chk("H4-a", "world5 is canonical as built", canon::isCanonical(w.data(), (uint32_t)w.size()));
      std::swap(w[0], w[3]);
      chk("H4-b", "swapped world detected as non-canonical", !canon::isCanonical(w.data(), (uint32_t)w.size()));
      canon::Verdict v = canon::canonicalise(w);
      chk("H4-c", "canonicalise sorts (x,y,z) ascending", v.ok && canon::isCanonical(w.data(), (uint32_t)w.size()) && w[0].x == -1);
      w.push_back(w[2]);
      v = canon::canonicalise(w);
      chk("H4-d", "duplicate refused with the cell named", !v.ok && v.code == "PROTOCOL_ERROR" && v.hasAt && v.at[0] == w[2].x);
      bsi_block bad = w[1]; bad.axis = 3;
      chk("H4-e", "axis 3 refused at the cell", !canon::checkBlock(bad, 3, 1).ok);
      bad = w[1]; bad.fill = 0;
      chk("H4-f", "fill 0 refused", !canon::checkBlock(bad, 3, 1).ok);
      std::vector<bsi_load> L(2); std::memset(L.data(), 0, sizeof(bsi_load) * 2);
      L[0].x = 1; L[1].x = 1; L[0].f[1] = -2; L[1].f[1] = -1;
      canon::canonicaliseLoads(L);
      chk("H4-g", "same-cell loads ordered by memcmp of the raw record", std::memcmp(&L[0], &L[1], sizeof(bsi_load)) < 0);
      bsi_load m{}; m.m[2] = 1.0; chk("H4-h", "non-zero moment -> LOAD_UNSUPPORTED", canon::checkLoad(m).code == "LOAD_UNSUPPORTED");
    }

    // ---- H5 vocab typing ----
    { json::Value v; json::parse(kVocab, v); VocabStore store; VocabError e;
      bool ok = buildVocab(v, nullptr, store, e);
      chk("H5-a", "vocab builds; ids are declaration order", ok && store.materialId("steel") == 0 && store.materialId("ground_rigid") == 1 && store.sectionId("steel_rect_200x400") == 0);
      chk("H5-b", "G derived once from E,nu", ok && std::fabs(store.materials[0].G[0] - 2.0e11 / (2.0 * 1.3)) < 1);
      chk("H5-c", "asymmetric allow triple typed; x- extension counted", ok && store.materials[0].sigmaAllowC == 2.5e8 && store.ignoredExtensions == 1);
      chk("H5-d", "support needs no mechanical fields", ok && store.materials[1].role == BSI_ROLE_SUPPORT && store.materials[1].supportKind == BSI_SUPPORT_FIXALL);
      json::Value bad; json::parse("{\"version\":1,\"materials\":[{\"name\":\"m\",\"role\":\"member\",\"E\":1,\"rho\":1}]}", bad);
      chk("H5-e", "member without nu/G refused as VOCAB_INVALID", !buildVocab(bad, nullptr, store, e) && e.code == "VOCAB_INVALID");
      json::parse("{\"version\":1,\"materials\":[{\"name\":\"j\",\"role\":\"member\",\"model\":\"x-acme:jelly\",\"E\":1,\"nu\":0.3,\"rho\":1,\"allow\":{\"sigma\":1}}]}", bad);
      std::vector<std::string> caps;
      chk("H5-f", "x-vendor model without the capability -> UNSUPPORTED (no downgrade)", !buildVocab(bad, &caps, store, e) && e.code == "UNSUPPORTED");
    }

    // ---- H6 session with the stub: state machine ----
    Engine eng; std::string err;
    chk("H6-a", "stub engine adopted from its entry point", loadEngineEntry(&bsi_engine_entry, eng, err) && eng.name == "bsi-stub");
    HostOptions opts;
    {
        Session s(eng, opts); Reply r;
        s.handle(req("bsi.solve", "{}"), nullptr, 0, r);
        chk("H6-b", "verb before hello -> EXPECTED_HELLO", r.error && field(r.header, "code") == "EXPECTED_HELLO");
        s.handle(req("bsi.hello", helloBody("0000000000000000000000000000000000000000000000000000000000000000").c_str()), nullptr, 0, r);
        chk("H6-c", "hello with a foreign contract hash -> BSI_VERSION", r.error && field(r.header, "code") == "BSI_VERSION");
        s.handle(req("bsi.hello", helloBody().c_str()), nullptr, 0, r);
        chk("H6-d", "session poisoned after BSI_VERSION", r.error && field(r.header, "code") == "BSI_VERSION" && s.poisoned());
    }
    std::string solveHeader, solvePayloadSha;
    {
        Session s(eng, opts); Reply r;
        s.handle(req("bsi.nope", "{}"), nullptr, 0, r);
        chk("H6-e", "unknown verb -> UNKNOWN_METHOD (before hello check? no: hello first)", r.error && (field(r.header, "code") == "UNKNOWN_METHOD"));
        s.handle(req("bsi.hello", helloBody().c_str()), nullptr, 0, r);
        chk("H6-f", "hello ok; capabilities echo the stub's declared subset; transports listed", !r.error && field(r.header, "status") == "ok" && field(r.header, "contractSha256") == contractSha256() && field(r.header, "capabilities").find("x-bsi.stub") != std::string::npos && field(r.header, "transports") == "[\"frame\",\"arena\",\"stdio-b64\"]");
        s.handle(req("bsi.world.declare", "{\"blocks\":1}"), nullptr, 0, r);
        chk("H6-g", "world before vocab -> VOCAB_INVALID", r.error && field(r.header, "code") == "VOCAB_INVALID");
        s.handle(req("bsi.vocab.declare", kVocab), nullptr, 0, r);
        chk("H6-h", "vocab.declare ok with id tables", !r.error && field(r.header, "materials").find("\"id\":0,\"name\":\"steel\"") != std::string::npos);
        s.handle(req("bsi.vocab.declare", kVocab), nullptr, 0, r);
        chk("H6-i", "second vocab.declare -> VOCAB_ALREADY_DECLARED", r.error && field(r.header, "code") == "VOCAB_ALREADY_DECLARED");
        s.handle(req("bsi.solve", "{}"), nullptr, 0, r);
        chk("H6-j", "solve before world -> NO_WORLD", r.error && field(r.header, "code") == "NO_WORLD");
        std::vector<bsi_block> w = world5();
        bsi_block bad = w[1]; bad.axis = 3;
        s.handle(req("bsi.world.declare", "{\"blocks\":1}"), (const uint8_t*)&bad, sizeof bad, r);
        chk("H6-k", "axis out of range -> PROTOCOL_ERROR at [x,y,z]", r.error && field(r.header, "code") == "PROTOCOL_ERROR" && field(r.header, "at") == "[0,0,0]");
        s.handle(req("bsi.world.declare", "{\"blocks\":7}"), (const uint8_t*)w.data(), sizeof(bsi_block) * 6, r);
        chk("H6-l", "payload length mismatch -> PROTOCOL_ERROR", r.error && field(r.header, "code") == "PROTOCOL_ERROR");
        std::swap(w[0], w[3]);       // non-canonical input: host sorts
        s.handle(req("bsi.world.declare", "{\"blocks\":7}"), (const uint8_t*)w.data(), sizeof(bsi_block) * 7, r);
        chk("H6-m", "world.declare ok (host canonicalises) with diag", !r.error && field(r.header, "status") == "ok" && field(r.header, "diag").find("\"blocks\":7") != std::string::npos);
        s.handle(req("bsi.vocab.declare", kVocab), nullptr, 0, r);
        chk("H6-n", "vocab after world -> VOCAB_AFTER_WORLD", r.error && field(r.header, "code") == "VOCAB_AFTER_WORLD");
        s.handle(req("bsi.solve", "{\"selfWeight\":true,\"gravityy\":[0,-9.81,0]}"), nullptr, 0, r);
        chk("H6-o", "unknown non-x- key in solve -> PROTOCOL_ERROR", r.error && field(r.header, "code") == "PROTOCOL_ERROR");
        s.handle(req("bsi.solve", "{\"selfWeight\":true,\"x-acme\":{\"foo\":1},\"include\":[\"members\"]}"), nullptr, 0, r);
        chk("H6-p", "solve ok; x- counted (vocab 1 + request 1 = 2)", !r.error && field(r.header, "status") == "ok" && field(r.header, "diag").find("\"ignoredExtensions\":2") != std::string::npos);
        json::Value hv; json::parse(r.header, hv);
        std::vector<std::string> keys; for (const auto& kv : hv.obj) keys.push_back(kv.first);
        auto order = sch.propertyOrder("solve.response");
        chk("H6-q", "solve.response keys are exactly in schema order", keys == order);
        chk("H6-r", "blocks section count == declared blocks; sections contiguous", field(r.header, "sections").find("\"name\":\"blocks\",\"offset\":0,\"bytes\":168,\"count\":7") != std::string::npos);
        chk("H6-s", "NON_STRUCTURAL block listed once under unassigned", field(r.header, "unassigned") == "[{\"why\":\"NON_STRUCTURAL\",\"island\":-1,\"blocks\":[[9,0,0]]}]");
        chk("H6-t", "schema validates the response header", sch.validate("solve.response", hv).ok);
        solveHeader = r.header; solvePayloadSha = sha256::hex(r.payload.data(), r.payload.size());
        s.handle(req("bsi.solve", "{\"include\":[\"stations\"]}"), nullptr, 0, r);
        chk("H6-u", "include stations without the capability -> UNSUPPORTED (gate before the engine)", r.error && field(r.header, "code") == "UNSUPPORTED");
        s.handle(req("bsi.solve", "{\"precision\":{\"tier\":\"display\"}}"), nullptr, 0, r);
        chk("H6-v", "display tier without the capability -> UNSUPPORTED", r.error && field(r.header, "code") == "UNSUPPORTED");
        bsi_load l{}; l.x = -1; l.y = 0; l.z = 0; l.f[1] = -1000;
        s.handle(req("bsi.solve", "{\"loads\":1}"), (const uint8_t*)&l, sizeof l, r);
        chk("H6-w", "load on the ground block -> LOAD_TARGET at [-1,0,0] (engine-side, host forwards)", r.error && field(r.header, "code") == "LOAD_TARGET" && field(r.header, "at") == "[-1,0,0]");
        s.handle(req("bsi.cancel", "{\"targetId\":\"r9\"}"), nullptr, 0, r);
        chk("H6-x", "cancel answers with targetId", !r.error && field(r.header, "targetId") == "r9");
        s.handle(req("bsi.solve", "{}", "r2", 8), nullptr, 0, r);
        chk("H6-y", "id and revision echoed", field(r.header, "id") == "r2" && field(r.header, "revision") == "8");
        s.handle("{\"bsi\":1,\"kind\":\"request\",\"id\":\"r3\",\"method\":\"bsi.solve\",\"revision\":1,\"payloadSha256\":\"" + std::string(64, '0') + "\"}", nullptr, 0, r);
        chk("H6-z", "payloadSha256 mismatch -> PROTOCOL_ERROR", r.error && field(r.header, "code") == "PROTOCOL_ERROR");
    }

    // ---- H7 DET x3 and writer consistency (mutations) ----
    {
        std::string h2, p2;
        for (int i = 0; i < 3; ++i) {
            Session s(eng, opts); Reply r;
            s.handle(req("bsi.hello", helloBody().c_str()), nullptr, 0, r);
            s.handle(req("bsi.vocab.declare", kVocab), nullptr, 0, r);
            std::vector<bsi_block> w = world5();
            s.handle(req("bsi.world.declare", "{\"blocks\":7}"), (const uint8_t*)w.data(), sizeof(bsi_block) * 7, r);
            s.handle(req("bsi.solve", "{\"selfWeight\":true,\"x-acme\":{\"foo\":1},\"include\":[\"members\"]}"), nullptr, 0, r);
            if (i == 0) { h2 = r.header; p2 = sha256::hex(r.payload.data(), r.payload.size()); }
            else chk(i == 1 ? "H7-a" : "H7-b", "fresh session repeats header and payload bitwise", r.header == h2 && sha256::hex(r.payload.data(), r.payload.size()) == p2);
        }
        chk("H7-c", "canonical vs non-canonical input give bitwise identical replies", h2 == solveHeader && p2 == solvePayloadSha);
        const char* muts[] = {"short_blocks", "bad_owner", "dup_member", "no_equilibrium"};
        const char* ids[] = {"H7-d", "H7-e", "H7-f", "H7-g"};
        for (int k = 0; k < 4; ++k) {
            setenv("BSI_STUB_MUTATE", muts[k], 1);
            Session s(eng, opts); Reply r;
            s.handle(req("bsi.hello", helloBody().c_str()), nullptr, 0, r);
            s.handle(req("bsi.vocab.declare", kVocab), nullptr, 0, r);
            std::vector<bsi_block> w = world5();
            s.handle(req("bsi.world.declare", "{\"blocks\":7}"), (const uint8_t*)w.data(), sizeof(bsi_block) * 7, r);
            s.handle(req("bsi.solve", "{\"include\":[\"members\"]}"), nullptr, 0, r);
            chk(ids[k], (std::string("engine mutation ") + muts[k] + " -> INTERNAL, never a silent reply").c_str(), r.error && field(r.header, "code") == "INTERNAL");
        }
        unsetenv("BSI_STUB_MUTATE");
    }

    // ---- H8 frame / base64 / sha256 / arena ----
    {
        std::vector<uint8_t> pl = {1, 2, 3, 4, 5};
        auto f = frame::encode(frame::kFlagEndOfResponse | frame::kFlagHasPayload, "{\"a\":1}", pl.data(), pl.size());
        frame::View v;
        chk("H8-a", "frame round trip", frame::decode(f.data(), f.size(), v) && v.headerStr() == "{\"a\":1}" && v.payloadLen == 5 && v.payload[4] == 5);
        f[0] = 'X';
        chk("H8-b", "bad magic refused", !frame::decode(f.data(), f.size(), v));
        f[0] = 'F'; f.push_back(0);
        chk("H8-c", "length mismatch refused", !frame::decode(f.data(), f.size(), v));
        std::vector<uint8_t> dec;
        chk("H8-d", "base64 round trip + padding rejects", b64::decode(b64::encode(pl.data(), pl.size()), dec) && dec == pl && b64::decode("", dec) && !b64::decode("A===", dec) && !b64::decode("AAA", dec));
        chk("H8-e", "sha256(\"abc\") known vector", sha256::hex((const uint8_t*)"abc", 3) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        arena::Header h{}; h.magic = arena::kMagic; h.version = 1; h.capacity = 4096;
        h.worldOff = 128; h.worldLen = 80; h.loadsOff = 256; h.loadsLen = 64; h.reqOff = 512; h.reqLen = 10; h.replyOff = 1024; h.replyLen = 0;
        std::string why;
        chk("H8-f", "valid arena header accepted", arena::validate(h, 4096, why));
        h.worldLen = 41; chk("H8-g", "world length not a multiple of 40 refused", !arena::validate(h, 4096, why)); h.worldLen = 80;
        h.loadsOff = 200; chk("H8-h", "overlapping regions refused", !arena::validate(h, 4096, why)); h.loadsOff = 256;
        h.capacity = 4095; chk("H8-i", "capacity != mapped size refused", !arena::validate(h, 4096, why));
    }

    // ---- H9 the in-process C ABI carries the same bytes as the session ----
    {
        chk("H9-a", "bsi_capi_abi_version()==1", bsi_capi_abi_version() == BSI_CAPI_ABI);
        void* h = bsi_capi_open("{}");
        chk("H9-b", "bsi_capi_open returns a handle", h != nullptr);
        auto call = [&](const std::string& header, const std::vector<uint8_t>& payload, Reply& out) -> int {
            auto f = frame::encode(0, header, payload.data(), payload.size());
            std::vector<uint8_t> buf(64);
            size_t len = 0, need = 0;
            int rc = bsi_capi_call(h, f.data(), f.size(), buf.data(), buf.size(), &len, &need);
            if (rc == BSI_CAPI_NEED_BIGGER) { buf.resize(need); rc = bsi_capi_call(h, f.data(), f.size(), buf.data(), buf.size(), &len, &need); }
            if (rc != BSI_CAPI_OK) return rc;
            frame::View v; if (!frame::decode(buf.data(), len, v)) return 99;
            out.header = v.headerStr(); out.payload.assign(v.payload, v.payload + v.payloadLen); out.error = (v.flags & frame::kFlagHasPayload) == 0 && out.header.find("\"kind\":\"error\"") != std::string::npos;
            return 0;
        };
        Reply r;
        chk("H9-c", "NEED_BIGGER then OK on retry (request not consumed)", call(req("bsi.hello", helloBody().c_str()), {}, r) == 0 && field(r.header, "status") == "ok");
        call(req("bsi.vocab.declare", kVocab), {}, r);
        std::vector<bsi_block> w = world5();
        std::vector<uint8_t> wb((const uint8_t*)w.data(), (const uint8_t*)w.data() + sizeof(bsi_block) * 7);
        call(req("bsi.world.declare", "{\"blocks\":7}"), wb, r);
        call(req("bsi.solve", "{\"selfWeight\":true,\"x-acme\":{\"foo\":1},\"include\":[\"members\"]}"), {}, r);
        chk("H9-d", "capi solve reply == session solve reply, header text and payload bytes", r.header == solveHeader && sha256::hex(r.payload.data(), r.payload.size()) == solvePayloadSha);
        uint8_t junk[4] = {1, 2, 3, 4};
        chk("H9-e", "malformed frame -> BSI_CAPI_PROTOCOL with last_error", bsi_capi_call(h, junk, 4, nullptr, 0, nullptr, nullptr) == BSI_CAPI_PROTOCOL && bsi_capi_last_error(h) != nullptr);
        bsi_capi_close(h);
        chk("H9-f", "NULL handle -> BSI_CAPI_INVALID", bsi_capi_call(nullptr, junk, 4, nullptr, 0, nullptr, nullptr) == BSI_CAPI_INVALID);
    }

    // ---- H10 contract addition batch #1 (BSI_ADD1): the three holes a probe walked
    //      through, and the two directions bit2 can lie in ----
    {
        // A session driven through the C ABI, so the open-options path is the one
        // under test rather than a hand-built HostOptions.
        auto session = [&](const char* opts, Reply& out, const char* solveBody) -> void {
            void* h = bsi_capi_open(opts);
            if (!h) { out.header = "<open refused>"; return; }
            auto call = [&](const std::string& header, const std::vector<uint8_t>& payload) {
                auto f = frame::encode(0, header, payload.data(), payload.size());
                std::vector<uint8_t> buf(1 << 16);
                size_t len = 0, need = 0;
                int rc = bsi_capi_call(h, f.data(), f.size(), buf.data(), buf.size(), &len, &need);
                if (rc == BSI_CAPI_NEED_BIGGER) { buf.resize(need); rc = bsi_capi_call(h, f.data(), f.size(), buf.data(), buf.size(), &len, &need); }
                if (rc != BSI_CAPI_OK) { out.header = "<call failed>"; return; }
                frame::View v; frame::decode(buf.data(), len, v);
                out.header = v.headerStr();
                out.payload.assign(v.payload, v.payload + v.payloadLen);
            };
            call(req("bsi.hello", helloBody().c_str()), {});
            call(req("bsi.vocab.declare", kVocab), {});
            std::vector<bsi_block> w = world5();
            call(req("bsi.world.declare", "{\"blocks\":7}"), std::vector<uint8_t>((const uint8_t*)w.data(), (const uint8_t*)w.data() + sizeof(bsi_block) * 7));
            call(req("bsi.solve", solveBody), {});
            bsi_capi_close(h);
        };
        Reply r;

        // G-E: open options are enforced, not assumed. Every one of these was
        // accepted before the batch, including the key nobody has ever defined.
        void* h1 = bsi_capi_open("{\"log\":0,\"numThreads\":4,\"probe\":false,\"x-acme\":{\"any\":1}}");
        chk("H10-a", "open: every documented key plus an x- extension is accepted", h1 != nullptr);
        if (h1) bsi_capi_close(h1);
        void* h2 = bsi_capi_open("{\"totallyBogusKey\":123}");
        const char* e2 = bsi_capi_last_error(nullptr);
        chk("H10-b", "open: unknown non-x- key -> NULL, and last_error(NULL) names the key",
            h2 == nullptr && e2 && std::strstr(e2, "totallyBogusKey") != nullptr);
        if (h2) bsi_capi_close(h2);
        void* h3 = bsi_capi_open("{\"numThreads\":0}");
        chk("H10-c", "open: numThreads out of 1..256 -> NULL", h3 == nullptr);
        if (h3) bsi_capi_close(h3);
        void* h4 = bsi_capi_open("{\"log\":\"loud\"}");
        chk("H10-d", "open: wrong type -> NULL", h4 == nullptr);
        if (h4) bsi_capi_close(h4);

        // G-C: an engine that cannot honour a deadline must refuse it. Before the
        // batch this answered ok with quality.timedOut = 0.
        session("{}", r, "{\"selfWeight\":true,\"precision\":{\"maxTimeMs\":1}}");
        chk("H10-e", "solve: maxTimeMs without bsi.precision.timeout -> UNSUPPORTED",
            field(r.header, "code") == "UNSUPPORTED");

        // G-D: 0 and 99999 were both accepted; "let the engine choose" is an
        // omitted key, never a zero.
        session("{}", r, "{\"selfWeight\":true,\"numThreads\":0}");
        chk("H10-f", "solve: numThreads 0 -> PROTOCOL_ERROR (not clamped)", field(r.header, "code") == "PROTOCOL_ERROR");
        session("{}", r, "{\"selfWeight\":true,\"numThreads\":99999}");
        chk("H10-g", "solve: numThreads 99999 -> PROTOCOL_ERROR (not clamped)", field(r.header, "code") == "PROTOCOL_ERROR");
        session("{}", r, "{\"selfWeight\":true,\"numThreads\":4}");
        chk("H10-h", "solve: numThreads 4 -> ok", field(r.header, "status") == "ok");

        // G-A: bit2 has to agree with the island's own record in BOTH directions,
        // and must not appear at all on a cell that owns no element.
        struct Esc { const char* mut; const char* id; const char* what; };
        const Esc escapes[] = {
            {"bit2_orphan",  "H10-i", "bit2 set with no buckling record -> INTERNAL"},
            {"bit2_lie",     "H10-j", "bit2 set while the record says disabled -> INTERNAL"},
            {"bit2_missing", "H10-k", "island computed with factor 0.5 but blocks unflagged -> INTERNAL"},
            {"bit2_ground",  "H10-l", "bit2 on a cell that owns no element -> INTERNAL"},
        };
        for (const Esc& esc : escapes) {
            setenv("BSI_STUB_MUTATE", esc.mut, 1);
            session("{}", r, "{\"selfWeight\":true,\"include\":[\"members\"]}");
            chk(esc.id, esc.what, field(r.header, "code") == "INTERNAL");
        }
        unsetenv("BSI_STUB_MUTATE");
        session("{}", r, "{\"selfWeight\":true,\"include\":[\"members\"]}");
        chk("H10-m", "and with no escape the same solve is ok (the legs bite the defect, not the fixture)",
            field(r.header, "status") == "ok");
    }

    std::printf("checks=%d\n", gCount);
    if (gFail == 0) std::printf("HOST-SUITE ALL PASS (failures=0)\n");
    else std::printf("HOST-SUITE **FAIL** (failures=%d)\n", gFail);
    return gFail == 0 ? 0 : 1;
}
