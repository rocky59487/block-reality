// bsi_session.cpp -- the BSI protocol state machine (host-internal).
//   hello -> vocab.declare -> world.declare -> solve*  (edit / cancel / query)
// Every rule here is a line in BSI.md Part A/B or Part G; the comments name it.
#include "bsi_host.h"
#include "bsi_canon.hpp"
#include "bsi_reply.hpp"
#include "bsi_schema.hpp"
#include "bsi_sha256.hpp"
#include "bsi_vocab.hpp"
#include <algorithm>
#include <array>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <map>
#include <set>
#include <thread>

namespace bsi {

namespace {

struct Request {
    json::Value hdr;
    std::string id, method;
    long long revision = 0;
    const json::Value* body = nullptr;
    int ignoredExt = 0;
};

const std::set<std::string> kTopLevelKeys = {"bsi", "kind", "id", "method", "revision", "body", "payloadBytes", "payloadSha256", "payloadB64"};

}  // namespace

class SessionImpl {
public:
    SessionImpl(Engine& e, const HostOptions& o) : engine_(e), opts_(o) {
        services_.logLevel = o.logLevel;
        hostHandle_.s = &services_;
        inst_ = engine_.vt->open(&hostHandle_);
        // effective capabilities: declared ∩ schema (x-vendor allowed), plus assumed ones in probe mode
        const auto& sch = schema::embedded();
        for (const auto& c : engine_.capabilities) {
            if (sch.isCapability(c) || (c.size() > 2 && c[0] == 'x' && c[1] == '-')) caps_.insert(c);
            else logf(1, "ignoring undeclared-by-schema capability string: %s", c.c_str());
        }
        if (o.probe) for (const auto& c : o.assumedCaps) assumed_.insert(c);
    }
    ~SessionImpl() { if (inst_) engine_.vt->close(inst_); }

    bool poisoned() const { return poisoned_; }
    const HostOptions& options() const { return opts_; }
    const Engine& engine() const { return engine_; }

    void handle(const std::string& header, const uint8_t* payload, size_t payloadLen, Reply& out) {
        out = Reply{};
        Request rq;
        if (!json::parse(header, rq.hdr) || !rq.hdr.isObj()) { errorReply(out, "", "", 0, "PROTOCOL_ERROR", "header is not a JSON object"); return; }
        const auto& sch = schema::embedded();
        schema::Result base = sch.validate("requestBase", rq.hdr);
        // id/method/revision are needed to answer at all; fill what we can first
        if (const json::Value* v = rq.hdr.find("id")) if (v->isStr()) rq.id = v->str;
        if (const json::Value* v = rq.hdr.find("method")) if (v->isStr()) rq.method = v->str;
        if (const json::Value* v = rq.hdr.find("revision")) if (v->isInt) rq.revision = v->i64;
        if (!rq.method.empty() && !sch.isVerb(rq.method)) { errorReply(out, rq.id, rq.method, rq.revision, "UNKNOWN_METHOD", rq.method); return; }
        if (!base.ok) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", "request header: " + firstProblem(base)); return; }
        for (const auto& kv : rq.hdr.obj) {
            if (kTopLevelKeys.count(kv.first)) continue;
            if (kv.first.size() >= 2 && kv.first[0] == 'x' && kv.first[1] == '-') { ++rq.ignoredExt; continue; }
            errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", "unknown header key " + kv.first); return;
        }
        if (const json::Value* pb = rq.hdr.find("payloadBytes")) {
            if (pb->isInt && (size_t)pb->i64 != payloadLen) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", "payloadBytes does not match the payload"); return; }
        }
        if (const json::Value* ps = rq.hdr.find("payloadSha256")) {
            if (ps->isStr() && ps->str != sha256::hex(payload, payloadLen)) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", "payloadSha256 mismatch"); return; }
        }
        rq.body = rq.hdr.find("body");
        if (poisoned_) { errorReply(out, rq.id, rq.method, rq.revision, "BSI_VERSION", "session refused after a contract mismatch"); return; }
        if (!sch.isVerb(rq.method)) { errorReply(out, rq.id, rq.method, rq.revision, "UNKNOWN_METHOD", rq.method); return; }
        if (!helloSeen_ && rq.method != "bsi.hello") { errorReply(out, rq.id, rq.method, rq.revision, "EXPECTED_HELLO", "first request must be bsi.hello"); return; }
        if (rq.method == "bsi.hello") hello(rq, out);
        else if (rq.method == "bsi.vocab.declare") vocabDeclare(rq, out);
        else if (rq.method == "bsi.vocab.query") vocabQuery(rq, out);
        else if (rq.method == "bsi.world.declare") worldDeclare(rq, payload, payloadLen, out);
        else if (rq.method == "bsi.world.edit") worldEdit(rq, payload, payloadLen, out);
        else if (rq.method == "bsi.solve") solve(rq, payload, payloadLen, out);
        else if (rq.method == "bsi.cancel") cancel(rq, out);
        else errorReply(out, rq.id, rq.method, rq.revision, "UNKNOWN_METHOD", rq.method);
    }

private:
    Engine& engine_;
    HostOptions opts_;
    HostServices services_;
    bsi_host hostHandle_{};
    bsi_engine* inst_ = nullptr;
    std::set<std::string> caps_, assumed_;
    bool helloSeen_ = false, poisoned_ = false;
    VocabStore vocab_;
    bool vocabDeclared_ = false;
    bool worldDeclared_ = false;
    std::vector<bsi_block> world_;
    std::vector<bsi_attr> attrs_;
    int worldExt_ = 0;

