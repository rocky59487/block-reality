package com.blockreality.api;

import java.util.Locale;

/**
 * What {@code bucklingFactor} is.
 *
 * <p>The number alone could not say. A factor of 0 meant "the request did not ask", "there
 * was nothing eligible to run the eigensolve on" and "it ran and found no positive
 * eigenvalue" all at once, and the reply carried no other buckling field to tell them
 * apart — measured, not assumed (N18, V04_PLAN 2.6). Until this existed, documentation was
 * barred from claiming the states were distinguishable, because they were not.
 */
public enum BucklingState {

    /** The eigensolve ran and produced a factor. {@code bucklingFactor > 0}. */
    COMPUTED("computed"),

    /** It ran on something and found no positive eigenvalue — nothing here can buckle. */
    NO_POSITIVE_EIGENVALUE("no-positive-eigenvalue"),

    /** It was asked for, but no structure had an element to run it on. */
    NOT_ELIGIBLE("not-eligible"),

    /** The request did not ask for it. */
    DISABLED_BY_REQUEST("disabled-by-request"),

    /**
     * The host declined to ask, on size. Never sent by the engine — from its side a refusal
     * to ask is just a refusal to ask — so the host substitutes this when it was the one
     * that turned the screen off, which is the only place that fact exists.
     */
    DISABLED_BY_SCALE("disabled-by-scale"),

    /** A state this build does not know, or a reply that carried none. */
    UNKNOWN("");

    private final String wire;

    BucklingState(String wire) { this.wire = wire; }

    public String wire() { return wire; }

    public String translationKey() {
        return "br.buckling." + name().toLowerCase(Locale.ROOT);
    }

    /** True when a factor is meaningful; every other state means there is no number. */
    public boolean hasFactor() { return this == COMPUTED; }

    public static BucklingState fromWire(String token) {
        if (token != null) {
            for (BucklingState s : values()) {
                if (!s.wire.isEmpty() && s.wire.equals(token)) return s;
            }
        }
        return UNKNOWN;
    }
}
