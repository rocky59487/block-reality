# Native verdict display API cleanup

Criteria d386ced preceded implementation9558351. Issue124's stale packet comment
was already corrected in the parent stack; this removes the unused numeric-only
hatch API and makes the numeric reference colour ramp private. The public result
colour method requires the native overload flag. No engine, packet, texture or
current rendering behavior changed. This is a development API removal: external
source callers of the removed one-argument APIs must use the explicit native flag.

Windows full core449 registered/437 PASS/12 platform SKIP; Forge124 PASS/0 SKIP.
Total573 registered,561 PASS/12 SKIP, no failures/errors. All applicable native
tests execute with the same published v1.5 DLL. Source/bytecode/jar guards and
their3 compiled arms pass; existing result packet goldens and36 quoted counts
pass. Linux palette control15 PASS, ignored-native-flag mutant compiles and
fails the actual verdict-boundary test, restored15 PASS. Source restored exactly.
The mutant is not a compile failure. No full Linux/native/client rerun is claimed;
the parent evidence is inherited only for unchanged paths.

Thirty-seven printed legend/native-flag colour/signed-hatch readouts from the
ordinary before/after jars match byte-for-byte. Tests cover native flags on both
sides of1, exact1, small/large and nonfinite display values, and enforce removal
of the public number-only APIs. Fixed legend colours and hatch patterns remain.

Jar15,286,370 bytes, SHA256
`03fff6f83036c7ab6d254db1b9577c72895053d5b86810b82d7ec5bf0ea5d492`,
source9558351c1bfa8f45fa9f64d418b7d4112ded8467. Against parent9d8cb6795e9c,
385 entries/234 classes are byte-identical; all native payloads, provenance,
notices, resources and contract pins are unchanged. StressPalette.class changes.

The first strict single-class assertion failed because nested LegendStop.class
also changed. Its original jar-identity.json is retained. Following the criterion's
requirement to explain any extra entry before acceptance, raw javap -c/-p/-s
outputs match exactly; verbose outputs show only source line180→169. The binary
files differ at exactly8 bytes, all the corresponding LineNumberTable entries;
all other bytes and class size2222 remain equal. No extra executable behavior
changed. See artifact-reviewed.json and the original verbose files/diff. The
verbose display diff uses replacement decoding for the JVM's localized date;
original javap bytes are retained without normalization.

This accepts the API cleanup with the disclosed nested debug-metadata difference,
not the initial one-class byte assertion. Issue124 stays open until actual Main
integration. Exact-head cleanup CI is pending. Parent native1f5c7b3 CI passes4
jobs/15 native step SKIPs; that receipt is not cleanup CI. Full CT construction,
actual-game thick-shell warning, Windows CM/N25, FPS and engine dynamics remain
open; neither this unit nor the parent claims v1 completion.
