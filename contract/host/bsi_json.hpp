// bsi_json.hpp -- strict, insertion-ordered JSON for the BSI host (contract artifact).
//
// Parser: recursive descent, depth-capped (64), no trailing tokens, \u escapes
// validated and UTF-8 encoded, numbers via strtod with an exact-int64 sidecar
// (revision / seq must survive above 2^53). Malformed input never throws across
// the host boundary: parse() returns false.
//
// Writer: emits objects in the order keys are added (response headers must follow
// the schema property order, BSI.md B.5). There is deliberately NO double overload:
// BSI P4 says headers carry no floating point, so the compiler enforces it.
#pragma once
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

namespace bsi { namespace json {

struct Value {
    enum class T { Null, Bool, Num, Str, Arr, Obj } t = T::Null;
    bool b = false;
    double num = 0;
    long long i64 = 0;      // valid iff isInt
    bool isInt = false;
    std::string str;
    std::vector<Value> arr;
    std::vector<std::pair<std::string, Value>> obj;   // insertion order

    bool isNull() const { return t == T::Null; }
    bool isObj() const { return t == T::Obj; }
    bool isArr() const { return t == T::Arr; }
    bool isStr() const { return t == T::Str; }
    bool isBool() const { return t == T::Bool; }
    bool isNum() const { return t == T::Num; }
    const Value* find(const char* key) const {
        if (t != T::Obj) return nullptr;
        for (const auto& kv : obj) if (kv.first == key) return &kv.second;
        return nullptr;
    }
    const Value* find(const std::string& key) const { return find(key.c_str()); }
    bool has(const char* key) const { return find(key) != nullptr; }
};

class Parser {
public:
    explicit Parser(const char* s, size_t n) : p_(s), end_(s + n) {}
    bool parse(Value& out) {
        ws();
        out = value();
        ws();
        if (p_ != end_) ok_ = false;
        return ok_;
    }
private:
    const char* p_;
    const char* end_;
    bool ok_ = true;
    int depth_ = 0;
    static constexpr int kMaxDepth = 64;

