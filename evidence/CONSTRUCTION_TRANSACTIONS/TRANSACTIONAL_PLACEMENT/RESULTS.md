# Ordinary placement — implementation and qualification in progress

This is a mod-only, single-cell caller of the existing construction coordinator.
Production implementation is committed at a5900cd; source-identity.json compares the
exact fourth build snapshot with that commit and records text line-ending normalization.
Criteria9318fd4,4f1a024,840dce8 ande0f22d4 precede the corresponding implementation
or qualification clarifications. Full CT, blueprint, undo, normal-break ownership
reconciliation, performance and v1 release remain open. Additional installed admission
and player-participant cases listed below are still needed before this unit is complete.

The nine structural items open a native placement screen and a product-shaped world
ghost. A separate construction channel carries bounded preview and confirmation
messages. Ordinary server ItemStack/ForgeHooks use does not place or debit. Confirmation
uses authenticated server offers and a complete world/player/identity transaction.
The client stores its exact confirmation before sending and can query the same durable
receipt after losing the response or restarting. Existing analysis protocol11 is retained.

Candidate FOURTH is15,439,737 bytes, SHA256
`7cda56e40cc0c85edff815a26d2361e98f7287fc60bd2443a7ad788e829e7285`.
It contains291 production classes and the unchanged published tectonic2 v1.5 libraries,
not executables. Its only different entry from candidate SECOND is PlacementScreen.class.
Server, journal, player, metadata, network, engine, licences, provenance and access
transformer entries are byte-identical. The source archives include all attempts;
candidate THIRD's full Forge run failed the translation scanner before a qualified
third artifact was copied. FOURTH changes that test's enum coverage, not the UI source.

## Complete native-enabled suites and packaging

487 core and152 Forge tests are registered on each platform,639 total. Windows has
627 PASS/12 platform SKIP; Linux has638 PASS/1 platform SKIP. Every skipped test is
named in dual-platform-tests-fourth.json and matches the earlier axis baseline's skips.
Windows uses the full third core result because every mod source/test/build file is
byte-identical in the fourth snapshot. Both fourth Forge runs and Linux fourth core
run completed successfully. Existing analysis packet goldens execute in these suites.

The ordinary jar passes its291-class constant-pool guard and three compiled forbidden
entry/reference counterexamples. Bundle verification confirms both library hashes,
the contract, included licence texts and zero executables; all nine bundle injections
are rejected. All53 contract files retain the pinned digest. These are mod-consumer
results; no engine source was modified or built. Full suites do not constitute FPS,
large-world latency, Windows game-client or soak qualification.

## Installed ordinary server and replay

Fresh Linux Forge1.20.1-47.4.13 servers load the Windows-built ordinary jar from mods/.
Separate test-only harness jars contain no production classes or access transformer.
These controls use explicitly synthetic FakePlayers; the socket section uses an
actual connected synthetic client instead.

The strengthened control passes277 checks; its fresh server restart passes209.
It independently changes only Count4 to3 in the raw before-inventory to derive the
expected survival image. It checks every other saved inventory field, creative no-debit,
private protection barriers, exact cancellation rollback, two competing actors, one
publication revision and a nested ordinary support change that still gets observed.
One hundred admitted, identical confirmations before and after restart retain their
original receipt with no additional material, identity or journal effect. Each call
compares clocks immediately before/after itself and records intervening native ticks.
Earlier275/209 controls passed with a less independent debit oracle and remain archived.

## Eight real process interruptions, sixteen fresh recoveries

The ordinary candidate SECOND is observed through JDI breakpoints. The debugger
suspends all threads, invokes a read-only test-probe capture on the Server thread,
verifies that thread remains suspended, and never resumes it before the owned process
group is SIGKILLed. Each interrupted server exits-9. The breakpoint source/line/BCI,
class origin, owned PID and raw journal/player/region files are retained. This is a
process-crash test, not hardware power-loss certification.

| Breakpoint | Disk cell at interruption | Disk material | Recovered decision |
|---|---|---:|---|
| PREPARED / DIRECTORY_FORCED | AIR |4|ABORTED|
| world applied | AIR |4|ABORTED|
| player applied | AIR |4|ABORTED|
| world flushed | declared steel Y |4|ABORTED|
| player flushed | declared steel Y |3|ABORTED|
| COMMITTED / TEMP_FORCED | declared steel Y |3|ABORTED|
| COMMITTED / DIRECTORY_FORCED | declared steel Y |3|COMMITTED|
| publication | declared steel Y |3|COMMITTED|

