package com.blockreality.core.sidecar;

import java.nio.file.Path;

/**
 * Sidecar policy knobs.
 *
 * @param executable      path to {@code br-sidecar}
 * @param requestTimeoutMs how long to wait for one reply before declaring the process
 *                         wedged. Generous by default: D-008 puts a building-scale solve
 *                         near 100 ms, and this budget is for detecting a hang, not for
 *                         enforcing a frame time.
 * @param maxRestarts     consecutive failed restarts before analysis is disabled for the
 *                         session. Retrying forever against a genuinely broken binary
 *                         just spawns processes.
 * @param backoffBaseMs   first backoff delay; doubles each attempt (2s, 4s, 8s, 16s).
 */
public record SidecarConfig(Path executable, long requestTimeoutMs, int maxRestarts, long backoffBaseMs) {

    public static SidecarConfig of(Path executable) {
        return new SidecarConfig(executable, 5_000, 4, 2_000);
    }

    public long backoffMs(int attempt) {
        int a = Math.max(0, Math.min(attempt, 16));
        return backoffBaseMs << a;
    }
}
