package org.measly.iree.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Concurrency contract for the observability surface. Tagged {@code stress} and excluded from the
 * default suite; run with {@code ./gradlew stressTest}.
 */
@Tag("stress")
class StatsConcurrencyIT {

    private static final Path MODEL_DIR = Paths.get("src/test/resources/models");
    private static final Map<String, String> ADD_OPTIONS = Map.of("entryPoint", "module.add");
    private static final int THREADS = 4;
    private static final int FORWARDS = 500;

    @Test
    void snapshotIsSafeWhileModelsForward() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            Thread worker = new Thread(() -> {
                // One Model per thread: the engine's contract. Sharing one would be a caller
                // error, not something this test should exercise.
                try (Model model = Model.newInstance("add", "IREE")) {
                    model.load(MODEL_DIR, "add", ADD_OPTIONS);
                    start.await();
                    for (int i = 0; i < FORWARDS; i++) {
                        try (NDManager manager = model.getNDManager().newSubManager()) {
                            NDArray lhs =
                                    manager.create(new float[] {1f, 2f, 3f, 4f}, new Shape(4));
                            NDArray rhs =
                                    manager.create(new float[] {10f, 20f, 30f, 40f}, new Shape(4));
                            model.getBlock().forward(null, new NDList(lhs, rhs), false);
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
            worker.start();
        }

        Thread poller = new Thread(() -> {
            try {
                start.await();
                while (done.getCount() > 0) {
                    IreeStatsSnapshot snapshot = IreeEngineStats.snapshot();
                    List<IreeModelStats> models = snapshot.getModels();
                    for (IreeModelStats stats : models) {
                        // The invariant IreeModelCounters.recordForward's write order buys:
                        // max is published only after a total that already contains it.
                        assertTrue(
                                stats.getForwardMaxNanos() <= stats.getForwardTotalNanos(),
                                "torn read: max " + stats.getForwardMaxNanos()
                                        + " > total " + stats.getForwardTotalNanos());
                        assertTrue(stats.getForwardCount() >= 0);
                        assertTrue(stats.getStagingBytes() >= -1);
                    }
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        poller.start();

        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), "workers did not finish in time");
        poller.join(30_000L);

        Throwable thrown = failure.get();
        if (thrown != null) {
            throw new AssertionError("concurrent snapshot/forward failed", thrown);
        }
    }

    @Test
    void snapshotIsSafeWhileModelsClose() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread poller = new Thread(() -> {
            try {
                while (done.getCount() > 0) {
                    IreeEngineStats.snapshot();
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        poller.start();

        try {
            // Churn load/forward/close against a continuous poll. Without statsLock this is the
            // use-after-free window: the poller reads a live handle, close() frees the runtime,
            // and the JNI stats call lands on freed memory.
            for (int i = 0; i < 300; i++) {
                try (Model model = Model.newInstance("add", "IREE")) {
                    model.load(MODEL_DIR, "add", ADD_OPTIONS);
                    try (NDManager manager = model.getNDManager().newSubManager()) {
                        NDArray lhs = manager.create(new float[] {1f, 2f, 3f, 4f}, new Shape(4));
                        NDArray rhs = manager.create(new float[] {10f, 20f, 30f, 40f}, new Shape(4));
                        model.getBlock().forward(null, new NDList(lhs, rhs), false);
                    }
                }
            }
        } finally {
            done.countDown();
            poller.join(30_000L);
        }

        Throwable thrown = failure.get();
        if (thrown != null) {
            throw new AssertionError("concurrent snapshot/close failed", thrown);
        }
    }
}
