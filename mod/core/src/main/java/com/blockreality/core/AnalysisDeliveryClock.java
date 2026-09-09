package com.blockreality.core;

import java.util.UUID;

/** Client-side ordering for one network connection. This owns delivery identity, not physics identity. */
public final class AnalysisDeliveryClock {
    private long sequence, worldRevision = -1, resultRevision = -1;
    private UUID source;
    private String dimension = "";

    /** Travel clears the visible binding but preserves the connection's high-water sequence. */
    public void leaveDimension() {
        source = null; dimension = ""; worldRevision = -1; resultRevision = -1;
    }

    /** A new server connection has its own ordering domain. */
    public void resetConnection() { leaveDimension(); sequence = 0; }

    /** A negative resultRevision means this update is a notice without a result payload. */
    public boolean accept(String activeDimension, String dimension, UUID source, long sequence,
                          long worldRevision, boolean bootstrap, long resultRevision) {
        if (activeDimension == null || dimension == null || source == null) return false;
        if (!activeDimension.equals(dimension)) return false;
        if (sequence <= this.sequence || worldRevision < 0 || resultRevision < -1
                || resultRevision > worldRevision) return false;
        boolean sameSource = source.equals(this.source) && dimension.equals(this.dimension);
        if (!sameSource && !bootstrap) return false;
        if (sameSource && (worldRevision < this.worldRevision
                || resultRevision >= 0 && resultRevision < this.resultRevision)) return false;
        // Every check precedes mutation: a rejected packet cannot advance the watermark.
        if (!sameSource) this.resultRevision = -1;
        this.source = source; this.dimension = dimension; this.sequence = sequence;
        this.worldRevision = worldRevision;
        if (resultRevision >= 0) this.resultRevision = resultRevision;
        return true;
    }

    public long sequence() { return sequence; }
    public long worldRevision() { return worldRevision; }
    public long resultRevision() { return resultRevision; }
    public UUID source() { return source; }
    public String dimension() { return dimension; }
}
