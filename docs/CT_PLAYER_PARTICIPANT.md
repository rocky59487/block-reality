# CT player participant — criteria before implementation

2026-09-10, follows CT_FORGE_PERSISTENCE at3db4e37. This intermediate adapter
qualification does not complete CT-1..9 or claim an ordinary gameplay entry exists.

Use vanilla named-root compound NBT and gzip player files. Canonical transaction
images sort compound keys, retain all tag types, list order, numeric bits and exact
item data. The pinned FloatTag/DoubleTag factories normalize negative zero; reject
that unrepresentable raw input instead of silently changing its bits. Other numeric
bit patterns, including NaN payloads, remain exact in this adapter. The reader rejects
duplicate keys, unknown tags, trailing/truncated data,
negative/impossible lengths and excessive nesting before unbounded allocation.
Frozen limits:64 container levels,262144 tags per document,1MiB per item image,
16MiB decoded player document and17MiB compressed player file. Refuse larger data;
never truncate it or replace unreadable data with a new empty player.

Inventory participant keys address vanilla slots0..40 (armor36..39, offhand40).
Read/write the saved Slot mapping0..35/100..103/150 explicitly, preserve every
unrelated player field and untouched inventory entry, reject ambiguous duplicate
or invalid slot data. Empty slots are missing values, distinct from empty compounds.
Offline recovery must not construct a player or invoke gameplay inventory mutation.

The player writer is synchronous under its caller's exclusive server-thread owner.
Compare captured full-file bytes before replacement so a stale snapshot cannot
overwrite unrelated later player data. Stage canonical gzip, force temporary bytes,
atomic replace, force destination, POSIX directory force, reload and compare.
No non-atomic fallback. Windows directory-force unavailability remains explicit.
Retain failed temporary files and propagate failures; never turn a void vanilla save
or a swallowed exception into a durability acknowledgment. No symlink target files.

Before PREPARED the coordinator must call a host checkpoint for all resources and
recheck captured values/revision. A checkpoint failure creates no intent and changes
no visible participant. Post-PREPARED barriers retain the already frozen ordering.

Required adapter checks:all12 NBT tags and numeric edge bits; insertion-order
independence and canonical byte replay; vanilla NbtIo interoperability; malformed
length/type/depth/count/truncation/duplicate/trailing inputs; exact slot mapping and
NBT preservation; offline file reload; missing/corrupt/foreign/stale file refusal;
failures before and after atomic replacement retain a determinable disk image;
checkpoint called before intent, failed checkpoint leaves no record, post-checkpoint
state change refuses. Use actual temporary files and compiled behavioral negatives
for skipped checkpoint, lost unrelated data and accepted duplicate slots. Preserve
first failures. Run complete core/Forge regression suites with delivered natives,
jar guards and cross-platform adapter tests; record costs without an FPS claim.

Real Minecraft restart, chunk durability, protection, manufactured metadata,
network/client confirmation and undo remain full CT gates, not covered by these
file-level or synthetic-host checks.

Pre-implementation extension after the first14 Forge/3 new core checks:failed player
temporary files also need bounded admission, independent of normal vanilla player
files. Retain at most1024 such files and1GiB total; refuse writes that cannot reserve
their full temporary bytes. Reopen scans and validates retained temporary files;
unknown temporary sizes/types refuse, never auto-delete evidence. Exercise reduced
package-local quotas against real files; production callers cannot raise these caps.

## Nested live baseline preservation — frozen B2 criteria (2026-10-10)

Construction must fail closed before checkpoint/PREPARED when the live serializer
cannot represent a saved field. Recursively require every saved compound key and
the same tag type in the live document, including ForgeData/ForgeCaps and every
compound/list element. Scalar values (Health, coordinates, Count, etc.) may change;
byte/int/long array lengths/values and pure scalar-list lengths/values may change,
but nonempty scalar lists must retain their element type. Empty lists contain no
old element shape to preserve. New live keys/elements are not saved extensions.

