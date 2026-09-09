# REGISTRY_SCALING — local relative optimization gates pass

2026-09-10. Module only. Final production source `0736baa`; original baseline
`c5876c3`. Criteria were committed before each implementation: `a04b538`,
`51c5d69`, `4cd0976`, `5f95246`, `275de0f`, `871b006`, and `fb43135`.
The benchmark driver, fixtures, warmups, heap, fork count and original budgets
remain unchanged. No engine source, native contract, identity algorithm or save
format changed. This qualifies this registry optimization on this host only.

## Result and cost

All three baseline and four successive three-fork candidate runs are retained.
The final candidate passes all 64 timing budgets and all 16 capture/total allocation
budgets; all 600 warmup/measured payload pairs are identical. Each stage has 60
measured samples per fixture/pattern. Full data: `baseline/`, `candidate-list/`,
`comparison-list/`. No samples or failed candidates were dropped or substituted.

| D131072 pattern / stage | Original p95 ms | Final p95 ms |
|---|---:|---:|
| ONE / capture | 714.063463 | 5.994607 |
| ONE / reconcile | 9521.883899 | 282.590760 |
| ONE / objects encode | 64.327511 | 34.969982 |
| ONE / coverage encode | 3.129652 | 1.367404 |
| BURST64 / capture | 718.956628 | 2.959951 |
| BURST64 / reconcile | 11958.807560 | 277.383667 |
| BURST64 / objects encode | 43.989164 | 26.312201 |
| BURST64 / coverage encode | 2.560641 | 1.890952 |

Capture still copies O(n) metadata. Its D131072/ONE allocation p95 rises from
3670304 to 5243208 B (+42.9%, within the frozen 1.6x limit). Reconcile rises from
67113096 to 67637464 B. Object encoding falls from 33822336 to 9050800 B;
all-stage iteration allocation p95 falls from 165906520 to 139561456 B. These are
current-thread allocations, not live heap or total server memory.

Coverage encoding retains at most one 1572912-byte private array, always returning
a caller-owned clone. It reuses the existing immutable ordered cells snapshot;
if encode is the first reader after an edit, it also creates a bounded list of at
most 131072 references. That list and the encoding cache are invalidated on changes.
Cold D131072/BURST64 encoding allocates 3146656 B versus 1573760 B originally;
its total iteration allocation still passes. The benchmark's existing snapshot
stage includes list creation. An isolated cold encode call pays that cost itself.

## Implementation and correctness

Coordinate maps use defensive hash-backed storage with detached immutable entries.
Ordered records use a privately owned ConcurrentSkipListMap behind an unmodifiable
NavigableMap. Capture and prior graph views cannot alias later edits. Object encoding
pre-encodes distinct declarations with the original modified UTF representation,
allocates one exactly sized final buffer, and hashes its body without copying it.
The frozen 4878-byte catalogue/axes/lineage/pending/modified-UTF golden has SHA-256
`9a3afb43b840d7fd6ec9204a3ccaa7e659df337899e4ac6c26e17c35f2ec04e2`.

The first targeted run was 16 PASS / 1 FAIL: generator-based entry arrays exposed
mutable entries through the JDK unmodifiable wrapper. A separate test also exposed
the existing wrapped TreeMap's record entries. Original failures remain in
`first-failure/` and `records-first-failure/`. Source reference:
[OpenJDK 17.0.18 Collections.java](https://raw.githubusercontent.com/openjdk/jdk17u/jdk-17.0.18-ga/src/java.base/share/classes/java/util/Collections.java).
No external JDK code was copied into the mod.

Four compiled fault arms fail named AssertionFailedError oracles: caller alias,
mutable coordinate entry, mutable ordered record view, and stale coverage encoding.
`cache-mutations/results.json` retains all four; serializer-era arms are also kept.
No compiler failure is counted as a behavioral oracle.

Final Linux checks: **45 PASS / 0 SKIP** in `linux-list-xml/` and `linux-list.log`:
27 registry/codec tests, 11 real JNA input/recovery tests against the delivered
#37 development library, and 7 controlled runtime lifecycle tests. This is not
45 independent real-engine scenarios. Library source `95a03e82bbc50c53eac97b5289b79d8e640f8075`,
contract `4b11cc738790d32d576ac71ecd1cb57f04d3ed9186a9e43f89492ae13c258801`,
Linux library SHA-256 `bbf38ff3d4633d4c9e0e7f42fb87e1e3183a6f9d7418ed88432b05392b36c121`.
It depends on host OpenBLAS/METIS/LAPACKE; it is not a self-contained release asset.

## Earlier complete candidates remain failures

| Source | Failure retained | Original budget ms |
|---|---|---:|
| `8d613c0` | D4096/ONE objects encode 4.427358 ms | 3.803510 |
| `8d613c0` | D131072/BURST64 coverage encode 4.130830 ms | 3.560641 |
| `bd9053d` | D131072/ONE coverage encode 4.240746 ms | 4.129652 |
| `bd9053d` | F131072/ONE coverage encode 3.777959 ms | 3.269339 |
| `d1457a7` | D32768/BURST64 coverage encode 2.146288 ms | 2.039887 |

Their full `candidate-first`, `candidate-serialized`, `candidate-cached` and matching
comparison directories remain. All three had 600 matching digests and passing
correctness checks; those facts did not override their timing failures. Successive
production changes, not a same-source rerun/fastest-fork selection, produced the
final candidate. Causes of every latency fluctuation have not been isolated.

## Ordinary artifact and limits

Final Windows Temurin 17.0.18 full core: **412 registered / 384 PASS / 28 SKIP**;
Forge: **107 / 106 PASS / 1 SKIP**. Combined **519 / 490 PASS / 29 SKIP**.
Original XML/logs and the command setup failure before the quoted core retry are
in `windows-list/`. Earlier Windows records remain separately labeled. Nineteen
native integration tests are registered; this Windows run does not qualify a new
Windows native library. The old sidecar executable is used only as a test fixture.

The ordinary jar has **200 classes**, **457931 bytes**, SHA-256
`7b6c82900c3d703c383213059edf84735cffee10f529073051b7bdb82541d6ed`.
Class/entry guards, both compiled jar fault arms, and all twelve packet goldens pass.
No profile driver, integration probe, native library or executable is included.
This is a development jar, not the offline v1 release.

The Ryzen 9 8940HX shared developer host runs Windows and Ubuntu 22.04 WSL. RS
uses OpenJDK 17.0.20, fixed 2 GiB heap. The original client/server remain live;
short verification and mutation runs overlapped parts of the original baseline,
and one baseline thread dump sampled MapN.probe. This is not hardware isolation
or randomized/interleaved experimental control. Raw maximums remain visible:
D131072/ONE capture still reaches 30.595381 ms, and reconcile 294.113365 ms.

SP separately measures real adapter compression/fsync/reopen after RS timing JVMs
finish. Live game tick/save latency, long history retention, independent regions,
actual socket/client/FPS, current qualified native packaging and engine lifecycle,
collapse/crushing/rolling remain open. No v1 or whole-game high-performance claim.
