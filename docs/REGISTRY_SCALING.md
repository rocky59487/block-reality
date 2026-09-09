# REGISTRY_SCALING (RS) — frozen before measurement and optimization

2026-09-10. Parent #115 / `07004ff`. Module only; engine sources and the live
CLIENT_MATERIALS server/client remain untouched. MP measured at most 832 cells;
its numbers cannot qualify the 131072-cell registry limit.

## Baseline protocol — Recorded

Run the actual core `WorldCellIndex`, `ConstructionLedger` and codec on the same
Ryzen 9 8940HX host, Ubuntu-22.04 WSL, Java 17, fixed 2 GiB initial/maximum heap.
Record JVM/kernel/CPU, source commit and hashes, heap/GC and current-thread allocated
bytes (if supported; unavailable is not zero). This is an isolated Java harness,
not a Minecraft tick, disk save, native solve, socket or client/FPS benchmark.

Use dense concrete monolith declarations (`concrete_rect_400x600`, initial axis 0)
at origin (4096,128,0), in canonical x/y/z order. Shapes are 32x4x32 = 4096,
64x8x64 = 32768, and 128x8x128 = 131072 cells. Also run 131072 sparse X-axis steel
cells: 1024 independent 128-cell beams, origin (4096,128,0), row z=2*r for r=0..1023.
This preserves both dense and sparse coordinate/hash distributions. No fixture reduction.

For each shape, independently measure two edit patterns on a ready ledger:

* ONE: toggle the first cell's declared axis between 0 and 2, without replacing it.
* BURST64: remove and replace the first 64 canonical cells with their original
  declaration in the same batch; coverage is removed/re-added too.

Measure edit, immutable work capture, pure reconciliation, publication, coverage
position snapshot, coverage encoding, object encoding, and object decoding separately.
Keep the initial population/reconciliation costs, then 5 warmup iterations and 20
measured iterations per pattern, in 3 fresh JVM forks, retaining every raw sample.
Record whether coverage snapshot is cold or reused. Read allocated-byte counters
outside each timed span. Keep GC/heap observations outside stage timing. Do not force GC,
choose the fastest fork, discard slow samples or silently retry a failed fixture.
An individual fork may be terminated after 20 minutes; retain partial output and
report incomplete work as FAIL, never a successful benchmark.

Use a fixed namespace per fixture/pattern/fork so baseline and candidate encodings
can be compared exactly. After every iteration check counts, expected epoch,
publication readiness, immutable captured input after a subsequent edit, exact
codec round trip and the canonical encoded SHA-256. Keep a result digest per
iteration; candidate digests must equal baseline for the same fork/fixture/pattern.
Document expected sparse ONE split/merge lineage, rather than weakening identity rules.

## Gates

| Gate | Required evidence |
|---|---|
| RS-1 | Complete fixed baseline/raw output, first failures and source/runtime identity. All initial timings are Recorded, without a retroactive performance threshold. |
| RS-2 | Only after the baseline identifies a cost, commit an optimization target and comparison budgets before editing production code. Include worker and allocation regressions, not just caller latency. |
| RS-3 | Immutable work/graph inputs and stale-publication rules remain valid during concurrent edits; bounds, null refusal, split/merge/rebuild identity, deterministic save bytes, corrupt-save refusal and no eviction all remain enforced. Add a named regression oracle and a compiled behavioral fault arm for any changed ownership boundary. |
| RS-4 | Repeat all fixed cases on the candidate, compare every digest, keep baseline and candidate raw data and report every budget loss. No Java mechanics/native changes. Full module/Forge checks, packet goldens and ordinary jar audit remain required. |

Actual main-thread save (compression/fsync), full server edit storms, chunk scan,
large-world native analysis, memory retention across long histories, rendering,
per-object scheduling and v1 performance qualification remain separate pending work.

## RS-2 optimization target — frozen after initial baseline observations

Baseline source `c5876c3`; first D131072/ONE warmup capture 787.447232 ms,
reconcile 9178.324904 ms, decode 1444.289351 ms. A single thread dump caught
`ImmutableCollections$MapN.probe` from `Graph`'s `Map.copyOf`. That diagnostic
attachment is part of fork 1's recorded workload; no samples will be discarded.
The remaining baseline forks continue on their archived, unmodified source.

Replace the registry's coordinate-keyed immutable Map/Set copies with defensive
hash-backed snapshots, preserving unmodifiable views and null rejection. The graph
may wrap a privately owned, fully validated map without another copy. Do not change
BlockKey equality/hash, persistent encoding, identity algorithm, core API signatures,
coverage indexing, world/thread authority or any other production path.

Compare all 60 measured samples per fixture/pattern, nearest-rank p95. A timing
loss is explicit; passing a jar build or identity oracle cannot erase it:

* D131072, both patterns: capture and reconcile p95 <= 25% of baseline p95.
* Every other fixture/pattern: capture/reconcile p95 <= max(1.25 times baseline,
  baseline + 1 ms). Record all eight stages; other stage p95 uses this same budget.
* Capture allocated-byte p95 <= 1.6 times baseline (hash buckets/nodes trade memory
  for collision resistance). Total allocated bytes of all measured stages per
  iteration p95 <= 1.10 times baseline. Report allocation losses within these budgets too.
* Every paired encoded digest and behavioral oracle must match; repeated source-map
  mutation, all collection mutation routes and captured destruction after publication
  must be rejected or remain independent. A defensive-copy-removal fault must compile
  and fail a named mutation-isolation assertion.

These relative budgets qualify only this registry optimization on this host. They
are not the v1 frame/tick budget, and O(n) snapshot/save work remains O(n).

### Immutable entry follow-up, before changing ordered record storage

The first candidate's generator-based entry array exposed mutable HashMap entries on
Temurin 17.0.18; the original Graph's wrapped TreeMap has the same escape. Both named
first failures are retained. RS-3's immutable graph requirement therefore includes
the ordered record map and its descending/head/tail/submap views. Keep its public
NavigableMap API, order, values and encoded bytes; use privately owned ordered
storage whose entries are intrinsically detached and immutable, then forbid all
map mutations. The relative timing/allocation budgets above are unchanged; added
ordered-storage costs must be reported. This expands storage scope from coordinate
maps to record views for correctness, without relaxing any prior gate.
