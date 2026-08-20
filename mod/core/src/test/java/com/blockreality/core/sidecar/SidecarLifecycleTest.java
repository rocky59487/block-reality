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

    // Copy-on-write: the sidecar's reader threads append concurrently with test
    // assertions that iterate.
    private final List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
    private SidecarClient client;

    @BeforeEach
    void reset() { log.clear(); }

    @AfterEach
    void shutdown() { if (client != null) client.close(); }

    private Path script(String name, String body) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                !System.getProperty("os.name", "").toLowerCase().contains("win"),
                "POSIX shell stub scripts are not supported on Windows");
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
                .block(new BlockKey(0, 64, 0), "steel", "steel_rect_200x400", true)
                .block(new BlockKey(1, 64, 0), "steel", "steel_rect_200x400", false)
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
                        + "\"materials\":[],\"sections\":[],\"plates\":[]}\\n'\n"
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
                        + "\"materials\":[],\"sections\":[],\"plates\":[]}\\n'\n"
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
                        + "\"materials\":[],\"sections\":[],\"plates\":[]}\\n' ;;\n"
                        + "    *)\n"
                        + "      if [ ! -f '" + marker + "' ]; then touch '" + marker + "'; exit 1; fi\n"
                        // Schema-complete on purpose: the strict decoder (#31) treats a
                        // reply that claims ok but lacks required fields as a protocol
                        // desync, and this test is about crash recovery, not schema.
                        + "      printf '{\"ok\":true,\"revision\":2,\"singular\":false,"
                        + "\"islands\":1,\"singularIslands\":0,\"equilibrium\":{\"residual\":0},"
                        + "\"maxDC\":0.5,\"governing\":1,\"members\":[],\"shells\":[],"
                        + "\"unassigned\":[]}\\n' ;;\n"
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
                        + "\"materials\":[],\"sections\":[],\"plates\":[]}\\n' ;;\n"
                        + "    *)\n"
                        + "      n=$((n+1))\n"
                        + "      if [ $n -eq 1 ]; then sleep 1; fi\n"
                        + "      printf '{\"ok\":true,\"revision\":%s,\"singular\":false,"
                        + "\"islands\":1,\"singularIslands\":0,\"equilibrium\":{\"residual\":0},"
                        + "\"maxDC\":0.1,\"governing\":1,\"members\":[],\"shells\":[],"
                        + "\"unassigned\":[]}\\n' \"$n\" ;;\n"
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
        // And the service must survive: one late reply bricking the client into
        // DISABLED would trade a poisoned answer for no answers ever (TEST-11).
        assertNotEquals(SidecarClient.Status.DISABLED, client.status());
    }

    @Test
    void closingSendsByeAndDoesNotLeaveAProcessBehind() throws IOException {
        // The stub proves its own exit by writing a marker when bye arrives. The old
        // assertion checked client.status() alone, which said nothing about the child
        // process — the test's name promised what no assertion held (TEST-11).
        Path marker = tmp.resolve("saw-bye");
        Path exe = script("normal.sh",
                "while read -r line; do\n"
                        + "  case \"$line\" in\n"
                        + "    *'\"op\":\"bye\"'*) touch '" + marker + "'; exit 0 ;;\n"
                        + "    *) printf '{\"ok\":true,\"op\":\"hello\",\"engine\":\"stub\",\"protocol\":1,"
                        + "\"materials\":[],\"sections\":[],\"plates\":[]}\\n' ;;\n"
                        + "  esac\n"
                        + "done\n");
        client = clientFor(exe, 2_000);
        assertTrue(client.ensureReady());
        client.close();
        assertEquals(SidecarClient.Status.CLOSED, client.status());
        long deadline = System.currentTimeMillis() + 2_000;
        while (!Files.exists(marker) && System.currentTimeMillis() < deadline) sleep(10);
        assertTrue(Files.exists(marker), "the child must have received bye and exited");
    }

    @Test
    void aClosedClientIsTerminalAndNeverRespawns() throws IOException {
        // CONC-3: close() used to reset the status to IDLE, so an in-flight solve's
        // ensureReady() happily started a NEW sidecar for a dimension that had already
        // unloaded — an orphan process nobody would ever close. The stub logs every
        // start, so a respawn is a second line in the log.
        Path starts = tmp.resolve("starts");
        Path exe = script("counting.sh",
                "echo started >> '" + starts + "'\n"
                        + "while read -r line; do\n"
                        + "  case \"$line\" in\n"
                        + "    *'\"op\":\"bye\"'*) exit 0 ;;\n"
                        + "    *) printf '{\"ok\":true,\"op\":\"hello\",\"engine\":\"stub\",\"protocol\":1,"
                        + "\"materials\":[],\"sections\":[],\"plates\":[]}\\n' ;;\n"
                        + "  esac\n"
                        + "done\n");
        client = clientFor(exe, 2_000);
        assertTrue(client.ensureReady());
        assertEquals(1, Files.readAllLines(starts).size());

        client.close();
        assertEquals(SidecarClient.Status.CLOSED, client.status());

        // Everything that could revive it must refuse.
        AnalysisResult r = client.solve(anyRequest(5));
        assertFalse(r.ok());
        assertFalse(client.ensureReady(), "a closed client must not restart");
        client.reset();
        assertEquals(SidecarClient.Status.CLOSED, client.status(),
                "reset() must not revive a closed client");
        assertFalse(client.ensureReady());

        assertEquals(1, Files.readAllLines(starts).size(),
                "no process may be spawned after close (CONC-3)");
    }

    @Test
    void closeOnANeverStartedClientIsSafeAndTerminal() {
        // Cross-platform (no stub needed): the state rules alone. The path would
        // exist-and-fail on ensureReady if the closed check were missing.
        client = clientFor(tmp.resolve("never-started"), 500);
        client.close();
        assertEquals(SidecarClient.Status.CLOSED, client.status());
        AnalysisResult r = client.solve(anyRequest(1));
        assertFalse(r.ok());
        client.reset();
        assertFalse(client.ensureReady());
        assertEquals(SidecarClient.Status.CLOSED, client.status());
    }

    @Test
    void aFloodingSidecarIsKilledNotBuffered() throws IOException {
        // #49: the stdout queue was unbounded, so a child spewing lines grew the JVM
        // heap without limit. Now the queue is bounded and overflow kills the child —
        // silently dropping lines instead would desynchronise every later reply.
        Path exe = script("flood.sh",
                "while :; do printf '{\"x\":1}\\n'; done\n");
        client = clientFor(exe, 2_000);

        AnalysisResult r = client.solve(anyRequest(1));
        assertFalse(r.ok(), "a flooding sidecar cannot produce a usable answer");

        long deadline = System.currentTimeMillis() + 3_000;
        while (log.stream().noneMatch(l -> l.contains("flooded stdout"))
                && System.currentTimeMillis() < deadline) {
            sleep(10);
        }
        assertTrue(log.stream().anyMatch(l -> l.contains("flooded stdout")),
                "overflow must be reported and the child killed, got: " + log);
    }

    // ---------------------------------------------------------- shm failure modes
    //
    // The stubs below declare "shm":1 and then misbehave at each stage of the shared
    // -memory conversation. None of them writes a byte into the region: the failure
    // paths under test are exactly the ones that must not depend on region content
    // (TEST-5 — these paths had zero coverage; every stub was JSON-only).

    private static final String SHM_HELLO =
            "printf '{\"ok\":true,\"op\":\"hello\",\"engine\":\"stub\",\"protocol\":1,"
                    + "\"materials\":[\"steel\"],\"sections\":[\"steel_rect_200x400\"],"
                    + "\"plates\":[],\"shm\":1}\\n'";

    private static final String JSON_SOLVE_REPLY =
            "printf '{\"ok\":true,\"revision\":%s,\"singular\":false,"
                    + "\"islands\":1,\"singularIslands\":0,\"equilibrium\":{\"residual\":0},"
                    + "\"maxDC\":0.5,\"governing\":1,\"members\":[],\"shells\":[],"
                    + "\"unassigned\":[]}\\n'";

    @Test
    void aRefusedShmMappingDowngradesToJsonAndTheSolveStillAnswers() throws IOException {
        // #32's contract: shm is the optimisation, JSON is the contract. An engine
        // that cannot map the region costs speed, never an analysis.
        Path exe = script("shm-refuse.sh",
                "while read -r line; do\n"
                        + "  case \"$line\" in\n"
                        + "    *'\"op\":\"hello\"'*) " + SHM_HELLO + " ;;\n"
                        + "    *'\"op\":\"shm.open\"'*) printf '{\"ok\":false,\"error\":\"no thanks\"}\\n' ;;\n"
                        + "    *'\"op\":\"solve.shm\"'*) printf '{\"ok\":false,\"revision\":0,"
                        + "\"error\":\"should never arrive\"}\\n' ;;\n"
                        + "    *) " + String.format(JSON_SOLVE_REPLY, 1) + " ;;\n"
                        + "  esac\n"
                        + "done\n");
        client = clientFor(exe, 2_000);

        AnalysisResult r = client.solve(anyRequest(1));
        assertTrue(r.ok(), r.diagnostic());
        assertEquals("json", client.transport(), "the refused mapping must leave JSON in charge");
        assertTrue(log.stream().anyMatch(l -> l.contains("staying on JSON")), log.toString());
        assertEquals(SidecarClient.Status.READY, client.status());
    }

    @Test
    void aShmDoorbellRevisionMismatchIsADesyncAndEndsTheConversation() throws IOException {
        // The doorbell claims to answer a request that was never asked. Once "which
        // reply is this?" has no answer, every later reply on the pipe is suspect —
        // this must kill and restart, not shrug (#33).
        Path exe = script("shm-desync.sh",
                "while read -r line; do\n"
                        + "  case \"$line\" in\n"
                        + "    *'\"op\":\"hello\"'*) " + SHM_HELLO + " ;;\n"
                        + "    *'\"op\":\"shm.open\"'*) printf '{\"ok\":true}\\n' ;;\n"
                        + "    *'\"op\":\"solve.shm\"'*) printf '{\"ok\":true,\"revision\":999,"
                        + "\"bytes\":16}\\n' ;;\n"
                        + "  esac\n"
                        + "done\n");
        client = clientFor(exe, 2_000);

        assertTrue(client.ensureReady());
        assertEquals("shm", client.transport(), "the handshake must have negotiated shm");

        AnalysisResult r = client.solve(anyRequest(1));
        assertFalse(r.ok());
        assertTrue(r.diagnostic().contains("desync"), r.diagnostic());
        assertNotEquals(SidecarClient.Status.READY, client.status(),
                "a desync must not leave the session marked healthy");
    }

    @Test
    void growIsRetriedOnceGrownAndARefusalAfterGrowthIsAnAnswer() throws IOException {
        // The grow loop is a two-state machine: refuse-with-grow, remap, resend. The
        // second refusal is a real engine answer and must come back verbatim, with
        // the session still READY (a refusal is not a transport failure).
        Path opens = tmp.resolve("opens");
        Path exe = script("shm-grow.sh",
                "solves=0\n"
                        + "while read -r line; do\n"
                        + "  case \"$line\" in\n"
                        + "    *'\"op\":\"hello\"'*) " + SHM_HELLO + " ;;\n"
                        + "    *'\"op\":\"shm.open\"'*) echo open >> '" + opens + "'; printf '{\"ok\":true}\\n' ;;\n"
                        + "    *'\"op\":\"solve.shm\"'*)\n"
                        + "      solves=$((solves+1))\n"
                        + "      if [ $solves -eq 1 ]; then\n"
                        + "        printf '{\"ok\":false,\"revision\":1,\"error\":\"grow the region and retry\"}\\n'\n"
                        + "      else\n"
                        + "        printf '{\"ok\":false,\"revision\":1,\"error\":\"model refused\"}\\n'\n"
                        + "      fi ;;\n"
                        + "  esac\n"
                        + "done\n");
        client = clientFor(exe, 2_000);

        AnalysisResult r = client.solve(anyRequest(1));
        assertFalse(r.ok());
        assertEquals("model refused", r.diagnostic(),
                "the post-grow refusal must come back verbatim, not be eaten by the loop");
        assertEquals(SidecarClient.Status.READY, client.status(),
                "an engine refusal is an answer, not a failure to count");
        assertEquals(2, Files.readAllLines(opens).size(),
                "the region must have been re-opened exactly once for the grow");
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
                        + "\"materials\":[],\"sections\":[],\"plates\":[]}\\n'\n"
                        + "done\n");
        client.reset();
        assertTrue(client.ensureReady());
    }

    @Test
    void concurrentSolveResetStatusAndCloseNeverThrowAndCloseWins() throws Exception {
        // TEST-6/CONC-2: the client is driven from an analysis thread while the server
        // thread reads status and issues reset/close. The conversation methods hold one
        // lock and the status fields are volatile, so no interleaving may throw — and
        // once close() has run, CLOSED is terminal against every racing reviver.
        // Cross-platform on purpose: a missing binary exercises the full state machine
        // (attempt, backoff, disable) without spawning processes.
        client = clientFor(tmp.resolve("absent"), 200);
        final SidecarClient c = client;
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(4);

        Thread solver = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 200; i++) {
                    AnalysisResult r = c.solve(anyRequest(i + 1));
                    if (r.ok()) failures.add(new AssertionError("absent binary cannot solve"));
                }
            } catch (Throwable t) { failures.add(t); }
        });
        Thread reader = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 2_000; i++) {
                    c.status();
                    c.transport();
                    c.disabledReason();
                }
            } catch (Throwable t) { failures.add(t); }
        });
        Thread resetter = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 200; i++) c.reset();
            } catch (Throwable t) { failures.add(t); }
        });
        Thread closer = new Thread(() -> {
            try {
                start.await();
                sleep(5);
                c.close();
            } catch (Throwable t) { failures.add(t); }
        });

        solver.start(); reader.start(); resetter.start(); closer.start();
        solver.join(30_000); reader.join(30_000); resetter.join(30_000); closer.join(30_000);

        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(SidecarClient.Status.CLOSED, c.status(),
                "close must win against every concurrent reviver");
        assertFalse(c.ensureReady());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
