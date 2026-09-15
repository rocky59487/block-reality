# WORLD_REGISTRY — durable input coverage (WR, frozen before implementation)

2026-09-10. Module-only increment toward #86/#17; parent `2005624` (#112).
Engine source, contract, pins and assets are read-only in this work.

## Scope and remaining architecture

Persist the **known structural cell index** per dimension before adopting loaded chunks.
Loading is observation, not deletion: an unloaded chunk cannot shrink this index. A complete
loaded chunk scan replaces only that chunk's known cells, including an empty scan. Ordinary
placement/removal and reconciliation update the same index. Canonical order is x/y/z.
Material, axis, ground contacts and temporary test loads are still read from the live world;
the index does not contain elements, inferred joints, mechanics or native solver IDs.

This is a prerequisite, **not the persistent fused-object registry required by V04_PLAN §2.5**.
Stable artifact identity, declared-role grouping, split/merge lineage, persistent damage and
independently scheduled regions remain open. Existing worlds adopt previously unknown cells
when their chunks are loaded/scanned; this does not claim discovery of unseen saved chunks.

Until region scheduling can preserve complete native domain verdicts, a missing indexed cell
or unreadable six-face ground observation defers the **whole current dimension request**.
This intentionally also delays unrelated structures. Never send a shortened request and call
its buckling factor a complete world result. No chunk ticket or terrain generation is added.
N14 withholding remains as a defensive consumer; input deferral is the new first barrier.

## Storage and bounded growth

Version 1 stores canonical integer coordinates plus a SHA-256 integrity check inside dimension
SavedData `blockreality_world_index.dat`. Maximum 131072 cells; replace-chunk operations are
validated and capacity-checked before mutation. Capacity failure is a sticky persisted refusal,
not eviction; the previous index is retained. Restart cannot clear this refusal. No automatic
reset/rebuild is introduced. A missing file starts adoption; an existing corrupt/unknown-version
file refuses analysis and is **never overwritten with an empty index**. Atomic sibling-file
replacement keeps the last complete file on write failure and leaves the data dirty for retry.
This is bounded metadata, not a measured performance or crash-consistent block-world transaction
claim. Minecraft chunk writes and SavedData writes are not one atomic transaction.

## Frozen gates

| Gate | Required observation |
|---|---|
| WR-1 | Save/reload preserves all indexed coordinates, including negative chunks. Opposite chunk scan orders yield identical canonical encoded bytes; unchanged scans are no-ops. |
| WR-2 | Complete empty scan removes only its loaded chunk. Unload performs no deletion. Replacing a chunk validates all positions/duplicates before mutation. |
| WR-3 | Hard capacity boundary, overflow atomicity, sticky refusal across reload, bounded serialization; no silent eviction or permissive restart. |
| WR-4 | Corrupt digest, truncation, trailing bytes, duplicate/unsorted cells, invalid coordinates and unknown version reject. Existing unreadable/corrupt files survive open/save byte-for-byte; absent file may initialize. |
| WR-5 | Real SavedData disk round trip and dimension separation; failed atomic save preserves the previous file and remains dirty. No temporary or empty replacement becomes authoritative on failure. |
| WR-6 | Missing indexed structural cells **or ground observation cells** yield a waiting notice and zero native dispatches. A complete gather dispatches the complete snapshot; empty known world may clear. |
| WR-7 | Relevant chunk load/unload invalidates in-flight revision, retains known cells, and triggers retry; an irrelevant chunk need not invalidate. A loaded empty chunk also reconciles removed cells. |
| WR-8 | Existing clients receive PENDING with a bounded waiting explanation, retaining only visibly stale results. Bootstrap while waiting carries the notice without mislabelling a cached result as current. `/br status` includes coverage/refusal even when a previous result exists. Channel 11 accepts notice details already; payload layout stays unchanged. |
| WR-9 | Isolated real Linux server/native: supported beam crosses chunk boundary; unload half -> index retained and analysis deferred; save/restart while half absent -> still deferred; reload -> complete native result returns with same geometry/values. Preserve commands, first failures and receipts. This does not substitute for real-client N25. |
| WR-10 | Core/Forge checks and final jar gate pass; both storage corruption and missing-input dispatch fault arms must compile and be rejected by their named behavioral tests (not compiler errors). |

Only executed gates are credited. Retain first failing logs. No closing #86/#17 or declaring v1
on these gates alone. Native CI skipped for missing token remains explicitly unmeasured.
