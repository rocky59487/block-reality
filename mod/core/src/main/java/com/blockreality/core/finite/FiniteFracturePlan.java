package com.blockreality.core.finite;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * W4 type placeholder (design §3): the complete durable description of one finite fracture
 * transaction. Fields exist so W2 journals/sources can already name them; no fracture
 * behaviour is implemented here — prepare/finish wire, receipts and archives are W4.
 */
public record FiniteFracturePlan(UUID request, UUID actor, String actorProvenance,
                                 FiniteBasis before, FiniteBasis after,
                                 byte[] prepareRequest, byte[] receipt,
                                 FiniteBlobRef afterSource, FiniteBlobRef afterCheckpoint,
                                 List<Long> bornBodies, List<Long> retiredOwnership,
                                 byte[] loadLedger, String planHash) {
    public FiniteFracturePlan {
        Objects.requireNonNull(request); Objects.requireNonNull(actor);
        FiniteDomainId.nonzero(request); FiniteDomainId.nonzero(actor);
        FiniteCodec.text(actorProvenance, 64);
        Objects.requireNonNull(before); Objects.requireNonNull(after);
        prepareRequest = prepareRequest.clone(); receipt = receipt.clone(); loadLedger = loadLedger.clone();
        Objects.requireNonNull(afterSource); Objects.requireNonNull(afterCheckpoint);
        bornBodies = List.copyOf(bornBodies); retiredOwnership = List.copyOf(retiredOwnership);
        for (long id : bornBodies) if (id <= 0) throw new IllegalArgumentException("invalid born body id");
        for (long id : retiredOwnership) if (id <= 0) throw new IllegalArgumentException("invalid retired owner id");
        FiniteBasis.hash(planHash);
    }
}
