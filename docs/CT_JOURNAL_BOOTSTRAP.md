# Construction journal bootstrap — criteria before changes

2026-09-10, after native-verdict cleanup6d1a0c2. Continues the metadata-authority
requirements in CT_FORGE_PERSISTENCE. No production Forge construction caller or
complete manufactured-piece registry is claimed by this bounded journal API step.

The server owner must reopen its existing construction domain without an external
saved UUID becoming a second identity authority. Add FileTransactionJournal.open
which acquires the same exclusive ownership lock before reading the exact existing
56-byte checksummed domain/schema manifest. Existing valid domains are preserved;
new UUID generation is allowed only for an otherwise empty owned directory (the
lock file alone is allowed). Missing manifest with records, retained temporary
files or unknown contents refuses without writing a replacement domain. Corrupt,
short/oversized, unsupported schema or non-file manifests refuse unchanged.
Keep explicit pinned-domain constructors and existing on-disk bytes compatible.

Expose an immutable, deterministic, bounded snapshot of indexed transaction IDs,
not copies of every record image. Resolve an ambiguous preceding journal write
through existing verification before taking that snapshot. Closed or latched
corrupt journals refuse. This is a key inventory, not commit order and not a claim
to rescan external filesystem mutations on every call; metadata bootstrap must
read/validate each selected record and reconstruct only committed metadata in its
own recorded order. World/inventory history must never be reapplied by this API.

Tests use actual files: fresh creation/reopen/pinned compatibility and unchanged
manifest bytes; exclusive owner; malformed and missing domain markers preserve
all evidence; deterministic immutable ID snapshots across decisions/reopen;
post-replace lost acknowledgment includes the verified key; close/read-failure
latches refuse enumeration. One compiled mutant bypassing the manifest integrity
check must fail the malformed-manifest test; retain first failures and restore.

Run full core/Forge suites with the delivered v1.5 libraries on both platforms,
both existing37 interrupted-JVM/recovery runs and owner exclusion, jar/source
guards and current packet goldens. Keep skips explicit. Compare ordinary jar
contents to03fff6f83036: only journal class behavior and attributable debug metadata
may change; both libraries/resources/contract bytes stay exact. Record actual
counts before updating quoted totals. CI and artifact provenance remain required.
No performance or game durability claim; full CT-1..9 and live Forge wiring remain
open after this step.
