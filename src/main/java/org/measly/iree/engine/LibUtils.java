package org.measly.iree.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Resolves and loads the native shim. IREE_LIBRARY_PATH wins; otherwise the
 * library is extracted from the classpath into a content-addressed cache keyed
 * by SHA-256 of the resource bytes. Windows cannot delete a loaded DLL, so a
 * per-JVM temp file would leak a full copy per run; the stable per-content
 * directory is reused instead (ported from the ExecuTorch engine).
 */
public final class LibUtils {

    private static final int BUF = 64 * 1024;
    private static boolean loaded;
    private static String loadedPath;

    private LibUtils() {}

    // Not unit-tested: drives System.load, the IREE_LIBRARY_PATH env override, and
    // classpath extraction, all of which need the real native library and JVM state.
    // platform(), libName() and cacheRoot() are the unit-tested seams.
    /**
     * Loads the native shim, extracting it first if needed. See the class comment for the
     * {@code IREE_LIBRARY_PATH} override and the content-addressed cache this falls back to.
     *
     * <p>Idempotent and synchronized: the first call in a JVM does the work, every later call
     * (from any thread) returns immediately. There is no unload — the library lives for the
     * process.
     *
     * @throws IllegalStateException if the classpath resource is missing, or extraction fails
     * @throws UnsupportedOperationException if {@code os.name}/{@code os.arch} is not one this
     *     engine ships a native library for; see {@link #platform()}
     */
    public static synchronized void loadLibrary() {
        if (loaded) {
            return;
        }
        String override = System.getenv("IREE_LIBRARY_PATH");
        if (override != null && !override.isEmpty()) {
            String path = Path.of(override).toAbsolutePath().toString();
            System.load(path);
            loadedPath = path;
            loaded = true;
            return;
        }
        String platform = platform();
        String lib = libName(platform);
        String resource = "/native/" + platform + "/" + lib;
        try {
            // Hash-only first pass. The cache key must be known before the path exists,
            // and a hit must not rewrite the library. A miss pays a second read; that
            // happens once per version per host.
            Path target = cacheRoot().resolve(sha256(resource)).resolve(lib);
            if (!Files.isRegularFile(target)) {
                extract(resource, target);
            }
            String path = target.toAbsolutePath().toString();
            System.load(path);
            loadedPath = path;
            loaded = true;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract native library " + resource, e);
        }
    }

    /**
     * The absolute path last passed to {@code System.load}, or {@code null} before any load.
     * Reported by the observability snapshot so an operator can tell which library a process
     * actually loaded — the {@code IREE_LIBRARY_PATH} override makes that non-obvious.
     */
    static String loadedPath() {
        return loadedPath;
    }

    /**
     * Resolves the platform directory under /native on the classpath. Mirrors
     * the ExecuTorch engine's seam (os.name/os.arch mapping) extended for
     * aarch64 and windows-x86_64.
     */
    static String platform() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (os.contains("linux") && x64) {
            return "linux-x86_64";
        }
        if (os.contains("linux") && (arch.equals("aarch64") || arch.equals("arm64"))) {
            return "linux-aarch64";
        }
        if (os.contains("windows") && x64) {
            return "windows-x86_64";
        }
        throw new UnsupportedOperationException(
                "IREE engine supports only linux-x86_64, linux-aarch64, and windows-x86_64, got: "
                        + os + "/" + arch);
    }

    /** MSVC emits no `lib` prefix and a .dll suffix. Keep in sync with nativeLibName in build.gradle.kts. */
    static String libName(String platform) {
        return platform.startsWith("windows-") ? "iree_djl.dll" : "libiree_djl.so";
    }

    /**
     * Cache location. Windows cannot delete a loaded DLL, so a per-JVM temp file
     * would leak a full copy per run; a stable per-content directory is reused
     * instead. Root: %LOCALAPPDATA%\iree-djl on Windows, else $XDG_CACHE_HOME if
     * set, else ~/.cache/iree-djl.
     */
    static Path cacheRoot() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("windows")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isEmpty()) {
                return Paths.get(localAppData, "iree-djl");
            }
            return Paths.get(System.getProperty("user.home"), "AppData", "Local", "iree-djl");
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isEmpty()) {
            return Paths.get(xdg, "iree-djl");
        }
        return Paths.get(System.getProperty("user.home"), ".cache", "iree-djl");
    }

    private static InputStream open(String resource) {
        InputStream is = LibUtils.class.getResourceAsStream(resource);
        if (is == null) {
            throw new IllegalStateException(
                    "Native library not found on the classpath at " + resource
                            + ". Build it with ./native/build.sh, or set"
                            + " IREE_LIBRARY_PATH to an existing native library.");
        }
        return is;
    }

    private static String sha256(String resource) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JRE but is unavailable", e);
        }
        byte[] buf = new byte[BUF];
        try (InputStream is = new DigestInputStream(open(resource), md)) {
            while (is.read(buf) != -1) {
                // DigestInputStream updates the digest as a side effect of reading.
            }
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static void extract(String resource, Path target) throws IOException {
        Path dir = target.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            try (InputStream is = open(resource); OutputStream os = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[BUF];
                int n;
                while ((n = is.read(buf)) != -1) {
                    os.write(buf, 0, n);
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // A concurrent JVM published first. The path is content-addressed, so the
                // winner's bytes are ours byte-for-byte: adopt it rather than overwrite a
                // file another process may have already mapped. Windows refuses the replace
                // with AccessDeniedException/FileSystemException (not
                // FileAlreadyExistsException), so catch IOException broadly and re-throw
                // anything that did not result in a published file.
                if (!Files.isRegularFile(target)) {
                    throw e;
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
