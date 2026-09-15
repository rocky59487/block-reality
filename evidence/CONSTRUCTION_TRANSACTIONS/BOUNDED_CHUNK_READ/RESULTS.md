# CT_BOUNDED_CHUNK_READ — decoded bounds and installed runtime verified

Criteria b8b3f7a precede production0c95eea and test/source snapshot bca242a. Chunk
participant reads now run on the same IOWorker and existing region cache, after the
previous store/force barrier. They refuse a pending write and read at most16MiB+1
decoded bytes before constructing NBT tags. Oversized data refuses; admitted bytes
use the strict64-depth/262144-tag CanonicalNbt parser. There is no pending-image or
unlimited vanilla-parser fallback. Only the input stream is closed.

The four exact Access Transformer directives widen the pinned worker's storage,
pending-write inventory and task submission, and its existing region lookup. Final
flags remain unchanged. Names/descriptors were checked against the local pinned
Forge1.20.1 official/SRG mappings; the ordinary jar contains precisely those four
directives. Global Minecraft NBT parsing is unchanged. Engine code/libraries and
physical processing are untouched.

## Regression and behavioral evidence

The first Windows compilation and13-test chunk run pass. Six new reader tests
exercise decoded-byte stopping, impossible declared lengths, every NBT tag, unordered
compound input, compressed oversize, duplicates, truncation/trailing bytes, unknown
types, excessive depth, foreign positions and pending-store refusal. Real region
files survive repeated fresh-adapter refusals and worker close/reopen unchanged.
Every generated valid/corrupt region fixture and its recorded byte hashes is retained.

Both complete Forge runs pass137 tests with zero failures/errors/skips, using the
published v1.5 native library. Packet goldens and the ordinary255-class jar guard
pass, including all3 compiled forbidden-content arms. The472-test core production
and test sources remain unchanged from4737e3c, whose full dual-platform receipts
are explicitly reused. Current609 registration has Windows597 PASS/12 platform SKIP
and Linux608 PASS/1 platform SKIP coverage; this is not described as a fresh core run.

Linux control/restored each pass13 chunk tests. Compiled negatives select the
specific existing assertion that tests their defect:

- readAllBytes instead of the bounded read fails decodedByteCapStopsTheStreamBeforeTagAllocation
  because the observed stream consumption exceeds16MiB+1, even though the later byte
  check still rejects the document.
- Vanilla NbtIo.read instead of CanonicalNbt.read fails duplicateNamesRefuseWithoutChangingRegionFiles:
  a duplicate xPos with the same value cannot be silently accepted as a valid image.

Each mutant runs its one selected behavioral test, not the full13-test suite.
The vanilla mutant deliberately does not execute the impossible-array fixture that
would ask an unbounded parser for an enormous allocation. Successful compilation,
fresh XML timestamps and the exact failed assertions are recorded. The original
source is restored. No qualification failure, sample or raw region file was discarded.

## Ordinary installed jar

The Windows-qualified ordinary jar was copied unchanged into a fresh Linux Forge
1.20.1-47.4.13 installation, with a separate10,016-byte test harness mod. The harness
has neither production Block Reality classes nor an AT file. It invokes the actual
installed classes; both source URLs point into mods/blockreality-0.4.0-dev.jar.
Consequently the reader's successful direct worker/cache calls exercise the AT from
the ordinary jar, rather than relying on a development classpath transformation.

All12 harness checks pass, including ownership and class-origin prerequisites:

- The real ServerLevel participant checkpoints and reads a complete loaded chunk.
- A complete supplied after-image reads back exactly; the original baseline restores.
- The live chest and its seven iron ingots with the exact custom Chinese item tag
  remain unchanged during storage operations.
- The installed reader refuses duplicate-key and compressed oversized fixtures on
  owned fixture workers; every corresponding region-file byte remains unchanged.

The server stops normally with exit0 and releases its loopback port25598. The raw
result, class-load log, live chunk baseline NBT and corrupt region files are retained.
No original Windows client, security modal or CM server was changed. The actual
world is isolated and contains no copied player/world/cache data.

This is a real installed-server storage-path check, not a full construction transaction
or Minecraft crash/restart test. The harness supplies a clean vanilla-serialized chunk;
it does not prove capture of throwing capability providers or arbitrary custom save
hooks. Player inventory, manufactured metadata publication, ordinary placement,
blueprint/undo, socket/UI, CT-1..9 and FPS remain separate required integration work.

## Artifact and receipts

Jar15,326,800 bytes, SHA-256
`4bf732d83a1eccf81dbf30da57bf1955c9d69e8c67435c15c85431f57962efce`.
Compared with the previous b5a9bab7e7b6 artifact, only ChunkFileParticipant$1.class
changes; BoundedChunkRead.class and META-INF/accesstransformer.cfg are added.
All404 other old entries/253 classes remain byte-identical, including both native
libraries, notices, contract, provenance and assets. The AT SHA-256 is
`230f398dea92d754f81bbeacd172ea9abb764a7b2438e48ef0b7d9810d475a13`.
The harness is separate and absent from the shipped jar. Its own identity and sources
are retained alongside the installation library-copy manifest.

Archive entries were reopened and checked against embedded SHA-256 manifests. Each
full-suite platform contributes31 retained region-fixture files; the first run also
retains31. Mutation receipts contain their own control/failure/restored fixtures.
Region timestamps can differ between independent platform runs; the guarantee is
unchanged original files across each refusal, not identical timestamped region files
between platforms. No power-loss or performance acceptance is claimed.

Parent PR131 head540ba95 CI run34448272935 has4 successful jobs and15 skipped native
build steps; its receipt is included. This branch's exact-head CI is separately
required after push and cannot be inferred from that parent result.
