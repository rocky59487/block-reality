# CT_CHUNK_PARTICIPANT — storage acknowledgment tested; live integration remains open

Criteria f693306 precede source70d1112. The module now has a complete-image chunk
writer using the existing IOWorker: prepare the whole bounded batch privately, wait
for every issued store, force storage, then reload and compare every canonical NBT
byte. A later synchronous issuance error still drains earlier futures. Storage
failure or mismatch latches the adapter; a new adapter must force/drain the same
worker before recovery reads. No worker is closed or competing region store opened.

The bind(ServerLevel) factory compiles against Forge47.4.13. It is not exercised in
a live Minecraft server by this unit and still has no production construction caller.
Host-side live capture, world mutation, saved baseline ordering, protection, inventory,
metadata publication and socket/UI behavior remain CT-1..9 work.

## Evidence

Seven new tests pass on Windows and Linux, including actual IOWorker region files
with multiple positive/negative region coordinates, complete supplied NBT and exact
reopen comparison. Other cases exercise independent immutable batches, all-or-nothing
admission, canonical budgets/headers, missing data, thread ownership, every storage
failure, late issuance failure and unresolved future draining. The region-file test
uses temporary fixture NBT; it is not a Minecraft world restart, live chunk serializer
test, power-loss proof or claim that arbitrary foreign-mod data is captured correctly.
JUnit removes those temporary region files; the archive retains the source/XML/logs,
not those ephemeral region file bytes.

Both complete Forge runs pass131 tests, zero skipped/failed/errors, using the published
v1.5 library. Native packet integration, fixed packet bytes and the ordinary254-class
jar guard pass, including3 compiling forbidden-content arms. Core production and test
sources have no changes from4737e3c, so its472-test dual-platform evidence is explicitly
reused, not called a fresh core run. Current combined registration is603; coverage is
Windows591 PASS/12 platform SKIP and Linux602 PASS/1 platform SKIP.

The first7-test Windows source/XML/log is retained. Linux7-test control/restored pass.
Both mutants compile and fail behavioral checks:

- Changing only the post-store force request to false fails whole-image persistence
  and outstanding-future ordering assertions.
- Removing ForgeCaps from the owned images before storage fails exact preservation,
  recovery and the actual-region reopen case.

No behavioral qualification failed before these intended negatives; no failing sample
was replaced. Mutation XML modification times and compilation status are checked, so
an earlier report or compiler failure cannot masquerade as the negative's observation.

## Artifact and limits

Jar15,324,472 bytes, SHA-256
`b5a9bab7e7b68559f20633aac4980079d8bf4a59340455f06339da7144d125c4`.
Exactly4 chunk-participant classes are added to the prior1315dce1e785 jar. All401
old entries/250 classes remain byte-identical, including both v1.5 libraries, contract,
notices, provenance and assets. Tests and fixture workers are absent from the jar.
The engine was neither edited nor built. No game transaction/FPS qualification or
v1 release follows from this source change.

Admission bounds are256 chunks,16MiB canonical NBT per chunk and64MiB aggregate.
An invalid final image refuses before the first store. These canonical-image bounds
do not bound the underlying vanilla parser's allocation before it returns a tag.
Pinned-source review confirms RegionFileStorage.read calls NbtIo.read(DataInput),
which uses NbtAccounter.UNLIMITED. This remains an explicit limitation to resolve
before claiming bounded corrupt-disk recovery in the live construction service.

The host must also resolve ChunkSerializer.write's caught capability-serialization
exception and validate block-entity/custom save-hook preservation. The writer preserves
all bytes it receives; it cannot prove a caller did not omit them before preparation.
The compiled lost-data mutant demonstrates writer readback enforcement, not that
unimplemented live-capture guarantee. Full CT-1..9 remains open with these limitations.

Raw platform logs/exits/XML, first control and mutation receipts are SHA-manifested
in the archives. All archive entries were reopened and verified. Parent PR130 source
87cc1cd CI run34446800967 has4 successful jobs and15 skipped native-build steps; its
API receipt is retained. This unit's exact-head CI is separately required after push.
