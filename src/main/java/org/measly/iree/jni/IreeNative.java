package org.measly.iree.jni;

import java.nio.ByteBuffer;
import org.measly.iree.engine.LibUtils;

/** The JNI boundary. Every method here has a counterpart in iree_djl_jni.cpp. */
public final class IreeNative {

    static {
        LibUtils.loadLibrary();
    }

    private IreeNative() {}

    /**
     * Returns an opaque handle to the native runtime. Caller must close it.
     * {@code device} selects the IREE HAL driver, e.g. "local-sync" or "local-task".
     * {@code paramScopes}/{@code paramPaths} are parallel arrays pairing each parameter
     * archive's runtime scope name with its file path; empty for a model without parameters.
     */
    public static native long load(byte[] vmfb, String entryPoint, String device,
                                   String[] paramScopes, String[] paramPaths);

    /** Convenience overload for models without parameters. */
    public static long load(byte[] vmfb, String entryPoint, String device) {
        return load(vmfb, entryPoint, device, new String[0], new String[0]);
    }

    /**
     * Runs the model. Inputs must be direct ByteBuffers; their addresses are
     * borrowed only for the duration of this call.
     */
    public static native IreeTensor[] invoke(
            long handle, ByteBuffer[] inputs, long[][] shapes, int[] elementTypes);

    /** Releases the native runtime. Safe to call once per handle. */
    public static native void close(long handle);

    /**
     * Address of a direct {@link ByteBuffer}'s backing memory, or 0 for a
     * non-direct buffer (JNI spec). The JVM guarantees nothing stronger than
     * 8-byte alignment for its own direct buffers; the engine's aligned
     * buffers (see {@link IreeNative#allocateDirectAligned}) are
     * 64-byte-aligned by construction.
     */
    public static native long bufferAddress(ByteBuffer buffer);

    /**
     * Allocates a direct {@link ByteBuffer} over engine-owned native memory,
     * 64-byte-aligned (IREE's zero-copy import precondition).
     *
     * <p><b>Borrow contract:</b> the backing memory is NOT GC-managed. The
     * caller must free it exactly once via {@link #freeDirectAligned(long)}
     * with this buffer's {@link #bufferAddress(ByteBuffer)} — the engine wires
     * this through a {@link java.lang.ref.Cleaner}, so plain Java code never
     * calls it directly. Freeing twice, or freeing an address owned by a
     * JVM-allocated buffer, is a native crash.
     */
    public static native ByteBuffer allocateDirectAligned(int capacity);

    /**
     * Frees an aligned allocation by address. {@code 0} is a no-op. Called
     * exactly once per buffer, from the registered Cleaner.
     */
    public static native void freeDirectAligned(long address);

    /** Live engine-allocated aligned buffers (leak probe for tests). */
    public static native long aliveAlignedBuffers();

    /**
     * Per-input import outcome from the last invoke: 1 = zero-copy wrap,
     * 0 = staged copy. Exposed so tests can assert what actually happened
     * rather than assuming a borrow.
     */
    public static native int[] lastImportOutcomes(long handle);

    /** Forces the class to initialise, loading the library. */
    public static void ensureLoaded() {
        LibUtils.loadLibrary();
    }
}
