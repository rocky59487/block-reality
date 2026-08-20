// Minimal JSON reader/writer for the Block Reality sidecar protocol.
// Deliberately small: the protocol shape is fixed and we control both ends.
// Parse failures return a null value — the caller replies with an error line,
// it never throws across the protocol boundary (ENGINE_BOUNDARY: fail-safe).
#pragma once
#include <string>
#include <vector>
#include <map>
#include <memory>
#include <cctype>
#include <cerrno>
#include <cstdlib>
#include <cmath>
#include <sstream>

namespace bjson {

struct Value;
using Object = std::map<std::string, Value>;
using Array  = std::vector<Value>;

struct Value {
    enum class T { Null, Bool, Num, Str, Arr, Obj } t = T::Null;
    bool        b = false;
    double      n = 0;
    // Exact-integer sidecar of `n` (#29). A double folds every integer above 2^53
    // onto its neighbours, and revision is a 64-bit stale-result fence — it must
    // round-trip exactly or two adjacent revisions become the same value. The
    // parser fills these whenever the literal was a plain integer that fits int64.
    long long   i = 0;
    bool        isInt = false;
    std::string s;
    Array       a;
    Object      o;

    bool isNull() const { return t == T::Null; }
    // Typed accessors with defaults — a missing or wrong-typed field degrades to
    // the default rather than throwing.
    double num(const char* k, double dflt = 0) const {
        auto it = o.find(k);
        return (it != o.end() && it->second.t == T::Num) ? it->second.n : dflt;
    }
    long long i64(const char* k, long long dflt = 0) const {
        auto it = o.find(k);
        return (it != o.end() && it->second.t == T::Num)
                   ? static_cast<long long>(it->second.n) : dflt;
    }
    std::string str(const char* k, const char* dflt = "") const {
        auto it = o.find(k);
        return (it != o.end() && it->second.t == T::Str) ? it->second.s : std::string(dflt);
    }
    bool boolean(const char* k, bool dflt = false) const {
        auto it = o.find(k);
        return (it != o.end() && it->second.t == T::Bool) ? it->second.b : dflt;
    }
    // Presence and type are asked separately from value. The protocol layer has to be
    // able to REJECT a missing or wrong-typed field, because substituting a default
    // silently changes which structure gets solved — and the reply still says ok.
    bool has(const char* k) const { return o.find(k) != o.end(); }
    bool isFiniteNum(const char* k) const {
        auto it = o.find(k);
        return it != o.end() && it->second.t == T::Num && std::isfinite(it->second.n);
    }
    bool isStr(const char* k) const {
        auto it = o.find(k);
        return it != o.end() && it->second.t == T::Str;
    }
    bool isBool(const char* k) const {
        auto it = o.find(k);
        return it != o.end() && it->second.t == T::Bool;
    }
    bool isArr(const char* k) const {
        auto it = o.find(k);
        return it != o.end() && it->second.t == T::Arr;
    }
    // Present, numeric, and written as a plain integer that fits int64 — the only
    // form a revision or byte count may take on the wire (#29).
    bool isExactInt(const char* k) const {
        auto it = o.find(k);
        return it != o.end() && it->second.t == T::Num && it->second.isInt;
    }
    long long exactI64(const char* k) const {
        auto it = o.find(k);
        return (it != o.end() && it->second.t == T::Num && it->second.isInt) ? it->second.i : 0;
    }

    const Array& arr(const char* k) const {
        static const Array kEmpty;
        auto it = o.find(k);
        return (it != o.end() && it->second.t == T::Arr) ? it->second.a : kEmpty;
    }
};

// ---------------------------------------------------------------- parser
class Parser {
public:
    explicit Parser(const std::string& src) : s_(src) {}
    // Trailing tokens are a parse error (#30): accepting a valid prefix of a line
    // means "{...}garbage" silently succeeds, and a framing bug upstream is then
    // indistinguishable from a well-formed request.
    Value parse() {
        skip();
        Value v = value();
        skip();
        if (p_ != s_.size()) ok_ = false;
        return ok_ ? v : Value{};
    }

private:
    const std::string& s_;
    size_t p_ = 0;
    bool   ok_ = true;

