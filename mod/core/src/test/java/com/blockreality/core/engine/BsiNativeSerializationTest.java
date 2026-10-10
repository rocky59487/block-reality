package com.blockreality.core.engine;

import com.blockreality.core.bsi.BsiFrame;
import com.blockreality.core.bsi.BsiHeaders;
import com.blockreality.core.diagnostics.PipelineProfile;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import static com.blockreality.core.diagnostics.PipelineProfile.Counter.*;
import static com.blockreality.core.diagnostics.PipelineProfile.Stage.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the production public methods; only the private C binding is replaced by a counted proxy. */
class BsiNativeSerializationTest {
    private static final byte[] ONE_THREAD = solve("one", 1);
    private static final byte[] FOUR_THREADS = solve("four", 4);

    enum Entry {
        CALL("bsi_capi_call"), ERROR("bsi_capi_last_error"), CLOSE("bsi_capi_close");
        final String symbol;
        Entry(String symbol) { this.symbol = symbol; }
        Object invoke(BsiNative session) {
            return switch (this) {
                case CALL -> session.call(FOUR_THREADS);
                case ERROR -> session.lastError();
                case CLOSE -> { session.close(); yield null; }
            };
        }
    }

    static Stream<Object[]> pairs() {
        return Stream.of(Entry.values()).flatMap(first -> Stream.of(Entry.values())
                .map(second -> new Object[]{first, second}));
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void differentSessionsAndLibraryPathsShareOneNativeGate(Entry holder, Entry contender) throws Exception {
        Probe probe = new Probe(holder);
        try (BsiNative first = probe.session(1, "library-a");
             BsiNative second = probe.session(2, "library-b");
             Workers workers = new Workers(probe.release)) {
            Pending<Object> a = workers.start(() -> holder == Entry.CALL ? first.call(ONE_THREAD) : holder.invoke(first));
            await(probe.entered);
            Pending<Object> b = workers.start(() -> contender.invoke(second));
            assertBlockedBy(b, a);
            assertEquals(1, probe.max.get());
            assertEquals(1, probe.entries.get());
            probe.release.countDown();
            Object firstResult = a.get(), secondResult = b.get();
            if (holder == Entry.CALL) assertArrayEquals(ONE_THREAD, (byte[]) firstResult);
            if (contender == Entry.CALL) assertArrayEquals(FOUR_THREADS, (byte[]) secondResult);
            assertEquals(2, probe.entries.get());
            assertEquals(1, probe.max.get(), "all native entries, not just calls, must be serialized");
        }
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void realLibraryOpenWaitsForEveryNativeEntry(Entry holder) throws Exception {
        String path = System.getProperty("br.engine", "");
        if (path.isBlank()) path = System.getenv("BR_ENGINE");
        Assumptions.assumeTrue(path != null && !path.isBlank(), "set BR_ENGINE to run real public open");
        Path library = Path.of(path);
        assertTrue(Files.isRegularFile(library), "explicit native library must exist");
        Probe probe = new Probe(holder);
        try (BsiNative first = probe.session(1, "a-different-library-path");
             Workers workers = new Workers(probe.release)) {
            Pending<Object> a = workers.start(() -> holder.invoke(first));
            await(probe.entered);
            Pending<Void> b = workers.start(() -> {
                try (BsiNative opened = BsiNative.open(library, null)) {
                    assertEquals(library.toAbsolutePath().toString(), opened.libraryPath());
                    assertNotNull(opened.lastError());
                }
                return null;
            });
            assertBlockedBy(b, a);
            probe.release.countDown();
            a.get();
            b.get();
        }
    }

    @Test
    void lastErrorWaitsForCloseAndNeverDereferencesAFreedHandle() throws Exception {
        Probe probe = new Probe(Entry.CLOSE);
        try (BsiNative session = probe.session(1, "library");
             Workers workers = new Workers(probe.release)) {
            Pending<Object> close = workers.start(() -> Entry.CLOSE.invoke(session));
            await(probe.entered);
            Pending<String> error = workers.start(session::lastError);
            assertBlockedBy(error, close);
            probe.release.countDown();
            close.get();
            assertEquals("", error.get());
            assertEquals("", session.lastError());
            session.close();
            assertThrows(IllegalStateException.class, () -> session.call(ONE_THREAD));
            assertEquals(1, probe.entries.get(), "closed handle must never reach the binding again");
        }
    }

    @Test
    void needBiggerRetriesIdenticalBytesBeforeAnotherRequestAndPreservesProfiling() throws Exception {
        Probe probe = new Probe(Entry.CALL);
        CountDownLatch retryEntered = new CountDownLatch(1), retryRelease = new CountDownLatch(1);
        List<byte[]> requests = new ArrayList<>();
        List<Integer> capacities = new ArrayList<>();
        byte[] reply = new byte[70_000];
        for (int i = 0; i < reply.length; i++) reply[i] = (byte) i;
        probe.reply = args -> {
            byte[] request = (byte[]) args[1];
            requests.add(request.clone());
            capacities.add(((ByteBuffer) args[3]).capacity());
            if (requests.size() == 1) {
                ((LongByReference) args[6]).setValue(reply.length);
                return BsiNative.NEED_BIGGER;
            }
            if (requests.size() == 2) {
                retryEntered.countDown();
                await(retryRelease);
                return respond(args, reply);
            }
            return respond(args, request);
        };
        PipelineProfile profile = new PipelineProfile();
        profile.start();
        try (BsiNative session = probe.session(1, "library", profile);
             Workers workers = new Workers(probe.release, retryRelease)) {
            Pending<byte[]> first = workers.start(() -> session.call(ONE_THREAD));
            await(probe.entered);
            Pending<byte[]> second = workers.start(() -> session.call(FOUR_THREADS));
            assertBlockedBy(second, first);
            probe.release.countDown();
            await(retryEntered);
            assertBlockedBy(second, first);
            retryRelease.countDown();
            assertArrayEquals(reply, first.get());
            assertArrayEquals(FOUR_THREADS, second.get());
            assertEquals(3, requests.size());
            assertArrayEquals(ONE_THREAD, requests.get(0));
            assertArrayEquals(ONE_THREAD, requests.get(1));
            assertArrayEquals(FOUR_THREADS, requests.get(2));
            assertEquals(List.of(64 * 1024, reply.length + 4096, reply.length + 4096), capacities);
            var snapshot = profile.snapshot();
            assertEquals(3, snapshot.stages().get(NATIVE_CALL).observed());
            assertEquals(2, snapshot.stages().get(REPLY_COPY).observed());
            assertEquals(2L * ONE_THREAD.length + FOUR_THREADS.length, snapshot.counters().get(REQUEST_BYTES));
            assertEquals((long) reply.length + FOUR_THREADS.length, snapshot.counters().get(REPLY_BYTES));
            assertEquals(1L, snapshot.counters().get(BUFFER_GROWTH));
        }
    }

    @Test
    void invalidAndRepeatedNeedBiggerKeepTheirDiagnosticsAndReleaseTheGate() throws Exception {
        Probe probe = new Probe(Entry.CALL);
        probe.release.countDown();
        try (BsiNative first = probe.session(1, "library-a");
             BsiNative second = probe.session(2, "library-b")) {
            probe.reply = args -> BsiNative.INVALID;
            assertTrue(assertThrows(BsiNative.EngineRefused.class, () -> first.call(ONE_THREAD))
                    .getMessage().contains("test diagnostic"));
            probe.reply = args -> {
                ((LongByReference) args[6]).setValue((long) ((ByteBuffer) args[3]).capacity() + 1);
                return BsiNative.NEED_BIGGER;
            };
            assertTrue(assertThrows(BsiNative.EngineRefused.class, () -> first.call(ONE_THREAD))
                    .getMessage().contains("kept asking for a bigger reply buffer: test diagnostic"));
            probe.reply = args -> BsiNative.PROTOCOL;
            assertNull(first.call(ONE_THREAD));
            probe.reply = args -> respond(args, (byte[]) args[1]);
            assertArrayEquals(FOUR_THREADS, second.call(FOUR_THREADS));
        }
    }

    private static byte[] solve(String id, int threads) {
        return BsiFrame.encode(BsiHeaders.solve(id, 1, true, null, 0, threads, List.of()), null);
    }

    private static int respond(Object[] args, byte[] bytes) {
        ((ByteBuffer) args[3]).put(bytes);
        ((LongByReference) args[5]).setValue(bytes.length);
        return BsiNative.OK;
    }

    private static final class Probe {
        final Entry held;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger active = new AtomicInteger(), max = new AtomicInteger(), entries = new AtomicInteger();
        final Set<Long> closed = ConcurrentHashMap.newKeySet();
        ToIntFunction<Object[]> reply = args -> respond(args, (byte[]) args[1]);

        Probe(Entry held) { this.held = held; }

        BsiNative session(long handle, String path) throws Exception {
            return session(handle, path, PipelineProfile.disabled());
        }

        BsiNative session(long handle, String path, PipelineProfile profile) throws Exception {
            Class<?> binding = Class.forName(BsiNative.class.getName() + "$Lib");
            Object lib = Proxy.newProxyInstance(binding.getClassLoader(), new Class<?>[]{binding}, (proxy, method, args) -> {
                long h = Pointer.nativeValue((Pointer) args[0]);
                entries.incrementAndGet();
                max.accumulateAndGet(active.incrementAndGet(), Math::max);
                try {
                    assertFalse(closed.contains(h), "native entry used a freed handle");
                    if (h == 1 && method.getName().equals(held.symbol)) {
                        entered.countDown();
                        await(release);
                    }
                    return switch (method.getName()) {
                        case "bsi_capi_call" -> reply.applyAsInt(args);
                        case "bsi_capi_last_error" -> "test diagnostic";
                        case "bsi_capi_close" -> { assertTrue(closed.add(h)); yield null; }
                        default -> throw new AssertionError("unexpected binding call " + method);
                    };
                } finally { active.decrementAndGet(); }
            });
            var constructor = BsiNative.class.getDeclaredConstructor(binding, Pointer.class, String.class, PipelineProfile.class);
            constructor.setAccessible(true);
            return constructor.newInstance(lib, new Pointer(handle), path, profile);
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "test barrier was not released"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private static void assertBlockedBy(Pending<?> contender, Pending<?> holder) throws Exception {
        await(contender.started);
        var threads = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            var info = threads.getThreadInfo(contender.thread.getId(), 20);
            if (info != null && info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockOwnerId() == holder.thread.getId()
                    && Stream.of(info.getStackTrace()).anyMatch(frame -> frame.getClassName().equals(BsiNative.class.getName()))) {
                return;
            }
            if (contender.result.isDone()) {
                contender.get();
                fail("second task entered/completed instead of blocking at the BsiNative monitor");
            }
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        fail("second task started but was not blocked by the native-entry holder");
    }

    private static final class Pending<T> {
        final CountDownLatch started = new CountDownLatch(1);
        final FutureTask<T> result;
        final Thread thread;
        Pending(Callable<T> action) {
            result = new FutureTask<>(() -> { started.countDown(); return action.call(); });
            thread = new Thread(result, "bsi-native-gate-test");
            thread.setDaemon(true);
            thread.start();
        }
        T get() throws Exception { return result.get(10, TimeUnit.SECONDS); }
    }

    private static final class Workers implements AutoCloseable {
        final List<Pending<?>> pending = new ArrayList<>();
        final CountDownLatch[] releases;
        Workers(CountDownLatch... releases) { this.releases = releases; }
        <T> Pending<T> start(Callable<T> action) {
            Pending<T> task = new Pending<>(action);
            pending.add(task);
            return task;
        }
        @Override public void close() {
            for (CountDownLatch latch : releases) latch.countDown();
            for (Pending<?> task : pending) {
                try { task.get(); }
                catch (Exception e) { throw new AssertionError("worker did not complete cleanly", e); }
            }
        }
    }
}
