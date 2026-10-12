package com.blockreality.impl.server.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.finite.ArtifactBinding;
import com.blockreality.core.world.ConstructionDeclaration;
import com.blockreality.impl.server.finite.FiniteRuntime.DomainObservation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

/**
 * MC-free world-observation seam for finite domain bootstrap: verify that every bound
 * cell still matches its durable declaration, gather face-sturdy non-structural
 * neighbours as supports, and count the chunks an adapter could not read — named,
 * never filled with air (design: incomplete observation waits on chunks).
 *
 * <p>The adapter must never force a chunk to load, never generate terrain and never
 * mutate the world; {@link World#cell} answers {@link Cell.Unloaded} instead.
 */
@ParametersAreNonnullByDefault
public final class FiniteObservation {

    private FiniteObservation() {}

    /** One live-cell probe; the adapter answers honestly and never force-loads. */
    public sealed interface Cell {
        /** The chunk covering this position is not loaded. */
        record Unloaded() implements Cell {}
        /** The position is outside the level's build height. */
        record Outside() implements Cell {}
        /** The position holds air. */
        record Air() implements Cell {}
        /** A structural block carrying its live declaration. */
        record Structural(ConstructionDeclaration declaration) implements Cell {}
        /** A non-structural, non-air block — support only when the contact face is sturdy. */
        record Other() implements Cell {}
    }

    /** A read-only world view for one dimension; every call is chunk-load-free. */
    public interface World {
        /** Live cell state at {@code position}. */
        Cell cell(BlockKey position);
        /** True when {@code position}'s face toward {@code towards} is sturdy. */
        boolean sturdy(BlockKey position, BlockKey towards);
    }

    /** Gathered observation outcome; {@link #observation()} is what the runtime commits on. */
    public record Gathered(Set<BlockKey> supports, int missingChunks, List<String> drift) {
        public Gathered {
            supports = Set.copyOf(Objects.requireNonNull(supports));
            drift = List.copyOf(Objects.requireNonNull(drift));
            if (missingChunks < 0) throw new IllegalArgumentException("negative missing count");
        }
        public boolean drifted() { return !drift.isEmpty(); }
        public DomainObservation observation() {
            return missingChunks == 0 ? DomainObservation.observed(supports)
                    : DomainObservation.waiting(missingChunks);
        }
    }

    private static final int[][] FACES = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Same (x,y,z) canonical order as {@code ArtifactBinding.CELL_ORDER} — deterministic support sets. */
    private static final java.util.Comparator<BlockKey> CELL_ORDER = java.util.Comparator
            .comparingInt(BlockKey::x).thenComparingInt(BlockKey::y).thenComparingInt(BlockKey::z);

    /**
     * Verify every active binding's cells against the live world and gather supports.
     * Drift is named per cell (expected declaration vs live state); unloaded chunks are
     * counted deduplicated; a sturdy {@link Cell.Other} face-neighbour becomes a support.
     */
    public static @Nonnull Gathered gather(List<ArtifactBinding> bindings, World world) {
        Objects.requireNonNull(bindings);
        Objects.requireNonNull(world);
        var owned = new HashSet<BlockKey>();
        var supports = new TreeSet<BlockKey>(CELL_ORDER);
        var missing = new HashSet<Long>();
        var drift = new ArrayList<String>();
        for (ArtifactBinding binding : bindings) {
            for (BlockKey cell : binding.cells()) owned.add(cell);
        }
        for (ArtifactBinding binding : bindings) {
            for (BlockKey cell : binding.cells()) {
                String at = "cell/" + cell.x() + "/" + cell.y() + "/" + cell.z();
                Cell live = world.cell(cell);
                if (live instanceof Cell.Unloaded) {
                    missing.add(chunkKey(cell));
                } else if (live instanceof Cell.Outside) {
                    drift.add(at + " outside build height");
                } else if (live instanceof Cell.Air) {
                    drift.add(at + " declared " + describe(binding.declaration()) + " but is air");
                } else if (live instanceof Cell.Other) {
                    drift.add(at + " declared " + describe(binding.declaration())
                            + " but holds a non-structural block");
                } else if (live instanceof Cell.Structural structural) {
                    if (!structural.declaration().equals(binding.declaration())) {
                        drift.add(at + " declared " + describe(binding.declaration())
                                + " but holds " + describe(structural.declaration()));
                    }
                }
                for (int[] face : FACES) {
                    BlockKey neighbour = new BlockKey(cell.x() + face[0],
                            cell.y() + face[1], cell.z() + face[2]);
                    if (owned.contains(neighbour)) continue;
                    Cell probe = world.cell(neighbour);
                    if (probe instanceof Cell.Unloaded) {
                        missing.add(chunkKey(neighbour));
                    } else if (probe instanceof Cell.Other && world.sturdy(neighbour, cell)) {
                        supports.add(neighbour);
                    }
                }
            }
        }
        return new Gathered(supports, missing.size(), drift);
    }

    private static long chunkKey(BlockKey position) {
        return ((long) (position.x() >> 4) << 32) | (position.z() >> 4) & 0xffff_ffffL;
    }

    private static String describe(ConstructionDeclaration declaration) {
        return declaration.material() + "/" + declaration.section() + "/" + declaration.axis();
    }
}
