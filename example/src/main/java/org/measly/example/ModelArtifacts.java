package org.measly.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Resolves generated model artifacts, failing fast toward the export task when they're absent. */
public final class ModelArtifacts {
    private ModelArtifacts() {}

    /** Directory holding generated artifacts; overridable via -Dexample.models.dir. */
    public static Path dir() {
        return Paths.get(System.getProperty("example.models.dir", "build/models"));
    }

    /** Returns the artifact path if present, else throws pointing at the export task. */
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
