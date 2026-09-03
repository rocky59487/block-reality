// bsi_capi.cpp -- the in-process C ABI (contract/bsi_capi.h) over one Session.
#define BSI_CAPI_BUILD 1
#include "../bsi_capi.h"
#include "bsi_frame.hpp"
#include "bsi_host.h"
#include <cstring>
#include <memory>
#include <mutex>

// The engine this library carries. A shared-library build defines
// BSI_HOST_STATIC_ENGINE and links an engine object that exports bsi_engine_entry;
// the generic host (bsi-hostd) does not use this file.
extern "C" const bsi_engine_vtable* bsi_engine_entry(uint32_t);

namespace {

struct Handle {
    bsi::Engine engine;
    std::unique_ptr<bsi::Session> session;
    std::string lastError;
    std::mutex mu;
};

bsi::Engine* sharedEngine(std::string& err) {
    static bsi::Engine eng;
    static bool tried = false, ok = false;
    static std::mutex mu;
    std::lock_guard<std::mutex> g(mu);
    if (!tried) { tried = true; ok = bsi::loadEngineEntry(&bsi_engine_entry, eng, err); }
    if (!ok) { if (err.empty()) err = "engine entry refused"; return nullptr; }
    return &eng;
}

}  // namespace

extern "C" {

BSI_CAPI uint32_t bsi_capi_abi_version(void) { return BSI_CAPI_ABI; }

BSI_CAPI void* bsi_capi_open(const char* optionsJson) {
    std::string err;
    bsi::Engine* eng = sharedEngine(err);
    if (!eng) return nullptr;
    bsi::HostOptions opts;
    opts.clientName = "bsi_capi";
    if (optionsJson && *optionsJson) {
        bsi::json::Value v;
        if (bsi::json::parse(optionsJson, std::strlen(optionsJson), v) && v.isObj()) {
            if (const bsi::json::Value* l = v.find("log")) if (l->isInt) opts.logLevel = (int)l->i64;
            if (const bsi::json::Value* p = v.find("probe")) if (p->isBool()) opts.probe = p->b;
            if (const bsi::json::Value* a = v.find("assumeCaps")) if (a->isArr()) for (const auto& c : a->arr) if (c.isStr()) opts.assumedCaps.push_back(c.str);
        }
    }
    Handle* h = new (std::nothrow) Handle();
    if (!h) return nullptr;
    h->session.reset(new (std::nothrow) bsi::Session(*eng, opts));
    if (!h->session) { delete h; return nullptr; }
    return h;
}

BSI_CAPI int bsi_capi_call(void* hv, const uint8_t* req, size_t reqLen, uint8_t* out, size_t outCap, size_t* outLen, size_t* outNeeded) {
    Handle* h = (Handle*)hv;
    if (!h || !h->session) return BSI_CAPI_INVALID;
    std::lock_guard<std::mutex> g(h->mu);
    bsi::frame::View in;
    if (!req || !bsi::frame::decode(req, reqLen, in)) { h->lastError = "malformed request frame"; return BSI_CAPI_PROTOCOL; }
    bsi::Reply reply;
    h->session->handle(in.headerStr(), in.payload, in.payloadLen, reply);
    size_t need = bsi::frame::encodedSize(reply.header.size(), reply.payload.size());
    if (outNeeded) *outNeeded = need;
    if (!out || outCap < need) { h->lastError = "reply needs " + std::to_string(need) + " bytes"; return BSI_CAPI_NEED_BIGGER; }
    uint16_t flags = bsi::frame::kFlagEndOfResponse;
    if (!reply.payload.empty()) flags |= bsi::frame::kFlagHasPayload | bsi::frame::kFlagBinaryPayload;
    bsi::frame::encodeInto(out, flags, reply.header, reply.payload.data(), reply.payload.size());
    if (outLen) *outLen = need;
    h->lastError.clear();
    return BSI_CAPI_OK;
}

BSI_CAPI void bsi_capi_close(void* hv) { delete (Handle*)hv; }

BSI_CAPI const char* bsi_capi_last_error(void* hv) {
    Handle* h = (Handle*)hv;
    if (!h || h->lastError.empty()) return nullptr;
    return h->lastError.c_str();
}

}  // extern "C"
