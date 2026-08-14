package com.blockreality.core.sidecar;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One running sidecar process and its two pipes. Transport only — no protocol, no retry
 * policy, no opinion about what the lines mean. {@link SidecarClient} owns all of that.
 *
 * <p>Two details here are not optional, and both are the kind that work in testing and
 * fail in production:
 *
 * <ul>
 *   <li><strong>stderr is drained on its own thread.</strong> A pipe nobody reads fills
 *       up, and the child then blocks forever inside {@code write}. The symptom is a
 *       sidecar that hangs only once it has produced enough diagnostic output — which is
 *       to say, only once something has gone wrong.
 *   <li><strong>stdout is drained into a queue, not read on demand.</strong> Reading
 *       straight from the pipe has no timeout, so a wedged child would block the caller
 *       indefinitely. Draining into a queue makes the timeout a {@code poll}.
 * </ul>
 */
public final class SidecarProcess implements AutoCloseable {

    private final Process process;
    private final BufferedWriter stdin;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final AtomicBoolean readerEnded = new AtomicBoolean(false);
    private final Thread outReader;
    private final Thread errReader;

    private SidecarProcess(Process p, Consumer<String> log) {
        this.process = p;
        this.stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));

        this.outReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) lines.offer(l);
            } catch (IOException ignored) {
                // The process died; readerEnded below is the signal that matters.
            } finally {
                readerEnded.set(true);
            }
        }, "br-sidecar-out");

        this.errReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) log.accept(l);
            } catch (IOException ignored) {
                // Same.
            }
        }, "br-sidecar-err");

        outReader.setDaemon(true);
        errReader.setDaemon(true);
        outReader.start();
        errReader.start();
    }

    /**
     * @throws IOException if the binary is missing, is not executable, or fails to start.
     *                     The caller turns this into "analysis unavailable", never into a
     *                     crash: a missing sidecar must leave the mod perfectly playable.
     */
    public static SidecarProcess start(Path executable, Consumer<String> log) throws IOException {
        if (!Files.isRegularFile(executable)) {
            throw new IOException("sidecar binary not found: " + executable);
        }
        if (!Files.isExecutable(executable)) {
            throw new IOException("sidecar binary is not executable: " + executable);
        }
        ProcessBuilder pb = new ProcessBuilder(executable.toAbsolutePath().toString());
        pb.redirectErrorStream(false);
        return new SidecarProcess(pb.start(), log == null ? s -> { } : log);
    }

    public boolean alive() { return process.isAlive(); }

    /** @return false if the pipe is already broken */
    public boolean send(String line) {
        try {
            stdin.write(line);
            stdin.write('\n');
            stdin.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** @return the next reply line, or {@code null} on timeout or on the process ending */
    public String awaitLine(long timeoutMs) {
        try {
            String l = lines.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (l == null && readerEnded.get()) return null;
            return l;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Discards anything already queued, so a late reply cannot be read as the next one. */
    public void drain() { lines.clear(); }

    /**
     * Closes stdin first. That is the shutdown signal: the sidecar's read loop sees EOF
     * and exits by itself, which also means a crashed JVM cannot leave a zombie behind.
     * The kill is only the backstop.
     */
    @Override
    public void close() {
        try {
            stdin.close();
        } catch (IOException ignored) {
            // Already broken; the destroy below still applies.
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** Immediate kill, for a process that has stopped answering. */
    public void kill() {
        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
