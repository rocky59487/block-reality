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

// bsi_capi_open has no handle to hang a message on when it refuses, so the
// reason lives here and bsi_capi_last_error(NULL) reads it. Thread-local: two
// threads opening at once must not overwrite each other's reason.
std::string& openError() {
    static thread_local std::string e;
    return e;
}

// x-capi.openOptions, enforced rather than assumed. Host configuration fails
// closed for the same reason the wire does (P6): a key the host has never heard
// of is a caller who believes something that is not true, and silently dropping
// it is how "numThreads had no effect" survived unnoticed on the consumer side.
bool parseOpenOptions(const char* optionsJson, bsi::HostOptions& opts, std::string& err) {
    if (!optionsJson || !*optionsJson) return true;
    bsi::json::Value v;
    if (!bsi::json::parse(optionsJson, std::strlen(optionsJson), v) || !v.isObj()) {
        err = "open options are not a JSON object";
        return false;
    }
    for (const auto& kv : v.obj) {
        const std::string& k = kv.first;
        const bsi::json::Value& val = kv.second;
        if (k.rfind("x-", 0) == 0) continue;                       // vendor extension: ignored
        if (k == "log") {
            if (!val.isInt || val.i64 < 0 || val.i64 > 3) { err = "open option 'log' must be an integer 0..3"; return false; }
            opts.logLevel = (int)val.i64;
        } else if (k == "numThreads") {
            if (!val.isInt || val.i64 < 1 || val.i64 > 256) { err = "open option 'numThreads' must be an integer 1..256"; return false; }
            opts.numThreads = (uint32_t)val.i64;
        } else if (k == "probe") {
            if (!val.isBool()) { err = "open option 'probe' must be a boolean"; return false; }
            opts.probe = val.b;
        } else if (k == "assumeCaps") {
            if (!val.isArr()) { err = "open option 'assumeCaps' must be an array of strings"; return false; }
            for (const auto& c : val.arr) {
                if (!c.isStr()) { err = "open option 'assumeCaps' must be an array of strings"; return false; }
                opts.assumedCaps.push_back(c.str);
            }
        } else {
            err = "unknown open option '" + k + "' (x-<vendor> keys are ignored; everything else is refused)";
            return false;
        }
    }
    return true;
}

}  // namespace

extern "C" {

BSI_CAPI uint32_t bsi_capi_abi_version(void) { return BSI_CAPI_ABI; }

BSI_CAPI void* bsi_capi_open(const char* optionsJson) {
    openError().clear();
    std::string err;
    bsi::Engine* eng = sharedEngine(err);
    if (!eng) { openError() = err; return nullptr; }
    bsi::HostOptions opts;
    opts.clientName = "bsi_capi";
    if (!parseOpenOptions(optionsJson, opts, openError())) return nullptr;
    Handle* h = new (std::nothrow) Handle();
    if (!h) { openError() = "out of memory"; return nullptr; }
    h->session.reset(new (std::nothrow) bsi::Session(*eng, opts));
    if (!h->session) { delete h; openError() = "out of memory"; return nullptr; }
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
    if (!h) return openError().empty() ? nullptr : openError().c_str();
    if (h->lastError.empty()) return nullptr;
    return h->lastError.c_str();
}

}  // extern "C"
