package org.measly.example;

import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.translate.Translator;

/**
 * An image-classification {@link Translator} that owns closeable resources. {@code close()} is
 * redeclared without a checked exception so callers (try-with-resources, JMH {@code @TearDown})
 * need no exception handling; implementations with no native resources make it a no-op.
 */
interface CloseableImageTranslator extends Translator<Image, Classifications>, AutoCloseable {
    @Override
    void close();
}
