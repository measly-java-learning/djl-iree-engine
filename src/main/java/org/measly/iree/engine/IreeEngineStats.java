package org.measly.iree.engine;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production monitoring surface for the IREE engine.
 *
 * <p>Answers the questions an operator actually asks: is inference slow, is the native side
 * growing without bound, is this deployment configured as expected, and — specific to this engine
 * — are inputs importing zero-copy or silently staging a copy on every call.
 *
 * <p><b>Not to be confused with {@code ai.djl.metric.Metrics}.</b> That is a time-series buffer
 * suited to benchmarking: its {@code limit} defaults to 0 (uncapped), so every {@code predict()}
 * retains three {@code Metric} objects indefinitely unless the caller wires both {@code setLimit}
 * and {@code setOnLimit}. Use it for profiling; use this class for production monitoring.
 */
public final class IreeEngineStats {

    /** The JMX object name this engine registers under. */
    public static final String OBJECT_NAME = "org.measly.iree:type=IreeEngineStats";

    private static final String UNKNOWN = "unknown";

    /** The staged-fallback policy the JNI compiles in; see iree_djl_jni.cpp. */
    private static final String STAGING_MODE = "cached-map-write";

    private static final ReferenceQueue<IreeSymbolBlock> REAPED = new ReferenceQueue<>();
    private static final Map<Long, ModelRef> LIVE = new ConcurrentHashMap<>();
    private static final AtomicLong MODELS_LOADED = new AtomicLong();
    private static final AtomicLong CLOSED_FORWARD_COUNT = new AtomicLong();
    private static final AtomicLong CLOSED_FORWARD_TOTAL_NANOS = new AtomicLong();
    private static final AtomicLong CLOSED_WRAPPED_IMPORTS = new AtomicLong();
    private static final AtomicLong CLOSED_STAGED_IMPORTS = new AtomicLong();

    private IreeEngineStats() {}

    /**
     * A registry entry: the block <b>weakly</b>, its counters <b>strongly</b>.
     *
     * <p>Weak on the block because this map is static and lives for the JVM. A caller who drops a
     * model without closing it already leaks the native IREE session — there is no {@code
     * Cleaner} or finalizer on {@link IreeSymbolBlock}, only {@code close()}. A strong entry here
     * would additionally pin the block, and through it the model's {@link IreeNDManager} and every
     * attached {@code NDArray}, for the life of the process: a native-only leak turned into a
     * permanent heap one, with {@code modelsLive} climbing forever. Observability must not cause
     * the leak it exists to detect.
     *
     * <p>(It does not make a leaked model free: DJL's {@code BaseNDManager} attaches every base
     * manager to a static system manager, so the model stays reachable regardless. That retention
     * is DJL's and predates this class. The weak reference stops <i>this</i> class from being a
     * cause.)
     *
     * <p>Strong on the counters because they are the only thing worth keeping — a few longs and
     * some string references, independent of the block's object graph. Holding them lets a
     * collected model's forwards still reach the rollup, which a bare {@code WeakReference} would
     * lose: exactly the history the rollup exists to preserve.
     */
    private static final class ModelRef extends WeakReference<IreeSymbolBlock> {
        final long handle;
        final IreeModelCounters counters;

        ModelRef(IreeSymbolBlock block, long handle, IreeModelCounters counters) {
            super(block, REAPED);
            this.handle = handle;
            this.counters = counters;
        }
    }

    /** Records a newly loaded model. Called from {@link IreeModel#load}. */
    static void register(long handle, IreeSymbolBlock block, IreeModelCounters counters) {
        purgeCollected(); // bounded by what the GC has reaped since the last call, usually nothing
        LIVE.put(handle, new ModelRef(block, handle, counters));
        MODELS_LOADED.incrementAndGet();
    }

    /**
     * Removes a model and folds its totals into the rollup. Called from
     * {@link IreeSymbolBlock#close()}. Idempotent: a second close finds nothing to remove.
     */
    static void deregister(long handle) {
        ModelRef ref = LIVE.remove(handle);
        if (ref != null) {
            foldIntoRollup(ref.counters);
        }
    }

