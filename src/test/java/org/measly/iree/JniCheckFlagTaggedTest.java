package org.measly.iree;

import org.junit.jupiter.api.Tag;

/**
 * Carries {@link JniCheckFlagTest}'s inherited assertion into the tag-filtered
 * test tasks. {@code tasks.test} excludes these three tags and the three tasks
 * each include exactly one, so no single class can run under all four; this
 * subclass is how the umbrella attachment gets proven where it matters most,
 * including {@code oomTest}.
 */
@Tag("leak")
@Tag("oom")
@Tag("stress")
class JniCheckFlagTaggedTest extends JniCheckFlagTest {}
