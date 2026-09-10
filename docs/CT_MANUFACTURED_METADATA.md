# Manufactured construction metadata — criteria before implementation

2026-09-10, base5af6f44 / PR129. Implements the committed-journal metadata authority
specified in CT_FORGE_PERSISTENCE. This is preparation for the shared ordinary
placement/blueprint/undo service; full CT-1..9 and live Forge wiring remain required.
Engine libraries/contract remain read-only; no mechanics or native event is added.

## Records and plans

An explicit piece plan contains one known material/section, a declared axis0..2
and1..4096 distinct valid world cells. Frame declarations require a contiguous
axial run. Monolith/panel declarations require face-connected cells; their axis is
still a declaration, not a derived panel normal. A whole proposal has at most256
pieces/4096 cells. Adjacent equal products stay separate; duplicate/overlapping
cells refuse. Observed ConstructionLedger groups never supply manufactured IDs.

Permanent immutable piece records carry server-generated UUID, birth transaction,
birth actor, dimension, declaration, birth metadata order/revision and current
cells/status. INTACT may become EDITED or RETIRED; edited pieces never become intact
again, and retired IDs never return. Edits only remove ownership of touched cells
or mark remaining pieces ineligible; they cannot silently add/extend/merge a piece.
Birth geometry is validated; an edited footprint may be disconnected. Empty records
are retired and retain birth identity without retaining cell arrays. Bounds remain
262144 lifetime IDs and131072 owned cells across the domain; reduced test quotas
cannot raise production caps. No eviction, partial admission or mutable views.

## Journal schema and reconstruction

Use versioned strict bounded binary metadata values inside the existing checksummed
transaction codec. Keys are `meta/order`, `revision/<SHA256(dimension)>`,
`transaction/<transaction UUID>` and `piece/<piece UUID>`. The transaction descriptor
binds BUILD/EDIT/UNDO, dimension, creative/no-consumption mode and optional original
build transaction. The outer request binds actor/session/domain/base revision.
Every batch carries global metadata order before/after (exactly+1), and dimension
revision before=request.baseRevision / after=base+1. Last committed dimension
revision must not exceed the next request's base; gaps are allowed for unrelated
world edits and do not authorize replaying them. Metadata order has no gaps among
COMMITTED metadata batches. A first dimension binding may begin above zero.

Strict decoders reject unknown versions/codes, invalid tokens/dimensions/UUID-key
bindings, excessive counts/lengths, duplicates/noncanonical ordering, truncation
and trailing bytes. Validate metadata-resource shape, created/retired receipt IDs,
birth binding, monotonic transitions, exact before-images, capacity and ownership
before mutating the registry. Nonmetadata world/player images remain opaque here;
the actual host must validate and persist them. Metadata alone never permits a
refund, world edit, native result or game revision publication.

Bootstrap inventories journal keys, reads validated records and orders committed
metadata by recorded global order. It retains only bounded ordering keys and typed
live/retired metadata, not all historical participant images. Re-read each selected
entry before applying. REJECTED/ABORTED/PREPARED never enter the committed registry;
prepared world/player rollback stays with the existing coordinator/Forge host.
Corrupt or inconsistent committed history refuses before returning a registry;
never replay old world/inventory images into participants. No second SavedData
copy becomes piece authority.

Build preparation returns private metadata changes/new IDs without publishing them.
Build cells must be unowned. Edit preparation marks touched pieces permanently
ineligible and releases changed cells. Undo metadata is eligible only for the whole
original BUILD transaction while every born piece remains INTACT, in the same
dimension, and is retired together; original creative mode stays bound so it cannot
be turned into a survival refund. Original before-world/item images and current
permissions/dependencies must still be checked by the later full service. No
public API claiming metadata-only undo is sufficient for gameplay.

## Evidence

Actual journal tests must demonstrate adjacent distinct pieces, multi-dimension
ownership, explicit declaration validity, no state changes during planning/refusal,
build/edit/retirement/whole-build undo, permanent IDs, creative binding, exact
before-images, order/revision gaps and rejection, capacity refusal, immutable
records, bootstrap with mixed phases, corrupt replay refusal and no invocation of
world/player callbacks. Codec cases cover valid round-trip and malformed inputs.
Compiled negatives independently drop ownership conflict and edit/undo eligibility
checks; they must fail behavioral assertions after compiling, then restore/pass.

Full core/Forge suites with exact delivered v1.5 libraries on both platforms,
existing process recovery gates, packet/source/bytecode/jar guards and exact-head CI
remain required. Preserve first failures and raw results; update quoted counts only
after measurement. Record cold bootstrap/live metadata costs without FPS claims.
This does not qualify real Forge atomicity, player/chunk persistence, protection,
network ordering, client confirmation or full v1. No old gate is replaced.

Pre-qualification review extension after the first14 file/codec tests pass: a live
registry that observes an inconsistent COMMITTED record or a journal verification
failure must latch unavailable before any further proposal/public readout. Last
typed data stays private, not advertised as current over a corrupt history. An
ordinary attempted publication of PREPARED/missing/foreign records refuses without
poisoning an otherwise valid instance. Exercise forged whole-undo subsets/creative
changes, retired-ID resurrection, and journal UUID order differing from commit
order. Preserve the first14 source/XML as the pre-extension control. No acceptance
or performance threshold is relaxed by these additional failure checks.
