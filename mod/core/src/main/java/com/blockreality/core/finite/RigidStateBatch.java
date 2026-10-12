package com.blockreality.core.finite;

import java.util.Objects;
import java.util.UUID;

/**
 * W5 type placeholder (design §3): one complete durable scene batch — every body's 128-byte
 * state, the 104-byte report and the sleep/wake id sets of one step. The durable sequence
 * pairs with the scene's alternating slots; nothing here predicts, integrates or substitutes
 * entity ids for body ids.
 */
public record RigidStateBatch(UUID sceneDomain, long sceneRevision, long stepSequence,
                              UUID request, String inputStateHash,
                              byte[] states, byte[] report, long[] sleepingIds, long[] wokenIds,
                              long durableSequence) {
    public RigidStateBatch {
        FiniteDomainId.nonzero(sceneDomain); FiniteDomainId.nonzero(request);
        FiniteBasis.hash(inputStateHash);
        if (sceneRevision < 0 || stepSequence < 0 || durableSequence < 0) throw new IllegalArgumentException("invalid state batch clock");
        states = states.clone(); report = report.clone();
        sleepingIds = sleepingIds.clone(); wokenIds = wokenIds.clone();
        for (long id : sleepingIds) if (id <= 0) throw new IllegalArgumentException("invalid sleeping body id");
        for (long id : wokenIds) if (id <= 0) throw new IllegalArgumentException("invalid woken body id");
        Objects.requireNonNull(states); Objects.requireNonNull(report);
    }
}
