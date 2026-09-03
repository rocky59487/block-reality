#include "bsi_arena.hpp"
#include <cstring>
#ifndef _WIN32
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace bsi { namespace arena {

bool validate(const Header& h, size_t mapped, std::string& why) {
    if (h.magic != kMagic) { why = "bad magic"; return false; }
    if (h.version != kVersion) { why = "bad version"; return false; }
    if (h.capacity != mapped) { why = "capacity != mapped size"; return false; }
    struct R { uint64_t off, len; const char* name; uint64_t align; } regions[] = {
        {h.worldOff, h.worldLen, "world", 40}, {h.attrsOff, h.attrsLen, "attrs", 16}, {h.loadsOff, h.loadsLen, "loads", 64},
        {h.reqOff, h.reqLen, "req", 1}, {h.replyOff, h.replyLen, "reply", 1}};
    for (const R& r : regions) {
        if (r.off < kHeaderBytes && r.len != 0) { why = std::string(r.name) + " overlaps header"; return false; }
        if (r.off > mapped || r.len > mapped - r.off) { why = std::string(r.name) + " out of bounds"; return false; }
        if (r.len % r.align != 0) { why = std::string(r.name) + " length not a whole number of records"; return false; }
    }
    // pairwise non-overlap of non-empty regions
    for (size_t a = 0; a < 5; ++a) for (size_t b = a + 1; b < 5; ++b) {
        const R& p = regions[a]; const R& q = regions[b];
        if (p.len == 0 || q.len == 0) continue;
        if (p.off < q.off + q.len && q.off < p.off + p.len) { why = std::string(p.name) + " overlaps " + q.name; return false; }
    }
    return true;
}

Mapping::~Mapping() { close(); }

bool Mapping::open(const std::string& path, std::string& err) {
#ifdef _WIN32
    (void)path; err = "arena transport: Windows not in this contract revision (BSI.md Part G)"; return false;
#else
    close();
    path_ = path;
    fd_ = ::open(path.c_str(), O_RDWR);
    if (fd_ < 0) { err = "open failed: " + path; return false; }
    return remap(err);
#endif
}

bool Mapping::remap(std::string& err) {
#ifdef _WIN32
    err = "unsupported"; return false;
#else
    if (fd_ < 0) { err = "not open"; return false; }
    if (base_) { ::munmap(base_, size_); base_ = nullptr; size_ = 0; }
    struct stat st{};
    if (::fstat(fd_, &st) != 0 || st.st_size < (off_t)kHeaderBytes) { err = "arena file too small"; return false; }
    void* p = ::mmap(nullptr, (size_t)st.st_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
    if (p == MAP_FAILED) { err = "mmap failed"; return false; }
    base_ = (uint8_t*)p; size_ = (size_t)st.st_size;
    return true;
#endif
}

void Mapping::close() {
#ifndef _WIN32
    if (base_) { ::munmap(base_, size_); base_ = nullptr; size_ = 0; }
    if (fd_ >= 0) { ::close(fd_); fd_ = -1; }
#endif
}

}}  // namespace bsi::arena
