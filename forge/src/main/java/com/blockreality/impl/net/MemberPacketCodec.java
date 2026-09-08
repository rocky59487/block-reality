package com.blockreality.impl.net;

import com.blockreality.api.*;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.geom.Vec3d;
import net.minecraft.network.FriendlyByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Channel 8 member samples with optional exact side identity. Shared geometry is sent once; the client receives no mechanical field. */
final class MemberPacketCodec {
    private MemberPacketCodec() { }
    private static final List<String> FACES = List.of("TOP_Y", "BOT_Y", "PLUS_Z", "MINUS_Z");
    private static final int MAX_ITEMS = 65_536;
    record Entry(MemberSnapshot member, boolean withheld) { }
    private record Geometry(Vec3d origin, Vec3d ax, Vec3d ay, Vec3d az, double hy, double hz) { }

    static void write(FriendlyByteBuf b, MemberSnapshot m, boolean withheld) {
        b.writeVarInt(m.id()); b.writeDouble(m.lengthMm()); b.writeDouble(m.dc()); b.writeBoolean(m.overloaded());
        b.writeByte(m.governingFibre().ordinal()); b.writeVarInt(m.governingStation()); optional(b, m.governingPositionMm());
        b.writeUtf(clip(m.section()), 48); b.writeUtf(clip(m.material()), 48);
        forces(b, m.endI()); forces(b, m.endJ());
        b.writeVarInt(count(m.blocks().size(), MAX_ITEMS));
        for (var p : m.blocks()) { b.writeVarInt(p.x()); b.writeVarInt(p.y()); b.writeVarInt(p.z()); }
        b.writeBoolean(withheld); b.writeBoolean(m.display().isPresent());
        if (m.display().isPresent()) {
            var f = m.display().get(); vec(b, f.originMm()); vec(b, f.ax()); vec(b, f.ay()); vec(b, f.az());
            b.writeDouble(f.halfYMm()); b.writeDouble(f.halfZMm());
        }
        b.writeVarInt(count(m.stations().size(), MAX_ITEMS));
        for (var s : m.stations()) {
            b.writeBoolean(s.identity().isPresent());
            s.identity().ifPresent(id -> { b.writeDouble(id.s()); b.writeByte(id.side()); });
            b.writeDouble(s.xMm()); vec(b, s.centroidMm());
            if (m.display().isEmpty()) b.writeVarInt(count(s.fibres().size(), 4));
            for (var f : s.fibres()) {
                if (m.display().isEmpty()) { b.writeUtf(clip(f.name()), 48); vec(b, f.direction()); b.writeDouble(f.offsetMm()); }
                b.writeDouble(f.sigmaMpa());
            }
            b.writeDouble(s.sigmaTensMpa()); b.writeDouble(s.sigmaCompMpa()); b.writeDouble(s.tauMpa());
            optional(b, s.naOffsetYMm()); optional(b, s.naOffsetZMm());
        }
    }

    static Entry read(FriendlyByteBuf b) {
        int id = b.readVarInt(); double length = finite(b), dc = finite(b); boolean overloaded = b.readBoolean();
        int ordinal = count(b.readUnsignedByte(), GoverningFibre.values().length - 1), governing = b.readVarInt();
        Optional<Double> position = optional(b);
        String section = b.readUtf(48), material = b.readUtf(48);
        EndForces endI = forces(b), endJ = forces(b);
        int nb = count(b.readVarInt(), MAX_ITEMS);
        if (nb > b.readableBytes() / 3) throw new IllegalArgumentException("truncated beam blocks");
        List<BlockKey> blocks = new ArrayList<>(nb);
        for (int i = 0; i < nb; i++) blocks.add(new BlockKey(b.readVarInt(), b.readVarInt(), b.readVarInt()));
        boolean withheld = b.readBoolean(); Geometry g = null;
        if (b.readBoolean()) g = new Geometry(vec(b), vec(b), vec(b), vec(b), finite(b), finite(b));
        int ns = count(b.readVarInt(), MAX_ITEMS);
        if (ns > b.readableBytes() / 59) throw new IllegalArgumentException("truncated beam stations");
        List<StressStation> stations = new ArrayList<>(ns);
        for (int i = 0; i < ns; i++) {
            Optional<StressStation.Identity> identity = b.readBoolean()
                    ? Optional.of(new StressStation.Identity(finite(b), b.readByte())) : Optional.empty();
            double x = finite(b); Vec3d centre = vec(b);
            int nf = g == null ? count(b.readVarInt(), 4) : 4;
            List<Fibre> fibres = new ArrayList<>(nf);
            for (int j = 0; j < nf; j++) {
                String name = g == null ? b.readUtf(48) : FACES.get(j);
                Vec3d dir = g == null ? vec(b) : (j < 2 ? g.ay : g.az).scaled(j % 2 == 0 ? 1 : -1);
                double offset = g == null ? finite(b) : j < 2 ? g.hy : g.hz;
                fibres.add(new Fibre(name, dir, offset, finite(b)));
            }
            stations.add(new StressStation(x, centre, fibres, finite(b), finite(b), finite(b), optional(b), optional(b), identity));
        }
        if (id < 0 || length < 0 || dc < 0 || governing < -1 || governing >= ns
                || position.filter(x -> x < 0 || x > length).isPresent())
            throw new IllegalArgumentException("invalid beam metadata");
        Optional<BeamDisplayField> display = g == null ? Optional.empty() : Optional.of(
                new BeamDisplayField(g.origin, g.ax, g.ay, g.az, length, g.hy, g.hz, stations));
        return new Entry(new MemberSnapshot(id, material, section, length, dc, GoverningFibre.values()[ordinal],
                governing, endI, endJ, blocks, stations, Optional.empty(), display, overloaded, position), withheld);
    }
    private static void optional(FriendlyByteBuf b, Optional<Double> v) { b.writeBoolean(v.isPresent()); v.ifPresent(b::writeDouble); }
    private static Optional<Double> optional(FriendlyByteBuf b) { return b.readBoolean() ? Optional.of(finite(b)) : Optional.empty(); }
    private static void vec(FriendlyByteBuf b, Vec3d p) { b.writeDouble(p.x()); b.writeDouble(p.y()); b.writeDouble(p.z()); }
    private static Vec3d vec(FriendlyByteBuf b) { return new Vec3d(finite(b), finite(b), finite(b)); }
    private static void forces(FriendlyByteBuf b, EndForces e) {
        for (double v : new double[]{e.n(), e.vy(), e.vz(), e.t(), e.my(), e.mz()}) b.writeDouble(v);
    }
    private static EndForces forces(FriendlyByteBuf b) { return new EndForces(finite(b), finite(b), finite(b), finite(b), finite(b), finite(b)); }
    private static int count(int n, int cap) {
        if (n < 0 || n > cap) throw new IllegalArgumentException("invalid beam count/enum");
        return n;
    }
    private static double finite(FriendlyByteBuf b) {
        double n = b.readDouble();
        if (!Double.isFinite(n)) throw new IllegalArgumentException("non-finite beam packet value");
        return n;
    }
    private static String clip(String s) { return s == null ? "" : s.substring(0, Math.min(48, s.length())); }
}