Each first recovery passes11 checks and each second recovery12. Recovery validates
the complete offline player document before login, exact world cell, manufactured
ownership and revision floor. The second restart leaves the terminal journal bytes
unchanged. Independent Python decoding also checks the actual captured region palette
cell and the entire raw gzip player file at all eight barriers. The final-world archive
is explicitly taken after the second recovery; it is not an earlier barrier snapshot.

## Compiled behavioral counterexamples

Each arm compiles independently, grafts only its specified class into the ordinary
candidate, verifies every other jar entry unchanged, loads in actual Forge, and fails
the intended behavioral assertion. Probe FAIL is retained; the outer driver reports
that the expected negative was caught.

- Debit2 instead of1 fails the independent live-material check at the COMMITTED
  temporary-force observer. That observer failure propagates through the real owner.
- Dropping the held item's tag fails the independent complete-inventory comparison.
- Shrinking the current held stack on exact replay fails replay1's no-effect comparison.
- Ignoring EntityPlace cancellation commits and fails the expected-ABORTED assertion.
- Omitting the duplicate-neighbor guard produces revision2 and fails the one-revision
  assertion. The nested genuine support-change control remains observed.

## Actual client, network, keyboard and screenshots

Two isolated Linux development clients use the exact shipping production sources plus
a test-only probe, with llvmpipe and a synthetic offline account. They connect through
real Netty sockets to the ordinary installed server jar. Original Windows Minecraft,
its security modal and the original CM server are untouched.

The first workflow passes48 checks but visual inspection finds excessive blank height
at1920x1080/GUI scale1. The second workflow, after content-sized layout and terminal
focus fixes, passes52 checks. Each workflow starts two fresh client JVMs and exits
both clients and its server normally. Eight screenshots per workflow preserve English
and Traditional Chinese,1280x720/scale3 and1920x1080/scale1, ready/committed/refused/
pending/retried states. Native notices/tutorial overlays were left visible.

Both workflows exercise actual game-mode use at the real crosshair, three server-authored
axes, Escape cancellation, survival material synchronization, creative no-debit, real
EntityPlace protection cancellation and normal chest interaction priority. The second
adds native Tab-to-cancel, Enter-to-cancel, Enter-to-confirm and Done focus after the
retry control disappears. Buttons stay inside the viewport. Inspected final screenshots
show content-sized panels and a visible product-shaped ghost beside them.

For lost-response recovery, the test-only client socket observer drops exactly one
actual server Outcome before the production handler. The server commits once; the
client times out without inventing a decision, retains its outbox after closing, and
exits. A new client JVM restores the old binding even when the cursor targets a new
cell. Its actual C2S confirmation is byte-identical to the original. The server returns
the existing receipt without another debit, cell, identity or journal change; the client
clears only that pending record. Raw packets, outbox bytes and both endpoint states
are archived. This demonstrates a client JVM restart, not an untested client power loss.

## First failures retained

The first installed harness used a public-constructor lookup for a package-private
player adapter, causing the observer/transaction to fail; the visible old trace is a
later assertion and the reflective cause is identified from source. A subsequent real
control exposes duplicate neighbor publication, revision0 to2; a diagnostic repeat
retains exact clocks. The fix suppresses only that already-announced root event.
A harness diagnostic compilation also fails on a shadowed variable and is retained.

The first negative-jar packer reuses ZipInfo and corrupts its own read offsets; it
fails before runtime. The corrected packer copies ZipInfo and verifies every entry.
The independent disk decoder's first attempt incorrectly requires a padded final
region sector. Mojang RegionFile.flush forces data without padding; close performs
padding. The original tool and failure are preserved; corrected decoding bounds the
actual payload and passes all captured files without changing them.

The first full Forge run fails because LangKeysTest does not yet enumerate the new
dynamic construction status keys. The fix walks ConstructionProtocol.Status alongside
the other enum families, retaining both missing-key and dead-key assertions. No key
is excluded, no status is removed, and the full fourth runs pass.

## Remaining qualification and development

Installed changed actor/domain/confirmation binding, held-slot/state conflict, offer
expiry/rate/capacity, Count1/offhand, additional capability/full-player preservation
and reentrant side-effect cases still need explicit runtime receipts. The server-EXPIRED
new-preview branch and narrator/very constrained content scrolling also need direct
client coverage. Recorded transaction costs and exact-head CI remain to be captured.
These are open tasks, not passes inferred from code or nearby tests.

Blueprint/multi-cell placement, inverse undo, normal-break ownership reconciliation,
independent-region scheduling and engine-authoritative motion/event consumption remain
v1 work. Published v1.5 does not deliver nonlinear collapse, crushing, contact or rigid
motion; Java does not synthesize them. Issues12,17,86 and89 are not closed by this unit.
