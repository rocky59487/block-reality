package com.blockreality.impl.net;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.EndForces;
import com.blockreality.api.GoverningFibre;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ShellFieldSpec;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.StressFieldSpec;
import com.blockreality.api.StressStation;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.Vec3d;
import com.blockreality.impl.BlockRealityMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The drawable half of an analysis, sent to the client.
 *
 * <p>Only what the overlay needs travels: geometry, signed stress and D/C. End forces,
 * block lists and diagnostics stay on the server, where the decisions are made.
 *
 * <h2>Decoding never throws — and never launders</h2>
 * Two rules, and they answer different attacks:
 *
 * <ul>
 *   <li><strong>Never throws.</strong> Forge kicks a player whose packet decoder throws,
 *       so every read — including the netty {@code IndexOutOfBoundsException} a truncated
 *       buffer raises — is caught here and turned into an <em>invalid</em> packet that
 *       the handler drops (#39).
 *   <li><strong>Never launders.</strong> The old decoder washed NaN into 0.0, unknown
 *       enum ordinals into NONE and hostile counts into clamped ones, which turned a
 *       corrupt frame into a confident healthy-looking overlay (#40/#50). Now any
 *       off-schema value rejects the WHOLE packet: the client keeps its previous good
 *       state and logs why, which is the same fail-closed posture the engine wire has.
 * </ul>
 *
 * <h2>Safety classification travels, it is not recomputed</h2>
 * Whether a member is over capacity, and whether the structure is at its buckling load,
 * are decided on the server in double precision and carried as flags. A client comparing
 * float32-degraded numbers against 1.0 would flip the verdict for values within a ulp of
 * the boundary — the display track showing a different judgement from the commit track,
 * which invariants 5/6 forbid (#55). The decoded numeric values are nudged into the half
 * the server ruled for, so downstream code that reads {@code dc()} stays consistent with
 * the flag by construction.
 */
public final class StressResultPacket {

    /** Above this many members the rest are dropped — and the drop is logged, never silent. */
    private static final int MAX_MEMBERS = 64;
    /** Blocks per member. A member longer than this is drawn short rather than dropped. */
    private static final int MAX_BLOCKS = 256;
    /** Facets sent. A floor meshes into one facet per 2x2 block square, so this fills up
     *  far faster than members do — and, like members, the drop is logged. */
    private static final int MAX_SHELLS = 512;

    /** Stations the client regenerates for the picker and the section diagram. */
    private static final int STATIONS = 11;

    private final boolean valid;
    private final String invalidReason;

    private final long revision;
    /** Which dimension this result describes; the client drops a mismatch (#41). */
    private final String dimension;
    private final boolean singular;
    private final double maxDc;
    /** Server-side double verdict of {@code maxDc > 1}; see the class javadoc. */
    private final boolean overCapacity;
    private final int islands;
    private final int singularIslands;
    private final double bucklingFactor;
    /** Server-side double verdict of {@code 0 < bucklingFactor <= 1}. */
    private final boolean bucklingCritical;
    /** Solved totals BEFORE truncation, so the HUD can say what it is not showing (#42). */
    private final int totalMembers;
    private final int totalShells;
    private final List<MemberSnapshot> members;
    private final List<ShellSnapshot> shells;

    private StressResultPacket(boolean valid, String invalidReason,
                               long revision, String dimension, boolean singular,
                               double maxDc, boolean overCapacity,
                               int islands, int singularIslands,
                               double bucklingFactor, boolean bucklingCritical,
                               int totalMembers, int totalShells,
                               List<MemberSnapshot> members, List<ShellSnapshot> shells) {
        this.valid = valid;
        this.invalidReason = invalidReason;
        this.revision = revision;
        this.dimension = dimension;
        this.singular = singular;
        this.maxDc = maxDc;
        this.overCapacity = overCapacity;
        this.islands = islands;
        this.singularIslands = singularIslands;
        this.bucklingFactor = bucklingFactor;
        this.bucklingCritical = bucklingCritical;
        this.totalMembers = totalMembers;
        this.totalShells = totalShells;
        this.members = members;
        this.shells = shells;
    }

    private static StressResultPacket invalid(String reason) {
        return new StressResultPacket(false, reason, 0, "", false, 0, false, 0, 0, 0, false,
                0, 0, List.of(), List.of());
    }

    public static StressResultPacket of(AnalysisResult r, String dimension) {
        List<MemberSnapshot> m = keepGoverning(r.members(), MAX_MEMBERS,
                "member".equals(r.governingKind()) ? r.governing() : Integer.MIN_VALUE,
                MemberSnapshot::id, "members");
        List<ShellSnapshot> s = keepGoverning(r.shells(), MAX_SHELLS,
                "shell".equals(r.governingKind()) ? r.governing() : Integer.MIN_VALUE,
                ShellSnapshot::id, "plate facets");
        return new StressResultPacket(true, "",
                r.revision().value(), dimension, r.singular(),
                r.maxDc(), r.maxDc() > 1.0,
                r.islands(), r.singularIslands(),
                r.bucklingFactor(), r.bucklingCritical(),
                r.members().size(), r.shells().size(), m, s);
    }

    /**
     * Truncates to {@code max}, but never drops the governing element: the one number
     * the HUD headlines must correspond to something the player can find drawn, or the
     * overlay says "max D/C 1.31" while every visible element reads safe (#42).
     */
    private static <T> List<T> keepGoverning(List<T> all, int max, int governingId,
                                             java.util.function.ToIntFunction<T> id, String what) {
        if (all.size() <= max) return all;
        BlockRealityMod.LOG.warn(
                "stress overlay truncated: {} {} solved, {} sent — the rest are not drawn",
                all.size(), what, max);
        List<T> kept = new ArrayList<>(all.subList(0, max));
        if (governingId != Integer.MIN_VALUE
                && kept.stream().noneMatch(t -> id.applyAsInt(t) == governingId)) {
            for (T t : all) {
                if (id.applyAsInt(t) == governingId) {
                    kept.set(max - 1, t);
                    break;
                }
            }
        }
        return kept;
    }

    /** False when decoding failed; the handler must drop the packet, not render it. */
    public boolean valid() { return valid; }

    public String invalidReason() { return invalidReason; }

    public long revision() { return revision; }

    public String dimension() { return dimension; }

    public boolean singular() { return singular; }

    public double maxDc() { return maxDc; }

    /** The server's double-precision verdict; the client never re-derives it. */
    public boolean overCapacity() { return overCapacity; }

    public int islands() { return islands; }

    public int singularIslands() { return singularIslands; }

    /** Smallest linear-buckling load factor; {@code <= 1} means already unstable. */
    public double bucklingFactor() { return bucklingFactor; }

    /** The server's double-precision verdict; the client never re-derives it. */
    public boolean bucklingCritical() { return bucklingCritical; }

    public int totalMembers() { return totalMembers; }

    public int totalShells() { return totalShells; }

    public boolean membersTruncated() { return totalMembers > members.size(); }

    public boolean shellsTruncated() { return totalShells > shells.size(); }

    public List<MemberSnapshot> members() { return members; }

    public List<ShellSnapshot> shells() { return shells; }

    // ---------------------------------------------------------------- encode
    //
    // The FIELD travels, not samples of it. Thirty-odd numbers per member replace eleven
    // stations of four fibres each — about a seventh of the bytes — and the client can
    // then evaluate the exact stress at any point of any block face, which is what a
    // surface contour needs and what interpolating between samples could never give.
    public static void encode(StressResultPacket p, FriendlyByteBuf buf) {
        buf.writeVarLong(p.revision);
        buf.writeUtf(p.dimension, 256);
        buf.writeBoolean(p.singular);
        buf.writeFloat((float) p.maxDc);
        buf.writeBoolean(p.overCapacity);
        buf.writeVarInt(Math.max(0, p.islands));
        buf.writeVarInt(Math.max(0, p.singularIslands));
        buf.writeFloat((float) p.bucklingFactor);
        buf.writeBoolean(p.bucklingCritical);
        buf.writeVarInt(Math.max(0, p.totalMembers));
        buf.writeVarInt(Math.max(0, p.totalShells));
        buf.writeVarInt(Math.min(p.members.size(), MAX_MEMBERS));

        for (int i = 0; i < p.members.size() && i < MAX_MEMBERS; i++) {
            MemberSnapshot m = p.members.get(i);
            buf.writeVarInt(m.id());
            buf.writeFloat((float) m.dc());
            // The double-precision verdict, decided here and only here (#55).
            buf.writeBoolean(m.dc() > 1.0);
            buf.writeByte(m.governingFibre().ordinal());
            // Where along the member the governing section sits, in mm. The client
            // regenerates stations from the field, so an INDEX into the server's
            // station list would not survive the trip; a position does (INV-5).
            buf.writeFloat((float) governingXmm(m));
            buf.writeUtf(m.section(), 48);

            List<BlockKey> blocks = m.blocks();
            int nb = Math.min(blocks.size(), MAX_BLOCKS);
            buf.writeVarInt(nb);
            for (int k = 0; k < nb; k++) {
                BlockKey b = blocks.get(k);
                buf.writeVarInt(b.x());
                buf.writeVarInt(b.y());
                buf.writeVarInt(b.z());
            }

            boolean hasField = m.field().isPresent();
            buf.writeBoolean(hasField);
            if (hasField) writeField(buf, m.field().get());
        }

        buf.writeVarInt(Math.min(p.shells.size(), MAX_SHELLS));
        for (int i = 0; i < p.shells.size() && i < MAX_SHELLS; i++) {
            ShellSnapshot s = p.shells.get(i);
            buf.writeVarInt(s.id());
            buf.writeUtf(s.plate(), 48);
            buf.writeFloat((float) s.thicknessMm());
            buf.writeFloat((float) s.dc());
            buf.writeBoolean(s.dc() > 1.0);
            buf.writeFloat((float) s.dcRaw());
            buf.writeBoolean(s.governingTopFace());
            buf.writeBoolean(s.edgeRecovered());

            List<BlockKey> blocks = s.blocks();
            int nb = Math.min(blocks.size(), 4);
            buf.writeVarInt(nb);
            for (int k = 0; k < nb; k++) {
                BlockKey b = blocks.get(k);
                buf.writeVarInt(b.x());
                buf.writeVarInt(b.y());
                buf.writeVarInt(b.z());
            }

            boolean hasField = s.field().isPresent() && s.field().get().isComplete();
            buf.writeBoolean(hasField);
            if (hasField) writeShellField(buf, s.field().get());
        }
    }

    private static double governingXmm(MemberSnapshot m) {
        int i = m.governingStation();
        if (i < 0 || i >= m.stations().size()) return -1;
        return m.stations().get(i).xMm();
    }

    // The four corner positions travel, not a centre and a size: the corners ARE the
    // element, and reconstructing them from a centre would bake in the assumption that
    // every facet is an axis-aligned square. That happens to be true of what the extractor
    // produces today and it is not something the wire should quietly depend on.
    private static void writeShellField(FriendlyByteBuf buf, ShellFieldSpec f) {
        for (Vec3d c : f.cornersMm()) writeVec(buf, c);
        writeVec(buf, f.ex());
        writeVec(buf, f.ey());
        writeVec(buf, f.normal());
        buf.writeFloat((float) f.thicknessMm());
        buf.writeFloat((float) f.nxx());
        buf.writeFloat((float) f.nyy());
        buf.writeFloat((float) f.nxy());
        buf.writeFloat((float) f.mxx());
        buf.writeFloat((float) f.myy());
        buf.writeFloat((float) f.mxy());
        buf.writeFloat((float) f.qx());
        buf.writeFloat((float) f.qy());
        for (ShellFieldSpec.Moments m : f.cornerM()) {
            buf.writeFloat((float) m.mxx());
            buf.writeFloat((float) m.myy());
            buf.writeFloat((float) m.mxy());
        }
    }

    private static void writeField(FriendlyByteBuf buf, StressFieldSpec f) {
        writeVec(buf, f.originMm());
        writeVec(buf, f.ax());
        writeVec(buf, f.ay());
        writeVec(buf, f.az());
        buf.writeFloat((float) f.lengthMm());
        buf.writeFloat((float) f.area());
        buf.writeFloat((float) f.iy());
        buf.writeFloat((float) f.iz());
        buf.writeFloat((float) f.cy());
        buf.writeFloat((float) f.cz());
        buf.writeFloat((float) f.wy());
        buf.writeFloat((float) f.wz());
        writeForces(buf, f.endI());
        writeForces(buf, f.endJ());
    }

    private static void writeForces(FriendlyByteBuf buf, EndForces e) {
        buf.writeFloat((float) e.n());
        buf.writeFloat((float) e.vy());
        buf.writeFloat((float) e.vz());
        buf.writeFloat((float) e.t());
        buf.writeFloat((float) e.my());
        buf.writeFloat((float) e.mz());
    }

    // ---------------------------------------------------------------- decode
    /** Off-schema content inside a frame; caught at the decode boundary. */
    private static final class Bad extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Bad(String why) { super(why); }
    }

    public static StressResultPacket decode(FriendlyByteBuf buf) {
        try {
            return decodeStrict(buf);
        } catch (RuntimeException e) {
            // Truncation (netty IndexOutOfBounds), a hostile count, NaN where a number
            // belongs — one packet, one rejection. Consuming the leftovers keeps the
            // channel's own bookkeeping quiet; the handler drops the packet.
            if (buf.readableBytes() > 0) buf.skipBytes(buf.readableBytes());
            return invalid(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static StressResultPacket decodeStrict(FriendlyByteBuf buf) {
        long revision = buf.readVarLong();
        if (revision < 0) throw new Bad("negative revision");
        String dimension = buf.readUtf(256);
        boolean singular = buf.readBoolean();
        double maxDc = finite(buf.readFloat(), "maxDc");
        boolean overCapacity = buf.readBoolean();
        maxDc = alignToVerdict(maxDc, overCapacity);
        int islands = count(buf.readVarInt(), Integer.MAX_VALUE, "islands");
        int singularIslands = count(buf.readVarInt(), Integer.MAX_VALUE, "singularIslands");
        double bucklingFactor = finite(buf.readFloat(), "bucklingFactor");
        if (bucklingFactor < 0) throw new Bad("negative bucklingFactor");
        boolean bucklingCritical = buf.readBoolean();
        int totalMembers = count(buf.readVarInt(), Integer.MAX_VALUE, "totalMembers");
        int totalShells = count(buf.readVarInt(), Integer.MAX_VALUE, "totalShells");
        int nMembers = count(buf.readVarInt(), MAX_MEMBERS, "members");

        List<MemberSnapshot> members = new ArrayList<>(nMembers);
        for (int i = 0; i < nMembers; i++) {
            int id = buf.readVarInt();
            double dc = finite(buf.readFloat(), "member dc");
            boolean overloaded = buf.readBoolean();
            dc = alignToVerdict(dc, overloaded);
            int fibreOrdinal = buf.readByte() & 0xFF;
            if (fibreOrdinal >= GoverningFibre.values().length) {
                // Mapping an unknown ordinal to NONE would show a governing member
                // with no governing reason — a confident wrong label (#40).
                throw new Bad("unknown governing fibre ordinal " + fibreOrdinal);
            }
            GoverningFibre fibre = GoverningFibre.values()[fibreOrdinal];
            double governingXmm = buf.readFloat();
            if (Double.isNaN(governingXmm) || Double.isInfinite(governingXmm)) {
                throw new Bad("governingXmm is not finite");
            }
            String section = buf.readUtf(48);

            int nb = count(buf.readVarInt(), MAX_BLOCKS, "member blocks");
            List<BlockKey> blocks = new ArrayList<>(nb);
            for (int k = 0; k < nb; k++) {
                blocks.add(new BlockKey(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
            }

            Optional<StressFieldSpec> field = buf.readBoolean()
                    ? Optional.of(readField(buf)) : Optional.empty();

            // Stations are REGENERATED from the field rather than sent. Handing a client
            // that can evaluate exactly an approximation of the same thing would be
            // strictly worse and strictly bigger.
            List<StressStation> stations = field.map(f -> f.stations(STATIONS)).orElse(List.of());

            // The member's end forces are the SAME two objects the field carries — the
            // server builds both from one pair (ProtocolCodec) — so they are taken from
            // the decoded field rather than left at zero. They used to be ZERO here, and
            // nothing read them, which is exactly why it survived: a public record
            // component that silently becomes 0.0 after a network trip is a trap for the
            // next caller, not a saving. Found by the display-track pipeline gate.
            members.add(new MemberSnapshot(id, "", section, field.map(StressFieldSpec::lengthMm).orElse(0.0),
                    dc, fibre, nearestStation(stations, governingXmm),
                    field.map(StressFieldSpec::endI).orElse(EndForces.ZERO),
                    field.map(StressFieldSpec::endJ).orElse(EndForces.ZERO),
                    blocks, stations, field));
        }

        int nShells = count(buf.readVarInt(), MAX_SHELLS, "shells");
        List<ShellSnapshot> shells = new ArrayList<>(nShells);
        for (int i = 0; i < nShells; i++) {
            int id = buf.readVarInt();
            String plate = buf.readUtf(48);
            double t = finite(buf.readFloat(), "thickness");
            double dc = finite(buf.readFloat(), "shell dc");
            boolean overloaded = buf.readBoolean();
            dc = alignToVerdict(dc, overloaded);
            double dcRaw = finite(buf.readFloat(), "shell dcRaw");
            boolean top = buf.readBoolean();
            boolean recovered = buf.readBoolean();

            int nb = count(buf.readVarInt(), 4, "shell blocks");
            List<BlockKey> blocks = new ArrayList<>(nb);
            for (int k = 0; k < nb; k++) {
                blocks.add(new BlockKey(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
            }

            Optional<ShellFieldSpec> field = buf.readBoolean()
                    ? Optional.of(readShellField(buf, t)) : Optional.empty();
            shells.add(new ShellSnapshot(id, "", plate, t, dc, dcRaw, top, recovered, blocks, field));
        }

        if (buf.readableBytes() > 0) {
            // Consumed exactly, or not this schema. Bytes past the end mean the two
            // sides disagree about the layout, and a disagreement that still parses
            // is the dangerous kind.
            throw new Bad(buf.readableBytes() + " bytes left after the packet");
        }

        return new StressResultPacket(true, "", revision, dimension, singular,
                maxDc, overCapacity, islands, singularIslands,
                bucklingFactor, bucklingCritical, totalMembers, totalShells, members, shells);
    }

    /** The regenerated station closest to the wire's governing position; -1 if none. */
    private static int nearestStation(List<StressStation> stations, double xMm) {
        if (xMm < 0 || stations.isEmpty()) return -1;
        int best = 0;
        double bestD = Math.abs(stations.get(0).xMm() - xMm);
        for (int i = 1; i < stations.size(); i++) {
            double d = Math.abs(stations.get(i).xMm() - xMm);
            if (d < bestD) {
                best = i;
                bestD = d;
            }
        }
        return best;
    }

    /**
     * Nudges a float-degraded D/C onto the side of 1.0 the server ruled for, so every
     * downstream comparison ({@code isOverloaded}, palette thresholds) agrees with the
     * carried verdict. The shift is at most one ulp around 1.0 — far inside the display
     * track's 1e-5 budget — and only fires when the rounding actually crossed the line.
     */
    private static double alignToVerdict(double dc, boolean overloaded) {
        if (overloaded && dc <= 1.0) return Math.nextUp(1.0);
        if (!overloaded && dc > 1.0) return 1.0;
        return dc;
    }

    private static ShellFieldSpec readShellField(FriendlyByteBuf buf, double fallbackT) {
        List<Vec3d> corners = new ArrayList<>(4);
        for (int k = 0; k < 4; k++) corners.add(readVec(buf));
        Vec3d ex = readVec(buf), ey = readVec(buf), n = readVec(buf);
        double t = finite(buf.readFloat(), "field thickness");
        double nxx = finite(buf.readFloat(), "Nxx"), nyy = finite(buf.readFloat(), "Nyy"),
                nxy = finite(buf.readFloat(), "Nxy");
        double mxx = finite(buf.readFloat(), "Mxx"), myy = finite(buf.readFloat(), "Myy"),
                mxy = finite(buf.readFloat(), "Mxy");
        double qx = finite(buf.readFloat(), "Qx"), qy = finite(buf.readFloat(), "Qy");
        List<ShellFieldSpec.Moments> corner = new ArrayList<>(4);
        for (int k = 0; k < 4; k++) {
            corner.add(new ShellFieldSpec.Moments(finite(buf.readFloat(), "Mc"),
                    finite(buf.readFloat(), "Mc"), finite(buf.readFloat(), "Mc")));
        }
        return new ShellFieldSpec(corners, ex, ey, n, t > 0 ? t : fallbackT,
                nxx, nyy, nxy, mxx, myy, mxy, qx, qy, corner);
    }

    private static StressFieldSpec readField(FriendlyByteBuf buf) {
        Vec3d origin = readVec(buf), ax = readVec(buf), ay = readVec(buf), az = readVec(buf);
        double len = finite(buf.readFloat(), "lengthMm");
        double a = finite(buf.readFloat(), "A");
        double iy = finite(buf.readFloat(), "Iy");
        double iz = finite(buf.readFloat(), "Iz");
        double cy = finite(buf.readFloat(), "cy");
        double cz = finite(buf.readFloat(), "cz");
        double wy = finite(buf.readFloat(), "wy");
        double wz = finite(buf.readFloat(), "wz");
        return new StressFieldSpec(origin, ax, ay, az, len, a, iy, iz, cy, cz, wy, wz,
                readForces(buf), readForces(buf));
    }

    private static EndForces readForces(FriendlyByteBuf buf) {
        return new EndForces(finite(buf.readFloat(), "N"), finite(buf.readFloat(), "Vy"),
                finite(buf.readFloat(), "Vz"), finite(buf.readFloat(), "T"),
                finite(buf.readFloat(), "My"), finite(buf.readFloat(), "Mz"));
    }

    /** Out-of-range counts reject the packet: a count past the cap is not this schema. */
    private static int count(int n, int max, String what) {
        if (n < 0 || n > max) throw new Bad("implausible " + what + " count " + n);
        return n;
    }

    /**
     * A NaN or infinity is corruption, not a value: it rejects the packet rather than
     * being washed to 0.0 (#40/#50). The one legitimate producer of these fields is the
     * engine wire, which is itself finite-checked, so a well-behaved server can never
     * hit this.
     */
    private static double finite(float f, String what) {
        if (!Float.isFinite(f)) throw new Bad(what + " is not finite");
        return f;
    }

    private static void writeVec(FriendlyByteBuf buf, Vec3d v) {
        buf.writeFloat((float) v.x());
        buf.writeFloat((float) v.y());
        buf.writeFloat((float) v.z());
    }

    private static Vec3d readVec(FriendlyByteBuf buf) {
        return new Vec3d(finite(buf.readFloat(), "vec"), finite(buf.readFloat(), "vec"),
                finite(buf.readFloat(), "vec"));
    }

    // ---------------------------------------------------------------- handle
    /**
     * The client-only type is named by its fully qualified name inside the supplier, and
     * is deliberately <em>not</em> imported. An import would put it in this class's
     * constant pool, and a dedicated server verifying this class would then try to
     * resolve a class that does not exist on its side.
     */
    public static void handle(StressResultPacket p, Supplier<NetworkEvent.Context> ctx) {
        if (!p.valid) {
            BlockRealityMod.LOG.warn("dropping malformed stress packet: {}", p.invalidReason);
        } else {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.blockreality.impl.client.ClientStressState.accept(p)));
        }
        ctx.get().setPacketHandled(true);
    }

    public WorldRevision worldRevision() { return new WorldRevision(revision); }
}
