package org.measly.iree.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Resolves and loads the native shim. IREE_LIBRARY_PATH wins; otherwise the
 * library is extracted from the classpath to a temp file and loaded.
 *
 * <p>No content-addressed cache here, unlike the ExecuTorch engine: that exists
 * to work around Windows refusing to delete a loaded DLL, and this skeleton is
 * Linux-only.
 */
public final class LibUtils {

    private static final String LIB_NAME = "libiree_djl.so";
    private static final String PLATFORM = "linux-x86_64";
    private static boolean loaded;

    private LibUtils() {}

    public static synchronized void loadLibrary() {
        if (loaded) {
            return;
        }
        String override = System.getenv("IREE_LIBRARY_PATH");
        if (override != null && !override.isEmpty()) {
            System.load(Path.of(override).toAbsolutePath().toString());
            loaded = true;
            return;
        }
        System.load(extractFromClasspath().toAbsolutePath().toString());
        loaded = true;
    }

    private static Path extractFromClasspath() {
        String resource = "/native/" + PLATFORM + "/" + LIB_NAME;
        try (InputStream in = LibUtils.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Native library not found on the classpath at " + resource
                                + ". Build it with ./native/build.sh, or set"
                                + " IREE_LIBRARY_PATH to an existing "
                                + LIB_NAME + ".");
            }
            Path dir = Files.createTempDirectory("iree-djl");
            dir.toFile().deleteOnExit();
            Path target = dir.resolve(LIB_NAME);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract " + resource, e);
        }
    }
}