    /**
     * Folds every model the GC has reclaimed since the last call into the rollup and drops its
     * entry. Called from {@link #snapshot()} and {@link #register}, so the map self-heals on any
     * activity; draining costs O(models collected), not O(models tracked).
     */
    private static void purgeCollected() {
        for (Reference<? extends IreeSymbolBlock> reaped = REAPED.poll();
                reaped != null;
                reaped = REAPED.poll()) {
            ModelRef ref = (ModelRef) reaped;
            // Two-argument remove, deliberately. IREE handles are pointers: if this handle was
            // already deregistered by close() and the allocator has since handed the same address
            // to a new model, the current mapping is a different ModelRef — removing by key alone
            // would evict the live model and double-count this one. Compare-and-remove makes both
            // impossible.
            if (LIVE.remove(ref.handle, ref)) {
                foldIntoRollup(ref.counters);
            }
        }
    }

    private static void foldIntoRollup(IreeModelCounters counters) {
        CLOSED_FORWARD_COUNT.addAndGet(counters.forwardCount());
        CLOSED_FORWARD_TOTAL_NANOS.addAndGet(counters.forwardTotalNanos());
        // Import totals live natively and die with the runtime, so they are captured at
        // deregistration time from the block's last successful stats read. A model collected
        // without a close contributes 0 here — its native counters went with the leaked runtime.
        CLOSED_WRAPPED_IMPORTS.addAndGet(counters.lastWrappedImports());
        CLOSED_STAGED_IMPORTS.addAndGet(counters.lastStagedImports());
    }

    /**
     * Captures the engine's current state.
     *
     * @return an immutable snapshot; never {@code null}, never throws
     */
    public static IreeStatsSnapshot snapshot() {
        purgeCollected();
        List<IreeModelStats> models = new ArrayList<>(LIVE.size());
        long staging = 0;
        long deviceLive = 0;
        boolean statsAvailable = true;

        for (ModelRef ref : LIVE.values()) {
            IreeSymbolBlock block = ref.get();
            if (block == null) {
                continue; // collected between the purge above and here; the next poll folds it in
            }
            IreeModelStats stats = block.toStats();
            if (stats == null) {
                // Defensive only, and not reachable through IreeModel.load: attachCounters()
                // always precedes register(). Kept so a block registered by some future path
                // without counters degrades to "absent from the list" rather than an NPE out of
                // a monitoring poll.
                continue;
            }
            models.add(stats);
            if (stats.getStagingBytes() > 0) {
                staging += stats.getStagingBytes(); // skips -1 so "unavailable" never sums in
            }
            if (stats.getDeviceBytesLive() > 0) {
                deviceLive += stats.getDeviceBytesLive();
            }
            if (stats.getDeviceBytesPeak() < 0) {
                statsAvailable = false;
            }
        }

        return new IreeStatsSnapshot(
                IreeEngine.getEngineVersion(),
                safeString(IreeRuntimeInfo.DIST_TAG),
                safePlatform(),
                safeString(LibUtils.loadedPath()),
                STAGING_MODE,
                statsAvailable,
                IreeJmx.status(),
                IreeJmx.error(),
                MODELS_LOADED.get(),
                models.size(),
                staging,
                deviceLive,
                CLOSED_FORWARD_COUNT.get(),
                CLOSED_FORWARD_TOTAL_NANOS.get(),
                CLOSED_WRAPPED_IMPORTS.get(),
                CLOSED_STAGED_IMPORTS.get(),
                Collections.unmodifiableList(models));
    }

    private static String safeString(String value) {
        return (value == null || value.isEmpty()) ? UNKNOWN : value;
    }

    private static String safePlatform() {
        try {
            return LibUtils.platform();
        } catch (RuntimeException e) {
            return UNKNOWN; // unsupported os.arch: reportable, not fatal to a monitoring read
        }
    }

    /** JMX registration state. Bodies are filled in when JMX lands; see Task 8. */
    static final class IreeJmx {
        private IreeJmx() {}

        static String status() {
            return "DISABLED";
        }

        static String error() {
            return "";
        }
    }
}
