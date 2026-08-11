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
     *
     * <p>Production callers should use {@link org.measly.iree.engine.IreeEngineStats#snapshot()}'s
     * cumulative {@code wrappedImports}/{@code stagedImports} instead — this per-call query is
     * last-call-only state.
     */
    public static native int[] lastImportOutcomes(long handle);

    /** Index of the cumulative zero-copy import count in {@link #stats(long)}. */
    public static final int STAT_WRAPPED_IMPORTS = 0;
    /** Index of the cumulative staged-copy import count in {@link #stats(long)}. */
    public static final int STAT_STAGED_IMPORTS = 1;
    /** Index of the cached staging footprint, in bytes, in {@link #stats(long)}. */
    public static final int STAT_STAGING_BYTES = 2;
    /** Index of the HAL allocator's peak device bytes in {@link #stats(long)}. */
    public static final int STAT_DEVICE_BYTES_PEAK = 3;
    /** Index of the HAL allocator's live device bytes in {@link #stats(long)}. */
    public static final int STAT_DEVICE_BYTES_LIVE = 4;
    /** Index of the statistics-compiled-in flag (1 or 0) in {@link #stats(long)}. */
    public static final int STAT_STATISTICS_AVAILABLE = 5;
    /** Length of the array {@link #stats(long)} returns. */
    public static final int STAT_LENGTH = 6;

    /**
     * Cold-path observability read for one runtime, as a fixed-layout array
     * indexed by the {@code STAT_*} constants above.
     *
     * <p>Returns a primitive array rather than an object deliberately: building
     * a Java object in JNI needs a cached constructor ID with a hardcoded
     * signature literal, which breaks at class init whenever the Java
     * constructor changes. The array is unpacked in Java, where the compiler
     * checks it.
     *
     * <p><b>Returns {@code null} for a closed or zero handle rather than
     * throwing</b>, because the caller is a monitoring poll that must never
     * throw. Callers skip a null entry.
     *
     * <p>When {@code STAT_STATISTICS_AVAILABLE} is 0, the two device-byte
     * entries are meaningless and callers must report them as unavailable.
     */
    public static native long[] stats(long handle);

    /**
     * Live native runtimes. A leak probe for tests: unlike LSan, which sees
     * only unreachable memory, this counts a runtime that is retained forever.
     */
    public static native long aliveRuntimes();

    /**
     * Whether IREE's HAL allocator statistics are compiled into this build —
     * that is, whether {@link #STAT_DEVICE_BYTES_PEAK} and
     * {@link #STAT_DEVICE_BYTES_LIVE} carry meaning.
     *
     * <p>Takes no handle on purpose. This is a build property fixed at compile
     * time (see the {@code IREE_STATISTICS_ENABLE} agreement check in
     * {@code native/CMakeLists.txt}), so a monitoring poll must be able to
     * report it before the first model loads and after the last one closes —
     * neither of which a per-handle read can do.
     */
    public static native boolean statisticsAvailable();

    /** Forces the class to initialise, loading the library. */
    public static void ensureLoaded() {
        LibUtils.loadLibrary();
    }
}