Nonempty compound/nested-list containers require equal lengths and per-index
recursive shape correspondence; do not search/reorder/match elements by guessed
identity. If this correspondence loses a key/type, truncates, clears or changes
container shape, refuse. Normal game saves, not this construction adapter, own
legitimate schema removal/reordering. Never merge saved-only data into live state,
automatically save the player, or invent schema-migration authority to allow it.
Validate both complete documents under CanonicalNbt's existing 64-depth/262144-tag/
16MiB bounds before recursive comparison, including newly added live subtrees.

Required focused evidence: saved-only nested ForgeData/ForgeCaps keys, later
compound-list elements, nested lists, changed tag/container types and ambiguous
container lengths refuse with IOException. Changed scalar/array/scalar-list values
and Count-only inventory updates remain valid; all existing exact NBT/item rules
remain unchanged. In transformed Forge, participant construction/checkpoint must
refuse the saved-only extension with original player.dat raw bytes, journal keys
and live inventory unchanged, while normal numeric/Count updates checkpoint.
Exercise depth/size/tag bounds without StackOverflowError and catch a compiled
negative that omits the recursive check. Preserve the original audit failures.

## Game-owned player file boundary — dated replacement criteria (2026-10-10)

The preceding B2 criteria remain historical: comparing a *stale* player.dat to
the current live player is not a legitimate test of representation. On facbafb,
the transformed Forge server rejected three ordinary operations: vanilla pickup
into a new Inventory slot, consumption of the last stack, and expiry of the last
ActiveEffects entry. These were expected construction checkpoint successes, but
each raised IOException. Preserve the first-failure log; facbafb is not accepted.

Instead, observe actual Forge PlayerEvent.LoadFromFile/SaveToFile at LOWEST
priority, after normal mod handlers, on the owning server thread. At that
*same boundary*, compare the on-disk complete document with saveWithoutId via
the bounded recursive B2 structural check. A mismatch marks this actor as
unrepresented, blocking construction until a subsequent *consistent* boundary.
Record the raw player.dat bytes' SHA-256 as a game-owned, in-memory digest per
actor; clear it on logout/server stop. An unreadable/missing file or failed
serialization must not be treated as consistent. No implicit game-save, saved
extension merge, schema migration, or exemption is allowed: the exemption
table is empty; only an unavoidable vanilla difference reproduced on a real
server may be appended here explicitly before changing this criterion.

Before a live participant checkpoint/PREPARED, if the file exists, require a
digest record, that the current raw file digest equals the record, and that
the actor is not marked unrepresented. Do not compare stale-disk structure to
current live structure. Preserve the saved inventory's existing exact 41-slot
decode validation. Participant writes (checkpoint/flush/rollback) update the
in-memory digest only after durable success; failed/uncertain writes do not
authorize a new digest. Offline recovery preserves the existing exact-file
rules and must not create a live actor. Missing digest fails closed; subsequent
valid Load/Save boundary may re-establish trust.

Required transformed-server evidence: on facbafb all three ordinary operations
first fail as above; after this change pickup, last-stack consumption and potion
expiry can checkpoint/commit, and two consecutive constructions succeed as
checkpoint/flush refreshes the digest. External ForgeData.audit_saved_only
injected after a boundary refuses with raw file bytes, inventory, journal and
world unchanged. A test mod adding an unrepresented field during SaveToFile
marks actor unrepresented and refuses construction. Logout/login/server restart
must rebuild a valid digest only at the real file boundary. Prove no digest
record and mismatched digest refuse via focused unit tests; a compiled negative
that removes the digest comparison must fail a behavioral assertion. Retain all
original B2 nested-shape, NBT and exact inventory tests as pure structural
oracles, not as a stale checkpoint policy; no SKIP is a PASS.

Boundary serialization is not placement permission: an ordinary game save may
occur while a container/cursor stack is open. The boundary still witnesses the
complete saved player document; construction retains its closed-menu/cursor
requirement. Live participant persistence rechecks the raw digest at its barrier,
including canonical-NBT-equal raw rewrites after participant capture. A failed
write is acknowledged only after the existing uncertain-write recovery verifies
and forces a known complete document; offline writes never create game-owned
trust or clear an unrepresented marker.
