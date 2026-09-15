# STATE_DELIVERY — 2026-09-10

Channel 11 carries every result and notice with dimension, ephemeral manager source UUID,
connection-wide sequence and world revision. A bootstrap binds the source; wrong dimensions,
late updates and revision rollback are rejected before the receiver changes state. Login,
respawn and travel reuse the cached result. EMPTY/OFF/refusal clear the visual state; PENDING
retains an explicitly stale result. None of these identities is a persistent Artifact ID.

| Executed check | Result |
|---|---|
| Windows core full check | 367 registered / 340 PASS / 27 SKIP |
| Windows Forge full build | 92 registered / 91 PASS / 1 SKIP |
| Combined Java | 459 registered / 431 PASS / 28 SKIP / 0 FAIL |
| Clock and envelope | 7 + 7 PASS; every-byte truncation, malformed notices/results and ordering |
| Three frozen mutations | each compiled, then failed its one named assertion |
| Linux packet suites with real native library | 10 PASS / 0 SKIP, including native result → envelope → decoder → clock |
| Isolated dedicated server | 16 status gates PASS; 6 probe runs / 24 synthetic player events PASS |
| RCON socket fixtures | 3 PASS; UTF-8 byte length, fragmentation, ordering and explicit disconnect |
| Document counts | 36 checked counts agree with the executed suites |

The server run starts OFF without locating/loading the library, enables through the real config,
runs the beam/column/slab/mixed-mechanism/critical-column cases, removes all structure without a
scan and observes EMPTY with no old command result, rebuilds, disables and enables again.
The event probe posts the registered Forge login/respawn/travel events with synthetic ServerPlayers,
captures the actual SimpleChannel payload at Connection.send, decodes it and applies the clock.
It confirms correct sources and unchanged cached result/revision across all four events per run.
It is an opt-in integration source and is absent from the normal jar.

The packet reserve remains 2,048 bytes within the unchanged 262,144-byte whole-message limit.
With a 256-character valid dimension and maximum sequence/world revision, the measured combined
result headers are 593–595 bytes. Even reserving another 128 bytes for all remaining scalar/count
varint growth fits. The dense fixture is 260,507 bytes; the longest multibyte notice is 1,065 bytes.
Controlling-element priority, cells, stations, samples and native verdict flags survive delivery.

The native library is the delivered #37 development artifact, source
`95a03e82bbc50c53eac97b5289b79d8e640f8075`, SHA256
`bbf38ff3d4633d4c9e0e7f42fb87e1e3183a6f9d7418ed88432b05392b36c121`, contract
`4b11cc738790d32d576ac71ecd1cb57f04d3ed9186a9e43f89492ae13c258801`.
The server logs its real path/hash/hello. No engine source, contract or pin changed.
The ordinary development jar is 412,925 bytes, SHA256
`ad0b906d31c7fae9f549fd0f5756df22d1084f238ea5eba1f8d2e7befb8d52f9`.
It contains the license/notice and new packet, and excludes the probe, retired notice packets,
executables and native libraries. It is **not** the self-contained release jar.

First failures are retained:

1. The first compile inferred Forge's Supplier overload from an expression lambda returning a
   package-private MessageHandler. A block lambda selects Runnable. This was not a mutation oracle.
2. The first packet test compared ShellDisplayField object identity. It now compares coordinates
   and both sample surfaces; the original failure XML is retained.
3. Server smoke `first` expected a nonexistent runtime enum OFF; the runtime actually says DISABLED
   while the analysis state says OFF. Both that state and the not-loaded diagnostic are required.
4. Smoke `second` lacked a Netty channel on the synthetic player; Forge's tier sync threw before
   the module listener. An EmbeddedChannel completes the fixture.
5. Smoke `third` passed the OFF event probe but the old RCON helper coalesced command and delimiter
   requests, which Minecraft 1.20.1 rejects. The helper now receives the first reply before sending
   the delimiter and throws on a closed connection instead of returning an empty success.
6. The run misleadingly named `final` missed the first file-watch notification and timed out OFF.
   A later explicit timestamp notification loaded the engine. The writer now flushes/fsyncs and
   updates the timestamp. The subsequent `fifth` run passed the complete fixed sequence; the
   missed-notification run remains a failure, and no general file-watcher reliability claim is made.
7. Parent #109 CI run 34383207699 attempts 1 and 2 stopped at a Chrome apt repository hash mismatch.
   The host job now uses existing cmake/g++ when present and prints versions; missing tools still
   require a successful install. All host/corpus gates remain mandatory. Parent failure is not PASS.

`counts.json` comes from JUnit XML; `receipts.json` records raw hashes and excerpt sources. Raw
server logs remain at their listed isolated paths; committed excerpts exclude configuration and
credentials. The final successful server stopped normally. Reproduce the opt-in event leg with
`gradlew -I ../scripts/state-delivery-probe.gradle runServer`, OFF in the isolated runtime-smoke
server config, then `game_runtime_smoke.py --check-readouts --check-state` with that server's
config/output arguments. Normal builds do not enable that source directory.

This is headless packet/state and real-server evidence. Real socket login/client clone order,
two-player interaction, visuals/textures, N25, FPS, persistent registry #86, lifecycle/rigid-body
consumption and zero legacy Java physics remain pending. No v1 or intermediate release is claimed.
