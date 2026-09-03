#include "bsi_host.h"
#include "bsi_schema.hpp"
#include <cstdio>
#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace bsi {

static bool adopt(const bsi_engine_vtable* vt, void* dl, Engine& out, std::string& err) {
    if (!vt) { err = "bsi_engine_entry returned NULL (host ABI " + std::to_string(BSI_ENGINE_ABI) + " not supported)"; return false; }
    if (vt->abi_version != BSI_ENGINE_ABI) { err = "engine abi_version " + std::to_string(vt->abi_version) + " != host " + std::to_string(BSI_ENGINE_ABI); return false; }
    if (!vt->name || !vt->version || !vt->build_sha || !vt->capabilities || !vt->open || !vt->close || !vt->vocab || !vt->world_declare || !vt->solve) {
        err = "engine vtable has a NULL mandatory slot"; return false;
    }
    out.vt = vt; out.dl = dl;
    out.name = vt->name() ? vt->name() : "";
    out.version = vt->version() ? vt->version() : "";
    out.buildSha = vt->build_sha() ? vt->build_sha() : "";
    const char* const* caps = nullptr;
    uint32_t n = vt->capabilities(&caps);
    out.capabilities.clear();
    for (uint32_t k = 0; k < n && caps; ++k) if (caps[k]) out.capabilities.push_back(caps[k]);
    return true;
}

bool loadEngineEntry(const bsi_engine_vtable* (*entry)(uint32_t), Engine& out, std::string& err) {
    if (!entry) { err = "no entry point"; return false; }
    return adopt(entry(BSI_ENGINE_ABI), nullptr, out, err);
}

bool loadEngineLibrary(const std::string& path, Engine& out, std::string& err) {
#ifdef _WIN32
    HMODULE h = LoadLibraryA(path.c_str());
    if (!h) { err = "LoadLibrary failed: " + path; return false; }
    auto entry = (const bsi_engine_vtable* (*)(uint32_t))GetProcAddress(h, "bsi_engine_entry");
    if (!entry) { FreeLibrary(h); err = "bsi_engine_entry not exported by " + path; return false; }
    if (!adopt(entry(BSI_ENGINE_ABI), (void*)h, out, err)) { FreeLibrary(h); return false; }
    return true;
#else
    void* h = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!h) { const char* e = dlerror(); err = std::string("dlopen failed: ") + (e ? e : path.c_str()); return false; }
    auto entry = (const bsi_engine_vtable* (*)(uint32_t))dlsym(h, "bsi_engine_entry");
    if (!entry) { dlclose(h); err = "bsi_engine_entry not exported by " + path; return false; }
    if (!adopt(entry(BSI_ENGINE_ABI), h, out, err)) { dlclose(h); return false; }
    return true;
#endif
}

void unloadEngine(Engine& e) {
    if (e.dl) {
#ifdef _WIN32
        FreeLibrary((HMODULE)e.dl);
#else
        dlclose(e.dl);
#endif
    }
    e = Engine{};
}

const char* contractSha256() { return schema::kEmbeddedContractSha256; }
const char* hostVersion() { return "bsi-host/1.0.0"; }

const char* statusToken(int s) {
    switch (s) {
        case BSI_E_PROTOCOL: return "PROTOCOL_ERROR";
        case BSI_E_UNSUPPORTED: return "UNSUPPORTED";
        case BSI_E_UNSUPPORTED_ATTR: return "UNSUPPORTED_ATTR";
        case BSI_E_VOCAB: return "VOCAB_INVALID";
        case BSI_E_NO_WORLD: return "NO_WORLD";
        case BSI_E_EMPTY_WORLD: return "EMPTY_WORLD";
        case BSI_E_EXTRACT: return "EXTRACT_FAILED";
        case BSI_E_LOAD_TARGET: return "LOAD_TARGET";
        case BSI_E_LOAD_UNSUPPORTED: return "LOAD_UNSUPPORTED";
        case BSI_E_SOLVE: return "SOLVE_FAILED";
        case BSI_E_OOM: return "OUT_OF_MEMORY";
        case BSI_E_CANCELLED: return "CANCELLED";
        case BSI_E_BUDGET: return "BUDGET_EXCEEDED";
        default: return "INTERNAL";
    }
}

}  // namespace bsi
