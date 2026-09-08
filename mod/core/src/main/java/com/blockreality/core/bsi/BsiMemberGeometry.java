package com.blockreality.core.bsi;

import com.blockreality.api.geom.Vec3d;
import javax.annotation.Nonnull;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Immutable SI sample geometry supplied by the engine; never a section silhouette. */
public record BsiMemberGeometry(int id, @Nonnull Vec3d origin, @Nonnull Vec3d ex, @Nonnull Vec3d ey, @Nonnull Vec3d ez,
                                @Nonnull List<Double> faceY, @Nonnull List<Double> faceZ) {
    public BsiMemberGeometry {
        faceY = List.copyOf(faceY); faceZ = List.copyOf(faceZ);
        if (id < 0 || faceY.size() != 4 || faceZ.size() != 4) throw invalid("id or sample count");
        for (Vec3d v : List.of(origin, ex, ey, ez))
            if (!Double.isFinite(v.x()) || !Double.isFinite(v.y()) || !Double.isFinite(v.z())) throw invalid("nonfinite vector");
        for (var values : List.of(faceY, faceZ)) for (double v : values)
            if (!Double.isFinite(v)) throw invalid("nonfinite sample offset");
        if (!(faceY.get(0) > 0) || !(faceZ.get(2) > 0) || faceY.get(1) != -faceY.get(0) ||
                faceZ.get(3) != -faceZ.get(2) || faceY.get(2) != 0 || faceY.get(3) != 0 ||
                faceZ.get(0) != 0 || faceZ.get(1) != 0) throw invalid("sample order");
        List<Vec3d> axes = List.of(ex, ey, ez);
        for (int i = 0; i < 3; i++) for (int j = i; j < 3; j++)
            if (Math.abs(dot(axes.get(i), axes.get(j)) - (i == j ? 1 : 0)) > 1e-9) throw invalid("orthonormal frame");
        if (Math.abs(ex.y()*ey.z()-ex.z()*ey.y()-ez.x()) > 1e-9 ||
                Math.abs(ex.z()*ey.x()-ex.x()*ey.z()-ez.y()) > 1e-9 ||
                Math.abs(ex.x()*ey.y()-ex.y()*ey.x()-ez.z()) > 1e-9) throw invalid("right handed frame");
    }

    /** Place an engine-sampled fibre at an engine-sampled station; no stress recovery. */
    @Nonnull public Vec3d samplePosition(@Nonnull Vec3d stationMetres, int fibre) {
        double y = faceY.get(fibre), z = faceZ.get(fibre);
        return new Vec3d(stationMetres.x()+ey.x()*y+ez.x()*z,
                stationMetres.y()+ey.y()*y+ez.y()*z, stationMetres.z()+ey.z()*y+ez.z()*z);
    }

    static BsiMemberGeometry read(ByteBuffer data, int offset) {
        if (data.getInt(offset + 4) != 0) throw invalid("reserved");
        List<Double> y = new ArrayList<>(), z = new ArrayList<>();
        for (int k = 0; k < 4; k++) { y.add(data.getDouble(offset+104+8*k)); z.add(data.getDouble(offset+136+8*k)); }
        return new BsiMemberGeometry(data.getInt(offset), vector(data, offset+8), vector(data, offset+32),
                vector(data, offset+56), vector(data, offset+80), y, z);
    }
    private static Vec3d vector(ByteBuffer b, int o) { return new Vec3d(b.getDouble(o), b.getDouble(o+8), b.getDouble(o+16)); }
    private static double dot(Vec3d a, Vec3d b) { return a.x()*b.x()+a.y()*b.y()+a.z()*b.z(); }
    private static IllegalArgumentException invalid(String why) { return new IllegalArgumentException("Invalid BSI memberGeometry: " + why); }
}