    void logf(int level, const char* fmt, ...) {
        if (level > opts_.logLevel) return;
        va_list ap; va_start(ap, fmt); std::vfprintf(stderr, fmt, ap); va_end(ap); std::fputc('\n', stderr);
    }
    bool has(const std::string& cap) const { return caps_.count(cap) || (opts_.probe && assumed_.count(cap)); }
    static std::string firstProblem(const schema::Result& r) { return r.problems.empty() ? "invalid" : r.problems[0].path + ": " + r.problems[0].what; }

    void beginResponse(json::Writer& w, const Request& rq, const char* kind) {
        w.beginObj();
        w.kv("bsi", 1); w.kv("kind", kind); w.kv("id", rq.id); w.kv("method", rq.method); w.kv("revision", rq.revision);
    }
    void errorReply(Reply& out, const std::string& id, const std::string& method, long long revision, const std::string& code, const std::string& msg,
                    const int32_t* at = nullptr, uint64_t required = 0) {
        json::Writer w;
        w.beginObj();
        w.kv("bsi", 1); w.kv("kind", "error"); w.kv("id", id); w.kv("method", method); w.kv("revision", revision);
        w.kv("code", code); w.kv("message", msg);
        if (at) { w.key("at"); w.beginArr(); w.val((long long)at[0]); w.val((long long)at[1]); w.val((long long)at[2]); w.endArr(); }
        if (required) w.kv("required", (unsigned long long)required);
        w.endObj();
        out.header = w.take(); out.payload.clear(); out.error = true;
    }
    void errorFromBuilder(Reply& out, const Request& rq, int status, const ReplyBuilder& b) {
        const auto& sch = schema::embedded();
        std::string code = statusToken(status), msg;
        const int32_t* at = nullptr;
        if (b.hasError()) {
            if (sch.isError(b.errorCode())) code = b.errorCode();
            msg = b.errorMessage();
            if (b.errorHasAt()) at = b.errorAt();
        }
        errorReply(out, rq.id, rq.method, rq.revision, code, msg, at);
    }

    // ---- verbs -------------------------------------------------------------
    void hello(const Request& rq, Reply& out) {
        const auto& sch = schema::embedded();
        if (!rq.body) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", "hello needs a body"); return; }
        schema::Result r = sch.validate("hello.request.body", *rq.body);
        if (!r.ok) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", firstProblem(r)); return; }
        const json::Value* sha = rq.body->find("contractSha256");
        if (!sha || !sha->isStr() || sha->str != contractSha256()) {
            poisoned_ = true;
            errorReply(out, rq.id, rq.method, rq.revision, "BSI_VERSION", std::string("contract sha256 mismatch: host has ") + contractSha256());
            return;
        }
        helloSeen_ = true;
        json::Writer w;
        beginResponse(w, rq, "response");
        w.kv("status", "ok");
        w.kv("engine", engine_.name); w.kv("version", engine_.version); w.kv("buildSha", engine_.buildSha);
        w.kv("contractSha256", contractSha256());
        w.key("capabilities"); w.beginArr();
        std::vector<std::string> c(caps_.begin(), caps_.end());
        for (const auto& s : c) w.val(s);
        w.endArr();
        unsigned hc = std::thread::hardware_concurrency(); if (hc == 0) hc = 1;
        w.kv("threads", (unsigned long long)hc);
        w.key("arena"); w.beginObj(); w.kv("required", false); w.kv("minReplyBytes", 65536); w.endObj();
        w.key("precision"); w.beginObj();
        w.key("tiers"); w.beginArr(); w.val("commit"); if (has("bsi.precision.display")) w.val("display"); w.endArr();
        w.key("storage"); w.beginArr(); w.val("f64"); if (has("bsi.precision.f32")) w.val("f32"); w.endArr();
        w.kv("warmStart", has("bsi.precision.warmstart"));
        w.endObj();
        w.key("transports"); w.beginArr(); w.val("frame"); w.val("arena"); w.val("stdio-b64"); w.endArr();
        if (opts_.probe) { w.key("x-host"); w.beginObj(); w.kv("probe", true); w.key("assumedCaps"); w.beginArr(); for (const auto& s : assumed_) w.val(s); w.endArr(); w.endObj(); }
        w.endObj();
        out.header = w.take();
    }

