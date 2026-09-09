# CONSTRUCTION_IDENTITY (CI) — frozen before implementation

2026-09-10. Parent #113 / `cfaeb79`. Module only; engine and BSI contract are unchanged.
Implements the gameplay identity part of V04_PLAN §2.5 / MEMBER_SEMANTICS §9.5 / #17.

## Model

An artifact key is a saved per-dimension UUID namespace plus a positive monotonic object number.
Numbers are never reused after a published object retires. Native member/shell IDs remain scoped
to the current source, dimension and result revision. An artifact can link to many native elements
through their supplied cell lists; these links are previews, not authority to break blocks.

Grouping is gameplay bookkeeping, not finite-element extraction: same declared frame product and
axis joins only along that axis; parallel runs and crossing axes stay separate. Same-material
monolith cells join on six faces. Same declared panel product joins on six faces as a presentation
region; this **does not choose its physical plane, joints or shell discretization**. Different panel
thicknesses stay separate. Undeclared frame axes remain individual pending cells. All physical
decomposition, mechanisms, damage, fracture and rigid-body state remain engine-owned.

Unchanged objects and single-object extension/trimming keep their identity. A split creates new
child IDs for every surviving component; merging multiple old objects creates a new child with
all parents. Removal retires the ID. Confirmed destruction/replacement of every original cell
cannot inherit that old ID, even when replacement occurs before the next metadata update.
Axis edits may retain a single object's ID while recording its updated declaration. Geometry
edits are reconciled as a revision-stamped batch; no intermediate unobserved shape is claimed as
an engine fracture. Native fracture/reinstallation/damage transactions remain a later integration.

## Persistence, scheduling and bounds

The existing atomic SavedData file also stores declared cells, pending destruction observations,
the last completed object graph, its namespace/counter and lineage. Save during pending work and
restart must preserve enough information to finish without reusing an old identity. Existing
coverage-only saves migrate without dropping coordinates; unknown declarations stay pending until
observed, and unloading never removes a declaration or an object.

Grouping runs off the server thread on immutable inputs. Main-thread publication requires the
same metadata epoch; a stale worker cannot overwrite newer edits. It also runs with native analysis
OFF or unavailable. World input gathering and native dispatch retain WR's complete-domain barrier.

Bounds: 131072 declared cells and pending destruction positions, 262144 lifetime object records,
524288 lineage references, and 128 MiB encoded object data. Overflow refuses further object
publication with the prior graph retained; no eviction or wraparound. Schema/checksum/type/count,
duplicate cell/ID, orphan parent and invalid counter/epoch checks fail closed. Corrupt new-format
data must not fall back to the old coverage-only format or overwrite the saved file.

## Frozen gates

| Gate | Required evidence |
|---|---|
| CI-1 | Frame axis/product separation, monolith six-face fusion, panel product/thickness grouping, undeclared axis behavior; no native element extraction or mechanics in the module grouping code. |
| CI-2 | No-op, extension and trimming retain IDs; split/merge create monotonic IDs with exact parent sets; complete destruction/replacement cannot resurrect an ID; unrelated objects unchanged. |
| CI-3 | Object graph/declarations/pending edits survive disk reload; opposite loaded-chunk observation orders from the same saved registry preserve IDs and canonical graph bytes. Legacy coverage migration retains every known cell and explicitly pending unknown declarations. |
| CI-4 | Worker inputs immutable, grouping off main thread, stale completion refused, empty-world retirement completes, native OFF/unavailable does not prevent identity updates. |
| CI-5 | Bounds, damaged/unknown schema, duplicate ownership/IDs, bad parent/counter/epoch and pending-save corruption refuse without replacing original data; at least one compiled persistence fault arm and one identity-reuse fault arm must fail named behavioral oracles. |
| CI-6 | `/br object <pos>` reports permanent key, declaration, cell count, lineage and current/pending state. Links use native-supplied cell membership plus exact result revision, allow one object to many elements, and disappear/relabel when stale. No membership from stress values or inferred physical connectivity. |
| CI-7 | Actual isolated server: placed frame/monolith/panel grouping, split/merge and full replacement; native OFF identity; save/restart and cross-chunk unload/load preserve established identity. Confirm real engine elements can map to a persistent object without changing it after re-solve. Preserve first failures. |
| CI-8 | Existing core/Forge, WR/native server regressions, packet golden bytes, documentation and shipping jar checks pass. Integration probes remain absent from ordinary jars. |

This does not close #17 or #86: per-object native scheduling, damage, engine lifecycle events,
reinstallation, client hover identity delivery and v1 packaging/performance still require evidence.
The command is the first game-facing object inspection surface; no unverified client UI claim.
