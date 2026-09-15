# Placement boundaries, explicit expiry recovery and native narration

This supplements the immutable FOURTH report one directory above. Production source
56541f4 changes only PlacementScreen and two translations. The fifth ordinary jar is
15,440,153 bytes, SHA256
`87bb2ed0d34cb6adcf6cc3bbf7508acfbde663c3d20e6956a2017f7fa57892d2`.
All 291 classes remain present; only PlacementScreen.class and en_us/zh_tw differ
from FOURTH. Server transactions, network, native libraries, licences, provenance,
access transformer and all other entries are byte-identical. Source identity checks
all 446 snapshot files against the commit, explicitly recording CRLF normalization.

This is mod-only work. Published tectonic2 v1.5 remains unchanged. This increment
does not qualify complete CT, multi-cell construction, undo, performance or v1.
Acceptance additions are in docs/CT_PLACEMENT_BOUNDARIES.md.

## Installed ordinary server

Two fresh installed Forge servers load ordinary FOURTH, whose server/core/native
bytes equal FIFTH. Synthetic authenticated FakePlayers call the production service;
the normal tick clock, offer cache and rate admission are not bypassed. Both servers
exit normally with code 0. The first harness passes 328 checks. The second passes
455 after strengthening the capacity oracle to inspect every actor's exact token
before and after logout, instead of only the total size and first token.

The actual cases cover all changed terminal bindings (actor, token, domain, session,
base revision and plan hash), exact terminal replay, fresh UUID with a consumed offer,
and unexecuted foreign actor/token/session/domain/base/hash. Pre-admission refusals
allocate no journal or player file. Changed selected slot, held NBT/product, target
state, game mode or reach produce only their durable REJECTED receipt, with exact
world/player/identity/publication preservation. Snapshots are taken after legitimate
fixture changes. Adventure, out-of-reach, nonempty cursor and foreign-dimension
previews remain read-only.

Count=1 in the main hand and offhand removes only its exact saved slot. Offhand
Count=4 changes only its Count byte. The independent expected image preserves all
other slots, selected index and typed NBT, and equals the actual saved inventory.
Eight invalid requests or exact terminal replays consume admission; the ninth is
RATE_LIMITED, with real ticks restoring admission. Replacement invalidates the old
offer. The expiry assertion runs at exactly 200 real elapsed server ticks. A 65th
actor receives CAPACITY while all 64 exact tokens remain; logout removes only that
actor's token and allows the waiting actor to obtain a new offer.

Each run retains raw before/after full player documents, player-file bytes, journal
bytes, cell images, clocks, request bindings, ordinary class origins and server logs.
Final world archives are explicitly after normal shutdown, not crash captures.

## Actual client, scrolling and narration

The third workflow passes 46 checks with one actual Linux development client using
the fifth production sources plus an opt-in test probe, a fresh synthetic profile,
llvmpipe and a real socket to the ordinary FOURTH server. The client and server both
exit normally, without cleanup termination. The original Windows Minecraft/security
modal and CM server are untouched.

A test-only outbound observer drops exactly one real confirmation before the server
handles it. Client timeout and closing retain the identical durable outbox. After
at least 200 server ticks (offer already received by 1062; retry after 1280), an
explicit byte-identical C2S retry receives server EXPIRED. The reply alone still
retains the pending record. Only the labelled new-preview action clears that exact
expired record and obtains a fresh preview, with no confirmation or durable edit.
A separate confirmation with a new UUID then creates exactly one piece and one debit.
Raw frames, outbox files and both endpoints are archived.

Five raw screenshots were rendered and visually inspected: large Chinese ready and
committed, small Chinese expired, and English unresolved at a real 320x240 GUI before
and after scrolling. The latter has a 126-pixel body viewport and 30-pixel overflow;
PageDown moves from 0 to 30, PageUp returns to 0, and controls/focus stay fixed. Text
clipping is confined to the scroll viewport and the complete close notice is reachable.
Native tutorial/toast messages remain visible in the captures.

Previously native narration exposed the screen title without the placement details.
PlacementScreen now shares its visible detail components with getNarrationMessage,
including product, declared axis, material cost, target, status and pending notice.
Changed messages trigger the native narration path while respecting the user's
narrator setting. The actual ScreenNarrationCollector includes those details and
the focused native confirm/new-preview/Done controls across READY, unresolved,
EXPIRED and COMMITTED. This qualifies narration text, not host audio/TTS output.

The first third-client helper compilation fails because its new reflection probe
omits the Screen import. Its source and failing log remain in probe-build-history.zip.
Adding that import to the test-only helper makes it compile. Production Forge builds
already passed and were not altered to accommodate the helper.

## Verification and recorded costs

Full Forge suites pass on Windows and Linux with the same platform skips as FOURTH.
The unchanged core source reuses the existing native-enabled full results. Combined
coverage remains 639 registered: Windows 627 PASS/12 platform SKIP; Linux 638 PASS/1
platform SKIP. Fifth jar constant-pool and bundle/licensing gates pass. The unchanged
guard scripts retain the previous three compiled class arms and nine package injection
negatives; they are not represented as rerun here. No test probe ships in the jar.

Each installed boundary run records 127 preview/confirmation calls. The cost tables
separate three new successful commits from nine terminal receipt replays. New commit
elapsed times are 143.3–172.6 ms in the first run and 161.0–198.7 ms in the second,
with roughly 7.07–7.23 MB allocated on the calling thread. These are un-warmed
correctness fixtures including the test EntityPlace observer, synchronous durability
and background analysis on this machine. They expose work to optimize; they are
Recorded values, not FPS, a performance threshold, a controlled comparison or v1
high-performance acceptance.

Prior exact head 587786b CI34476235037 succeeds in four jobs but skips 15 native steps;
its raw job/step receipt is preserved. Updated head CI is checked after this evidence
commit and is reported on PR135. Native execution evidence comes from local suites,
not those skipped CI steps.

## Remaining work

Additional item/player capability preservation, unrepresented nested saved-player
fields, and reentrant foreign-hook effects still need direct adversarial qualification.
The existing root-key baseline check has not been qualified for nested extensions;
do not infer preservation from ordinary NBT cases. Axis metadata release bindings
also warrant review. The measured durable commit cost needs dedicated work.

Multi-cell blueprint/inverse undo, ordinary-break manufactured ownership reconciliation,
region scheduling, Windows CM/N25, large-world performance/soak, engine-authoritative
pose/event consumption and v1 release remain open. No Java physics is added. PR135
stays draft and issues 12,17,86,89 remain open.
