# CT bounded chunk read — criteria before implementation

2026-09-10, base540ba95 / PR131. The acknowledged writer's canonical-image limits
currently run after vanilla IOWorker has materialized an unbounded NBT document.
Resolve that read boundary before wiring live construction recovery. Keep all prior
chunk/CT gates; engine code/libraries and global Minecraft NBT behavior stay unchanged.

Use a narrowly scoped Forge Access Transformer to reach the existing worker's
storage, pending-write inventory and task submission, and its existing region cache
lookup. Retain final flags. Names/descriptors must be checked against the pinned
1.20.1 official/SRG mapping. The exact META-INF/accesstransformer.cfg must be present
in the ordinary jar and enabled in development. This follows the supported
[Forge AT mechanism](https://docs.minecraftforge.net/en/1.20.x/advanced/accesstransformers/).
Do not open another RegionFileStorage or read region handles on the server thread.

After the existing write/drain/force barrier, enqueue the bounded read on that same
IOWorker. Reject a still-pending write for the requested chunk. Read its decompressed
stream to at most16MiB+1 bytes; reject excess before creating NBT tag objects. Parse
with the existing strict bounded CanonicalNbt reader:64 depth,262144 tags, exact
named-root/type/length/duplicate/trailing validation. No fallback to vanilla's
unlimited reader or pending-map image. Missing stays missing. Close only the input
stream, not the worker/cache/region handle. Propagate ordinary errors through the
future so the participant latches unavailable. Worker lifetime/save exclusion remain
the host's responsibility, as before.

Preserve exact content for normal complete region NBT, including arbitrary compound
key order and every tag type allowed by CanonicalNbt. Raw-stream capacity checks are
additional to the writer's aggregate admission, not a lower replacement of it.
Malformed/oversized/duplicate/truncated/deep/foreign data must not trigger an automatic
rewrite. Retain actual corrupt/valid fixture bytes and hashes for these new checks.

Exercise real IOWorker/region files, a compressed oversized document, impossible
declared lengths and duplicate keys, before/after/rollback readback and repeated
fresh-adapter refusal. Verify the maximum decoded byte read without allocating a
declared oversized array. Compiled negatives remove the stream cap and substitute
the vanilla reader; behavioral tests must catch them without a compiler failure.
Archive first failures and prove original fixture files are unchanged after refusal.

Because AT requires runtime transformation, dev compilation alone is insufficient:
verify the normal jar's exact AT entries and run the same reader against a real
installed Forge server with classes loaded from that jar, using an isolated test-only
harness that does not ship. Run appropriate full Forge/native/packet/jar regression
checks and exact-head CI. No original Windows CM/N25, full transaction/restart, FPS
or v1 claim is made by this reader. Live complete capture/custom hooks and the
ordinary placement/blueprint/undo host still remain required.
