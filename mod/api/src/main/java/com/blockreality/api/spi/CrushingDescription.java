package com.blockreality.api.spi;

import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.Aabb;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.geom.Vec3d;

import java.util.List;

/**
 * A description of a crushing failure that <strong>has already happened</strong>.
 *
 * <p>Handed to third-party effect mods after Block Reality has finished changing the
 * world. Every field is a statement of fact about the past; there is nothing here a
 * handler could set to change an outcome, because by the time it is called there is no
 * outcome left to change (D-014).
 *
 * @param revision      the world revision this failure was decided at
 * @param dimension     dimension id, e.g. {@code minecraft:overworld}
 * @param region        the affected volume
 * @param direction     failure normal
 * @param severity      0..1
 * @param visualSource  the blocks as they looked <em>before</em> the change, so particles
 *                      and meshes can match the material that was actually there
 * @param transactionId unique per failure; a handler that is called twice with the same
 *                      id must treat the second call as a duplicate. Retries and server
 *                      restarts must not be able to double up (DEMO_V0 §3).
 */
public record CrushingDescription(
        WorldRevision revision,
        String dimension,
        Aabb region,
        Vec3d direction,
        float severity,
        List<BlockKey> visualSource,
        long transactionId) {

    public CrushingDescription {
        visualSource = List.copyOf(visualSource);
        severity = Math.max(0f, Math.min(1f, severity));
    }
}
