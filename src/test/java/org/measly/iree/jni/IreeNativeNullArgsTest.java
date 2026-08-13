package org.measly.iree.jni;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Null-argument guards at the JNI boundary. Every caller-supplied
 * array, element, and string must be validated before the native code
 * dereferences or length-checks it: the JNI spec makes
 * {@code GetArrayLength}/{@code GetStringUTFChars}/{@code GetDirectBufferAddress}
 * on null UB, which SIGSEGVs the whole JVM instead of throwing.
 *
 * <p>The suite completing in one JVM is itself the no-SIGSEGV proof — an
 * unfixed build dies at the first case. Each case pins the exact
 * {@link RuntimeException} message the guard throws.
 */
class IreeNativeNullArgsTest {

    private static final int F32 = 0x21000020;
    private static final String ENTRY_POINT = "module.add";

    private static final float[] ADD_LHS = {1f, 2f, 3f, 4f};
    private static final float[] ADD_RHS = {10f, 20f, 30f, 40f};
    private static final float[] ADD_SUM = {11f, 22f, 33f, 44f};

    private static byte[] addVmfb() throws IOException {
        try (InputStream in =
                IreeNativeNullArgsTest.class.getResourceAsStream("/models/add.vmfb")) {
            assertTrue(in != null, "add.vmfb missing — run ./tools/export_add.sh");
            return in.readAllBytes();
        }
    }

    private static ByteBuffer directFloats(float... values) {
        ByteBuffer buffer =
                ByteBuffer.allocateDirect(values.length * Float.BYTES)
                        .order(ByteOrder.nativeOrder());
        buffer.asFloatBuffer().put(values);
        return buffer;
    }

    private static void assertRuntimeMessage(
            String expected, Executable call) {
        RuntimeException ex = assertThrows(RuntimeException.class, call);
        assertEquals(expected, ex.getMessage());
    }

    // --- invoke(): null arrays and null elements ----------------------------

    @Test
    void invokeNullBuffersThrows() throws IOException {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        try {
            assertRuntimeMessage(
                    "inputs, shapes, and elementTypes must not be null",
                    () -> IreeNative.invoke(handle, null, new long[][] {{4L}}, new int[] {F32}));
        } finally {
            IreeNative.close(handle);
        }
    }

    @Test
    void invokeNullShapesThrows() throws IOException {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        try {
            assertRuntimeMessage(
                    "inputs, shapes, and elementTypes must not be null",
                    () ->
                            IreeNative.invoke(
                                    handle,
                                    new ByteBuffer[] {directFloats(1f)},
                                    null,
                                    new int[] {F32}));
        } finally {
            IreeNative.close(handle);
        }
    }

    @Test
    void invokeNullTypesThrows() throws IOException {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        try {
            assertRuntimeMessage(
                    "inputs, shapes, and elementTypes must not be null",
                    () ->
                            IreeNative.invoke(
                                    handle,
                                    new ByteBuffer[] {directFloats(1f)},
                                    new long[][] {{4L}},
                                    null));
        } finally {
            IreeNative.close(handle);
        }
    }

    @Test
    void invokeNullBufferElementThrows() throws IOException {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        try {
            assertRuntimeMessage(
                    "input must be a direct ByteBuffer",
                    () ->
                            IreeNative.invoke(
                                    handle,
                                    new ByteBuffer[] {null},
                                    new long[][] {{4L}},
                                    new int[] {F32}));
        } finally {
            IreeNative.close(handle);
        }
    }

    @Test
    void invokeNullShapeElementThrows() throws IOException {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        try {
            assertRuntimeMessage(
                    "input shape is null",
                    () ->
                            IreeNative.invoke(
                                    handle,
                                    new ByteBuffer[] {directFloats(1f)},
                                    (long[][]) new long[][] {null},
                                    new int[] {F32}));
        } finally {
            IreeNative.close(handle);
        }
    }

    // --- load(): null arrays and null strings -------------------------------

    @Test
    void loadNullVmfbThrows() {
        assertRuntimeMessage(
                "vmfb was null", () -> IreeNative.load(null, ENTRY_POINT, "local-sync"));
    }

    @Test
    void loadNullParamScopesThrows() throws IOException {
        assertRuntimeMessage(
                "paramScopes and paramPaths must not be null",
                () -> IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync", null, new String[0]));
    }

    @Test
    void loadNullParamPathsThrows() throws IOException {
        assertRuntimeMessage(
                "paramScopes and paramPaths must not be null",
                () -> IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync", new String[0], null));
    }

    @Test
    void loadNullEntryPointThrows() throws IOException {
        assertRuntimeMessage(
                "entryPoint was null",
                () -> IreeNative.load(addVmfb(), null, "local-sync", new String[0], new String[0]));
    }

    @Test
    void loadNullDeviceThrows() throws IOException {
        assertRuntimeMessage(
                "device was null",
                () -> IreeNative.load(addVmfb(), ENTRY_POINT, null, new String[0], new String[0]));
    }

    /** All the throws above left the runtime intact: a fresh load + invoke works. */
    @Test
    void stillWorksAfterAllNullCalls() throws IOException {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        try {
            IreeTensor[] outputs =
                    IreeNative.invoke(
                            handle,
                            new ByteBuffer[] {directFloats(ADD_LHS), directFloats(ADD_RHS)},
                            new long[][] {{4L}, {4L}},
                            new int[] {F32, F32});
            assertEquals(1, outputs.length);
            FloatBuffer result =
                    outputs[0].getData().order(ByteOrder.nativeOrder()).asFloatBuffer();
            assertArrayEquals(
                    ADD_SUM,
                    new float[] {result.get(0), result.get(1), result.get(2), result.get(3)},
                    1e-6f);
        } finally {
            IreeNative.close(handle);
        }
    }
}
