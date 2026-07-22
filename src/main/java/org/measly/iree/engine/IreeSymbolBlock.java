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

    IreeSymbolBlock(IreeNDManager manager, long handle) {
        this.manager = manager;
        this.handle = handle;
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

        IreeTensor[] outputs = IreeNative.invoke(handle, buffers, shapes, types);

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

    /** Per-input import outcome from the last forward: 1 = wrapped, 0 = staged. */
    public int[] getLastImportOutcomes() {
        return IreeNative.lastImportOutcomes(handle);
    }

    @Override
    public void close() {
        if (handle != 0L) {
            IreeNative.close(handle);
            handle = 0L;
        }
    }
}
