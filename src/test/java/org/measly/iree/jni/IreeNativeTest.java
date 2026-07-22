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

class IreeNativeTest {

    private static final int F32 = 0x21000020;
    private static final String ENTRY_POINT = "module.add";

    private static byte[] addVmfb() throws IOException {
        try (InputStream in =
                IreeNativeTest.class.getResourceAsStream("/models/add.vmfb")) {
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

    @Test
    void loadInvokeClose() throws IOException {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        assertTrue(handle != 0L);
        try {
            IreeTensor[] outputs =
                    IreeNative.invoke(
                            handle,
                            new ByteBuffer[] {
                                directFloats(1f, 2f, 3f, 4f),
                                directFloats(10f, 20f, 30f, 40f)
                            },
                            new long[][] {{4L}, {4L}},
                            new int[] {F32, F32});

            assertEquals(1, outputs.length);
            assertArrayEquals(new long[] {4L}, outputs[0].getShape());

            FloatBuffer result =
                    outputs[0].getData().order(ByteOrder.nativeOrder()).asFloatBuffer();
            assertEquals(11f, result.get(0));
            assertEquals(22f, result.get(1));
            assertEquals(33f, result.get(2));
            assertEquals(44f, result.get(3));
        } finally {
            IreeNative.close(handle);
        }
    }

    /**
     * The answer this project exists to produce: does a Java direct ByteBuffer
     * meet IREE's import preconditions, or does it silently stage a copy? The
     * test asserts only that an outcome is reported — it must not prejudge it.
     */
    @Test
    void reportsImportOutcomeForJavaDirectBuffers() throws IOException {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        try {
            IreeNative.invoke(
                    handle,
                    new ByteBuffer[] {directFloats(1f, 2f, 3f, 4f), directFloats(1f, 1f, 1f, 1f)},
                    new long[][] {{4L}, {4L}},
                    new int[] {F32, F32});

            int[] outcomes = IreeNative.lastImportOutcomes(handle);
            assertEquals(2, outcomes.length);
            System.out.println(
                    "JAVA DIRECT BYTEBUFFER IMPORT OUTCOME: "
                            + (outcomes[0] == 1 ? "WRAPPED (zero-copy)" : "STAGED (copied)"));
        } finally {
            IreeNative.close(handle);
        }
    }

    @Test
    void rejectsCorruptModel() {
        byte[] garbage = new byte[256];
        assertThrows(RuntimeException.class, () -> IreeNative.load(garbage, ENTRY_POINT, "local-sync"));
    }

    @Test
    void rejectsUnknownEntryPoint() throws IOException {
        assertThrows(
                RuntimeException.class,
                () -> IreeNative.load(addVmfb(), "module.does_not_exist", "local-sync"));
    }

    @Test
    void loadInvokeWithLocalTaskDriver() throws IOException {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-task");
        assertTrue(handle != 0L);
        try {
            IreeTensor[] outputs =
                    IreeNative.invoke(
                            handle,
                            new ByteBuffer[] {
                                directFloats(1f, 2f, 3f, 4f),
                                directFloats(10f, 20f, 30f, 40f)
                            },
                            new long[][] {{4L}, {4L}},
                            new int[] {F32, F32});
            assertEquals(1, outputs.length);
            FloatBuffer result =
                    outputs[0].getData().order(ByteOrder.nativeOrder()).asFloatBuffer();
            assertEquals(11f, result.get(0));
            assertEquals(44f, result.get(3));
        } finally {
            IreeNative.close(handle);
        }
    }

    @Test
    void rejectsUnknownDriver() throws IOException {
        assertThrows(
                RuntimeException.class,
                () -> IreeNative.load(addVmfb(), ENTRY_POINT, "no-such-driver"));
    }
}
