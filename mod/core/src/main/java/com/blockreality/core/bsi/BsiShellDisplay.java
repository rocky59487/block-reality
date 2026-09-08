package com.blockreality.core.bsi;

import com.blockreality.api.ShellDisplayField;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.geom.Vec3d;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The BSI-to-display boundary: units and indexing only. No force recovery or safety decisions. */
public final class BsiShellDisplay {
    private BsiShellDisplay() { }

    @Nonnull public static List<ShellSnapshot> decode(@Nonnull BsiResponse reply, long expectedRevision,
                                                      @Nonnull Map<Integer, String> materials) {
        if (!"response".equals(reply.header().str("kind", "")) || !"bsi.solve".equals(reply.header().str("method", ""))
                || !"ok".equals(reply.status()) || reply.revision() != expectedRevision)
            throw new IllegalArgumentException("shell display requires this revision's successful solve");
        if (!reply.sections().containsKey("facets"))
            throw new IllegalArgumentException("shell display was not requested");
        var facets = reply.facets(); var surfaces = reply.facetSurfaces(); var coords = reply.facetBlocks();
        List<ShellSnapshot> out = new ArrayList<>(facets.size());
        for (int i = 0; i < facets.size(); i++) {
            var f = facets.get(i); var s = surfaces.get(i);
            String material = materials.get(f.material());
            if (material == null || material.isBlank()) throw new IllegalArgumentException("unresolved shell material");
            List<BlockKey> blocks = new ArrayList<>(f.blockCount());
            for (int b = f.blockFirst(); b < f.blockFirst() + f.blockCount(); b++) {
                int[] xyz = coords.get(b); blocks.add(new BlockKey(xyz[0], xyz[1], xyz[2]));
            }
            List<Vec3d> corners = new ArrayList<>(4);
            for (double[] p : f.corners()) corners.add(vec(p).scaled(1000));
            var display = new ShellDisplayField(corners, vec(f.ex()), vec(f.ey()), vec(f.n()),
                    convert(s.top()), convert(s.bottom()));
            // BSI identifies panels by material, with no separate legacy plate token or dcRaw.
            out.add(new ShellSnapshot(f.id(), material, material, f.thicknessM() * 1000, f.dc(), Double.NaN,
                    f.governingTop(), false, blocks, Optional.empty(), Optional.of(display),
                    f.overloaded(), f.governingFibre()));
        }
        return List.copyOf(out);
    }

    private static List<ShellDisplayField.Surface> convert(List<BsiResponse.Surface> values) {
        return values.stream().map(s -> new ShellDisplayField.Surface(
                s.s1() * 1e-6, s.s2() * 1e-6, s.theta(), s.vm() * 1e-6)).toList();
    }
    private static Vec3d vec(double[] p) { return new Vec3d(p[0], p[1], p[2]); }
}
