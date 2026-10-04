package org.measly.iree.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/**
 * GHSA-cqqg-r2fh-7jjm: the byte count for a tensor was computed as {@code elements * width} in
 * 32-bit {@code int}, so a crafted shape wrapped it to a small or negative value. The undersized
 * buffer then passed validation and reached the native side carrying a shape far larger than
 * its backing memory. Each case here must now throw instead of producing an array.
 */
class IreeNDManagerTest {

    @Test
    void rejectsByteCountThatWrapsToZero() {
        // FLOAT32 (4 bytes) * 2^30 == 2^32, which wraps int32 to exactly 0: an empty buffer
        // would otherwise satisfy the size check.
        assertOverflowRejected(ByteBuffer.allocateDirect(0), new Shape(1L << 30), DataType.FLOAT32);
    }

    @Test
    void rejectsByteCountThatWrapsToSmallPositive() {
        // FLOAT32 * (2^30 + 4) wraps to 16, so a 16-byte buffer would pass for a ~4 GiB tensor.
        assertOverflowRejected(
                ByteBuffer.allocateDirect(16), new Shape((1L << 30) + 4), DataType.FLOAT32);
    }

    @Test
    void rejectsByteCountThatWrapsNegative() {
        // FLOAT32 * (2^29 + 1) wraps negative, and "remaining < negative" is never true.
        assertOverflowRejected(
                ByteBuffer.allocateDirect(16), new Shape((1L << 29) + 1), DataType.FLOAT32);
    }

    @Test
    void acceptsExactlySizedBuffer() {
        try (NDManager manager = IreeNDManager.getSystemManager().newSubManager()) {
            NDArray array =
                    manager.create(ByteBuffer.allocateDirect(16), new Shape(4), DataType.FLOAT32);
            assertEquals(16, array.toByteBuffer().remaining());
        }
    }

    private static void assertOverflowRejected(ByteBuffer data, Shape shape, DataType dataType) {
        try (NDManager manager = IreeNDManager.getSystemManager().newSubManager()) {
            assertThrows(ArithmeticException.class, () -> manager.create(data, shape, dataType));
        }
    }
}
