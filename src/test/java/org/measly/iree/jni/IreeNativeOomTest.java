package org.measly.iree.jni;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * JNI output-marshalling failure-contract tests under a constrained heap
 * (issue #16). Run via {@code ./gradlew oomTest} — the fixture is exported by
 * the {@code exportOomFixture} task and the test JVM is deliberately small
 * ({@code -Xmx128m}, no heap dump: the OOM is the expected outcome).
 *
 * <p>Honest framing: this suite asserts the <em>contract</em> — an output
 * allocation failure surfaces as a clean {@link OutOfMemoryError} and the JVM
 * (and a fresh runtime) survives. The specific null-check branches added in
 * the fix are not deterministically reachable: they need heap exhaustion
 * mid-loop, and the 512 MiB output fails first at the already-checked
 * {@code ByteBuffer.allocateDirect}. This is the executorch PR #18 test shape
 * adapted to IREE's direct-buffer output path.
 */
@Tag("oom")
class IreeNativeOomTest {

    private static final int F32 = 0x21000020;
    private static final String ENTRY_POINT = "module.add";

    private static final float[] ADD_LHS = {1f, 2f, 3f, 4f};
    private static final float[] ADD_RHS = {10f, 20f, 30f, 40f};
    private static final float[] ADD_SUM = {11f, 22f, 33f, 44f};

    private static byte[] addVmfb() throws IOException {
        try (InputStream in =
                IreeNativeOomTest.class.getResourceAsStream("/models/add.vmfb")) {
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

    private static byte[] oomFixture() throws IOException {
        String path = System.getProperty("ireeOomFixture");
        if (path == null || !Files.isRegularFile(Path.of(path))) {
            fail("run ./gradlew oomTest — the fixture is exported by the exportOomFixture task");
        }
        return Files.readAllBytes(Path.of(path));
    }

    /**
     * The splat fixture's output is 512 MiB — below the 2 GiB JNI direct-buffer
     * guard (so the invoke reaches the allocation path) but above the default
     * {@code MaxDirectMemorySize} ({@code -Xmx128m}), so
     * {@code ByteBuffer.allocateDirect} throws {@link OutOfMemoryError}
     * deterministically. The pending OOM propagates cleanly through the JNI
     * boundary; the follow-up golden invoke on a fresh handle proves the
     * failure path left the JVM and runtime intact.
     */
    @Test
    void oversizedOutputThrowsCleanOutOfMemoryErrorAndJvmSurvives() throws IOException {
        long splat = IreeNative.load(oomFixture(), "module.main", "local-sync");
        assertTrue(splat != 0L);
        long add = 0L;
        try {
            assertThrows(
                    OutOfMemoryError.class,
                    () ->
                            IreeNative.invoke(
                                    splat,
                                    new ByteBuffer[] {directFloats(1f)},
                                    new long[][] {{1L}},
                                    new int[] {F32}));

            add = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
            assertTrue(add != 0L);
            IreeTensor[] outputs =
                    IreeNative.invoke(
                            add,
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
            IreeNative.close(splat);
            if (add != 0L) {
                IreeNative.close(add);
            }
        }
    }
}
