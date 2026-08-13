package org.measly.iree;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Asserts the JVM under test runs with {@code -Xcheck:jni}, the JNI-contract
 * checker (issue #16's defect class: JNI calls made with a pending exception,
 * null array arguments). The flag is attached to the {@code Test} task umbrella
 * in {@code build.gradle.kts}, so this assertion must hold for every test task,
 * not just {@code test} — see {@link JniCheckFlagTaggedTest}, which inherits
 * this check into the tag-filtered tasks.
 *
 * <p>This asserts the checker is <em>active</em> rather than that it fires:
 * {@code IreeNativeOomTest} documents that the null-check branches are not
 * deterministically reachable, so a fire-on-demand probe cannot be a gate.
 */
class JniCheckFlagTest {

    @Test
    void jvmRunsWithXcheckJni() {
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        assertTrue(
                args.contains("-Xcheck:jni"),
                "test JVM must run with -Xcheck:jni; actual JVM arguments: " + args);
    }
}
