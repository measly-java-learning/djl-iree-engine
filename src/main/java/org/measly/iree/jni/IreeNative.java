package org.measly.iree.jni;

import java.nio.ByteBuffer;
import org.measly.iree.engine.LibUtils;

/**
 * The JNI boundary. Every method here has a counterpart in iree_djl_jni.cpp.
 *
 * @apiNote Internal API. Not covered by compatibility guarantees; see
 *          {@link org.measly.iree.jni} for what that means in practice.
 */
public final class IreeNative {

    static {
        LibUtils.loadLibrary();
    }

    private IreeNative() {}

    /**
     * Loads a compiled {@code .vmfb} program into a new native runtime and returns an opaque
     * handle to it.
     *
     * <p>The JVM side must guarantee: {@code vmfb}, {@code entryPoint}, and {@code device} are
     * non-null; {@code paramScopes} and {@code paramPaths} are non-null, the same length, and
     * contain no null elements (pass empty arrays, not null, for a model without parameters).
     * {@code vmfb}'s bytes are copied into the runtime before this call returns, so the array
     * itself need not outlive the call. {@code device} selects the IREE HAL driver, e.g.
     * {@code "local-sync"} or {@code "local-task"}; an unknown or unavailable driver fails here,
     * not on a later call. {@code paramScopes}/{@code paramPaths} are parallel arrays pairing
     * each parameter archive's runtime scope name with its file path.
     *
     * <p>On success, ownership of the returned handle passes to the caller: release it with
     * exactly one call to {@link #close(long)}. After that call returns, the handle value must
     * not be reused for any method in this class — the native side does not track whether a
     * handle is still live, so calling anything with a closed handle is a use-after-free in
     * native code, not a checked Java error.
     *
     * @param vmfb the compiled program's bytes; copied internally, so the array may be reused
     *     or discarded once this call returns
     * @param entryPoint the exported function name this runtime will invoke
     * @param device the IREE HAL driver name
     * @param paramScopes runtime scope name for each parameter archive, parallel to
     *     {@code paramPaths}; empty for a model without parameters
     * @param paramPaths filesystem path for each parameter archive, parallel to
     *     {@code paramScopes}
     * @return an opaque, non-zero native handle
     * @throws RuntimeException if any argument precondition above is violated, if the driver is
     *     unknown or unavailable, or if loading the program or its parameters otherwise fails
     */
    public static native long load(byte[] vmfb, String entryPoint, String device,
                                   String[] paramScopes, String[] paramPaths);

    /**
     * Convenience overload for models without parameters.
     *
     * @param vmfb the compiled program's bytes; copied internally, so the array may be reused
     *     or discarded once this call returns
     * @param entryPoint the exported function name this runtime will invoke
     * @param device the IREE HAL driver name
     * @return an opaque, non-zero native handle; see
     *     {@link #load(byte[], String, String, String[], String[])}
     * @throws RuntimeException under the same conditions as the five-argument overload
     */
    public static long load(byte[] vmfb, String entryPoint, String device) {
        return load(vmfb, entryPoint, device, new String[0], new String[0]);
    }

