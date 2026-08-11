package org.measly.iree.engine;

import java.io.IOException;

/**
 * A model manifest that violates the schema rules or the containment policy.
 * Extends {@link IOException} so {@code ModelManifest.parse} and the resolver
 * stay free of filesystem exception types while still fitting
 * {@code Model.load}'s {@code throws IOException}.
 */
public class ManifestException extends IOException {

    /**
     * Creates the exception. Thrown by {@link ModelManifest#parse} for a schema violation
     * (missing or malformed field, unsupported {@code schemaVersion}, unrecognized {@code
     * requires} key) and by {@link ModelResolver} for a manifest asset that resolves outside its
     * manifest's directory.
     *
     * @param message a human-readable description; by convention it is prefixed with the
     *     source label (file path, or {@code "<implicit manifest>"}) that produced it
     */
    public ManifestException(String message) {
        super(message);
    }
}
