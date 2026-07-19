package org.measly.iree.engine;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrayAdapter;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.ndarray.types.SparseFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Minimal NDArray backed by a direct ByteBuffer.
 *
 * <p>Extends NDArrayAdapter so the large NDArray surface throws
 * UnsupportedOperationException by default: this skeleton moves tensors across
 * the boundary and does no maths on the Java side.
 */
public class IreeNDArray extends NDArrayAdapter {

    private ByteBuffer data;

    IreeNDArray(NDManager manager, ByteBuffer data, Shape shape, DataType dataType) {
        super(manager, manager, shape, dataType, NDManager.nextUid());
        this.data = data;
        manager.attachInternal(uid, this);
    }

    @Override
    public void intern(NDArray replaced) {
        this.data = ((IreeNDArray) replaced).data;
    }

    @Override
    public void detach() {
        manager.detachInternal(getUid());
        manager = IreeNDManager.getSystemManager();
    }

    @Override
    public ByteBuffer toByteBuffer(boolean tryDirect) {
        return data.duplicate().order(ByteOrder.nativeOrder()).rewind();
    }

    @Override
    public SparseFormat getSparseFormat() {
        return SparseFormat.DENSE;
    }
}
