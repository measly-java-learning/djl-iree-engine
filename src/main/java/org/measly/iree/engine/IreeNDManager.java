package org.measly.iree.engine;

import ai.djl.Device;
import ai.djl.engine.Engine;
import ai.djl.ndarray.BaseNDManager;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import java.lang.ref.Cleaner;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.measly.iree.jni.IreeNative;

/**
 * Minimal tensor factory. Direct buffers only — the JNI layer requires them.
 *
 * <p><b>System property {@code iree.engine.alignedBuffers} (default
 * {@code false}):</b> when set, {@link #allocateDirect(int)} returns
 * engine-allocated buffers that are 64-byte-aligned
 * ({@code IREE_HAL_HEAP_BUFFER_ALIGNMENT}) and therefore import into IREE
 * zero-copy (kWrapped) deterministically. JVM-allocated direct buffers meet
 * that alignment only by luck (~40% of small allocations — see
 * docs/2026-08-04-borrowed-host-buffers-findings.md §4) and otherwise stage a
 * per-call copy. The aligned buffers' backing memory is freed by a
 * {@link Cleaner} once the buffer becomes unreachable; it is not counted
 * against {@code -XX:MaxDirectMemorySize}. The flag is read per allocation, so
 * it can be toggled around a measurement. The copy path stays the default.
 */
public class IreeNDManager extends BaseNDManager {

    private static final IreeNDManager SYSTEM_MANAGER = new SystemManager();

    /** Frees engine-allocated aligned buffers when their ByteBuffer dies. */
    private static final Cleaner CLEANER = Cleaner.create();

    private IreeNDManager(NDManager parent, Device device) {
        super(parent, device);
    }

    static IreeNDManager getSystemManager() {
        return SYSTEM_MANAGER;
    }

    @Override
    public ByteBuffer allocateDirect(int capacity) {
        if (Boolean.getBoolean("iree.engine.alignedBuffers")) {
            ByteBuffer buffer = IreeNative.allocateDirectAligned(capacity);
            // Capture ONLY the address primitive: capturing `buffer` (or any
            // holder of it) keeps the buffer strongly reachable and the
            // Cleaner would never fire. 0 is a no-op on the native side.
            long address = IreeNative.bufferAddress(buffer);
            CLEANER.register(buffer, () -> IreeNative.freeDirectAligned(address));
            return buffer.order(ByteOrder.nativeOrder());
        }
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
