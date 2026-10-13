package com.blockreality.core.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.BsiCorotFracture;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiRecords;
import com.blockreality.core.json.JsonValue;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.HashSet;

/**
 * The immutable complete finite source (design §3): the exact vocabulary request and the
 * engine's id-mapping reply, the canonical {@code blocks40} world, the complete
 * {@code owners20} artifact table and every input record group a {@code bsi.corot.solve}
 * would consume — all as the contract-fixed little-endian bytes, never re-derived.
 *
 * <p>Two construction rules carry the architecture, not mechanics:
 *
 * <ul>
 *   <li>Canonical order: blocks and owners are sorted by (x,y,z) before encoding, so the
 *       same world produces identical bytes no matter which order chunks reported cells.
 *   <li>Owner coverage (§3.2): every owner position must be a block, and every block whose
 *       material is <em>not</em> a declared support must carry exactly one owner. Support
 *       cells stay in the source — the engine still sees the ground — but they are never
 *       minted droppable owners.
 * </ul>
 *
 * <p>No stress, force, energy or verdict is computed here or anywhere in Java.
 */
public final class FiniteSource {

    public static final int MAX_VOCAB_BYTES = 1 << 20;
    public static final int MAX_BLOCKS = 4096; // same bound as the CT cell participant cap
    public static final int OPTIONS_BYTES = 176;
    public static final int MATERIAL_BYTES = 152;
    public static final int OWNER_BYTES = 20;
    public static final int SHELL_LAW_BYTES = 56;
    public static final int SHELL_LAYER_BYTES = 16;
    public static final int PRESCRIBED_BYTES = 64;
    public static final int GENERALIZED_LOAD_BYTES = 144;
    public static final int RULE_BYTES = 40;        // bsi_corot_failure_rule
    public static final int MAX_RULES = 4096;       // failure-rule declaration bound
    private static final byte[] EMPTY = new byte[0];

    private final byte[] vocabRequest, vocabReply;
    private final byte[] blocks40, owners20;
    private final byte[] materials, shellLaws, shellLayers, cellLoads, prescribed, loads, options;
    private final byte[] failureRules;
    private final int[] supportIds;
    private final int blockCount, ownerCount;
    /** The exact stored payload a decoded source retains so {@link #serialize()} is stable. */
    private final byte[] persisted;

    private FiniteSource(byte[] vocabRequest, byte[] vocabReply, byte[] blocks40, byte[] owners20,
                         byte[] materials, byte[] shellLaws, byte[] shellLayers,
                         byte[] cellLoads, byte[] prescribed, byte[] loads, byte[] options,
                         byte[] failureRules, int[] supportIds, byte[] persisted) {
        this.vocabRequest = vocabRequest.clone(); this.vocabReply = vocabReply.clone();
        this.blocks40 = blocks40.clone(); this.owners20 = owners20.clone();
        this.materials = materials.clone(); this.shellLaws = shellLaws.clone(); this.shellLayers = shellLayers.clone();
        this.cellLoads = cellLoads.clone(); this.prescribed = prescribed.clone();
        this.loads = loads.clone(); this.options = options.clone();
        this.failureRules = failureRules.clone();
        this.supportIds = supportIds.clone();
        this.persisted = persisted == null ? null : persisted.clone();
        this.blockCount = blocks40.length / BsiRecords.BLOCK_BYTES;
        this.ownerCount = owners20.length / OWNER_BYTES;
    }

    /**
     * Canonicalise and validate one complete source. {@code supportMaterialIds} are the
     * vocabulary material ids declared with a support role: they stay in {@code blocks} but
     * must not appear in {@code owners}. Every other block must.
     */
    public static FiniteSource build(String vocabRequestJson, String vocabReplyJson,
                                     List<BsiRecords.Block> blocks, List<BsiFracture.Owner> owners,
                                     Set<Integer> supportMaterialIds,
                                     byte[] materials, byte[] shellLaws, byte[] shellLayers,
                                     byte[] cellLoads, byte[] prescribed, byte[] loads, byte[] options) {
        return build(vocabRequestJson, vocabReplyJson, blocks, owners, supportMaterialIds,
                materials, shellLaws, shellLayers, cellLoads, prescribed, loads, options, null);
    }

