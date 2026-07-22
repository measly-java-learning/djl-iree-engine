package org.measly.example;

import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * MobileNetV2 inference latency for this IREE engine, through DJL {@code Criteria}/{@code
 * Predictor} with the plain-Java translator (no NDArray algebra; preprocessing + softmax in Java).
 *
 * <p><b>Two arms: {@code local-sync} vs {@code local-task}</b> (JMH {@code @Param} on {@code
 * device}). Both arms run the same model, same f32 weights, and the same caller contract (one
 * invoke at a time). They differ only in whether IREE parallelizes a single invoke across an
 * intra-op worker pool ({@code local-task}) or runs it inline on the calling thread
 * ({@code local-sync}). The {@code local-task} arm's absolute latency is host-core-dependent
 * (topology = driver default) — read it as the on-machine delta against {@code local-sync}, not
 * as a portable figure.
 *
 * <p><b>Comparing against other engines is manual, by design.</b> This project is not linked to the
 * ExecuTorch/PyTorch benchmarks. To keep an eyeball comparison honest, the timed region here matches
 * that project's {@code ET_NATIVE} arm exactly: {@code predictor.predict(image)} on a pre-decoded
 * {@link Image}, with the same JMH config (warmup/iterations/fork, {@code AverageTime} steady-state +
 * {@code SingleShotTime} cold-start). Directional, not authoritative.
 */
public class MobilenetBenchmark {

    private static final String ENGINE = "IREE";
    private static final String ARTIFACT = "mobilenet_v2.vmfb";
    private static final String MODEL_NAME = "mobilenet_v2";

    /** Shared, read-only fixtures (models dir + image + synset), reused across invocations. */
    @State(Scope.Benchmark)
    public static class Config {
        Path modelsDir;
        Image image;
        List<String> synset;

        @Param({"local-sync", "local-task"})
        String device;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            modelsDir = ModelArtifacts.require(ARTIFACT).getParent();
            try (InputStream in = MobilenetBenchmark.class.getResourceAsStream("/kitten.jpg")) {
                image = ImageFactory.getInstance().fromInputStream(in);
            }
            try (InputStream in = MobilenetBenchmark.class.getResourceAsStream("/synset.txt")) {
                synset = Arrays.asList(new String(in.readAllBytes()).trim().split("\\R"));
            }
        }
    }

    /** Builds the Criteria for the IREE engine — identical to {@code MobilenetExample}. */
    static Criteria<Image, Classifications> criteria(
            Config cfg, CloseableImageTranslator translator) {
        return Criteria.builder()
                .setTypes(Image.class, Classifications.class)
                .optEngine(ENGINE)
                .optModelPath(cfg.modelsDir)
                .optModelName(MODEL_NAME)
                // Default entry point is "module.main"; the exported .vmfb uses it.
                .optOption("device", cfg.device)
                .optTranslator(translator)
                .build();
    }

    /**
     * Warm predictor held across invocations: measures steady-state inference. Teardown order is
     * predictor, then model, then translator (the translator owns native preprocessing state and
     * must outlive both).
     */
    @State(Scope.Benchmark)
    public static class Warm {
        CloseableImageTranslator translator;
        ZooModel<Image, Classifications> model;
        Predictor<Image, Classifications> predictor;

        @Setup(Level.Trial)
        public void setup(Config cfg) throws Exception {
            translator = new PlainJavaMobilenetTranslator(cfg.synset);
            try {
                model = criteria(cfg, translator).loadModel();
                predictor = model.newPredictor();
                predictor.predict(cfg.image); // warm once so the first measured op is steady-state
            } catch (Throwable t) {
                // JMH won't call @TearDown if @Setup throws, so close whatever was already opened
                // here ourselves (same order as tearDown: predictor, model, translator).
                if (predictor != null) predictor.close();
                if (model != null) model.close();
                translator.close();
                throw t;
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (predictor != null) predictor.close();
            if (model != null) model.close();
            if (translator != null) translator.close();
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Classifications steadyState(Config cfg, Warm warm) throws Exception {
        return warm.predictor.predict(cfg.image);
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Classifications coldStart(Config cfg) throws Exception {
        try (CloseableImageTranslator translator = new PlainJavaMobilenetTranslator(cfg.synset);
                ZooModel<Image, Classifications> model = criteria(cfg, translator).loadModel();
                Predictor<Image, Classifications> predictor = model.newPredictor()) {
            return predictor.predict(cfg.image); // load + first forward, per invocation
        }
    }
}
