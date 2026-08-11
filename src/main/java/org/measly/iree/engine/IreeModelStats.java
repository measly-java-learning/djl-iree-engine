package org.measly.iree.engine;

/**
 * Immutable per-model statistics, as of one {@link IreeEngineStats#snapshot()}.
 *
 * <p><b>Getter-only by contract.</b> This is returned through an MXBean, and the JMX runtime
 * converts it to {@code CompositeData} by reflecting over its getters. Adding a setter or a
 * public field breaks that conversion.
 *
 * <p><b>Byte gauges use {@code -1} for "unavailable" and {@code 0} for "genuinely zero".</b> The
 * distinction matters twice here: {@code stagingBytes == 0} means no input has staged a copy yet,
 * a real and meaningful state; {@code deviceBytesPeak == -1} means IREE's allocator statistics
 * were compiled out of the runtime and the figure is unknowable, not zero.
 */
public final class IreeModelStats {

    private final String name;
    private final String driver;
    private final String entryPoint;
    private final int parameterScopeCount;
    private final long loadNanos;
    private final long forwardCount;
    private final long forwardTotalNanos;
    private final long forwardMaxNanos;
    private final long wrappedImports;
    private final long stagedImports;
    private final long stagingBytes;
    private final long deviceBytesPeak;
    private final long deviceBytesLive;

    IreeModelStats(
            String name,
            String driver,
            String entryPoint,
            int parameterScopeCount,
            long loadNanos,
            long forwardCount,
            long forwardTotalNanos,
            long forwardMaxNanos,
            long wrappedImports,
            long stagedImports,
            long stagingBytes,
            long deviceBytesPeak,
            long deviceBytesLive) {
        this.name = name;
        this.driver = driver;
        this.entryPoint = entryPoint;
        this.parameterScopeCount = parameterScopeCount;
        this.loadNanos = loadNanos;
        this.forwardCount = forwardCount;
        this.forwardTotalNanos = forwardTotalNanos;
        this.forwardMaxNanos = forwardMaxNanos;
        this.wrappedImports = wrappedImports;
        this.stagedImports = stagedImports;
        this.stagingBytes = stagingBytes;
        this.deviceBytesPeak = deviceBytesPeak;
        this.deviceBytesLive = deviceBytesLive;
    }

    /** @return the DJL model name */
    public String getName() {
        return name;
    }

    /** @return the IREE HAL driver this model loaded with, e.g. {@code local-sync} */
    public String getDriver() {
        return driver;
    }

    /** @return the compiled entry point, e.g. {@code module.main} */
    public String getEntryPoint() {
        return entryPoint;
    }

    /** @return how many {@code .irpa} parameter scopes were bound at load */
    public int getParameterScopeCount() {
        return parameterScopeCount;
    }

    /** @return wall time spent in the native load call */
    public long getLoadNanos() {
        return loadNanos;
    }

    /** @return completed forwards on this model */
    public long getForwardCount() {
        return forwardCount;
    }

    /** @return summed wall time of every completed forward */
    public long getForwardTotalNanos() {
        return forwardTotalNanos;
    }

    /** @return the slowest single forward */
    public long getForwardMaxNanos() {
        return forwardMaxNanos;
    }

    /**
     * @return cumulative inputs imported zero-copy. Compare against {@link #getStagedImports()}:
     *     a high staged share means the engine is copying every input, which is the single most
     *     common silent performance cliff in this engine.
     */
    public long getWrappedImports() {
        return wrappedImports;
    }

    /** @return cumulative inputs that fell back to a staged copy */
    public long getStagedImports() {
        return stagedImports;
    }

    /**
     * @return bytes held by the grow-only per-slot staging buffers, or {@code -1} if unreadable.
     *     {@code 0} means nothing has staged yet, which is a real state, not an error.
     */
    public long getStagingBytes() {
        return stagingBytes;
    }

    /** @return peak HAL device-allocator bytes, or {@code -1} if statistics are compiled out */
    public long getDeviceBytesPeak() {
        return deviceBytesPeak;
    }

    /**
     * @return currently-live HAL device-allocator bytes (allocated minus freed), or {@code -1} if
     *     statistics are compiled out. This is the gauge to alert on for an unbounded-growth leak.
     */
    public long getDeviceBytesLive() {
        return deviceBytesLive;
    }
}
