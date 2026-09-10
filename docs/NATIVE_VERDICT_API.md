# Native verdict display API cleanup — criteria before changes

2026-09-10, base1f5c7b3 / published-v1.5 consumer PR127. Addresses module issue124.
The stale result-packet comment is already corrected in this stack. The remaining
unused utilizationHatch(double) can independently infer failure from a displayed
number. Production renderers already call utilization(dc, nativeOverloaded).

Remove the unused numeric-only hatch API. Make the numeric-only reference colour
ramp private so future result callers must supply the native verdict; keep the
fixed legend colours/patterns and the public native-verdict colour method unchanged.
Private reference stops are a visual legend, not an engineering decision. No new
threshold, physics, native call, packet field, texture or visible rendering change.
This is a development API cleanup; there is no public numeric-only replacement.

Before/after checks: existing palette values, signed hatch directions and all
three legend stops remain exact. For native flags both ways at nextDown(1),1,
nextUp(1), small/large ratios and nonfinite values, the native flag alone selects
the overload swatch. Public API inspection rejects the removed hatch and one-arg
colour entry. Use the existing palette tests to cover this boundary without
adding unrelated tests or changing their registered count.

Run core checks with the qualified Windows v1.5 library, Forge build/tests and
the ordinary jar guards (including unchanged result packet goldens). Exercise one
compiled behavioral mutant that ignores the native overload flag: the boundary
test must fail after compilation. Restore and pass the same test. Compare the
new jar against9d8cb6795e9c: only StressPalette.class may change; all remaining
classes, both native libraries, resources, notices and manifest must match.
If another entry changes, retain and explain it before accepting the artifact.
Existing Linux/JNA/client qualification is inherited only for unchanged paths,
not claimed as rerun. No engine files or claims about missing dynamics change.

Record exact source, first failures, raw logs/XML and exact-head CI with native
steps marked SKIP where applicable. Issue124 stays open until its actual Main
integration; this local cleanup alone must not close it or claim v1 completion.
