# CT live chunk capture — frozen before implementation

2026-09-10; base02856fd / PR132. This is module integration work. The published
tectonic2 v1.5 libraries and contract remain unchanged. Full CT-1..9 still apply.

Before a construction checkpoint can acknowledge live chunks, capture must detect
the pinned Forge serializer's swallowed block-entity and chunk-capability failures.
Use the real loaded LevelChunk on its server thread, without generation or pending
block-entity promotion. Refuse pending, removed, foreign or inconsistently indexed
block entities. Direct saveWithFullMetadata/writeCapsToNBT calls supply independent,
bounded witnesses; exceptions propagate as a refusal, never an empty replacement.
Require exactly the witnessed block-entity positions/data and capability presence/data
in the vanilla serialization. Preserve all other serialized fields and NBT types.

Post the normal ChunkDataEvent.Save once for the candidate image. Additive custom
top-level fields are preserved. A hook that deletes or rewrites an existing serialized
field is unsupported and explicitly refuses. Recheck the live serializer and direct
witnesses after the hook; unstable providers, changed live data or omitted values
refuse. Return a private canonical copy, not a retained hook-owned mutable tag.
Providers/hooks are cooperative foreign code: this check cannot bound their internal
allocations or roll back their arbitrary external side effects. It performs no game
mutation or I/O itself. Concurrent light changes may cause a conservative refusal.

A batch contains at most256 already loaded chunks,16MiB per image and64MiB in total.
Check every requested key/loading/binding before invoking providers; finish every
capture and validate the whole batch before issuing any store. Checkpoint retains
the existing worker's drain/force/readback path. Do not clear a chunk's unsaved flag:
the host still owns exclusion and later vanilla persistence. A capture error makes
no participant store and creates no PREPARED journal entry.

Required evidence before this subunit is accepted:

1. Behavioral tests for exact capability/entity presence, duplicates, wrong positions,
   dropped data, unstable providers, hook rewrite/removal, additive custom data,
   private copies, and pre-I/O batch refusal.
2. A real installed Forge47.4.13 server using the ordinary jar, with a separate harness
   that contains no production classes or access transformer. Exercise a real chest,
   serializable chunk/block-entity capabilities, exceptions swallowed in the middle
   vanilla call, pending entity refusal, custom save events, durable readback and
   unchanged live inventory. Retain raw first failures and exact artifact identity.
3. Compile and run behavioral negative arms that omit entity/capability witness
   verification and the hook rewrite guard. Restore the source and rerun controls.
4. Full Forge tests on Windows/Linux using delivered v1.5 libraries, packet/class/jar
   guards, source snapshot and exact-head CI. Core results can be reused only with
   byte-identical core sources and explicitly reported as reused. Record capture cost;
   do not equate it with transaction, FPS, crash-recovery or full CT qualification.

The subsequent construction host must also suppress StructureManager observations,
native dispatch and client publication during private work, then publish once at
R+1 after a durable COMMITTED decision. This capture subunit does not claim that
ordinary placement, inventory transactions, manufactured IDs, undo or UI are wired.
