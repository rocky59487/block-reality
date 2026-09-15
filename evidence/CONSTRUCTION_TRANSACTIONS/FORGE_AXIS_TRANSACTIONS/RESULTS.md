# CT_FORGE_AXIS_TRANSACTIONS — first gameplay transaction owner

Criteria f5216a5 precede implementation0ed2da2. Clarifications ad8cc86 and18b5dbb
precede their respective fixes d720995 and338c490. The ordinary jar is built from
338c490. This unit routes existing empty-hand sneaking axis interaction through
the server-owned coordinator. It does not implement ordinary placement, inventory
consumption, manufactured BUILD, blueprint, undo or client confirmation/replay.

The service opens its journal under Minecraft's world owner, restores pending
transactions before server-started/player access, and publishes a recovered baseline.
Committed records rebuild manufactured metadata and revision floors without replaying
old world images. Verified ABORTED intents retain their before-revision floor across
repeated startups; REJECTED requests cannot raise that floor.

Before PREPARED, the host checkpoints both the complete live chunk and conservative
known-cell coverage of the legacy structural target. The coverage remains observational
data, not manufactured authority. World writes use no neighbor/client publication;
observation, dispatch, apply and snapshots remain paused. Reentrant module edits
refuse. COMMITTED precedes final observation, exactly one revision advance and one
result envelope. No item or player file is written by this axis operation.

## Complete suites and first failures

Final counts are622 registered:477 core and145 Forge-side. Windows610 PASS/12
platform SKIP; Linux621 PASS/1 platform SKIP. Both full core runs use d720995;
the136 core source/test/build files are unchanged at338c490, whose full Forge
builds both pass. Native cases run with the delivered v1.5 libraries. The existing
packet goldens and261-class ordinary jar guard, including its3 compiled forbidden
content arms, pass. Platform skips are named in dual-platform-tests.json.
All36 documentation count checks pass against the current XML and the existing
Windows legacy executable; no engine was built for that documentation check.

Both platforms also pass37 interrupted core fixture JVMs with37 fresh recoveries
and an owner-lock process. These file-host runs supplement, and are separate from,
the real Minecraft interruptions below. A redundant Windows third core task was
UP-TO-DATE; it is not claimed as another fresh core run.

The first Windows core run had476 registered tests with one failure: a new pending
metadata test compared insertion order against the Intent's canonical resource order.
The assertion now compares canonical order; the original source, XML and log remain.
The second Forge run on each platform failed a plain-JUnit registry bootstrap:
Forge's event transformer is absent from that JVM. The two real-registry codec tests
were moved into the installed Forge harness; they execute there, with no SKIP.
The independent coordinate-key JUnit test remains. These fixture failures were not
converted into product passes or hidden by reducing the intended assertions.

## Ordinary installed gameplay

The Windows-qualified ordinary jar runs in fresh isolated Linux Forge1.20.1-47.4.13
servers. Real class URLs point into mods/blockreality-0.4.0-dev.jar. Separate harness
jars contain only testaxisprobe classes, no production classes or access transformer.
The synthetic FakePlayer invokes the actual game-mode useItemOn path.

The initial53-check control passes. Its inventory assertion covered the tagged
material stack but was insufficient to claim whole-inventory equivalence. The
strengthened control and restored control each pass60 checks. All7 interactions
retain identical canonical bytes for the full saved inventory, selected slot and
menu-carried stack; the14 before/after NBT documents per run are archived.

- Real air/stone/log states round-trip exactly. Nine missing/empty/unknown/mistyped/
  extra-property schema cases refuse instead of silently becoming air/default state.
- Forge RightClickBlock cancellation, adventure permission, server reach, non-sneaking
  and a nonempty hand each leave world, metadata and public revision unchanged.
- Survival and creative each rotate the real registered structural block exactly once,
  advance public revision and committed metadata order once, and publish one envelope.
  Legacy axis edits fabricate no manufactured birth or ownership.
- At durable PREPARED and COMMITTED temporary-force barriers, the harness invokes
  actual structural observation, the level tick, a snapshot request and a reentrant
  resolve. Dispatch remains paused and public metadata, declaration, revision and
  envelope sequence remain at the before-state, even after the private cell changed.

The final controls use harness16825B/SHA2aafcb2e8d7b9242fd8afc27251df40c861bec3b512fb391c20642d2ee76b82b.
The crash matrix uses the earlier16251B/SHA88997cc6a005533752e058bf2b0a6b1fb2941ddaf9f9ab5f03a8f8d2edf79f44
harness, whose gameplay/recovery assertions are unchanged by the inventory audit.
The first pre-runtime harness build used a nonexistent declaration accessor; it was
corrected to the actual API before any runtime launch. Both sources/jars are retained.