    /**
     * Runs the loaded model once and returns its outputs.
     *
     * <p>{@code handle} must be a still-open handle from {@link #load}. {@code inputs},
     * {@code shapes}, and {@code elementTypes} must be non-null and the same length; every
     * element of {@code inputs} must itself be a non-null direct {@link ByteBuffer} — a
     * non-direct (heap) buffer is rejected, not silently copied. Each input's address is
     * borrowed only for the duration of this call: IREE either imports it zero-copy or, when
     * the address is not 64-byte aligned (see {@link #allocateDirectAligned}), stages a copy
     * into a per-runtime cached staging buffer that is reused across calls; either way, once
     * this call returns the caller may reuse or discard the buffer freely. {@code shapes[i]}
     * gives {@code inputs[i]}'s dimension sizes and {@code elementTypes[i]} its
     * {@code iree_hal_element_type_t} value.
     *
     * <p>Each returned {@link IreeTensor} owns a freshly allocated, ordinary JVM-managed direct
     * {@link ByteBuffer} (not one of the aligned allocations from
     * {@link #allocateDirectAligned}) already populated with a copy of that output — there is
     * nothing further for the caller to release. Native-side output views are released before
     * this call returns, on both the success and the error path.
     *
     * @param handle a still-open handle from {@link #load}
     * @param inputs one direct {@link ByteBuffer} per model input
     * @param shapes dimension sizes, one array per input, parallel to {@code inputs}
     * @param elementTypes one {@code iree_hal_element_type_t} value per input, parallel to
     *     {@code inputs}
     * @return one {@link IreeTensor} per model output, in output order
     * @throws RuntimeException if {@code handle} is zero, if the array-length preconditions
     *     above are violated, if any input is not a direct buffer, if native invocation fails,
     *     or if an output exceeds the 2 GiB JNI direct-buffer limit
     * @throws OutOfMemoryError if the JVM cannot allocate an output buffer or the result array
     */
    public static native IreeTensor[] invoke(
            long handle, ByteBuffer[] inputs, long[][] shapes, int[] elementTypes);

    /**
     * Releases the native runtime behind {@code handle}, freeing every native resource it owns.
     *
     * <p>{@code handle} must be either {@code 0} (a no-op) or a value previously returned by
     * {@link #load} that has not already been passed to this method. Calling this twice with
     * the same non-zero handle — or calling any other method in this class with a handle after
     * it has been closed — is a double-free or use-after-free in native code: undefined
     * behavior, typically a crash, never a Java exception. The JVM side owns the handle's
     * lifecycle; callers should zero their copy of the handle immediately after this call
     * returns, as {@code IreeSymbolBlock.close()} does under its own lock.
     *
     * @param handle a handle from {@link #load}, or {@code 0}
     */
    public static native void close(long handle);

    /**
     * Returns the address of a direct {@link ByteBuffer}'s backing memory, or {@code 0} for a
     * non-direct buffer (per the JNI spec).
     *
     * <p>{@code buffer} must be non-null: unlike {@link #invoke}, which explicitly guards its
     * own buffer arguments, this method passes {@code buffer} straight to the JNI
     * {@code GetDirectBufferAddress} intrinsic. Calling it with {@code null} is undefined
     * behavior in native code, not a checked error.
     *
     * <p>The JVM guarantees nothing stronger than 8-byte alignment for its own direct buffers;
     * the engine's aligned buffers (see {@link #allocateDirectAligned}) are 64-byte-aligned by
     * construction.
     *
     * @param buffer a non-null {@link ByteBuffer}
     * @return the buffer's native address, or {@code 0} if {@code buffer} is not direct
     */
    public static native long bufferAddress(ByteBuffer buffer);

    /**
     * Allocates a direct {@link ByteBuffer} over engine-owned native memory, 64-byte-aligned
     * (IREE's zero-copy import precondition).
     *
     * <p><b>Borrow contract:</b> the backing memory is NOT GC-managed. The caller must free it
     * exactly once via {@link #freeDirectAligned(long)} with this buffer's
     * {@link #bufferAddress(ByteBuffer)} — the engine wires this through a
     * {@link java.lang.ref.Cleaner}, so plain Java code never calls it directly. Freeing twice, or
     * freeing an address owned by a JVM-allocated buffer, is a native crash.
     *
     * @param capacity the allocation size in bytes, and the returned buffer's capacity; must be
     *     non-negative
     * @return a new direct buffer of size {@code capacity}, 64-byte-aligned
     * @throws RuntimeException if the native aligned allocation fails (out of memory)
     * @throws OutOfMemoryError if the JVM cannot construct the {@link ByteBuffer} wrapper around
     *     an allocation that itself succeeded; the underlying native memory is freed
     *     automatically in that case, so nothing leaks
     */
    public static native ByteBuffer allocateDirectAligned(int capacity);

