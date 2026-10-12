package com.blockreality.core.finite;

import com.blockreality.core.transaction.ConstructionTransaction;
import com.blockreality.core.transaction.ConstructionTransaction.Change;
import com.blockreality.core.transaction.ConstructionTransaction.Receipt;
import com.blockreality.core.transaction.ConstructionTransaction.Request;
import java.io.IOException;
import java.util.List;

/**
 * The W4-A fracture host contract (FINITE_FRACTURE_TRANSACTION §6.1). The interface is
 * shaped so a Forge implementation can directly carry the §8.2/§8.3 semantics; the core
 * default has no world mutation and answers {@link #prepareImages} with no changes.
 *
 * <p>Resource ownership is partitioned: keys under {@code finite/} and {@code fracture/}
 * are the finite authority's durable evidence — the driver serves them from the
 * construction journal fold and a Forge host must never claim them (the driver refuses
 * host images in that namespace). Everything else is the host's own participant world:
 * cells, fragment bodies, whatever the caller's {@link #prepareImages} names.
 *
 * <p>{@link #revision()} is the persistent world-revision clock — the durable basis the
 * request's {@code baseRevision} binds; a transaction moves it exactly once, at durable
 * commit. Every {@code request()} native call is confined to the private lane returned by
 * {@link #openLane()}: native work on the authority session is forbidden by construction.
 */
public interface FiniteFractureHost {

    /** Server-thread/context assertion — every driver entry point calls it first. */
    void checkAccess();

    /** The persistent revision clock (committed world revision). Must not move in {@code prepareImages}. */
    long revision() throws IOException;

    /** The host's committed value for one of its own resources; never a {@code finite/} key. */
    ConstructionTransaction.Value read(String resource) throws Exception;

    /** Persist/verify live before-images of the host's own resources, no visible changes. */
    void checkpoint(List<String> resources) throws Exception;

    /** Apply one host participant image — durable, never visible before COMMITTED. */
    void write(String resource, ConstructionTransaction.Value value) throws Exception;

    /** Flush the listed host resources (and restores) all the way to storage. */
    void flush(List<String> resources) throws Exception;

    /**
     * The host's participant images for this fracture — world cell removal, fragment
     * body entities, inventory effects. Pure capture: no permanent ids, no publication,
     * no native calls. The core default returns {@link List#of()}.
     */
    List<Change> prepareImages(Request request, FiniteFracture.Staged staged) throws Exception;

    /**
     * The single visible publication of a durable COMMITTED decision. Called exactly
     * once, after the journal committed and the registry folded the new authority.
     */
    void publish(Receipt receipt, FiniteFracturePlan plan) throws Exception;

    /** Rebuild visible state from committed authority during startup recovery. */
    void publishRecoveredState() throws Exception;

    /** A fresh private finite lane for staging — never the authority session's lane. */
    FiniteLane openLane() throws IOException;

    /** A fresh session generation distinct from the authority session's. */
    long sessionGeneration();

    /** The engine build string stamped into durable checkpoint slots. */
    String engineBuild();

    /** The pinned contract hash stamped into durable checkpoint slots. */
    String contractSha256();
}
