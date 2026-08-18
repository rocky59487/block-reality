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
 * <h2>Decoding never throws</h2>
 * Every count is clamped and every field is read before anything is validated. Forge kicks
 * a player whose packet decoder throws, so a decoder that throws on unexpected input is a
 * remote disconnect waiting to happen — which is exactly what the previous codebase's
 * {@code FdActionPacket} did on an out-of-range index.
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

    private final long revision;
    private final boolean singular;
    private final double maxDc;
    private final int islands;
    private final int singularIslands;
    private final double bucklingFactor;
    private final List<MemberSnapshot> members;
    private final List<ShellSnapshot> shells;

    private StressResultPacket(long revision, boolean singular, double maxDc,
                               int islands, int singularIslands, double bucklingFactor,
                               List<MemberSnapshot> members, List<ShellSnapshot> shells) {
        this.revision = revision;
        this.singular = singular;
        this.maxDc = maxDc;
        this.islands = islands;
        this.singularIslands = singularIslands;
        this.bucklingFactor = bucklingFactor;
        this.members = members;
        this.shells = shells;
    }

    public static StressResultPacket of(AnalysisResult r) {
        List<MemberSnapshot> m = r.members();
        if (m.size() > MAX_MEMBERS) {
            BlockRealityMod.LOG.warn(
                    "stress overlay truncated: {} members solved, {} sent — the rest are not drawn",
                    m.size(), MAX_MEMBERS);
            m = m.subList(0, MAX_MEMBERS);
        }
        List<ShellSnapshot> s = r.shells();
        if (s.size() > MAX_SHELLS) {
            BlockRealityMod.LOG.warn(
                    "stress overlay truncated: {} plate facets solved, {} sent — the rest are not drawn",
                    s.size(), MAX_SHELLS);
            s = s.subList(0, MAX_SHELLS);
        }
        return new StressResultPacket(r.revision().value(), r.singular(), r.maxDc(),
                r.islands(), r.singularIslands(), r.bucklingFactor(), m, s);
    }

    public long revision() { return revision; }

    public boolean singular() { return singular; }

    public double maxDc() { return maxDc; }

    public int islands() { return islands; }

    public int singularIslands() { return singularIslands; }

    /** Smallest linear-buckling load factor; {@code <= 1} means already unstable. */
    public double bucklingFactor() { return bucklingFactor; }

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
        buf.writeBoolean(p.singular);
        buf.writeFloat((float) p.maxDc);
        buf.writeVarInt(Math.max(0, p.islands));
        buf.writeVarInt(Math.max(0, p.singularIslands));
        buf.writeFloat((float) p.bucklingFactor);
        buf.writeVarInt(Math.min(p.members.size(), MAX_MEMBERS));

        for (int i = 0; i < p.members.size() && i < MAX_MEMBERS; i++) {
            MemberSnapshot m = p.members.get(i);
            buf.writeVarInt(m.id());
            buf.writeFloat((float) m.dc());
            buf.writeByte(m.governingFibre().ordinal());
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
    public static StressResultPacket decode(FriendlyByteBuf buf) {
        long revision = Math.max(0, buf.readVarLong());
        boolean singular = buf.readBoolean();
        double maxDc = finite(buf.readFloat());
        int islands = clamp(buf.readVarInt(), Integer.MAX_VALUE);
        int singularIslands = clamp(buf.readVarInt(), Integer.MAX_VALUE);
        double bucklingFactor = Math.max(0, finite(buf.readFloat()));
        int nMembers = clamp(buf.readVarInt(), MAX_MEMBERS);

        List<MemberSnapshot> members = new ArrayList<>(nMembers);
        for (int i = 0; i < nMembers; i++) {
            int id = buf.readVarInt();
            double dc = finite(buf.readFloat());
            int fibreOrdinal = buf.readByte() & 0xFF;
            GoverningFibre fibre = fibreOrdinal < GoverningFibre.values().length
                    ? GoverningFibre.values()[fibreOrdinal] : GoverningFibre.NONE;
            String section = buf.readUtf(48);

            int nb = clamp(buf.readVarInt(), MAX_BLOCKS);
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

            members.add(new MemberSnapshot(id, "", section, field.map(StressFieldSpec::lengthMm).orElse(0.0),
                    dc, fibre, 0, EndForces.ZERO, EndForces.ZERO, blocks, stations, field));
        }

        int nShells = clamp(buf.readVarInt(), MAX_SHELLS);
        List<ShellSnapshot> shells = new ArrayList<>(nShells);
        for (int i = 0; i < nShells; i++) {
            int id = buf.readVarInt();
            String plate = buf.readUtf(48);
            double t = finite(buf.readFloat());
            double dc = finite(buf.readFloat());
            double dcRaw = finite(buf.readFloat());
            boolean top = buf.readBoolean();
            boolean recovered = buf.readBoolean();

            int nb = clamp(buf.readVarInt(), 4);
            List<BlockKey> blocks = new ArrayList<>(nb);
            for (int k = 0; k < nb; k++) {
                blocks.add(new BlockKey(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
            }

            Optional<ShellFieldSpec> field = buf.readBoolean()
                    ? Optional.of(readShellField(buf, t)) : Optional.empty();
            shells.add(new ShellSnapshot(id, "", plate, t, dc, dcRaw, top, recovered, blocks, field));
        }

        return new StressResultPacket(revision, singular, maxDc, islands, singularIslands,
                bucklingFactor, members, shells);
    }

    private static ShellFieldSpec readShellField(FriendlyByteBuf buf, double fallbackT) {
        List<Vec3d> corners = new ArrayList<>(4);
        for (int k = 0; k < 4; k++) corners.add(readVec(buf));
        Vec3d ex = readVec(buf), ey = readVec(buf), n = readVec(buf);
        double t = finite(buf.readFloat());
        double nxx = finite(buf.readFloat()), nyy = finite(buf.readFloat()), nxy = finite(buf.readFloat());
        double mxx = finite(buf.readFloat()), myy = finite(buf.readFloat()), mxy = finite(buf.readFloat());
        double qx = finite(buf.readFloat()), qy = finite(buf.readFloat());
        List<ShellFieldSpec.Moments> corner = new ArrayList<>(4);
        for (int k = 0; k < 4; k++) {
            corner.add(new ShellFieldSpec.Moments(finite(buf.readFloat()),
                    finite(buf.readFloat()), finite(buf.readFloat())));
        }
        return new ShellFieldSpec(corners, ex, ey, n, t > 0 ? t : fallbackT,
                nxx, nyy, nxy, mxx, myy, mxy, qx, qy, corner);
    }

    private static StressFieldSpec readField(FriendlyByteBuf buf) {
        Vec3d origin = readVec(buf), ax = readVec(buf), ay = readVec(buf), az = readVec(buf);
        double len = finite(buf.readFloat());
        double a = finite(buf.readFloat());
        double iy = finite(buf.readFloat());
        double iz = finite(buf.readFloat());
        double cy = finite(buf.readFloat());
        double cz = finite(buf.readFloat());
        double wy = finite(buf.readFloat());
        double wz = finite(buf.readFloat());
        return new StressFieldSpec(origin, ax, ay, az, len, a, iy, iz, cy, cz, wy, wz,
                readForces(buf), readForces(buf));
    }

    private static EndForces readForces(FriendlyByteBuf buf) {
        return new EndForces(finite(buf.readFloat()), finite(buf.readFloat()), finite(buf.readFloat()),
                finite(buf.readFloat()), finite(buf.readFloat()), finite(buf.readFloat()));
    }

    /** Out-of-range counts are clamped, never rejected: a rejection here disconnects a player. */
    private static int clamp(int n, int max) {
        if (n < 0) return 0;
        return Math.min(n, max);
    }

    /** NaN and infinity reach the renderer otherwise, where they become invisible geometry. */
    private static double finite(float f) { return Float.isFinite(f) ? f : 0.0; }

    private static void writeVec(FriendlyByteBuf buf, Vec3d v) {
        buf.writeFloat((float) v.x());
        buf.writeFloat((float) v.y());
        buf.writeFloat((float) v.z());
    }

    private static Vec3d readVec(FriendlyByteBuf buf) {
        return new Vec3d(finite(buf.readFloat()), finite(buf.readFloat()), finite(buf.readFloat()));
    }

    // ---------------------------------------------------------------- handle
    /**
     * The client-only type is named by its fully qualified name inside the supplier, and
     * is deliberately <em>not</em> imported. An import would put it in this class's
     * constant pool, and a dedicated server verifying this class would then try to
     * resolve a class that does not exist on its side.
     */
    public static void handle(StressResultPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.blockreality.impl.client.ClientStressState.accept(p)));
        ctx.get().setPacketHandled(true);
    }

    public WorldRevision worldRevision() { return new WorldRevision(revision); }
}
