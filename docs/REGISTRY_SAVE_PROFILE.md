# REGISTRY_SAVE_PROFILE (SP) — frozen before implementing the driver

2026-09-10. Module only, parent implementation `8d613c0` / #116. RS capacity
measurements are still running. This adds a separate Recorded baseline for the
actual Forge persistence adapter, without changing production serialization or I/O.

## Protocol

Invoke the production `WorldIndexData.save(CompoundTag)`, `save(File)` and `open`
methods from an opt-in Java main, with the normal Forge/Minecraft classpath. This
is the real adapter/codec/NBT/compression/fsync/atomic-replace path on a generated
registry. It is not a live game tick, world/chunk save, native solve or FPS measurement.

Use the RS dense concrete fixtures D4096 (32x4x32), D32768 (64x8x64), D131072
(128x8x128), origin (4096,128,0), `concrete_rect_400x600`, initial axis 0.
Generate one immutable ready seed per shape, keeping its actual UUID and bytes.
All fresh JVM forks and both platforms load the same seed bytes. Do not regenerate
namespace/identities between candidate comparisons or silently replace a bad seed.

Two patterns, each starting from its seed in its own output directory:

* READY: toggle the first cell's axis 0/2 and reconcile/publish before saving.
* PENDING64: remove and replace the first 64 canonical cells as one batch, then save
  before reconciliation; settle only after checking the saved pending state.

After initial validation, use 5 warmups and 20 measured iterations per case in
three fresh JVM forks, fixed -Xms2g/-Xmx2g, without forced GC. In each iteration
measure tag serialization, the complete `save(File)` call, and fresh storage reopen
separately. The full file call includes its own serialization again: stages do not
divide a single save into additive parts. Repeated paths/cache are warm; do not
present these as cold-disk or power-loss durability tests.

Measure on the existing Ryzen 9 8940HX host, Windows/NTFS (Temurin 17) and
Ubuntu-22.04 WSL/ext4 (OpenJDK 17). Record exact runtimes, filesystem location/type,
source and seed hashes, compressed bytes, current-thread allocated bytes where
available, heap/GC and nearest-rank p50/p95/max. Keep all raw samples and failures.
Each fork may stop after 10 minutes with partial output retained as FAIL.
Do not overlap these measurements with RS baseline/candidate timing JVMs.
The existing client/server remain live; this remains a shared developer host.

## Gates

| Gate | Required evidence |
|---|---|
| SP-1 | Actual `save(File)` clears dirty only on success, produces a readable current-schema file, and leaves no temporary files after every successful iteration. Original failure tests for retained old files and dirty retries remain unchanged. |
| SP-2 | Fresh reopen preserves exact coverage/object encoded bytes, namespace, epochs and graph. READY is current; PENDING64 preserves exactly the 64 destroyed coordinates and its pending epoch, and reconciliation retains the existing monolith's identity. After every iteration, canonical payload hashes match across all forks/platforms for that case/iteration. |
| SP-3 | Every fixed case/fork/platform is complete, with raw timings, allocations, compressed sizes and source/runtime/seed identity. Timing is Recorded only; freeze any future optimization budget before changing production. Missing or failed legs are not a pass. |
| SP-4 | Driver/init script are opt-in and absent from the ordinary jar; production persistence and engine sources are unchanged. Existing correctness, jar and documentation gates remain valid. |

Any extra setup/compile failure is retained and distinguished from a behavioral
failure. No standard tests are silently replaced with this benchmark. Live server
tick/save latency, crash/power-loss durability, asynchronous save design, long history,
per-object scheduling and all v1 delivery/physics/visual requirements remain pending.
