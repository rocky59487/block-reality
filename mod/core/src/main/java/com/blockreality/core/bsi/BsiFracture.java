package com.blockreality.core.bsi;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.json.JsonValue;
import com.blockreality.core.json.JsonWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Identified physical fracture wire. No Java mechanics or durable native tokens. */
public final class BsiFracture {
    public static final int MAX_PAYLOAD = (256 << 20) - 4096 - 12;
    static final Comparator<BlockKey> ORDER = Comparator.comparingInt(BlockKey::x)
            .thenComparingInt(BlockKey::y).thenComparingInt(BlockKey::z);
    private BsiFracture() { }

    public record Stamp(UUID domain, long revision) {
        public Stamp { nonzero(domain); if (revision < 0) throw invalid("negative revision"); }
    }
    public record Owner(BlockKey position, long artifact) {
        public Owner { Objects.requireNonNull(position); if (artifact <= 0) throw invalid("invalid artifact"); }
    }
    public record Token(UUID context, String sequence) {
        public Token {
            nonzero(context);
            if (sequence == null || !sequence.matches("[0-9a-f]{16}") || sequence.equals("0".repeat(16)))
                throw invalid("invalid sequence");
        }
    }
    public record Options(double gx, double gy, double gz, int budget, Integer numThreads) {
        public Options {
            if (!Double.isFinite(gx) || !Double.isFinite(gy) || !Double.isFinite(gz)
                    || budget < 1 || budget > 4096 || numThreads != null && (numThreads < 0 || numThreads > 256))
                throw invalid("invalid options");
        }
    }
    public record World(Stamp stamp, UUID artifactNamespace, List<BsiRecords.Block> blocks, List<Owner> owners) {
        public World {
            Objects.requireNonNull(stamp); nonzero(artifactNamespace);
            blocks = List.copyOf(BsiRecords.canonical(blocks));
            var sorted = new ArrayList<>(owners); sorted.sort(Comparator.comparing(Owner::position, ORDER));
            var positions = new HashSet<BlockKey>(); for (var b : blocks) positions.add(key(b));
            BlockKey previous = null;
            for (Owner o : sorted) {
                if (o.position().equals(previous) || !positions.contains(o.position())) throw invalid("foreign or repeated owner");
                previous = o.position();
            }
            owners = List.copyOf(sorted);
            if ((long) blocks.size() * 40 + (long) owners.size() * 20 > MAX_PAYLOAD) throw invalid("world exceeds frame capacity");
        }
        public byte[] payload() {
            var out = ByteBuffer.allocate(Math.addExact(Math.multiplyExact(blocks.size(),40),Math.multiplyExact(owners.size(),20))).order(ByteOrder.LITTLE_ENDIAN);
            for (var b : blocks) b.write(out);
            for (Owner owner : owners) out.putInt(owner.position().x()).putInt(owner.position().y()).putInt(owner.position().z()).putLong(owner.artifact());
            return out.array();
        }
        public World remaining(BsiFractureReceipt receipt) {
            if (!receipt.matchesSource(this)) throw invalid("foreign receipt source");
            var removed = new HashSet<BlockKey>();
            for (var cell : receipt.cells()) if (cell.group() != 0) removed.add(key(cell.source()));
            return new World(receipt.after(),artifactNamespace,
                    blocks.stream().filter(b -> !removed.contains(key(b))).toList(),
                    owners.stream().filter(o -> !removed.contains(o.position())).toList());
        }
        /** Binds the server's idempotency request to the exact source and options, without a token. */
        public String planHash(Options options) {
            try {
                var hash = MessageDigest.getInstance("SHA-256");
                var header = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN);
                header.putLong(stamp.domain().getMostSignificantBits()).putLong(stamp.domain().getLeastSignificantBits()).putLong(stamp.revision());
                header.putLong(artifactNamespace.getMostSignificantBits()).putLong(artifactNamespace.getLeastSignificantBits());
                header.putDouble(options.gx()).putDouble(options.gy()).putDouble(options.gz()).putInt(options.budget());
                header.putInt(options.numThreads() == null ? -1 : options.numThreads()).putInt(blocks.size()).putInt(owners.size());
                hash.update(header.array()); hash.update(payload()); return HexFormat.of().formatHex(hash.digest());
            } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
    }
    public enum Finish { COMMITTED, REPLAYED, DISCARDED }

