package org.measly.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelArtifactsTest {

    private static final String MOBILENET_V2_ARTIFACT = "mobilenet_v2.vmfb";

    @Test
    void requireThrowsWithExportPointerWhenMissing(@TempDir Path dir) {
        System.setProperty("example.models.dir", dir.toString());
        try {
            IllegalStateException ex =
                    assertThrows(
                            IllegalStateException.class,
                            () -> ModelArtifacts.require(MOBILENET_V2_ARTIFACT));
            assertTrue(
                    ex.getMessage().contains("./gradlew :example:exportModels"),
                    "message should point at the export task, was: " + ex.getMessage());
        } finally {
            System.clearProperty("example.models.dir");
        }
    }

    @Test
    void requireReturnsPathWhenPresent(@TempDir Path dir) throws Exception {
        System.setProperty("example.models.dir", dir.toString());
        try {
            Path vmfb = Files.createFile(dir.resolve(MOBILENET_V2_ARTIFACT));
            assertEquals(vmfb, ModelArtifacts.require(MOBILENET_V2_ARTIFACT));
        } finally {
            System.clearProperty("example.models.dir");
        }
    }
}
