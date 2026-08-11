package org.measly.iree.engine;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.AbstractSymbolBlock;
import ai.djl.nn.ParameterList;
import ai.djl.training.ParameterStore;
import ai.djl.util.PairList;
import java.nio.ByteBuffer;
import org.measly.iree.jni.IreeNative;
import org.measly.iree.jni.IreeTensor;

/**
 * Runs the model's entry point.
 *
 * <p><b>Not thread-safe on the same model.</b> One Model/Predictor per thread,
 * and never close a model with a forward in flight. An IREE session is not safe
 * for concurrent invocation. This caller contract is identical for every driver:
 * {@code local-task} adds an intra-op worker pool <i>below</i> this boundary
 * (parallelizing a single invoke), but does not make concurrent invocation of
 * one session safe. {@code local-sync} holds the contract all the way down by
 * running everything on the calling thread.
 */
public class IreeSymbolBlock extends AbstractSymbolBlock implements AutoCloseable {

    private final IreeNDManager manager;
    private volatile long handle;

    // Serializes the stats cold path against close(): the stats read takes the native
    // handle and calls into JNI, close() destroys the handle, and a destroy between the
    // read and the JNI call would be a use-after-free. Taken only by close() and
    // toStats(), never by forwardInternal — the hot path stays lock-free.
    private final Object statsLock = new Object();

    // Attached by IreeModel.load right after construction. Null only in the narrow
    // window before that, and in tests that build a block directly.
    private volatile IreeModelCounters counters;

    IreeSymbolBlock(IreeNDManager manager, long handle) {
        this.manager = manager;
        this.handle = handle;
    }

    /** Attaches the counters this block updates. Called once, from {@link IreeModel#load}. */
    void attachCounters(IreeModelCounters counters) {
        this.counters = counters;
    }

    @Override
    protected NDList forwardInternal(
            ParameterStore parameterStore,
            NDList inputs,
            boolean training,
            PairList<String, Object> params) {

        if (handle == 0L) {
            throw new IllegalStateException("forward on a closed model");
        }

        int count = inputs.size();
        ByteBuffer[] buffers = new ByteBuffer[count];
        long[][] shapes = new long[count][];
        int[] types = new int[count];

        for (int i = 0; i < count; i++) {
            NDArray input = inputs.get(i);
            ByteBuffer buffer = input.toByteBuffer(true);
            if (!buffer.isDirect()) {
                throw new IllegalArgumentException(
                        "IREE inputs must be backed by direct buffers; input "
                                + i + " was not");
            }
            buffers[i] = buffer;
            shapes[i] = input.getShape().getShape();
            types[i] = IreeDataTypes.toIree(input.getDataType());
        }

        final long start = System.nanoTime();
        IreeTensor[] outputs = IreeNative.invoke(handle, buffers, shapes, types);
        final long elapsed = System.nanoTime() - start;
        IreeModelCounters c = counters;
        if (c != null) {
            c.recordForward(elapsed);
        }

        NDManager rm = inputs.isEmpty() ? manager : inputs.head().getManager();
        IreeNDManager target = (rm instanceof IreeNDManager) ? (IreeNDManager) rm : manager;

        NDList result = new NDList(outputs.length);
        for (IreeTensor tensor : outputs) {
            result.add(
                    target.wrap(
                            tensor.getData(),
                            new Shape(tensor.getShape()),
                            IreeDataTypes.fromIree(tensor.getElementType())));
        }
        return result;
    }

    @Override
    public void removeLastBlock() {
        throw new UnsupportedOperationException("IREE does not support removeLastBlock");
    }

    @Override
    public ParameterList getDirectParameters() {
        return new ParameterList();
    }

    /**
     * Per-input import outcome from the last forward: 1 = wrapped, 0 = staged.
     *
     * <p>Production callers should use {@link IreeEngineStats#snapshot()}'s cumulative
     * {@code wrappedImports}/{@code stagedImports} instead — this per-call query is
     * last-call-only state.
     */
    public int[] getLastImportOutcomes() {
        return IreeNative.lastImportOutcomes(handle);
    }

    /**
     * Reads this model's statistics. Returns {@code null} if no counters are attached.
     *
     * <p>Holds {@code statsLock} across the handle read and the JNI call so a concurrent
     * {@code close()} cannot free the runtime between them — that interleaving is a
     * use-after-free, and it is exactly what a monitoring poll racing a model shutdown does.
     */
    IreeModelStats toStats() {
        IreeModelCounters c = counters;
        if (c == null) {
            return null;
        }
        long wrapped = -1;
        long staged = -1;
        long stagingBytes = -1;
        long devicePeak = -1;
        long deviceLive = -1;
        synchronized (statsLock) {
            if (handle != 0L) {
                long[] raw = IreeNative.stats(handle);
                if (raw != null && raw.length == IreeNative.STAT_LENGTH) {
                    wrapped = raw[IreeNative.STAT_WRAPPED_IMPORTS];
                    staged = raw[IreeNative.STAT_STAGED_IMPORTS];
                    stagingBytes = raw[IreeNative.STAT_STAGING_BYTES];
                    if (raw[IreeNative.STAT_STATISTICS_AVAILABLE] == 1L) {
                        devicePeak = raw[IreeNative.STAT_DEVICE_BYTES_PEAK];
                        deviceLive = raw[IreeNative.STAT_DEVICE_BYTES_LIVE];
                    }
                    c.recordNativeImports(wrapped, staged);
                }
            }
        }
        return new IreeModelStats(
                c.name(),
                c.driver(),
                c.entryPoint(),
                c.parameterScopeCount(),
                c.loadNanos(),
                c.forwardCount(),
                c.forwardTotalNanos(),
                c.forwardMaxNanos(),
                wrapped,
                staged,
                stagingBytes,
                devicePeak,
                deviceLive);
    }

    @Override
    public void close() {
        // Mutual exclusion with toStats(): a concurrent snapshot poll must never observe
        // the handle between IreeNative.close() freeing it and the handle read.
        synchronized (statsLock) {
            if (handle != 0L) {
                // Deregister first so no poll can reach a handle this method is about to free.
                // Capture the native import totals into the counters on the way out, since the
                // native side dies with the runtime.
                IreeModelCounters c = counters;
                if (c != null) {
                    long[] raw = IreeNative.stats(handle);
                    if (raw != null && raw.length == IreeNative.STAT_LENGTH) {
                        c.recordNativeImports(
                                raw[IreeNative.STAT_WRAPPED_IMPORTS],
                                raw[IreeNative.STAT_STAGED_IMPORTS]);
                    }
                }
                IreeEngineStats.deregister(handle);
                IreeNative.close(handle);
                handle = 0L;
            }
        }
    }
}
