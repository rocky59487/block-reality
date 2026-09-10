# CT ordinary placement — frozen before implementation

2026-09-10; base b9048e5 / PR134. This is the next production caller of the same
server-owned construction service, not a separate simulation or persistence authority.
Only the mod changes. The delivered v1.5 engine, contract and physical interpretation
remain unchanged. Full CT-1..9, blueprint multi-piece editing and inverse undo retain
their existing acceptance criteria and remain required for v1.

## Real gameplay entry

Structural products keep their existing item/block registry names and vanilla block
interaction priority. Their item use opens a temporary placement preview on the client;
the server's ordinary ItemStack/ForgeHooks item call performs no placement, item change
or success publication. A separate bounded construction channel sends preview and
confirmation requests. Do not commit inside ForgeHooks.onPlaceItemIntoWorld: its later
cancellable placement event and stack restoration must not reverse a committed decision.
Preserve protocol11 analysis packet bytes and all existing direction/size guards.

The initial ordinary placement plan is one declared product cell. Each confirmation
creates a new independent manufactured piece; it cannot silently merge/extend an old
piece. The same host must support further explicit multi-piece plans later. This initial
caller does not close the multi-cell blueprint or undo gates by testing one cell.

Server preview validation is read-only: authenticated sender/current dimension, actor
alive, permission/game mode, reach, world border/build height, loaded clicked/target
chunks, current held product, placement context, replacement/survival/collision rules,
enabled features and the exact before-state/item image. No chunk generation, permanent
ID, inventory write or durable transaction on preview/cancel. Preview offers are private,
bounded and expire; at most one per player,64 total and4MiB total captured item data,
200 server ticks lifetime. A new offer replaces that player's old offer. Disconnect,
respawn or dimension travel invalidates it. Refuse admission rather than evict another
player's live offer to conceal capacity pressure.

The server authors the offer token, domain/session/base revision, exact plan hash,
before-images and material debit. The client supplies an explicit axis, intended held
product and hit/context, never authoritative inventory images or artifact IDs. A
confirmation supplies its unique transaction UUID and the exact offered binding.
Before first execution, verify the live offer plus current world/items/revision again.
Existing terminal requests must compare their complete stored binding and authenticated
actor; exact retry returns the original durable receipt without a new ID, debit, world
write, revision or publication. This remains true after reconnect/restart: an old
session can query its exact terminal decision but cannot authorize a new execution.

Both C2S and S2C codecs use explicit direction pins, bounded strings/counts/frames and
closed enums before allocation. Bound per-actor admission (including invalid/replayed
requests), distinguish busy/expired/stale/permission/conflict/capacity/recovery states,
and do not expose raw exceptions in UI. Invalid remote data cannot allocate a journal
key or force-load chunks before admission. Do not treat a missing/expired offer as
permission to rebuild a different plan under the same confirmation UUID.

## One world/material/identity transaction

Survival consumes exactly one held product, preserving all other item NBT/capabilities
and every other inventory slot. Creative has a verified explicit no-debit plan. Plan
schema binds the target, before/after block, declaration, actor, exact held slot/item,
mode and intended debit; store the bounded schema in the journal so startup validates
BUILD independently of transient preview memory. Continue accepting the existing
axis EDIT schema and its historical journals without changing their interpretation.

Reuse PlayerFileParticipant and PlayerInventoryImage for exact full player-file
checkpoint/apply/rollback. Add a live-player binding with strict item round-trip and
full saved-inventory preservation; malformed/unknown item or capability data refuses.
Checkpoint complete current player data before PREPARED even for a first unsaved
player; an unreadable existing file cannot be treated as absent. Preserve unrelated
player document fields, item tag types, empty slots and capability data. No guesses,
partial object deserialization fallback or refund reconstruction from item names.

The owner remains on the server thread without pumping normal game/save work. It
checkpoints the complete chunk and required coverage, then writes PREPARED before
applying world/inventory/metadata participant images. Run the cancellable Forge
RightClickBlock and EntityPlace protection paths at the proper validation stage;
EntityPlace sees the private candidate and before-inventory, without module/native/
client publication. A cancellation must restore and durably flush exact before-images.
Other structural callbacks outside the owned participant set keep the service closed.
Do not bypass the existing protection guards globally to allow the owner's validator.

Await full chunk and player-file barriers before COMMITTED. Then publish manufactured
identity, final observations, one revision, block/inventory synchronization and outcome.
Sound/stats/placement criteria must not run on preview, refusal, rollback or replay.
Publication failure cannot undo COMMITTED. Failed/uncertain rollback keeps the intent
and blocks further mutation/analysis. Startup validates and restores affected offline
player and world participants before login; committed history never overwrites later
legitimate world/player edits. Scope arbitrary foreign-hook side effects honestly.

