# MODULE_PIPELINE_PROFILE result

Criteria were committed as `5d9a078` before implementation, parent #114 / `472c3a2`.
Only the module changed. Native source, binary, contract and pins are unchanged. The client still
has a Windows security request in front of the game; no visual, material-model or N25 claim is made.

Operators can now use `/br profile start|stop|show` in the current dimension. The recorder defaults
OFF, keeps at most 256 durations per fixed stage and reports nearest-rank p50/p95/max with retained
and total observed counts. Stops preserve a frozen snapshot; restart clears it. Captured contexts
follow queued worker work and its delivery callback, so old work cannot enter a new session.
Disabled spans are shared no-ops and never consult the timer. Profiling does not resolve, edit,
reset or load the engine, and it is neither saved nor sent to clients.

Instrumentation covers actual manager tick, metadata capture/queue/reconcile/publish, gather
preparation/steps/final snapshot, analysis queue/worker, world mapping/encoding, BSI frame encoding,
actual JNA calls, reply copy/parsing, result decoding, packet construction, apply and dispatch.
JNA counters include actual request/reply bytes and buffer-growth responses. Native retry behavior,
result flags, mechanical samples and wire bytes are preserved. Nested stage times overlap.

| Gate | Evidence |
|---|---|
| MP-1 | Six tests cover OFF/no clock/shared spans, exact bounded rolling quantiles, immutable stop snapshots, old spans and queued-context rejection, zero/negative duration, idempotent close and 4000 concurrent completions |
| MP-2 | Real native profiled/unprofiled results agree, including supplied samples/flags; a larger 64-beam reply grows the real initial 64 KiB buffer and counts the retry; existing recovery/lifecycle tests and 12 packet goldens pass |
| MP-3 | Permission table keeps profile at operator level 2; real commands leave world/result revision and graph SHA unchanged; stop remains frozen while a later native resolve runs |
| MP-4 | Isolated real server: 14 functional gates pass, including OFF recorder, metadata edit costs, all 40 expected completions/stage, native byte counters and unchanged graph for each scene |
| MP-5 | Frozen A49/F576/M832 scenes each run 10 warmups and 40 measured sequential resolves; full command receipts, all attempts and stage reports in `server-first.json`, extracted values in `baseline.csv` |
| MP-6 | Full suites/build, 36 documentation count checks, ordinary jar audit and two compiled jar fault arms; compiled stopped-span mutation fails the named behavior test |

Windows core: **405 registered / 377 PASS / 28 SKIP**. Forge: **107 / 106 / 1**.
Combined: **512 / 483 PASS / 29 SKIP / 0 FAIL**. Seven new tests include the actual native profiling
test, which is skipped on Windows and executed on Linux. The explicit legacy sidecar argument
only runs old test compatibility; it does not put an executable back in the mod.

The Linux targeted run executes **18 tests / 18 PASS / 0 SKIP** across GameInputNativeTest,
InProcessRecoveryTest and NativeGameRuntimeTest; the last class uses controlled sessions for
lifecycle races. These are overlapping registered tests, not 18 additional tests in the total.
The larger native profiling test validates reply growth and result parity, not an independent
solver invocation counter. The existing counted BsiRetryGate source is unchanged; its counted
engine fixture was not available locally for a new run. Its CI leg may not be credited when skipped.

## Recorded baseline, not a performance qualification

Host: AMD Ryzen 9 8940HX, 16 cores/32 logical processors. Server: Ubuntu-22.04 WSL2,
Linux 6.6.87.2-microsoft-standard-WSL2 amd64, Ubuntu OpenJDK 17.0.20+8. Native threads=1,
eigen budget=2400, self weight enabled, minTicksBetweenSolves=1. No forced GC or sample selection.
The three scenes and their order are exactly those frozen in MODULE_PIPELINE_PROFILE.md.
The existing CLIENT_MATERIALS server/client remained live and were not changed; this is a shared
development host. These data are one baseline, without a hardware-isolated repeatability claim.

All following times are p95 in milliseconds of each stage's own retained observations:

| Scene | Gather step | Analysis worker | JNA call, includes engine | Result decode | Packet build | Apply |
|---|---:|---:|---:|---:|---:|---:|
| A49, 1 beam | 0.214 | 1.520 | 0.248 | 0.658 | 0.289 | 0.322 |
| F576, 64 beams | 0.492 | 5.493 | 3.183 | 0.981 | 0.438 | 0.478 |
| M832, 64 beams + 256 facets | 2.790 | 65.627 | 48.566 | 5.838 | 1.543 | 1.611 |