    /** {@link #build} plus the durable {@code bsi_corot_failure_rule} section (40 B each). */
    public static FiniteSource build(String vocabRequestJson, String vocabReplyJson,
                                     List<BsiRecords.Block> blocks, List<BsiFracture.Owner> owners,
                                     Set<Integer> supportMaterialIds,
                                     byte[] materials, byte[] shellLaws, byte[] shellLayers,
                                     byte[] cellLoads, byte[] prescribed, byte[] loads, byte[] options,
                                     byte[] failureRules) {
        Objects.requireNonNull(supportMaterialIds);
        canonicalSupport(supportMaterialIds);
        byte[] request = vocab(vocabRequestJson, true), reply = vocab(vocabReplyJson, false);
        if (blocks == null || blocks.isEmpty() || blocks.size() > MAX_BLOCKS) throw invalid();
        var canonicalBlocks = BsiRecords.canonical(blocks);
        var sortedOwners = owners == null ? List.<BsiFracture.Owner>of() : canonicalOwners(owners);
        checkCoverage(canonicalBlocks, sortedOwners, supportMaterialIds);
        byte[] blocks40 = BsiRecords.encodeBlocks(canonicalBlocks);
        var out = ByteBuffer.allocate(sortedOwners.size() * OWNER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (var owner : sortedOwners)
            out.putInt(owner.position().x()).putInt(owner.position().y()).putInt(owner.position().z()).putLong(owner.artifact());
        byte[] owners20 = out.array();
        sections(materials, shellLaws, shellLayers, cellLoads, prescribed, loads, options, failureRules);
        return new FiniteSource(request, reply, blocks40, owners20, copy(materials), copy(shellLaws), copy(shellLayers),
                copy(cellLoads), copy(prescribed), copy(loads), copy(options), copy(failureRules),
                canonicalSupport(supportMaterialIds), null);
    }

    /** The persisted support-material id set, ascending. */
    public int[] supportMaterialIds() { return supportIds.clone(); }

    static int[] canonicalSupport(Set<Integer> ids) {
        Objects.requireNonNull(ids);
        var sorted = new TreeSet<Integer>();
        for (Integer id : ids) {
            if (id == null || id < 0) throw invalid();
            if (!sorted.add(id)) throw invalid();
        }
        var out = new int[sorted.size()];
        int i = 0; for (int id : sorted) out[i++] = id;
        return out;
    }

    static Set<Integer> supportSet(int[] ids) {
        var set = new HashSet<Integer>();
        int previous = -1;
        for (int id : ids) {
            if (id < 0 || id <= previous) throw invalid();
            previous = id;
            set.add(id);
        }
        return Set.copyOf(set);
    }

    /** Rebuild from the canonical serialized payload; every section is re-validated. */
    public static FiniteSource decode(byte[] payload) { return FiniteCodec.decodeSource(payload); }

    static FiniteSource of(byte[] vocabRequest, byte[] vocabReply, byte[] blocks40, byte[] owners20,
                           byte[] materials, byte[] shellLaws, byte[] shellLayers,
                           byte[] cellLoads, byte[] prescribed, byte[] loads, byte[] options,
                           int[] supportIds) {
        return of(vocabRequest, vocabReply, blocks40, owners20, materials, shellLaws, shellLayers,
                cellLoads, prescribed, loads, options, new byte[0], supportIds, null);
    }

    /** {@link #of} plus the decoded failure-rules section and the retained original payload. */
    static FiniteSource of(byte[] vocabRequest, byte[] vocabReply, byte[] blocks40, byte[] owners20,
                           byte[] materials, byte[] shellLaws, byte[] shellLayers,
                           byte[] cellLoads, byte[] prescribed, byte[] loads, byte[] options,
                           byte[] failureRules, int[] supportIds, byte[] persisted) {
        var blocks = decodeBlocks(blocks40);
        var owners = decodeOwners(owners20);
        var support = supportSet(supportIds);
        checkCoverage(blocks, owners, support);
        sections(materials, shellLaws, shellLayers, cellLoads, prescribed, loads, options, failureRules);
        return new FiniteSource(vocabRequest, vocabReply, blocks40, owners20, materials,
                shellLaws, shellLayers, cellLoads, prescribed, loads, options, failureRules,
                supportIds, persisted);
    }

    static List<BsiRecords.Block> decodeBlocks(byte[] blocks40) {
        var data = ByteBuffer.wrap(blocks40).order(ByteOrder.LITTLE_ENDIAN);
        var out = new ArrayList<BsiRecords.Block>(blocks40.length / BsiRecords.BLOCK_BYTES);
        BlockKey previous = null;
        while (data.hasRemaining()) {
            int x = data.getInt(), y = data.getInt(), z = data.getInt(), mat = data.getInt(), sect = data.getInt();
            int axis = data.get() & 0xFF, joint = data.get() & 0xFF, axisRot = data.get() & 0xFF, attr = data.get() & 0xFF;
            double fill = data.getDouble(), strength = data.getDouble();
            if (attr != 0) throw invalid(); // Identified finite worlds never carry per-block attrs.
            var block = new BsiRecords.Block(x, y, z, mat, sect, axis, joint, axisRot, fill, strength);
            var key = new BlockKey(x, y, z);
            if (previous != null && ArtifactBinding.CELL_ORDER.compare(previous, key) >= 0) throw invalid();
            previous = key;
            out.add(block);
        }
        return List.copyOf(out);
    }

    static List<BsiFracture.Owner> decodeOwners(byte[] owners20) {
        var data = ByteBuffer.wrap(owners20).order(ByteOrder.LITTLE_ENDIAN);
        var out = new ArrayList<BsiFracture.Owner>(owners20.length / OWNER_BYTES);
        BlockKey previous = null;
        while (data.hasRemaining()) {
            var key = new BlockKey(data.getInt(), data.getInt(), data.getInt());
            long artifact = data.getLong();
            if (previous != null && ArtifactBinding.CELL_ORDER.compare(previous, key) >= 0) throw invalid();
            previous = key;
            out.add(new BsiFracture.Owner(key, artifact));
        }
        return List.copyOf(out);
    }

    private static List<BsiFracture.Owner> canonicalOwners(List<BsiFracture.Owner> owners) {
        var sorted = new ArrayList<>(owners);
        sorted.sort((a, b) -> ArtifactBinding.CELL_ORDER.compare(a.position(), b.position()));
        for (int i = 1; i < sorted.size(); i++)
            if (sorted.get(i - 1).position().equals(sorted.get(i).position())) throw invalid();
        return List.copyOf(sorted);
    }

    private static void checkCoverage(List<BsiRecords.Block> blocks, List<BsiFracture.Owner> owners, Set<Integer> supportMaterialIds) {
        var positions = new java.util.HashSet<BlockKey>();
        var structural = new java.util.HashSet<BlockKey>();
        for (var block : blocks) {
            var key = new BlockKey(block.x(), block.y(), block.z());
            positions.add(key);
            if (!supportMaterialIds.contains(block.mat())) structural.add(key);
        }
        var owned = new java.util.HashSet<BlockKey>();
        for (var owner : owners) {
            if (!positions.contains(owner.position()) || !owned.add(owner.position())) throw invalid();
        }
        if (!owned.equals(structural)) throw invalid(); // 漏 ground 或把支承誤作可掉落物
    }

    static void sections(byte[] materials, byte[] shellLaws, byte[] shellLayers,
                         byte[] cellLoads, byte[] prescribed, byte[] loads, byte[] options) {
        sections(materials, shellLaws, shellLayers, cellLoads, prescribed, loads, options, null);
    }

    static void sections(byte[] materials, byte[] shellLaws, byte[] shellLayers,
                         byte[] cellLoads, byte[] prescribed, byte[] loads, byte[] options,
                         byte[] failureRules) {
        stride(materials, MATERIAL_BYTES, 1, (1 << 24));
        stride(shellLaws, SHELL_LAW_BYTES, 0, (1 << 24));
        stride(shellLayers, SHELL_LAYER_BYTES, 0, (1 << 24));
        // ABI10: shell material laws and their layers arrive together or not at all.
        if ((shellLaws == null || shellLaws.length == 0) != (shellLayers == null || shellLayers.length == 0)) throw invalid();
        stride(cellLoads, BsiRecords.LOAD_BYTES, 0, (1 << 24));
        stride(prescribed, PRESCRIBED_BYTES, 0, (1 << 24));
        stride(loads, GENERALIZED_LOAD_BYTES, 0, (1 << 24));
        stride(failureRules, RULE_BYTES, 0, MAX_RULES);
        if (options == null || options.length != OPTIONS_BYTES) throw invalid();
        var buffer = ByteBuffer.wrap(options).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt(0) != OPTIONS_BYTES || buffer.getInt(172) != 0) throw invalid();
        // corot_world_builder.h: materials (primary or shell laws), any prescribed record
        // and the transferTopology flag each require the retained load path — the engine
        // answers PROTOCOL_ERROR otherwise, so refuse the source before it reaches the wire.
        int flags = buffer.getInt(4);
        boolean hasMaterials = materials.length > 0 || shellLaws.length > 0;
        if ((flags & 4) == 0 && (hasMaterials || prescribed.length > 0 || (flags & 8) != 0))
            throw invalid();
    }

