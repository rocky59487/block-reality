package com.blockreality.impl.server;

import com.blockreality.api.geom.BlockKey;
import java.util.HashSet;
import java.util.Set;

/** Admission for a whole native domain: unreadable input cannot become a shorter solve. */
final class InputCoverage {
    private final Set<BlockKey> unreadable = new HashSet<>();
    void missing(BlockKey pos) { unreadable.add(pos); }
    int missingCount() { return unreadable.size(); }
    boolean complete() { return unreadable.isEmpty(); }
    String detail() { return "Waiting for unloaded input: " + missingCount() + " cell(s)"; }
    /** Return false without invoking native dispatch when even one observation is missing. */
    boolean dispatch(Runnable submit) {
        if (!complete()) return false;
        submit.run(); return true;
    }
}
