package org.measly.example;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Per-call overhead of one {@code forward()}: Java marshalling, the JNI crossing, input
 * import/staging and output materialization, on a model whose kernel time is negligible.
 *
 * <p>MobileNet's milliseconds of kernel time bury anything in the call path, so a change to the
 * JNI shim (an added input check, say) cannot be judged there. {@code add.vmfb} adds two
 * 4-element f32 vectors, so nearly everything this measures is overhead — the most sensitive
 * place to see a per-call cost. Inputs are created once, outside the timed region; only the
 * output list is created and closed per invocation, as any caller must.
 *
 * <p>The model directory comes from {@code -Dexample.add.dir}, defaulting to the engine's
 * committed test fixture relative to the repository root.
 */
public class InvokeOverheadBenchmark {

    @State(Scope.Benchmark)
    public static class Cfg {
        Model model;
        NDManager manager;
        NDList inputs;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            Path dir = Paths.get(System.getProperty("example.add.dir", "src/test/resources/models"));
            model = Model.newInstance("add", "IREE");
            model.load(dir, "add", Map.of("entryPoint", "module.add"));
            manager = model.getNDManager().newSubManager();
            NDArray lhs = manager.create(new float[] {1f, 2f, 3f, 4f}, new Shape(4));
            NDArray rhs = manager.create(new float[] {10f, 20f, 30f, 40f}, new Shape(4));
            inputs = new NDList(lhs, rhs);
        }

        @TearDown(Level.Trial)
        public void teardown() {
            manager.close();
            model.close();
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public NDList forward(Cfg c) {
        NDList outputs = c.model.getBlock().forward(null, c.inputs, false);
        outputs.close();
        return outputs; // blackholed; its buffers are already released
    }
}
