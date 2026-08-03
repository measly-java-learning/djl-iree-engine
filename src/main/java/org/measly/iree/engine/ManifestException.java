package org.measly.iree.engine;

import java.io.IOException;

/**
 * A model manifest that violates the schema rules or the containment policy.
 * Extends {@link IOException} so {@code ModelManifest.parse} and the resolver
 * stay free of filesystem exception types while still fitting
 * {@code Model.load}'s {@code throws IOException}.
 */
public class ManifestException extends IOException {
    public ManifestException(String message) {
        super(message);
    }
}
