package org.measly.iree.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IreeEngineStatsTest {

    private static final Path MODEL_DIR = Paths.get("src/test/resources/models");
    private static final Map<String, String> ADD_OPTIONS = Map.of("entryPoint", "module.add");

    private static void forwardOnce(Model model) {
        try (NDManager manager = model.getNDManager().newSubManager()) {
            NDArray lhs = manager.create(new float[] {1f, 2f, 3f, 4f}, new Shape(4));
            NDArray rhs = manager.create(new float[] {10f, 20f, 30f, 40f}, new Shape(4));
            model.getBlock().forward(null, new NDList(lhs, rhs), false);
        }
    }

    @Test
    void snapshotReportsConfiguration() {
        IreeStatsSnapshot snapshot = IreeEngineStats.snapshot();
        assertNotNull(snapshot);
        assertEquals(IreeEngine.getEngineVersion(), snapshot.getEngineVersion());
        assertEquals(IreeRuntimeInfo.DIST_TAG, snapshot.getIreeRuntimeVersion());
        assertFalse(snapshot.getPlatform().isEmpty());
        assertEquals("cached-map-write", snapshot.getStagingMode());
        assertNotNull(snapshot.getJmxStatus());
    }

    @Test
    void snapshotTracksLiveModelAndCounters() throws Exception {
        long loadedBefore = IreeEngineStats.snapshot().getModelsLoaded();
        try (Model model = Model.newInstance("add", "IREE")) {
            model.load(MODEL_DIR, "add", ADD_OPTIONS);
            forwardOnce(model);
            forwardOnce(model);

            IreeStatsSnapshot snapshot = IreeEngineStats.snapshot();
            assertEquals(loadedBefore + 1, snapshot.getModelsLoaded());
            assertEquals(1, snapshot.getModelsLive());

            IreeModelStats stats = snapshot.getModels().get(0);
            assertEquals("add", stats.getName());
            assertEquals("local-sync", stats.getDriver());
            assertEquals("module.add", stats.getEntryPoint());
            assertEquals(0, stats.getParameterScopeCount());
            assertEquals(2L, stats.getForwardCount());
            assertTrue(stats.getForwardTotalNanos() > 0);
            assertTrue(stats.getForwardMaxNanos() > 0);
            assertTrue(stats.getForwardMaxNanos() <= stats.getForwardTotalNanos());
            assertTrue(stats.getLoadNanos() > 0);
            // Two inputs per forward, two forwards; each input wrapped or staged.
            assertEquals(4L, stats.getWrappedImports() + stats.getStagedImports());
        }
    }

    @Test
    void closingFoldsCountersIntoTheRollup() throws Exception {
        long closedBefore = IreeEngineStats.snapshot().getClosedForwardCount();
        try (Model model = Model.newInstance("add", "IREE")) {
            model.load(MODEL_DIR, "add", ADD_OPTIONS);
            forwardOnce(model);
            forwardOnce(model);
            forwardOnce(model);
        }
        IreeStatsSnapshot after = IreeEngineStats.snapshot();
        assertEquals(0, after.getModelsLive());
        assertEquals(closedBefore + 3, after.getClosedForwardCount());
        assertTrue(after.getClosedForwardTotalNanos() > 0);
    }

    @Test
    void collectedModelIsFoldedIntoTheRollupWithoutClose() throws Exception {
        long closedBefore = IreeEngineStats.snapshot().getClosedForwardCount();

        // Deliberately no try-with-resources: drop the model unclosed and let GC reap it.
        Model model = Model.newInstance("add", "IREE");
        model.load(MODEL_DIR, "add", ADD_OPTIONS);
        forwardOnce(model);
        model = null;

        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            System.gc();
            Thread.sleep(50L);
            if (IreeEngineStats.snapshot().getClosedForwardCount() >= closedBefore + 1) {
                break;
            }
        }

        IreeStatsSnapshot after = IreeEngineStats.snapshot();
        assertEquals(
                closedBefore + 1,
                after.getClosedForwardCount(),
                "a collected block must reach the rollup, not be stranded in LIVE");
    }

    @Test
    void byteGaugesNeverSumUnavailableIntoTotals() throws Exception {
        try (Model model = Model.newInstance("add", "IREE")) {
            model.load(MODEL_DIR, "add", ADD_OPTIONS);
            forwardOnce(model);
            IreeStatsSnapshot snapshot = IreeEngineStats.snapshot();
            assertTrue(
                    snapshot.getTotalStagingBytes() >= 0,
                    "-1 must be skipped rather than summed");
            assertTrue(snapshot.getTotalDeviceBytesLive() >= 0);
        }
    }
}
