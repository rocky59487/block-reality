package com.blockreality.core.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.transaction.ConstructionTransaction;
import com.blockreality.core.world.ConstructionDeclaration;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The canonical binary codec for finite durability (design §4).
 *
 * <p>Three envelopes, all fail-closed on unknown magic/schema/kind:
 *
 * <ul>
 *   <li>{@code BRV2OBJ1} — the immutable object envelope for {@code objects/<sha256>.blob}:
 *       schema, kind, payload length and payload SHA-256. The object name must equal the
 *       hash of the <em>complete envelope</em>, so a tampered payload, header or kind can
 *       never validate under its own name.
 *   <li>{@code BRV2SRC1} — the tagged-section serialization of a {@link FiniteSource}.
 *       Every section is mandatory exactly once, in ascending tag order; the declared
 *       support-material id set is persisted so decode re-checks owner coverage.
 *   <li>{@code BRV2VAL1} — versioned journal values: domain descriptors, owner bindings,
 *       root descriptors and finite operation descriptors. Schema 1 only; anything else
 *       is an error, never a guess.
 * </ul>
 *
 * <p>Checkpoint blobs stay opaque end to end: they are stored, hashed and moved as bytes
 * and are never decoded, re-encoded or round-tripped through JSON/floats.
 */
public final class FiniteCodec {

    public static final byte[] OBJECT_MAGIC = {'B', 'R', 'V', '2', 'O', 'B', 'J', '1'};
    public static final byte[] SOURCE_MAGIC = {'B', 'R', 'V', '2', 'S', 'R', 'C', '1'};
    public static final byte[] VALUE_MAGIC = {'B', 'R', 'V', '2', 'V', 'A', 'L', '1'};
    public static final int ENVELOPE_HEADER_BYTES = 8 + 4 + 4 + 8 + 32;
    public static final int SCHEMA = 1;

    public static final int KIND_SOURCE = 1, KIND_CHECKPOINT = 2;
    public static final int VAL_DOMAIN = 1, VAL_BINDING = 2, VAL_ROOT = 3, VAL_OPERATION = 4;

    private FiniteCodec() { }

    // ---------------------------------------------------------------- object envelope

    /** The complete {@code BRV2OBJ1} envelope for one payload; its SHA-256 names the object. */
    public static byte[] pack(int kind, byte[] payload) {
        if (kind != KIND_SOURCE && kind != KIND_CHECKPOINT) throw fail();
        if (payload == null || payload.length < 1 || payload.length > FiniteStore.MAX_OBJECT_BYTES) throw fail();
        var out = new W();
        out.raw(OBJECT_MAGIC).u32(SCHEMA).u32(kind).u64(payload.length)
                .raw(HexFormat.of().parseHex(FiniteSource.sha256(payload))).raw(payload);
        return out.bytes();
    }

    public record ObjectBody(int kind, byte[] payload) {
        public ObjectBody { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }

    /** Validate one complete envelope read from disk: magic, schema, kind, length, hash. */
    public static ObjectBody unpack(byte[] encoded, String expectedName) {
        if (encoded == null || encoded.length < ENVELOPE_HEADER_BYTES
                || encoded.length > FiniteStore.MAX_OBJECT_BYTES + ENVELOPE_HEADER_BYTES) throw fail();
        var r = new R(encoded);
        for (byte m : OBJECT_MAGIC) if (r.u8() != (m & 0xFF)) throw fail();
        if (r.u32() != SCHEMA) throw fail();
        int kind = (int) r.u32();
        if (kind != KIND_SOURCE && kind != KIND_CHECKPOINT) throw fail();
        long length = r.u64();
        if (length != encoded.length - ENVELOPE_HEADER_BYTES) throw fail();
        var hash = r.raw(32);
        var payload = r.raw((int) length);
        r.done();
        if (!java.security.MessageDigest.isEqual(hash, HexFormat.of().parseHex(FiniteSource.sha256(payload)))) throw fail();
        if (expectedName != null && !FiniteSource.sha256(encoded).equals(expectedName)) throw fail();
        return new ObjectBody(kind, payload);
    }

