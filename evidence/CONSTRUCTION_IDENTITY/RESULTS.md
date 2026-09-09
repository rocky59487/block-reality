# CONSTRUCTION_IDENTITY result

Criteria `735fa0a` and additive command acceptance `e30c416` were committed before implementation.
Parent is #113 / `cfaeb79`. This unit changes only the Minecraft module. Engine source, contract,
native asset and release pins are unchanged.

Construction objects now have saved per-dimension UUID namespaces and monotonic IDs. Declared
frame products group along their declared axes; monolith and panel products group by face adjacency.
These are gameplay regions, not finite elements, inferred panel planes or physical connectivity.
Extension/trimming retain identity; splits, merges and complete replacement create children with
explicit parents. Retired records remain saved and their IDs are never reused.

The existing atomic SavedData file now includes declarations, pending destruction, completed graph,
epochs, namespace, counter and lineage. Coverage-only saves keep unknown unloaded coordinates and
report PENDING_DECLARATIONS until observed. Immutable work runs on the dedicated analysis pool;
publication requires the exact metadata epoch and marks the same SavedData dirty. Identity also
updates in native OFF mode. Actual block callbacks record command removals and replacements;
break/explosion notifications no longer retire protected blocks before actual removal.

`/br object <pos>` reads the saved identity without loading the target chunk. It reports declaration,
cell count, parents and metadata state. Native preview links use only native-supplied cell lists and
the exact current result revision, accompanied by source UUID and dimension. One permanent object
can link to multiple native elements. `/br section 0` now accepts the native engine's zero ID.
Both commands use the existing diagnostic permission policy. Client identity packets/hover are
not introduced, and existing analysis packet bytes are unchanged.

| Gate | Executed evidence |
|---|---|
| CI-1 | Frame parallel/crossing/axis/product separation, undeclared cells, monolith face grouping and panel thickness/product grouping; real world frame/monolith/panel fixtures |
| CI-2 | No-op/extend/trim retention; exact split/merge parents; full replacement across pending save; split with one rebuilt child; empty-world retirement and no ID reuse |
| CI-3 | Canonical codec round trips/opposite observation order; actual SavedData reload; coverage-only migration retains unknown cells; real unload/restart/reload keeps the permanent key and full ledger SHA |
| CI-4 | Immutable worker snapshot, separate worker thread, stale completion rejection, empty retirement; actual server starts OFF and publishes all gameplay identity operations before native loading |
| CI-5 | Bounds, checksums/truncations and re-signed malformed schemas/counters/epochs/IDs/ownership/parents reject; real corrupt file stays unchanged; two compiled behavioral fault arms fail named assertions |
| CI-6 | Real `/br object`, native member 0 and `/br section 0`; a column maps to several native members at a crossing; re-solving leaves the ledger unchanged; unloaded input removes current native preview links |
| CI-7 | 47 passing real-server assertions including readiness waits; first and repeated runs retained, each completing prepare/restart/recover |
| CI-8 | Full Windows suites/build, 13 prior WR server gates repeated with identity integration, 12 native packet goldens, documentation counts and shipping jar checks |

Windows core: **398 registered / 371 PASS / 27 SKIP**. Forge: **107 / 106 / 1**.
Combined: **505 / 477 PASS / 28 SKIP / 0 FAIL**, including 18 new tests.
The explicit legacy sidecar test argument runs compatibility tests only; that executable is not
shipped by the new mod. Skipped Windows native tests remain unqualified. Documentation verification
ran the 330 legacy engine checks and confirmed all 36 quoted counts.

The final ordinary development jar has 187 classes and is 436179 bytes, SHA-256
`2d0fafb5b97928c195dd1318d05b30505bdadfa34c7ae76d1e0a61751eaff478`.
Its entry/constant-pool audit and two compiled jar fault arms pass. No native library, executable,
integration probe or retired mechanics/codec fixture is present. This is **not a self-contained
release jar**. The live CLIENT_MATERIALS client, installed baseline jar and server were not replaced.

## First failures

1. First main-source compile and initial core tests passed. The first targeted Forge run was
   13 PASS / 1 FAIL: the save/reload test published directly to the ledger, bypassing the production
   dirty notification. `publishObjects` now owns both publication and dirty marking and is called
   by production and the test. The original reload assertion passes unchanged. First log/XML remain.
