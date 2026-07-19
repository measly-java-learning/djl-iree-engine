package org.measly.iree.jni;

import java.nio.ByteBuffer;

/** A tensor crossing the JNI boundary: direct buffer, shape, IREE element type. */
public final class IreeTensor {

    private final ByteBuffer data;
    private final long[] shape;
    private final int elementType;

    /** Invoked from native code — keep the signature in sync with the shim. */
    public IreeTensor(ByteBuffer data, long[] shape, int elementType) {
        this.data = data;
        this.shape = shape;
        this.elementType = elementType;
    }

    public ByteBuffer getData() {
        return data;
    }

    public long[] getShape() {
        return shape;
    }

    public int getElementType() {
        return elementType;
    }
}