    // ---------------------------------------------------------------- source payload

    private static final int S_VOCAB_REQUEST = 1, S_VOCAB_REPLY = 2, S_BLOCKS = 3, S_OWNERS = 4,
            S_MATERIALS = 5, S_SHELL_LAWS = 6, S_SHELL_LAYERS = 7, S_CELL_LOADS = 8,
            S_PRESCRIBED = 9, S_LOADS = 10, S_OPTIONS = 11, S_SUPPORT = 12;

    static byte[] encodeSource(FiniteSource source) {
        var out = new W();
        out.raw(SOURCE_MAGIC).u32(SCHEMA).u32(12);
        section(out, S_VOCAB_REQUEST, source.vocabRequest());
        section(out, S_VOCAB_REPLY, source.vocabReply());
        section(out, S_BLOCKS, source.blocks40());
        section(out, S_OWNERS, source.owners20());
        section(out, S_MATERIALS, source.materials());
        section(out, S_SHELL_LAWS, source.shellLaws());
        section(out, S_SHELL_LAYERS, source.shellLayers());
        section(out, S_CELL_LOADS, source.cellLoads());
        section(out, S_PRESCRIBED, source.prescribed());
        section(out, S_LOADS, source.loads());
        section(out, S_OPTIONS, source.options());
        var support = new W();
        for (int id : source.supportMaterialIds()) support.u32(id);
        section(out, S_SUPPORT, support.bytes());
        return out.bytes();
    }

    private static void section(W out, int tag, byte[] bytes) { out.u32(tag).u64(bytes.length).raw(bytes); }

    static FiniteSource decodeSource(byte[] payload) {
        var r = new R(payload);
        for (byte m : SOURCE_MAGIC) if (r.u8() != (m & 0xFF)) throw fail();
        if (r.u32() != SCHEMA || r.u32() != 12) throw fail();
        byte[][] sections = new byte[13][];
        int previous = 0;
        for (int i = 0; i < 12; i++) {
            int tag = (int) r.u32();
            long length = r.u64();
            if (tag <= previous || tag < S_VOCAB_REQUEST || tag > S_SUPPORT || length > Integer.MAX_VALUE) throw fail();
            previous = tag;
            sections[tag] = r.raw((int) length);
        }
        r.done();
        for (int tag = S_VOCAB_REQUEST; tag <= S_SUPPORT; tag++) if (sections[tag] == null) throw fail();
        var support = sections[S_SUPPORT];
        if (support.length % 4 != 0 || support.length > 4L * (1 << 24)) throw fail();
        var ids = new int[support.length / 4];
        var buffer = ByteBuffer.wrap(support).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < ids.length; i++) ids[i] = buffer.getInt();
        return FiniteSource.of(sections[S_VOCAB_REQUEST], sections[S_VOCAB_REPLY], sections[S_BLOCKS],
                sections[S_OWNERS], sections[S_MATERIALS], sections[S_SHELL_LAWS], sections[S_SHELL_LAYERS],
                sections[S_CELL_LOADS], sections[S_PRESCRIBED], sections[S_LOADS], sections[S_OPTIONS], ids);
    }

    // ---------------------------------------------------------------- journal values

    static byte[] encodeDomain(FiniteDomain domain) {
        var out = header(VAL_DOMAIN);
        var id = domain.id();
        out.uuid(id.journalDomain()).utf8(id.dimension()).uuid(id.bsiDomain()).uuid(id.artifactNamespace());
        out.u64(domain.nextOwnerId()).u64(domain.observationEpoch()).uuid(domain.createdBy());
        return out.bytes();
    }

    static FiniteDomain decodeDomain(byte[] data) {
        var r = value(data, VAL_DOMAIN);
        var id = new FiniteDomainId(r.uuid(), r.utf8(256), r.uuid(), r.uuid());
        var domain = new FiniteDomain(id, r.i64(), r.i64(), r.uuid());
        r.done();
        return domain;
    }

