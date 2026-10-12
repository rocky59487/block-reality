package com.blockreality.core.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.transaction.ConstructionTransaction;
import com.blockreality.core.world.ConstructionDeclaration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The journal-owned bridge from a durable artifact identity to a positive signed-i64
 * {@code nativeOwnerId} (design §3.2).
 *
 * <p>A binding is minted once and never reassigned. Manufactured pieces carry their
 * {@link ManufacturedPiece}-assigned UUID; legacy adoptions carry the old observed
 * provenance key and are permanently marked {@link Origin#LEGACY_NO_REFUND}: the id can
 * never become refundable inventory again. Adjacent same-material pieces keep separate
 * bindings; nothing here merges owners by connectivity.
 */
public record ArtifactBinding(long ownerId, Origin origin, UUID pieceId, String legacyKey,
                              UUID birthTransaction, ConstructionDeclaration declaration,
                              List<BlockKey> cells, Disposition disposition, UUID retiredBy) {

    /** How the owner id was minted. {@code LEGACY_NO_REFUND} is the explicit adoption marker. */
    public enum Origin { MANUFACTURED, LEGACY_NO_REFUND }

    /** Whether the binding still authorizes ownership in the current world source. */
    public enum Disposition { ACTIVE, RETIRED }

    static final Comparator<BlockKey> CELL_ORDER = Comparator.comparingInt(BlockKey::x)
            .thenComparingInt(BlockKey::y).thenComparingInt(BlockKey::z);
    static final int MAX_LEGACY_KEY = 128;

    public ArtifactBinding {
        if (ownerId <= 0) throw invalid();
        Objects.requireNonNull(origin);
        Objects.requireNonNull(birthTransaction);
        FiniteDomainId.nonzero(birthTransaction);
        declared(declaration);
        Objects.requireNonNull(disposition);
        boolean manufactured = origin == Origin.MANUFACTURED;
        if (manufactured != (pieceId != null) || manufactured == (legacyKey != null)) throw invalid();
        if (pieceId != null) FiniteDomainId.nonzero(pieceId);
        if (legacyKey != null) {
            if (legacyKey.isEmpty() || legacyKey.length() > MAX_LEGACY_KEY) throw invalid();
            for (int i = 0; i < legacyKey.length(); i++) {
                char c = legacyKey.charAt(i);
                if (c < 32 || c > 126) throw invalid();
            }
        }
        if ((disposition == Disposition.RETIRED) != (retiredBy != null)) throw invalid();
        if (retiredBy != null) FiniteDomainId.nonzero(retiredBy);
        cells = canonicalCells(cells);
    }

    /** The minted binding for a newly manufactured piece. */
    public static ArtifactBinding manufactured(long ownerId, UUID pieceId, UUID birthTransaction,
                                               ConstructionDeclaration declaration, List<BlockKey> cells) {
        return new ArtifactBinding(ownerId, Origin.MANUFACTURED, pieceId, null,
                birthTransaction, declaration, cells, Disposition.ACTIVE, null);
    }

    /** The one-time adoption of a pre-registry observed structure; never refundable. */
    public static ArtifactBinding legacy(long ownerId, String provenanceKey, UUID birthTransaction,
                                         ConstructionDeclaration declaration, List<BlockKey> cells) {
        return new ArtifactBinding(ownerId, Origin.LEGACY_NO_REFUND, null, provenanceKey,
                birthTransaction, declaration, cells, Disposition.ACTIVE, null);
    }

    public ArtifactBinding retired(UUID by) {
        FiniteDomainId.nonzero(by);
        if (disposition == Disposition.RETIRED) throw invalid();
        return new ArtifactBinding(ownerId, origin, pieceId, legacyKey, birthTransaction,
                declaration, cells, Disposition.RETIRED, by);
    }

    public boolean active() { return disposition == Disposition.ACTIVE; }

    /** The durable artifact id: the manufactured UUID, or the legacy key for adoptions. */
    public String durableId() { return pieceId != null ? pieceId.toString() : "legacy/" + legacyKey; }

    static List<BlockKey> canonicalCells(List<BlockKey> input) {
        Objects.requireNonNull(input);
        if (input.isEmpty() || input.size() > ConstructionTransaction.MAX_CELLS) throw invalid();
        var sorted = new TreeSet<BlockKey>(CELL_ORDER);
        for (BlockKey p : input) {
            Objects.requireNonNull(p);
            position(p);
            if (!sorted.add(p)) throw invalid();
        }
        return List.copyOf(sorted);
    }

    static void position(BlockKey p) {
        if (p.x() < -30000000 || p.x() >= 30000000 || p.z() < -30000000 || p.z() >= 30000000
                || p.y() < -2048 || p.y() >= 2048) throw invalid();
    }

    private static void declared(ConstructionDeclaration declaration) {
        Objects.requireNonNull(declaration);
        // A frame member must carry a declared axis; monolith/panel products may omit it.
        if (declaration.axis() < 0 && declaration.role() == ConstructionDeclaration.Role.FRAME) throw invalid();
    }

    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid finite artifact binding"); }
}
