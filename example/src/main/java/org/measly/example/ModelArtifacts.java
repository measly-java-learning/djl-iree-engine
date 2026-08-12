package org.measly.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Resolves generated model artifacts, failing fast toward the export task when they're absent. */
public final class ModelArtifacts {
    private ModelArtifacts() {}

    /**
     * Directory holding generated artifacts; overridable via -Dexample.models.dir.
     *
     * <p>Defaults to {@code build/models}, which is populated by {@code ./gradlew
     * :example:exportModels}. Must exist before any {@link #require require()} call succeeds.
     *
     * @return a Path to the artifact directory (may not exist)
     */
    public static Path dir() {
        return Paths.get(System.getProperty("example.models.dir", "build/models"));
    }

    /**
     * Returns the artifact path if present, else throws pointing at the export task.
     *
     * <p>This is the entry point a user hits first if they run the example without generating
     * models. The error message guides them to run {@code ./gradlew :example:exportModels}.
     *
     * @param name the artifact filename (e.g., {@code "mobilenet_v2.vmfb"}), resolved relative to
     *     {@link #dir()}
     * @return the Path to the artifact, guaranteed to exist
     * @throws IllegalStateException if the artifact does not exist; the message directs the caller
     *     to run {@code ./gradlew :example:exportModels}
     */
    public static Path require(String name) {
        Path p = dir().resolve(name);
        if (!Files.exists(p)) {
            throw new IllegalStateException(
                    "Missing model artifact: "
                            + p
                            + "\nGenerate it first with: ./gradlew :example:exportModels"
                            + " (requires uv on PATH).");
        }
        return p;
    }
}