    void vocabTables(json::Writer& w) {
        w.key("materials"); w.beginArr();
        for (size_t k = 0; k < vocab_.materialNames.size(); ++k) { w.beginObj(); w.kv("id", (long long)k); w.kv("name", vocab_.materialNames[k]); w.endObj(); }
        w.endArr();
        w.key("sections"); w.beginArr();
        for (size_t k = 0; k < vocab_.sectionNames.size(); ++k) { w.beginObj(); w.kv("id", (long long)k); w.kv("name", vocab_.sectionNames[k]); w.endObj(); }
        w.endArr();
        w.key("attrKeys"); w.beginArr();
        for (size_t k = 0; k < vocab_.attrKeys.size(); ++k) { w.beginObj(); w.kv("id", (long long)k); w.kv("name", vocab_.attrKeys[k]); w.endObj(); }
        w.endArr();
    }

    void vocabDeclare(const Request& rq, Reply& out) {
        const auto& sch = schema::embedded();
        if (worldDeclared_) { errorReply(out, rq.id, rq.method, rq.revision, "VOCAB_AFTER_WORLD", "vocabulary cannot change after world.declare"); return; }
        if (vocabDeclared_) { errorReply(out, rq.id, rq.method, rq.revision, "VOCAB_ALREADY_DECLARED", "vocabulary already declared"); return; }
        if (!rq.body) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", "vocab.declare needs a body"); return; }
        schema::Result r = sch.validate("vocab.declare.body", *rq.body);
        if (!r.ok) {
            bool unknown = false; for (const auto& p : r.problems) if (p.unknownKey) unknown = true;
            errorReply(out, rq.id, rq.method, rq.revision, unknown ? "PROTOCOL_ERROR" : "VOCAB_INVALID", firstProblem(r)); return;
        }
        VocabStore store; VocabError ve;
        std::vector<std::string> capList(caps_.begin(), caps_.end());
        for (const auto& a : assumed_) if (opts_.probe) capList.push_back(a);
        if (!buildVocab(*rq.body, &capList, store, ve)) { errorReply(out, rq.id, rq.method, rq.revision, ve.code, ve.message); return; }
        // orthotropic / composite / rope need capabilities (P6: no downgrade)
        for (size_t k = 0; k < store.materials.size(); ++k) {
            const bsi_material& m = store.materials[k];
            const char* need = nullptr;
            if (m.model == BSI_MODEL_ORTHOTROPIC) need = "bsi.material.orthotropic";
            else if (m.model == BSI_MODEL_COMPOSITE_RC) need = "bsi.material.composite";
            else if (m.model == BSI_MODEL_ROPE) need = "bsi.material.rope";
            if (need && !has(need)) { errorReply(out, rq.id, rq.method, rq.revision, "UNSUPPORTED", std::string("material ") + store.materialNames[k] + " needs capability " + need); return; }
        }
        for (size_t k = 0; k < store.sections.size(); ++k)
            if (store.sections[k].kind == BSI_SECT_CUSTOM && !has("bsi.section.custom")) { errorReply(out, rq.id, rq.method, rq.revision, "UNSUPPORTED", "custom section " + store.sectionNames[k] + " needs capability bsi.section.custom"); return; }
        vocab_ = std::move(store);
        bsi_vocab v = vocab_.view();
        int st = engine_.vt->vocab(inst_, &v);
        if (st != BSI_OK) { vocab_ = VocabStore{}; errorReply(out, rq.id, rq.method, rq.revision, statusToken(st), "engine refused the vocabulary"); return; }
        vocabDeclared_ = true;
        json::Writer w;
        beginResponse(w, rq, "response");
        w.kv("status", "ok"); w.kv("version", (long long)vocab_.version);
        vocabTables(w);
        w.endObj();
        out.header = w.take();
    }

