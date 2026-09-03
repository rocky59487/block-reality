// bsi_frame.hpp -- the T-A frame codec (contract artifact).
//   'F' 'C' | flags u16 | headerLen u32 | payloadLen u32 | header | payload   (LE)
// Shared by bsi_capi (in-process), the frame stdio transport, and the arena
// reply region (BSI.md Part G item 2). No allocation surprises: the decoder
// returns views into the caller's buffer.
#pragma once
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace bsi { namespace frame {

constexpr uint8_t  kMagic0 = 0x46, kMagic1 = 0x43;
constexpr uint16_t kFlagEndOfResponse = 1u << 0;
constexpr uint16_t kFlagHasPayload    = 1u << 1;
constexpr uint16_t kFlagBinaryPayload = 1u << 2;
constexpr size_t   kPrefixBytes = 12;

inline void putU16(uint8_t* p, uint16_t x) { p[0] = (uint8_t)x; p[1] = (uint8_t)(x >> 8); }
inline void putU32(uint8_t* p, uint32_t x) { for (int k = 0; k < 4; ++k) p[k] = (uint8_t)(x >> (8 * k)); }
inline uint16_t getU16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }
inline uint32_t getU32(const uint8_t* p) { return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24); }

struct View {
    uint16_t flags = 0;
    const uint8_t* header = nullptr; size_t headerLen = 0;
    const uint8_t* payload = nullptr; size_t payloadLen = 0;
    std::string headerStr() const { return std::string((const char*)header, headerLen); }
};

// Decode one frame occupying exactly [p, p+n). Returns false on bad magic or
// inconsistent lengths (the transport-level PROTOCOL error; never dispatched).
inline bool decode(const uint8_t* p, size_t n, View& out) {
    if (n < kPrefixBytes || p[0] != kMagic0 || p[1] != kMagic1) return false;
    out.flags = getU16(p + 2);
    uint32_t hl = getU32(p + 4), pl = getU32(p + 8);
    if ((uint64_t)kPrefixBytes + hl + pl != n) return false;
    out.header = p + kPrefixBytes; out.headerLen = hl;
    out.payload = p + kPrefixBytes + hl; out.payloadLen = pl;
    return true;
}

inline size_t encodedSize(size_t headerLen, size_t payloadLen) { return kPrefixBytes + headerLen + payloadLen; }

// Encode into a caller buffer of at least encodedSize() bytes.
inline void encodeInto(uint8_t* dst, uint16_t flags, const std::string& header, const uint8_t* payload, size_t payloadLen) {
    dst[0] = kMagic0; dst[1] = kMagic1;
    putU16(dst + 2, flags);
    putU32(dst + 4, (uint32_t)header.size());
    putU32(dst + 8, (uint32_t)payloadLen);
    std::memcpy(dst + kPrefixBytes, header.data(), header.size());
    if (payloadLen) std::memcpy(dst + kPrefixBytes + header.size(), payload, payloadLen);
}

inline std::vector<uint8_t> encode(uint16_t flags, const std::string& header, const uint8_t* payload, size_t payloadLen) {
    std::vector<uint8_t> f(encodedSize(header.size(), payloadLen));
    encodeInto(f.data(), flags, header, payload, payloadLen);
    return f;
}

}}  // namespace bsi::frame
