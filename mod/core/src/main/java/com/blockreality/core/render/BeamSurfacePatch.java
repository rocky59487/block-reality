package com.blockreality.core.render;

import com.blockreality.api.BeamDisplayField;
import com.blockreality.api.geom.Vec3d;
import java.util.ArrayList;
import java.util.List;

/** Splits a convex surface tile at sample discontinuities, retaining independent values on either side. */
public final class BeamSurfacePatch {
    private BeamSurfacePatch() { }
    public record Vertex(Vec3d positionMm, double sigmaMpa) { }
    private record Point(Vec3d position, double xMm) { }

    public static List<List<Vertex>> sample(BeamDisplayField field, List<Vec3d> polygon, double blockHalfMm) {
        if (polygon.size() < 3) throw new IllegalArgumentException("surface requires a polygon");
        if (!Double.isFinite(blockHalfMm) || blockHalfMm <= 0) throw new IllegalArgumentException("invalid surface magnification");
        List<List<Point>> parts = List.of(polygon.stream().map(p -> new Point(p, field.longitudinalMm(p))).toList());
        for (double cut : field.breaksMm()) {
            List<List<Point>> next = new ArrayList<>();
            for (var part : parts) {
                double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
                for (Point p : part) { min = Math.min(min, p.xMm); max = Math.max(max, p.xMm); }
                if (!(min < cut && cut < max)) { next.add(part); continue; }
                next.add(clip(part, cut, true)); next.add(clip(part, cut, false));
            }
            parts = next;
        }
        List<List<Vertex>> result = new ArrayList<>(parts.size());
        for (var part : parts) {
            double mid = 0;
            for (Point p : part) mid += p.xMm / part.size();
            List<Vertex> vertices = new ArrayList<>(part.size());
            for (Point p : part) {
                // Intersections retain their exact station coordinate independently of world-vector rounding.
                double x = p.xMm;
                Vec3d d = p.position.minus(field.originMm());
                vertices.add(new Vertex(p.position, field.sampleMpa(x, d.dot(field.ay()) / blockHalfMm,
                        d.dot(field.az()) / blockHalfMm, x > mid ? BeamDisplayField.Side.FIRST : BeamDisplayField.Side.LAST)));
            }
            result.add(List.copyOf(vertices));
        }
        return List.copyOf(result);
    }
    private static List<Point> clip(List<Point> points, double cut, boolean below) {
        List<Point> out = new ArrayList<>();
        Point a = points.get(points.size() - 1); double da = a.xMm - cut;
        for (Point b : points) {
            double db = b.xMm - cut;
            boolean ia = below ? da <= 0 : da >= 0, ib = below ? db <= 0 : db >= 0;
            if (ia != ib) out.add(new Point(a.position.plus(b.position.minus(a.position).scaled(da / (da - db))), cut));
            if (ib) out.add(b);
            a = b; da = db;
        }
        return List.copyOf(out);
    }
}