    private static void stride(byte[] section, int stride, int min, int max) {
        if (section == null) { if (min > 0) throw invalid(); return; }
        if (section.length < (long) min * stride || section.length > (long) max * stride
                || section.length % stride != 0) throw invalid();
    }

    private static byte[] vocab(String json, boolean request) {
        if (json == null || json.isBlank() || json.length() > MAX_VOCAB_BYTES) throw invalid();
        JsonValue value = JsonValue.parseStrict(json);
        if (value == null || !value.isObject() || !value.isExactInt("version") || value.exactI64("version") < 1)
            throw invalid();
        if (!request && (!"bsi.vocab.declare".equals(value.str("method", ""))
                || !"ok".equals(value.str("status", "")) || !value.isArr("materials"))) throw invalid();
        return json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] copy(byte[] bytes) { return bytes == null ? new byte[0] : bytes.clone(); }

    public byte[] vocabRequest() { return vocabRequest.clone(); }
    public byte[] vocabReply() { return vocabReply.clone(); }
    public String vocabRequestJson() { return new String(vocabRequest, java.nio.charset.StandardCharsets.UTF_8); }
    public String vocabReplyJson() { return new String(vocabReply, java.nio.charset.StandardCharsets.UTF_8); }
    public byte[] blocks40() { return blocks40.clone(); }
    public byte[] owners20() { return owners20.clone(); }
    public byte[] materials() { return materials.clone(); }
    public byte[] shellLaws() { return shellLaws.clone(); }
    public byte[] shellLayers() { return shellLayers.clone(); }
    public byte[] cellLoads() { return cellLoads.clone(); }
    public byte[] prescribed() { return prescribed.clone(); }
    public byte[] loads() { return loads.clone(); }
    public byte[] options() { return options.clone(); }
    /** The durable {@code bsi_corot_failure_rule} section applied by the last committed fracture. */
    public byte[] failureRules() { return failureRules.clone(); }
    public int blockCount() { return blockCount; }
    public int ownerCount() { return ownerCount; }
    public boolean hasShellMaterials() { return shellLaws.length != 0; }
    public List<BsiRecords.Block> blocks() { return decodeBlocks(blocks40); }
    public List<BsiFracture.Owner> owners() { return decodeOwners(owners20); }

