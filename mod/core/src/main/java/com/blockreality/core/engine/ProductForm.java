package com.blockreality.core.engine;

import java.util.Optional;

/** Presentation/interaction bounds in block units. Never used to derive engine input or physics. */
public record ProductForm(Box box, double depthHalfMetres, double widthHalfMetres, boolean resolved) {
    public ProductForm {
        java.util.Objects.requireNonNull(box);
        if (!Double.isFinite(depthHalfMetres) || depthHalfMetres <= 0
                || !Double.isFinite(widthHalfMetres) || widthHalfMetres <= 0)
            throw new IllegalArgumentException("invalid product presentation extents");
    }
    public static final Box CELL = new Box(0, 0, 0, 1, 1, 1);
    public static final ProductForm UNRESOLVED = new ProductForm(CELL, .5, .5, false);

    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Box {
            double[] lo = {minX, minY, minZ}, hi = {maxX, maxY, maxZ};
            for (int i = 0; i < 3; i++)
                if (!Double.isFinite(lo[i]) || !Double.isFinite(hi[i]) || lo[i] < 0 || hi[i] > 1 || lo[i] >= hi[i])
                    throw new IllegalArgumentException("invalid product presentation bounds");
        }
        public double min(int axis) { return switch (axis) { case 0 -> minX; case 1 -> minY; case 2 -> minZ; default -> throw new IllegalArgumentException("axis"); }; }
        public double max(int axis) { return switch (axis) { case 0 -> maxX; case 1 -> maxY; case 2 -> maxZ; default -> throw new IllegalArgumentException("axis"); }; }
        public double half(int axis) { return (max(axis) - min(axis)) * .5; }
        /** Whether a face-adjacent box covers this entire face; partial coverage stays drawable. */
        public boolean coveredBy(Box neighbor, int axis, int sign) {
            if (sign != -1 && sign != 1) throw new IllegalArgumentException("face sign");
            if (sign == 1 ? max(axis) != 1 || neighbor.min(axis) != 0 : min(axis) != 0 || neighbor.max(axis) != 1)
                return false;
            for (int other = 0; other < 3; other++)
                if (other != axis && (neighbor.min(other) > min(other) || neighbor.max(other) < max(other))) return false;
            return true;
        }
    }

    public static ProductForm of(Optional<ProductGeometry> geometry, int axis) {
        if (axis < -1 || axis > 2) throw new IllegalArgumentException("placement axis");
        if (axis == -1 || geometry.isEmpty()) return UNRESOLVED;
        var product = geometry.get();
        if (product.kind() == ProductGeometry.Kind.SOLID_CELL || product.kind() == ProductGeometry.Kind.PANEL)
            return new ProductForm(CELL, .5, .5, true); // A panel placement axis is not its normal.
        if (product.kind() != ProductGeometry.Kind.RECTANGULAR_MEMBER) return UNRESOLVED;
        double width = product.dimensionsMetres().get(0), depth = product.dimensionsMetres().get(1);
        if (width > 1 || depth > 1) return UNRESOLVED; // No guessed/capped dimensions for unsupported display shapes.
        double x = axis == 0 ? 1 : axis == 1 ? depth : width;
        double y = axis == 1 ? 1 : depth;
        double z = axis == 2 ? 1 : width;
        return new ProductForm(new Box((1-x)/2, (1-y)/2, (1-z)/2, (1+x)/2, (1+y)/2, (1+z)/2),
                depth/2, width/2, true);
    }
}
