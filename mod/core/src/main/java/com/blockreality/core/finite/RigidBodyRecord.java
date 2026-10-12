package com.blockreality.core.finite;

import com.blockreality.api.geom.BlockKey;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * W5 type placeholder (design §3): the server-persistent identity of one rigid body.
 * A body id is a positive signed-i64 minted by the server — never an entity id, never
 * reused. No motion, geometry or integration behaviour is implemented here.
 */
public record RigidBodyRecord(long bodyId, UUID birthDomain, UUID birthNamespace,
                              UUID birthRequest, int birthGroup, int fragment,
                              List<UUID> parents, List<BlockKey> sourceCells,
                              FiniteBlobRef portableSource, String geometryHash,
                              FiniteBlobRef materialArchive, int lifeStatus) {
    public RigidBodyRecord {
        if (bodyId <= 0) throw new IllegalArgumentException("invalid rigid body id");
        FiniteDomainId.nonzero(birthDomain); FiniteDomainId.nonzero(birthNamespace); FiniteDomainId.nonzero(birthRequest);
        if (birthGroup < 0 || fragment < 0 || lifeStatus < 0) throw new IllegalArgumentException("invalid rigid body field");
        parents = List.copyOf(parents);
        for (UUID parent : parents) FiniteDomainId.nonzero(parent);
        sourceCells = List.copyOf(sourceCells);
        Objects.requireNonNull(portableSource);
        FiniteBasis.hash(geometryHash);
        Objects.requireNonNull(materialArchive);
    }
}
