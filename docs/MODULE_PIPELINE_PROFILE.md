# MODULE_PIPELINE_PROFILE (MP) — frozen before implementation

2026-09-10, module only. Parent #114 / `472c3a2`. The actual client is still behind a Windows
security request, so visual changes and CM-1..6 are pending. This unit measures the real module
pipeline, including work outside the existing 8 ms gather loop, without changing engine physics.

## Recorder and authority

An operator can start, stop and inspect a per-dimension bounded profiling session through
`/br profile start|stop|show`. All profile commands require level 2. Profiling defaults OFF,
is not persisted and cannot change world/result revisions, force a solve or load the engine.
Disabled instrumentation performs no clock reads or sample allocations. A profile restart starts
a new session; work started before restart/stop must never enter the new session. Concurrent
main/worker completions are supported, and a span closes at most once. Retain at most 256 samples
per fixed stage; report retained count, total observed count, p50, p95 and maximum of retained
samples, using nearest-rank percentiles. A read takes an immutable snapshot and does not clear it.
No unbounded per-request history, no identifiers, no network telemetry or file logging by default.

Stages cover manager tick; metadata capture/queue/reconcile/publish; gather preparation/steps/final
snapshot; analysis queue/worker; world-record encoding; BSI frame encoding, actual JNA calls,
reply-frame parsing and analysis-result decoding; accepted packet construction; apply and network
dispatch. Nested durations overlap and must not be summed. JNA duration includes engine work;
it is not a claim to measure engine compute separately. Include call byte totals/growth attempts
where available without changing request bytes or retry semantics. Failed/stale operations remain
visible as costs, not silently removed to improve percentiles.

## Frozen gates

| Gate | Required evidence |
|---|---|
| MP-1 | Disabled path does not consult injected clock or store samples; fixed storage bound/wrap; exact quantiles; immutable snapshots; repeated close; invalid/zero durations; stop/restart excludes old spans; concurrent completions retained correctly. |
| MP-2 | Instrument the real production call sites, preserve both success and exception behavior, and retain native bytes/result flags. Existing packet goldens/native retry and lifecycle tests remain unchanged. No Java mechanics or native source changes. |
| MP-3 | Profile commands have explicit operator permission, do not resolve/edit/reset native state, show session/stage/count/units and explain overlap. Stop preserves the frozen sample set; start clears it. |
| MP-4 | Actual isolated native server profile start/stop/restart; no observation when disabled; successful solves, metadata edits and packet construction populate the appropriate stages. Profile control leaves world/object/native result identity unchanged. Preserve every first failure. |
| MP-5 | Fixed performance fixtures below, all raw stage reports and hardware/runtime/native/source identity. Timings are Recorded until a stable baseline justifies a separate precommitted optimization gate. Never present headless timings as FPS, client rendering or v1 performance qualification. |
| MP-6 | Full core/Forge checks, existing 12 packet goldens, jar/entry/constant-pool checks and documentation counts; profile probes absent from the ordinary jar. A compiled recorder fault arm must fail a named behavioral oracle, not fail compilation. |

## Fixed baseline protocol (Recorded)

Host AMD Ryzen 9 8940HX, 16 cores/32 logical processors, Windows host with Ubuntu-22.04 WSL,
Java 17; record exact JVM/kernel/memory availability at execution. Use the already delivered
Linux #37 library `bbf38ff3d4633d4c9e0e7f42fb87e1e3183a6f9d7418ed88432b05392b36c121`, contract
`4b11cc738790d32d576ac71ecd1cb57f04d3ed9186a9e43f89492ae13c258801`. Native threads=1,
eigen budget=2400, self weight enabled; minTicksBetweenSolves=1 for the controlled test.
Only use isolated world `pipeline-smoke`, loopback game/RCON ports 25592/25593, no players.
Do not restart or alter the existing CLIENT_MATERIALS baseline server or client.

Use three scenes, in this order; clear the previous scene's structural cells before the next:

1. A49: 49 X-axis steel 200x400 cells x=4104..4152, y=200, z=8, ground at (4103,200,8).
2. F576: 64 independent 9-cell X-axis steel 200x400 beams. For c,r=0..7, origin
   (4104+12c,200,8+4r), cells x=origin..origin+8, ground at origin-1.
3. M832: F576 plus 256 concrete slab-200 cells x=4256..4271, y=210, z=8..23,
   declared axis Y, ground along x=4255 at the same y/z.

Force only the required test chunks and wait for CURRENT and completed identity. Perform
10 warmup resolves before each scene's session, then 40 sequential resolves, waiting for the
exact new revision to become CURRENT after each. Stop profiling after the final result.
Keep every attempted sample/failure; no best-run selection, no forced GC, no silently reduced
fixture. If a scene refuses or times out, retain that result and its measurements as a failure
of that scenario; diagnose and record any correction, keeping the original fixture and claim.
Record per-stage distributions, result element counts, packet budget state, process heap/GC
and live workload. Socket transmission to real players and client/FPS costs remain unmeasured.

Any subsequent optimization must retain these scenes/results and first baseline. Measured
regressions stay in the record. Independent region scheduling, native packaging, material visuals,
collapse/crushing/rolling and all existing v1 requirements remain in scope of the overall goal.
