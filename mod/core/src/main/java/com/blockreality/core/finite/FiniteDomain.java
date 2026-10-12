package com.blockreality.core.finite;

import java.util.Objects;
import java.util.UUID;

/**
 * The committed descriptor of one finite domain (journal value behind
 * {@code finite/domain/<dimensionHash>}).
 *
 * <p>{@code nextOwnerId} is the single monotonic allocator for the domain's artifact
 * namespace: ids are handed out in order and are never reused, including after a binding
 * retires or the JVM restarts. {@code observationEpoch} is the non-physical observation
 * counter: chunk rescans and observation refreshes bump it without touching
 * {@code worldRevision} (design §6 clock separation).
 */
public record FiniteDomain(FiniteDomainId id, long nextOwnerId, long observationEpoch, UUID createdBy) {

    public FiniteDomain {
        Objects.requireNonNull(id);
        FiniteDomainId.nonzero(createdBy);
        if (nextOwnerId < 1 || observationEpoch < 0) throw FiniteDomainId.invalid();
    }

    public static FiniteDomain create(FiniteDomainId id, UUID request) {
        return new FiniteDomain(id, 1, 0, request);
    }

    public FiniteDomain observed() { return new FiniteDomain(id, nextOwnerId, observationEpoch + 1, createdBy); }

    FiniteDomain allocated(long owners) {
        if (owners < 1 || nextOwnerId > Long.MAX_VALUE - owners) throw FiniteDomainId.invalid();
        return new FiniteDomain(id, nextOwnerId + owners, observationEpoch, createdBy);
    }
}
