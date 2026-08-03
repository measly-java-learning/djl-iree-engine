package org.measly.iree.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resolver tests: the three front doors, the manifest-directory anchor, eager
 * existence checking, containment via {@code ..} and via symlink,
 * {@code allowUnsafePaths}, and a zero-byte asset passing. The resolver never
 * reads asset content, so tests create their own files — no fixture copying,
 * and no {@code djl-iree-model.json} is ever written into the repository's
 * {@code src/test/resources/models/} (it would hijack {@code AddModelIT}'s
 * directory door).
 */
class ModelResolverTest {

    @TempDir Path tempDir;

    private static final IreeLoadOptions DEFAULTS = IreeLoadOptions.from(Map.of());
    private static final IreeLoadOptions UNSAFE = IreeLoadOptions.from(Map.of("allowUnsafePaths", true));

    private static void touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
    }

    private static String writeManifest(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        return file.toString();
    }

    // Path.toString() on Windows uses backslashes, which are JSON escape
    // characters — embed paths via this so the manifest stays valid on both OSes.
    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ResolvedModel resolve(Path modelPath, String prefix, IreeLoadOptions options)
            throws IOException {
        return ModelResolver.resolve(modelPath, prefix, options);
    }

    @Test
    void fileDoorParsesManifestUnderAnyName() throws IOException {
        touch(tempDir.resolve("scale.vmfb"));
        touch(tempDir.resolve("scale_weights.irpa"));
        writeManifest(
                tempDir.resolve("prod-v3.json"),
                """
                {"schemaVersion":1,"program":"scale.vmfb",\
                 "parameters":{"model":"scale_weights.irpa"}}""");

        ResolvedModel r = resolve(tempDir.resolve("prod-v3.json"), "ignored", DEFAULTS);

        assertEquals(tempDir.resolve("scale.vmfb").toRealPath(), r.vmfb());
        assertEquals(1, r.parameters().size());
        assertEquals("model", r.parameters().get(0).scope());
        assertEquals(tempDir.resolve("scale_weights.irpa").toRealPath(), r.parameters().get(0).path());
    }

    @Test
    void dirDoorFindsDjlIreeModelJson() throws IOException {
        touch(tempDir.resolve("scale.vmfb"));
        writeManifest(
                tempDir.resolve("djl-iree-model.json"),
                """
                {"schemaVersion":1,"program":"scale.vmfb","entryPoint":"module.scale"}""");

        ResolvedModel r = resolve(tempDir, "ignored", DEFAULTS);

        assertEquals("module.scale", r.entryPoint());
        assertEquals(tempDir.resolve("scale.vmfb").toRealPath(), r.vmfb());
    }

    @Test
    void dirDoorWithoutManifestBuildsImplicitManifest() throws IOException {
        touch(tempDir.resolve("add.vmfb"));

        ResolvedModel r = resolve(tempDir, "add", DEFAULTS);

        assertEquals(tempDir.resolve("add.vmfb").toRealPath(), r.vmfb());
        assertTrue(r.parameters().isEmpty());
        assertNull(r.entryPoint());
    }

    @Test
    void dirDoorWithNeitherFails() {
        FileNotFoundException e =
                assertThrows(FileNotFoundException.class, () -> resolve(tempDir, "add", DEFAULTS));
        assertTrue(e.getMessage().contains(tempDir.toString()), e.getMessage());
        assertTrue(e.getMessage().contains("djl-iree-model.json"), e.getMessage());
        assertTrue(e.getMessage().contains("add.vmfb"), e.getMessage());
    }

    @Test
    void modelPathThatDoesNotExistFails() {
        FileNotFoundException e =
                assertThrows(
                        FileNotFoundException.class,
                        () -> resolve(tempDir.resolve("nope"), "add", DEFAULTS));
        assertTrue(e.getMessage().contains("nope"), e.getMessage());
    }

    @Test
    void fileDoorAnchorsToManifestDirectoryNotModelPath() throws IOException {
        touch(tempDir.resolve("a/weights.irpa"));
        writeManifest(
                tempDir.resolve("a/prod.json"),
                """
                {"schemaVersion":1,"program":"x.vmfb","parameters":{"m":"weights.irpa"}}""");
        touch(tempDir.resolve("x.vmfb")); // stray: must NOT be found (anchor is tempDir/a)
        touch(tempDir.resolve("a/x.vmfb")); // the anchored program

        ResolvedModel r = resolve(tempDir.resolve("a/prod.json"), "ignored", DEFAULTS);

        // Anchor is the manifest's parent (tempDir/a), not modelPath or tempDir:
        // the vmfb must resolve to tempDir/a/x.vmfb even though a bare x.vmfb
        // exists in tempDir.
        assertEquals(tempDir.resolve("a/x.vmfb").toRealPath(), r.vmfb());
    }

    @Test
    void eagerExistenceCheck() throws IOException {
        touch(tempDir.resolve("scale.vmfb"));
        writeManifest(
                tempDir.resolve("djl-iree-model.json"),
                """
                {"schemaVersion":1,"program":"scale.vmfb",\
                 "parameters":{"model":"missing.irpa"}}""");

        FileNotFoundException e =
                assertThrows(FileNotFoundException.class, () -> resolve(tempDir, "scale", DEFAULTS));
        assertTrue(e.getMessage().contains("missing.irpa"), e.getMessage());
        assertTrue(e.getMessage().contains("unarchive"), e.getMessage());
    }

    @Test
    void rejectsDotDotEscape() throws IOException {
        touch(tempDir.resolve("a/manifest.json"));
        touch(tempDir.resolve("b/escape.irpa"));
        writeManifest(
                tempDir.resolve("a/manifest.json"),
                """
                {"schemaVersion":1,"program":"x.vmfb","parameters":{"m":"../b/escape.irpa"}}""");
        touch(tempDir.resolve("a/x.vmfb"));

        ManifestException e =
                assertThrows(
                        ManifestException.class,
                        () -> resolve(tempDir.resolve("a/manifest.json"), "ignored", DEFAULTS));
        assertTrue(e.getMessage().contains("allowUnsafePaths"), e.getMessage());

        ResolvedModel r =
                resolve(tempDir.resolve("a/manifest.json"), "ignored", UNSAFE);
        // Flag set: no realpath normalization, so the returned path is the
        // un-normalized ../b/escape.irpa; realpath'ing it lands inside tempDir/b.
        assertEquals(
                tempDir.resolve("b/escape.irpa").toRealPath(),
                r.parameters().get(0).path().toRealPath());
    }

    @Test
    void rejectsSymlinkEscape() throws IOException {
        touch(tempDir.resolve("x.irpa"));
        touch(tempDir.resolve("a/x.vmfb"));
        Files.createSymbolicLink(tempDir.resolve("a/link.irpa"), Path.of("../x.irpa"));
        writeManifest(
                tempDir.resolve("a/manifest.json"),
                """
                {"schemaVersion":1,"program":"x.vmfb","parameters":{"m":"link.irpa"}}""");

        // The escape is invisible in the manifest text — this is the realpath
        // check's reason to exist.
        ManifestException e =
                assertThrows(
                        ManifestException.class,
                        () -> resolve(tempDir.resolve("a/manifest.json"), "ignored", DEFAULTS));
        assertTrue(e.getMessage().contains("allowUnsafePaths"), e.getMessage());

        ResolvedModel r =
                resolve(tempDir.resolve("a/manifest.json"), "ignored", UNSAFE);
        // Flag set: no realpath normalization, so the returned path is the
        // symlink itself; realpath'ing it lands on the escaped file.
        assertEquals(
                tempDir.resolve("x.irpa").toRealPath(),
                r.parameters().get(0).path().toRealPath());
    }

    @Test
    void dotDotStayingInsideIsAllowed() throws IOException {
        touch(tempDir.resolve("a/weights.irpa"));
        Files.createDirectories(tempDir.resolve("a/sub")); // .. needs an existing dir to traverse
        writeManifest(
                tempDir.resolve("a/manifest.json"),
                """
                {"schemaVersion":1,"program":"x.vmfb",\
                 "parameters":{"m":"sub/../weights.irpa"}}""");
        touch(tempDir.resolve("a/x.vmfb"));

        // Containment is on the real path, not the string: sub/../weights.irpa
        // is inside tempDir/a after normalization.
        ResolvedModel r = resolve(tempDir.resolve("a/manifest.json"), "ignored", DEFAULTS);
        assertEquals(tempDir.resolve("a/weights.irpa").toRealPath(), r.parameters().get(0).path());
    }

    @Test
    void absolutePathInsideIsAllowed() throws IOException {
        touch(tempDir.resolve("a/weights.irpa"));
        writeManifest(
                tempDir.resolve("a/manifest.json"),
                """
                {"schemaVersion":1,"program":"x.vmfb","parameters":{"m":"%s"}}
                """.formatted(jsonEscape(tempDir.resolve("a/weights.irpa").toString())));
        touch(tempDir.resolve("a/x.vmfb"));

        ResolvedModel r = resolve(tempDir.resolve("a/manifest.json"), "ignored", DEFAULTS);
        assertEquals(tempDir.resolve("a/weights.irpa").toRealPath(), r.parameters().get(0).path());
    }

    @Test
    void absolutePathOutsideIsRejected() throws IOException {
        touch(tempDir.resolve("x.irpa"));
        writeManifest(
                tempDir.resolve("a/manifest.json"),
                """
                {"schemaVersion":1,"program":"x.vmfb","parameters":{"m":"%s"}}
                """.formatted(jsonEscape(tempDir.resolve("x.irpa").toString())));
        touch(tempDir.resolve("a/x.vmfb"));

        ManifestException e =
                assertThrows(
                        ManifestException.class,
                        () -> resolve(tempDir.resolve("a/manifest.json"), "ignored", DEFAULTS));
        assertTrue(e.getMessage().contains("allowUnsafePaths"), e.getMessage());

        ResolvedModel r =
                resolve(tempDir.resolve("a/manifest.json"), "ignored", UNSAFE);
        assertEquals(tempDir.resolve("x.irpa").toRealPath(), r.parameters().get(0).path());
    }

    @Test
    void zeroByteAssetPasses() throws IOException {
        touch(tempDir.resolve("scale.vmfb"));
        Files.write(tempDir.resolve("weights.irpa"), new byte[0]);
        writeManifest(
                tempDir.resolve("djl-iree-model.json"),
                """
                {"schemaVersion":1,"program":"scale.vmfb",\
                 "parameters":{"model":"weights.irpa"}}""");

        ResolvedModel r = resolve(tempDir, "scale", DEFAULTS);

        assertEquals(tempDir.resolve("weights.irpa").toRealPath(), r.parameters().get(0).path());
        assertInstanceOf(List.class, r.parameters());
    }
}
