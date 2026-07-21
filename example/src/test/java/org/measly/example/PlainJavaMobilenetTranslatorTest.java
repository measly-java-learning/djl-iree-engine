package org.measly.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlainJavaMobilenetTranslatorTest {

    private static final int SIZE = 224;
    private static final int PLANE = SIZE * SIZE;

    @Test
    void preprocessProducesNormalizedChannelMajorCHW() {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        // whole image black; set pixel (0,0) pure red.
        img.setRGB(0, 0, (255 << 16)); // R=255,G=0,B=0

        float[] chw = PlainJavaMobilenetTranslator.preprocess(img);

        assertEquals(3 * PLANE, chw.length);
        // Red pixel at (0,0): R plane idx 0, G plane, B plane.
        assertEquals((1f - 0.485f) / 0.229f, chw[0], 1e-4);           // R=255
        assertEquals((0f - 0.456f) / 0.224f, chw[PLANE + 0], 1e-4);   // G=0
        assertEquals((0f - 0.406f) / 0.225f, chw[2 * PLANE + 0], 1e-4); // B=0
        // A black pixel elsewhere: R plane idx 1.
        assertEquals((0f - 0.485f) / 0.229f, chw[1], 1e-4);
    }

    @Test
    void preprocessRejectsWrongSize() {
        BufferedImage small = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        assertThrows(
                IllegalArgumentException.class,
                () -> PlainJavaMobilenetTranslator.preprocess(small));
    }

    @Test
    void softmaxSumsToOneAndPicksArgmax() {
        List<Double> probs = PlainJavaMobilenetTranslator.softmax(new float[] {1f, 2f, 3f});
        double sum = probs.get(0) + probs.get(1) + probs.get(2);
        assertEquals(1.0, sum, 1e-9);
        assertEquals(0.6652, probs.get(2), 1e-3); // exp-normalized largest
        assertTrue(probs.get(2) > probs.get(1) && probs.get(1) > probs.get(0));
    }

    @Test
    void softmaxIsNumericallyStable() {
        List<Double> probs = PlainJavaMobilenetTranslator.softmax(new float[] {1000f, 1001f, 1002f});
        double sum = probs.get(0) + probs.get(1) + probs.get(2);
        assertEquals(1.0, sum, 1e-9); // no NaN/Inf despite large magnitudes
        assertEquals(0.6652, probs.get(2), 1e-3); // same shape as {1,2,3}
    }
}
