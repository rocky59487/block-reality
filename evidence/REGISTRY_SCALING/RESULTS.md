# REGISTRY_SCALING — candidate verification, performance comparison pending

2026-09-10. Module only. Criteria `a04b538`, baseline driver/source `c5876c3`,
optimization budgets `51c5d69`, ordered-view clarification `4cd0976`.

The first archived D131072/ONE warmup measured capture 787.447232 ms and pure
reconciliation 9178.324904 ms. A thread dump caught Java 17 `MapN.probe` while
building the graph ownership map. These are initial observations, not percentiles
or a passed optimization gate. Three baseline JVM forks are still being collected
on their original source at `/home/rocky/br-registry-scaling`.

The candidate uses defensive hash-backed coordinate snapshots and detached immutable
entries. Ordered record maps use privately owned ConcurrentSkipListMap storage,
wrapped against modification; its navigable entries are detached snapshots.
No BlockKey hash/equality, identity algorithm, codec bytes, Forge runtime path,
native contract or engine source changed. Snapshot capture is still O(n).

## Correctness and first failures

* First targeted run: 16 PASS / 1 FAIL. On Temurin 17.0.18,
  `workAndGraphViewsRejectEveryMutableEntryPoint` reached generator-based
  `entrySet().toArray(...)`; `setValue` succeeded through the standard unmodifiable
  wrapper. `first-failure/` retains the original log and XML.
* A follow-up on the pre-existing wrapped TreeMap also failed:
  `graphRecordsAndRangeViewsNeverExposeMutableEntries`. Its original one-test failure
  is in `records-first-failure/`. The immutable view gate was not weakened.
* Fixed targeted run: 18 PASS. Covers caller ownership, mutator/default methods,
  iterators, all array overloads, streams/spliterators, ordered/range views, retained
  old graph/destruction after publish, null rejection, concurrent edits and stale
  result rejection, plus prior identity/codec gates.
* Three compiled fault arms each fail their named AssertionFailedError oracle:
  caller alias, mutable coordinate entry, and mutable ordered record view.
  See `mutations-final/results.json`; compiler errors never count as rejection.

The Java 17 wrapper's inherited generator-array route delegates to the backing
collection; the entry wrapper overrides the older array routes. Source inspection
supports the local regression result:
[OpenJDK 17.0.18 Collections.java](https://raw.githubusercontent.com/openjdk/jdk17u/jdk-17.0.18-ga/src/java.base/share/classes/java/util/Collections.java).
No external JDK code was copied into the mod.

## Ordinary artifact and checks

Windows Temurin 17.0.18 full core: **410 registered / 382 PASS / 28 SKIP**.
Forge: **107 registered / 106 PASS / 1 SKIP**. Combined **517 / 488 PASS / 29 SKIP**.
Nineteen native integration tests are registered; this Windows run has no compatible
current native library and does not establish Windows native qualification.
The explicitly selected old sidecar executable is a test fixture only.

The ordinary jar has **200 classes**, **457244 bytes**, SHA-256
`123776aebbdb50e715f0f4d5bdc681140ec9c03c78a424c2828c2a992008c927`.
Entry/constant-pool checks and both compiled jar fault arms pass. The twelve packet
goldens pass unchanged. No profile driver, integration probe, native library or
executable is included. It is a development jar, not the required offline v1 release.
All 36 documentation count checks agree with the actual suites.

## Measurement scope and remaining work

Baseline and candidate must each complete the fixed 480 measured iterations plus
120 warmups. Every paired digest must match. `compare_registry_scaling.py` checks
completeness, all eight stage populations and the budgets frozen before production
changes; a failed timing or allocation budget remains a failure.

The host also runs the existing CLIENT_MATERIALS server/client. Short Windows
verification and mutation runs occurred during baseline fork 1; retain this shared
developer-host workload, including the diagnostic thread attachment, in interpreting
the measurements. No samples are dropped or substituted. This is not a controlled
hardware-isolation claim. Current-thread allocated bytes cover the measured core
operations; full server disk save, native work and client/FPS are not measured here.

RS performance qualification, actual main-thread compression/fsync, long history
retention, independent region scheduling, true client delivery/visuals, qualified
latest native packaging and engine lifecycle/collapse/crushing/rolling remain open.
The native engine and live CLIENT_MATERIALS baseline are unchanged.