    static byte[] encodeBinding(ArtifactBinding binding) {
        var out = header(VAL_BINDING);
        out.u64(binding.ownerId()).u32(binding.origin().ordinal());
        out.uuidOrZero(binding.pieceId()).utf8OrNull(binding.legacyKey(), ArtifactBinding.MAX_LEGACY_KEY);
        out.uuid(binding.birthTransaction());
        out.utf8(binding.declaration().material()).utf8(binding.declaration().section()).i32(binding.declaration().axis());
        out.u32(binding.disposition().ordinal()).uuidOrZero(binding.retiredBy());
        out.u32(binding.cells().size());
        for (BlockKey cell : binding.cells()) out.i32(cell.x()).i32(cell.y()).i32(cell.z());
        return out.bytes();
    }

    static ArtifactBinding decodeBinding(byte[] data) {
        var r = value(data, VAL_BINDING);
        long ownerId = r.i64();
        int origin = (int) r.u32();
        var piece = r.uuidOrZero();
        var legacy = r.utf8OrNull(ArtifactBinding.MAX_LEGACY_KEY);
        var birth = r.uuid();
        var declaration = new ConstructionDeclaration(r.utf8(64), r.utf8(64), r.i32());
        int disposition = (int) r.u32();
        var retiredBy = r.uuidOrZero();
        long cellCount = r.u32();
        if (origin >= ArtifactBinding.Origin.values().length
                || disposition >= ArtifactBinding.Disposition.values().length
                || cellCount < 1 || cellCount > 4096) throw fail();
        var cells = new ArrayList<BlockKey>((int) cellCount);
        for (int i = 0; i < cellCount; i++) cells.add(new BlockKey(r.i32(), r.i32(), r.i32()));
        r.done();
        return new ArtifactBinding(ownerId, ArtifactBinding.Origin.values()[origin], piece, legacy, birth,
                declaration, cells, ArtifactBinding.Disposition.values()[disposition], retiredBy);
    }

    static byte[] encodeRoot(FiniteRoot root) {
        var out = header(VAL_ROOT);
        out.u64(root.worldRevision()).u64(root.historySequence()).ref(root.source());
        var checkpoint = root.checkpoint();
        if (checkpoint == null) out.u32(0);
        else {
            out.u32(1).ref(checkpoint.blob()).u32(checkpoint.format())
                    .utf8(checkpoint.engineBuild()).utf8(checkpoint.contractSha256())
                    .u64(checkpoint.byteBudget()).u64(checkpoint.allocationBudget()).u64(checkpoint.elementBudget());
        }
        out.hash(root.prevRootHash()).uuid(root.requestId());
        return out.bytes();
    }

    static FiniteRoot decodeRoot(byte[] data) {
        var r = value(data, VAL_ROOT);
        long revision = r.i64(), sequence = r.i64();
        var source = r.ref();
        FiniteRoot.Checkpoint checkpoint = null;
        long present = r.u32();
        if (present == 1) {
            checkpoint = new FiniteRoot.Checkpoint(r.ref(), (int) r.u32(), r.utf8(256), r.utf8(128),
                    r.i64(), r.i64(), r.i64());
        } else if (present != 0) throw fail();
        var root = new FiniteRoot(revision, sequence, source, checkpoint, r.hash(), r.uuid());
        r.done();
        return root;
    }

    // ---------------------------------------------------------------- operation descriptor

