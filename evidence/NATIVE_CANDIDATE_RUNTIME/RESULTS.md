# NATIVE_CANDIDATE_RUNTIME — 2026-09-10

The current module retains HUD #120, the native GAME_RUNTIME, channel 11, the native-only
shipping boundary, construction identity and registry/save improvements while consuming
the delivered #119 / engine #40 native candidate. No engine source was changed, compiled,
merged or released. Frozen criteria: `d96d98a`; qualified module source: `c2a1b94`.
The later #119 `10146b5` integration only adds delivery documentation to this branch;
its #118-based jar is a different artifact and its qualification is not reused here.

| Executed gate | Result |
|---|---|
| Windows full core / Forge | 400 / 109 PASS; 12 / 0 SKIP; 521 registered, 0 failures |
| Linux full core / Forge | 411 / 109 PASS; 1 / 0 SKIP; 521 registered, 0 failures |
| Native-dependent tests | Executed on both delivered libraries; none skipped |
| Packet golden resources | Existing 12 cases pass in both full Forge suites |
| Release source / SDK / licenses / inventory | Both platform ZIPs and 5,045 source files accepted |
| Real archive corruption | 10 refused; 4 independent removed Python guards expose the rejection oracle |
| Distribution bundle | Accepted; all 9 injections refused |
| Production jar boundary | 204 classes accepted; 2 injected arms compile successfully, then are refused |
| Same jar on Windows and Linux | Each platform: 48 C5/C6/C8/C10 f64/f32 frames, 3 direct and 3 extracted repeats agree byte for byte |
| Extraction faults and concurrency | Each platform: real permission denial, cache repair/refusal, bad pin/identity, 2 concurrent JVMs pass |
| Real isolated Forge server | 16 status assertions and 6 probe calls / 24 synthetic player events pass |
| Real isolated Forge client | 45 assertions, 16 screenshots; clean exit after 165.836 seconds |

The 12 Windows skips are 11 legacy POSIX fake-process lifecycle cases and one legacy
unpacked-executable permission case. Linux skips the one Windows-only invalid-path-string
case. Exact names are in `counts.json` and the full JUnit XML. The historical test-only
sidecar executable is used by its existing regression suite, never packaged or used by
GAME_RUNTIME. Native and legacy checks are distinct; their totals are not added again.

## Artifact identity

* Jar: `blockreality-0.4.0-dev.jar`, **15,203,560 bytes**,
  SHA256 `5a93c66bfe5f5a3dd8c1104a6e97fe000a2a0d2a8e66bf602821993d2a4c9b79`.
* Delivered engine source: `42e10f5f7af166788588afcd2fb2fd97101d16dc`, version 1.3.0,
  buildSha `42e10f5`; contract
  `4b11cc738790d32d576ac71ecd1cb57f04d3ed9186a9e43f89492ae13c258801`.
* Root SHA256SUMS:
  `f587d8092e0798da4d1f9c8d0e7a4d1b806d64fbf5999d749622757181231cd5`.
* Windows DLL: 31,769,600 bytes,
  SHA256 `9761d73586277614a56132ec3492d324ae83c8489068c00c266c3d56f530acea`.
* Linux SO: 28,500,368 bytes,
  SHA256 `53aae7156b94c0a761ef5abb2322ad47a708362f417a50ca3f04105d9c50abf1`.

`provenance.json` and the jar retain the explicit **locally qualified candidate** basis.
Archive validation is separate from platform runtime hello. Same-platform direct/extracted
byte equality validates transport and repeatability, not independent physical correctness
or Windows/Linux byte equality. Engine-owner numerical qualification stays attributed to
its delivery record; this module work did not rerun engine suites.

The distributable directory is `build/native-candidate-runtime/distribution/` in the module
workspace. It contains this exact jar, LICENSE, NOTICE, third-party texts, candidate
provenance, installation/scope text and SHA256SUMS. It is not a formal release. Raw native
libraries, modified negative-test jars and the distributable jar are identified by hashes,
not committed as additional executable payloads in this evidence tree.

## Actual game observations

Both new servers log **source=BUNDLED** and the expected SO hash after extracting staged
resources from the development classpath. No BR_ENGINE, configured native path or platform
override supplies the game library. The separate jar-only gate above does load the actual
candidate jar on both platforms. **Development-classpath game runs do not qualify an
installed jar running inside Forge**, and the Linux renderer is llvmpipe, not an FPS result.

The render world has a grounded beam, column and panel before login: native revision 44,
2 members, 12 shell facets, maximum D/C `0.7597396436387274`. The actual client receives that
cached result without a structure edit. The coordinator checks the server's values, source
revision, framebuffer dimensions, language and lens mode for every screenshot. Nine
materials with X/Y/Z/undeclared states then produce a real MODEL_REFUSED result; the client
clears the old analysis surfaces and shows the native refusal. The current 44%-width HUD
keeps warning text within the panel. The catalogue still visibly uses full cubes; this is
evidence of the existing directional-material gap, not acceptance of completed materials.

The separate runtime world exercises support removal/restoration, mixed solved and
unrestrained structures, undeclared-axis refusal/recovery, session reset, a critical tall
column within an incompletely evaluated world, removing that warning, EMPTY, and live
OFF/INPROCESS. Local native critical flags remain visible without inventing a world factor.
Java does not reconstruct damage, factors or motion. The 24 synthetic events validate
packet/event delivery; the real client capture separately validates socket login.

Owned ports 25594–25597 are released after normal shutdown. The original Windows profile,
installed jar, security dialog and original CM server/world remain untouched.

## Retained failures and limits

1. `runtime-smoke-first` starts in a fresh empty world. The old state script expects an
   existing supported model after OFF is enabled. OFF passes, then the expectation fails
   correctly at EMPTY/IDLE. No native result exists to recover; this is a missing test
   precondition, not a library solve failure.
2. `runtime-second-setup` writes OFF without the explicit timestamp notification used by
   the established smoke writer. The watcher does not observe this write within 20 seconds;
   its command/status receipts are retained. The second smoke was also launched before
   this preparation had completed and fails at its OFF precondition. Neither run qualifies
   state delivery. No source behaviour was altered to conceal these failed attempts.
3. The distinct third preparation writes/fsyncs and calls `os.utime`, waits until OFF is
   observed, then seeds the supported model while OFF. Only after preparation exits 0 does
   the unchanged smoke run: all 16 assertions and 24 events pass. The old file-watcher
   reliability limitation remains; this is not a general hot-reload reliability claim.

Windows raw logs may retain their original encoding/BOM. `receipts.json` hashes the copied
raw outputs, XML, protocol frames and screenshots; no native response or screenshot is
edited. `module-c2a1b94-sources.json` fingerprints canonical Git bytes. Setup scripts and
first/second/third receipts remain separate. CI is recorded only for exact heads and its
native steps skipped without a token are never counted as local native qualification.
The first aggregate collector rejected a missing optional empty `skips` list; the replacement
parses XML and checks all recorded totals. Its first Windows invocation then rejected UTF-8
under the host CP950 default. Running that same collector with `python -X utf8` succeeds.
Both collection errors are retained separately; no raw test run or result was rewritten.

Installed Windows CM/N25, directional materials and placement interaction, construction
transactions, independent-region scheduling, FPS, and engine-driven fracture/crushing/
rigid-body lifecycle remain open. This candidate does not establish v1 completion or
distribution-platform approval.
