# WORLD_REGISTRY result

Criteria committed as `cd4c1b3` before implementation, parent #112 / `2005624`.
Module-only work. Engine source, contract, pins and native assets did not change.

Known structural cells now survive dimension save/reload. Complete loaded-chunk scans reconcile
only their own chunk; unloading cannot delete saved coverage. Storage has a 131072-cell bound,
canonical coordinates, version and SHA-256 integrity check. Overflow latches a persisted refusal
without eviction; corrupt existing files refuse analysis without being overwritten. Writes use
an atomic sibling-file replacement and retain both the old file and dirty retry state on failure.

Any unreadable structural cell or six-face ground observation now prevents native dispatch.
PENDING carries a bounded explanation through the existing channel-11 envelope. Existing clients
retain stale results; waiting bootstrap supplies the notice without a result. Commands report the
waiting state even when a previous native result exists. Result acceptance rechecks the whole
required chunk domain. No terrain generation, chunk tickets, physics or result reconstruction is added.

| Executed gate | Evidence |
|---|---|
| WR-1/2 | Canonical bytes identical across opposite chunk observation order and reload; negative coordinates, empty-chunk reconciliation, immutable snapshots and invalid atomic replacement tested |
| WR-3 | Inclusive 131072-cell bound, replacement at the bound, overflow with no eviction, persisted refusal across reload and maximum encoded size tested |
| WR-4 | Every byte truncation/bit flip rejects; re-signed unknown version, duplicates, unsorted/out-of-world coordinates reject; corrupt real SavedData file survives open/edit/save unchanged |
| WR-5 | Real disk SavedData round trip, dimension separation and injected atomic replacement failure/retry pass |
| WR-6 | Unit dispatch counter remains zero for missing structure/ground; real server also waits with all 40 structural cells present but one unreadable ground observation |
| WR-7 | FULL demotion/promotion without Forge unload/load events, bounded polling, late unreadable chunk rejection and coverage removal tested; actual native server unload/reload passes |
| WR-8 | Real server bootstrap probe shows RESULT+payload at baseline, PENDING+explanation without payload while waiting; channel-11 round-trip tests pass. Actual client rendering/socket behavior is not credited here |
| WR-9 | Real Linux server: 49-cell beam -> part unreadable -> save/restart still remembers 49 -> reload returns identical native packet bytes after revision normalization |
| WR-10 | Full checks, documentation counts, reobfuscated jar gate and compiled checksum/missing-input fault arms pass |

Windows core: **383 registered / 356 PASS / 27 SKIP**. Forge: **104 / 103 / 1**.
Combined: **487 / 459 PASS / 28 SKIP / 0 FAIL**, including 15 new tests.
The legacy sidecar-backed tests ran with an explicit test-only executable; the earlier whole-core
run omitted that argument and correctly reported 55 SKIP (328 PASS), not the final 27 SKIP.
Documentation checker ran 330 old compatibility checks and verified all 36 quoted counts.
The twelve frozen native-only packet hashes still pass. No skipped native test is claimed as PASS.

The final ordinary development jar is 406943 bytes, SHA256
`d5387a18f94e9f9d3f6219dbc28ffaf726b7003bfb6c0d617f59e8243c368f29`.
Its 176 classes pass the required constant-pool/entry audit and both compiled artifact fault arms.
The integration probes and retired formula/codec fixtures are absent. It contains no bundled
native library and is **not** a self-contained release jar. The live CLIENT_MATERIALS baseline
client/server and installed jar were not replaced or restarted by this unit.

## First failures and the defect they exposed

1. First Forge compile failed on two missed adapter call sites (`List.copyOf` and the new gather
   record argument). These compiler errors are retained and are not mutation oracles.
2. The isolated source-copy server's first build lacked the root LICENSE file. Copying LICENSE
   and NOTICE fixed the harness. Its first command driver also omitted RCON `connect`; no gate
   ran in that attempt. Both are setup failures, not engine capability results.
3. The first complete server baseline solved, but unloading the far end left revision 1 CURRENT
   for the whole 55-second oracle window. Subsequent vanilla `execute unless loaded` confirmed
   the endpoint was unreadable. **Forge Unload events arrive after FULL availability is lost.**
   Relying on that event alone left a current-looking result for unavailable input.
4. The fix polls at most 256 required chunk statuses per tick, without requesting chunks, and
   checks the whole required domain before accepting a native result. The original scenario then
   passed unload, restart and recovery. The final-source repeat includes clearing the obsolete
   load-probe flag when an unavailable domain rejects its reply.

`server-first.json` retains the failed oracle. `server-final.json` retains the final 13 passing
server gates and their complete RCON receipts. The baseline index SHA256 is
`c6605d647133c88074758d662dbdcf789f44ce81653a5e1581e65e0ab9d23450`;
the revision-normalized native payload SHA256 is
`3550de307d4911e0efeeaabd53cdd700cf80ee678d8c39d7ed0de2e9aa21aea7`.
The 49-cell steel cantilever spans x=4104..4152, y=200, z=8, anchored at x=4103.
Its native max D/C is 3.8020 in the command readout. Forced chunks also retain neighbours;
this span allows the far endpoint to truly become unreadable while the anchored end stays loaded.
The ground-only case removes the last nine cells, leaving x=4143 at the readable boundary.

Native source is delivered #37 `95a03e82bbc50c53eac97b5289b79d8e640f8075`;
library `bbf38ff3d4633d4c9e0e7f42fb87e1e3183a6f9d7418ed88432b05392b36c121`;
contract `4b11cc738790d32d576ac71ecd1cb57f04d3ed9186a9e43f89492ae13c258801`.
These are the same development inputs as #112, not new engine releases. The server ran in
`/home/rocky/br-world-registry`, ports 25588/25589, isolated world `world-registry-smoke`.

## Reproduction and remaining scope

From this repository with Java 17:

```powershell
mod\gradlew.bat -p mod check '-Dbr.sidecar=C:\Users\wmc02\Desktop\block-reality\dist\br-sidecar.exe'
forge\gradlew.bat -p forge build
python scripts/check_world_registry_mutations.py --out build/world-registry-mutations
python scripts/check_docs.py dist/br-sidecar.exe
```

For the isolated Linux native server, use the opt-in integration source via
`bash gradlew -I ../scripts/state-delivery-probe.gradle runServer`, with BR_ENGINE set to the
delivered library. Run `scripts/world_registry_smoke.py --config <isolated server.properties>
--out <receipts.json> --phase prepare`; it saves/stops. Restart, then run `--phase restart` and
`--phase recover`. Recovery saves/stops too. Normal build/jar source sets exclude both probes.
The mutation runner copies sources into a checked disposable workspace; the real checkout is
not mutated. Both arms compile, run their named behavioral tests, and fail with AssertionFailedError.

This is **known-cell coverage**, not the persistent fused-object registry. Stable artifact IDs,
declared-role grouping, lineage/damage, independent region scheduling and lifecycle remain open.
Unknown cells in old saves are discovered when loaded/scanned. Chunk/world saves and index writes
are not one crash-consistent transaction. Metadata is bounded; performance is not yet qualified.
For unchanged coverage, FULL-change notification latency is at most one poll sweep, ceil(required chunks / 256) ticks;
new result acceptance always checks the whole required domain. Missing one chunk currently delays
unrelated structures in the same dimension. #86/#17 remain open. No v1 or real-client N25 claim.
