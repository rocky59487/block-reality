# CT adapter integration notes — active, not qualified gameplay

The criteria in `CONSTRUCTION_TRANSACTIONS.md` were committed at a180ae2/1dafb52.
The first implementation step adds the module transaction data model, bounded binary
journal and serial coordinator. There is **no Forge production caller yet**. None of
CT-1 through CT-9 is marked complete by the core fixtures alone.

## Implemented boundary

`ConstructionTransaction` binds the request UUID to actor, session, domain, base revision
and server-computed canonical plan SHA-256. Immutable before/after images include the
persistent revision participant. Receipt piece UUIDs are exposed only for COMMITTED;
preallocated UUIDs in an aborted intent never become published pieces.

`FileTransactionJournal` retains checksummed records and terminal outcomes without
eviction. It keeps only a bounded UUID/size index in memory, reads images lazily and
allows one outstanding PREPARED per domain. It forces the temporary file, atomically
replaces the record and forces its destination. POSIX also forces the directory;
Windows JDK17 has no directory FileChannel and reports `directorySyncAvailable=false`.
File/process interruption evidence does not qualify power loss or Minecraft durability.
Incomplete temp files are preserved and reported, counted toward admission capacity,
and never interpreted as decisions. Corruption or a missing indexed record latches
the journal closed; reopen performs a complete validation scan before recovery.

`AtomicConstructionCoordinator` starts closed. `recover` must finish before participants
are accessible. A failure after replacement is resolved from the actual record with
storage barriers repeated; reading a COMMITTED phase does not by itself clear an
uncertain storage barrier. PREPARED restores every known before-image and flushes all
participants before abort. Unknown external images are not overwritten. COMMITTED
never reapplies an old image over newer gameplay. Failed publication requires baseline
republication; it cannot reverse a committed decision. Reentrant calls are refused.

The Host is a persistence/visibility contract, not an implementation of those guarantees.
It must validate authenticated ownership and game rules, capture canonical NBT, keep
inventory/world/piece/revision changes inaccessible until publication, flush all real
participants, and block construction/analysis when recovery is required. Undo eligibility,
piece lifetime enforcement and product/blueprint validation still belong to the adapter
and authoritative piece registry; the journal cannot infer them from opaque images.

## Verified local Forge source findings

Read from the locally mapped Forge1.20.1-47.4.13 sources (not changed):

- `PlayerDataStorage.save` catches exceptions and returns void. Its successful return
  is not a persistence acknowledgment. The adapter must verify/force actual player data.
- `ChunkMap.save` clears the unsaved flag before writing, catches errors and can return
  false. `saveAllChunks(true)` also runs `mainThreadExecutor.managedBlock`, which can
  execute queued work while a provisional transaction is in progress. Do not use it as
  an opaque atomic barrier.
- `ChunkStorage.write` discards the future from `IOWorker.store`. `IOWorker.store` and
  `synchronize(true)` expose completion/failure; `RegionFile.flush` calls `force(true)`.
  The Forge adapter must await explicit completion without pumping game tasks and check
  relevant saved images. These public APIs need game/restart tests before being trusted.
- `ForgeHooks.onPlaceItemIntoWorld` invokes the BlockItem before its cancellable placement
  event and only then publishes snapshots. A transaction committed inside that item call
  could precede outer protection cancellation. The ordinary placement entry must avoid
  nested commit-before-cancellation, while preserving protection/reach/adventure checks.

Startup must recover affected offline inventories and chunk participants before login,
world interaction or analysis dispatch. Multiple dimensions share player inventories;
per-domain journal serialization alone does not provide a global player barrier. Existing
observational construction groups are retained but cannot authorize material refunds or
manufactured-piece undo. All of these remain required integration work.

## Core evidence procedure

`mod :core:test --tests 'com.blockreality.core.transaction.*'` exercises request binding,
immutable images, corruption/bounds, atomic file stage failures, every participant write,
flush/rollback/publication failure, competing requests and restart replay. The standalone
`:core:constructionRecoveryGate -Dbr.transactionEvidence=<fresh absolute directory>`
kills real fixture JVMs, retains raw files before recovery, starts new JVMs and verifies
every durable image/terminal receipt. It also checks a second live JVM cannot acquire
the owner's journal. Process drivers are test sources and never enter the normal jar.

These file fixtures do not provide Minecraft restart, canonical NBT, survival refund,
ghost/confirm/cancel/undo UI, ordinary socket placement, protection integration or FPS
evidence. #12/#17 and v1 remain open; engine lifecycle/crushing/rigid poses are still
separate delivered-contract dependencies, with no Java physics fallback.
