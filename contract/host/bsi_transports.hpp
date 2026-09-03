// bsi_transports.hpp -- the three transports of BSI.md Part D, all driving the
// same Session (transport layer has zero semantics; C-2 asserts it).
#pragma once
#include <cstdio>
#include <string>
#include "bsi_host.h"

namespace bsi { namespace transport {

// T-B': one request line = header JSON (+ payloadB64); one reply line.
int runStdioB64(Session& s, FILE* in, FILE* out);
// T-A over stdio: raw frames in, raw frames out.
int runFrameStdio(Session& s, FILE* in, FILE* out);
// T-B: doorbell lines over stdio, world/loads/req/reply in the mapped arena file.
int runArena(Session& s, const std::string& arenaPath, FILE* in, FILE* out);

}}  // namespace bsi::transport
