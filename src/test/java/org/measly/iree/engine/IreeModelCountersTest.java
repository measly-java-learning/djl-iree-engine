package org.measly.iree.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IreeModelCountersTest {

    private static IreeModelCounters newCounters() {
        return new IreeModelCounters("add", "local-sync", "module.add", 0, 1_000L);
    }

    @Test
    void startsAtZero() {
        IreeModelCounters counters = newCounters();
        assertEquals(0L, counters.forwardCount());
        assertEquals(0L, counters.forwardTotalNanos());
        assertEquals(0L, counters.forwardMaxNanos());
        assertEquals("add", counters.name());
        assertEquals("local-sync", counters.driver());
        assertEquals("module.add", counters.entryPoint());
        assertEquals(0, counters.parameterScopeCount());
        assertEquals(1_000L, counters.loadNanos());
    }

    @Test
    void accumulatesCountAndTotal() {
        IreeModelCounters counters = newCounters();
        counters.recordForward(10L);
        counters.recordForward(30L);
        counters.recordForward(20L);
        assertEquals(3L, counters.forwardCount());
        assertEquals(60L, counters.forwardTotalNanos());
    }

    @Test
    void tracksMaximum() {
        IreeModelCounters counters = newCounters();
        counters.recordForward(10L);
        counters.recordForward(30L);
        counters.recordForward(20L);
        assertEquals(30L, counters.forwardMaxNanos());
    }

    @Test
    void maxNeverExceedsTotal() {
        IreeModelCounters counters = newCounters();
        for (int i = 1; i <= 100; i++) {
            counters.recordForward(i);
            assertTrue(
                    counters.forwardMaxNanos() <= counters.forwardTotalNanos(),
                    "max must never be published ahead of the total containing it");
        }
    }
}