2. A subsequent boundary test exposed an implementation defect: splitting while rebuilding every
   cell of one child counted only continuously surviving children, so the other child kept the
   parent ID. The first failure is preserved. Counting the parent across every resulting component
   fixes the rule; both children receive fresh IDs and exact parent lineage. This does not relax CI-2.
3. The terminal-epoch audit found that destruction was recorded before epoch advancement refused.
   A ledger already completed at Long.MAX_VALUE then could not decode its own refusal save. The
   original failure is retained; the bound now refuses before recording destruction. The unchanged
   graph and persisted refusal round-trip pass. This guard does not alter normal-epoch behavior.
4. The first documentation check caught a stale README badge count after the prose had been updated.
   The badge was corrected to the measured 505; the final 36-count check passes.

The identity-reuse mutation removes the destruction continuity guard; the schema mutation bypasses
the SavedData object-format version check. Both compile and reach their named behavioral tests,
which fail with `AssertionFailedError`. Compiler failures are not accepted as mutation evidence.

## Real server details

The isolated identity server uses `/home/rocky/br-construction-identity`, world `construction-smoke`,
loopback game/RCON ports 25590/25591. The 49-cell steel beam spans x=4104..4152, y=200, z=8, supported
at x=4103. Additional parallel, monolith, panel and crossing-column fixtures are created and removed
by the driver. The replacement probe removes and recreates all five initial cells in one server
command, with no intervening bookkeeping publication. Only the opt-in integration source adds probes.

The repeated WR regression uses `/home/rocky/br-world-registry`, ports 25588/25589. It confirms
that partial unload/restart retains all 49 known cells and that native packet bytes are identical
after revision normalization when the complete input returns. Its 40-cell case still refuses when
only a required ground observation is unreadable. The first CI run, final CI run and both WR runs
are retained with all RCON receipts. Server log files here are named excerpts; full logs remain in
the isolated workspaces. These test servers save and stop after recovery. Server repetitions
preceded the final terminal-epoch guard; `receipts.json` pins their source and the final delta.
The final full suites/build re-ran after that guard. No server test claims to reach Long.MAX_VALUE.

Native source remains delivered #37 `95a03e82bbc50c53eac97b5289b79d8e640f8075`;
library `bbf38ff3d4633d4c9e0e7f42fb87e1e3183a6f9d7418ed88432b05392b36c121`;
contract `4b11cc738790d32d576ac71ecd1cb57f04d3ed9186a9e43f89492ae13c258801`.
Read-only upstream synchronization still reports formal v1.3 and main `c776ebc6`; neither was changed.

## Reproduction and limits

```powershell
mod\gradlew.bat -p mod check '-Dbr.sidecar=C:\Users\wmc02\Desktop\block-reality\dist\br-sidecar.exe'
forge\gradlew.bat -p forge build
python scripts/check_construction_mutations.py --out build/construction-mutations
python scripts/check_native_only_jar.py forge/build/libs/blockreality-0.4.0-dev.jar --mutation-checks
python scripts/check_docs.py dist/br-sidecar.exe
```

The real Linux server uses `bash gradlew -I ../scripts/state-delivery-probe.gradle runServer` with
BR_ENGINE set to the delivered library and the guarded isolated configuration. Start with native
OFF, run `construction_identity_smoke.py --config <server.properties> --out <receipts.json> --phase
prepare`; restart and run phases restart and recover. The WR driver uses its own guarded world and
the same three phases. Never point these fixture drivers at a player's world.

The ledger is bounded to 131072 cells/pending destructions, 262144 lifetime records, 524288 parent
references and 128 MiB encoded data. Overflow refuses publication without evicting history. Whole
graph reconciliation and save costs are not performance-qualified. Native calls and bookkeeping
share a pool; a fully exhausted native pool can delay metadata work. Known declarations survive
unload; unknown legacy declarations still require observation. Chunk saves and SavedData are not
one crash-consistent transaction. Player protection/cancellation paths have been arranged around
actual callbacks but have not been exercised with a real protection mod/client here.

#17/#86 remain open for independent native region scheduling, engine damage/lifecycle integration,
reinstallation, transactional world changes and client identity delivery. Actual client visuals,
material axis models, latest qualifying Windows/Linux native packaging, performance, collapse,
crushing and rigid-body rolling remain unqualified. No v1 or N25 completion is claimed.