    /**
     * The versioned operation descriptor recorded at {@code finite/tx/<requestId>} (design §4.2).
     * Schema 1 ops: 1 createDomain, 2 bind (manufactured + legacy adoption), 3 retire,
     * 4 observe, 5 commitRoot.
     */
    public sealed interface Operation {
        record CreateDomain(String dimension) implements Operation {
            public CreateDomain { FiniteDomainId.dimension(dimension); }
        }
        /** One binding the transaction asks to mint; the registry assigns the owner id. */
        record BindingPlan(ArtifactBinding.Origin origin, UUID pieceId, String legacyKey,
                           ConstructionDeclaration declaration, List<BlockKey> cells) {
            public BindingPlan {
                java.util.Objects.requireNonNull(origin);
                java.util.Objects.requireNonNull(declaration);
                cells = ArtifactBinding.canonicalCells(cells);
            }
            public static BindingPlan manufactured(UUID pieceId, ConstructionDeclaration declaration, List<BlockKey> cells) {
                return new BindingPlan(ArtifactBinding.Origin.MANUFACTURED, pieceId, null, declaration, cells);
            }
            public static BindingPlan legacy(String key, ConstructionDeclaration declaration, List<BlockKey> cells) {
                return new BindingPlan(ArtifactBinding.Origin.LEGACY_NO_REFUND, null, key, declaration, cells);
            }
        }
        record Bind(List<BindingPlan> plans) implements Operation {
            public Bind {
                plans = List.copyOf(plans);
                if (plans.isEmpty() || plans.size() > 256) throw fail();
            }
        }
        record Retire(java.util.Set<Long> ownerIds) implements Operation {
            public Retire {
                ownerIds = java.util.Set.copyOf(ownerIds);
                if (ownerIds.isEmpty() || ownerIds.size() > 4096) throw fail();
                for (long id : ownerIds) if (id <= 0) throw fail();
            }
        }
        record Observe() implements Operation { }
        record CommitRoot(FiniteRoot.Next next) implements Operation {
            public CommitRoot { java.util.Objects.requireNonNull(next); }
        }
    }

    static byte[] encodeOperation(Operation op) {
        var out = header(VAL_OPERATION);
        if (op instanceof Operation.CreateDomain create) {
            out.u32(1).utf8(create.dimension());
        } else if (op instanceof Operation.Bind bind) {
            out.u32(2).u32(bind.plans().size());
            for (var plan : bind.plans()) {
                // Owner id is minted by the registry; the descriptor carries identity + provenance.
                out.u32(plan.origin().ordinal()).uuidOrZero(plan.pieceId())
                        .utf8OrNull(plan.legacyKey(), ArtifactBinding.MAX_LEGACY_KEY)
                        .utf8(plan.declaration().material()).utf8(plan.declaration().section())
                        .i32(plan.declaration().axis()).u32(plan.cells().size());
                for (BlockKey cell : plan.cells()) out.i32(cell.x()).i32(cell.y()).i32(cell.z());
            }
        } else if (op instanceof Operation.Retire retire) {
            out.u32(3).u32(retire.ownerIds().size());
            var sorted = new ArrayList<>(retire.ownerIds());
            java.util.Collections.sort(sorted);
            for (long id : sorted) out.u64(id);
        } else if (op instanceof Operation.Observe) {
            out.u32(4);
        } else if (op instanceof Operation.CommitRoot commit) {
            var next = commit.next();
            out.u32(5).u64(next.worldRevision()).u64(next.historySequence()).ref(next.source());
            var checkpoint = next.checkpoint();
            if (checkpoint == null) out.u32(0);
            else {
                out.u32(1).ref(checkpoint.blob()).u32(checkpoint.format())
                        .utf8(checkpoint.engineBuild()).utf8(checkpoint.contractSha256())
                        .u64(checkpoint.byteBudget()).u64(checkpoint.allocationBudget()).u64(checkpoint.elementBudget());
            }
        } else throw fail();
        return out.bytes();
    }