    /**
     * Every declared material's bsi_role ordinal, as material id → {@code nonstructural=0,
     * member=1, panel=2, support=3, monolith=4}. Derived, never persisted separately: the
     * vocabulary request's {@code materials[].role} names plus the reply's id→name table
     * already carry the binding, so the map is rebuilt by identity on every access.
     */
    public java.util.Map<Integer, Integer> materialRoles() {
        var reply = JsonValue.parseStrict(vocabReplyJson());
        var request = JsonValue.parseStrict(vocabRequestJson());
        var names = new java.util.HashMap<String, Integer>();
        for (JsonValue row : reply.arr("materials")) names.put(row.str("name", ""), (int) row.exactI64("id"));
        var roles = java.util.Map.of("nonstructural", 0, "member", 1, "panel", 2, "support", 3, "monolith", 4);
        var out = new java.util.HashMap<Integer, Integer>();
        for (JsonValue material : request.arr("materials")) {
            var name = material.str("name", "");
            Integer id = names.get(name), role = roles.get(material.str("role", ""));
            if (id == null || role == null) throw invalid();   // reply/request drift
            out.put(id, role);
        }
        return Map.copyOf(out);
    }

    /**
     * The vocabulary's material id → default section id map — the {@code defaultSections}
     * view every corot world validation needs. Rebuilt from the persisted vocabulary
     * request on every access; a section or material the vocabulary does not know is a
     * hard error, never a guess.
     */
    public java.util.Map<Integer, Integer> defaultSections(com.blockreality.core.bsi.BsiVocabulary vocabulary) {
        Objects.requireNonNull(vocabulary);
        var out = new java.util.HashMap<Integer, Integer>();
        for (JsonValue material : JsonValue.parseStrict(vocabRequestJson()).arr("materials")) {
            var name = material.str("name", "");
            var section = material.str("defaultSection", "");
            if (name.isEmpty() || section.isEmpty()) continue;
            try {
                out.put(vocabulary.materialId(name), vocabulary.sectionId(section));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("vocabulary default section drift", unknown);
            }
        }
        return Map.copyOf(out);
    }

