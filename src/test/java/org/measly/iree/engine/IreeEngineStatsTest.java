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
import org.measly.iree.jni.IreeNative;

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

    /**
     * Finds this test's own model rather than assuming it is the only one live. The registry is
     * process-wide static state; an absolute {@code getModels().get(0)} couples this assertion to
     * whatever else happens to be loaded, so one unrelated failure cascades into this one.
     */
    private static IreeModelStats onlyModelNamed(IreeStatsSnapshot snapshot, String name) {
        return snapshot.getModels().stream()
                .filter(m -> name.equals(m.getName()))
                .reduce(
                        (a, b) -> {
                            throw new AssertionError("more than one model named " + name);
                        })
                .orElseThrow(() -> new AssertionError("no model named " + name + " in snapshot"));
    }

    @Test
    void snapshotTracksLiveModelAndCounters() throws Exception {
        IreeStatsSnapshot before = IreeEngineStats.snapshot();
        long loadedBefore = before.getModelsLoaded();
        int liveBefore = before.getModelsLive();
        try (Model model = Model.newInstance("add", "IREE")) {
            model.load(MODEL_DIR, "add", ADD_OPTIONS);
            forwardOnce(model);
            forwardOnce(model);

            IreeStatsSnapshot snapshot = IreeEngineStats.snapshot();
            assertEquals(loadedBefore + 1, snapshot.getModelsLoaded());
            assertEquals(liveBefore + 1, snapshot.getModelsLive());

            IreeModelStats stats = onlyModelNamed(snapshot, "add");
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
        IreeStatsSnapshot before = IreeEngineStats.snapshot();
        long closedBefore = before.getClosedForwardCount();
        int liveBefore = before.getModelsLive();
        try (Model model = Model.newInstance("add", "IREE")) {
            model.load(MODEL_DIR, "add", ADD_OPTIONS);
            forwardOnce(model);
            forwardOnce(model);
            forwardOnce(model);
        }
        IreeStatsSnapshot after = IreeEngineStats.snapshot();
        assertEquals(liveBefore, after.getModelsLive());
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

    /**
     * The never-throws contract, exercised at the only seam a test can reach. The real trigger is
     * an {@code OutOfMemoryError} left pending by a failed {@code NewLongArray} inside
     * {@code IreeNative.stats}, which cannot be provoked deterministically; a block whose
     * {@code toStats()} throws stands in for it and drives the same backstop.
     */
    @Test
    void snapshotSurvivesAModelThatFailsToReportAndOmitsOnlyThatModel() throws Exception {
        long handle = -1L; // never a real pointer, so it cannot collide with a live model
        IreeSymbolBlock hostile =
                new IreeSymbolBlock(null, handle) {
                    @Override
                    IreeModelStats toStats() {
                        throw new IllegalStateException("stats read exploded");
                    }
                };
        IreeModelCounters counters =
                new IreeModelCounters("hostile", "local-sync", "module.add", 0, 1L);
        hostile.attachCounters(counters);
        IreeEngineStats.register(handle, hostile, counters);
        try (Model model = Model.newInstance("add", "IREE")) {
            model.load(MODEL_DIR, "add", ADD_OPTIONS);
            forwardOnce(model);

            IreeStatsSnapshot snapshot = IreeEngineStats.snapshot();
            assertNotNull(
                    onlyModelNamed(snapshot, "add"),
                    "the healthy model is still reported alongside the failing one");
            assertTrue(
                    snapshot.getModels().stream().noneMatch(m -> "hostile".equals(m.getName())),
                    "the failing model is dropped rather than failing the poll");
        } finally {
            IreeEngineStats.deregister(handle);
            // Keep the block reachable to here: a collection mid-test would fold it through the
            // reference queue instead, defeating the point.
            assertNotNull(hostile);
        }
    }

    /**
     * {@code nativeStatsAvailable} must reflect how the dist was built, never which models
     * happen to be live: inferring it from the live population reports {@code true}
     * unconditionally when none are — the wrong answer on a dist built with statistics off, at
     * exactly the moment an operator polls to see how the deployment is configured. Sampled
     * either side of a model's lifetime as well as during it, since the population is what the
     * answer must not depend on.
     */
    @Test
    void nativeStatsAvailableMatchesTheBuildRegardlessOfLiveModels() throws Exception {
        IreeNative.ensureLoaded();
        boolean fromNative = IreeNative.statisticsAvailable();

        assertEquals(
                fromNative,
                IreeEngineStats.snapshot().isNativeStatsAvailable(),
                "the flag is a build property, not a function of the live-model population");

        try (Model model = Model.newInstance("add", "IREE")) {
            model.load(MODEL_DIR, "add", ADD_OPTIONS);
            forwardOnce(model);
            assertEquals(fromNative, IreeEngineStats.snapshot().isNativeStatsAvailable());
        }
        assertEquals(fromNative, IreeEngineStats.snapshot().isNativeStatsAvailable());
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