## Real Minecraft process interruption

Each row starts a fresh owned world, executes the same ordinary jar and calls
Runtime.halt(73) at the named journal stage. Each interrupted world then starts two
fresh Minecraft JVMs. The harness reflects the journal's internal fault observer;
there is no production configuration or shipping harness switch.

| Interruption | Pre-interruption checks | First restart | Second restart | Durable result |
|---|---:|---:|---:|---|
| PREPARED / DIRECTORY_FORCED |23|13 PASS|14 PASS|ABORTED, exact before-axis|
| COMMITTED / TEMP_FORCED, after full world flush |27|13 PASS|14 PASS|PREPARED remains authoritative; ABORTED, exact before-axis|
| COMMITTED / DIRECTORY_FORCED, before publication |27|13 PASS|14 PASS|COMMITTED, exact after-axis|

All restarts preserve known legacy coverage, restore at least the correct revision
floor before target loading, use a new result-source UUID, and reconstruct metadata
order0 or1 as appropriate. The second restart leaves the terminal transaction file
byte-identical. A higher public revision from subsequent legitimate chunk observation
is allowed; recovery cannot lower it. Raw journal, region/index NBT files, hashes,
class origins, process owners and complete logs are archived after each stopped JVM.
These are process-crash tests, not hardware power-loss certification.

## Compiled behavioral counterexamples

- Control/restored each pass27 selected core tests. Removing ABORTED revision-floor
  retention compiles and fails exactly the repeated-reopen/rejected-floor assertion.
- Removing only the observedStructure transaction suppression compiles. The installed
  negative run reaches the COMMITTED temporary-force phase and fails the actual
  no-provisional-observation/revision/metadata/envelope assertion. The normal jar
  subsequently passes the same60-check installed control in a fresh world.

The first negative-artifact inspection refused before gameplay because the Linux
snapshot and Windows control differed only in line endings in five text entries. Comparing
the two Linux builds proves only StructureManager.class changed. The final negative
artifact copies that compiled class into the exact Windows control, preserving every
other entry byte-for-byte. It is explicitly a test mutant, not the ordinary candidate.
Original compiled jar6097f49b… and isolated mutant8688c8a8… are retained locally;
their hashes, changed source, compilation and actual failure logs are archived.
A dependent launch was initially called before isolation finished and refused on a
missing identity receipt; after the same build session reached exit0, it was retried.
No server had launched on either setup failure. These two driver failures are clearly
identified as tool-output transcriptions, not fabricated raw subprocess logs.
An initial finalization assertion also assumed the wrong direction of newline conversion.
Normalizing both sides proves exact equality; platform-resource-line-endings.json records
the actual byte/CRLF counts and corrects the earlier narration. The initial assertion
and its source are retained, and no ordinary artifact was changed by this audit fix.

## Artifact, cost and remaining scope

Ordinary jar15355886B, SHA-256
`828d24b47b237c2ded256633a10c3e0d42795eeaa0c51c88514eb7773889fa32`.
Adds5 classes;246 old classes/396 old entries are unchanged. Four additional old
companion classes differ in debug metadata but have identical javap -p -c -s output.
Both v1.5 libraries, contract, licenses/provenance and the exact4-directive AT remain
byte-identical to PR133. Only the two refusal language resources change among old
assets. No engine source/build/release or Java physics changes occur.

Correctness-run action timings are retained, including481.48ms and229.86ms in the
strengthened inventory control. They include cold synchronous persistence and harness
callbacks/NBT receipts. The original control's last action was66.72ms. These sparse
samples are Recorded only; there is no relative performance, FPS, large-world or v1
high-performance pass. Persistent transaction latency still requires a frozen profile.

All owned test servers stop or deliberately halt; loopback25600 is released. Original
Windows client/security dialog and CM server are untouched. At evidence archival,
branch CI had not yet run; the exact-head result is to be attached to the PR after
push. Parent PR133/ecc8525's4 successful jobs/15 native step SKIPs are included as
parent evidence only. CI skips never substitute for the local native runs.

This completes the axis-only service subunit. Full CT-1..9, ordinary placement and
inventory consumption/refunds, manufactured BUILD, blueprint/undo, stable C2S context,
real sockets/client UI, performance/soak and v1 release remain open. Arbitrary foreign
hooks may cause their own external side effects; the service refuses unknown changes
instead of inventing additional participants or repairing them by guesswork.
