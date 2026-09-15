# CT Forge integration — implementation decisions before code

2026-09-10; continues the full CT-1..9 requirements, after the verified core journal
source3413590 / PR125 headce29eeed. These decisions do not replace the full gates.

## Metadata authority and bounds

Use the committed construction journal as the durable source for manufactured-piece
metadata. Each intent carries per-piece before/after records, a server-global monotonic
metadata order, dimension binding and its dimension's revision. Reconstruct metadata
only from validated COMMITTED entries in that order; never replay their old world or
inventory images over later gameplay. PREPARED metadata is private working state and
rolls back. A separate SavedData copy must not become a second piece authority.

The existing observational ConstructionLedger remains a grouping/history service. It
cannot manufacture transaction-born IDs, authorize refunds or become an undo source.
New pieces use server UUIDs and explicit same-product boundaries. A frame piece must
be a declared, contiguous axial run; monolith/panel footprints must be explicitly
connected declared cells. A blueprint can contain multiple adjacent identical pieces.
Edits mark the affected original piece irreversibly ineligible for simple undo; emptied
or undone pieces retain permanent retirement records. No native damage event is invented.

Initial manufactured registry bounds:262144 lifetime piece IDs and131072 simultaneously
owned cells across its server construction domain, with4096 cells per proposed plan,
256 pieces per plan, and the already frozen journal/record/value limits. Capacity refuses
explicitly; no identity eviction or silent partial admission. Retired records need not
retain cell arrays because their original birth images remain in the journal. Keep live
typed metadata and bounded indices, not all world/inventory before/after images in heap.

## Real participants

Minecraft can have newer in-memory state than its last autosave. Before PREPARED, ensure
each affected participant's baseline is actually durable and equals the captured before
image. This checkpoint does not alter visible world, inventory or piece state. A failure
must refuse the new transaction. Do not misclassify ordinary unsaved data as corruption
after a later crash. Record the checkpoint and commit costs separately in game tests.

World/player writes must expose completion/failure. Await the pinned Forge IOWorker's
store and synchronize futures without pumping main-thread tasks, and verify relevant
stored block states. Preserve full player data around exact inventory-slot changes;
write atomically, force, reload and compare canonical item data. Normal void save methods
that swallow errors are not acknowledgments. Canonical NBT sorts compound keys, preserves
tag types/list ordering and rejects unknown/truncated/oversized/deep inputs.

Suppress internal observation, native dispatch and client publication while validating
provisional protected placements and applying/rolling back a transaction. The transaction
publishes all changes and advances the affected revision once after COMMITTED. Restore
before images on pre-commit failure; retain the intent and refuse further operations if
verification or rollback fails. Startup recovery happens before players and analysis gain
access; it includes affected offline player files and loaded world participants.

A server-global construction owner serializes dimensions that share player inventories.
Dimension/native availability bookkeeping must not expose a pending transaction during
bootstrap. Existing known-cell coverage remains conservative, augmented from committed
manufactured ownership so a crash before observational publication cannot omit new cells.

## Ordinary placement and client boundaries

The normal structural BlockItem path must use the transaction service. Do not commit
inside an outer Forge captured placement before its later cancellable protection event.
Retain vanilla interaction priority and server checks, and run explicit protected candidate
validation outside any nested placement capture. The new bounded C2S request has a stable
transaction UUID and server-issued session/domain context. Actor identity, inventory,
piece IDs and game mode are always server-authored. Refuse changed hand/target/session,
stale previews, malformed plans, unloaded targets, permissions/protection/world-border
violations and excessive request rates before creating permanent pieces or consuming items.

Blueprint confirmation and inverse undo must use the same production service. Undo is
a new transaction with exact originally consumed item data, permanent retirement and
eligibility checks against all later relevant edits/dependencies. Creative construction
has no consumption and cannot create a later survival refund. Networking/client UI,
actual socket/restart tests and all full CT gates remain required even if intermediate
metadata/persistence helpers pass their own tests.
