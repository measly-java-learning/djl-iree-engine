package org.measly.iree.jni;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class IreeNativeTest {

    private static final int F32 = 0x21000020;
    private static final String ENTRY_POINT = "module.add";

    // The add fixture's operands and golden sum, hoisted so the invoke helper
    // and the tests that assert on it share one source of truth.
    private static final float[] ADD_LHS = {1f, 2f, 3f, 4f};
    private static final float[] ADD_RHS = {10f, 20f, 30f, 40f};
    private static final float[] ADD_SUM = {11f, 22f, 33f, 44f};

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

    private static Path modelResource(String name) throws URISyntaxException {
        // The resources directory is on the test classpath as a directory, so
        // this is a file: URI and IREE can open the path directly.
        return Paths.get(IreeNativeTest.class.getResource("/models/" + name).toURI());
    }

    private static byte[] vmfb(String name) throws IOException {
        try (InputStream in = IreeNativeTest.class.getResourceAsStream("/models/" + name)) {
            assertTrue(in != null, name + " missing — run ./tools/export_scale.sh");
            return in.readAllBytes();
        }
    }

    private static float[] invokeAdd(long handle) {
        IreeTensor[] outputs =
                IreeNative.invoke(
                        handle,
                        new ByteBuffer[] {directFloats(ADD_LHS), directFloats(ADD_RHS)},
                        new long[][] {{4L}, {4L}},
                        new int[] {F32, F32});
        assertEquals(1, outputs.length);
        FloatBuffer result =
                outputs[0].getData().order(ByteOrder.nativeOrder()).asFloatBuffer();
        return new float[] {result.get(0), result.get(1), result.get(2), result.get(3)};
    }

    @Test
    void loadInvokeClose() throws IOException {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        assertTrue(handle != 0L);
        try {
            assertArrayEquals(ADD_SUM, invokeAdd(handle), 1e-6f);
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
            assertArrayEquals(ADD_SUM, invokeAdd(handle), 1e-6f);
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

    /** The JNI zip: scopes {"model"} + the splat scale_weights.irpa → 2 4 6 8. */
    @Test
    void loadsScaleWithSingleParameterArchive() throws Exception {
        byte[] bytes = vmfb("scale.vmfb");
        long handle =
                IreeNative.load(
                        bytes,
                        "module.scale",
                        "local-sync",
                        new String[] {"model"},
                        new String[] {modelResource("scale_weights.irpa").toString()});
        assertTrue(handle != 0L);
        try {
            IreeTensor[] outputs =
                    IreeNative.invoke(
                            handle,
                            new ByteBuffer[] {directFloats(1f, 2f, 3f, 4f)},
                            new long[][] {{4L}},
                            new int[] {F32});
            assertEquals(1, outputs.length);
            assertArrayEquals(new long[] {4L}, outputs[0].getShape());
            FloatBuffer result =
                    outputs[0].getData().order(ByteOrder.nativeOrder()).asFloatBuffer();
            assertEquals(2f, result.get(0));
            assertEquals(4f, result.get(1));
            assertEquals(6f, result.get(2));
            assertEquals(8f, result.get(3));
        } finally {
            IreeNative.close(handle);
        }
    }

    /** Two scopes across two archives: model(2.0) + bias(10.0) → 12 14 16 18. */
    @Test
    void loadsScale2WithTwoParameterArchives() throws Exception {
        byte[] bytes = vmfb("scale2.vmfb");
        long handle =
                IreeNative.load(
                        bytes,
                        "module.scale2",
                        "local-sync",
                        new String[] {"model", "bias"},
                        new String[] {
                            modelResource("scale_weights.irpa").toString(),
                            modelResource("scale2_bias.irpa").toString()
                        });
        assertTrue(handle != 0L);
        try {
            IreeTensor[] outputs =
                    IreeNative.invoke(
                            handle,
                            new ByteBuffer[] {directFloats(1f, 2f, 3f, 4f)},
                            new long[][] {{4L}},
                            new int[] {F32});
            assertEquals(1, outputs.length);
            FloatBuffer result =
                    outputs[0].getData().order(ByteOrder.nativeOrder()).asFloatBuffer();
            assertEquals(12f, result.get(0));
            assertEquals(14f, result.get(1));
            assertEquals(16f, result.get(2));
            assertEquals(18f, result.get(3));
        } finally {
            IreeNative.close(handle);
        }
    }

    /** The 3-arg overload must match the 5-arg form with empty arrays. */
    @Test
    void threeArgOverloadMatchesFiveArgWithEmptyArrays() throws IOException {
        long via3 = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        long via5 =
                IreeNative.load(
                        addVmfb(), ENTRY_POINT, "local-sync", new String[0], new String[0]);
        assertTrue(via3 != 0L);
        assertTrue(via5 != 0L);
        try {
            assertArrayEquals(invokeAdd(via3), invokeAdd(via5), 1e-6f);
        } finally {
            IreeNative.close(via3);
            IreeNative.close(via5);
        }
    }
}
