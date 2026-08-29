package org.measly.example;

import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.NoopTranslator;
import java.nio.file.Path;
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
 * Isolates input-marshalling cost in {@code IreeNative.invoke}: {@code manyinputs_30.vmfb} sums
 * 30 tiny (16-byte) {@code tensor<4xf32>} inputs, so the per-invoke JNI call count dominates
 * end-to-end time rather than being masked by memcpy cost (contrast {@link MobilenetBenchmark},
 * one large input, or {@link CopyCostBenchmark}, Java-side copy only).
 *
 * <p>Input count is capped at 30 by IREE's 32-binding-per-dispatch limit (see
 * tools/export_manyinputs.sh), short of the 41 the analogous {@code djl-executorch-engine} JNI
 * struct-of-arrays benchmark uses (PR #85 there), but still a large multiple of every other
 * fixture in this repo (1-2 inputs), which is what this benchmark needs to show a signal.
 * {@code local-sync} only: this isolates marshalling, not driver scheduling.
 *
 * <p>A struct-of-arrays counterpart to {@code invoke()} (flat shapes + offsets, mirroring PR #85)
 * was spiked and A/B-benchmarked here against the jagged {@code long[][]} original: at this input
 * count the flat layout showed no measurable win (trended ~7% slower, within noise) -- IREE's own
 * per-invoke dispatch overhead dominates the total far more than in ExecuTorch's engine, so the
 * saved JNI calls are too small a fraction to matter. Not pursued further; {@code invoke()} is
 * unchanged.
 */
public class ManyInputsBenchmark {

    private static final String ENGINE = "IREE";
    private static final String ARTIFACT = "manyinputs_30.vmfb";
    private static final String MODEL_NAME = "manyinputs_30";
    private static final int INPUT_COUNT = 30;

    @State(Scope.Benchmark)
    public static class Warm {
        ZooModel<NDList, NDList> model;
        Predictor<NDList, NDList> predictor;
        NDList inputs;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            try {
                Path modelsDir = ModelArtifacts.require(ARTIFACT).getParent();
                model =
                        Criteria.builder()
                                .setTypes(NDList.class, NDList.class)
                                .optEngine(ENGINE)
                                .optModelPath(modelsDir)
                                .optModelName(MODEL_NAME)
                                // Default entry point is "module.main"; the exported .vmfb uses it.
                                .optOption("device", "local-sync")
                                .optTranslator(new NoopTranslator())
                                .build()
                                .loadModel();
                predictor = model.newPredictor();
                NDManager manager = model.getNDManager();
                inputs = new NDList(INPUT_COUNT);
                for (int i = 0; i < INPUT_COUNT; i++) {
                    inputs.add(manager.create(new float[] {1f, 2f, 3f, 4f}, new Shape(4)));
                }
                predictor.predict(inputs); // warm once so the first measured call is steady-state
            } catch (Throwable t) {
                if (predictor != null) predictor.close();
                if (model != null) model.close();
                throw t;
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (predictor != null) predictor.close();
            if (model != null) model.close();
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public NDList steadyState(Warm warm) throws Exception {
        return warm.predictor.predict(warm.inputs);
    }
}
