package org.measly.iree.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LibUtilsTest {

    @Test
    void platformResolvesLinuxX8664() {
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Linux");
            System.setProperty("os.arch", "amd64");
            assertEquals("linux-x86_64", LibUtils.platform());
        } finally {
            System.setProperty("os.name", os);
            System.setProperty("os.arch", arch);
        }
    }

    @Test
    void platformResolvesLinuxAarch64() {
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Linux");
            System.setProperty("os.arch", "aarch64");
            assertEquals("linux-aarch64", LibUtils.platform());
        } finally {
            System.setProperty("os.name", os);
            System.setProperty("os.arch", arch);
        }
    }

    @Test
    void platformRejectsUnsupportedArch() {
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Linux");
            System.setProperty("os.arch", "sparc");
            assertThrows(UnsupportedOperationException.class, LibUtils::platform);
        } finally {
            System.setProperty("os.name", os);
            System.setProperty("os.arch", arch);
        }
    }

    @Test
    void platformRejectsUnsupportedOs() {
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty("os.arch", "amd64");
            assertThrows(UnsupportedOperationException.class, LibUtils::platform);
        } finally {
            System.setProperty("os.name", os);
            System.setProperty("os.arch", arch);
        }
    }

    @Test
    void platformResolvesWindowsX8664() {
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");
            assertEquals("windows-x86_64", LibUtils.platform());
        } finally {
            System.setProperty("os.name", os);
            System.setProperty("os.arch", arch);
        }
    }

    @Test
    void libNameIsPlatformSpecific() {
        assertEquals("libiree_djl.so", LibUtils.libName("linux-x86_64"));
        assertEquals("iree_djl.dll", LibUtils.libName("windows-x86_64"));
    }

    // Asserts the OS branch is actually taken. Checking only the leaf name would pass on
    // either branch and prove nothing, so compare the two roots against each other: they
    // must differ, and both must be absolute (System.load requires an absolute path).
    @Test
    void cacheRootBranchesOnOs() {
        String os = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            Path windows = LibUtils.cacheRoot();
            System.setProperty("os.name", "Linux");
            Path linux = LibUtils.cacheRoot();

            assertEquals("iree-djl", windows.getFileName().toString());
            assertEquals("iree-djl", linux.getFileName().toString());
            assertNotEquals(windows, linux, "cache root must differ per OS");
            assertTrue(windows.isAbsolute(), "windows cache root must be absolute: " + windows);
            assertTrue(linux.isAbsolute(), "linux cache root must be absolute: " + linux);
            assertTrue(linux.toString().contains(".cache") || System.getenv("XDG_CACHE_HOME") != null,
                    "linux cache root should honour XDG_CACHE_HOME or fall back to ~/.cache: " + linux);
        } finally {
            System.setProperty("os.name", os);
        }
    }

    @Test
    void loadedPathIsRecordedAfterLoad() {
        LibUtils.loadLibrary();
        String path = LibUtils.loadedPath();
        assertNotNull(path, "loadedPath must be recorded once the library is loaded");
        assertFalse(path.isEmpty());
        assertTrue(
                path.endsWith("libiree_djl.so") || path.endsWith("iree_djl.dll"),
                "expected a native library filename, got: " + path);
    }

    @Test
    void distTagIsGenerated() {
        assertNotNull(IreeRuntimeInfo.DIST_TAG);
        assertTrue(
                IreeRuntimeInfo.DIST_TAG.startsWith("v"),
                "expected a release tag like v3.11.0-11, got: " + IreeRuntimeInfo.DIST_TAG);
    }
}
