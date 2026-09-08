package com.blockreality.impl.net;

import com.blockreality.api.ShellDisplayField;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.geom.Vec3d;
import net.minecraft.network.FriendlyByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Channel 6 shell records. Carries decided samples and flags; never reconstructs mechanics. */
final class ShellPacketCodec {
    private ShellPacketCodec() { }
    private static final int MAX_BLOCKS = 65_536;
    record Entry(ShellSnapshot shell, boolean withheld) { }

    static void write(FriendlyByteBuf buf, ShellSnapshot s, boolean withheld) {
        buf.writeVarInt(s.id());
        buf.writeUtf(clip(s.plate()), 48); buf.writeUtf(clip(s.material()), 48);
        buf.writeDouble(s.thicknessMm()); buf.writeDouble(s.dc());
        buf.writeBoolean(s.overloaded());
        buf.writeBoolean(s.rawDc().isPresent());
        if (s.rawDc().isPresent()) buf.writeDouble(s.rawDc().getAsDouble());
        buf.writeBoolean(s.governingTopFace()); buf.writeBoolean(s.edgeRecovered());
        buf.writeVarInt(s.governingFibre());
        if (s.blocks().size() > MAX_BLOCKS) throw new IllegalArgumentException("too many shell blocks");
        buf.writeVarInt(s.blocks().size());
        for (BlockKey b : s.blocks()) {
            buf.writeVarInt(b.x()); buf.writeVarInt(b.y()); buf.writeVarInt(b.z());
        }
        buf.writeBoolean(withheld);
        buf.writeBoolean(s.display().isPresent());
        if (s.display().isEmpty()) return;
        var f = s.display().get();
        for (Vec3d p : f.cornersMm()) writeVec(buf, p);
        writeVec(buf, f.ex()); writeVec(buf, f.ey()); writeVec(buf, f.normal());
        for (var face : List.of(f.top(), f.bottom())) for (var p : face) {
            buf.writeDouble(p.s1()); buf.writeDouble(p.s2()); buf.writeDouble(p.theta()); buf.writeDouble(p.vm());
        }
    }

    static Entry read(FriendlyByteBuf buf) {
        int id = buf.readVarInt(); String plate = buf.readUtf(48), material = buf.readUtf(48);
        double thickness = finite(buf), dc = finite(buf);
        boolean overloaded = buf.readBoolean();
        double raw = buf.readBoolean() ? finite(buf) : Double.NaN;
        boolean top = buf.readBoolean(), recovered = buf.readBoolean();
        int fibre = buf.readVarInt();
        if (fibre < 0 || fibre > 6) throw new IllegalArgumentException("unknown shell governing fibre");
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_BLOCKS) throw new IllegalArgumentException("invalid shell block count");
        List<BlockKey> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) blocks.add(new BlockKey(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        boolean withheld = buf.readBoolean();
        Optional<ShellDisplayField> display = Optional.empty();
        if (buf.readBoolean()) {
            List<Vec3d> corners = new ArrayList<>(4);
            for (int k = 0; k < 4; k++) corners.add(readVec(buf));
            Vec3d ex = readVec(buf), ey = readVec(buf), normal = readVec(buf);
            display = Optional.of(new ShellDisplayField(corners, ex, ey, normal, readFace(buf), readFace(buf)));
        }
        return new Entry(new ShellSnapshot(id, material, plate, thickness, dc, raw, top, recovered, blocks,
                Optional.empty(), display, overloaded, fibre), withheld);
    }
    private static List<ShellDisplayField.Surface> readFace(FriendlyByteBuf buf) {
        List<ShellDisplayField.Surface> out = new ArrayList<>(4);
        for (int k = 0; k < 4; k++) out.add(new ShellDisplayField.Surface(finite(buf), finite(buf), finite(buf), finite(buf)));
        return out;
    }
    private static String clip(String s) { return s == null ? "" : s.substring(0, Math.min(48, s.length())); }
    private static void writeVec(FriendlyByteBuf buf, Vec3d p) {
        buf.writeDouble(p.x()); buf.writeDouble(p.y()); buf.writeDouble(p.z());
    }
    private static Vec3d readVec(FriendlyByteBuf buf) { return new Vec3d(finite(buf), finite(buf), finite(buf)); }
    private static double finite(FriendlyByteBuf buf) {
        double v = buf.readDouble();
        if (!Double.isFinite(v)) throw new IllegalArgumentException("non-finite shell packet value");
        return v;
    }
}
