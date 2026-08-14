package com.blockreality.api.spi;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Holds the registered effect handlers and enforces the contract on their behalf.
 *
 * <p>A handler that throws is <strong>quarantined</strong>: recorded, disabled, and never
 * called again this session. It is not retried, because a handler that throws once on a
 * crushing event will throw on the next one too, and an exception per failure event is a
 * log flood on top of a broken effect.
 *
 * <p>{@link #dispatchCrushing} never throws. That is the whole point — it sits directly
 * downstream of world mutation, and an exception escaping it would mean the world had
 * been changed but the transaction never completed.
 */
public final class EffectHandlerRegistry {

    private final List<IFractureEffectHandler> handlers = new CopyOnWriteArrayList<>();
    private final Map<String, String> quarantined = new ConcurrentHashMap<>();
    private final BiConsumer<String, Throwable> log;

    /** @param log called with (handlerId, error) when a handler is quarantined */
    public EffectHandlerRegistry(BiConsumer<String, Throwable> log) {
        this.log = log == null ? (id, t) -> { } : log;
    }

    public void register(IFractureEffectHandler h) {
        if (h != null && !quarantined.containsKey(h.handlerId())) handlers.add(h);
    }

    public void unregister(IFractureEffectHandler h) { handlers.remove(h); }

    /** Ids of handlers disabled this session, with the reason. */
    public Map<String, String> quarantined() { return Map.copyOf(quarantined); }

    public int activeCount() { return handlers.size(); }

    /**
     * @return {@code true} if at least one handler ran without throwing, so the caller
     *         knows whether to fall back to the built-in effect
     */
    public boolean dispatchCrushing(CrushingDescription d) {
        boolean anyRan = false;
        for (IFractureEffectHandler h : handlers) {
            try {
                h.onCrushing(d);
                anyRan = true;
            } catch (Throwable t) {
                // Throwable, not Exception: a handler that manages a StackOverflowError
                // or a linkage error from a version-skewed dependency must still not be
                // able to abort the transaction.
                handlers.remove(h);
                quarantined.put(h.handlerId(), String.valueOf(t));
                log.accept(h.handlerId(), t);
            }
        }
        return anyRan;
    }
}
