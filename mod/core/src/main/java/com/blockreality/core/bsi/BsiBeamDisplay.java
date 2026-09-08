package com.blockreality.core.bsi;

import com.blockreality.api.*;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.geom.Vec3d;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** BSI samples to one display snapshot: unit/convention conversion only, with no force recovery. */
public final class BsiBeamDisplay {
    private BsiBeamDisplay() { }
    private static final List<String> FACES = List.of("TOP_Y", "BOT_Y", "PLUS_Z", "MINUS_Z");

    @Nonnull public static List<MemberSnapshot> decode(@Nonnull BsiResponse reply, long expectedRevision,
            @Nonnull Map<Integer, String> materials, @Nonnull Map<Integer, String> sections) {
        if (!"response".equals(reply.header().str("kind", "")) || !"bsi.solve".equals(reply.header().str("method", ""))
                || !"ok".equals(reply.status()) || reply.revision() != expectedRevision)
            throw new IllegalArgumentException("beam display requires this revision's successful solve");
        boolean f32 = reply.sections().containsKey("stations:f32");
        if (!reply.sections().containsKey("members") || !reply.sections().containsKey("memberGeometry")
                || (!f32 && !reply.sections().containsKey("stations")))
            throw new IllegalArgumentException("beam display was not requested");
        var members = reply.members(); var geometry = reply.memberGeometry();
        var coords = reply.memberBlocks(); var raw = reply.stations();
        List<MemberSnapshot> out = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            var m = members.get(i); var g = geometry.get(i);
            String material = token(materials, m.material()), section = token(sections, m.section());
            if (m.lengthM() <= 0 || m.maxDC() < 0 || m.governingS() < 0 || m.governingS() > 1)
                throw new IllegalArgumentException("invalid beam metadata");
            if (m.governingFibre() >= GoverningFibre.values().length) throw new IllegalArgumentException("unknown governing fibre");
            List<BlockKey> blocks = new ArrayList<>(m.blockCount());
            for (int k = m.blockFirst(); k < m.blockFirst() + m.blockCount(); k++) {
                int[] b = coords.get(k); blocks.add(new BlockKey(b[0], b[1], b[2]));
            }
            double length = m.lengthM() * 1000;
            List<StressStation> samples = new ArrayList<>(m.stationCount());
            int governing = -1, matches = 0;
            double governingS = f32 ? (double)(float)m.governingS() : m.governingS();
            for (int k = 0; k < m.stationCount(); k++) {
                double[] s = raw[m.stationFirst() + k];
                List<Fibre> fibres = new ArrayList<>(4);
                double tension = 0, compression = 0;
                for (int j = 0; j < 4; j++) {
                    double sigma = s[4 + j] * 1e-6;
                    fibres.add(new Fibre(FACES.get(j), (j < 2 ? g.ey() : g.ez()).scaled(j % 2 == 0 ? 1 : -1),
                            (j < 2 ? g.faceY().get(0) : g.faceZ().get(2)) * 1000, sigma));
                    tension = Math.max(tension, sigma); compression = Math.max(compression, -sigma);
                }
                samples.add(new StressStation(s[0] * length, new Vec3d(s[1] * 1000, s[2] * 1000, s[3] * 1000),
                        fibres, tension, compression, s[8] * 1e-6, offset(s[9]), offset(s[10])));
                if (s[0] == governingS) { governing = k; matches++; }
            }
            var display = new BeamDisplayField(g.origin().scaled(1000), g.ex(), g.ey(), g.ez(), length,
                    g.faceY().get(0) * 1000, g.faceZ().get(2) * 1000, samples);
            out.add(new MemberSnapshot(m.id(), material, section, length, m.maxDC(),
                    GoverningFibre.values()[m.governingFibre()], matches == 1 ? governing : -1,
                    diagnostic(m.endI()), diagnostic(m.endJ()), blocks, samples, Optional.empty(),
                    Optional.of(display), m.overloaded(), Optional.of(m.governingS() * length)));
        }
        return List.copyOf(out);
    }
    private static String token(Map<Integer, String> map, int id) {
        String value = map.get(id);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("unresolved beam catalogue token");
        return value;
    }
    private static Optional<Double> offset(double m) { return Double.isNaN(m) ? Optional.empty() : Optional.of(m * 1000); }
    private static EndForces diagnostic(double[] e) {
        for (int i = 0; i < 6; i++) if (!Double.isFinite(e[i] * (i < 3 ? 1 : 1000)))
            throw new IllegalArgumentException("non-finite converted beam end force");
        return new EndForces(-e[0], e[1], e[2], e[3] * 1000, e[4] * 1000, e[5] * 1000);
    }
}
