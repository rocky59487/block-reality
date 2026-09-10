# Native v1.5 consumer integration — criteria before changes

2026-09-10. Start at CT/player evidencea52c28f. Module-only work:consume published
engine assets, synchronize module contract and adapt/validate the module boundary.
Do not build, modify or publish tectonic2. Construction Forge integration remains
open; the engine release is linear buckling SDK, not fracture/crushing/rigid dynamics.

Upstream module Main PR126 atd32c744 mirrors engine source
42ba7eb912bd7d763435ce48a945df6954b680ea, contract
5d4367f40de8687e3d861340d8aa13bcf2da52e2d63aef467cf2f52f213624cd.
Merge that existing module history, resolve contract files to the upstream exact
bytes, preserve the current module implementation and its tests/evidence.

Published v1.5 has two native ZIPs and a source archive in SHA256SUMS, with no separate
verification JSON asset. Add an explicit SDK inventory profile to release staging;
the existing profile still requires its verification asset. Do not accept arbitrary
missing/extra assets. Both profiles retain exact root/asset/source manifest/native
provenance/binary/SDK identity and licensing checks before any staging write.
The SDK profile is an integrity qualification, not a signature or a substitute for
executing the library. Record the chosen profile in staged provenance.

Independently read GitHub release asset metadata pins SHA256SUMS to
faa4d03dcbc973ed56904339dfe4297056d41348e77d5cbdaf823c2aa48539eb.
The downloaded DLL is749ceebe5aa382f1c25a5d28797ababed9f1d7c9750009a3d957fb7c74d0bd15;
SO2d048b9bfaf971d6b1971b7b0c36cc4942238448652735ba264dfbddf7d440a4.
Verify these against both archives and actual runtime hello before claiming delivery.

Required gates:

1. Published root/three assets/all source files/native archive manifests/SDK/licences
   match. Existing10 adversarial archive cases and4 independent guard removals still
   bite under SDK profile; legacy profile refuses this missing verification asset.
2. Full core/Forge checks on Windows/Linux with these exact libraries. All applicable
   native tests execute; retain platform skips and first failures. Existing result
   packet goldens and Java/native authority boundaries remain unchanged.
3. Same ordinary jar bundles both exact libraries, provenances and required notices;
   source/bytecode/jar guards exclude probes, executables and legacy physics. Compare
   retained module classes/material assets against the prior qualified artifact.
4. Actual jar-only production Java/JNA loading and Python CAPI produce identical full
   responses for the existing C5/C6/C8/C10 frame set on both platforms,3 sessions each,
   including cache/extraction tests. Add C14 native wall/rotation/mirror/indicative
   cases through the same consumer path without recomputing lambda or physical flags.
5. Real installed Forge server consumes this jar and reports its exact native identity,
   accepts the existing gameplay fixture and preserves typed native warnings/results
   through persistence/restart. Reuse isolated owned Linux server resources; original
   Windows permission dialog/server remain untouched.
6. Recheck the existing real Linux client geometry/HUD and new shell-indicative warning
   when a reproducible game input reaches it. Unobserved behavior remains open; do not
   label core/JNA checks as client or Windows CM/N25 acceptance.
7. Exact-head module CI and explicit native skips, reproducible artifacts and source
   identity. Engine release notes' CI billing restriction/historical failures/performance
   misses remain disclosed; no conversion into a consumer PASS or a v1 completion claim.

Timing/allocation remains Recorded unless separately frozen before optimization.
Any unexpected native result is retained and diagnosed on the module boundary;
do not edit engine code, invent new result fields or implement Java physical correction.

Gate3 first observation:after NOTICE changed, normal checkNativeOnlyJar reused the
bundleEngines output and shipped the old NOTICE. Source/native checks held the jar
copy back; first replay launchers therefore failed before any native load. Preserve
that jar/log/input set. Before fixing the build, make gate3 concrete:NOTICE,LICENSE,
third_party files, contract pin and selected staging input are declared Gradle inputs;
an actual incremental NOTICE change must update the generated and packaged bytes,
restoring it must update again, and an unchanged rerun should be up-to-date. Removing
that input declaration must make the artifact-equality oracle fail after a successful
Gradle task. This is an incremental packaging fault, not a Java compilation negative.
