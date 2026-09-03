// bsi_arena.hpp -- the zero-copy arena (BSI.md Part D.2, Part G item 2).
//   struct BsiArenaHeader { u32 magic 'BSIA'; u32 version=1; u64 capacity;
//     u64 worldOff, worldLen, attrsOff, attrsLen, loadsOff, loadsLen, reqOff, reqLen, replyOff, replyLen;
//     u64 seq; u32 flags; u32 crc32; }  -- 128 bytes, LE
// The reply region holds ONE T-A frame; replyLen is its byte count.
#pragma once
#include <cstdint>
#include <string>

namespace bsi { namespace arena {

constexpr uint32_t kMagic = 0x41495342u;   // 'B','S','I','A' little-endian
constexpr uint32_t kVersion = 1;
constexpr size_t   kHeaderBytes = 128;

#pragma pack(push, 1)
struct Header {
    uint32_t magic, version;
    uint64_t capacity;
    uint64_t worldOff, worldLen, attrsOff, attrsLen, loadsOff, loadsLen, reqOff, reqLen, replyOff, replyLen;
    uint64_t seq;
    uint32_t flags, crc32;
    uint8_t  reserved[16];    // pads the 112 named bytes to the contract's headerBytes=128
};
#pragma pack(pop)
static_assert(sizeof(Header) == kHeaderBytes, "arena header must be 128 bytes");

// Validate a header against the mapped size. Returns false with a reason on
// any inconsistency (=> ARENA_CORRUPT).
bool validate(const Header& h, size_t mappedBytes, std::string& why);

// A mapped arena file (POSIX mmap). Windows is out of scope for this revision
// (BSI.md Part G); the type still compiles there but open() fails.
class Mapping {
public:
    Mapping() = default;
    ~Mapping();
    Mapping(const Mapping&) = delete;
    Mapping& operator=(const Mapping&) = delete;
    bool open(const std::string& path, std::string& err);     // maps the whole existing file read-write
    bool remap(std::string& err);                                // re-map after the owner grew the file
    void close();
    uint8_t* base() const { return base_; }
    size_t size() const { return size_; }
    Header* header() const { return (Header*)base_; }
private:
    std::string path_;
    uint8_t* base_ = nullptr;
    size_t size_ = 0;
    int fd_ = -1;
};

}}  // namespace bsi::arena
