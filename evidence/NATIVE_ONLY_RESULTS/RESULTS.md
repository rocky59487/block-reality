# NATIVE_ONLY_RESULTS — 2026-09-10

The shipping API no longer contains beam/shell force-field records, legacy stress recovery,
neutral-axis reconstruction, or constructors that infer verdicts from D/C or buckling factors.
Member and shell snapshots accept supplied display samples and explicit overload flags;
AnalysisResult requires explicit capacity and buckling flags. Its failed-result factory sets
both false as part of the explicit failure state. Channel 11 and its payload schema are unchanged.

The old JSON/shm decoders now live in core test sources. The physical field helpers and legacy
result records live in `mod/core/src/testFixtures/java/com/blockreality/testlegacy`, added only
to core/Forge test source sets. Old engine comparisons still exercise both independent decoders
and all existing field assertions. Crossing into current rendering tests requires `snapshot()`.
No compatibility constructor, formula helper or fixture is part of main or the shipping jar.

| Executed gate | Result |
|---|---|
| NO-1 API/flag/absence tests | 3 PASS; all public constructors require flags; contradictory numbers cannot replace them; no inferred neutral axis |
| NO-2 actual reobfuscated jar | 171 classes inspected, zero forbidden entries/constants |
| NO-2 legacy jar negative control | Installed parent jar rejected with 20 entry/reference findings |
| NO-2 compiled mutation arms | Both compile with exit 0; bundled legacy type and dormant field-reference-only jars are rejected |
| NO-3 frozen packet oracle | All 12 pre-removal beam/shell packet SHA256 values unchanged, Windows and Linux |
| Windows core check | 376 registered / 349 PASS / 27 SKIP |
| Windows Forge build | 96 registered / 95 PASS / 1 SKIP |
| Combined Windows JUnit | 472 registered / 444 PASS / 28 SKIP / 0 FAIL |
| Linux native recovery + API | 11 PASS / 0 SKIP, including 8 actual native recovery tests |
| Linux native packet + envelope + golden bytes | 11 PASS / 0 SKIP, including real native analysis in both F32/F64 storage modes |
| Documentation counts | 36 agree; test-only legacy compatibility suite ran 330 checks |

The Linux tests cover native beam and shell recovery, loaded-point sides, governing identities,
axes and rotated sections, large F64 coordinates, native flags, surface samples, unit conversions,
packet encoding/decoding and channel-11 source/revision acceptance. The 22 Linux tests are targeted
runs of overlapping suites, not 22 additional registered tests. Native source/library and contract
are unchanged: delivered #37 `95a03e82bbc50c53eac97b5289b79d8e640f8075`, library SHA256
`bbf38ff3d4633d4c9e0e7f42fb87e1e3183a6f9d7418ed88432b05392b36c121`, contract
`4b11cc738790d32d576ac71ecd1cb57f04d3ed9186a9e43f89492ae13c258801`.
They ran in the separate `/home/rocky/br-native-only` workspace. The live CLIENT_MATERIALS server
and client baseline were not restarted or replaced.

The new development jar is 392,072 bytes, SHA256
`0381a9e2e0d1336bdff421981d7b4cde98c26c9e06d350d4adfb26c48a1e8489`.
LICENSE/NOTICE are present. Native libraries, executables, test fixtures and retired codecs are
absent. This is an ordinary development jar, **not a self-contained native release artifact**.
The jar gate parses all class constant pools, including descriptors used only by uncalled fields.
Its two mutation classes are compiled with javac and injected into copies of the actual jar.
The second arm omits the forbidden class itself; the reference alone must trigger refusal.
`forge build`/`check` now require this artifact gate and both mutation arms, including in CI.

Reproduce from repository root with Java 17:

```powershell
mod\gradlew.bat -p mod check '-Dbr.sidecar=C:\Users\wmc02\Desktop\block-reality\dist\br-sidecar.exe'
forge\gradlew.bat -p forge build
python scripts/check_native_only_jar.py forge/build/libs/blockreality-0.4.0-dev.jar --mutation-checks
python scripts/check_docs.py dist/br-sidecar.exe
```

Linux native legs use `-Dbr.engine=<delivered library>` with core's InProcessRecoveryTest /
NativeOnlyResultsTest and `BR_ENGINE=<delivered library>` with Forge's NativeResultPacketTest /
AnalysisUpdatePacketTest / NativeOnlyPacketGoldenTest. The raw command logs and hashes are retained
in `receipts.json`; committed build excerpts identify their complete local source. JUnit counts
are read from XML. The final native code changed only formatting after the Linux run; the final
Windows builds and frozen byte oracle ran again after that formatting change.

First outcomes are preserved rather than relabelled:

1. The pre-removal packet capture intentionally failed its empty oracle to record all 12 actual
   hashes. They were committed in `f1fff7e` before any production removal; this is oracle collection,
   not a production defect or a mutation gate.
2. The first core/Forge compile found 11/4 old field-accessor and test-model type references.
   Native absence assertions now check that the field is structurally absent. Legacy diagnostic
   assertions retain their fields in test-only records. One renderer adapter was then missed;
   the attempted correction used a wrong working-directory path and left the same compile failure.
   All these compiler failures are retained; none is counted as an oracle for the mutation arms.
3. The first completed Forge suite and the first completed core suite passed. Later complete
   runs include the three new API tests and required artifact gate. No legacy assertion or test
   was deleted or skipped to make the migration pass.

The artifact gate proves absence of the named retired footprint, while the source/API review
and existing native-value tests cover how the current path behaves. Drawing still performs
coordinate/unit conversion, palette mapping, maxima for display scale and bounded interpolation
of supplied samples; these values do not become engineering verdicts or reconstructed forces.
This unit does not qualify performance, persistence, textures, lifecycle/rigid-body features,
Windows native packaging or a v1 release. Real client world entry is still pending user handling
of the Windows Security dialog documented by CLIENT_MATERIALS; no UI/security action was taken.
