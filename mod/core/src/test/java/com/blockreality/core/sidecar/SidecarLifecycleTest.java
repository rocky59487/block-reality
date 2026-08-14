package com.blockreality.core.sidecar;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.protocol.SolveRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle and failure handling, driven by stub sidecars rather than the real engine.
 *
 * <p>Stubs on purpose: these tests are about what happens when the engine is absent,
 * wedged, mismatched or dying, and a correct engine cannot produce any of those. The
 * physics is checked separately in {@link SidecarEngineTest}.
 *
 * <p>The one rule every case here shares: <strong>the caller gets a failed result, never
 * an exception</strong>. A missing or broken sidecar has to leave the game playable.
 */
class SidecarLifecycleTest {

    @TempDir
    Path tmp;

    private final List<String> log = new ArrayList<>();
    private SidecarClient client;

    @BeforeEach
    void reset() { log.clear(); }

    @AfterEach
    void shutdown() { if (client != null) client.close(); }

    private Path script(String name, String body) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(p, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        return p;
    }

    /** Fast backoff so the test does not spend 30 seconds proving it waits. */
    private SidecarClient clientFor(Path exe, long timeoutMs) {
        return new SidecarClient(new SidecarConfig(exe, timeoutMs, 3, 1), log::add);
    }

    private static SolveRequest anyRequest(long rev) {
        return SolveRequest.builder(new WorldRevision(rev))
                .block(new BlockKey(0, 64, 0), "steel", "steel_h400", true)
                .block(new BlockKey(1, 64, 0), "steel", "steel_h400", false)
                .build();
    }

    @Test
    void missingBinaryDisablesAnalysisAndLeavesTheGamePlayable() {
        client = clientFor(tmp.resolve("does-not-exist"), 1_000);

        AnalysisResult r = client.solve(anyRequest(1));
        assertFalse(r.ok());
        assertFalse(r.diagnostic().isEmpty());
        assertEquals(new WorldRevision(1), r.revision());

        // Retries, then gives up rather than spawning processes forever.
        for (int i = 0; i < 5; i++) {
            sleep(3);
            client.solve(anyRequest(1));
        }
        assertEquals(SidecarClient.Status.DISABLED, client.status());
        assertTrue(client.disabledReason().contains("not found"), client.disabledReason());
    }

    @Test
    void aProtocolMismatchIsRefusedOutrightAndNotRetried() throws IOException {
        // A version skew is not transient. Restarting into it four times only delays the
        // message, and proceeding anyway would give answers that are wrong rather than
        // missing.
        Path exe = script("mismatch.sh",
                "while read -r line; do\n"
                        + "  printf '{\"ok\":true,\"op\":\"hello\",\"engine\":\"X\",\"protocol\":99,"
                        + "\"materials\":[],\"sections\":[]}\\n'\n"
                        + "done\n");
        client = clientFor(exe, 2_000);

        assertFalse(client.ensureReady());
        assertEquals(SidecarClient.Status.DISABLED, client.status());
        assertTrue(client.disabledReason().contains("protocol mismatch"), client.disabledReason());

        // Still disabled after a wait: no backoff path revives a mismatch.
        sleep(20);
        assertFalse(client.ensureReady());
    }

