# SAVE_COMPRESSION — fixed local comparison passes

2026-09-10. Criteria committed first in `201e081`; production source `4e9e927`.
Original SP baseline remains Recorded at `0736baa`, with its immutable seeds and
raw observations retained under ../REGISTRY_SAVE_PROFILE. No baseline, fixture,
Java driver, warmup, fork count, heap, percentile or budget changed.

All six candidate JVM forks complete: **900 baseline/candidate iteration pairs,
720 measured pairs, 150 canonical payloads identical across every fork/platform**.
All 36 timing and 36 Java allocation budgets pass; all 900 paired compressed files
stay within the frozen 1.50x size limit. The candidate functional summary also
passes independently. Raw files: `windows/`, `linux/`, `functional/`, `comparison/`.
The source-identity comparison confirms only WorldIndexData.java changed among the
144 measured production/profile files, with identical candidate sources on both OSes.

## Result and tradeoff

| Platform / D131072 pattern | Original file-save p95 ms | Candidate p95 ms | Candidate max ms |
|---|---:|---:|---:|
| Windows / READY | 260.154200 | 96.526700 | 115.328100 |
| Windows / PENDING64 | 251.118500 | 98.199400 | 117.404900 |
| Linux / READY | 201.673856 | 83.316362 | 117.318688 |
| Linux / PENDING64 | 196.999733 | 72.643371 | 74.622216 |

The 131K files grow from 840643–840689 to 989424–989552 bytes (about 17.7%).
The 32K cases grow about 21.7–21.8%; all size increases are retained in the CSV.
The 32 KiB gzip output buffer adds about 32 KiB of current-thread Java allocation
per save: Windows 131K READY p95 10640016 → 10672688 B, Linux 10637648 → 10670200 B.
This metric excludes native zlib allocations and is not a whole-process heap bound.

The writer uses JDK BEST_SPEED compression and a 32768-byte output buffer, with
Minecraft's unchanged NBT writer. It keeps DataVersion and both registry schemas,
then closes gzip, fsyncs the temporary file, atomically replaces the target and
clears dirty. It never acknowledges persistence early or adds background I/O.
Compression effort and output-buffer size changed together; this end-to-end
comparison does not isolate their individual contributions.

## Correctness and artifact

Two new tests pass: old default gzip can be read/resaved with pending object
metadata through vanilla NbtIo, and a partial gzip write failure preserves the
original file, dirty retry and exact later payload while cleaning its temporary.
Existing failed atomic replacement, corrupt input, pending metadata and identity
checks also pass. `first.log` retains the first targeted run (8 PASS / 0 SKIP).
No functional or performance candidate failed in this unit.

Full Windows Forge: **109 registered / 108 PASS / 1 SKIP**, archived XML/logs.
Unchanged core source reuses the RS full record **412 / 384 PASS / 28 SKIP**;
combined **521 registered / 492 PASS / 29 SKIP**. It was not presented as a new
local core run. CI separately reran the full core and Forge suites on this source.
Linux full Forge: **109 PASS / 0 SKIP**, including the real JNA-to-packet case using
the already delivered #37 development library. No engine source was edited/built.

Twelve packet goldens, entry/constant-pool checks and both compiled jar fault arms
pass. The ordinary jar is **201 classes / 458988 bytes**, SHA-256
`b0a3f9447cfcc3c2f22659c0c6e0705fe24aa57b9591c48d10e300f6d8adfd35`.
No executable, native binary, profile driver or integration probe is included.
All 36 quoted-count checks agree. This remains a development jar.

CI [34415501370](https://github.com/rocky59487/block-reality/actions/runs/34415501370)
at evidence head `ca4be7e` completes all four job statuses. The native job skips
15 substantive steps for the missing cross-repository token; `ci-ca4be7e.json`
records them. Its status is not current native packaging qualification.

## Scope

Same shared Ryzen 9 8940HX host, Windows 11/NTFS/Temurin 17.0.18 and Ubuntu 22.04
WSL/ext4/OpenJDK 17.0.20, fixed 2 GiB heap. Candidate Windows and Linux timing
JVMs ran sequentially, after correctness checks and without other profile JVMs;
the existing client/server remained live. Warm paths/cache, no hardware isolation.
File save includes serialization, compression, fsync and replacement; the measured
stages are not additive subdivisions of one save. Raw maximums remain visible.

Even the faster 131K save still blocks its caller for tens to over 100 ms. This
qualifies the frozen relative adapter optimization only. Live tick/autosave/FPS,
whole-world save, crash/power-loss durability, long history, independent scheduling,
Windows client acceptance, latest qualified native packaging and all engine
lifecycle/collapse/crushing/rolling requirements remain open. No v1 release.
