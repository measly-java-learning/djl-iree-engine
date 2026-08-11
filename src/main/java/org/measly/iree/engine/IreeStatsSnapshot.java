package org.measly.iree.engine;

import java.util.List;

/**
 * An immutable point-in-time view of engine configuration, process totals, and live models.
 *
 * <p><b>Getter-only by contract</b>, for the same MXBean {@code CompositeData} reason as
 * {@link IreeModelStats}.
 *
 * <p>Unresolvable string fields report {@code "unknown"} rather than {@code null}, so a
 * monitoring consumer never has to null-check.
 */
public final class IreeStatsSnapshot {

    private final String engineVersion;
    private final String ireeRuntimeVersion;
    private final String platform;
    private final String nativeLibraryPath;
    private final String stagingMode;
    private final boolean nativeStatsAvailable;
    private final String jmxStatus;
    private final String jmxError;
    private final long modelsLoaded;
    private final int modelsLive;
    private final long totalStagingBytes;
    private final long totalDeviceBytesLive;
    private final long closedForwardCount;
    private final long closedForwardTotalNanos;
    private final long closedWrappedImports;
    private final long closedStagedImports;
    private final List<IreeModelStats> models;

    IreeStatsSnapshot(
            String engineVersion,
            String ireeRuntimeVersion,
            String platform,
            String nativeLibraryPath,
            String stagingMode,
            boolean nativeStatsAvailable,
            String jmxStatus,
            String jmxError,
            long modelsLoaded,
            int modelsLive,
            long totalStagingBytes,
            long totalDeviceBytesLive,
            long closedForwardCount,
            long closedForwardTotalNanos,
            long closedWrappedImports,
            long closedStagedImports,
            List<IreeModelStats> models) {
        this.engineVersion = engineVersion;
        this.ireeRuntimeVersion = ireeRuntimeVersion;
        this.platform = platform;
        this.nativeLibraryPath = nativeLibraryPath;
        this.stagingMode = stagingMode;
        this.nativeStatsAvailable = nativeStatsAvailable;
        this.jmxStatus = jmxStatus;
        this.jmxError = jmxError;
        this.modelsLoaded = modelsLoaded;
        this.modelsLive = modelsLive;
        this.totalStagingBytes = totalStagingBytes;
        this.totalDeviceBytesLive = totalDeviceBytesLive;
        this.closedForwardCount = closedForwardCount;
        this.closedForwardTotalNanos = closedForwardTotalNanos;
        this.closedWrappedImports = closedWrappedImports;
        this.closedStagedImports = closedStagedImports;
        this.models = models;
    }

    /** @return the DJL engine version */
    public String getEngineVersion() {
        return engineVersion;
    }

    /** @return the pinned iree-runtime-dist release tag */
    public String getIreeRuntimeVersion() {
        return ireeRuntimeVersion;
    }

    /** @return the resolved platform directory, e.g. {@code linux-x86_64} */
    public String getPlatform() {
        return platform;
    }

    /** @return the native library path actually loaded, or {@code "unknown"} */
    public String getNativeLibraryPath() {
        return nativeLibraryPath;
    }

    /** @return the compiled-in staged-fallback policy, e.g. {@code cached-map-write} */
    public String getStagingMode() {
        return stagingMode;
    }

    /**
     * @return whether IREE's HAL allocator statistics are compiled into the runtime. When false,
     *     every {@code deviceBytes*} figure is {@code -1}.
     */
    public boolean isNativeStatsAvailable() {
        return nativeStatsAvailable;
    }

    /** @return {@code REGISTERED}, {@code DISABLED}, or {@code FAILED} */
    public String getJmxStatus() {
        return jmxStatus;
    }

    /** @return why JMX registration failed, or an empty string */
    public String getJmxError() {
        return jmxError;
    }

    /** @return models loaded since JVM start, cumulative */
    public long getModelsLoaded() {
        return modelsLoaded;
    }

    /** @return models currently live */
    public int getModelsLive() {
        return modelsLive;
    }

    /** @return summed staging bytes across live models; unavailable ({@code -1}) entries skipped */
    public long getTotalStagingBytes() {
        return totalStagingBytes;
    }

    /** @return summed live device bytes across live models; unavailable entries skipped */
    public long getTotalDeviceBytesLive() {
        return totalDeviceBytesLive;
    }

    /** @return forwards completed by models that are now closed or collected */
    public long getClosedForwardCount() {
        return closedForwardCount;
    }

    /** @return summed forward time of models that are now closed or collected */
    public long getClosedForwardTotalNanos() {
        return closedForwardTotalNanos;
    }

    /** @return zero-copy imports by models that are now closed or collected */
    public long getClosedWrappedImports() {
        return closedWrappedImports;
    }

    /** @return staged imports by models that are now closed or collected */
    public long getClosedStagedImports() {
        return closedStagedImports;
    }

    /** @return per-model detail for live models only; never {@code null} */
    public List<IreeModelStats> getModels() {
        return models;
    }
}
