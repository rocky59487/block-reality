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
        if (!arena::validate(h, map.size(), why) || h.seq != seq) { bell(out, "error", seq, 0, 0, ("ARENA_CORRUPT: " + (h.seq != seq ? std::string("seq mismatch") : why)).c_str()); continue; }
        std::string header((const char*)map.base() + h.reqOff, (size_t)h.reqLen);
        // payload source per door (Part G): declare -> world(+attrs), solve -> loads, others none
        std::vector<uint8_t> payload;
        json::Value hv; std::string method;
        if (json::parse(header, hv) && hv.isObj() && hv.find("method") && hv.find("method")->isStr()) method = hv.find("method")->str;
        static const struct { const char* door; const char* method; } pairs[] = {
            {"hello", "bsi.hello"}, {"vocab", "bsi.vocab.declare"}, {"declare", "bsi.world.declare"}, {"edit", "bsi.world.edit"}, {"solve", "bsi.solve"}, {"cancel", "bsi.cancel"}};
        bool doorOk = false;
        for (const auto& p : pairs) if (door == p.door) { doorOk = true; if (method != p.method && !(door == "vocab" && method == "bsi.vocab.query")) { bell(out, "error", seq, 0, 0, "PROTOCOL_ERROR: door does not match method"); doorOk = false; method.clear(); } break; }
        if (!doorOk) { if (!method.empty() || door.empty()) bell(out, "error", seq, 0, 0, "PROTOCOL_ERROR: unknown door"); continue; }
        if (door == "declare") {
            payload.assign(map.base() + h.worldOff, map.base() + h.worldOff + h.worldLen);
            payload.insert(payload.end(), map.base() + h.attrsOff, map.base() + h.attrsOff + h.attrsLen);
        } else if (door == "solve") {
            payload.assign(map.base() + h.loadsOff, map.base() + h.loadsOff + h.loadsLen);
        } else if (door == "edit") {
            bell(out, "error", seq, 0, 0, "UNSUPPORTED: world.edit over the arena is not in this contract revision"); continue;
        }
        Reply r;
        s.handle(header, payload.data(), payload.size(), r);
        uint16_t flags = frame::kFlagEndOfResponse;
        if (!r.payload.empty()) flags |= frame::kFlagHasPayload | frame::kFlagBinaryPayload;
        size_t need = frame::encodedSize(r.header.size(), r.payload.size());
        uint64_t cap = (h.replyOff <= map.size()) ? map.size() - h.replyOff : 0;
        if (need > cap) { bell(out, "needBigger", seq, 0, need, nullptr); continue; }
        frame::encodeInto(map.base() + h.replyOff, flags, r.header, r.payload.data(), r.payload.size());
        arena::Header* hp = map.header();
        hp->replyLen = need;
        bell(out, r.error ? "error" : "reply", seq, need, 0, nullptr);
    }
    return 0;
}

}}  // namespace bsi::transport
