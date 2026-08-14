package com.blockreality.api;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.geom.Vec3d;

/**
 * A structural failure, decided by the solver and by nothing else.
 *
 * <p>The handover is one-way (DEMO_V0 §2): the solver decides whether, where and how a
 * failure happens; the rigid-body and effect systems take it from there and never feed
 * back into the same analysis. A rigid body does not get to argue about structural
 * strength.
 *
 * @param revision the revision the decision was made at. An event whose revision is no
 *                 longer current is dropped — that is the entire reason the token exists.
 */
public record FailureEvent(
        WorldRevision revision,
        Kind kind,
        int memberId,
        BlockKey at,
        Vec3d normal,
        float severity,
        GoverningFibre mode,
        long transactionId) {

    public enum Kind {
        /** Produces a persistent rigid member. Block Reality's own gameplay. */
        FRACTURE,
        /** Produces purely visual debris. May be lent to a third-party effect mod. */
        CRUSHING
    }
}