Each scene has 40 worker/result/apply samples and 80 native-call samples: world declaration and
solve are separate native calls. Manager ticks also include idle/waiting ticks, so their quantiles
are a different population. Do not add stage percentiles or subtract them to infer engine CPU time.
The dominant observed worker cost in M832 is the inclusive native call. No engine optimization is
performed here, and no general module speedup is claimed from adding instrumentation.

| Scene | Delivered result bytes | Budget omission | GC during measurement | Heap used before -> after |
|---|---:|---|---|---:|
| A49 | 11695 | none | 0 collections | 1287091024 -> 1341616976 B |
| F576 | 185852 | none | 1 collection / 9 ms | 1507291984 -> 853698048 B |
| M832 | 259753 | explicit truncation | 0 collections | 966944256 -> 1271031296 B |

Heap deltas include the full server, warm process state and RCON/probe allocations; they are not
per-request allocation rates. The mixed scene's complete native result has 64 members and 256
facets, while the bounded display packet omits whole elements explicitly. Result readiness gates
do not pretend the packet contains all elements. No players were connected: NETWORK_DISPATCH
measures envelope/broadcast work with zero recipients, not real socket or client decode cost.
The probe's full packet encoding and graph fingerprint happen outside the manager timing spans.

## Artifact, provenance and first runs

The ordinary jar is **453741 bytes**, SHA-256
`a7d78d33604ca9d382aa9dbc27498e86c374d904870d172e4f36cc490c7398ef`.
All 197 classes pass the existing entry/constant-pool audit. No native library, executable, test
probe or retired mechanics/codec fixture is bundled. Both compiled artifact fault arms are rejected.
It remains a development jar, not an offline native release.

The isolated server ran at `/home/rocky/br-module-pipeline`, world `pipeline-smoke`, loopback ports
25592/25593, and saved/stopped after measurement. Raw RCON receipts retain every attempted resolve;
server logs here are explicit excerpts. Startup records the JVM/kernel and loaded library hash.
Native input remains delivered #37 `95a03e82bbc50c53eac97b5289b79d8e640f8075`, library
`bbf38ff3d4633d4c9e0e7f42fb87e1e3183a6f9d7418ed88432b05392b36c121`, contract
`4b11cc738790d32d576ac71ecd1cb57f04d3ed9186a9e43f89492ae13c258801`.

First compile, core tests and server driver passed; no failed runtime scenario was discarded.
The recorder fault arm intentionally removes the stopped-session check; it compiles, reaches
`stopAndRestartRejectOldWorkAndPreserveImmutableStoppedSnapshot` and fails with AssertionFailedError.
This is behavioral rejection, not a compiler failure. `receipts.json` pins baseline/final production
source hashes. Their only difference is reply-copy indentation; afterward the native test was
expanded to require actual buffer growth. Full core checks and the build were repeated; unchanged
Forge tests were correctly UP-TO-DATE with their previous passing XML retained.

## Reproduction and outstanding work

```powershell
mod\gradlew.bat -p mod check '-Dbr.sidecar=C:\Users\wmc02\Desktop\block-reality\dist\br-sidecar.exe'
forge\gradlew.bat -p forge build
python scripts/check_pipeline_mutations.py --out build/pipeline-mutations
python scripts/check_native_only_jar.py forge/build/libs/blockreality-0.4.0-dev.jar --mutation-checks
python scripts/check_docs.py dist/br-sidecar.exe
```

For the native targeted run set BR_ENGINE to the delivered library and run :core:test with filters
`*GameInputNativeTest`, `*InProcessRecoveryTest` and `*NativeGameRuntimeTest`. For the fresh isolated
server use the opt-in `-I ../scripts/state-delivery-probe.gradle runServer`, then invoke
`module_pipeline_smoke.py --config <guarded server.properties> --out <new receipts path>`.
Use a fresh isolated world for this first-baseline driver; it deliberately refuses to overwrite
an existing evidence file. Probes are outside the ordinary source set and jar.

FPS, real player networking, large-world save costs, registry capacity workloads, sustained edit
storms, native pool exhaustion and performance across Windows/Linux release assets remain unmeasured.
This unit establishes instrumentation and a Recorded baseline; it does not close the v1 performance
requirement or overwrite the earlier 41.7 ms FAIL/Linux failure records. Material visuals, player
interaction, latest self-contained native packaging and engine lifecycle/collapse/crushing/rolling
remain open. Java still owns no mechanical postprocessing or physics fallback.
