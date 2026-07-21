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

/** Classifies a bundled image with MobileNetV2 through the IREE engine and prints top-5. */
public final class MobilenetExample {
    private static final String ENGINE = "IREE";
    private static final String ARTIFACT = "mobilenet_v2.vmfb";
    private MobilenetExample() {}

    public static void main(String[] args) throws Exception {
 
        Path models = ModelArtifacts.require(ARTIFACT).getParent();
        List<String> synset = loadSynset();

        try (CloseableImageTranslator translator = new PlainJavaMobilenetTranslator(synset);
                InputStream imageStream =
                        MobilenetExample.class.getResourceAsStream("/kitten.jpg");
                ZooModel<Image, Classifications> model =
                        Criteria.builder()
                                .setTypes(Image.class, Classifications.class)
                                .optEngine(ENGINE)
                                .optModelPath(models)
                                .optModelName("mobilenet_v2")
                                // An IREE model requires an entrypoint.  The default entrypoint is "module.main", but inspect your MLIR to verify
                                //.optOption("entryPoint", "module.main")
                                .optTranslator(translator)
                                .build()
                                .loadModel();
                Predictor<Image, Classifications> predictor = model.newPredictor()) {
            Image image = ImageFactory.getInstance().fromInputStream(imageStream);
            Classifications result = predictor.predict(image);
            System.out.println("Top-5 (MobileNetV2):");
            for (Classifications.Classification c : result.topK(5)) {
                System.out.printf("  %-30s %.4f%n", c.getClassName(), c.getProbability());
            }
        }
    }

    private static List<String> loadSynset() throws Exception {
        try (InputStream in = MobilenetExample.class.getResourceAsStream("/synset.txt")) {
            if (in == null) {
                throw new IllegalStateException("synset.txt not found on classpath");
            }
            return Arrays.asList(new String(in.readAllBytes()).trim().split("\\R"));
        }
    }
}
