package com.blockreality.core.transaction;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.world.ConstructionDeclaration;
import com.blockreality.core.engine.GameWorldSnapshot;
import java.util.*;
import static com.blockreality.core.transaction.ConstructionTransaction.MAX_CELLS;

/** Transaction-born identity and ownership only. Never a solver element or a physical damage state. */
public record ManufacturedPiece(UUID id, UUID birthTransaction, UUID actor, String dimension,
                                ConstructionDeclaration declaration, long birthOrder, long birthRevision,
                                Status status, List<BlockKey> cells) {
    public enum Status { INTACT, EDITED, RETIRED }
    static final Comparator<BlockKey> CELL_ORDER = Comparator.comparingInt(BlockKey::x)
            .thenComparingInt(BlockKey::y).thenComparingInt(BlockKey::z);

    public ManufacturedPiece {
        Objects.requireNonNull(id); Objects.requireNonNull(birthTransaction); Objects.requireNonNull(actor);
        MetadataCodec.dimension(dimension); declared(declaration); Objects.requireNonNull(status);
        if (birthOrder < 1 || birthRevision < 1) throw invalid();
        cells = canonicalCells(cells, status == Status.RETIRED);
        if ((status == Status.RETIRED) != cells.isEmpty()) throw invalid();
        if (status == Status.INTACT) validateFootprint(declaration, cells);
    }

    /** Explicit manufactured boundary. Constructing a plan does not allocate permanent IDs. */
    public record Plan(ConstructionDeclaration declaration, List<BlockKey> cells) {
        public Plan {
            declared(declaration); cells = canonicalCells(cells, false); validateFootprint(declaration, cells);
        }
    }

    ManufacturedPiece edited(Set<BlockKey> released, boolean retire) {
        List<BlockKey> remaining = retire ? List.of() : cells.stream().filter(p -> !released.contains(p)).toList();
        return new ManufacturedPiece(id, birthTransaction, actor, dimension, declaration, birthOrder, birthRevision,
                remaining.isEmpty() ? Status.RETIRED : Status.EDITED, remaining);
    }
    boolean sameBirth(ManufacturedPiece other) {
        return id.equals(other.id) && birthTransaction.equals(other.birthTransaction) && actor.equals(other.actor)
                && dimension.equals(other.dimension) && declaration.equals(other.declaration)
                && birthOrder == other.birthOrder && birthRevision == other.birthRevision;
    }
    static List<BlockKey> canonicalCells(List<BlockKey> input, boolean emptyAllowed) {
        Objects.requireNonNull(input);
        if (input.size() > MAX_CELLS || !emptyAllowed && input.isEmpty()) throw invalid();
        var sorted = new TreeSet<BlockKey>(CELL_ORDER);
        for (BlockKey p : input) {
            position(p); if (!sorted.add(p)) throw invalid();
        }
        return List.copyOf(sorted);
    }
    static void position(BlockKey p) {
        Objects.requireNonNull(p);
        if (p.x() < -30000000 || p.x() >= 30000000 || p.z() < -30000000 || p.z() >= 30000000
                || p.y() < -2048 || p.y() >= 2048) throw invalid();
    }
    private static void declared(ConstructionDeclaration declaration) {
        Objects.requireNonNull(declaration);
        if (declaration.axis() < 0) throw invalid();
        declaration.role(); // An unavailable product role cannot authorize manufacturing.
    }
    private static void validateFootprint(ConstructionDeclaration declaration, List<BlockKey> cells) {
        if (declaration.role() == ConstructionDeclaration.Role.FRAME) {
            BlockKey first = cells.get(0); int axis = declaration.axis();
            for (int i = 0; i < cells.size(); i++) {
                BlockKey p = cells.get(i);
                if (p.x() != first.x() + (axis == 0 ? i : 0)
                        || p.y() != first.y() + (axis == 1 ? i : 0)
                        || p.z() != first.z() + (axis == 2 ? i : 0)) throw invalid();
            }
        } else {
            var unseen = new HashSet<>(cells); var queue = new ArrayDeque<BlockKey>();
            queue.add(cells.get(0)); unseen.remove(cells.get(0));
            while (!queue.isEmpty()) for (BlockKey next : GameWorldSnapshot.neighbours(queue.remove()))
                if (unseen.remove(next)) queue.add(next);
            if (!unseen.isEmpty()) throw invalid();
        }
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid manufactured piece metadata"); }
}