    void vocabQuery(const Request& rq, Reply& out) {
        if (!vocabDeclared_) { errorReply(out, rq.id, rq.method, rq.revision, "VOCAB_INVALID", "no vocabulary declared"); return; }
        json::Writer w;
        beginResponse(w, rq, "response");
        w.kv("status", "ok"); w.kv("version", (long long)vocab_.version);
        vocabTables(w);
        w.endObj();
        out.header = w.take();
    }

    void writeDiag(json::Writer& w, const ReplyBuilder& b, uint32_t blocks, int ignoredExt) {
        w.key("diag"); w.beginObj();
        w.kv("blocks", (unsigned long long)blocks);
        w.kv("nodes", (unsigned long long)b.nodes()); w.kv("members", (unsigned long long)b.members()); w.kv("facets", (unsigned long long)b.facets());
        w.kv("islands", (unsigned long long)b.islands()); w.kv("singularIslands", (unsigned long long)b.singularIslands());
        w.kv("refusedBlocks", (unsigned long long)b.refusedBlocks()); w.kv("ignoredExtensions", (long long)ignoredExt);
        w.key("warnings"); w.beginArr();
        for (const auto& wc : b.warnings()) { w.beginObj(); w.kv("code", wc.code); w.kv("count", (unsigned long long)wc.count); w.endObj(); }
        w.endArr();
        w.endObj();
    }

    void worldDeclare(const Request& rq, const uint8_t* payload, size_t n, Reply& out) {
        const auto& sch = schema::embedded();
        if (!vocabDeclared_) { errorReply(out, rq.id, rq.method, rq.revision, "VOCAB_INVALID", "no vocabulary declared"); return; }
        json::Value emptyBody; emptyBody.t = json::Value::T::Obj;
        const json::Value& body = rq.body ? *rq.body : emptyBody;
        schema::Result r = sch.validate("world.declare.body", body);
        if (!r.ok) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", firstProblem(r)); return; }
        uint64_t B = 0, A = 0;
        if (const json::Value* v = body.find("blocks")) B = (uint64_t)v->i64;
        if (const json::Value* v = body.find("attrs")) A = (uint64_t)v->i64;
        if (!body.find("blocks")) B = n / sizeof(bsi_block);       // body absent: whole payload is blocks (Part G item 4 default)
        if (B * sizeof(bsi_block) + A * sizeof(bsi_attr) != n) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", "payload length does not match blocks*40 + attrs*16"); return; }
        if (B == 0) { errorReply(out, rq.id, rq.method, rq.revision, "EMPTY_WORLD", "no blocks"); return; }
        if (A > 0 && !has("bsi.block.attrs")) { errorReply(out, rq.id, rq.method, rq.revision, "UNSUPPORTED", "attrs need capability bsi.block.attrs"); return; }
        std::vector<bsi_block> blocks((size_t)B);
        std::memcpy(blocks.data(), payload, (size_t)B * sizeof(bsi_block));
        for (const bsi_block& b : blocks) {
            canon::Verdict v = canon::checkBlock(b, (uint32_t)vocab_.materials.size(), (uint32_t)vocab_.sections.size());
            if (!v.ok) { errorReply(out, rq.id, rq.method, rq.revision, v.code, v.message, v.hasAt ? v.at : nullptr); return; }
        }
        canon::Verdict dup;
        if (!canon::isCanonical(blocks.data(), (uint32_t)blocks.size(), &dup)) {
            if (!dup.ok) { errorReply(out, rq.id, rq.method, rq.revision, dup.code, dup.message, dup.at); return; }
            canon::Verdict c = canon::canonicalise(blocks);
            if (!c.ok) { errorReply(out, rq.id, rq.method, rq.revision, c.code, c.message, c.hasAt ? c.at : nullptr); return; }
        }
        std::vector<bsi_attr> attrs((size_t)A);
        if (A) std::memcpy(attrs.data(), payload + (size_t)B * sizeof(bsi_block), (size_t)A * sizeof(bsi_attr));
        for (const bsi_attr& a : attrs) {
            if (a.blockIndex >= B) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", "attr blockIndex out of range"); return; }
            if (a.key >= vocab_.attrKeys.size()) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", "attr key out of range"); return; }
            if (a.type > 2) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", "attr type out of range"); return; }
        }
        ReplyBuilder b((uint32_t)B, 0, BSI_STORAGE_F64);
        bsi_writer w{&b};
        int st = engine_.vt->world_declare(inst_, blocks.data(), (uint32_t)B, attrs.empty() ? nullptr : attrs.data(), (uint32_t)A, &w);
        if (st != BSI_OK) { errorFromBuilder(out, rq, st, b); return; }
        std::string why;
        if (!b.finalizeDeclare(why)) { errorReply(out, rq.id, rq.method, rq.revision, "INTERNAL", why); return; }
        world_ = std::move(blocks); attrs_ = std::move(attrs); worldDeclared_ = true;
        worldExt_ = rq.ignoredExt + r.ignoredExtensions;
        json::Writer jw;
        beginResponse(jw, rq, "response");
        jw.kv("status", "ok");
        writeDiag(jw, b, (uint32_t)B, vocab_.ignoredExtensions + worldExt_);
        jw.endObj();
        out.header = jw.take();
    }

