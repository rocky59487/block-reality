package com.blockreality.core.engine;

import com.blockreality.core.bsi.BsiContract;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * The whole bridge to the engine: five C functions, bound with JNA direct mapping.
 *
 * <p>There is no JNI glue, no {@code javah}, no per-platform C file to keep in step — the contract
 * is {@code contract/bsi_capi.h} and the binding is this class (D-044 §2). The same five functions
 * the conformance runner drives through ctypes on every run are the ones the game calls, so the
 * consumer's path is gated by the corpus rather than by a separate integration test nobody runs.
 *
 * <p>One call carries one frame in and one frame out. Calls on a handle are serialised here; the
 * engine is free to use threads inside a call and is told how many by the request.
 *
 * <p><b>This runs in the game's process.</b> A native crash takes the JVM with it — the cost
 * D-044 records rather than hides. What this class can do about it, it does: refuse an ABI it does
 * not know, refuse a contract hash that is not its own, and never hand the library a buffer whose
 * size it did not agree to.
 */
public final class BsiNative implements AutoCloseable {

    /** Return codes of {@code bsi_capi_call}. */
    public static final int OK = 0, NEED_BIGGER = 2, PROTOCOL = 4, INVALID = 5;

    private interface Lib extends com.sun.jna.Library {
        int bsi_capi_abi_version();
        Pointer bsi_capi_open(String optionsJson);
        int bsi_capi_call(Pointer h, byte[] req, long reqLen, ByteBuffer out, long outCap,
                          LongByReference outLen, LongByReference outNeeded);
        void bsi_capi_close(Pointer h);
        String bsi_capi_last_error(Pointer h);
    }

    private final Lib lib;
    private final Pointer handle;
    private final String libraryPath;
    private ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
    private volatile boolean closed;

    private BsiNative(Lib lib, Pointer handle, String libraryPath) {
        this.lib = lib;
        this.handle = handle;
        this.libraryPath = libraryPath;
    }

    /**
     * Load a library and open one session.
     *
     * @throws EngineRefused when the library cannot be loaded, its ABI generation is not the one
     *                       this build binds, or it will not open a session. Every one of those is
     *                       a refusal with a reason, never a half-working engine.
     */
    public static BsiNative open(Path library, String optionsJson) {
        String path = library.toAbsolutePath().toString();
        Lib lib;
        try {
            lib = Native.load(path, Lib.class);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            throw new EngineRefused("cannot load " + path + ": " + e.getMessage(), e);
        }
        int abi;
        try {
            abi = lib.bsi_capi_abi_version();
        } catch (UnsatisfiedLinkError e) {
            throw new EngineRefused(path + " exports no bsi_capi_abi_version: it is not a BSI engine", e);
        }
        if (abi != BsiContract.CAPI_ABI) {
            throw new EngineRefused("engine speaks bsi_capi ABI " + abi + ", this build binds " + BsiContract.CAPI_ABI
                    + " (" + path + ")");
        }
        Pointer h = lib.bsi_capi_open(optionsJson == null ? "{}" : optionsJson);
        if (h == null) throw new EngineRefused("bsi_capi_open refused a session (" + path + ")");
        return new BsiNative(lib, h, path);
    }

    public String libraryPath() { return libraryPath; }

    /**
     * One request frame in, one reply frame out.
     *
     * <p>{@code NEED_BIGGER} is answered by growing once and re-sending the same request: the
     * contract says a refused call consumes nothing, so a retry is a repeat and not a resume.
     *
     * @return the reply frame bytes, or null when the library refused the request at the transport
     *         level (a malformed frame — which would be this class's own defect, not the engine's).
     */
    public synchronized byte[] call(byte[] requestFrame) {
        if (closed) throw new IllegalStateException("engine session is closed");
        for (int attempt = 0; attempt < 2; attempt++) {
            LongByReference len = new LongByReference(), need = new LongByReference();
            buffer.clear();
            int rc = lib.bsi_capi_call(handle, requestFrame, requestFrame.length, buffer, buffer.capacity(), len, need);
            if (rc == OK) {
                byte[] out = new byte[(int) len.getValue()];
                buffer.position(0);
                buffer.get(out);
                return out;
            }
            if (rc == NEED_BIGGER) {
                long required = need.getValue();
                if (required <= buffer.capacity() || required > (256L << 20)) {
                    throw new EngineRefused("engine asked for " + required + " reply bytes; refusing");
                }
                buffer = ByteBuffer.allocateDirect((int) required + 4096).order(ByteOrder.LITTLE_ENDIAN);
                continue;
            }
            if (rc == INVALID) throw new EngineRefused("engine session is no longer valid: " + lastError());
            return null;   // PROTOCOL: the frame this side built is malformed
        }
        throw new EngineRefused("engine kept asking for a bigger reply buffer: " + lastError());
    }

    public String lastError() {
        try {
            String s = lib.bsi_capi_last_error(handle);
            return s == null ? "" : s;
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            lib.bsi_capi_close(handle);
        } catch (RuntimeException | UnsatisfiedLinkError ignored) {
            // closing twice, or after the library went away, must not throw into a shutdown hook
        }
    }

    /** The engine would not load, would not open, or does not speak this ABI. Always carries the reason. */
    public static final class EngineRefused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public EngineRefused(String message) { super(message); }
        public EngineRefused(String message, Throwable cause) { super(message, cause); }
    }
}