    /**
     * The {@code bsi.vocab.declare} vocabulary this source's bytes encode, decoded offline
     * for validators that need it (receipt re-verification, rule material lookup).
     */
    public com.blockreality.core.bsi.BsiVocabulary vocabulary() {
        int version = (int) JsonValue.parseStrict(vocabRequestJson()).exactI64("version");
        return com.blockreality.core.bsi.BsiVocabulary.decodeStored(
                JsonValue.parseStrict(vocabReplyJson()), version);
    }

    /** The exact {@code bsi.world.declare} payload: blocks40 then owners20. */
    public byte[] worldPayload() {
        var out = new byte[blocks40.length + owners20.length];
        System.arraycopy(blocks40, 0, out, 0, blocks40.length);
        System.arraycopy(owners20, 0, out, blocks40.length, owners20.length);
        return out;
    }

    /** The exact ordered {@code bsi.corot.solve} input payload (ABI10 tail included when used). */
    public byte[] corotPayload() {
        var out = new byte[materials.length + cellLoads.length + prescribed.length + loads.length
                + shellLaws.length + shellLayers.length];
        int p = 0;
        for (byte[] section : List.of(materials, cellLoads, prescribed, loads, shellLaws, shellLayers)) {
            System.arraycopy(section, 0, out, p, section.length); p += section.length;
        }
        return out;
    }

    /**
     * The exact ordered {@code bsi.corot.fracture.prepare} input payload:
     * {@code materials ‖ cellLoads ‖ prescribed ‖ loads ‖ rules ‖ shellLaws ‖ shellLayers}
     * — the rules group is the request's own encoded rules (its bytes, not persisted state).
     */
    public byte[] fracturePayload(byte[] rules) {
        stride(rules, RULE_BYTES, 0, MAX_RULES);
        var out = new byte[materials.length + cellLoads.length + prescribed.length + loads.length
                + rules.length + shellLaws.length + shellLayers.length];
        int p = 0;
        for (byte[] section : List.of(materials, cellLoads, prescribed, loads, rules, shellLaws, shellLayers)) {
            System.arraycopy(section, 0, out, p, section.length); p += section.length;
        }
        return out;
    }

