# CONSTRUCTION_TRANSACTIONS (CT) — frozen before implementation

2026-09-10. Base material-geometry #123 /29c8d37. Scope: module-owned construction,
inventory, persistent piece identity, revision, networking and UI. The delivered42e10f5
libraries and BSI contract remain read-only. #12 is the construction acceptance basis;
#17's engine joint/release/lifecycle contract is still a separate dependency.

## Accepted implementation direction

A construction preview is temporary. Confirmation supplies a unique client transaction
UUID, actor/session/domain binding, baseWorldRevision and the bounded explicit plan.
The server validates permissions, reach, loaded chunks, world border, target states,
inventory and piece declarations before accepting it. The client never sends authoritative
inventory images, artifact IDs, result flags or native physical fields.

Each newly confirmed piece obtains a new server UUID. The plan explicitly separates
pieces at a bend, joint, material/section change or manufactured boundary. New placement
does not silently extend or merge an old piece. Observed same-material fusion/grouping
can still describe an aggregate; it is not authority to consume/refund material or undo
a manufactured piece. Existing observed IDs/history remain readable with that scope.
Authoritative piece records must distinguish transaction birth from old observation.

Materials are discrete inventory items matching the declared products, not Java-derived
mass or stiffness. Survival consumes exact counts; creative has an explicit no-consumption
receipt. Undo is a new inverse transaction, restoring the affected world state and returning
the permitted items only when the original pieces are unmodified and independent. It retires
their IDs permanently. Edited, cut, damaged, moved, joined or dependent pieces refuse simple
undo. None of those engine events is fabricated when the native contract cannot deliver it.

## Atomicity and recovery

The server serializes construction commits. It captures before/after images for affected
world cells, inventory slots, authoritative piece metadata and worldRevision. An immutable
PREPARED intent is durably written before any participant changes. Applying and flushing
all participants happens before a durable COMMITTED decision. Only then are world updates,
native analysis scheduling and the success receipt published. A commit advances revision
exactly once; repeated callbacks cannot expose intermediate registry/analysis states.

Any failure before a durable commit restores and flushes every before-image before recording
an abort. If rollback or decision verification fails, keep the intent and refuse further
construction/analysis until recovery succeeds; never pretend rollback succeeded. A failure
after durable commit cannot undo history: retry returns the original committed receipt.
Recovery precedes access to the affected participants. PREPARED rolls back, COMMITTED is a
durable terminal decision. Recovery checks that actual values are known before/after images;
unrelated external changes/corruption cannot be overwritten as a guessed repair.

Minecraft world/player files and module state are separate participants. A SavedData write
alone is not evidence that inventory or chunks are durable. Forge integration must demonstrate
their flush and restart behavior. Cancellable protection hooks see provisional placement for
validation, not committed artifacts; rejection rolls back without client/native publication.
The atomic visibility claim applies to published world/registry/inventory state, not to an
external validator deliberately inspecting the candidate during validation.

Storage is bounded and checksummed, with atomic phase replacement and an exclusive owner.
No idempotency-key eviction or reused artifact IDs. Capacity exhaustion refuses new work
explicitly. Exact payload/actor/domain binding survives restart. A reused key with different
content cannot replace the original outcome. Strict stale base revisions may require a new
preview; unrelated transactions accepted in server order each get one new revision.

## Full unit gates (none may be replaced by core-only tests)

| Gate | Required evidence |
|---|---|
| CT-1 | Preview/cancel/validation failure creates no permanent piece ID, inventory delta or world edit. Explicit multi-piece blueprint boundaries survive adjacent equal products and reload. Legacy observed grouping is not used as transaction authority. |
| CT-2 | Successful survival/creative commits expose exact items, all cells, authoritative pieces/ownership and one revision together. Real Forge callback suppression and native dispatch see only the committed state. Multiple nonconflicting accepted transactions remain ordered. |
| CT-3 | Inject faults at prepare, each participant write/flush, durable decision and publication. Failures before commit restore all participants; committed decisions survive lost acknowledgments. Failed rollback retains recoverable intent and blocks further mutation. Compilation failures do not count as behavioral negative arms. |
| CT-4 |100 identical confirmations and100 inverse replays yield one original outcome each, before and after restart. Different payload/actor/domain using the same key refuses. Two players competing for the same target produce no mixed artifacts or duplicated materials. |
| CT-5 | Actual file/process interruption and Minecraft server restart recover prepared transactions and retain committed ones. Player inventory, chunk data, module registry and revision are all checked. Unknown schema/digest/truncation/foreign values refuse with originals preserved. Canonical item data survives NBT reload without a false conflict. |
| CT-6 | Inverse undo is an authoritative transaction: full placement removal/restoration, exact allowed refund, permanent retirement, no ID reuse. Any relevant later edit/dependency makes simple undo refuse without changing history. |
| CT-7 | Bounded C2S/S2C messages, direction pins, rate limit, owner/session/domain checks and vanilla permission/protection/world-border/reach checks. Ordinary structural placement and blueprint confirmation use the production transaction entry, not an unused helper or test-only command. Preserve existing result packet bytes. |
| CT-8 | Real client ghost/confirm/cancel/outcome/undo interaction, en/zh, current material geometry/HUD and inventory behavior. Exercise actual socket requests and server-observed results, plus malformed/replay/cancel/protection cases. Probes never enter the ordinary jar; original Windows CM/N25 remains distinct. |
| CT-9 | Full core/Forge suites with delivered native cases executed, existing regression fixtures, same jar/bytecode/source/licensing guards and exact-head CI. Preserve first failures and raw receipts. Record transaction latency/allocation/durability cost; do not claim v1 performance from correctness tests. |

Native analysis remains preview-scoped where persistent piece/joint/lifecycle semantics are
not supported by the delivered contract. Creating an artifact does not confer structural
stiffness or authorize destruction. All engine-generated fracture/crushing/rigid-body events,
damage authority and physical post-processing remain engine work; this unit adds none in Java.

The unit stays open across implementation steps until the Forge, recovery and client gates
are met. A tested transaction journal alone is necessary infrastructure, not CT completion,
not closure of #12/#17, and not a replacement of the full v1 objective.
