package com.blockreality.core.bsi;

import com.blockreality.core.json.JsonValue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A decoded BSI reply: the header fields the consumer acts on, and typed views over the payload
 * sections.
 *
 * <p>The sections are read where they lie. {@code sections[]} gives every one its offset, byte
 * count and record count, so a view is a slice and an index — no copying, and a section this
 * build does not know about is simply not looked at rather than being a parse failure.
 *
 * <p>Nothing here recomputes mechanics. Values arrive decided (BSI P2), and the one comparison
 * this file makes — {@code dc > 1} — is not made: the engine's flag is read instead (N19).
 */
public final class BsiResponse {

    /** One payload section as the reply describes it. */
    public record Section(String name, int offset, int bytes, int count) {}

    /** Per-cell verdict, aligned to canonical block order. */
    public record BlockResult(double dc, int island, int owner, int mode, int ownerKind, int flags, long reason) {
        public boolean overloaded() { return (flags & 1) != 0; }
        public boolean indicative() { return (flags & 2) != 0; }
        public boolean unassigned() { return ownerKind == 3; }
    }

    /** Totals of the solve. {@code residual} is the engine's own equilibrium check. */
    public record Equilibrium(double[] applied, double[] reaction, double residual) {}

    public record Quality(double achievedRel, int iterations, boolean tierHonoured, boolean warmStartUsed,
                          int storage, boolean timedOut) {}

    public record Buckling(int island, int state, int kind, double factor) {}

    /** One member. End forces are section forces in local axes, tension positive. */
    public record Member(int id, int island, int blockFirst, int blockCount, int stationFirst, int stationCount,
                         int material, int section, double lengthM, double[] endI, double[] endJ,
                         double maxDC, double governingS, int mode, int governingFibre, int flags) {
        public boolean overloaded() { return (flags & 1) != 0; }
    }

    /** A cell listed as not modelled, with the reason the engine gave. */
    public record Unassigned(String why, int island, List<int[]> blocks) {}

    private final String headerText;
    private final JsonValue header;
    private final byte[] payload;
    private final Map<String, Section> sections = new LinkedHashMap<>();

    private BsiResponse(String headerText, JsonValue header, byte[] payload) {
        this.headerText = headerText;
        this.header = header;
        this.payload = payload;
        if (header.isArr("sections")) {
            for (JsonValue s : header.arr("sections")) {
                Section sec = new Section(s.str("name", ""), s.i32("offset", -1), s.i32("bytes", -1), s.i32("count", -1));
                if (sec.offset() >= 0 && sec.bytes() >= 0 && sec.offset() + sec.bytes() <= payload.length) {
                    sections.put(sec.name(), sec);
                }
            }
        }
    }

    /** Parse one frame's header and payload. Returns null when the header is not a JSON object. */
    public static BsiResponse of(BsiFrame.Decoded frame) {
        if (frame == null) return null;
        JsonValue h = JsonValue.parse(frame.header());
        if (h == null || !h.isObject()) return null;
        return new BsiResponse(frame.header(), h, frame.payload());
    }

    public String headerText() { return headerText; }
    public JsonValue header() { return header; }
    public byte[] payload() { return payload; }
    public boolean isError() { return "error".equals(header.str("kind", "")); }
    /** The contract's error token, or empty for a response. Consumers branch on this, never on the message. */
    public String code() { return header.str("code", ""); }
    public String message() { return header.str("message", ""); }
    public String status() { return header.str("status", ""); }
    public String id() { return header.str("id", ""); }
    public long revision() { return header.exactI64("revision"); }
    public Map<String, Section> sections() { return sections; }