    void worldEdit(const Request& rq, const uint8_t* payload, size_t n, Reply& out) {
        const auto& sch = schema::embedded();
        if (!worldDeclared_) { errorReply(out, rq.id, rq.method, rq.revision, "NO_WORLD", "world.edit before world.declare"); return; }
        if (!has("bsi.world.edit") || !engine_.vt->world_edit) { errorReply(out, rq.id, rq.method, rq.revision, "UNSUPPORTED", "engine has no bsi.world.edit"); return; }
        json::Value emptyBody; emptyBody.t = json::Value::T::Obj;
        const json::Value& body = rq.body ? *rq.body : emptyBody;
        uint64_t N = n / sizeof(bsi_edit);
        if (body.find("edits")) {
            schema::Result r = sch.validate("world.edit.body", body);
            if (!r.ok) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", firstProblem(r)); return; }
            N = (uint64_t)body.find("edits")->i64;
        }
        if (N == 0 || N * sizeof(bsi_edit) != n) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", "payload length does not match edits*41"); return; }
        std::vector<bsi_edit> edits((size_t)N);
        std::memcpy(edits.data(), payload, (size_t)N * sizeof(bsi_edit));
        for (const bsi_edit& e : edits) {
            if (e.op > 2) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", "edit op out of range"); return; }
            canon::Verdict v = canon::checkBlock(e.block, (uint32_t)vocab_.materials.size(), (uint32_t)vocab_.sections.size());
            if (!v.ok) { errorReply(out, rq.id, rq.method, rq.revision, v.code, v.message, v.hasAt ? v.at : nullptr); return; }
        }
        ReplyBuilder b((uint32_t)world_.size(), 0, BSI_STORAGE_F64);
        bsi_writer w{&b};
        int st = engine_.vt->world_edit(inst_, edits.data(), (uint32_t)N, &w);
        if (st != BSI_OK) { errorFromBuilder(out, rq, st, b); return; }
        std::string why;
        if (!b.finalizeDeclare(why) || !b.haveEdit()) { errorReply(out, rq.id, rq.method, rq.revision, "INTERNAL", why.empty() ? "engine wrote no edit class" : why); return; }
        // apply to the host's world copy (persistent world semantics)
        for (const bsi_edit& e : edits) {
            auto it = std::find_if(world_.begin(), world_.end(), [&](const bsi_block& q) { return q.x == e.block.x && q.y == e.block.y && q.z == e.block.z; });
            if (e.op == BSI_EDIT_REMOVE) { if (it != world_.end()) world_.erase(it); }
            else if (it != world_.end()) *it = e.block;
            else world_.push_back(e.block);
        }
        canon::canonicalise(world_);
        json::Writer jw;
        beginResponse(jw, rq, "response");
        jw.kv("status", "ok");
        writeDiag(jw, b, (uint32_t)world_.size(), vocab_.ignoredExtensions + worldExt_ + rq.ignoredExt);
        jw.key("edit"); jw.beginObj(); jw.kv("class", std::string(1, b.editCls())); if (!b.editDowngraded().empty()) jw.kv("downgraded", b.editDowngraded()); jw.endObj();
        jw.endObj();
        out.header = jw.take();
    }

