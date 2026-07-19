package org.measly.iree.jni;

import java.nio.ByteBuffer;
import org.measly.iree.engine.LibUtils;

/** The JNI boundary. Every method here has a counterpart in iree_djl_jni.cpp. */
public final class IreeNative {

    static {
        LibUtils.loadLibrary();
    }

    private IreeNative() {}

    /** Returns an opaque handle to the native runtime. Caller must close it. */
    public static native long load(byte[] vmfb, String entryPoint);

    /**
     * Runs the model. Inputs must be direct ByteBuffers; their addresses are
     * borrowed only for the duration of this call.
     */
    public static native IreeTensor[] invoke(
            long handle, ByteBuffer[] inputs, long[][] shapes, int[] elementTypes);

    /** Releases the native runtime. Safe to call once per handle. */
    public static native void close(long handle);

    /**
     * Per-input import outcome from the last invoke: 1 = zero-copy wrap,
     * 0 = staged copy. Exposed so tests can assert what actually happened
     * rather than assuming a borrow.
     */
    public static native int[] lastImportOutcomes(long handle);

    /** Forces the class to initialise, loading the library. */
    public static void ensureLoaded() {
        LibUtils.loadLibrary();
    }
}
