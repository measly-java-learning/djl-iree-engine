package org.measly.iree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
}