## Interaction brief

The player is standing in a Minecraft build and needs to see what one action will
place and consume. Keep the world visible, use its existing pixel font and native
focusable buttons, and make the declared product/direction the focal point. The tone
is a precise construction instrument, with no artificial animation on repeated use.

Domain: material stock, declared section, local axis, target cell, placement boundary,
construction receipt. Color world: steel blue-grey, concrete grey, timber brown,
brick red, drawing cyan and caution amber. Use the existing HUD's dark steel backplate,
white/detail text and cyan focus; reserve amber/red for actual state/refusal.
Signature: the world-space ghost uses the same declared product bounds and axis as
the material model, paired with an exact one-item debit and target coordinates.
Avoid a fullscreen generic settings form, unexplained colored success, or a ghost
whose geometry differs from the placed product. Use a compact side panel, explicit
text states and the existing product geometry instead.

Component checkpoint: native Minecraft font; primary product/quantity then axis and
position, with status/controls below. Four-pixel spacing rhythm, compact padding and
one backplate depth strategy consistent with HudPanel. Use native Button keyboard,
focus, hover and disabled behavior. Confirmation is disabled while context is pending
or invalid; Escape/cancel makes no server construction. A sent request retains its
UUID for explicit retry after a lost response; it cannot turn a retry into another
placement. Changing dimension/connection cannot leave an active foreign-world ghost.
Small GUI sizes and en/zh wrapping must remain usable. Render and inspect actual
Minecraft screenshots before qualifying this UI; code or a browser mockup is insufficient.

## Evidence required

1. Behavioral plan/cache/admission/codec tests with malformed, foreign, stale, expired,
   replay and capacity cases. Exact packet round trips and prior analysis goldens.
   Compiled mutants must fail actual debit/preservation/replay/protection assertions.
2. Ordinary jar in installed Forge: real item/game-mode entry opens no server-side
   placement by itself; production confirmation commits survival and creative through
   the same service. Observe exact world/player/identity/revision at private and public
   barriers; denied placement, slot/state conflicts and reentrant callbacks refuse.
3. At least100 identical admitted confirmations before and after restart produce one
   outcome; changed payload/actor/domain refuses. Two competing actors cannot create
   mixed pieces or duplicate material. Identify synthetic players and fixtures honestly.
4. Real Minecraft process interruption across PREPARED, world/player apply/flush,
   durable decision and publication. Restart validates exact offline inventory, chunk,
   metadata and clocks, including first unsaved player and repeated recovery. Corrupt
   or foreign data refuses with originals retained. Core file-host tests are supplemental.
5. Actual client/socket preview, axis, confirm, cancel, pending/retry/refusal and material
   update; ordinary vanilla container interaction still works. Check small/large GUI
   scales and en/zh, preserve raw screenshots/logs and first failures. Original Windows
   CM/security modal is untouched; owned Linux client evidence is explicitly separate.
6. Full native-enabled core/Forge suites on Windows/Linux, ordinary jar/class/licensing
   checks and exact-head CI. Profile correctness-run costs as Recorded; no FPS or v1
   performance claim without the existing frozen large-world/client performance gates.

The full goal stays active until all v1 requirements are verified. Completing this
caller does not redefine blueprint, undo, engine dynamics, high performance or release
around the simpler single-cell case.

Implementation clarification before channel/UI qualification: ordinary world placement
requires the player's inventory menu with an empty cursor and refuses block-entity
replacement targets. These participants are not represented by this initial one-cell
caller. The network handoff permits at most4 queued C2S jobs per connection/128 total
and16 queued construction S2C jobs per connection/64 total, before main-thread enqueue.
Overflow drops bounded work; explicit retry and the server admission status handle it.
Persist the exact client confirmation before send, keyed by server/world and actor,
with at most16 pending destinations and4096 bytes each. No silent eviction, overwrite
of a different pending request, corrupt-file fallback or automatic new UUID on retry.
Durable terminal outcomes clear only their own exact pending record. Transient failures
retain it. Screens must state when a sent request remains unresolved after closing.
When the server explicitly reports EXPIRED after checking durable history, the player
may choose a separately labelled new preview. Remove only that exact expired outbox
record; require a fresh preview and another confirmation before a new construction.
Client timeout/elapsed time never authorizes this release. An old session in the same
journal domain with no stored decision is expired, while a different domain conflicts.
