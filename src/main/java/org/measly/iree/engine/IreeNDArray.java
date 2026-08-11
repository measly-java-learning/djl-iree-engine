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
 * A tensor backed by a direct {@link ByteBuffer}, allocated and freed through {@link
 * IreeNDManager}.
 *
 * <p>Extends {@code NDArrayAdapter} so the large NDArray surface throws {@code
 * UnsupportedOperationException} by default: this class moves tensor data across the JNI
 * boundary and does no maths on the Java side.
 *
 * <p>Whether an array's buffer imports into IREE zero-copy or is staged is decided at
 * allocation time by {@link IreeNDManager#allocateDirect(int)} — see that class for the
 * alignment story. This class only carries the buffer; it does not know which path produced
 * it.
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
