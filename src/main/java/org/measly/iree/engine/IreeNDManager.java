package org.measly.iree.engine;

import ai.djl.Device;
import ai.djl.engine.Engine;
import ai.djl.ndarray.BaseNDManager;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal tensor factory. Direct buffers only — the JNI layer requires them. */
public class IreeNDManager extends BaseNDManager {

    private static final IreeNDManager SYSTEM_MANAGER = new SystemManager();

    private IreeNDManager(NDManager parent, Device device) {
        super(parent, device);
    }

    static IreeNDManager getSystemManager() {
        return SYSTEM_MANAGER;
    }

    @Override
    public ByteBuffer allocateDirect(int capacity) {
        return ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
    }

    @Override
    public IreeNDArray from(NDArray array) {
        if (array == null || array instanceof IreeNDArray) {
            return (IreeNDArray) array;
        }
        IreeNDArray result = (IreeNDArray) create(array.toByteBuffer(), array.getShape(), array.getDataType());
        result.setName(array.getName());
        return result;
    }

    @Override
    public IreeNDManager newSubManager(Device device) {
        IreeNDManager manager = new IreeNDManager(this, device);
        attachInternal(manager.uid, manager);
        return manager;
    }

    @Override
    public Engine getEngine() {
        return Engine.getEngine(IreeEngine.ENGINE_NAME);
    }

    @Override
    public NDArray create(Buffer data, Shape shape, DataType dataType) {
        int size = Math.toIntExact(shape.size());
        BaseNDManager.validateBuffer(data, dataType, size);
        ByteBuffer direct = allocateDirect(size * dataType.getNumOfBytes());
        copyBuffer(data, direct);
        direct.rewind();
        return new IreeNDArray(this, direct, shape, dataType);
    }

    /** Wraps an already-direct buffer (e.g. a forward() output) without copying. */
    IreeNDArray wrap(ByteBuffer directData, Shape shape, DataType dataType) {
        return new IreeNDArray(this, directData.order(ByteOrder.nativeOrder()), shape, dataType);
    }

    /** The root manager, which is never closed. */
    private static final class SystemManager extends IreeNDManager
            implements NDManager.SystemNDManager {

        SystemManager() {
            super(null, null);
        }
    }
}
