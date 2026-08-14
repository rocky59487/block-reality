package com.blockreality.impl.net;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.EndForces;
import com.blockreality.api.GoverningFibre;
import com.blockreality.api.Fibre;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.StressStation;
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
    private static final int MAX_STATIONS = 32;
    private static final int MAX_FIBRES = 8;

    private final long revision;
    private final boolean singular;
    private final double maxDc;
    private final List<MemberSnapshot> members;

    private StressResultPacket(long revision, boolean singular, double maxDc, List<MemberSnapshot> members) {
        this.revision = revision;
        this.singular = singular;
        this.maxDc = maxDc;
        this.members = members;
    }

    public static StressResultPacket of(AnalysisResult r) {
        List<MemberSnapshot> m = r.members();
        if (m.size() > MAX_MEMBERS) {
            BlockRealityMod.LOG.warn(
                    "stress overlay truncated: {} members solved, {} sent — the rest are not drawn",
                    m.size(), MAX_MEMBERS);
            m = m.subList(0, MAX_MEMBERS);
        }
        return new StressResultPacket(r.revision().value(), r.singular(), r.maxDc(), m);
    }

    public long revision() { return revision; }

    public boolean singular() { return singular; }

    public double maxDc() { return maxDc; }

    public List<MemberSnapshot> members() { return members; }

    // ---------------------------------------------------------------- encode
    public static void encode(StressResultPacket p, FriendlyByteBuf buf) {
        buf.writeVarLong(p.revision);
        buf.writeBoolean(p.singular);
        buf.writeFloat((float) p.maxDc);
        buf.writeVarInt(Math.min(p.members.size(), MAX_MEMBERS));

        for (int i = 0; i < p.members.size() && i < MAX_MEMBERS; i++) {
            MemberSnapshot m = p.members.get(i);
            buf.writeVarInt(m.id());
            buf.writeFloat((float) m.dc());
            buf.writeByte(m.governingFibre().ordinal());
            buf.writeVarInt(Math.max(-1, m.governingStation()));

            List<StressStation> st = m.stations();
            int nSt = Math.min(st.size(), MAX_STATIONS);
            buf.writeVarInt(nSt);
            for (int q = 0; q < nSt; q++) {
                StressStation s = st.get(q);
                writeVec(buf, s.centroidMm());
                buf.writeBoolean(s.naOffsetYMm().isPresent());
                buf.writeFloat(s.naOffsetYMm().orElse(0.0).floatValue());

                List<Fibre> fb = s.fibres();
                int nFb = Math.min(fb.size(), MAX_FIBRES);
                buf.writeVarInt(nFb);
                for (int k = 0; k < nFb; k++) {
                    Fibre f = fb.get(k);
                    buf.writeUtf(f.name(), 16);
                    writeVec(buf, f.direction());
                    buf.writeFloat((float) f.offsetMm());
                    buf.writeFloat((float) f.sigmaMpa());
                }
            }
        }
    }

    // ---------------------------------------------------------------- decode
    public static StressResultPacket decode(FriendlyByteBuf buf) {
        long revision = Math.max(0, buf.readVarLong());
        boolean singular = buf.readBoolean();
        double maxDc = finite(buf.readFloat());
        int nMembers = clamp(buf.readVarInt(), MAX_MEMBERS);

        List<MemberSnapshot> members = new ArrayList<>(nMembers);
        for (int i = 0; i < nMembers; i++) {
            int id = buf.readVarInt();
            double dc = finite(buf.readFloat());
            int fibreOrdinal = buf.readByte() & 0xFF;
            GoverningFibre fibre = fibreOrdinal < GoverningFibre.values().length
                    ? GoverningFibre.values()[fibreOrdinal] : GoverningFibre.NONE;
            int governingStation = buf.readVarInt();

            int nSt = clamp(buf.readVarInt(), MAX_STATIONS);
            List<StressStation> stations = new ArrayList<>(nSt);
            for (int q = 0; q < nSt; q++) {
                Vec3d centre = readVec(buf);
                boolean hasNa = buf.readBoolean();
                double na = finite(buf.readFloat());

                int nFb = clamp(buf.readVarInt(), MAX_FIBRES);
                List<Fibre> fibres = new ArrayList<>(nFb);
                double tens = 0, comp = 0;
                for (int k = 0; k < nFb; k++) {
                    String name = buf.readUtf(16);
                    Vec3d dir = readVec(buf);
                    double off = finite(buf.readFloat());
                    double sigma = finite(buf.readFloat());
                    fibres.add(new Fibre(name, dir, off, sigma));
                    tens = Math.max(tens, sigma);
                    comp = Math.max(comp, -sigma);
                }
                stations.add(new StressStation(0, centre, fibres, tens, comp, 0,
                        hasNa ? Optional.of(na) : Optional.empty(), Optional.empty()));
            }
            members.add(new MemberSnapshot(id, "", "", 0, dc, fibre, governingStation,
                    EndForces.ZERO, EndForces.ZERO, List.of(), stations));
        }
        return new StressResultPacket(revision, singular, maxDc, members);
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