    static Operation decodeOperation(byte[] data) {
        var r = value(data, VAL_OPERATION);
        int tag = (int) r.u32();
        Operation op;
        if (tag == 1) {
            op = new Operation.CreateDomain(r.utf8(256));
        } else if (tag == 2) {
            long count = r.u32();
            if (count < 1 || count > 256) throw fail();
            var plans = new ArrayList<Operation.BindingPlan>((int) count);
            for (int i = 0; i < count; i++) {
                int origin = (int) r.u32();
                var piece = r.uuidOrZero();
                var legacy = r.utf8OrNull(ArtifactBinding.MAX_LEGACY_KEY);
                var declaration = new ConstructionDeclaration(r.utf8(64), r.utf8(64), r.i32());
                long cells = r.u32();
                if (origin >= ArtifactBinding.Origin.values().length || cells < 1 || cells > 4096) throw fail();
                var list = new ArrayList<BlockKey>((int) cells);
                for (int c = 0; c < cells; c++) list.add(new BlockKey(r.i32(), r.i32(), r.i32()));
                plans.add(new Operation.BindingPlan(ArtifactBinding.Origin.values()[origin], piece, legacy,
                        declaration, list));
            }
            op = new Operation.Bind(plans);
        } else if (tag == 3) {
            long count = r.u32();
            if (count < 1 || count > 4096) throw fail();
            var ids = new java.util.TreeSet<Long>();
            for (int i = 0; i < count; i++) if (!ids.add(r.i64())) throw fail();
            op = new Operation.Retire(ids);
        } else if (tag == 4) {
            op = new Operation.Observe();
        } else if (tag == 5) {
            long revision = r.i64(), sequence = r.i64();
            var source = r.ref();
            FiniteRoot.Checkpoint checkpoint = null;
            long present = r.u32();
            if (present == 1) {
                checkpoint = new FiniteRoot.Checkpoint(r.ref(), (int) r.u32(), r.utf8(256), r.utf8(128),
                        r.i64(), r.i64(), r.i64());
            } else if (present != 0) throw fail();
            op = new Operation.CommitRoot(new FiniteRoot.Next(revision, sequence, source, checkpoint));
        } else throw fail();
        r.done();
        return op;
    }

    // ---------------------------------------------------------------- HEAD cache

    /**
     * The {@code HEAD-<bsiDomainHex>} cache file: a convenience pointer to the last committed
     * root hash plus the basis fields. It is verified against the journal root on every open
     * and never authorizes anything by itself.
     */
    public static byte[] encodeHead(FiniteBasis basis, String rootHash) {
        FiniteBasis.hash(rootHash);
        var out = new StringBuilder(320);
        out.append("BRV2HEAD1\n")
                .append(BsiHex(basis.domain().bsiDomain())).append('\n')
                .append(rootHash).append('\n')
                .append(basis.worldRevision()).append('\n')
                .append(basis.historySequence()).append('\n')
                .append(basis.sessionGeneration()).append('\n')
                .append(basis.sourceHash()).append('\n')
                .append(basis.vocabHash()).append('\n')
                .append(basis.ownersHash()).append('\n');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    public record Head(String rootHash, FiniteBasis basis) { }

    public static Head decodeHead(byte[] data, FiniteDomainId domain) {
        if (data == null || data.length > 4096) throw fail();
        var lines = new String(data, StandardCharsets.UTF_8).split("\n", -1);
        // encodeHead terminates the ninth field with '\n', so a canonical file splits to
        // exactly ten parts with an empty tail — anything else is not our cache format.
        if (lines.length != 10 || !lines[9].isEmpty() || !lines[0].equals("BRV2HEAD1")
                || !lines[1].equals(BsiHex(domain.bsiDomain()))) throw fail();
        long revision = number(lines[3]), sequence = number(lines[4]), generation = number(lines[5]);
        var basis = new FiniteBasis(domain, revision, sequence, lines[6], lines[7], lines[8], generation);
        return new Head(lines[2], basis);
    }

    private static long number(String text) {
        if (text.isEmpty() || text.length() > 20 || !text.matches("[0-9]+")) throw fail();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException bad) { throw fail(); }
    }

    static String BsiHex(UUID id) { return id.toString().replace("-", ""); }

    // ---------------------------------------------------------------- shared checks

    /** Contract checkpoint formats 1–3 (1 finite state, 2 +arc direction, 3 +shell tensor state). */
    static void format(int format) { if (format < 1 || format > 3) throw fail(); }

