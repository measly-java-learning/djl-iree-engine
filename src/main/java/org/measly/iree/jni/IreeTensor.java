package org.measly.iree.jni;

import java.nio.ByteBuffer;

/**
 * A tensor crossing the JNI boundary: direct buffer, shape, IREE element type.
 *
 * @apiNote Internal API. Not covered by compatibility guarantees; see
 *          {@link org.measly.iree.jni} for what that means in practice.
 */
public final class IreeTensor {

    private final ByteBuffer data;
    private final long[] shape;
    private final int elementType;

    /**
     * Wraps a tensor crossing the JNI boundary.
     *
     * <p>Invoked from native code — keep the signature in sync with the shim. No argument is
     * copied or validated: the instance holds exactly the references it is handed.
     *
     * @param data a direct {@link ByteBuffer} holding the tensor's elements, whose contents
     *     must outlive this instance; a non-direct buffer cannot cross to native code
     * @param shape the dimension sizes, retained by reference and never copied, so callers
     *     must not mutate it afterward
     * @param elementType an {@code iree_hal_element_type_t} value, as declared in
     *     {@code IreeDataTypes}
     */
    public IreeTensor(ByteBuffer data, long[] shape, int elementType) {
        this.data = data;
        this.shape = shape;
        this.elementType = elementType;
    }

    /**
     * Returns this tensor's data buffer — the exact instance passed to the constructor, never
     * copied. For a native-produced output this is a fresh, JVM-managed direct buffer already
     * populated with the tensor's bytes; for anything else, the contents are only as valid as
     * whatever the caller originally backed the buffer with.
     *
     * @return the retained {@link ByteBuffer}, not a copy
     */
    public ByteBuffer getData() {
        return data;
    }

    /**
     * Returns this tensor's shape — the retained array itself, not a copy. Mutating the
     * returned array corrupts the shape this tensor reports to every other caller.
     *
     * @return the retained shape array, not a copy
     */
    public long[] getShape() {
        return shape;
    }

    /**
     * Returns this tensor's element type as an {@code iree_hal_element_type_t} value, decodable
     * with {@code IreeDataTypes}.
     *
     * @return the raw {@code iree_hal_element_type_t} value
     */
    public int getElementType() {
        return elementType;
    }
}
