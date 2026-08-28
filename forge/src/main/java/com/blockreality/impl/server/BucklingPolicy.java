package com.blockreality.impl.server;

/**
 * The one rule for when the buckling screen runs, extracted so its boundaries are
 * testable: the eigensolve is cubic (72.8 s at 1000 nodes measured), so past the
 * configured size it is SKIPPED — deliberately and visibly, never as a hang. The
 * D/C strength screen is unaffected either way.
 */
public final class BucklingPolicy {

    private BucklingPolicy() { }

    /**
     * @param blockCount structural blocks in this request
     * @param limit      config value; {@code 0} means never compute buckling
     * @return whether the request should ask for the buckling screen
     */
    public static boolean enabled(int blockCount, int limit) {
        return limit > 0 && blockCount <= limit;
    }
}
