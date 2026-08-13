package org.measly.iree.jni;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * The alignment evidence behind the STAGED outcome. The JVM guarantees
     * nothing stronger than 8-byte (long) alignment for direct-buffer
     * addresses; IREE's import precondition is 64-byte alignment
     * (IREE_HAL_HEAP_BUFFER_ALIGNMENT). The printed {@code addr%64} histogram
     * shows how often a malloc'd address happens to meet the precondition —
     * the raw material behind
     * docs/2026-08-04-borrowed-host-buffers-findings.md §4. Buffers are dropped without GC
     * between allocations so consecutive mallocs land at different offsets.
     */
    @Test
    void recordsDirectBufferAddressAlignment() {
        long[] buckets = new long[64];
        for (int i = 0; i < 1000; i++) {
            ByteBuffer b =
                    ByteBuffer.allocateDirect(4 * Float.BYTES)
                            .order(ByteOrder.nativeOrder());
            long addr = IreeNative.bufferAddress(b);
            assertTrue(addr != 0, "direct buffer must have a native address");
            assertTrue(addr % 8 == 0, "JVM floor: direct buffer addresses are 8-aligned");
            buckets[(int) (addr & 63)]++;
        }
        StringBuilder sb =
                new StringBuilder("DIRECT BUFFER ADDR%64 HISTOGRAM (1000 x 16B):");
        for (int i = 0; i < 64; i++) {
            if (buckets[i] > 0) {
                sb.append(' ').append(i).append('=').append(buckets[i]);
            }
        }
        System.out.println(sb);
    }

    /**
     * The core claim behind engine-allocated buffers: an aligned buffer
     * imports zero-copy deterministically (both inputs are aligned, so both
     * outcomes must be WRAPPED — a plain JDK buffer for either input would
     * make this flaky, see recordsDirectBufferAddressAlignment). The buffer
     * is freed explicitly here; the Cleaner path is LeakStressTest's job.
     */
    @Test
    void alignedBufferImportsZeroCopy() throws IOException {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        try {
            ByteBuffer lhs =
                    IreeNative.allocateDirectAligned(4 * Float.BYTES)
                            .order(ByteOrder.nativeOrder());
            ByteBuffer rhs =
                    IreeNative.allocateDirectAligned(4 * Float.BYTES)
                            .order(ByteOrder.nativeOrder());
            long lhsAddr = IreeNative.bufferAddress(lhs);
            long rhsAddr = IreeNative.bufferAddress(rhs);
            try {
                assertTrue(lhsAddr != 0 && lhsAddr % 64 == 0, "aligned buffer must be 64-aligned");
                assertTrue(rhsAddr != 0 && rhsAddr % 64 == 0, "aligned buffer must be 64-aligned");
                lhs.asFloatBuffer().put(ADD_LHS);
                rhs.asFloatBuffer().put(ADD_RHS);

                IreeTensor[] outputs =
                        IreeNative.invoke(
                                handle,
                                new ByteBuffer[] {lhs, rhs},
                                new long[][] {{4L}, {4L}},
                                new int[] {F32, F32});

                assertEquals(1, outputs.length);
                FloatBuffer result =
                        outputs[0].getData().order(ByteOrder.nativeOrder()).asFloatBuffer();
                assertEquals(11f, result.get(0), 1e-6f);
                assertEquals(44f, result.get(3), 1e-6f);
                assertArrayEquals(new int[] {1, 1}, IreeNative.lastImportOutcomes(handle));
            } finally {
                IreeNative.freeDirectAligned(lhsAddr);
                IreeNative.freeDirectAligned(rhsAddr);
            }
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

    @Test
    void statsReturnsNullForClosedHandle() {
        IreeNative.ensureLoaded();
        assertNull(IreeNative.stats(0L));
    }

    @Test
    void statsReportsImportOutcomesAndLength() throws Exception {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        try {
            long[] before = IreeNative.stats(handle);
            assertNotNull(before);
            assertEquals(IreeNative.STAT_LENGTH, before.length);
            assertEquals(0L, before[IreeNative.STAT_WRAPPED_IMPORTS]);
            assertEquals(0L, before[IreeNative.STAT_STAGED_IMPORTS]);
            // Against the build probe, not a hardcoded 1: this is a compile-time property, and
            // a dist built with statistics off is a supported configuration, not a failure.
            assertEquals(
                    IreeNative.statisticsAvailable() ? 1L : 0L,
                    before[IreeNative.STAT_STATISTICS_AVAILABLE]);

            assertArrayEquals(ADD_SUM, invokeAdd(handle), 1e-6f);

            long[] after = IreeNative.stats(handle);
            assertNotNull(after);
            // Two inputs crossed; each is either wrapped or staged, never both.
            assertEquals(
                    2L,
                    after[IreeNative.STAT_WRAPPED_IMPORTS]
                            + after[IreeNative.STAT_STAGED_IMPORTS]);
            // A JVM direct ByteBuffer misses IREE's 64-byte alignment precondition, so a
            // directFloats() input usually stages rather than wraps — the engine's defining
            // performance cliff and the reason this gauge exists. Only the sum is asserted,
            // above: malloc can hand back a 64-byte-aligned address, making a wrapped outcome
            // a genuine and observed possibility. The harness's IntraRuntimeInvokeCycle pins
            // the staged path deterministically with a deliberately misaligned buffer.
            //
            // stagingBytes>0 holds only when at least one input staged; both inputs can
            // legitimately wrap when malloc happens to return 64-byte-aligned addresses.
            // The invariant that matters: a staged input must have left a cached footprint.
            if (after[IreeNative.STAT_STAGED_IMPORTS] > 0L) {
                assertTrue(after[IreeNative.STAT_STAGING_BYTES] > 0L);
            }
        } finally {
            IreeNative.close(handle);
        }
    }

    @Test
    void aliveRuntimesTracksLoadAndClose() throws Exception {
        long baseline = IreeNative.aliveRuntimes();
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        assertEquals(baseline + 1, IreeNative.aliveRuntimes());
        IreeNative.close(handle);
        assertEquals(baseline, IreeNative.aliveRuntimes());
    }
}
