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

/**
 * Regression tests for the invoke() shapes/types arity validation (issue #13).
 *
 * <p>The native guard (iree_djl_jni.cpp: throws "inputs, shapes, and elementTypes
 * must have the same length" before touching the marshalled copies) landed in
 * main via PR #18; this class pins the contract so it cannot regress. The
 * recovery test additionally proves the early throw leaves the runtime usable
 * — a failed invoke must not poison the handle.
 */
class IreeNativeArityTest {

    private static final int F32 = 0x21000020;
    private static final String ENTRY_POINT = "module.add";

    private static final float[] ADD_LHS = {1f, 2f, 3f, 4f};
    private static final float[] ADD_RHS = {10f, 20f, 30f, 40f};
    private static final float[] ADD_SUM = {11f, 22f, 33f, 44f};

    private static byte[] addVmfb() throws IOException {
        try (InputStream in =
                IreeNativeArityTest.class.getResourceAsStream("/models/add.vmfb")) {
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

    private static RuntimeException assertSameLengthMessage(
            ByteBuffer[] inputs, long[][] shapes, int[] elementTypes) throws IOException {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        try {
            RuntimeException ex =
                    assertThrows(
                            RuntimeException.class,
                            () -> IreeNative.invoke(handle, inputs, shapes, elementTypes));
            assertEquals("inputs, shapes, and elementTypes must have the same length", ex.getMessage());
            return ex;
        } finally {
            IreeNative.close(handle);
        }
    }

    @Test
    void invokeWithShortShapesThrows() throws IOException {
        // 2 inputs, 1 shape, 2 types: shapes is shorter than count.
        assertSameLengthMessage(
                new ByteBuffer[] {directFloats(ADD_LHS), directFloats(ADD_RHS)},
                new long[][] {{4L}},
                new int[] {F32, F32});
    }

    @Test
    void invokeWithShortTypesThrows() throws IOException {
        // 2 inputs, 2 shapes, 1 type: elementTypes is shorter than count.
        assertSameLengthMessage(
                new ByteBuffer[] {directFloats(ADD_LHS), directFloats(ADD_RHS)},
                new long[][] {{4L}, {4L}},
                new int[] {F32});
    }

    @Test
    void invokeAfterFailedInvokeStillWorks() throws IOException {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        try {
            assertThrows(
                    RuntimeException.class,
                    () ->
                            IreeNative.invoke(
                                    handle,
                                    new ByteBuffer[] {
                                        directFloats(ADD_LHS), directFloats(ADD_RHS)
                                    },
                                    new long[][] {{4L}},
                                    new int[] {F32, F32}));

            // The early throw must not have left the runtime in a broken state:
            // a well-formed invoke on the same handle still produces the golden sum.
            IreeTensor[] outputs =
                    IreeNative.invoke(
                            handle,
                            new ByteBuffer[] {directFloats(ADD_LHS), directFloats(ADD_RHS)},
                            new long[][] {{4L}, {4L}},
                            new int[] {F32, F32});
            assertEquals(1, outputs.length);
            FloatBuffer result =
                    outputs[0].getData().order(ByteOrder.nativeOrder()).asFloatBuffer();
            assertArrayEquals(ADD_SUM, new float[] {result.get(0), result.get(1), result.get(2), result.get(3)}, 1e-6f);
        } finally {
            IreeNative.close(handle);
        }
    }
}