    void ws() { while (p_ < end_ && (*p_ == ' ' || *p_ == '\t' || *p_ == '\n' || *p_ == '\r')) ++p_; }
    bool lit(const char* w, size_t n) {
        if ((size_t)(end_ - p_) >= n && std::memcmp(p_, w, n) == 0) { p_ += n; return true; }
        return false;
    }
    Value value() {
        ws();
        if (p_ >= end_) { ok_ = false; return {}; }
        if (depth_ >= kMaxDepth) { ok_ = false; return {}; }
        ++depth_;
        Value v = dispatch();
        --depth_;
        return v;
    }
    Value dispatch() {
        char c = *p_;
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') { Value v; v.t = Value::T::Str; v.str = string(); return v; }
        if (c == 't') { Value v; v.t = Value::T::Bool; v.b = true;  if (!lit("true", 4))  ok_ = false; return v; }
        if (c == 'f') { Value v; v.t = Value::T::Bool; v.b = false; if (!lit("false", 5)) ok_ = false; return v; }
        if (c == 'n') { Value v; if (!lit("null", 4)) ok_ = false; return v; }
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        ok_ = false;
        return {};
    }
    Value object() {
        Value v; v.t = Value::T::Obj;
        ++p_;
        ws();
        if (p_ < end_ && *p_ == '}') { ++p_; return v; }
        for (;;) {
            ws();
            if (p_ >= end_ || *p_ != '"') { ok_ = false; return v; }
            std::string key = string();
            if (!ok_) return v;
            ws();
            if (p_ >= end_ || *p_ != ':') { ok_ = false; return v; }
            ++p_;
            Value val = value();
            if (!ok_) return v;
            v.obj.emplace_back(std::move(key), std::move(val));
            ws();
            if (p_ >= end_) { ok_ = false; return v; }
            if (*p_ == ',') { ++p_; continue; }
            if (*p_ == '}') { ++p_; return v; }
            ok_ = false; return v;
        }
    }
    Value array() {
        Value v; v.t = Value::T::Arr;
        ++p_;
        ws();
        if (p_ < end_ && *p_ == ']') { ++p_; return v; }
        for (;;) {
            Value val = value();
            if (!ok_) return v;
            v.arr.push_back(std::move(val));
            ws();
            if (p_ >= end_) { ok_ = false; return v; }
            if (*p_ == ',') { ++p_; continue; }
            if (*p_ == ']') { ++p_; return v; }
            ok_ = false; return v;
        }
    }
    static int hex(char h) {
        if (h >= '0' && h <= '9') return h - '0';
        if (h >= 'a' && h <= 'f') return h - 'a' + 10;
        if (h >= 'A' && h <= 'F') return h - 'A' + 10;
        return -1;
    }
    std::string string() {
        std::string out;
        ++p_;
        while (p_ < end_) {
            char c = *p_++;
            if (c == '"') return out;
            if ((unsigned char)c < 0x20) { ok_ = false; return out; }   // control chars must be escaped
            if (c != '\\') { out.push_back(c); continue; }
            if (p_ >= end_) { ok_ = false; return out; }
            char e = *p_++;
            switch (e) {
                case '"': out.push_back('"'); break;
                case '\\': out.push_back('\\'); break;
                case '/': out.push_back('/'); break;
                case 'b': out.push_back('\b'); break;
                case 'f': out.push_back('\f'); break;
                case 'n': out.push_back('\n'); break;
                case 'r': out.push_back('\r'); break;
                case 't': out.push_back('\t'); break;
                case 'u': {
                    if (end_ - p_ < 4) { ok_ = false; return out; }
                    int cp = 0;
                    for (int k = 0; k < 4; ++k) { int h = hex(*p_++); if (h < 0) { ok_ = false; return out; } cp = (cp << 4) | h; }
                    if (cp >= 0xD800 && cp <= 0xDBFF) {              // surrogate pair
                        if (end_ - p_ < 6 || p_[0] != '\\' || p_[1] != 'u') { ok_ = false; return out; }
                        p_ += 2;
                        int lo = 0;
                        for (int k = 0; k < 4; ++k) { int h = hex(*p_++); if (h < 0) { ok_ = false; return out; } lo = (lo << 4) | h; }
                        if (lo < 0xDC00 || lo > 0xDFFF) { ok_ = false; return out; }
                        cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                    } else if (cp >= 0xDC00 && cp <= 0xDFFF) { ok_ = false; return out; }
                    if (cp < 0x80) out.push_back((char)cp);
                    else if (cp < 0x800) { out.push_back((char)(0xC0 | (cp >> 6))); out.push_back((char)(0x80 | (cp & 0x3F))); }
                    else if (cp < 0x10000) { out.push_back((char)(0xE0 | (cp >> 12))); out.push_back((char)(0x80 | ((cp >> 6) & 0x3F))); out.push_back((char)(0x80 | (cp & 0x3F))); }
                    else { out.push_back((char)(0xF0 | (cp >> 18))); out.push_back((char)(0x80 | ((cp >> 12) & 0x3F))); out.push_back((char)(0x80 | ((cp >> 6) & 0x3F))); out.push_back((char)(0x80 | (cp & 0x3F))); }
                    break;
                }
                default: ok_ = false; return out;
            }
        }
        ok_ = false;
        return out;
    }
    Value number() {
        const char* s = p_;
        bool plain = true;
        if (p_ < end_ && *p_ == '-') ++p_;
        if (p_ >= end_ || !(*p_ >= '0' && *p_ <= '9')) { ok_ = false; return {}; }
        if (*p_ == '0') { ++p_; if (p_ < end_ && *p_ >= '0' && *p_ <= '9') { ok_ = false; return {}; } }
        else while (p_ < end_ && *p_ >= '0' && *p_ <= '9') ++p_;
        if (p_ < end_ && *p_ == '.') { plain = false; ++p_; if (p_ >= end_ || !(*p_ >= '0' && *p_ <= '9')) { ok_ = false; return {}; } while (p_ < end_ && *p_ >= '0' && *p_ <= '9') ++p_; }
        if (p_ < end_ && (*p_ == 'e' || *p_ == 'E')) { plain = false; ++p_; if (p_ < end_ && (*p_ == '+' || *p_ == '-')) ++p_; if (p_ >= end_ || !(*p_ >= '0' && *p_ <= '9')) { ok_ = false; return {}; } while (p_ < end_ && *p_ >= '0' && *p_ <= '9') ++p_; }
        std::string tok(s, p_);
        Value v; v.t = Value::T::Num;
        char* e = nullptr;
        v.num = std::strtod(tok.c_str(), &e);
        if (!e || *e != '\0') { ok_ = false; return {}; }
        if (plain) {
            errno = 0;
            char* ie = nullptr;
            long long iv = std::strtoll(tok.c_str(), &ie, 10);
            if (errno == 0 && ie && *ie == '\0') { v.i64 = iv; v.isInt = true; }
        }
        return v;
    }
};

inline bool parse(const std::string& s, Value& out) { Parser p(s.data(), s.size()); return p.parse(out); }
inline bool parse(const char* s, size_t n, Value& out) { Parser p(s, n); return p.parse(out); }

inline void escape(const std::string& in, std::string& out) {
    for (char c : in) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            default:
                if ((unsigned char)c < 0x20) { char buf[8]; std::snprintf(buf, sizeof buf, "\\u%04x", (unsigned)(unsigned char)c); out += buf; }
                else out.push_back(c);
        }
    }
}