    void skip() { while (p_ < s_.size() && (unsigned char)s_[p_] <= ' ') ++p_; }
    bool eat(char c) { skip(); if (p_ < s_.size() && s_[p_] == c) { ++p_; return true; } return false; }
    bool lit(const char* w) {
        skip();
        size_t n = std::string(w).size();
        if (s_.compare(p_, n, w) == 0) { p_ += n; return true; }
        return false;
    }

    Value value() {
        skip();
        if (p_ >= s_.size()) { ok_ = false; return {}; }
        switch (s_[p_]) {
            case '{': return object();
            case '[': return array();
            case '"': { Value v; v.t = Value::T::Str; v.s = string(); return v; }
            case 't': if (lit("true"))  { Value v; v.t = Value::T::Bool; v.b = true;  return v; } break;
            case 'f': if (lit("false")) { Value v; v.t = Value::T::Bool; v.b = false; return v; } break;
            case 'n': if (lit("null"))  { return Value{}; } break;
            default: break;
        }
        return number();
    }

    Value object() {
        Value v; v.t = Value::T::Obj;
        if (!eat('{')) { ok_ = false; return v; }
        skip();
        if (eat('}')) return v;
        for (;;) {
            skip();
            if (p_ >= s_.size() || s_[p_] != '"') { ok_ = false; return v; }
            std::string key = string();
            if (!eat(':')) { ok_ = false; return v; }
            v.o[key] = value();
            if (!ok_) return v;
            if (eat(',')) continue;
            if (eat('}')) return v;
            ok_ = false; return v;
        }
    }

    Value array() {
        Value v; v.t = Value::T::Arr;
        if (!eat('[')) { ok_ = false; return v; }
        skip();
        if (eat(']')) return v;
        for (;;) {
            v.a.push_back(value());
            if (!ok_) return v;
            if (eat(',')) continue;
            if (eat(']')) return v;
            ok_ = false; return v;
        }
    }

    std::string string() {
        std::string out;
        if (!eat('"')) { ok_ = false; return out; }
        while (p_ < s_.size()) {
            char c = s_[p_++];
            if (c == '"') return out;
            if (c == '\\' && p_ < s_.size()) {
                char e = s_[p_++];
                switch (e) {
                    case 'n':  out += '\n'; break;
                    case 't':  out += '\t'; break;
                    case 'r':  out += '\r'; break;
                    case 'b':  out += '\b'; break;
                    case 'f':  out += '\f'; break;
                    case '"':  out += '"';  break;
                    case '\\': out += '\\'; break;
                    case '/':  out += '/';  break;
                    case 'u':
                        // Not used by this protocol; the four hex digits are still
                        // REQUIRED to be there and to be hex, or the escape is a
                        // parse error rather than a silently swallowed prefix.
                        if (p_ + 4 > s_.size()) { ok_ = false; return out; }
                        for (int h = 0; h < 4; ++h) {
                            if (!std::isxdigit((unsigned char)s_[p_ + h])) { ok_ = false; return out; }
                        }
                        p_ += 4;
                        out += '?';
                        break;
                    default:
                        // "\x" and friends are not JSON (#30). Interpreting them as
                        // the literal character accepts input no other parser would.
                        ok_ = false;
                        return out;
                }
            } else {
                out += c;
            }
        }
        ok_ = false;
        return out;
    }

