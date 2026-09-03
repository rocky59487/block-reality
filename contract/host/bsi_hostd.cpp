// bsi_hostd.cpp -- the generic host process (dev/CI arm, BSI.md Part D):
//   bsi-hostd --engine <lib|static> --transport stdio-b64|arena|frame [--arena <file>]
//             [--log-level n] [--assume-caps a,b] [--client name]
// Exit 0 on stdin EOF, 2 on usage / engine load failure / transport failure.
#include "bsi_host.h"
#include "bsi_transports.hpp"
#include <cstdio>
#include <cstring>
#include <string>

#ifdef BSI_HOST_STATIC_ENGINE
extern "C" const bsi_engine_vtable* bsi_engine_entry(uint32_t);
#endif

static int usage() {
    std::fprintf(stderr, "usage: bsi-hostd --engine <path|static> --transport stdio-b64|arena|frame [--arena <file>] [--log-level n] [--assume-caps a,b]\n");
    return 2;
}

int main(int argc, char** argv) {
    std::string enginePath, transport = "stdio-b64", arenaPath;
    bsi::HostOptions opts;
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        auto next = [&](std::string& dst) { if (i + 1 >= argc) return false; dst = argv[++i]; return true; };
        if (a == "--engine") { if (!next(enginePath)) return usage(); }
        else if (a == "--transport") { if (!next(transport)) return usage(); }
        else if (a == "--arena") { if (!next(arenaPath)) return usage(); }
        else if (a == "--log-level") { std::string v; if (!next(v)) return usage(); opts.logLevel = std::atoi(v.c_str()); }
        else if (a == "--client") { if (!next(opts.clientName)) return usage(); }
        else if (a == "--assume-caps") {
            std::string v; if (!next(v)) return usage();
            opts.probe = true;
            size_t pos = 0;
            while (pos <= v.size()) { size_t c = v.find(',', pos); std::string t = v.substr(pos, c == std::string::npos ? std::string::npos : c - pos); if (!t.empty()) opts.assumedCaps.push_back(t); if (c == std::string::npos) break; pos = c + 1; }
        }
        else if (a == "--version") { std::printf("%s contract %s\n", bsi::hostVersion(), bsi::contractSha256()); return 0; }
        else return usage();
    }
    if (enginePath.empty()) return usage();
    bsi::Engine engine;
    std::string err;
    bool ok;
    if (enginePath == "static") {
#ifdef BSI_HOST_STATIC_ENGINE
        ok = bsi::loadEngineEntry(&bsi_engine_entry, engine, err);
#else
        ok = false; err = "this bsi-hostd was built without a static engine";
#endif
    } else ok = bsi::loadEngineLibrary(enginePath, engine, err);
    if (!ok) { std::fprintf(stderr, "bsi-hostd: cannot load engine: %s\n", err.c_str()); return 2; }
    if (opts.probe) std::fprintf(stderr, "bsi-hostd: PROBE MODE -- capabilities assumed, nothing here is a capability claim\n");
    int rc;
    {
        bsi::Session session(engine, opts);
        if (transport == "stdio-b64") rc = bsi::transport::runStdioB64(session, stdin, stdout);
        else if (transport == "frame") rc = bsi::transport::runFrameStdio(session, stdin, stdout);
        else if (transport == "arena") { if (arenaPath.empty()) return usage(); rc = bsi::transport::runArena(session, arenaPath, stdin, stdout); }
        else return usage();
    }
    bsi::unloadEngine(engine);
    return rc;
}
