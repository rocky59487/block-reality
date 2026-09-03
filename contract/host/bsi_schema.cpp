#include "bsi_schema.hpp"
#include <cmath>
#include <mutex>
#include <set>

namespace bsi { namespace schema {

bool Schema::load(const char* text, size_t n) { return json::parse(text, n, root_) && root_.isObj(); }

const json::Value* Schema::resolveRef(const std::string& ref) const {
    // "#/$defs/name" or "#/x-enums/name"
    if (ref.size() < 3 || ref[0] != '#' || ref[1] != '/') return nullptr;
    std::string rest = ref.substr(2);
    const json::Value* cur = &root_;
    size_t pos = 0;
    while (pos <= rest.size()) {
        size_t slash = rest.find('/', pos);
        std::string seg = rest.substr(pos, slash == std::string::npos ? std::string::npos : slash - pos);
        cur = cur->find(seg);
        if (!cur) return nullptr;
        if (slash == std::string::npos) break;
        pos = slash + 1;
    }
    return cur;
}

bool Schema::inList(const char* key, const std::string& s) const {
    const json::Value* l = root_.find(key);
    if (!l || !l->isArr()) return false;
    for (const auto& e : l->arr) if (e.isStr() && e.str == s) return true;
    return false;
}

std::vector<std::string> Schema::enumValues(const std::string& name) const {
    std::vector<std::string> out;
    const json::Value* en = root_.find("x-enums");
    const json::Value* l = en ? en->find(name) : nullptr;
    if (l && l->isArr()) for (const auto& e : l->arr) if (e.isStr()) out.push_back(e.str);
    return out;
}

int Schema::enumIndex(const std::string& name, const std::string& token) const {
    auto v = enumValues(name);
    for (size_t k = 0; k < v.size(); ++k) if (v[k] == token) return (int)k;
    return -1;
}

long Schema::recordBytes(const std::string& section) const {
    const json::Value* recs = root_.find("x-records");
    if (!recs) return -1;
    std::string base = section; bool f32 = false;
    size_t c = base.find(':');
    if (c != std::string::npos) { f32 = base.substr(c + 1) == "f32"; base = base.substr(0, c); }
    const json::Value* r = recs->find(base);
    if (!r) return -1;
    const json::Value* b = r->find(f32 ? "x-f32" : "bytes");
    if (!b || !b->isInt) return -1;
    return (long)b->i64;
}

std::vector<std::string> Schema::propertyOrder(const std::string& def) const {
    std::vector<std::string> out;
    const json::Value* d = resolveRef("#/$defs/" + def);
    const json::Value* props = d ? d->find("properties") : nullptr;
    if (props && props->isObj()) for (const auto& kv : props->obj) out.push_back(kv.first);
    return out;
}

void Schema::orderBySchema(const std::string& def, json::Value& obj) const {
    if (!obj.isObj()) return;
    auto order = propertyOrder(def);
    std::vector<std::pair<std::string, json::Value>> sorted;
    std::vector<bool> used(obj.obj.size(), false);
    for (const auto& k : order)
        for (size_t i = 0; i < obj.obj.size(); ++i)
            if (!used[i] && obj.obj[i].first == k) { sorted.push_back(std::move(obj.obj[i])); used[i] = true; }
    for (size_t i = 0; i < obj.obj.size(); ++i) if (!used[i]) sorted.push_back(std::move(obj.obj[i]));
    obj.obj = std::move(sorted);
}

bool Schema::typeMatches(const std::string& type, const json::Value& v) {
    using T = json::Value::T;
    if (type == "object") return v.t == T::Obj;
    if (type == "array") return v.t == T::Arr;
    if (type == "string") return v.t == T::Str;
    if (type == "boolean") return v.t == T::Bool;
    if (type == "integer") return v.t == T::Num && v.isInt;
    if (type == "number") return v.t == T::Num;
    if (type == "null") return v.t == T::Null;
    return false;
}

bool Schema::valueEquals(const json::Value& a, const json::Value& b) {
    if (a.t != b.t) return false;
    switch (a.t) {
        case json::Value::T::Null: return true;
        case json::Value::T::Bool: return a.b == b.b;
        case json::Value::T::Num: return a.num == b.num;
        case json::Value::T::Str: return a.str == b.str;
        case json::Value::T::Arr:
            if (a.arr.size() != b.arr.size()) return false;
            for (size_t k = 0; k < a.arr.size(); ++k) if (!valueEquals(a.arr[k], b.arr[k])) return false;
            return true;
        case json::Value::T::Obj:
            if (a.obj.size() != b.obj.size()) return false;
            for (size_t k = 0; k < a.obj.size(); ++k) {
                const json::Value* o = b.find(a.obj[k].first);
                if (!o || !valueEquals(a.obj[k].second, *o)) return false;
            }
            return true;
    }
    return false;
}

bool Schema::patternMatches(const std::string& pattern, const std::string& s) {
    if (pattern == "^[0-9a-f]{64}$") {
        if (s.size() != 64) return false;
        for (char c : s) if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        return true;
    }
    if (pattern == "^[0-9]+\\.[0-9]+\\.[0-9]+$") {
        int parts = 0; size_t i = 0;
        while (i < s.size()) {
            size_t j = i;
            while (j < s.size() && s[j] >= '0' && s[j] <= '9') ++j;
            if (j == i) return false;
            ++parts;
            if (j == s.size()) break;
            if (s[j] != '.') return false;
            i = j + 1;
            if (i == s.size()) return false;
        }
        return parts == 3;
    }
    if (pattern == "^x-") return s.size() >= 2 && s[0] == 'x' && s[1] == '-';
    return false;   // unknown pattern: fail closed (a contract change must teach the host)
}

void Schema::checkNode(const json::Value& node, const json::Value& v, const std::string& path, Result& r) const {
    if (!node.isObj()) return;
    if (const json::Value* ref = node.find("$ref")) {
        const json::Value* target = ref->isStr() ? resolveRef(ref->str) : nullptr;
        if (!target) { r.ok = false; r.problems.push_back({path, "unresolvable $ref " + ref->str, false}); return; }
        if (target->isArr()) {          // #/x-enums/<name>
            bool hit = false;
            for (const auto& e : target->arr) if (valueEquals(e, v)) { hit = true; break; }
            if (!hit) { r.ok = false; r.problems.push_back({path, "not in enumeration", false}); }
            return;
        }
        checkNode(*target, v, path, r);
        return;
    }
    if (const json::Value* one = node.find("oneOf")) {
        int matches = 0;
        for (const auto& alt : one->arr) { Result sub; checkNode(alt, v, path, sub); if (sub.ok) ++matches; }
        if (matches != 1) { r.ok = false; r.problems.push_back({path, "oneOf: " + std::to_string(matches) + " alternatives match", false}); }
        return;
    }
    if (const json::Value* c = node.find("const")) {
        if (!valueEquals(*c, v)) { r.ok = false; r.problems.push_back({path, "const mismatch", false}); return; }
    }
    if (const json::Value* t = node.find("type")) {
        if (t->isStr() && !typeMatches(t->str, v)) { r.ok = false; r.problems.push_back({path, "expected " + t->str, false}); return; }
    }
    if (const json::Value* en = node.find("enum")) {
        bool hit = false;
        for (const auto& e : en->arr) if (valueEquals(e, v)) { hit = true; break; }
        if (!hit) { r.ok = false; r.problems.push_back({path, "not in enum", false}); return; }
    }
    if (v.isNum()) {
        if (const json::Value* m = node.find("minimum")) if (v.num < m->num) { r.ok = false; r.problems.push_back({path, "below minimum", false}); }
        if (const json::Value* m = node.find("maximum")) if (v.num > m->num) { r.ok = false; r.problems.push_back({path, "above maximum", false}); }
        if (const json::Value* m = node.find("exclusiveMinimum")) if (!(v.num > m->num)) { r.ok = false; r.problems.push_back({path, "not above exclusiveMinimum", false}); }
        if (!std::isfinite(v.num)) { r.ok = false; r.problems.push_back({path, "non-finite", false}); }
    }
    if (v.isStr()) {
        if (const json::Value* m = node.find("minLength")) if ((long)v.str.size() < m->i64) { r.ok = false; r.problems.push_back({path, "too short", false}); }
        if (const json::Value* m = node.find("maxLength")) if ((long)v.str.size() > m->i64) { r.ok = false; r.problems.push_back({path, "too long", false}); }
        if (const json::Value* p = node.find("pattern")) if (p->isStr() && !patternMatches(p->str, v.str)) { r.ok = false; r.problems.push_back({path, "pattern mismatch", false}); }
    }
    if (v.isArr()) {
        if (const json::Value* m = node.find("minItems")) if ((long)v.arr.size() < m->i64) { r.ok = false; r.problems.push_back({path, "too few items", false}); }
        if (const json::Value* m = node.find("maxItems")) if ((long)v.arr.size() > m->i64) { r.ok = false; r.problems.push_back({path, "too many items", false}); }
        if (const json::Value* u = node.find("uniqueItems")) if (u->isBool() && u->b) {
            for (size_t a = 0; a < v.arr.size(); ++a) for (size_t b = a + 1; b < v.arr.size(); ++b)
                if (valueEquals(v.arr[a], v.arr[b])) { r.ok = false; r.problems.push_back({path, "duplicate items", false}); }
        }
        if (const json::Value* it = node.find("items"))
            for (size_t k = 0; k < v.arr.size(); ++k) checkNode(*it, v.arr[k], path + "[" + std::to_string(k) + "]", r);
    }
    if (v.isObj()) {
        const json::Value* props = node.find("properties");
        const json::Value* req = node.find("required");
        const json::Value* addl = node.find("additionalProperties");
        const json::Value* pat = node.find("patternProperties");
        const bool closed = addl && addl->isBool() && !addl->b;
        if (req && req->isArr())
            for (const auto& k : req->arr)
                if (k.isStr() && !v.has(k.str.c_str())) { r.ok = false; r.problems.push_back({path + "." + k.str, "required", false}); }
        for (const auto& kv : v.obj) {
            const std::string& k = kv.first;
            const json::Value* sub = props ? props->find(k) : nullptr;
            if (sub) { checkNode(*sub, kv.second, path + "." + k, r); continue; }
            bool isExt = k.size() >= 2 && k[0] == 'x' && k[1] == '-';
            if (isExt) { ++r.ignoredExtensions; continue; }      // P6: x- keys ignored and counted
            (void)pat;
            if (closed) { r.ok = false; r.problems.push_back({path + "." + k, "unknown key", true}); }
        }
    }
}

Result Schema::validateNode(const json::Value& node, const json::Value& v, const std::string& path) const {
    Result r; checkNode(node, v, path, r); return r;
}

Result Schema::validate(const std::string& def, const json::Value& v) const {
    const json::Value* d = resolveRef("#/$defs/" + def);
    Result r;
    if (!d) { r.ok = false; r.problems.push_back({def, "no such $def", false}); return r; }
    checkNode(*d, v, def, r);
    return r;
}

const Schema& embedded() {
    static Schema s;
    static std::once_flag once;
    std::call_once(once, [] { s.load(kEmbeddedSchemaText, (size_t)kEmbeddedSchemaBytes); });
    return s;
}

}}  // namespace bsi::schema
