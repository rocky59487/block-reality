# Construction journal domain bootstrap

Criteria019ca33 precede implementation471fad98f9f208f1d3635560820e84b5a1260f8c.
FileTransactionJournal.open discovers the domain from the existing checksummed
manifest under the journal's exclusive lock. A fresh domain is generated only
when the directory contains no evidence besides its owner lock. Missing markers
with records, temporary files or unknown contents refuse unchanged; malformed
size/schema/checksum/non-file markers refuse unchanged. Explicit pinned-domain
constructors retain compatible on-disk bytes.

entryIds returns an immutable, deterministic snapshot of bounded indexed keys
after resolving an ambiguous preceding replacement through existing verification.
Closed/read-failure-latched stores refuse enumeration. It is neither commit order
nor a rescan of external filesystem mutations, and does not replay world/player
images. Metadata reconstruction and the actual Forge construction service remain
separate work; there is still no production Forge construction caller.

Full suites with the published v1.5 libraries:

| Platform | Core | Forge | Total |
|---|---|---|---|
| Windows |454 registered /442 PASS /12 platform SKIP |124 PASS /0 SKIP |566 PASS /12 SKIP |
| Linux |454 registered /453 PASS /1 platform SKIP |124 PASS /0 SKIP |577 PASS /1 SKIP |

578 registered per platform, no failures/errors. All applicable native tests
execute. Five new actual-file tests cover factory/pin/exclusion, corrupt/missing
markers, immutable inventory/reopen, lost acknowledgment, close and corruption
latches. Each platform also repeats37 interrupted JVMs,37 fresh recovery JVMs and
one concurrent owner check using the existing file-participant process harness.
These are synthetic file participants, not actual Minecraft crash recovery.

Linux isolated mutation: control5 PASS; bypassing manifest integrity compiles and
fails malformedManifestIsNeverAcceptedOrReplaced; restored5 PASS. The modified
source is restored byte-for-byte. This unit's first full builds and artifact
checks passed; no unexpected behavioral failure was discarded. Existing parent
failures remain in their original evidence. The intended mutant failure is kept.

Ordinary Windows jar15,286,967 bytes, SHA256
`73a491fba10e76f2dfbd6514cba0daa410f7ff248b6e7dcc43d2e2b79d537834`.
Compared with03fff6f83036, only FileTransactionJournal.class changes. All386 other
entries/235 classes, both native libraries, provenance, resources, license text,
contract pin and result packet code remain byte-identical. Forge packet goldens
and source/bytecode/jar guards (including3 compiled arms) pass on both platforms.
Thirty-six quoted totals were updated only after measured XML and pass check_docs.
No repeated JNA replay or client run is claimed for this API-only change; those
unchanged paths retain their named v1.5 qualification.

receipts.json hashes30 raw archives/logs/identities/helpers. Both complete process
output trees and all JUnit XML are zipped with per-file SHA manifests verified
against the original bytes. Mutation logs/XML and restored-source hash are included.
Large duplicate jars/source archive/caches remain in the build qualification roots;
the source archive identity is recorded in source.json. Exact-head bootstrap CI is
pending. Parent cleanup6d1a0c2 CI passes4 jobs with15 native steps skipped; that
record does not qualify this new head. No engine build/change or v1 completion.
CT-1..9, manufactured metadata reconstruction, live chunk/player barriers, normal
placement/blueprint/undo/UI and actual socket/restart qualification remain open.
