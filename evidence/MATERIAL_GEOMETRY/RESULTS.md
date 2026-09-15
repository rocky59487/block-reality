# MATERIAL_GEOMETRY — module qualification

2026-09-10. Criteria `7e0b70f`; replay-harness correction criterion `c2db539`.
Shipping source `b10884b5802c24121263fb779319860c7f95ec9c`, based on installed-native-server #122.
First render/source candidate `9b6c707`; extra inventory probe correction `7263a85` is test-only.
No engine source, contract or delivered native binary was changed.

The bounded MG gates passed locally. This is a development candidate, not v1 or the
original installed Windows CM/N25 qualification. CI is checked separately against the
final PR head; its exact run and raw receipt are linked in the PR qualification record.

## Delivered behavior

Four frame products use their declared non-square SI dimensions: steel200×400,
150×300,100×200 mm and timber140×240 mm. The same `ProductForm` feeds model baking,
outline/collision bounds and the scan surface. X/Y/Z use distinct cut/end faces.
Undeclared cells carry an ochre ! on all six faces. Ordinary atlas/item rendering is
retained; there is no missing-texture fallback in the observed products.

Concrete/brick remain1m³ material cells. Panels also remain material cells: their
placement axis is not a physical panel normal. MG does not invent a thin panel shape.
Samples use separate visual width/depth extents and preserve native station jumps.
Neighbor coverage requires full face contact. Product/axis changes suppress incompatible
old member/plate surfaces. No Java section properties, forces, damage, motion or
engineering verdicts were added; vanilla collision shapes serve player interaction.

## Gates and observations

| Gate | Evidence and result |
|---|---|
| MG-1/2 | `final-client/mg-models.json`: actual atlas-baked quads, item quads and real outline/collision boxes for9 products×4 states; independent literal bounds/end-face oracle,127 checks PASS |
| MG-3 |5 ProductForm tests, new unequal-extents/discontinuity test, and real native4 products×3 axes; full suites retain12 packet goldens. Three compiled behavioral mutations fail their named assertions and restored sources pass |
| MG-4 | Original16 scenes/45 assertions pass on both first and final candidates. `native-readout-comparison.json` compares every previously recorded native readout field for16 final receipts and all bootstrap fields with NCR; all equal. Six additional unscanned/hotbar/inventory captures PASS |
| MG-5 |14 assertions PASS through actual `MultiPlayerGameMode.useItemOn(real mc.hitResult)`: face-based X/Y/Z placement, empty-hand sneak X→Y→Z→X, authoritative server blockstates/revision advances, ray through empty cell space and ray hitting visible section |
| MG-6 | Full Windows/Linux suites,207 shipping classes plus2 compiled bytecode negative arms, bundle9 injections, same-jar48frame×3/direct/JVM/permission/cache/pin/two-JVM checks PASS; details below |

The interaction is **programmatically driven vanilla client input over a real socket**,
not a human Windows session. RCON prepares and observes fixtures; it does not create the
accepted placement/cycle results. Client prediction and later server checks are retained
separately. Revision advances are observed; this is not the future atomic construction
transaction/one-revision gate. The controller never injects accepted native results.

The final16 screens include en/zh at1280×720 and1920×1080. Additional captures show frame
sizes/axes, concrete/brick/panel cells, unresolved markers and real hotbar/inventory items.
Visual review checked beam/column/plate scans, all product groups, warning markers and
item rendering. Vanilla tutorial toasts remain present. Renderer: llvmpipe/Mesa software;
these captures make no FPS claim.

## Full tests and artifact

| Platform | Core | Forge | Total |
|---|---|---|---|
| Windows |407 PASS /12 platform SKIP |109 PASS /0 SKIP |516 PASS /12 SKIP,528 registered |
| Linux |418 PASS /1 platform SKIP |109 PASS /0 SKIP |527 PASS /1 SKIP,528 registered |

All20 native-related tests execute. The28 legacy sidecar compatibility cases also run
against existing test executables; none enters the shipping jar. Windows skips11 legacy
POSIX fake-process cases and1 executable-permission case; Linux skips1 Windows path case.
The first full runs omitted the legacy executable option and skipped those28 cases;
their XML remains. Supplemental28-case runs and final complete suites then passed.
`summary.json` and platform `*-final-tests.json` enumerate counts/skips. Documentation's
36 quoted counts agree with measured330 legacy engine checks,41 closed forms,528 Java tests.

Jar: **15,216,948 bytes**, SHA256
`8e587a715ebd3e169e4a0e6afa9d22cf60cac400eb7093aecf76b1a07f5c3c7a`.
Candidate ZIP: **15,103,538 bytes**, SHA256
`4f1dbb83901df2f8e60e3e3eca26fe8b3a3f50f2a568fd2768decc2f3869ab78`.
Local artifacts are in `build/material-geometry/`. The normal jar contains no probe classes.
Both embedded native libraries are byte-identical to INS/NCR: source42e10f5, version1.3.0,
contract4b11cc738790…, Windows DLL9761d735… and Linux SO53aae715….
`windows/distribution.json` lists hashes, native equality and every changed jar entry.

## First failures and test isolation

1. First isolated server build omitted LICENSE/NOTICE from the module source archive and
   failed at `bundleEngines`. `first-client/server-first.log` is retained. Adding licensing
   and build-support files from the same commit fixed the fixture; no licensing gate was
   bypassed. Final server was built from a fresh complete source archive.
2. The original replay harness controlled child JVM OpenBLAS settings but not its Python
   direct process. First Windows replay (`windows/jar-windows-final`) failed exact equality
   on6 C10 f32/f64 replies. A process-start Haswell/one-thread control passes, and **every
   first JVM reply equals its controlled direct counterpart**; comparison is recorded.
3. The first harness correction, setting Python's `os.environ` after startup, still failed
   the same byte oracle. Those actual frames/logs remain in `jar-windows-harness-fixed`.
   The final module test script restarts its driver under the controlled environment when
   needed, before creating output or loading a library. Fresh Windows/Linux invocations
   with neither variable set both pass48frame×3, two-JVM/cache/permission/pin checks.
   This does not change the engine, its arithmetic, or the equality threshold. The precise
   native runtime mechanism behind the late-environment behavior was not investigated.

The three geometry negative arms are separate from these infrastructure failures:
swapped width/depth, non-contact face culling and equalized sample extents all compile
and fail the specified behavioral assertion. Their logs/XML and restored PASS are under
`mutations/`. Compiler errors are not counted as successful negative arms.

## Scope and provenance

`receipts.json` verifies2900 copied raw files against their source paths/hashes. Raw XML,
frames, screenshots and logs preserve bytes with `.gitattributes`; repeated native cache
libraries/mutant jars are omitted from Git, with commands and hashes retained. Shipping
source hashes are pinned in `shipping-b10884b-sources.json`. The first raw files are never
overwritten by later successful attempts. Windows/Linux qualification runs use the same
final jar; this tests packaging/replay, not independent engine physical correctness.

Owned MG clients/servers were shut down. Original Windows profile, security dialog,
old installed jar and CM server on25586/25587 remain untouched. The prior INS installed
server proof applies to its earlier jar, not this changed jar. Current MG game checks
use isolated Forge development source sets. Full installed-client qualification, resolved
panel geometry, atomic construction transactions, independent region scheduling, FPS/soak,
and engine-driven fracture/crushing/rigid-body lifecycle remain open v1 requirements.
