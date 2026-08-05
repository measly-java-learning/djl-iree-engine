package org.measly.iree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.djl.Model;
import ai.djl.engine.Engine;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.measly.iree.jni.IreeNative;

/**
 * Memory-leak stress test under a constrained heap/direct-memory budget (set by the leakTest
 * Gradle task). Loops load/invoke/close so an unbalanced native retain/release, or a leaked
 * direct ByteBuffer at the JNI boundary, exhausts the budget and fails with OOM instead of
 * passing silently. Tagged "leak" so it runs only via `./gradlew leakTest`, never in the
 * normal suite.
 */
@Tag("leak")
class LeakStressTest {

    private static final int ITERATIONS = 2000;

    @Test
    void loadInvokeCloseDoesNotLeak() throws Exception {
        Path modelDir = Paths.get("src/test/resources/models");
        float[] expected = {11f, 22f, 33f, 44f};

        for (int i = 0; i < ITERATIONS; i++) {
            try (Model model = Model.newInstance("add", "IREE")) {
                model.load(modelDir, "add", Map.of("entryPoint", "module.add"));
                try (NDManager manager = model.getNDManager().newSubManager()) {
                    NDArray lhs = manager.create(new float[] {1f, 2f, 3f, 4f}, new Shape(4));
                    NDArray rhs = manager.create(new float[] {10f, 20f, 30f, 40f}, new Shape(4));
                    NDList outputs = model.getBlock().forward(null, new NDList(lhs, rhs), false);
                    assertArrayEquals(expected, outputs.get(0).toFloatArray(), 1e-6f);
                }
            }
        }
    }

    /**
     * The Cleaner lifetime proof for the aligned-buffer borrow path: 10_000
     * engine-allocated buffers through {@code IreeNDManager.allocateDirect}
     * (the only path that registers the Cleaner), all references dropped, then
     * GC pressure until the native live count reaches zero. The aligned
     * buffers are JNI-created and NOT counted against
     * {@code -XX:MaxDirectMemorySize}, so the native counter — not direct
     * memory pressure — is the leak signal: if the Cleaner never fired, the
     * count stays at 10_000 and this fails after 5 s.
     */
    @Test
    void alignedBuffersAreFreedByCleaner() throws Exception {
        System.setProperty("iree.engine.alignedBuffers", "true");
        try (NDManager manager = Engine.getEngine("IREE").newBaseManager()) {
            for (int i = 0; i < 10_000; i++) {
                manager.allocateDirect(1024); // reference dropped immediately
            }
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline
                    && IreeNative.aliveAlignedBuffers() != 0) {
                System.gc();
                Thread.sleep(25);
            }
            assertEquals(
                    0,
                    IreeNative.aliveAlignedBuffers(),
                    "Cleaner must free every aligned buffer once unreachable");
        } finally {
            System.clearProperty("iree.engine.alignedBuffers");
        }
    }
}
