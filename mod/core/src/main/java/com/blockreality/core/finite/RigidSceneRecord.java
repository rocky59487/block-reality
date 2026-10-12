package com.blockreality.core.finite;

import java.util.Objects;
import java.util.UUID;

/**
 * W5 type placeholder (design §3): the independent motion scene clock and hashes.
 * {@code sceneRevision} counts terrain/body-set/source changes on its own axis and is
 * deliberately not bound to the structural worldRevision. No scene behaviour is implemented.
 */
public record RigidSceneRecord(UUID sceneDomain, long sceneRevision, String bodySetHash,
                               String terrainCoverageHash, UUID topologyTransaction,
                               String sceneSourceHash, long motionGeneration) {
    public RigidSceneRecord {
        FiniteDomainId.nonzero(sceneDomain); FiniteDomainId.nonzero(topologyTransaction);
        FiniteBasis.hash(bodySetHash); FiniteBasis.hash(terrainCoverageHash); FiniteBasis.hash(sceneSourceHash);
        if (sceneRevision < 0 || motionGeneration < 0) throw new IllegalArgumentException("invalid scene clock");
    }
}
