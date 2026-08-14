package com.blockreality.api;

/**
 * A monotonically increasing token stamped on every world edit, and carried by every
 * analysis request, result and failure event.
 *
 * <p>Its single job is to make <strong>stale results harmless</strong>. A result whose
 * revision is not the current one is not "old but usable" — it is expired, and applying
 * it is a bug (DEMO_V0 §1).
 *
 * <p>This is the mechanism the two-track precision split (D-007) was missing: the display
 * track may show stale numbers, but it must <em>label</em> them as stale rather than
 * pretend they are current, and only the commit track may act on them.
 */
public record WorldRevision(long value) implements Comparable<WorldRevision> {

    /** The revision of a world nothing has happened to yet. */
    public static final WorldRevision ZERO = new WorldRevision(0);

    public WorldRevision {
        if (value < 0) throw new IllegalArgumentException("revision must be >= 0: " + value);
    }

    public WorldRevision next() { return new WorldRevision(value + 1); }

    public boolean isNewerThan(WorldRevision other) { return value > other.value; }

    @Override
    public int compareTo(WorldRevision o) { return Long.compare(value, o.value); }

    @Override
    public String toString() { return "rev" + value; }
}
