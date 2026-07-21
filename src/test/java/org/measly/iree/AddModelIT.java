package org.measly.iree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The walking-skeleton gate: add.vmfb through a real DJL Model. */
class AddModelIT {

    @Test
    void runsAddThroughDjl() throws Exception {
        Path modelDir = Paths.get("src/test/resources/models");

        try (Model model = Model.newInstance("add", "IREE")) {
            model.load(modelDir, "add", Map.of("entryPoint", "module.add"));

            try (NDManager manager = model.getNDManager().newSubManager()) {
                NDArray lhs = manager.create(new float[] {1f, 2f, 3f, 4f}, new Shape(4));
                NDArray rhs = manager.create(new float[] {10f, 20f, 30f, 40f}, new Shape(4));

                NDList outputs = model.getBlock().forward(null, new NDList(lhs, rhs), false);

                assertEquals(1, outputs.size());
                assertArrayEquals(new long[] {4L}, outputs.get(0).getShape().getShape());
                assertArrayEquals(
                        new float[] {11f, 22f, 33f, 44f},
                        outputs.get(0).toFloatArray(),
                        1e-6f);
            }
        }
    }
}
