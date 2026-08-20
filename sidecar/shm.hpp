// File-backed shared memory for the zero-copy transport (D-019).
//
// The JVM creates and maps a scratch file; this process maps the SAME file. Numbers
// then cross the boundary as raw little-endian IEEE-754 — written once by the
// producer, read once by the consumer, no text, no parse, no intermediate copy.
// Synchronisation is the stdio doorbell: one request line, one reply line, strictly
// half-duplex, so the two sides never touch the region at the same time and the
// request and reply can REUSE the same bytes.
//
// Why a FILE and not POSIX shm_open / CreateFileMapping(INVALID_HANDLE_VALUE):
// a path travels over the existing handshake with no extra namespace, works
// identically on both platforms, and leaks at worst a temp file rather than a
// named kernel object if either side dies.
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace brshm {

class Mapping {
public:
    Mapping() = default;
    Mapping(const Mapping&) = delete;
    Mapping& operator=(const Mapping&) = delete;
    ~Mapping() { close(); }

    // Maps an EXISTING file read-write. The JVM owns creation and sizing; mapping a
    // file that is missing or empty is an error, never a silent zero-page region.
    bool open(const std::string& path, std::string& err) {
        close();
#if defined(_WIN32)
        // The path arrives as UTF-8; the W API wants UTF-16.
        const int wlen = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, nullptr, 0);
        if (wlen <= 0) { err = "shm: path is not valid UTF-8"; return false; }
        std::wstring wpath(static_cast<size_t>(wlen), L'\0');
        MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, &wpath[0], wlen);

        file_ = CreateFileW(wpath.c_str(), GENERIC_READ | GENERIC_WRITE,
                            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                            nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (file_ == INVALID_HANDLE_VALUE) {
            err = "shm: cannot open " + path + " (error " + std::to_string(GetLastError()) + ")";
            return false;
        }
        LARGE_INTEGER sz{};
        if (!GetFileSizeEx(file_, &sz) || sz.QuadPart <= 0) {
            err = "shm: file is empty: " + path;
            close();
            return false;
        }
        size_ = static_cast<size_t>(sz.QuadPart);
        mapping_ = CreateFileMappingW(file_, nullptr, PAGE_READWRITE, 0, 0, nullptr);
        if (!mapping_) {
            err = "shm: CreateFileMapping failed (error " + std::to_string(GetLastError()) + ")";
            close();
            return false;
        }
        base_ = MapViewOfFile(mapping_, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, 0);
        if (!base_) {
            err = "shm: MapViewOfFile failed (error " + std::to_string(GetLastError()) + ")";
            close();
            return false;
        }
#else
        fd_ = ::open(path.c_str(), O_RDWR);
        if (fd_ < 0) { err = "shm: cannot open " + path; return false; }
        struct stat st{};
        if (fstat(fd_, &st) != 0 || st.st_size <= 0) {
            err = "shm: file is empty: " + path;
            close();
            return false;
        }
        size_ = static_cast<size_t>(st.st_size);
        base_ = mmap(nullptr, size_, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
        if (base_ == MAP_FAILED) {
            base_ = nullptr;
            err = "shm: mmap failed: " + path;
            close();
            return false;
        }
#endif
        return true;
    }

    void close() {
#if defined(_WIN32)
        if (base_)    { UnmapViewOfFile(base_); base_ = nullptr; }
        if (mapping_) { CloseHandle(mapping_); mapping_ = nullptr; }
        if (file_ != INVALID_HANDLE_VALUE) { CloseHandle(file_); file_ = INVALID_HANDLE_VALUE; }
#else
        if (base_)   { munmap(base_, size_); base_ = nullptr; }
        if (fd_ >= 0) { ::close(fd_); fd_ = -1; }
#endif
        size_ = 0;
    }

    bool          valid() const { return base_ != nullptr; }
    std::uint8_t* data()  const { return static_cast<std::uint8_t*>(base_); }
    size_t        size()  const { return size_; }

private:
    void*  base_ = nullptr;
    size_t size_ = 0;
#if defined(_WIN32)
    HANDLE file_    = INVALID_HANDLE_VALUE;
    HANDLE mapping_ = nullptr;
#else
    int fd_ = -1;
#endif
};

// ---- little-endian cursor ----------------------------------------------------
// The wire is little-endian by CONTRACT. The implementation is a native-order
// memcpy, which is only correct on an LE host — true of every supported target
// (x86-64 and AArch64 in its usual mode). hostIsLittleEndian() lets the caller
// refuse the shm transport, rather than corrupt numbers, if a BE build appears.
inline bool hostIsLittleEndian() {
    const std::uint32_t probe = 1u;
    std::uint8_t b;
    std::memcpy(&b, &probe, 1);
    return b == 1;
}

struct Reader {
    const std::uint8_t* p;
    const std::uint8_t* end;
    bool ok = true;

    bool need(size_t n) {
        if (!ok || static_cast<size_t>(end - p) < n) { ok = false; return false; }
        return true;
    }
    std::uint32_t u32() {
        if (!need(4)) return 0;
        std::uint32_t v;
        std::memcpy(&v, p, 4);
        p += 4;
        return v;
    }
    std::int32_t i32() { return static_cast<std::int32_t>(u32()); }
    double f64() {
        if (!need(8)) return 0;
        double v;
        std::memcpy(&v, p, 8);
        p += 8;
        return v;
    }
};

struct Writer {
    std::uint8_t* p;
    std::uint8_t* end;
    bool ok = true;

    bool need(size_t n) {
        if (!ok || static_cast<size_t>(end - p) < n) { ok = false; return false; }
        return true;
    }
    void u32(std::uint32_t v) {
        if (!need(4)) return;
        std::memcpy(p, &v, 4);
        p += 4;
    }
    void i32(std::int32_t v) { u32(static_cast<std::uint32_t>(v)); }
    void f64(double v) {
        if (!need(8)) return;
        std::memcpy(p, &v, 8);
        p += 8;
    }
};

} // namespace brshm
