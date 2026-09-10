# CT Forge service and axis edits — frozen before implementation

2026-09-10; baseecc8525 / PR133. The published v1.5 engine and contract remain
unchanged. This implements the first real gameplay caller of the construction
coordinator, rather than another disconnected persistence adapter. Full CT-1..9
and ordinary placement/blueprint/inventory/undo/socket/UI remain required.

## Production entry and authority

Route existing empty-hand sneaking structural-axis interaction through one
server-owned construction service. Preserve vanilla RightClickBlock protection
priority and additionally check server actor, level, reach, mayBuild/mayInteract,
loaded target, world border, current state and empty hand before editing. Server
authors the request ID/session/domain/plan hash; this vanilla action has no client
idempotency-key protocol and does not claim CT-7 confirmation/replay completion.
No item is consumed or refunded, in either survival or creative. Success text and
block update follow the durable committed decision; refusal/rollback cannot display
the success text. Provide en/zh refusal text without raw exceptions in the UI.

The same persistent global journal is the manufactured-metadata authority. An axis
edit uses prepareEdit to release changed ownership and irreversibly invalidate the
affected original piece's simple undo eligibility. Observational grouping does not
authorize the edit or create a manufactured birth. Do not fabricate native damage.

## Service lifecycle and visibility

Open the service under Minecraft's world lock, before player/analysis access. Load
committed metadata, recover any PREPARED entry with the real chunk participant, then
publish the recovered baseline. COMMITTED entries reconstruct metadata/revision
floors and coverage only; never replay old cell images over later legitimate edits.
Close the journal owner on server stop. A malformed/foreign/unverifiable entry or
failed restore keeps construction and analysis unavailable with originals retained.

Serialize the service across dimensions sharing inventories. During validation,
checkpoint, apply, rollback and publication, suppress StructureManager's structural,
placement, break, neighbor, explosion and chunk observation paths. Pause gathers,
native dispatch/apply and client snapshots. Reject reentrant module edits/commands
before mutation. A structural callback outside the owned cell set is an exclusion
violation, not permission to absorb an unrelated edit into the transaction.

Keep the public revision/observational registry at the before-state during private
work. The host has private metadata/revision participant images. After COMMITTED,
verify final cells, apply committed metadata, reconcile affected observations and
advance the public revision exactly once. Send block/client state only afterwards.
Replay never republishes or bumps revision. Successful rollback restores exact cells
and durable complete chunks before releasing the pause and exposes no success.
An uncertain/failed rollback keeps analysis closed. Preserve an explicit failure
state; do not silently reopen merely because a scope or Java stack frame ended.

Revision startup restoration is monotonic: restore at least the committed journal
floor, never lower an already higher revision or accept a result from the former
revision. Coverage from committed owned cells augments the conservative known-cell
index without forcing their chunks to load.

## Participant schema and durability

Cell resources have bounded canonical coordinates and exact vanilla block-state NBT.
Decode with the real registry, then require an exact canonical round trip; unknown
blocks/properties cannot silently become air/default states. The initial caller edits
only the AXIS property of an existing StructuralBlock, and performs no Java physics.
Keep cell/schema validation separate so ordinary placement/undo can use the same
host later with their additional validation and participants.

Checkpoint all complete live chunk before-images through the existing worker before
PREPARED. Apply cell changes without client/neighbor publication, verify exact cells,
capture complete chunk after-images and await store/force/readback. Rollback uses
the identical path. Await futures without pumping main-thread tasks. The journal's
COMMITTED decision is the metadata durability boundary; a second SavedData is not
an alternative authority. No player file write is needed when no inventory value
changes. This does not claim inventory-transaction durability.

## Evidence required

1. Behavioral tests for strict cell round trips/schema/bounds, monotonic revision
   restoration and validated pending metadata context. Add compiled mutations for
   actual preservation/visibility assertions; compilation errors are not counterexamples.
2. Real installed Forge47.4.13 using the ordinary jar and a separate harness without
   production classes. Exercise the vanilla game-mode interaction path in survival
   and creative, protection/permission/reach refusal, exact unchanged inventory,
   world/chunk/metadata consistency and one revision per successful multi-callback
   action. Observe actual pause behavior and absence of provisional publication.
3. Inject real process interruption with PREPARED durable, after world flush before
   COMMITTED, and after COMMITTED. Restart Minecraft with the same owned world;
   validate rollback/retention, terminal journal phase, exact cell data and revision
   floor. Keep first failures, raw logs, journal/NBT bytes and artifact identities.
   Synthetic players are identified as such; actual sockets/client gates stay open.
4. Full core/Forge suites on Windows/Linux with delivered native cases exercised,
   prior packet/class/jar guards, same-artifact installed tests, exact-head CI and
   licensing/provenance preservation. Record correctness-run timing separately;
   no FPS, large-world or complete v1 performance claim from these tests.

The owner excludes normal Minecraft task/save publication while it holds the server
thread. Arbitrary foreign hooks can still cause their own external side effects;
unknown mutations refuse rather than being repaired by guessed extra participant
images. Ordinary placement/blueprint/undo must join this service with full stable
C2S/S2C context and inventory semantics before the overall CT unit is complete.