    void solve(const Request& rq, const uint8_t* payload, size_t n, Reply& out) {
        const auto& sch = schema::embedded();
        if (!worldDeclared_) { errorReply(out, rq.id, rq.method, rq.revision, "NO_WORLD", "solve before world.declare"); return; }
        json::Value emptyBody; emptyBody.t = json::Value::T::Obj;
        const json::Value& body = rq.body ? *rq.body : emptyBody;
        schema::Result r = sch.validate("solve.request.body", body);
        if (!r.ok) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", firstProblem(r)); return; }
        bsi_solve_options o{};
        o.selfWeight = 1; o.gravity[0] = 0; o.gravity[1] = -9.80665; o.gravity[2] = 0;
        o.bucklingMode = BSI_BUCK_NONE; o.bucklingK = 1.0; o.bucklingBudgetDof = 0;
        o.tier = BSI_TIER_COMMIT; o.targetRel = 1e-9; o.storage = BSI_STORAGE_F64; o.warmStart = 0; o.maxTimeMs = 0; o.numThreads = 0; o.includeMask = 0;
        if (const json::Value* v = body.find("selfWeight")) o.selfWeight = v->b ? 1 : 0;
        if (const json::Value* v = body.find("gravity")) for (int k = 0; k < 3; ++k) o.gravity[k] = v->arr[k].num;
        if (const json::Value* bk = body.find("buckling")) {
            if (const json::Value* m = bk->find("mode")) { int i = sch.enumIndex("bucklingMode", m->str); o.bucklingMode = (uint8_t)i; }
            if (const json::Value* v = bk->find("budgetDof")) o.bucklingBudgetDof = (uint32_t)v->i64;
            if (const json::Value* v = bk->find("K")) o.bucklingK = v->num;
        }
        if (const json::Value* pr = body.find("precision")) {
            if (const json::Value* v = pr->find("tier")) o.tier = (uint8_t)sch.enumIndex("tier", v->str);
            if (const json::Value* v = pr->find("targetRel")) o.targetRel = v->num;
            if (const json::Value* v = pr->find("storage")) o.storage = (uint8_t)sch.enumIndex("storage", v->str);
            if (const json::Value* v = pr->find("warmStart")) o.warmStart = v->b ? 1 : 0;
            if (const json::Value* v = pr->find("maxTimeMs")) o.maxTimeMs = (uint32_t)v->i64;
        }
        // Session default (x-capi.openOptions.numThreads) unless this request says
        // otherwise; 0 stays 0 and means "the engine chooses".
        o.numThreads = opts_.numThreads;
        if (const json::Value* v = body.find("numThreads")) o.numThreads = (uint32_t)v->i64;
        if (const json::Value* inc = body.find("include")) for (const auto& e : inc->arr) {
            if (e.str == "members") o.includeMask |= kIncMembers;
            else if (e.str == "stations") o.includeMask |= kIncStations;
            else if (e.str == "shells") o.includeMask |= kIncShells;
            else if (e.str == "attrsEcho") o.includeMask |= kIncAttrsEcho;
        }
        // capability gate BEFORE the engine (P6)
        if (o.bucklingMode == BSI_BUCK_EIGEN && !has("bsi.buckling.eigen")) { errorReply(out, rq.id, rq.method, rq.revision, "UNSUPPORTED", "buckling.mode=eigen needs bsi.buckling.eigen"); return; }
        if (o.bucklingMode == BSI_BUCK_SCREEN && !has("bsi.buckling.screen")) { errorReply(out, rq.id, rq.method, rq.revision, "UNSUPPORTED", "buckling.mode=screen needs bsi.buckling.screen"); return; }
        if (o.tier == BSI_TIER_DISPLAY && !has("bsi.precision.display")) { errorReply(out, rq.id, rq.method, rq.revision, "UNSUPPORTED", "precision.tier=display needs bsi.precision.display"); return; }
        if (o.storage == BSI_STORAGE_F32 && !has("bsi.precision.f32")) { errorReply(out, rq.id, rq.method, rq.revision, "UNSUPPORTED", "precision.storage=f32 needs bsi.precision.f32"); return; }
        if (o.warmStart && !has("bsi.precision.warmstart")) { errorReply(out, rq.id, rq.method, rq.revision, "UNSUPPORTED", "precision.warmStart needs bsi.precision.warmstart"); return; }
        // maxTimeMs used to arrive here and go no further: an engine that cannot be
        // interrupted answered "ok" and the caller had no way to tell that its
        // deadline had been dropped. An ignored option is a lie told in the reply's
        // own quality section (BSI_ADD1 G-C, measured against the stub).
        if (o.maxTimeMs && !has("bsi.precision.timeout")) { errorReply(out, rq.id, rq.method, rq.revision, "UNSUPPORTED", "precision.maxTimeMs needs bsi.precision.timeout"); return; }
        if ((o.includeMask & kIncMembers) && !has("bsi.readback.members")) { errorReply(out, rq.id, rq.method, rq.revision, "UNSUPPORTED", "include members needs bsi.readback.members"); return; }
        if ((o.includeMask & kIncStations) && !has("bsi.readback.stations")) { errorReply(out, rq.id, rq.method, rq.revision, "UNSUPPORTED", "include stations needs bsi.readback.stations"); return; }
        if ((o.includeMask & kIncShells) && !has("bsi.readback.shells")) { errorReply(out, rq.id, rq.method, rq.revision, "UNSUPPORTED", "include shells needs bsi.readback.shells"); return; }
        if ((o.includeMask & kIncAttrsEcho) && !has("bsi.block.attrs")) { errorReply(out, rq.id, rq.method, rq.revision, "UNSUPPORTED", "include attrsEcho needs bsi.block.attrs"); return; }
        // loads
        uint64_t N = 0;
        if (const json::Value* v = body.find("loads")) N = (uint64_t)v->i64;
        if (N * sizeof(bsi_load) != n) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", "payload length does not match loads*64"); return; }
        std::vector<bsi_load> loads((size_t)N);
        if (N) std::memcpy(loads.data(), payload, (size_t)N * sizeof(bsi_load));
        for (const bsi_load& l : loads) {
            canon::Verdict v = canon::checkLoad(l);
            if (!v.ok) { errorReply(out, rq.id, rq.method, rq.revision, v.code, v.message, v.hasAt ? v.at : nullptr); return; }
        }
        canon::canonicaliseLoads(loads);
        ReplyBuilder b((uint32_t)world_.size(), o.includeMask, o.storage);
        if (o.includeMask & kIncAttrsEcho) b.attrsEcho(attrs_.data(), (uint32_t)attrs_.size());
        bsi_writer w{&b};
        services_.cancelled = 0;
        int st = engine_.vt->solve(inst_, &o, loads.empty() ? nullptr : loads.data(), (uint32_t)N, &w);
        if (st != BSI_OK) { errorFromBuilder(out, rq, st, b); return; }
        std::string why;
        if (!b.finalizeSolve(why)) { errorReply(out, rq.id, rq.method, rq.revision, "INTERNAL", why); return; }
        // per-cell identity: every ownerKind==unassigned block appears in the listing, at its own coordinates
        {
            std::set<std::array<int32_t, 3>> listed;
            for (const auto& g : b.unassignedGroups()) for (size_t k = 0; k + 2 < g.xyz.size(); k += 3) listed.insert({g.xyz[k], g.xyz[k + 1], g.xyz[k + 2]});
            const auto& blocks = b.payload();
            for (size_t i = 0; i < world_.size(); ++i) {
                bsi_block_result br; std::memcpy(&br, blocks.data() + i * sizeof(bsi_block_result), sizeof br);
                bool inList = listed.count({world_[i].x, world_[i].y, world_[i].z}) > 0;
                if ((br.ownerKind == BSI_OWNER_UNASSIGNED) != inList) { errorReply(out, rq.id, rq.method, rq.revision, "INTERNAL", "ownerKind/unassigned listing disagree at a block"); return; }
                if (br.ownerKind == BSI_OWNER_UNASSIGNED) {
                    int idx = sch.enumIndex("unassignedWhy", reasonOf(b, world_[i]));
                    if (idx >= 0 && br.reason != (uint32_t)(idx + 1)) { errorReply(out, rq.id, rq.method, rq.revision, "INTERNAL", "reason code disagrees with the unassigned listing"); return; }
                }
                bool over = br.flags & 1u;
                if (over != (br.dc > 1.0)) { errorReply(out, rq.id, rq.method, rq.revision, "INTERNAL", "overloaded flag disagrees with dc on the double"); return; }
                // bit2 must agree with the island's own buckling record, in BOTH
                // directions: a flag set with nothing behind it and a critical
                // island whose blocks stay unflagged are the same defect seen from
                // two sides, and the consumer cannot tell either of them from a
                // safe answer (BSI_ADD1 G-A).
                const bool crit = (br.flags & 4u) != 0;
                const bool owned = br.ownerKind == BSI_OWNER_MEMBER || br.ownerKind == BSI_OWNER_FACET;
                if (!owned) {
                    // A ground record or an unmodelled cell has no element and therefore no
                    // stability verdict. Letting the bit through here would put a colour on
                    // a cell the engine never analysed.
                    if (crit) { errorReply(out, rq.id, rq.method, rq.revision, "INTERNAL", "bucklingCritical set on a block that owns no element"); return; }
                } else {
                    bool hasRecord = false;
                    const bool shouldBeCrit = b.islandBucklingCritical(br.island, hasRecord);
                    if (crit && !hasRecord) { errorReply(out, rq.id, rq.method, rq.revision, "INTERNAL", "bucklingCritical set on a block whose island has no buckling record"); return; }
                    if (crit != shouldBeCrit) { errorReply(out, rq.id, rq.method, rq.revision, "INTERNAL", "bucklingCritical disagrees with the island's buckling state/factor"); return; }
                }
            }
        }
        json::Writer jw;
        beginResponse(jw, rq, "response");
        jw.kv("status", b.timedOut() ? "partial" : "ok");
        writeDiag(jw, b, (uint32_t)world_.size(), vocab_.ignoredExtensions + worldExt_ + rq.ignoredExt + r.ignoredExtensions);
        jw.key("buckling"); jw.beginObj();
        jw.kv("kind", sch.enumValues("bucklingMode")[o.bucklingMode]); jw.kv("state", b.bucklingState(o.bucklingMode));
        jw.endObj();
        jw.key("unassigned"); jw.beginArr();
        for (const auto& g : b.unassignedGroups()) {
            jw.beginObj(); jw.kv("why", g.why); jw.kv("island", (long long)g.island);
            jw.key("blocks"); jw.beginArr();
            for (size_t k = 0; k + 2 < g.xyz.size(); k += 3) { jw.beginArr(); jw.val((long long)g.xyz[k]); jw.val((long long)g.xyz[k + 1]); jw.val((long long)g.xyz[k + 2]); jw.endArr(); }
            jw.endArr(); jw.endObj();
        }
        jw.endArr();
        jw.key("sections"); jw.beginArr();
        for (const auto& s : b.sections()) { jw.beginObj(); jw.kv("name", s.name); jw.kv("offset", (unsigned long long)s.offset); jw.kv("bytes", (unsigned long long)s.bytes); jw.kv("count", (unsigned long long)s.count); jw.endObj(); }
        jw.endArr();
        jw.endObj();
        out.header = jw.take();
        out.payload = b.payload();
    }