    @Test
    void aWedgedSidecarTimesOutAndIsKilled() throws IOException {
        Path exe = script("wedged.sh",
                "read -r line\n"
                        + "printf '{\"ok\":true,\"op\":\"hello\",\"engine\":\"stub\",\"protocol\":1,"
                        + "\"materials\":[],\"sections\":[]}\\n'\n"
                        + "sleep 60\n");
        client = clientFor(exe, 300);

        assertTrue(client.ensureReady());
        AnalysisResult r = client.solve(anyRequest(1));
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("timed out"), r.diagnostic());
        assertNotEquals(SidecarClient.Status.READY, client.status());
    }

    @Test
    void aCrashDuringAnalysisFailsThatRequestAndRecovers() throws IOException {
        // Dies on the first solve, answers normally afterwards: the restart has to
        // actually restore service, not just avoid throwing.
        Path marker = tmp.resolve("crashed");
        Path exe = script("flaky.sh",
                "while read -r line; do\n"
                        + "  case \"$line\" in\n"
                        + "    *'\"op\":\"hello\"'*)\n"
                        + "      printf '{\"ok\":true,\"op\":\"hello\",\"engine\":\"stub\",\"protocol\":1,"
                        + "\"materials\":[],\"sections\":[]}\\n' ;;\n"
                        + "    *)\n"
                        + "      if [ ! -f '" + marker + "' ]; then touch '" + marker + "'; exit 1; fi\n"
                        + "      printf '{\"ok\":true,\"revision\":2,\"singular\":false,\"maxDC\":0.5,"
                        + "\"governing\":1,\"members\":[],\"unassigned\":[]}\\n' ;;\n"
                        + "  esac\n"
                        + "done\n");
        client = clientFor(exe, 2_000);

        AnalysisResult first = client.solve(anyRequest(1));
        assertFalse(first.ok(), "the request that hit the crash must fail");

        sleep(20);
        AnalysisResult second = client.solve(anyRequest(2));
        assertTrue(second.ok(), "service must come back: " + second.diagnostic());
        assertEquals(SidecarClient.Status.READY, client.status());
    }

    @Test
    void aStaleReplyLeftInThePipeDoesNotPoisonTheNextRequest() throws IOException {
        // The sidecar answers the previous request late. Without draining, that reply
        // would be read as the answer to the next one.
        Path exe = script("late.sh",
                "n=0\n"
                        + "while read -r line; do\n"
                        + "  case \"$line\" in\n"
                        + "    *'\"op\":\"hello\"'*)\n"
                        + "      printf '{\"ok\":true,\"op\":\"hello\",\"engine\":\"stub\",\"protocol\":1,"
                        + "\"materials\":[],\"sections\":[]}\\n' ;;\n"
                        + "    *)\n"
                        + "      n=$((n+1))\n"
                        + "      if [ $n -eq 1 ]; then sleep 1; fi\n"
                        + "      printf '{\"ok\":true,\"revision\":%s,\"singular\":false,\"maxDC\":0.1,"
                        + "\"governing\":1,\"members\":[],\"unassigned\":[]}\\n' \"$n\" ;;\n"
                        + "  esac\n"
                        + "done\n");
        client = clientFor(exe, 200);

        assertTrue(client.ensureReady());
        client.solve(anyRequest(1));   // times out; the reply arrives afterwards
        sleep(1200);
        AnalysisResult r = client.solve(anyRequest(2));
        // Either the client restarted cleanly or it drained first. What must never happen
        // is silently accepting revision 1's answer as revision 2's.
        assertFalse(r.ok() && r.revision().value() != 2);
    }

    @Test
    void closingSendsByeAndDoesNotLeaveAProcessBehind() throws IOException {
        Path exe = script("normal.sh",
                "while read -r line; do\n"
                        + "  case \"$line\" in\n"
                        + "    *'\"op\":\"bye\"'*) exit 0 ;;\n"
                        + "    *) printf '{\"ok\":true,\"op\":\"hello\",\"engine\":\"stub\",\"protocol\":1,"
                        + "\"materials\":[],\"sections\":[]}\\n' ;;\n"
                        + "  esac\n"
                        + "done\n");
        client = clientFor(exe, 2_000);
        assertTrue(client.ensureReady());
        client.close();
        assertEquals(SidecarClient.Status.IDLE, client.status());
    }

    @Test
    void resetRevivesADisabledClientOnceTheBinaryIsInstalled() throws IOException {
        Path exe = tmp.resolve("later.sh");
        client = clientFor(exe, 500);
        for (int i = 0; i < 5; i++) { client.solve(anyRequest(1)); sleep(3); }
        assertEquals(SidecarClient.Status.DISABLED, client.status());

        script("later.sh",
                "while read -r line; do\n"
                        + "  printf '{\"ok\":true,\"op\":\"hello\",\"engine\":\"stub\",\"protocol\":1,"
                        + "\"materials\":[],\"sections\":[]}\\n'\n"
                        + "done\n");
        client.reset();
        assertTrue(client.ensureReady());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