    /**
     * Frees an aligned allocation previously returned by {@link #allocateDirectAligned}, given
     * its address. {@code 0} is a no-op. Must be called exactly once per buffer, and only from
     * the registered {@link java.lang.ref.Cleaner} — freeing an address twice, or an address
     * not obtained from {@link #allocateDirectAligned}, is a native crash. Never throws.
     *
     * @param address a value previously obtained from {@link #bufferAddress(ByteBuffer)} on a
     *     buffer returned by {@link #allocateDirectAligned}, or {@code 0}
     */
    public static native void freeDirectAligned(long address);

    /**
     * Live engine-allocated aligned buffers (leak probe for tests). Unlike
     * {@code -XX:MaxDirectMemorySize} accounting, which does not see these allocations, this
     * counter does.
     *
     * @return the number of aligned buffers currently allocated and not yet freed
     */
    public static native long aliveAlignedBuffers();

    /**
     * Per-input import outcome from the last {@link #invoke} call on this handle.
     *
     * <p>Exposed so tests can assert what actually happened rather than assuming a borrow.
     * Production callers should use
     * {@link org.measly.iree.engine.IreeEngineStats#snapshot()}'s cumulative
     * {@code wrappedImports}/{@code stagedImports} instead — this per-call query is
     * last-call-only state, so it does not compose across concurrent or repeated calls.
     *
     * @param handle a still-open handle from {@link #load}
     * @return one entry per input to the most recent {@link #invoke} call on this handle:
     *     {@code 1} if that input was imported zero-copy, {@code 0} if it was staged as a copy
     * @throws RuntimeException if {@code handle} is zero
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
     * Cold-path observability read for one runtime, as a fixed-layout array indexed by the
     * {@code STAT_*} constants above.
     *
     * <p>Returns a primitive array rather than an object deliberately: building a Java object in
     * JNI needs a cached constructor ID with a hardcoded signature literal, which breaks at class
     * init whenever the Java constructor changes. The array is unpacked in Java, where the compiler
     * checks it.
     *
     * <p><b>Returns {@code null} for a closed or zero handle rather than throwing</b>, because the
     * caller is a monitoring poll that must never throw over a routine race with {@code close()}.
     * (A rare {@link OutOfMemoryError} from the result-array allocation can still surface —
     * this null-vs-throw contract is about handle state, not about memory pressure.)
     *
     * <p>{@code STAT_WRAPPED_IMPORTS}, {@code STAT_STAGED_IMPORTS}, and
     * {@code STAT_STAGING_BYTES} are always real counts, tracked independently of IREE's own
     * statistics; {@code 0} means genuinely zero (e.g. nothing has staged yet), never
     * "unavailable". When {@code STAT_STATISTICS_AVAILABLE} is {@code 0}, the two device-byte
     * entries are the literal value {@code 0} coming out of native code — not a sentinel — and
     * callers must treat them as unavailable rather than "zero bytes". The {@code -1}
     * "unavailable" encoding some callers use for these fields is a convention applied one layer
     * up, in {@code org.measly.iree.engine}; this method itself never returns {@code -1}.
     *
     * @param handle a still-open handle from {@link #load}, or {@code 0}
     * @return a {@link #STAT_LENGTH}-element array indexed by the {@code STAT_*} constants, or
     *     {@code null} if {@code handle} is zero or the runtime behind it has already closed
     */
    public static native long[] stats(long handle);

    /**
     * Live native runtimes. A leak probe for tests: unlike LSan, which sees
     * only unreachable memory, this counts a runtime that is retained forever.
     *
     * @return the number of native runtimes currently live, process-wide (not per-classloader)
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
     *
     * @return {@code true} if the two device-byte gauges in {@link #stats(long)} carry a real
     *     measurement, {@code false} if this build was compiled without allocator statistics
     */
    public static native boolean statisticsAvailable();

    /** Forces the class to initialise, loading the library. */
    public static void ensureLoaded() {
        LibUtils.loadLibrary();
    }
}