    private ByteBuffer view(String name) {
        Section s = sections.get(name);
        if (s == null) return null;
        return ByteBuffer.wrap(payload, s.offset(), s.bytes()).order(ByteOrder.LITTLE_ENDIAN).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    private Section either(String base) {
        Section s = sections.get(base);
        return s != null ? s : sections.get(base + ":f32");
    }

    public List<BlockResult> blocks() {
        List<BlockResult> out = new ArrayList<>();
        ByteBuffer b = view("blocks");
        Section s = sections.get("blocks");
        if (b == null) return out;
        for (int k = 0; k < s.count(); k++) {
            int base = k * BsiRecords.BLOCK_RESULT_BYTES;
            out.add(new BlockResult(b.getDouble(base), b.getInt(base + 8), b.getInt(base + 12),
                    b.get(base + 16) & 0xFF, b.get(base + 17) & 0xFF, b.get(base + 18) & 0xFF,
                    b.getInt(base + 20) & 0xFFFFFFFFL));
        }
        return out;
    }

    public Equilibrium equilibrium() {
        ByteBuffer b = view("equilibrium");
        if (b == null) return null;
        double[] a = {b.getDouble(0), b.getDouble(8), b.getDouble(16)};
        double[] r = {b.getDouble(24), b.getDouble(32), b.getDouble(40)};
        return new Equilibrium(a, r, b.getDouble(48));
    }

    public Quality quality() {
        ByteBuffer b = view("quality");
        if (b == null) return null;
        return new Quality(b.getDouble(0), b.getInt(8), b.get(12) != 0, b.get(13) != 0, b.get(14) & 0xFF, b.get(15) != 0);
    }

    public List<Buckling> buckling() {
        List<Buckling> out = new ArrayList<>();
        ByteBuffer b = view("buckling");
        Section s = sections.get("buckling");
        if (b == null) return out;
        for (int k = 0; k < s.count(); k++) {
            int base = k * BsiRecords.BUCKLING_BYTES;
            out.add(new Buckling(b.getInt(base), b.get(base + 4) & 0xFF, b.get(base + 5) & 0xFF, b.getDouble(base + 8)));
        }
        return out;
    }

    public List<Member> members() {
        List<Member> out = new ArrayList<>();
        ByteBuffer b = view("members");
        Section s = sections.get("members");
        if (b == null) return out;
        for (int k = 0; k < s.count(); k++) {
            int o = k * BsiRecords.MEMBER_BYTES;
            double[] ei = new double[6], ej = new double[6];
            for (int c = 0; c < 6; c++) ei[c] = b.getDouble(o + 40 + c * 8);
            for (int c = 0; c < 6; c++) ej[c] = b.getDouble(o + 88 + c * 8);
            out.add(new Member(b.getInt(o), b.getInt(o + 4), b.getInt(o + 8), b.getInt(o + 12),
                    b.getInt(o + 16), b.getInt(o + 20), b.getInt(o + 24), b.getInt(o + 28),
                    b.getDouble(o + 32), ei, ej, b.getDouble(o + 136), b.getDouble(o + 144),
                    b.get(o + 152) & 0xFF, b.get(o + 153) & 0xFF, b.get(o + 154) & 0xFF));
        }
        return out;
    }

    /** World coordinates of the cells a member covers, indexed by {@code blockFirst}/{@code blockCount}. */
    public List<int[]> memberBlocks() {
        List<int[]> out = new ArrayList<>();
        ByteBuffer b = view("memberBlocks");
        Section s = sections.get("memberBlocks");
        if (b == null) return out;
        for (int k = 0; k < s.count(); k++) {
            int o = k * BsiRecords.MEMBER_BLOCK_BYTES;
            out.add(new int[]{b.getInt(o), b.getInt(o + 4), b.getInt(o + 8)});
        }
        return out;
    }

    /**
     * Stations of the display track, as f64 or f32 depending on what was asked for. The f32 variant
     * is the same eleven fields at half the width (contract Part G item 7), so one reader serves both.
     */
    public double[][] stations() {
        Section s = either("stations");
        if (s == null) return new double[0][];
        boolean f32 = s.name().endsWith(":f32");
        ByteBuffer b = view(s.name());
        int rec = f32 ? BsiRecords.STATION_F32_BYTES : BsiRecords.STATION_BYTES;
        double[][] out = new double[s.count()][11];
        for (int k = 0; k < s.count(); k++) {
            int o = k * rec;
            for (int f = 0; f < 11; f++) {
                out[k][f] = f32 ? b.getFloat(o + f * 4) : b.getDouble(o + f * 8);
            }
        }
        return out;
    }

    public List<Unassigned> unassigned() {
        List<Unassigned> out = new ArrayList<>();
        if (!header.isArr("unassigned")) return out;
        for (JsonValue u : header.arr("unassigned")) {
            List<int[]> cells = new ArrayList<>();
            for (JsonValue c : u.arr("blocks")) {
                List<JsonValue> xyz = c.asArr();
                if (xyz.size() == 3) cells.add(new int[]{(int) xyz.get(0).asNum(0), (int) xyz.get(1).asNum(0), (int) xyz.get(2).asNum(0)});
            }
            out.add(new Unassigned(u.str("why", "UNKNOWN"), u.i32("island", -1), cells));
        }
        return out;
    }

    /** {@code diag} counts, or -1 when the field is absent. */
    public int diag(String field) {
        JsonValue d = header.objField("diag");
        return d.isObject() ? d.i32(field, -1) : -1;
    }
}
