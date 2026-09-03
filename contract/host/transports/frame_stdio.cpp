#include "../bsi_transports.hpp"
#include "../bsi_frame.hpp"
#include <vector>

namespace bsi { namespace transport {

static bool readExact(FILE* in, uint8_t* p, size_t n) { return std::fread(p, 1, n, in) == n; }

int runFrameStdio(Session& s, FILE* in, FILE* out) {
    for (;;) {
        uint8_t prefix[frame::kPrefixBytes];
        if (!readExact(in, prefix, sizeof prefix)) return 0;
        uint32_t hl = frame::getU32(prefix + 4), pl = frame::getU32(prefix + 8);
        if ((uint64_t)hl + pl > (256u << 20)) return 2;         // 256 MiB frame cap
        std::vector<uint8_t> f(frame::kPrefixBytes + hl + pl);
        std::memcpy(f.data(), prefix, sizeof prefix);
        if (!readExact(in, f.data() + frame::kPrefixBytes, hl + pl)) return 2;
        frame::View v;
        Reply r;
        if (!frame::decode(f.data(), f.size(), v)) return 2;      // transport-level: cannot even answer
        s.handle(v.headerStr(), v.payload, v.payloadLen, r);
        uint16_t flags = frame::kFlagEndOfResponse;
        if (!r.payload.empty()) flags |= frame::kFlagHasPayload | frame::kFlagBinaryPayload;
        std::vector<uint8_t> o = frame::encode(flags, r.header, r.payload.data(), r.payload.size());
        std::fwrite(o.data(), 1, o.size(), out);
        std::fflush(out);
    }
}

}}  // namespace bsi::transport
