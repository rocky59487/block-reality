# CT core journal step — verified infrastructure, CT still open

2026-09-10. Criteria committed at a180ae2/1dafb52 before implementation. First core
source ee491960; allocation refinement criteria ff038638; final shipping source
**341359021430fad5fb6e89b8ea2a4829d3af6b0f**. This adds no engine changes and has
**no Forge production transaction caller yet**. None of the full CT-1..9 gates is
declared complete; #12/#17 and the v1 objective remain open.

## Observations

- 27 core transaction JUnit tests pass on Windows and Linux: immutable/bounded images,
  checksummed schema, exclusive/domain ownership, reduced-quota admission, replay
  binding, stale/competing requests, every write/flush failure, rollback failure,
  uncertain decisions, lost publication, reentrancy and corruption remaining closed.
- Each platform's final process gate terminates 37 real JVMs at prepare/commit/abort
  file stages, each participant write/flush and each rollback write/flush, then starts
  37 fresh recovery JVMs. Six durable images and published baseline are checked in
  each scenario; each existing outcome replays 100 times. The first-temp scenario has
  no transaction record and retains the incomplete temp. A second concurrent JVM is
  refused by the live owner's journal lock. Both gates pass before and after caching.
- These are synthetic file participants, **not Minecraft restart evidence**. The
  memory Host tests exercise 100 successful commit replays before and after restart
  and prove committed records do not overwrite subsequent edits. They do not supply
  authoritative piece lifetime/refund/undo semantics or 100 real inverse transactions.
- Three compiled negative versions of the coordinator/journal at ee491960 are caught
  by specific assertion failures: commit before participant flush, overwrite foreign
  values during recovery, and clear a corruption latch by baseline publication.
  Control 27 and restored 27 pass. These components are unchanged by the later cached
  validation-pattern refinement; final full behaviors/process cases pass again.

The new CI step runs the real process gate and uploads its raw receipts. Exact-head CI
status belongs to the PR check record; a local pass is not substituted for it.

## Full module regressions and packaging

| Platform | Core | Forge | Total |
|---|---|---|---|
| Windows 17.0.18 |434 PASS /12 platform SKIP |109 PASS |543 PASS /12 SKIP |
| Linux 17.0.20 |445 PASS /1 platform SKIP |109 PASS |554 PASS /1 SKIP |

555 registered tests (446 core,109 Forge). The delivered 42e10f5 native cases all
execute; the 28 compatibility sidecar cases also execute using existing binaries.
Windows skips are 11 POSIX fake-process cases and one executable-permission case;
Linux skips the Windows invalid-path case. Full suites pass before and after caching.
The unchanged 12 native-only packet golden cases execute in the Forge suites.
`check_docs.py` verifies all 36 quoted counts against the full XML and the existing
330-check compatibility binary suite. No engine source/build/release is performed.

Final development jar:15,253,895 bytes,
SHA-256 `c760f844a376add4252158cb3a5e581f173903c9889e0ed7e2b47dc444cafd5f`.
All 356 entries of the previous material-geometry jar are byte-identical, including
the 207 prior classes, resources, licenses and both native libraries. Added entries
are 20 transaction classes and their directory. The 227-class guard passes; three
compiled packaging negatives refuse legacy entries, dormant references and an
accidentally bundled construction process driver. Test drivers remain outside the jar.
The pre-cache jar/hash and its guard are retained separately.

This dormant core addition is not an installed-client/server construction release.
The prior MG gameplay evidence remains scoped to its own tested candidate; no new
live-game behavior, physics, destruction, material capability or FPS claim is added.

## Fixed allocation refinement

The exact same synthetic 1/128/4096-cell fixture uses 8 warmups plus 40 measured samples
per size, one JVM per platform/version. Timing includes journal create+decide and
their file barriers; input Intent construction, Forge participants and native work
are excluded. All raw samples are retained. Only allocation/identity has an adoption
threshold (at most 60% of baseline 4096-cell median allocation); timing is Recorded.

|4096-cell result | Baseline | Cached patterns |
|---|---:|---:|
| Windows median allocated bytes |9,030,408 |3,359,528 (37.20%) |
| Linux median allocated bytes |9,025,736 |3,354,696 (37.17%) |
| Windows create+decide p95 |12.547 ms |14.294 ms — increased |
| Linux create+decide p95 |125.573 ms |22.030 ms — decreased |

The memory threshold passes without changing validation, record format or barriers.
All 144 terminal records per platform match its baseline byte-for-byte, and the two
platforms'144 final records also match. The Windows timing increase is retained;
these single-fork samples establish neither a latency improvement nor v1 performance.
POSIX reports an available directory force; Windows JDK17 reports none. No power-loss
or storage-device guarantee is inferred from successful process interruption tests.

## First runs and raw evidence

The first core-only run has 24 PASS and three compiler warnings about unused test
resources. Those warnings were corrected; later added failure/quotas tests bring the
core transaction group to 27. First 21-scenario and expanded 37-scenario process runs
are retained independently from the final runs. Their passes do not retroactively
qualify a later source revision.

The first Linux launcher refused before Gradle because it pointed to a legacy binary
path absent from the MG source-only tree. The original helper/error are retained;
it was corrected to the existing NCR binary path, then all requested tests executed.
This setup failure is not counted as a behavioral negative arm or an engine skip.

`receipts.json` pins raw logs, XML archives, sources/helpers, mutation evidence and
process archives. Each ZIP contains every original file byte and an embedded SHA-256
manifest, verified after writing. Archives keep the PR source diff reviewable; raw
before-recovery files, journal images, command arguments and process logs remain
extractable. The four cost runs have identical journal records, so one canonical raw
set is retained with all four CSVs and the verified 144-file comparison hashes.

## Work still required

`docs/CONSTRUCTION_TRANSACTION_ADAPTER.md` records the actual Forge save API findings.
Implement explicit piece birth/retirement/ownership, canonical NBT, actor/global
participant barriers, ordinary placement and blueprint validation, actual chunk/player
durability and startup recovery, inverse undo, bounded authenticated packets and client
ghost/confirm/cancel/outcome/undo. Then run the real Forge/socket/restart/client and
performance gates. Journal correctness cannot replace any of those acceptance gates.
Engine lifecycle/crushing/rigid-pose delivery remains a separate read-only dependency.