    public static String declare(String id, World world) {
        var w = BsiHeaders.base(id,"bsi.world.declare",world.stamp().revision());
        w.key("body").beginObj().kv("blocks",world.blocks().size()).kv("attrs",0).key("identity").beginObj();
        w.kv("domain",hex(world.stamp().domain())).kv("revision",world.stamp().revision())
                .kv("artifactNamespace",hex(world.artifactNamespace())).kv("owners",world.owners().size());
        return w.endObj().endObj().endObj().done();
    }
    public static String prepare(String id, World world, UUID request, Options options) {
        nonzero(request);
        var w = BsiHeaders.base(id,"bsi.fracture.prepare",world.stamp().revision()).key("body").beginObj().key("expected");
        stamp(w,world.stamp()); w.kv("requestId",hex(request)).key("gravity").beginArr()
                .val(options.gx()).val(options.gy()).val(options.gz()).endArr().kv("budget",options.budget()).kv("tier","commit");
        if (options.numThreads() != null) w.kv("numThreads",options.numThreads());
        return w.endObj().endObj().done();
    }
    public static String finish(String id, BsiFractureReceipt receipt, boolean commit) {
        var w = BsiHeaders.base(id,"bsi.fracture.finish",receipt.before().revision()).key("body").beginObj().key("expected");
        stamp(w,receipt.before()); w.kv("requestId",hex(receipt.request())).key("token"); token(w,receipt.token());
        return w.kv("resultRevision",receipt.after().revision()).kv("action",commit?"commit":"discard").endObj().endObj().done();
    }
    static void stamp(JsonWriter w, Stamp value) { w.beginObj().kv("domain",hex(value.domain())).kv("revision",value.revision()).endObj(); }
    static void token(JsonWriter w, Token value) { w.beginObj().kv("context",hex(value.context())).kv("sequence",value.sequence()).endObj(); }
    public static String hex(UUID value) { return value.toString().replace("-",""); }
    static UUID uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{32}")) throw invalid("invalid UUID encoding");
        var b = ByteBuffer.wrap(HexFormat.of().parseHex(value)); var id = new UUID(b.getLong(),b.getLong()); nonzero(id); return id;
    }
    static Stamp readStamp(JsonValue value) { return new Stamp(uuid(value.str("domain","")),value.exactI64("revision")); }
    static Token readToken(JsonValue value) { return new Token(uuid(value.str("context","")),value.str("sequence","")); }
    public static void checkEnvelope(BsiResponse response, String id, String method, long revision) {
        if (response == null || !JsonValue.parseStrict(response.headerText()).isObject() || response.isError() || !response.id().equals(id) || response.revision() != revision
                || response.header().exactI64("bsi") != 1 || !response.header().str("kind","").equals("response")
                || !response.header().str("method","").equals(method)) throw invalid("response binding");
    }
    public static void checkWorld(BsiResponse response, String id, World world) {
        checkEnvelope(response,id,"bsi.world.declare",world.stamp().revision());
        var identity = response.header().objField("identity");
        if (!response.status().equals("ok") || response.payload().length != 0 || !readStamp(identity).equals(world.stamp())
                || !uuid(identity.str("artifactNamespace","")).equals(world.artifactNamespace())) throw invalid("world identity response");
    }
    public static Finish checkFinish(BsiResponse response, String id, BsiFractureReceipt receipt, Stamp current, boolean commit) {
        checkEnvelope(response,id,"bsi.fracture.finish",receipt.before().revision());
        var h = response.header(); var identity = h.objField("identity");
        if (response.payload().length != 0 || !uuid(h.str("requestId","")).equals(receipt.request())
                || !readToken(h.objField("token")).equals(receipt.token()) || !readStamp(identity).equals(current)
                || !uuid(identity.str("artifactNamespace","")).equals(receipt.artifactNamespace())) throw invalid("finish identity response");
        return switch (response.status()) {
            case "committed" -> { if (!commit) throw invalid("unexpected commit"); yield Finish.COMMITTED; }
            case "replayed" -> { if (!commit) throw invalid("unexpected replay"); yield Finish.REPLAYED; }
            case "discarded" -> { if (commit) throw invalid("unexpected discard"); yield Finish.DISCARDED; }
            default -> throw invalid("unknown finish status");
        };
    }
    public static BlockKey key(BsiRecords.Block block) { return new BlockKey(block.x(),block.y(),block.z()); }
    private static void nonzero(UUID value) { if (value == null || value.equals(new UUID(0,0))) throw invalid("zero identity"); }
    static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("BSI fracture: " + message); }
}
