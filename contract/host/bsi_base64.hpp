// bsi_base64.hpp -- base64 for the stdio-b64 transport (T-B').
#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace bsi { namespace b64 {

inline std::string encode(const uint8_t* p, size_t n) {
    static const char* T = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out; out.reserve((n + 2) / 3 * 4);
    size_t i = 0;
    for (; i + 2 < n; i += 3) {
        uint32_t v = ((uint32_t)p[i] << 16) | ((uint32_t)p[i + 1] << 8) | p[i + 2];
        out.push_back(T[(v >> 18) & 63]); out.push_back(T[(v >> 12) & 63]); out.push_back(T[(v >> 6) & 63]); out.push_back(T[v & 63]);
    }
    if (i < n) {
        uint32_t v = (uint32_t)p[i] << 16; if (i + 1 < n) v |= (uint32_t)p[i + 1] << 8;
        out.push_back(T[(v >> 18) & 63]); out.push_back(T[(v >> 12) & 63]);
        out.push_back(i + 1 < n ? T[(v >> 6) & 63] : '='); out.push_back('=');
    }
    return out;
}

inline bool decode(const std::string& s, std::vector<uint8_t>& out) {
    out.clear();
    if (s.size() % 4 != 0) return false;
    auto val = [](char c) -> int {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        return -1;
    };
    for (size_t i = 0; i < s.size(); i += 4) {
        int a = val(s[i]), b = val(s[i + 1]);
        if (a < 0 || b < 0) return false;
        bool p3 = s[i + 2] == '=', p4 = s[i + 3] == '=';
        int c = p3 ? 0 : val(s[i + 2]), d = p4 ? 0 : val(s[i + 3]);
        if (c < 0 || d < 0 || (p3 && !p4) || ((p3 || p4) && i + 4 != s.size())) return false;
        uint32_t v = ((uint32_t)a << 18) | ((uint32_t)b << 12) | ((uint32_t)c << 6) | (uint32_t)d;
        out.push_back((uint8_t)(v >> 16));
        if (!p3) out.push_back((uint8_t)(v >> 8));
        if (!p4) out.push_back((uint8_t)v);
    }
    return true;
}

}}  // namespace bsi::b64
