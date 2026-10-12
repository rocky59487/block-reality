# Finite durable store and identity mapping (v2 W2)

This unit implements the W2 stage of v2 integration (`docs/design/V2_FORGE_INTEGRATION.md`):
the persistent source of truth for finite worlds and the durable identity mapping that later
stages build on. Java owns persistence, transactions and wire discipline only — every
mechanics formula, material history and verdict remains the native engine's sole authority.

## Scope delivered

**Durable identity records** (`com.blockreality.core.finite`):
`FiniteDomainId` (journal domain + Minecraft dimension + BSI domain UUID + artifact
namespace), `FiniteBasis` (world revision + history sequence + source/vocab/owner hashes +
live-only `sessionGeneration`, never disk authority), `ArtifactBinding` (positive native
owner ids, `MANUFACTURED` piece UUID or `LEGACY_NO_REFUND` provenance key, immutable cells,
retire disposition), `FiniteDomain`, `FiniteRoot` (genesis/hash-chained root chain),
`FiniteSource` (complete canonical finite input: vocabulary request+reply, blocks, owners,
materials, shell laws/layers, cell loads, prescribed values, loads, 176-byte options,
support-material coverage), `FiniteCheckpointRef`, `FiniteResult`, and the four W3–W5 type
boundary stubs (`FiniteFracturePlan`, `RigidBodyRecord`, `RigidSceneRecord`,
`RigidStateBatch`).

**`FiniteCodec`**: the `BRV2OBJ1` object envelope (SHA-256-named, self-describing kind +
payload hash), `BRV2VAL1` journal values for domains/owners/roots, `BRV2SRC1` source
payloads, the `BRV2HEAD1` HEAD cache, and operation encoding/decoding with strict size,
version, hash, UUID and trailing-byte validation. Every malformed input fails closed.

**`FiniteStore`**: immutable `objects/<sha256>.blob` store with `staging/<request-uuid>/`
private staging → `force` → atomic move → readback verification. Admission bounds
(64 MiB object / 256 MiB staging / 1M objects), exclusive `.lock`, HEAD cache with the same
atomic discipline, retained crash staging evidence, idempotent republish, and a
package-private `FaultHook` seam used only by the process gate.

**`FiniteRegistry`**: the construction journal remains the single authority. Operations are
`finite/domain`, `finite/owners`, `finite/order`, `finite/tx/*` chains: create-domain,
bind (minted owner ids, adjacent pieces never merged), one-time legacy adoption, retire,
observation epoch, root commit with store-reference verification (genesis ⇒ `0/0`,
history-only ⇒ `historySequence+1` with identical source, new revision ⇒ `revision+1,
history 0` with a different source). Unknown `finite/*` keys, extra participants, foreign
sources and unverifiable store references all fail closed. `ManufacturedRegistry` folds
past `finite/*` entries untouched while keeping every strict malformed-history check.

**BSI finite wire** (`com.blockreality.core.bsi` + `FiniteSession`/`FiniteLane`):
`BsiCorot` encodes/decodes the `bsi.corot.*` wire (176-byte options record, ordered
materials/cellLoads/prescribed/loads/shell payload groups, checkpoint import/export with
budgets, island verdict decode over `corotIslands`+`corotText`).
`BsiVocabulary.decodeReply` binds engine replies to request id/revision/version and
`sameMapping` proves the durable mapping. `InProcessEngine` gained `finiteRequest`,
`adoptVocabulary`, `declareFiniteWorld` and `finiteWorldDeclared`. `FiniteSession` enforces
the lifecycle vocabulary → identical identified world → optional one-shot checkpoint
import (durable-basis match proves identical world bytes) → solve; any order violation,
foreign basis, mapping drift or engine refusal permanently disables the session.
`sessionGeneration` and `token` stay live-only.

## Tests (28 new, all PASS)

| Suite | Evidence |
|---|---|
| `FiniteCodecTest` | envelope/value/source/operation round-trips, HEAD cache, structural tamper, truncation, foreign-domain refusal |
| `FiniteSourceTest` | canonical ordering, support coverage, hash binding, malformed inputs, owner/support separation |
| `FiniteStoreTest` | real filesystem publish/readback, exclusive lock, corrupt/truncated objects, admission bound, staging evidence, HEAD cache, adjacent-open |
| `FiniteRegistryTest` | full lifecycle fold across reloads, one-time adoption, monotonic owner mint, retire, root chain rules, pending-entry validation, manufactured skip |
| `FiniteSessionTest` | scripted native-shaped lane: ordered lifecycle, drifted vocabulary, foreign checkpoint basis (never reaches the wire), engine errors, island verdict decode, identity mismatch disable |

## Process evidence (`:core:finiteRecoveryGate`)

`evidence/FINITE_STORE/process/` retains raw images from **2 crashed JVMs** (`forced` and
`moved` FaultHook stages), **2 fresh recovery JVMs**, and **1 concurrent-owner check** —
`{"status":"PASS","crashed_jvms":2,"fresh_recovery_jvms":2,"concurrent_owner_checks":1}`.
Crash-at-`forced` leaves the staged blob as bounded evidence with nothing published;
crash-at-`moved` leaves a fully durable object that reads back byte-identical; republish is
idempotent to the same content name in both outcomes. This is filesystem evidence, not a
Minecraft restart qualification.

## Suite totals

`:core:test` on this branch: **604 registered / 604 executed-or-skipped, 0 failures**
(82 SKIP = platform/native arms, unchanged discipline). The `finiteRecoveryGate` JavaExec
task is registered beside `constructionRecoveryGate`.

## Bugs found and fixed inside this unit

- `BsiCorot.options()` rejected negative gravity through an unsigned-finite check; signed
  vectors now use a finite-only check while tolerances/steps stay unsigned.
- `FiniteCodec.decodeHead` miscounted the canonical trailing newline.
- `FiniteStore.open` surfaced a same-JVM lock as `OverlappingFileLockException` instead of
  the documented `IOException`; codec validation failures at the store boundary now also
  surface as `IOException`, and admission runs before envelope materialization.
- `FiniteStore.publish` could not republish after a crash between move and readback on
  filesystems where atomic rename refuses an existing name; the content-addressed
  `FileAlreadyExistsException` path now verifies the existing object and discards the
  duplicate stage.
- `BsiCorot.islands` skipped 8 bytes short of each 96-byte record (7 u32 + 6 f64 = 76).
- `FiniteRegistry` legacy/piece identity re-binding across transactions was not checked
  against committed bindings; it is now refused (one-time adoption, no re-mint).

## Explicitly not in scope

W3 finite analysis semantics, W4 destruction transactions, W5 rigid-body scene mechanics,
W6 restart loop, Forge runtime wiring and N25 acceptance. The stub records establish type
boundaries only; nothing here computes mechanics in Java.
