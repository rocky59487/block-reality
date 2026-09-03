#include "bsi_vocab.hpp"
#include <cmath>
#include <cstring>

namespace bsi {

static const char* kRoles[] = {"nonstructural", "member", "panel", "support", "monolith"};
static const char* kKinds[] = {"rect", "circle", "h", "box", "pipe", "rcrect", "custom"};

const char* roleName(int role) { return (role >= 0 && role < 5) ? kRoles[role] : "?"; }
const char* sectKindName(int kind) { return (kind >= 0 && kind < 7) ? kKinds[kind] : "?"; }

bsi_vocab VocabStore::view() const {
    bsi_vocab v{};
    v.version = version;
    v.materials = materials.empty() ? nullptr : materials.data();
    v.nMaterials = (uint32_t)materials.size();
    v.sections = sections.empty() ? nullptr : sections.data();
    v.nSections = (uint32_t)sections.size();
    v.attrKeys = attrKeyPtrs.empty() ? nullptr : attrKeyPtrs.data();
    v.nAttrKeys = (uint32_t)attrKeyPtrs.size();
    return v;
}

int VocabStore::materialId(const std::string& n) const {
    for (size_t k = 0; k < materialNames.size(); ++k) if (materialNames[k] == n) return (int)k;
    return -1;
}
int VocabStore::sectionId(const std::string& n) const {
    for (size_t k = 0; k < sectionNames.size(); ++k) if (sectionNames[k] == n) return (int)k;
    return -1;
}

namespace {

bool numOrVec3(const json::Value* v, double out[3], bool& isVec) {
    isVec = false;
    if (!v) return false;
    if (v->isNum()) { out[0] = out[1] = out[2] = v->num; return std::isfinite(v->num); }
    if (v->isArr() && v->arr.size() == 3) {
        for (int k = 0; k < 3; ++k) { if (!v->arr[k].isNum() || !std::isfinite(v->arr[k].num)) return false; out[k] = v->arr[k].num; }
        isVec = true;
        return true;
    }
    return false;
}

double numOr(const json::Value* o, const char* key, double def) {
    const json::Value* v = o ? o->find(key) : nullptr;
    return (v && v->isNum()) ? v->num : def;
}
bool hasNum(const json::Value* o, const char* key) {
    const json::Value* v = o ? o->find(key) : nullptr;
    return v && v->isNum();
}

int countExt(const json::Value& v) {
    int n = 0;
    if (v.isObj()) for (const auto& kv : v.obj) { if (kv.first.size() >= 2 && kv.first[0] == 'x' && kv.first[1] == '-') ++n; n += countExt(kv.second); }
    else if (v.isArr()) for (const auto& e : v.arr) n += countExt(e);
    return n;
}

std::string extJson(const json::Value& obj) {
    json::Value x; x.t = json::Value::T::Obj;
    for (const auto& kv : obj.obj) if (kv.first.size() >= 2 && kv.first[0] == 'x' && kv.first[1] == '-') x.obj.push_back(kv);
    if (x.obj.empty()) return "";
    std::string s; json::serialize(x, s); return s;
}

bool fail(VocabError& err, const char* code, const std::string& msg) { err.code = code; err.message = msg; return false; }

}  // namespace

bool buildVocab(const json::Value& body, const std::vector<std::string>* caps, VocabStore& out, VocabError& err) {
    out = VocabStore{};
    const json::Value* ver = body.find("version");
    if (!ver || !ver->isInt || ver->i64 < 1) return fail(err, "VOCAB_INVALID", "version");
    out.version = (uint32_t)ver->i64;
    out.ignoredExtensions = countExt(body);

    // sections first: materials refer to them by name
    const json::Value* secs = body.find("sections");
    if (secs && secs->isArr()) {
        for (const auto& s : secs->arr) {
            const json::Value* name = s.find("name");
            const json::Value* kind = s.find("kind");
            if (!name || !name->isStr() || name->str.empty()) return fail(err, "VOCAB_INVALID", "section.name");
            if (out.sectionId(name->str) >= 0) return fail(err, "VOCAB_INVALID", "duplicate section " + name->str);
            if (!kind || !kind->isStr()) return fail(err, "VOCAB_INVALID", "section.kind");
            int kk = -1;
            for (int k = 0; k < 7; ++k) if (kind->str == kKinds[k]) kk = k;
            if (kk < 0) return fail(err, "VOCAB_INVALID", "section.kind " + kind->str);
            bsi_section sec{};
            sec.kind = (uint8_t)kk;
            static const int arity[] = {2, 1, 4, 3, 2, 4, 0};
            const json::Value* p = s.find("p");
            size_t np = (p && p->isArr()) ? p->arr.size() : 0;
            if (kk != BSI_SECT_CUSTOM) {
                if ((int)np != arity[kk]) return fail(err, "VOCAB_INVALID", "section " + name->str + ": p needs " + std::to_string(arity[kk]) + " values");
                for (size_t k = 0; k < np; ++k) {
                    if (!p->arr[k].isNum() || !std::isfinite(p->arr[k].num)) return fail(err, "VOCAB_INVALID", "section " + name->str + ": p[" + std::to_string(k) + "]");
                    sec.p[k] = p->arr[k].num;
                    // every dimension positive except rcrect lever/As which may be 0
                    if (sec.p[k] <= 0 && !(kk == BSI_SECT_RCRECT && k >= 2)) return fail(err, "VOCAB_INVALID", "section " + name->str + ": p[" + std::to_string(k) + "] must be > 0");
                }
            } else {
                static const char* req[] = {"A", "Iy", "Iz", "J", "cy", "cz", "Asy", "Asz", "Zy", "Zz"};
                double* dst[] = {&sec.A, &sec.Iy, &sec.Iz, &sec.J, &sec.cy, &sec.cz, &sec.Asy, &sec.Asz, &sec.Zy, &sec.Zz};
                for (int k = 0; k < 10; ++k) {
                    if (!hasNum(&s, req[k])) return fail(err, "VOCAB_INVALID", std::string("custom section ") + name->str + " needs " + req[k]);
                    *dst[k] = numOr(&s, req[k], 0);
                    if (!(*dst[k] > 0) || !std::isfinite(*dst[k])) return fail(err, "VOCAB_INVALID", std::string("custom section ") + name->str + ": " + req[k] + " must be > 0");
                }
                sec.principalAngle = numOr(&s, "principalAngle", 0);
                if (np) return fail(err, "VOCAB_INVALID", "custom section " + name->str + ": p must be absent");
            }
            out.sectionNames.push_back(name->str);
            out.sections.push_back(sec);
        }
    }
    for (size_t k = 0; k < out.sections.size(); ++k) out.sections[k].name = out.sectionNames[k].c_str();

    const json::Value* mats = body.find("materials");
    if (!mats || !mats->isArr() || mats->arr.empty()) return fail(err, "VOCAB_INVALID", "materials");
    for (const auto& m : mats->arr) {
        const json::Value* name = m.find("name");
        const json::Value* role = m.find("role");
        if (!name || !name->isStr() || name->str.empty()) return fail(err, "VOCAB_INVALID", "material.name");
        if (out.materialId(name->str) >= 0) return fail(err, "VOCAB_INVALID", "duplicate material " + name->str);
        if (!role || !role->isStr()) return fail(err, "VOCAB_INVALID", "material.role");
        int rr = -1;
        for (int k = 0; k < 5; ++k) if (role->str == kRoles[k]) rr = k;
        if (rr < 0) return fail(err, "VOCAB_INVALID", "material.role " + role->str);
        bsi_material mat{};
        mat.role = (uint8_t)rr;
        mat.defaultSection = -1;
        std::string vendorModel;
        const json::Value* model = m.find("model");
        std::string ms = (model && model->isStr()) ? model->str : "isotropic";
        if (ms == "isotropic") mat.model = BSI_MODEL_ISOTROPIC;
        else if (ms == "orthotropic") mat.model = BSI_MODEL_ORTHOTROPIC;
        else if (ms == "composite_rc") mat.model = BSI_MODEL_COMPOSITE_RC;
        else if (ms == "rope") mat.model = BSI_MODEL_ROPE;
        else if (ms.size() > 2 && ms[0] == 'x' && ms[1] == '-') {
            mat.model = BSI_MODEL_VENDOR; vendorModel = ms;
            if (caps) {
                std::string vendor = ms.substr(0, ms.find(':'));      // "x-vendor"
                std::string need = "bsi.material." + vendor;
                bool have = false;
                for (const auto& c : *caps) if (c == need) have = true;
                if (!have) return fail(err, "UNSUPPORTED", "material model " + ms + " needs capability " + need);
            }
        } else return fail(err, "VOCAB_INVALID", "material.model " + ms);

        const bool mech = rr == BSI_ROLE_MEMBER || rr == BSI_ROLE_PANEL || rr == BSI_ROLE_MONOLITH;
        if (mech) {
            double E[3], G[3], nu[3]; bool vE = false, vG = false, vNu = false;
            bool hasE = numOrVec3(m.find("E"), E, vE);
            bool hasG = numOrVec3(m.find("G"), G, vG);
            bool hasNu = numOrVec3(m.find("nu"), nu, vNu);
            if (!hasE) return fail(err, "VOCAB_INVALID", name->str + ": E");
            if (!hasG && !hasNu) return fail(err, "VOCAB_INVALID", name->str + ": nu or G");
            if (mat.model == BSI_MODEL_ORTHOTROPIC) {
                if (!(vE && hasG && vG && hasNu && vNu)) return fail(err, "VOCAB_INVALID", name->str + ": orthotropic needs E[3], G[3], nu[3]");
            } else if (vE || vG || vNu) return fail(err, "VOCAB_INVALID", name->str + ": vector moduli only for orthotropic");
            for (int k = 0; k < 3; ++k) { mat.E[k] = E[k]; if (hasG) mat.G[k] = G[k]; if (hasNu) mat.nu[k] = nu[k]; }
            if (!hasG) for (int k = 0; k < 3; ++k) mat.G[k] = E[k] / (2.0 * (1.0 + nu[k]));   // the ONE derivation (README)
            for (int k = 0; k < 3; ++k) if (!(mat.E[k] > 0) || !(mat.G[k] > 0)) return fail(err, "VOCAB_INVALID", name->str + ": E,G must be > 0");
            if (!hasNum(&m, "rho") || !(numOr(&m, "rho", 0) > 0)) return fail(err, "VOCAB_INVALID", name->str + ": rho");
            mat.rho = numOr(&m, "rho", 0);
            const json::Value* allow = m.find("allow");
            double sC = numOr(allow, "sigmaC", 0), sT = numOr(allow, "sigmaT", 0), tau = numOr(allow, "tau", 0), sig = numOr(allow, "sigma", 0);
            if (sC > 0 && sT > 0 && tau > 0) { mat.sigmaAllowC = sC; mat.sigmaAllowT = sT; mat.tauAllowS = tau; }
            else if (mat.model == BSI_MODEL_ROPE && sT > 0) { mat.sigmaAllowT = sT; mat.sigmaAllow = sT; }
            else if (sig > 0) mat.sigmaAllow = sig;
            else return fail(err, "VOCAB_INVALID", name->str + ": allow needs sigmaC,sigmaT,tau or sigma");
            if (const json::Value* cap = m.find("capacity")) {
                mat.fc = numOr(cap, "fc", 0); mat.ft = numOr(cap, "ft", 0); mat.tauS = numOr(cap, "tauS", 0);
                mat.hasCapacity = (mat.fc > 0 && mat.ft > 0 && mat.tauS > 0) ? 1 : 0;
            }
            if (mat.model == BSI_MODEL_COMPOSITE_RC) {
                mat.Ec = numOr(&m, "Ec", 0); mat.rhoC = numOr(&m, "rhoC", 0);
                if (!(mat.Ec > 0) || !(mat.rhoC > 0)) return fail(err, "VOCAB_INVALID", name->str + ": composite_rc needs Ec, rhoC");
            }
            if (rr == BSI_ROLE_PANEL) {
                mat.shellThickness = numOr(&m, "shellThickness", 0);
                if (!(mat.shellThickness > 0)) return fail(err, "VOCAB_INVALID", name->str + ": panel needs shellThickness > 0");
            }
            if (const json::Value* ds = m.find("defaultSection")) {
                if (!ds->isStr()) return fail(err, "VOCAB_INVALID", name->str + ": defaultSection");
                mat.defaultSection = out.sectionId(ds->str);
                if (mat.defaultSection < 0) return fail(err, "VOCAB_INVALID", name->str + ": unknown defaultSection " + ds->str);
            }
            if (const json::Value* eb = m.find("eulerBernoulli")) mat.eulerBernoulli = (eb->isBool() && eb->b) ? 1 : 0;
            if (const json::Value* to = m.find("tensionOnly")) mat.tensionOnly = (to->isBool() && to->b) ? 1 : 0;
            if (mat.model == BSI_MODEL_ROPE) mat.tensionOnly = 1;
        } else if (rr == BSI_ROLE_SUPPORT) {
            const json::Value* sk = m.find("supportKind");
            if (!sk || !sk->isStr()) return fail(err, "VOCAB_INVALID", name->str + ": support needs supportKind");
            if (sk->str == "fixAll") mat.supportKind = BSI_SUPPORT_FIXALL;
            else if (sk->str == "translationOnly") mat.supportKind = BSI_SUPPORT_TRANSLATION_ONLY;
            else return fail(err, "VOCAB_INVALID", name->str + ": supportKind " + sk->str);
            if (const json::Value* t = m.find("temporary")) mat.temporary = (t->isBool() && t->b) ? 1 : 0;
        }
        out.materialNames.push_back(name->str);
        out.vendorModels.push_back(vendorModel);
        out.vendorJsons.push_back(extJson(m));
        out.materials.push_back(mat);
    }
    for (size_t k = 0; k < out.materials.size(); ++k) {
        out.materials[k].name = out.materialNames[k].c_str();
        out.materials[k].vendorModel = out.vendorModels[k].empty() ? nullptr : out.vendorModels[k].c_str();
        out.materials[k].vendorJson = out.vendorJsons[k].empty() ? nullptr : out.vendorJsons[k].c_str();
    }
    if (const json::Value* ak = body.find("attrKeys")) {
        if (ak->isArr()) for (const auto& k : ak->arr) {
            if (!k.isStr() || k.str.empty()) return fail(err, "VOCAB_INVALID", "attrKeys");
            out.attrKeys.push_back(k.str);
        }
        for (const auto& s : out.attrKeys) out.attrKeyPtrs.push_back(s.c_str());
    }
    out.built = true;
    return true;
}

}  // namespace bsi