    Value number() {
        skip();
        size_t start = p_;
        if (p_ < s_.size() && (s_[p_] == '-' || s_[p_] == '+')) ++p_;
        bool any = false, plain = true;   // plain: digits only after the sign
        while (p_ < s_.size() && (std::isdigit((unsigned char)s_[p_]) || s_[p_] == '.' ||
                                  s_[p_] == 'e' || s_[p_] == 'E' || s_[p_] == '-' || s_[p_] == '+')) {
            if (!std::isdigit((unsigned char)s_[p_])) plain = false;
            any = true; ++p_;
        }
        if (!any) { ok_ = false; return {}; }
        const std::string lit = s_.substr(start, p_ - start);
        // strtod must consume the WHOLE literal (#30): "1.2.3" and "1e" parse as a
        // prefix plus garbage, and a number that is half garbage is not a number.
        char* end = nullptr;
        Value v; v.t = Value::T::Num;
        v.n = std::strtod(lit.c_str(), &end);
        if (end == nullptr || *end != '\0') { ok_ = false; return {}; }
        // Exact-integer sidecar (#29): a plain integer literal that fits int64 is
        // kept as one, so revision survives above 2^53 where the double cannot.
        if (plain) {
            errno = 0;
            char* iend = nullptr;
            const long long iv = std::strtoll(lit.c_str(), &iend, 10);
            if (errno == 0 && iend != nullptr && *iend == '\0') {
                v.i = iv;
                v.isInt = true;
            }
        }
        return v;
    }
};

inline Value parse(const std::string& src) { return Parser(src).parse(); }

// ---------------------------------------------------------------- writer
class Writer {
public:
    Writer& beginObj()  { sep(); os_ << '{'; first_.push_back(true); return *this; }
    Writer& endObj()    { os_ << '}'; first_.pop_back(); mark(); return *this; }
    Writer& beginArr()  { sep(); os_ << '['; first_.push_back(true); return *this; }
    Writer& endArr()    { os_ << ']'; first_.pop_back(); mark(); return *this; }

    Writer& key(const char* k) { sep(); os_ << '"' << k << "\":"; suppressSep_ = true; return *this; }

    Writer& val(bool b)               { sep(); os_ << (b ? "true" : "false"); mark(); return *this; }
    Writer& val(long long i)          { sep(); os_ << i; mark(); return *this; }
    Writer& val(int i)                { return val(static_cast<long long>(i)); }
    Writer& val(const std::string& s) { sep(); os_ << '"' << esc(s) << '"'; mark(); return *this; }
    Writer& val(const char* s)        { return val(std::string(s)); }
    // Non-finite doubles are written as null: JSON has no NaN/Inf, and silently
    // emitting a bare `nan` token would break the Java side's parser.
    Writer& val(double d) {
        sep();
        if (!std::isfinite(d)) os_ << "null";
        else {
            // 17 significant digits: the smallest precision that round-trips every
            // IEEE-754 double exactly. The previous value, 10, silently truncated
            // every number on the wire to ~1e-10 relative — an adapter that alters
            // values in transit, which is the one thing a transport must never do.
            // (The shared-memory transport sidesteps text entirely; this floor is
            // for the JSON path, which remains the fallback and the debug surface.)
            std::ostringstream t; t.precision(17); t << d; os_ << t.str();
        }
        mark();
        return *this;
    }

    template <class V> Writer& kv(const char* k, V v) { key(k); return val(v); }

    std::string done() const { return os_.str(); }

private:
    std::ostringstream os_;
    std::vector<bool>  first_;
    bool               suppressSep_ = false;

    static std::string esc(const std::string& in) {
        std::string out;
        for (char c : in) {
            switch (c) {
                case '"':  out += "\\\""; break;
                case '\\': out += "\\\\"; break;
                case '\n': out += "\\n";  break;
                case '\r': out += "\\r";  break;
                case '\t': out += "\\t";  break;
                default:
                    if ((unsigned char)c < 0x20) out += ' ';
                    else out += c;
            }
        }
        return out;
    }
    void sep() {
        if (suppressSep_) { suppressSep_ = false; return; }
        if (!first_.empty()) {
            if (!first_.back()) os_ << ',';
            first_.back() = false;
        }
    }
    void mark() { if (!first_.empty()) first_.back() = false; }
};

}  // namespace bjson
