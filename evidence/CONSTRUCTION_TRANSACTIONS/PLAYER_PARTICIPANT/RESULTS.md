# Player participant — adapter verified; full construction unit open

Source5d1fb6f; criteria3db4e37/60f02f6/cf0a22f and retained-file extension4dbff5a.
The server transaction coordinator now requires a durable baseline checkpoint before
PREPARED and rechecks all images/revision afterward. New Forge-side adapters provide
bounded canonical NBT, exact vanilla inventory-slot mapping and acknowledged atomic
whole-player-file writes for future checkpoint/commit/offline recovery callers.
They preserve unrelated data, refuse corrupt/foreign/stale files and ambiguous slots,
retain failed temporary files within explicit limits, and verify replacement bytes.

These adapters have **no production Forge gameplay caller yet**. Tests do not start
Minecraft, consume an actual player's items, recover real chunks or qualify undo/UI.
Manufactured metadata, lifecycle exclusion, chunk barriers, server entry/networking,
ordinary placement, blueprint/undo and real socket/restart tests remain open. None of
CT-1..9 is marked complete by this intermediate step.

## Verification

| Platform | Core | Forge | Combined |
|---|---:|---:|---:|
| Windows/JDK17.0.18 |437 PASS/12 SKIP|124 PASS/0 SKIP|561 PASS/12 SKIP|
| Linux/JDK17.0.20 |448 PASS/1 SKIP|124 PASS/0 SKIP|572 PASS/1 SKIP|

573 registered per platform. New checks:15 Forge adapter tests plus3 coordinator
checkpoint tests. Both full builds/checks pass; the existing native cases execute
with the delivered42e10f5 libraries. `native-test-execution.json` records individual
native-related classes and their actual skips; no SKIP is counted as PASS.

Each platform also passes37 forced JVM interruptions,37 fresh recoveries and one
concurrent-owner rejection using the existing six-file coordinator process fixture.
This requalifies coordinator ordering after the checkpoint addition; those synthetic
files are not Minecraft chunk/player restart evidence.

Three independent compiled behavioral mutants are caught by named AssertionFailedError
oracles:omitted checkpoint, dropped unrelated player ForgeData, accepted duplicate
inventory slots. Control45/restored45 pass without skips. Player writer faults at all
five real boundaries verify old data before replacement and complete new data after
replacement; fresh verification/replacement restores the exact original document.
Canonical images cover all payload tag types, lists, UTF/NUL, NaN payloads and bounds.
Vanilla NbtIo interoperability passes. Negative zero raw input is refused because the
pinned vanilla numeric factories normalize it; gzip optional headers/multiple members
are refused because this adapter accepts the single-member form vanilla writes.

## Artifact and unchanged payload

Development jar15,273,480 bytes, SHA256
`74aa09815fd335f25af2b7f27a62d53e13ba7ffa07c512112a0c6b997b27531a`.
It adds9 production classes.372 prior entries remain byte-identical, including all
native libraries, licenses, material assets and existing result packet implementation.
Coordinator/Host change;3 nested exception classes change only source line numbers
(raw verbose javap diffs retained). Jar/class guard and its3 compiled negative arms
pass. No tests, measurement helper, process probe or legacy physics enters the jar.

Windows DLL9761d735… and Linux SO53aae715… remain the previously delivered1.3.0
build42e10f5. During this step, engine v1.5 was published at42ba7eb and module Main
merged contract PR126. Its release/PR metadata are saved here. **This artifact and
these results do not qualify v1.5**; a separate consumer integration must follow.

## Cost recorded, not a performance gate

One JVM per platform; each of3 deterministic player NBT fixtures gets8 warmup and40
measured checkpoint/commit pairs. Each phase times full file capture, canonical
compression, atomic replacement, force and read-back; no game, chunk, native or UI
work. All288 canonical NBT images match across platforms. Raw CSVs and final files
are retained; this is not FPS or v1 performance qualification.

| Fixture | Windows checkpoint/commit p95 ms | Linux checkpoint/commit p95 ms |
|---|---:|---:|
|2 slots|93.469 /73.933|7.766 /8.498|
|41 slots|6.045 /6.150|10.182 /10.388|
|41 slots,16KiB custom tags each|27.474 /26.809|39.052 /38.995|

The Windows2-slot tail remains recorded despite its larger values. Large-document
median thread allocation is about18.74MB per write on both platforms. Real server
integration must avoid unnecessary checkpoints and measure the complete cost.

## First observations and evidence

First compile and14-test Forge/30-test core runs pass and are retained separately;
temporary-file quota test then raises Forge's adapter count to15. The Windows full
Gradle run succeeds, but its Python launcher fails afterward while decoding a mixed
encoding console log. Raw log bytes, XML and all37 recovery receipts confirm success;
the helper now records exit before display and uses replacement decoding only for
display. No test rerun was substituted for those original results.

The first jar comparison expected only2 changed classes and exposed3 additional
exception debug changes; verbose javap confirms they are line numbers only. Its first
UTF-8 display attempt also met Windows-localized javap output; original bytes are
preserved and decoded as CP950 for the readable diff. These were inspection assumptions,
not production behavior failures or qualifying negative arms.

ZIPs preserve every original file byte and include verified per-file SHA256 manifests.
`receipts.json` pins the archives/logs/helpers, `source-manifest.json` pins the412 source
and configuration files used for the isolated mutation workspace, and `summary.json`
states the remaining scope. No existing game client/server or engine checkout changed.
