package org.measly.example;

import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.TranslatorContext;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * PyTorch-free image-classification translator for the IREE engine. All preprocessing
 * (resize + normalize) and post-processing (softmax) run in plain Java; the input tensor is built
 * directly in the IREE {@code NDManager} via {@code create(FloatBuffer, …)} and the output
 * logits are read via {@link NDArray#toFloatArray()}. Nothing here touches {@code NDArrayEx} or a
 * PyTorch {@code NDManager}, so on a path that uses only this translator LibTorch never loads.
 */
final class PlainJavaMobilenetTranslator implements CloseableImageTranslator {

    private static final int SIZE = 224;
    private static final int PLANE = SIZE * SIZE;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    // processInput() already emits [1,3,224,224]; this no-op batchifier avoids NDArrays.stack,
    // which needs NDArrayEx support EtNDArray does not have. Batch size 1 only.
    private static final Batchifier BATCHIFIER =
            new Batchifier() {
                @Override
                public NDList batchify(NDList[] inputs) {
                    if (inputs.length != 1) {
                        throw new UnsupportedOperationException(
                                "PlainJavaMobilenetTranslator only supports a batch size of 1");
                    }
                    return inputs[0];
                }

                @Override
                public NDList[] unbatchify(NDList inputs) {
                    return new NDList[] {inputs};
                }
            };

    private final List<String> synset;

    PlainJavaMobilenetTranslator(List<String> synset) {
        this.synset = synset;
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        Image resized = input.resize(SIZE, SIZE, true); // copy=true: don't mutate a reused image
        Object wrapped = resized.getWrappedImage();
        if (!(wrapped instanceof BufferedImage)) {
            throw new IllegalStateException(
                    "PlainJavaMobilenetTranslator requires a BufferedImage-backed Image (the default"
                            + " ai.djl:api ImageFactory); got "
                            + wrapped.getClass().getName());
        }
        float[] chw = preprocess((BufferedImage) wrapped);
        NDArray array =
                ctx.getNDManager()
                        .create(FloatBuffer.wrap(chw), new Shape(1, 3, SIZE, SIZE), DataType.FLOAT32);
        return new NDList(array);
    }

    @Override
    public Classifications processOutput(TranslatorContext ctx, NDList list) {
        float[] logits = list.singletonOrThrow().toFloatArray();
        return new Classifications(synset, softmax(logits));
    }

    @Override
    public Batchifier getBatchifier() {
        return BATCHIFIER;
    }

    @Override
    public void close() {
        // no native resources to release
    }

    /** Normalized channel-major CHW {@code float[3*224*224]} from a 224x224 image. */
    static float[] preprocess(BufferedImage img) {
        if (img.getWidth() != SIZE || img.getHeight() != SIZE) {
            throw new IllegalArgumentException(
                    "expected "
                            + SIZE + "x" + SIZE + " image, got "
                            + img.getWidth() + "x" + img.getHeight());
        }
        float[] chw = new float[3 * PLANE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int argb = img.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int idx = y * SIZE + x;
                chw[idx] = (r / 255f - MEAN[0]) / STD[0];
                chw[PLANE + idx] = (g / 255f - MEAN[1]) / STD[1];
                chw[2 * PLANE + idx] = (b / 255f - MEAN[2]) / STD[2];
            }
        }
        return chw;
    }

    /** Numerically-stable softmax (max-subtraction); probabilities aligned to logit indices. */
    static List<Double> softmax(float[] logits) {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : logits) {
            if (v > max) {
                max = v;
            }
        }
        double[] exps = new double[logits.length];
        double sum = 0.0;
        for (int i = 0; i < logits.length; i++) {
            exps[i] = Math.exp(logits[i] - max);
            sum += exps[i];
        }
        List<Double> probs = new ArrayList<>(logits.length);
        for (double e : exps) {
            probs.add(e / sum);
        }
        return probs;
    }
}
