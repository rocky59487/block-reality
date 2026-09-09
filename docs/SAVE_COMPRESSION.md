# SAVE_COMPRESSION (SC) — frozen before production changes

2026-09-10. Module only, based on #116 `69f95c9`, production source `0736baa`.
The original SP baseline remains Recorded: complete 131072-cell READY file-save
p95 is 260.1542 ms on Windows and 201.673856 ms on Linux. Do not retroactively
reclassify that baseline. This unit evaluates a lossless gzip writer tradeoff.

## Proposed change and scope

Keep Minecraft's NBT encoding, root data/DataVersion, current coverage/object
schemas, synchronous save semantics, fsync, atomic rename, original-file retention,
dirty retry and temporary cleanup. Use JDK gzip with Deflater.BEST_SPEED and a
32768-byte compression output buffer. This trades compression effort/ratio for
latency; no asynchronous persistence or early success acknowledgement is added.
Compressed bytes may differ; the decoded canonical payload must not differ.
No engine, packet, metadata grouping, identity or gameplay changes.

Before any performance qualification, test vanilla NbtIo compatibility, reading an
old default-compression file and resaving pending metadata, plus an injected failure
during writing. A failed write must preserve the previous file and dirty state,
clean its own temporary file, and permit an exact successful retry. Existing
replacement-failure and corrupt-file tests, packet goldens and jar guards remain.

## Fixed comparison

Use the unmodified SP Java driver/init/runner and exactly the original three seed
files in evidence/REGISTRY_SAVE_PROFILE/seeds. Same two platforms/filesystems,
three fresh JVM forks each, 2 GiB heap, all three dense fixtures, READY/PENDING64,
5 warmups plus 20 samples, ten-minute fork timeout. Preserve first failures and
partial output. No RS or other profile timing JVMs may overlap. The existing
client/server stay live; retain the shared-host limitation.

Compare every one of the 900 baseline/candidate iteration payloads and all 720
measured samples. Every candidate fork must also agree with all other forks and
platforms. Compressed file hashes are recorded but not required to match.

* D131072 complete file-save p95, each platform and pattern: <= 50% of its original
  SP p95. Other file-save p95 and all tag-encode/reopen p95:
  <= max(1.25 * baseline p95, baseline p95 + 1 ms).
* Each measured stage allocation p95: <= baseline p95 + 65536 bytes. Report every
  allocation increase even when it is inside this limit.
* Each paired compressed file size: <= 1.50 * its original baseline size. Report
  actual size increases and the complete compressed-size range per case.
* All source/seed/runtime identities, raw iterations, p50/p95/max and gates retained.
  No fastest fork, resampling without a source change, dropped outlier or adjusted
  threshold. An observed loss remains FAIL.

These budgets qualify only this generated-registry adapter change on this host.
Synchronous serialization/fsync still block the caller. Live tick/autosave/FPS,
whole-world save, crash/power-loss durability, long history and all existing v1
native/visual/physics gates remain open.
