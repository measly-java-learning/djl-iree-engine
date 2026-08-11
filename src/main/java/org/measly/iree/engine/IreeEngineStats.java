package org.measly.iree.engine;

import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.measly.iree.jni.IreeNative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger logger = LoggerFactory.getLogger(IreeEngineStats.class);

    /** The property that disables auto-registration. */
    public static final String JMX_ENABLED_PROPERTY = "ai.djl.iree.jmx_enabled";

    // Guards the one-shot auto-registration attempt. A failed attempt is not retried: a
    // per-load retry would log on every load and re-run a failure we already reported.
    private static final AtomicBoolean JMX_ATTEMPTED = new AtomicBoolean();

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

    // Null until the native probe has been answered once; see nativeStatsAvailable().
    private static volatile Boolean nativeStatsAvailable;

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
     * <p><b>Never throws</b>, and that is enforced rather than merely intended: the native read
     * is absorbed in {@link IreeSymbolBlock#toStats()} (degrading to the {@code -1} gauges), a
     * model that fails unforeseeably is dropped from the list rather than failing the poll, and
     * {@link #safePlatform()} covers an unsupported {@code os.arch}. A monitoring poll must not
     * be the thing that breaks production.
     *
     * @return an immutable snapshot; never {@code null}
     */
    public static IreeStatsSnapshot snapshot() {
        purgeCollected();
        List<IreeModelStats> models = new ArrayList<>(LIVE.size());
        long staging = 0;
        long deviceLive = 0;

        for (ModelRef ref : LIVE.values()) {
            IreeSymbolBlock block = ref.get();
            if (block == null) {
                continue; // collected between the purge above and here; the next poll folds it in
            }
            IreeModelStats stats;
            try {
                stats = block.toStats();
            } catch (Throwable t) {
                // Backstop for the never-throws contract. toStats() already absorbs failure of
                // the native read, so reaching here means something unforeseen; drop the one
                // model rather than the whole poll. Debug, not warn: a poll loop logs at its own
                // rate, and the operator's signal is the model's absence from getModels().
                logger.debug("IREE stats read failed for one model; omitting it from the snapshot", t);
                continue;
            }
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
        }

        return new IreeStatsSnapshot(
                IreeEngine.getEngineVersion(),
                safeString(IreeRuntimeInfo.DIST_TAG),
                safePlatform(),
                safeString(LibUtils.loadedPath()),
                STAGING_MODE,
                nativeStatsAvailable(),
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

    /**
     * Whether IREE's HAL allocator statistics are compiled into the loaded runtime.
     *
     * <p>This is a <b>build</b> property, so it is read from a handle-free native probe rather
     * than inferred from whichever models happen to be live. Inferring it was wrong in the
     * direction that matters most: a process that has loaded nothing yet, or has closed
     * everything, had no model to answer and so reported {@code true} unconditionally — including
     * on a dist built with {@code -DIREE_STATISTICS_ENABLE=0}. That is exactly the moment an
     * operator polls to check how the deployment is configured.
     *
     * <p>Cached after the first successful probe: the answer is fixed at compile time and cannot
     * change within a process. Before the native library is loaded there is no one to ask, and a
     * monitoring poll must not force a library load as a side effect, so the compiled default
     * (statistics on, per the header and the CMake agreement check) stands in until the first
     * model load makes the real answer reachable.
     */
    private static boolean nativeStatsAvailable() {
        Boolean cached = nativeStatsAvailable;
        if (cached != null) {
            return cached;
        }
        if (LibUtils.loadedPath() == null) {
            return true;
        }
        try {
            boolean available = IreeNative.statisticsAvailable();
            nativeStatsAvailable = available;
            return available;
        } catch (Throwable t) {
            logger.debug("IREE statistics-availability probe failed; assuming available", t);
            return true;
        }
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

    /** JMX registration state, reported in every snapshot. */
    static final class IreeJmx {
        private static volatile String status = "DISABLED";
        private static volatile String error = "";

        private IreeJmx() {}

        static String status() {
            return status;
        }

        static String error() {
            return error;
        }

        static void registered() {
            status = "REGISTERED";
            error = "";
        }

        static void failed(String reason) {
            status = "FAILED";
            error = reason == null ? "" : reason;
        }

        static void disabled() {
            status = "DISABLED";
            error = "";
        }
    }

    /** The MXBean implementation. Held only by the platform MBean server. */
    private static final class Bean implements IreeEngineStatsMXBean {
        @Override
        public IreeStatsSnapshot getSnapshot() {
            return IreeEngineStats.snapshot();
        }
    }

    /**
     * Registers the MXBean on the platform MBean server. Idempotent: a second call on an
     * already-registered name is a no-op rather than an error.
     *
     * <p>Never throws. A failure — name collision, {@code SecurityManager}, a restricted
     * container — logs one warning and sets {@code jmxStatus=FAILED} in the snapshot.
     */
    public static void registerMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName objectName = new ObjectName(OBJECT_NAME);
            if (server.isRegistered(objectName)) {
                IreeJmx.registered();
                return;
            }
            server.registerMBean(new Bean(), objectName);
            IreeJmx.registered();
        } catch (Exception e) {
            IreeJmx.failed(e.toString());
            logger.warn(
                    "IREE engine JMX registration failed for {}; snapshot() is unaffected.",
                    OBJECT_NAME,
                    e);
        }
    }

    /** Removes the MXBean if registered. Never throws. */
    public static void unregisterMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName objectName = new ObjectName(OBJECT_NAME);
            if (server.isRegistered(objectName)) {
                server.unregisterMBean(objectName);
            }
            IreeJmx.disabled();
        } catch (InstanceNotFoundException e) {
            IreeJmx.disabled(); // raced with another unregister; nothing to do
        } catch (Exception e) {
            logger.warn("IREE engine JMX unregistration failed for {}", OBJECT_NAME, e);
        }
    }

    /**
     * One-shot auto-registration, called from {@link IreeModel#load}. Opt out with
     * {@code -Dai.djl.iree.jmx_enabled=false}. Never retried and never fails a load.
     */
    static void registerMBeanOnce() {
        if (!JMX_ATTEMPTED.compareAndSet(false, true)) {
            return;
        }
        if (!jmxEnabled()) {
            IreeJmx.disabled();
            return;
        }
        registerMBean();
    }

    /**
     * Whether auto-registration is permitted. Opt out with
     * {@code -Dai.djl.iree.jmx_enabled=false}; anything else, including an absent property, is
     * opt-in. A property unreadable under a restrictive {@code SecurityManager} stays off.
     *
     * <p>Split out of {@link #registerMBeanOnce()} so it is reachable from a test. Folded in, the
     * only way to observe the decision was through the one-shot latch, which fires once per JVM.
     */
    static boolean jmxEnabled() {
        try {
            return !"false".equalsIgnoreCase(System.getProperty(JMX_ENABLED_PROPERTY));
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * Re-arms the one-shot latch so {@link #registerMBeanOnce()} will attempt again.
     *
     * <p><b>Test seam.</b> Auto-registration fires once per JVM by design, which leaves the
     * opt-out branch unreachable from a test that runs in the same JVM as any other model load.
     * Nothing in production calls this.
     */
    static void resetJmxAutoRegistrationForTesting() {
        JMX_ATTEMPTED.set(false);
    }
}
