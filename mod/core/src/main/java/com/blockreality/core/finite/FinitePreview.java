package com.blockreality.core.finite;

import com.blockreality.core.bsi.BsiCorot;
import java.util.Objects;

/**
 * A private preview handle (design §5.4): a fully isolated {@link FiniteSession} that
 * replays the durable vocabulary, world and — when the caller holds one — the committed
 * checkpoint, then may run {@code solve} or {@code arcAdvance} purely to read the result.
 *
 * <p>The handle never mutates the authority session, never touches the store or the
 * registry, and authorizes nothing: the returned {@link FiniteResult} carries a
 * worker-live token minted inside this private session, useless anywhere else.
 * {@link #close()} discards it; there is no path back into durable state.
 *
 * <p>Any restore or analysis failure refuses the inner session (fail-closed) and is
 * reported by {@link #step()}/{@link #refusal()}; the preview never throws on the wire's
 * behalf.
 */
public final class FinitePreview implements AutoCloseable {

    private final FiniteSession session;

    private FinitePreview(FiniteSession session) { this.session = session; }

    /**
     * Replay the durable state into a private session. {@code sessionGeneration} must be
     * distinct from the authority session's: token identities are only meaningful inside
     * one generation, and aliasing them would let a stale preview token pose as live.
     *
     * <p>{@code history} may be null when the domain has no committed checkpoint; in that
     * case the preview analyses the fresh world. Returns null when any restore step fails —
     * nothing was registered and nothing is left running.
     */
    public static FinitePreview restore(FiniteLane lane, FiniteDomainId domain, FiniteSource source,
                                        long worldRevision, FiniteCheckpointRef history, byte[] blob,
                                        long sessionGeneration) {
        Objects.requireNonNull(lane);
        if (history != null) Objects.requireNonNull(blob);
        var session = FiniteSession.attach(lane, domain, source, sessionGeneration);
        if (!session.declareVocabulary() || !session.declareWorld(worldRevision)
                || (history != null && session.importCheckpoint(history, blob) == null)) {
            session.close();
            return null;
        }
        return new FinitePreview(session);
    }

    public FiniteResult solve() { return session.solve(); }

    public FiniteResult arcAdvance(BsiCorot.ArcOptions options) { return session.arcAdvance(options); }

    /** The inner session's lifecycle step — DISABLED means the preview refused and is dead. */
    public FiniteSession.Step step() { return session.step(); }
    public String refusal() { return session.refusal(); }
    public FiniteDomainId domain() { return session.domain(); }

    @Override public void close() { session.close(); }
}
