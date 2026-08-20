package com.blockreality.core;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.WorldRevision;

/**
 * The gate every analysis result has to pass before it is allowed to mean anything.
 *
 * <p>Analysis is asynchronous, so a result always describes a world that existed when the
 * request was sent. If the player has edited since, that world is gone. The rule is not
 * "prefer the newer result" — it is that a result for a superseded revision <strong>is not
 * evidence about the current world at all</strong>, and using it to trigger a collapse
 * would break a structure the player has already fixed.
 *
 * <p>Display and commit differ only in what they do with a stale result: the display
 * track may keep drawing it while labelled stale, the commit track must discard it
 * (D-007, DEMO_V0 §1).
 *
 * <p>Thread-safe. The gate is driven from the server thread, but its callers arrive
 * through Forge events with no thread assertion anywhere on the path, and
 * {@code bump()} is a read-modify-write: a lost update here would let a result for a
 * superseded world pass {@code acceptForCommit} — the exact non-determinism invariant
 * 6 exists to forbid. The lock is uncontended in normal play and costs nothing.
 */
public final class RevisionGate {

    private WorldRevision current = WorldRevision.ZERO;
    private AnalysisResult lastAccepted;
    private long rejected;

    /** Call on every world edit. Everything in flight becomes stale at this moment. */
    public synchronized WorldRevision bump() {
        current = current.next();
        return current;
    }

    public synchronized WorldRevision current() { return current; }

    public synchronized long rejectedCount() { return rejected; }

    /** The newest result that was current when it arrived; may be null. */
    public synchronized AnalysisResult lastAccepted() { return lastAccepted; }

    public synchronized boolean isStale(AnalysisResult r) {
        return r.revision().value() != current.value();
    }

    /**
     * Commit track: accept only a usable result for the current revision.
     *
     * @return true if the result may be acted on — failure events, world changes, damage
     */
    public synchronized boolean acceptForCommit(AnalysisResult r) {
        if (isStale(r) || !r.isUsable()) {
            rejected++;
            return false;
        }
        lastAccepted = r;
        return true;
    }

    /**
     * Display track: a stale result is still worth drawing, but the caller must label it.
     *
     * <p>Production caller: {@code StructureManager.apply}, which routes UNAVAILABLE to
     * the engine-status packet and MECHANISM to a broadcast the commit gate alone would
     * have suppressed (a mechanism verdict is unusable but true). The STALE labelling
     * itself happens on the client, driven by the analysis-pending signal — the client
     * has packets, not {@link AnalysisResult}s, so this method cannot run there.
     *
     * @return how the overlay should present this result
     */
    public Display displayState(AnalysisResult r) {
        if (!r.ok()) return Display.UNAVAILABLE;
        // MECHANISM means there is NOTHING to draw, not merely that something in the world
        // is unrestrained. The engine solves each structure separately, so one unsupported
        // shed leaves every other building with real numbers — and blanking the overlay for
        // all of them was the whole reason a second structure appeared to kill the mod.
        if (r.allSingular()) return Display.MECHANISM;
        return isStale(r) ? Display.STALE : Display.CURRENT;
    }

    public enum Display {
        /** Matches the world on screen. */
        CURRENT,
        /** Real numbers for a world that has since changed — draw, but say so. */
        STALE,
        /** Not a structure. There is no stress field to draw, and inventing one would lie. */
        MECHANISM,
        /** The engine could not answer. Draw nothing. */
        UNAVAILABLE
    }
}
