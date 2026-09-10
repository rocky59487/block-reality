#include "../bsi_transports.hpp"
#include "../bsi_arena.hpp"
#include "../bsi_frame.hpp"
#include "../bsi_json.hpp"
#include <cstring>
#include <vector>

namespace bsi { namespace transport {

static bool readLine(FILE* in, std::string& line) {
    line.clear();
    int c;
    while ((c = std::fgetc(in)) != EOF) { if (c == '\n') return true; line.push_back((char)c); if (line.size() > 65536) return false; }
    return !line.empty();
}

static void bell(FILE* out, const char* door, unsigned long long seq, unsigned long long replyLen, unsigned long long required, const char* msg) {
    json::Writer w; w.beginObj(); w.kv("bsi", 1); w.kv("door", door); w.kv("seq", seq); w.kv("replyLen", replyLen);
    if (required) w.kv("required", required);
    if (msg) w.kv("message", msg);
    w.endObj();
    std::string l = w.take(); l += '\n';
    std::fwrite(l.data(), 1, l.size(), out); std::fflush(out);
}

int runArena(Session& s, const std::string& path, FILE* in, FILE* out) {
    arena::Mapping map;
    std::string err;
    if (!map.open(path, err)) { std::fprintf(stderr, "arena: %s\n", err.c_str()); return 2; }
    std::string line;
    std::string pendingHeader;
    std::vector<uint8_t> pendingPayload, pendingReply;
    bool pendingError = false;
    while (readLine(in, line)) {
        if (line.empty()) continue;
        json::Value v;
        unsigned long long seq = 0;
        if (!json::parse(line, v) || !v.isObj() || !v.find("door") || !v.find("door")->isStr() || !v.find("seq") || !v.find("seq")->isInt) { bell(out, "error", 0, 0, 0, "PROTOCOL_ERROR: malformed doorbell"); continue; }
        seq = (unsigned long long)v.find("seq")->i64;
        std::string door = v.find("door")->str;
        if (!map.remap(err)) { bell(out, "error", seq, 0, 0, "ARENA_CORRUPT: remap failed"); continue; }
        arena::Header h; std::memcpy(&h, map.base(), sizeof h);
        std::string why;
        if (!arena::validate(h, map.size(), why, true) || h.seq != seq) { bell(out, "error", seq, 0, 0, ("ARENA_CORRUPT: " + (h.seq != seq ? std::string("seq mismatch") : why)).c_str()); continue; }
        if (h.reqLen > 256u * 1024u * 1024u - 12 || h.worldLen > 256u * 1024u * 1024u ||
            h.attrsLen > 256u * 1024u * 1024u - h.worldLen || h.loadsLen > 256u * 1024u * 1024u) {
            bell(out, "error", seq, 0, 0, "PROTOCOL_ERROR: arena request exceeds frame budget"); continue;
        }
        std::string header((const char*)map.base() + h.reqOff, (size_t)h.reqLen);
        // payload source per door (Part G): declare -> world(+attrs), solve -> loads, others none
        std::vector<uint8_t> payload;
        json::Value hv; std::string method;
        if (json::parse(header, hv) && hv.isObj() && hv.find("method") && hv.find("method")->isStr()) method = hv.find("method")->str;
        static const struct { const char* door; const char* method; } pairs[] = {
            {"hello", "bsi.hello"}, {"vocab", "bsi.vocab.declare"}, {"declare", "bsi.world.declare"}, {"edit", "bsi.world.edit"}, {"solve", "bsi.solve"}, {"cancel", "bsi.cancel"},
            {"fracturePrepare", "bsi.fracture.prepare"}, {"fractureFinish", "bsi.fracture.finish"}};
        bool doorOk = false;
        for (const auto& p : pairs) if (door == p.door) { doorOk = true; if (method != p.method && !(door == "vocab" && method == "bsi.vocab.query")) { bell(out, "error", seq, 0, 0, "PROTOCOL_ERROR: door does not match method"); doorOk = false; method.clear(); } break; }
        if (!doorOk) { if (!method.empty() || door.empty()) bell(out, "error", seq, 0, 0, "PROTOCOL_ERROR: unknown door"); continue; }
        const auto* requestBody = hv.find("body");
        const bool identified = (door == "declare" || door == "edit") && requestBody && requestBody->find("identity");
        // Unused regions can retain the preceding identified world's owner layout.
        if ((!identified && door == "declare" && !arena::validate(h, map.size(), why)) ||
            (identified && (door == "declare" ? h.worldLen % 40 || h.attrsLen % 20 : h.attrsLen != 0))) {
            bell(out, "error", seq, 0, 0, "ARENA_CORRUPT: invalid payload region record lengths"); continue;
        }
        if (door == "declare") {
            payload.assign(map.base() + h.worldOff, map.base() + h.worldOff + h.worldLen);
            payload.insert(payload.end(), map.base() + h.attrsOff, map.base() + h.attrsOff + h.attrsLen);
        } else if (door == "solve") {
            payload.assign(map.base() + h.loadsOff, map.base() + h.loadsOff + h.loadsLen);
        } else if (door == "edit") {
            const auto* body = hv.find("body");
            if (!body || !body->find("identity")) {
                bell(out, "error", seq, 0, 0, "UNSUPPORTED: unidentified world.edit over the arena is not in this contract revision"); continue;
            }
            payload.assign(map.base() + h.worldOff, map.base() + h.worldOff + h.worldLen);
        }
        if (!pendingReply.empty() && (header != pendingHeader || payload != pendingPayload)) {
            bell(out, "error", seq, 0, 0, "PROTOCOL_ERROR: a different request has a pending reply"); continue;
        }
        if (pendingReply.empty()) {
            // Allocate request ownership before dispatch. A resize must not rerun a commit.
            pendingHeader = header; pendingPayload = payload;
            try {
                Reply r;
                s.handle(pendingHeader, pendingPayload.data(), pendingPayload.size(), r);
                uint16_t flags = frame::kFlagEndOfResponse;
                if (!r.payload.empty()) flags |= frame::kFlagHasPayload | frame::kFlagBinaryPayload;
                size_t need = frame::encodedSize(r.header.size(), r.payload.size());
                if (need > 256u * 1024u * 1024u) {
                    bell(out, "error", seq, 0, 0, "INTERNAL: reply exceeds frame budget; reopen session"); return 2;
                }
                pendingReply.resize(need);
                frame::encodeInto(pendingReply.data(), flags, r.header, r.payload.data(), r.payload.size());
                pendingError = r.error;
            } catch (...) {
                bell(out, "error", seq, 0, 0, "INTERNAL: operation interrupted; reopen session"); return 2;
            }
        }
        const size_t need = pendingReply.size();
        uint64_t cap = (h.replyOff <= map.size()) ? map.size() - h.replyOff : 0;
        if (need > cap) {
#ifdef BSI_TEST_NFW_ARENA_RETRY
            pendingReply.clear();
#endif
            bell(out, "needBigger", seq, 0, need, nullptr); continue;
        }
        std::memcpy(map.base() + h.replyOff, pendingReply.data(), need);
        arena::Header* hp = map.header();
        hp->replyLen = need;
        bell(out, pendingError ? "error" : "reply", seq, need, 0, nullptr);
        pendingHeader.clear(); pendingPayload.clear(); pendingReply.clear();
    }
    return 0;
}

}}  // namespace bsi::transport