    static void text(String value, int maxBytes) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw fail();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c > 126) throw fail();
        }
    }

    static IllegalArgumentException fail() { return new IllegalArgumentException("Finite codec violation"); }

    private static W header(int valueKind) { return new W().raw(VALUE_MAGIC).u32(valueKind).u32(SCHEMA); }

    private static R value(byte[] data, int expectedKind) {
        if (data == null || data.length > ConstructionTransaction.MAX_VALUE_BYTES) throw fail();
        var r = new R(data);
        for (byte m : VALUE_MAGIC) if (r.u8() != (m & 0xFF)) throw fail();
        if (r.u32() != expectedKind || r.u32() != SCHEMA) throw fail();
        return r;
    }

    // ---------------------------------------------------------------- cursor helpers

    /** Little-endian writer for numbers; UUIDs and hashes are fixed-width raw fields. */
    static final class W {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        W raw(byte[] bytes) { out.write(bytes, 0, bytes.length); return this; }
        W u8(int value) { if (value < 0 || value > 255) throw fail(); out.write(value); return this; }
        W u32(long value) {
            if (value < 0 || value > 0xFFFFFFFFL) throw fail();
            for (int i = 0; i < 4; i++) out.write((int) (value >>> (8 * i)) & 0xFF);
            return this;
        }
        W i32(int value) { return u32(value & 0xFFFFFFFFL); }
        W u64(long value) {
            if (value < 0) throw fail();
            for (int i = 0; i < 8; i++) out.write((int) (value >>> (8 * i)) & 0xFF);
            return this;
        }
        W i64(long value) { return u64(value); }
        /** UUIDs are opaque 128-bit identities stored big-endian (msb then lsb). */
        W uuid(UUID id) {
            long msb = id.getMostSignificantBits(), lsb = id.getLeastSignificantBits();
            for (int i = 0; i < 8; i++) out.write((int) (msb >>> (56 - 8 * i)) & 0xFF);
            for (int i = 0; i < 8; i++) out.write((int) (lsb >>> (56 - 8 * i)) & 0xFF);
            return this;
        }
        W uuidOrZero(UUID id) { return uuid(id == null ? new UUID(0, 0) : id); }
        W utf8(String text) {
            if (text == null) throw fail();
            var bytes = text.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > ConstructionTransaction.MAX_VALUE_BYTES) throw fail();
            return u32(bytes.length).raw(bytes);
        }
        W utf8OrNull(String text, int maxBytes) {
            if (text == null) { u32(0xFFFFFFFFL); return this; }
            var bytes = text.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > maxBytes) throw fail();
            return u32(bytes.length).raw(bytes);
        }
        W hash(String hex) { return raw(HexFormat.of().parseHex(hex)); }
        W ref(FiniteBlobRef ref) { return hash(ref.sha256()).u64(ref.length()); }
        byte[] bytes() { return out.toByteArray(); }
    }

    /** Little-endian reader that fails closed on truncation, junk or overflow. */
    static final class R {
        private final ByteBuffer b;
        R(byte[] data) { b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); }
        private void need(int n) { if (n < 0 || b.remaining() < n) throw fail(); }
        int u8() { need(1); return b.get() & 0xFF; }
        long u32() { need(4); return b.getInt() & 0xFFFFFFFFL; }
        int i32() { need(4); return b.getInt(); }
        long u64() { need(8); long v = b.getLong(); if (v < 0) throw fail(); return v; }
        long i64() { return u64(); }
        byte[] raw(int n) { need(n); var out = new byte[n]; b.get(out); return out; }
        UUID uuid() {
            need(16);
            long msb = 0, lsb = 0;
            for (int i = 0; i < 8; i++) msb = (msb << 8) | u8();
            for (int i = 0; i < 8; i++) lsb = (lsb << 8) | u8();
            return new UUID(msb, lsb);
        }
        UUID uuidOrZero() { var id = uuid(); return id.equals(new UUID(0, 0)) ? null : id; }
        String utf8(int maxBytes) {
            String value = utf8OrNull(maxBytes);
            if (value == null) throw fail();
            return value;
        }
        String utf8OrNull(int maxBytes) {
            long length = u32();
            if (length == 0xFFFFFFFFL) return null;
            if (length > maxBytes) throw fail();
            return new String(raw((int) length), StandardCharsets.UTF_8);
        }
        String hash() { return HexFormat.of().formatHex(raw(32)); }
        FiniteBlobRef ref() { return new FiniteBlobRef(hash(), i64()); }
        void done() { if (b.hasRemaining()) throw fail(); }
    }
}
