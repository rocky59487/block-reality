#include "../bsi_transports.hpp"
#include "../bsi_base64.hpp"
#include "../bsi_json.hpp"
#include <cstring>
#include <vector>

namespace bsi { namespace transport {

static bool readLine(FILE* in, std::string& line) {
    line.clear();
    int c;
    while ((c = std::fgetc(in)) != EOF) {
        if (c == '\n') return true;
        line.push_back((char)c);
        if (line.size() > (64u << 20)) return false;     // 64 MiB line cap (fail closed)
    }
    return !line.empty();
}

// Strip payloadB64 (and payloadBytes) from a request line so the session sees a
// header identical to the other transports (C-2).
static bool splitLine(const std::string& line, std::string& header, std::vector<uint8_t>& payload, std::string& why) {
    json::Value v;
    if (!json::parse(line, v) || !v.isObj()) { why = "line is not a JSON object"; return false; }
    payload.clear();
    json::Value stripped; stripped.t = json::Value::T::Obj;
    for (const auto& kv : v.obj) {
        if (kv.first == "payloadB64") { if (!kv.second.isStr() || !b64::decode(kv.second.str, payload)) { why = "payloadB64 is not valid base64"; return false; } continue; }
        stripped.obj.push_back(kv);
    }
    header.clear();
    json::serialize(stripped, header);
    return true;
}

int runStdioB64(Session& s, FILE* in, FILE* out) {
    std::string line, header;
    std::vector<uint8_t> payload;
    while (readLine(in, line)) {
        if (line.empty()) continue;
        Reply r;
        std::string why;
        if (!splitLine(line, header, payload, why)) {
            json::Writer w; w.beginObj(); w.kv("bsi", 1); w.kv("kind", "error"); w.kv("id", ""); w.kv("method", ""); w.kv("revision", 0LL);
            w.kv("code", "PROTOCOL_ERROR"); w.kv("message", why); w.endObj();
            r.header = w.take(); r.error = true;
        } else {
            s.handle(header, payload.data(), payload.size(), r);
        }
        // Part G item 3: append payloadBytes/payloadB64 after the header's own keys
        std::string outLine = r.header;
        if (!outLine.empty() && outLine.back() == '}') outLine.pop_back();
        outLine += ",\"payloadBytes\":" + std::to_string(r.payload.size()) + ",\"payloadB64\":\"" + b64::encode(r.payload.data(), r.payload.size()) + "\"}\n";
        std::fwrite(outLine.data(), 1, outLine.size(), out);
        std::fflush(out);
    }
    return 0;
}

}}  // namespace bsi::transport
