# Published v1.5 SDK: module consumer qualification

2026-09-10. Criteria: `docs/NATIVE_V15_CONSUMER.md`, committed at1972f7f;
incremental packaging extension ata132e32 preceded its fix. Gates1–5 PASS;
gate6 real-client recheck and gate7 exact-head CI remain OPEN. This is a development
jar, not a v1 release. No engine source was modified, built, merged or published.

## Identity and artifact

Engine release v1.5 / runtime1.5.0, source
`42ba7eb912bd7d763435ce48a945df6954b680ea`; contract
`5d4367f40de8687e3d861340d8aa13bcf2da52e2d63aef467cf2f52f213624cd`.
Module Main PR126 was merged into this branch. All53 mirrored contract hash entries
pass. Release asset API metadata independently pins the SHA256SUMS file; root pins,
both SDK inventories,5659 source files, provenance, required SDK files and licenses
were verified before staging. The explicit `sdk` profile does not require a separate
verification JSON absent from this release; the default legacy profile still does.
All11 archive checks and4 independently disabled guards pass.

Artifact source `1a60418bc9e747ab93fb79b01549e70e74d76bc8`:
`build/native-v1.5-consumer/blockreality-0.4.0-dev-v1.5.jar`,15,286,415 bytes,
SHA256 `9d8cb6795e9c41514697a6eb2492fd00ef9eb484b4b5b30f627371967702de30`.
Windows DLL SHA256 `749ceebe5aa382f1c25a5d28797ababed9f1d7c9750009a3d957fb7c74d0bd15`;
Linux SO SHA256 `2d048b9bfaf971d6b1971b7b0c36cc4942238448652735ba264dfbddf7d440a4`.
Against the CT player artifact, all236 classes and46 material/assets entries are
byte-identical. Exactly8 resources change: two libraries, manifest, three provenance
files, contract pin and NOTICE. No entries added/removed. Jar/production guards and
all3 compiled fault arms pass. NOTICE describes native-only distribution and links
the exact source archive; retained license bytes match the verified inputs.

## Execution

Full checks atfc5424 (later changes affect packaging and qualification helpers):

| Platform | Core | Forge | Total |
|---|---|---|---|
| Windows/JDK17.0.18.8 | 437 PASS /12 platform SKIP |124 PASS |561 PASS /12 SKIP |
| Linux/JDK17.0.20 |448 PASS /1 platform SKIP |124 PASS |572 PASS /1 SKIP |

573 registered tests on each platform. All applicable native tests execute. Final
packaging at1a60418 independently passes the source/bytecode/artifact checks.
The unchanged ordinary jar loads through production Java/JNA on both platforms.
Each platform runs96 request/reply frames in3 fresh direct-CAPI sessions and3 fresh
jar/JNA sessions: existing C5/C6/C8/C10 plus C14 wall/rotation/mirror, thin/thick and
f64/f32 cases. Full response bytes match within each platform; no cross-platform
bit-identity claim. Cache/extraction, corrupt/missing pin, permission and concurrent
JVM checks pass. Native C14 corpus also passes3 repeats on each platform.

C14 reads the delivered native factor/flags/warnings; Java computes no eigenvalue.
Thin/thick wall factors are50.56905199441451/200.46984474255567 on Windows and
50.5690519944142/200.46984474255535 on Linux. Rotation/mirror/precision checks use
the contract's1e-12 relative relation. Thick cases preserve the native indicative
bit and one affected-island warning; thin cases carry neither.

An ordinary installed Forge1.20.1/47.4.13 dedicated server uses only the exact jar
in mods/, a fresh world and cache. All10 gameplay checks pass: beam/column/slab,
support removal/restoration, undeclared-axis refusal, reset and mixed-world local
buckling warning preservation/removal. After normal save/stop and fresh restart,
native command readouts and15 member lines match; session revision restarts as
expected. Cache path, bytes, SHA and mtime are unchanged.156/150 production class
origin lines resolve to the installed jar; no development/probe classpath. Both
owned server processes exit0 and their ports are released. Original CM server and
Windows security dialog are untouched. See `windows/installed-summary.json` and
raw `linux/installed-*` receipts.

## First failures retained

After NOTICE changed, Gradle reused bundleEngines output and packaged the old notice.
The first artifact was withheld, so the first replay launchers failed before native
loading. The stale jar stays in the original build directory; its hash/identity and
raw logs/frames are archived here. This was a real incremental packaging defect.
The fix declares staging selection, contract pin, LICENSE, NOTICE and third_party
as task inputs. Seven actual incremental runs prove unchanged/up-to-date, notice
edit, restoration and restored control. Removing the input declaration compiles
and builds successfully but leaves stale jar bytes, which the artifact oracle
rejects. Original criteria and failed observations are not replaced.

The first corrected Windows replay helper selected jna-5.12.1-sources.jar and failed
with NoClassDefFoundError. Its direct CAPI run passed; this is a setup failure, not a
native negative arm. The retained helper was corrected to select the exact runtime
JNA jar and verify Library.class before the successful run. Raw contract Python
deprecation warnings are retained without editing the mirrored engine corpus.

## Evidence scope and remaining work

`receipts.json` hashes123 archived files. Replay ZIPs retain selected raw frames,
JSON/log/text plus per-file SHA manifests; `__selection.json` lists omitted large
duplicate libraries/corrupt jars and marker files. Those originals remain in the
build qualification roots. Do not describe these ZIPs as complete directory copies.
XML ZIPs, release metadata/source manifest, licenses, incremental records and helper
sources are included. Raw bytes were not normalized or re-decoded for archiving.

Gate6 real Linux client geometry/HUD remains pending. C14 thick-shell warnings
verified through CAPI/JNA are not yet observations from an actual game input.
Gate7 exact-head module CI is pending; the retained CI9976fc0 belongs to the CT base,
not this branch. The engine release discloses historical Linux7 failures,41.7ms and
soft-SS misses and a CI billing limitation; those remain upstream limitations.
Windows CM/N25, actual installed client, FPS/soak, full construction transactions,
independent region scheduling and dynamic event/rigid-pose delivery remain open.
This SDK delivers linear shared beam/shell buckling, not nonlinear collapse,
crushing, contact or rigid-body motion. No Java fallback substitutes for them.
