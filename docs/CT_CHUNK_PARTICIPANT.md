# CT chunk persistence — criteria before implementation

2026-09-10, base87cc1cd / PR130. Continue CT_FORGE_PERSISTENCE and full CT-1..9;
this is an acknowledged region-storage participant, not a completed construction
entry or replacement for player/metadata/world visibility integration. Engine code,
libraries and physical post-processing remain untouched.

The adapter uses the pinned Minecraft/Forge IOWorker owned by the real level's
ChunkMap, without closing it or opening a competing RegionFileStorage. Every call
must occur on its original server/owner thread. It accepts complete canonical chunk
NBT prepared by the host, not just changed palettes, so unrelated sections, entities,
scheduled ticks, heightmaps and custom fields remain in the written image. Snapshot
data is immutable and independently owned. Input is bound to explicit chunk x/z,
current full-chunk schema and data version; missing/foreign/malformed headers refuse.

Before the first store, validate and clone the entire batch: at most256 chunks,
16MiB canonical NBT per chunk and64MiB aggregate, retaining the existing64-depth/
262144-tag limits. These are admission limits, not truncation; an invalid last
participant must not permit earlier writes. The full construction plan remains at
most4096 cells/256 pieces; a plan spanning more than256 chunks must explicitly refuse.

Wait for every issued store future to finish, then synchronize(true), then reload
each complete image and compare canonical bytes. A queued store, void vanilla save,
pending-map read or successful future without the storage barrier is not success.
Do not pump Minecraft's main-thread task queue while private state is being persisted.
Use the same acknowledged path for before-image checkpoints, after-images and rollback.
No missing chunk may be treated as a durable empty chunk; the host must provide a
fully serialized generated/loaded baseline before PREPARED.

Pre-store input rejection leaves the adapter usable. Once storage is entered, any
exception, failed barrier or content mismatch latches the adapter unavailable.
Drain all already-issued store futures even if issuing a later store throws. A new
owner/adapter must synchronize the same worker before recovery reads/writes, so an
old late write cannot overwrite a verified rollback. Recovery must use the actual
known images; this adapter never chooses a transaction decision or overwrites unknown
data automatically. No timeout may advertise completed persistence while I/O is live.

Use controlled future-backed tests for issuance/drain/barrier/read ordering, thread
exclusion, all-or-nothing admission, missing/wrong bytes, errors at each storage
boundary and recovery after an ambiguous write. Also exercise the actual IOWorker
and region files with complete multi-chunk NBT, reopen and exact content comparison.
Compiled negatives must demonstrate that omitted forcing and lost unrelated data
are caught by behavioral assertions after successful compilation. Run appropriate
full Forge regression/packet/jar checks on both platforms with delivered natives.
Preserve first failures; do not call fixture I/O a Minecraft restart or FPS test.

The future live capture/host must account for a verified pinned-source pitfall:
ChunkSerializer.write catches exceptions from chunk capability serialization and
can omit that data. Block-entity/custom save paths also need explicit failure and
preservation checks. A successful call to that serializer alone cannot promise a
complete participant image. Construction integration must resolve this before using
the writer in ordinary gameplay; it must also preserve protection/custom save hooks
without exposing private updates. This adapter accepts a host-supplied complete image
and does not claim those live capture/hook guarantees by itself.
