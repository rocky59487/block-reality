# CT_LIVE_CHUNK_CAPTURE — complete live images verified in installed Forge

Criteria a6ec28c precede implementation/test source5e5920d. LiveChunkCapture now
directly witnesses loaded block-entity and chunk-capability data, checks the pinned
vanilla serializer against those witnesses, posts one normal ChunkDataEvent.Save,
and rechecks the live serialization after the hook. Only additive custom top-level
hook fields are supported; removal or modification of serialized fields refuses.
The returned canonical image is independent of provider/hook-owned tags.

ChunkFileParticipant.capture binds the batch to the original ServerLevel, checks all
keys/loading/duplicates before invoking providers, and captures every image before
any store can be issued. It never generates chunks or promotes pending entities.
Existing limits remain256 chunks,16MiB/image and64MiB/batch. It does not clear the
unsaved flag, pump server tasks, publish results or change the engine. The future
construction host still owns exclusion, journal ordering and live mutation recovery.

## Verification

First Windows compile and18 selected chunk tests pass. Five new tests cover exact
capability/entity preservation, list-order independence, omissions, duplicates,
foreign coordinates, malformed types, packed flags and additive/rewriting save data.
Both complete Forge runs pass142 tests with zero failures/errors/skips, using the
delivered v1.5 native libraries. Packet goldens and the256-class ordinary jar guard
pass, including its3 compiled forbidden-content arms.

Core production/test sources and build configuration remain unchanged from4737e3c;
that source's full dual-platform472-test receipts are explicitly reused. Combined
coverage is614 registered tests: Windows602 PASS/12 platform SKIP and Linux613
PASS/1 platform SKIP. This is not a fresh core run. All36 quoted-count checks pass
against the existing Windows legacy executable and current XML; no engine was built.

Separate compiled mutations retain their changed source, logs and fresh XML:

- Control/restored each pass all5 new tests.
- Removing witness verification fails both the chunk-capability preservation and
  omitted/changed/duplicate/foreign-entity tests (2 selected tests,2 failures).
- Removing the hook guard fails the hook rewrite/removal test (1 selected failure).

The original source is restored. These are executed assertion failures after successful
compilation, not compile errors or searches for source text.

## Ordinary installed runtime

A fresh isolated Linux Forge1.20.1-47.4.13 server ran the exact Windows-qualified
ordinary jar. A separate13,067-byte harness contains only testcaptureprobe classes,
with no production classes and no access transformer. Both invoked production class
URLs point into mods/blockreality-0.4.0-dev.jar. The44 checks, including ownership and
origin prerequisites, pass:

- A real chest retains7 iron ingots and its exact Chinese custom item data. Real
  attached serializable chunk and block-entity capabilities preserve their compound
  fields and long-array bits. A custom save-event field survives capture/readback.
- The normal save event is posted exactly once; subsequent mutation of the hook's
  retained tag cannot change the returned private image. Full live checkpoint and
  durable readback agree; capture/checkpoint leaves unsaved ownership with vanilla.
- Each capability independently throws on calls1,2,3 and4. All8 cases refuse and
  preserve the previously durable full baseline. Direct vanilla controls demonstrate
  that its serializer actually swallows these chunk/entity exceptions and omits data.
- Hook deletion, nested capability rewrite, hook exception and live capability change
  refuse. A later pending-entity chunk makes the batch refuse before storage; the
  earlier chunk's durable baseline remains unchanged.
- Pending raw NBT is unchanged at refusal, with no promotion. Unloaded, duplicate,
  foreign-dimension and off-thread captures refuse; unloaded-key validation precedes
  hooks and does not generate a chunk. Live inventory remains unchanged throughout.

The server exits normally with code0; loopback25599 is released. The first runtime
attempt passes. Logged exception stacks are the deliberately injected provider/hook
faults. Vanilla's later shutdown autosave does attempt to promote the deliberately
unknown pending entity and logs its rejection; preservation is asserted at the module
refusal, not across that subsequent unrelated vanilla save. The exact refused pending
NBT and the complete checkpoint NBT are retained in installed-runtime.zip.

Two warmups followed by10 captured timing samples are recorded: median4.084ms,
maximum31.375ms for this single chest/chunk on this host. The tail is retained. This
small series has no performance threshold and does not qualify transaction cost,
large worlds, FPS or v1 high performance. Cooperative provider/hook code can allocate
or cause external side effects internally; this capture check cannot bound or undo
arbitrary foreign code. Concurrent light changes may conservatively refuse capture.

## Artifact and limits

Jar15,332,097 bytes, SHA-256
`06e6355bc11df1442194d88babd42b62bac75d3ca31c29b27f245cfb34851c91`.
Adds LiveChunkCapture.class and changes ChunkFileParticipant.class. Two companion
classes change debug line metadata but have identical javap -p -c -s output. All404
other old entries/252 classes are byte-identical, including both v1.5 libraries,
assets, notices, contract, provenance and the existing exact4-directive AT.
Harness SHA-256`12fcff663f03beb6bb174a43611c63a35df1b3684ff3a656745a8a318e1f0316`;
it is not inside the ordinary jar.

The first artifact-inspection assertion expected only one changed old class and
failed on the two debug companions. Its original script and identity are retained;
the expanded inspection proves their executable instructions/descriptors identical.
The first documentation command omitted the executable argument, selecting the
Linux binary on Windows, and failed in child-output decoding. The corrected command
uses the existing Windows executable and a UTF-8 child environment and passes. These
two initial tool-output failures are transcribed in initial-verification-failures.json;
they are not misrepresented as raw subprocess log files. Product sources/artifact
were unchanged by those verification-driver corrections.

Raw test/runtime/mutation logs, XML, harness sources, helper commands and identities
are archived with reopened SHA-256 checks. Parent PR132 head02856fd CI34450475197
has4 successful jobs and15 skipped native build steps; its receipt is included.
This branch still requires its own exact-head CI after push.

This subunit completes live capture and its persistence binding. It has no formal
ordinary-placement caller yet. Transaction callback/publication suppression, player
inventory application, manufactured publication, bootstrap recovery, ordinary
placement/blueprint/undo, actual socket/UI and full CT-1..9 remain open. No Minecraft
crash/restart or v1 completion is claimed. No original Windows client/security modal,
CM server, engine sources, contract or physics behavior was changed.