// Ordered writer. Keys are emitted in call order; the caller is responsible for
// following the schema order (bsi_schema exposes it).
class Writer {
public:
    Writer& beginObj() { sep(); out_ += '{'; first_.push_back(true); return *this; }
    Writer& endObj()   { out_ += '}'; first_.pop_back(); mark(); return *this; }
    Writer& beginArr() { sep(); out_ += '['; first_.push_back(true); return *this; }
    Writer& endArr()   { out_ += ']'; first_.pop_back(); mark(); return *this; }
    Writer& key(const char* k) { sep(); out_ += '"'; escape(k, out_); out_ += "\":"; suppress_ = true; return *this; }
    Writer& key(const std::string& k) { return key(k.c_str()); }
    Writer& val(bool b) { sep(); out_ += (b ? "true" : "false"); mark(); return *this; }
    Writer& val(long long i) { sep(); out_ += std::to_string(i); mark(); return *this; }
    Writer& val(unsigned long long u) { sep(); out_ += std::to_string(u); mark(); return *this; }
    Writer& val(int i) { return val((long long)i); }
    Writer& val(unsigned u) { return val((unsigned long long)u); }
    Writer& val(long i) { return val((long long)i); }
    Writer& val(unsigned long u) { return val((unsigned long long)u); }
    Writer& val(const std::string& s) { sep(); out_ += '"'; escape(s, out_); out_ += '"'; mark(); return *this; }
    Writer& val(const char* s) { return val(std::string(s)); }
    Writer& null() { sep(); out_ += "null"; mark(); return *this; }
    Writer& val(double) = delete;    // BSI P4: no floating point in headers
    Writer& val(float) = delete;
    // raw pre-serialised JSON fragment (used to echo x- extension objects verbatim)
    Writer& raw(const std::string& j) { sep(); out_ += j; mark(); return *this; }
    template <class V> Writer& kv(const char* k, V v) { key(k); return val(v); }
    const std::string& str() const { return out_; }
    std::string take() { return std::move(out_); }
private:
    std::string out_;
    std::vector<bool> first_;
    bool suppress_ = false;
    void sep() {
        if (suppress_) { suppress_ = false; return; }
        if (!first_.empty()) { if (!first_.back()) out_ += ','; first_.back() = false; }
    }
    void mark() { if (!first_.empty()) first_.back() = false; }
};

// Re-serialise a parsed value deterministically (used for x- echo and tests).
// Numbers: integers as integers; non-integers with 17 significant digits.
inline void serialize(const Value& v, std::string& out) {
    switch (v.t) {
        case Value::T::Null: out += "null"; break;
        case Value::T::Bool: out += v.b ? "true" : "false"; break;
        case Value::T::Num:
            if (v.isInt) out += std::to_string(v.i64);
            else { char buf[40]; std::snprintf(buf, sizeof buf, "%.17g", v.num); out += buf; }
            break;
        case Value::T::Str: out += '"'; escape(v.str, out); out += '"'; break;
        case Value::T::Arr: {
            out += '[';
            for (size_t k = 0; k < v.arr.size(); ++k) { if (k) out += ','; serialize(v.arr[k], out); }
            out += ']';
            break;
        }
        case Value::T::Obj: {
            out += '{';
            for (size_t k = 0; k < v.obj.size(); ++k) {
                if (k) out += ',';
                out += '"'; escape(v.obj[k].first, out); out += "\":";
                serialize(v.obj[k].second, out);
            }
            out += '}';
            break;
        }
    }
}

}}  // namespace bsi::json
