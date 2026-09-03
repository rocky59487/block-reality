// bsi_host.h -- public C++17 API of the shared BSI host (contract artifact).
//
// The host owns everything an engine must not care about: transport framing,
// schema validation, canonical ordering, vocabulary typing, the zero-copy arena,
// result packing and error-code mapping (BSI.md Part C). An engine implements
// the bsi_engine_vtable and nothing else.
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
#include "../bsi_engine.h"
#include "bsi_json.hpp"

namespace bsi {

struct HostOptions {
    int  logLevel = 0;          // 0 silent .. 3 chatty (stderr only; never stdout)
    bool probe = false;         // --assume-caps harvest mode: capability gate off. NEVER a claim.
    std::vector<std::string> assumedCaps;   // capabilities to act as if declared (probe only)
    std::string clientName = "bsi-host";
    // This session's default thread count (schema x-capi.openOptions.numThreads,
    // 1..256). A solve's body.numThreads overrides it; 0 means "engine's choice",
    // which is what an omitted key gives.
    uint32_t numThreads = 0;
};

// A loaded engine (shared library or statically linked entry point).
struct Engine {
    const bsi_engine_vtable* vt = nullptr;
    void* dl = nullptr;                       // dlopen handle when loaded from a library
    std::string name, version, buildSha;
    std::vector<std::string> capabilities;   // as declared by the engine (raw)
    bool loaded() const { return vt != nullptr; }
};

// Load by path (dlopen / LoadLibrary) or from a static entry point. On failure
// `err` names the reason (ABI mismatch, missing symbol, ...). Never throws.
bool loadEngineLibrary(const std::string& path, Engine& out, std::string& err);
bool loadEngineEntry(const bsi_engine_vtable* (*entry)(uint32_t), Engine& out, std::string& err);
void unloadEngine(Engine& e);

// One reply as the transports see it: header text + payload bytes.
struct Reply {
    std::string header;
    std::vector<uint8_t> payload;
    bool error = false;
};

class SessionImpl;

// One BSI session = one engine instance + one protocol state machine
// (hello -> vocab -> world -> solve...). Calls on a session are serialised by
// the caller (transports are single-threaded per session).
class Session {
public:
    Session(Engine& engine, const HostOptions& opts);
    ~Session();
    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;

    // Dispatch one request. `header` is the request header JSON text; the
    // payload is the request's binary payload (world records, loads, ...).
    // Always produces exactly one reply (response or error frame content).
    void handle(const std::string& header, const uint8_t* payload, size_t payloadLen, Reply& out);

    // Transport-level helpers.
    bool poisoned() const;          // BSI_VERSION was answered: everything after is refused
    const HostOptions& options() const;
    const Engine& engine() const;

private:
    std::unique_ptr<SessionImpl> impl_;
};

// The contract hash this host was built against (from CONTRACT_SHA256 at build time).
const char* contractSha256();

// Error-code mapping bsi_status -> wire token (BSI.md B.7 / bsi_engine.h).
const char* statusToken(int bsiStatus);

// Version string of the host library itself (informational).
const char* hostVersion();

}  // namespace bsi