    /**
     * The after source of a committed fracture: the receipt's remaining world and owners,
     * the same effective input sections, the receipt's after-stamped options record, and the
     * request's rules persisted as the durable {@code failureRules} section. No receipt
     * content is re-derived; the receipt validator already byte-matched every retained cell.
     */
    public static FiniteSource afterFracture(FiniteSource source,
                                             BsiCorotFracture.Prepared prepared, byte[] rules) {
        Objects.requireNonNull(source); Objects.requireNonNull(prepared);
        stride(rules, RULE_BYTES, 0, MAX_RULES);
        var blocks40 = BsiRecords.encodeBlocks(prepared.remaining());
        var out = ByteBuffer.allocate(prepared.remainingOwners().size() * OWNER_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (var owner : prepared.remainingOwners())
            out.putInt(owner.position().x()).putInt(owner.position().y()).putInt(owner.position().z())
                    .putLong(owner.artifact());
        // Cell loads target world positions: a load whose cell left the remaining body is a
        // foreign force source to the next analysis — retire it with its cell.
        var surviving = new java.util.HashSet<BlockKey>(prepared.remaining().size() * 2);
        for (var cell : prepared.remaining()) surviving.add(new BlockKey(cell.x(), cell.y(), cell.z()));
        var loadView = ByteBuffer.wrap(source.cellLoads).order(ByteOrder.LITTLE_ENDIAN);
        var kept = ByteBuffer.allocate(source.cellLoads.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int at = 0; at + BsiRecords.LOAD_BYTES <= source.cellLoads.length; at += BsiRecords.LOAD_BYTES)
            if (surviving.contains(new BlockKey(loadView.getInt(at), loadView.getInt(at + 4),
                    loadView.getInt(at + 8))))
                kept.put(source.cellLoads, at, BsiRecords.LOAD_BYTES);
        var cellLoads = new byte[kept.position()];
        kept.flip(); kept.get(cellLoads);
        // Prescribed records address node indices and generalized loads address node/member
        // indices — both renumber when the body changes. They belong to the solve that
        // produced them, not to the surviving structure.
        return of(source.vocabRequest, source.vocabReply, blocks40, out.array(), source.materials,
                source.shellLaws, source.shellLayers, cellLoads, EMPTY,
                EMPTY, prepared.afterOptions(), rules, source.supportIds, null);
    }

    /**
     * The canonical serialized form stored under {@code objects/}; {@link #sourceHash} hashes it.
     * A decoded source retains its original payload bytes (pre-13-section objects keep their
     * 12-section encoding — hashes already committed are never rewritten); a source built in
     * memory always serializes the current 13-section schema.
     */
    public byte[] serialize() {
        return persisted != null ? persisted.clone() : FiniteCodec.encodeSource(this);
    }

    public String sourceHash() { return sha256(serialize()); }
    public String vocabHash() {
        var digest = digest();
        digest.update(vocabRequest); digest.update((byte) 0); digest.update(vocabReply);
        return HexFormat.of().formatHex(digest.digest());
    }
    public String ownersHash() { return sha256(owners20); }

    static String sha256(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof FiniteSource that)) return false;
        return Arrays.equals(vocabRequest, that.vocabRequest) && Arrays.equals(vocabReply, that.vocabReply)
                && Arrays.equals(blocks40, that.blocks40) && Arrays.equals(owners20, that.owners20)
                && Arrays.equals(materials, that.materials) && Arrays.equals(shellLaws, that.shellLaws)
                && Arrays.equals(shellLayers, that.shellLayers) && Arrays.equals(cellLoads, that.cellLoads)
                && Arrays.equals(prescribed, that.prescribed) && Arrays.equals(loads, that.loads)
                && Arrays.equals(options, that.options) && Arrays.equals(failureRules, that.failureRules)
                && Arrays.equals(supportIds, that.supportIds);
    }
    /** Field-tuple hash — never {@link #sourceHash()}, whose bytes differ between 12/13-section encodings of equal content. */
    @Override public int hashCode() {
        return Objects.hash(Arrays.hashCode(vocabRequest), Arrays.hashCode(vocabReply),
                Arrays.hashCode(blocks40), Arrays.hashCode(owners20), Arrays.hashCode(materials),
                Arrays.hashCode(shellLaws), Arrays.hashCode(shellLayers), Arrays.hashCode(cellLoads),
                Arrays.hashCode(prescribed), Arrays.hashCode(loads), Arrays.hashCode(options),
                Arrays.hashCode(failureRules), Arrays.hashCode(supportIds));
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid finite source"); }
}