    static std::string reasonOf(const ReplyBuilder& b, const bsi_block& blk) {
        for (const auto& g : b.unassignedGroups())
            for (size_t k = 0; k + 2 < g.xyz.size(); k += 3)
                if (g.xyz[k] == blk.x && g.xyz[k + 1] == blk.y && g.xyz[k + 2] == blk.z) return g.why;
        return "";
    }

    void cancel(const Request& rq, Reply& out) {
        const auto& sch = schema::embedded();
        if (!rq.body) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", "cancel needs a body"); return; }
        schema::Result r = sch.validate("cancel.body", *rq.body);
        if (!r.ok) { errorReply(out, rq.id, rq.method, rq.revision, "PROTOCOL_ERROR", firstProblem(r)); return; }
        // The host serialises requests: nothing is ever in flight when cancel is read, so
        // there is nothing to cancel. The engine's cancel slot is a courtesy (Part G item 5).
        if (engine_.vt->cancel) engine_.vt->cancel(inst_);
        json::Writer jw;
        beginResponse(jw, rq, "response");
        jw.kv("status", "ok"); jw.kv("targetId", rq.body->find("targetId")->str);
        jw.endObj();
        out.header = jw.take();
    }
};

Session::Session(Engine& e, const HostOptions& o) : impl_(new SessionImpl(e, o)) {}
Session::~Session() = default;
void Session::handle(const std::string& h, const uint8_t* p, size_t n, Reply& out) { impl_->handle(h, p, n, out); }
bool Session::poisoned() const { return impl_->poisoned(); }
const HostOptions& Session::options() const { return impl_->options(); }
const Engine& Session::engine() const { return impl_->engine(); }

}  // namespace bsi
