# GAME_RUNTIME module result — 2026-09-10

Only the Minecraft module changed. The consumed engine is the delivered Linux development
library from tectonic2 `95a03e82`, contract `4b11cc738790…`; its binary SHA256 and the default
development jar identity are in `receipts.json`. No engine source, release or pin was changed.

| Execution | Result |
|---|---|
| Windows core check | 357 registered / 330 PASS / 27 SKIP |
| Windows Forge build/check | 85 registered / 84 PASS / 1 SKIP |
| Total | 442 registered / 414 PASS / 28 SKIP / 0 FAIL |
| OFF_LOAD, CLOSE_IGNORED, UNDECLARED_Y | each compiled and produced its one named AssertionFailedError |
| Final Linux Forge dedicated server | boot, real BSI/JNA game analysis, reset and orderly stop executed |
| Isolated RCON scenarios | seven checks executed; final/third receipts retained |
| Default jar scan | native runtime/vocabulary/licenses present, zero executable/legacy process classes; no native library bundled |
| Explicit executable packaging | correctly quoted invocation refused by GAME_RUNTIME |

The 28 skips comprise native-library-dependent checks and platform-specific legacy lifecycle
checks. They are not passes. `counts.json` is harvested from JUnit XML. The Linux game smoke is
additional evidence, not a claim that the entire Linux unit/native suite was rerun.

The supported steel cantilever produced 1 member and nonzero D/C. Removing its sole ground
contact advanced the revision and revoked the previous success; restoring the contact restored
analysis without scan/resolve. The mixed column/cantilever/slab/floating-beam world produced
2 members, 12 facets and 1 unrestrained island. An undeclared legacy axis refused the complete
input; removing it recovered analysis. Reset logged a fresh native load. Tests touched only the
loopback, isolated `runtime-smoke` world, with the previously accepted development EULA.

Failures retained:

1. First Forge run: old BucklingPolicy default test expected 600 blocks. The frozen migration
   requires the engine's DOF budget; old pure policy tests remain under test sources. The
   replacement 2400-DOF default has no measured performance qualification.
2. First server: removing nonstructural ground did not update the revision. Added module
   ground/neighbor notification hooks; subsequent server runs observed the edit.
3. Second server: expected MECHANISM but received native SOLVE_FAILED/no solved island.
   The original JSON remains. Later smoke checks prove invalidation and explicit refusal only;
   typed all-mechanism presentation is **not accepted**. Java does not derive physics from an
   error message. The separate mixed-world singular result was actually executed.
4. First executable counterexample invocation was split by PowerShell into a nonexistent
   task; it is an invocation error, not evidence of the packaging guard. The quoted rerun
   reached the intended explicit guard and failed there.

Text log copies have trailing whitespace removed; `receipts.json` records original hashes and
identifies server excerpts. RCON receipts retain every command and formatting-stripped reply;
credentials and server configuration are excluded. Native source/hash/contract are in the final
server excerpt. The DLL/SO remains a dev dependency, not a compliant distributable release.

Still open: latest contract-compatible Windows/Linux release assets, N25 real client,
performance qualification, persistence #86, local buckling-critical HUD, command stale/solved
labels, empty-model UI, legacy Java physical postprocessing retirement, engine event/rigid pose
consumption and collapse/crushing/rolling. The v1 goal and #89 remain open.
