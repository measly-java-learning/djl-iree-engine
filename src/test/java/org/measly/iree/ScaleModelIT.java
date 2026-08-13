package org.measly.iree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The end-to-end proof for manifest loading: a real manifest directory through
 * {@code Criteria}/{@code Model.load}, with weights from a scope-bound .irpa.
 * Fixtures are copied into a {@link TempDir} and the manifest is written there
 * — never into {@code src/test/resources/models/} (that would hijack
 * {@code AddModelIT}'s directory door).
 */
class ScaleModelIT {

    @TempDir Path tempDir;

    private void copyFixture(String name) throws IOException, URISyntaxException {
        Files.copy(
                Paths.get(ScaleModelIT.class.getResource("/models/" + name).toURI()),
                tempDir.resolve(name));
    }

    private float[] forwardScale() throws Exception {
        copyFixture("scale.vmfb");
        copyFixture("scale_weights.irpa");

        try (Model model = Model.newInstance("scale", "IREE")) {
            model.load(tempDir, "scale", Map.of());

            try (NDManager manager = model.getNDManager().newSubManager()) {
                NDArray input = manager.create(new float[] {1f, 2f, 3f, 4f}, new Shape(4));

                NDList outputs = model.getBlock().forward(null, new NDList(input), false);

                assertEquals(1, outputs.size());
                assertArrayEquals(new long[] {4L}, outputs.get(0).getShape().getShape());
                return outputs.get(0).toFloatArray();
            }
        }
    }

    @Test
    void loadsManifestDirectoryEndToEnd() throws Exception {
        Files.writeString(
                tempDir.resolve("djl-iree-model.json"),
                """
                {"schemaVersion":1,"program":"scale.vmfb","entryPoint":"module.scale",\
                 "parameters":{"model":"scale_weights.irpa"}}""");

        float[] out = forwardScale();

        // Golden value: the manifest's entryPoint beats the module.main
        // default — the default fails against scale.vmfb.
        assertArrayEquals(new float[] {2f, 4f, 6f, 8f}, out, 1e-6f);
    }

    @Test
    void loadOptionEntryPointOverridesManifest() throws Exception {
        copyFixture("scale.vmfb");
        copyFixture("scale_weights.irpa");
        Files.writeString(
                tempDir.resolve("djl-iree-model.json"),
                """
                {"schemaVersion":1,"program":"scale.vmfb","entryPoint":"module.main",\
                 "parameters":{"model":"scale_weights.irpa"}}""");

        try (Model model = Model.newInstance("scale", "IREE")) {
            model.load(tempDir, "scale", Map.of("entryPoint", "module.scale"));

            try (NDManager manager = model.getNDManager().newSubManager()) {
                NDArray input = manager.create(new float[] {1f, 2f, 3f, 4f}, new Shape(4));
                NDList outputs = model.getBlock().forward(null, new NDList(input), false);

                assertEquals(1, outputs.size());
                // Option beats manifest: if precedence were broken, the wrong
                // manifest entryPoint would fail at load.
                assertArrayEquals(
                        new float[] {2f, 4f, 6f, 8f}, outputs.get(0).toFloatArray(), 1e-6f);
            }
        }
    }
}
