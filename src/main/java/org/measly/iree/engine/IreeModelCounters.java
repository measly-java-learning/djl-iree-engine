package org.measly.iree.engine;

/**
 * Mutable per-model counters, updated on the forward path and read by the observability snapshot.
 *
 * <p><b>Single-writer by design.</b> {@code IreeSymbolBlock.forward()} is not safe for concurrent
 * calls on the same model — the engine's contract is one {@code Model}/{@code Predictor} per
 * thread, and an IREE session is not safe for concurrent invocation — so exactly one thread ever
 * calls {@link #recordForward(long)} for a given instance. That is what lets the accumulators be
 * plain read-modify-writes with no CAS and no lock.
 *
 * <p>The fields are {@code volatile} for the reader's sake, not the writer's: a snapshot taken on
 * another thread must observe the updates and must never see a torn 64-bit value. A {@code
 * LongAdder} would be strictly worse — it allocates cells and makes the read a summation, and
 * there is no write contention for it to relieve.
 *
 * <p>This object holds no reference to its {@code IreeSymbolBlock}. That is deliberate: {@code
 * IreeEngineStats} retains it strongly from a static map, so a back-reference would pin the
 * block's whole object graph.
 */
final class IreeModelCounters {

    private final String name;
    private final String driver;
    private final String entryPoint;
    private final int parameterScopeCount;
    private final long loadNanos;

    private volatile long forwardCount;
    private volatile long forwardTotalNanos;
    private volatile long forwardMaxNanos;

    // Last observed native import totals. Native counters die with the runtime, so the
    // block records them here on every stats read; deregistration folds the last value
    // into the process rollup. Written from the stats cold path under the block's
    // statsLock, read from the same place plus deregistration.
    private volatile long lastWrappedImports;
    private volatile long lastStagedImports;

    // Status of the last native stats read: 1 = the read succeeded and the native side
    // reported STAT_STATISTICS_AVAILABLE == 1; 0 = the read succeeded and statistics are
    // compiled out; -1 = no successful read yet/this tick. Distinct from the -1
    // byte-gauge convention: the gauges also read -1 when the native read did not happen
    // at all (handle zero racing close(), JNI null), and the process-wide statsAvailable
    // flag must flip only on an EXPLICIT compiled-out answer, never on an unreadable
    // read. Written from the stats cold path under the block's statsLock (toStats() and
    // close()), read from IreeEngineStats.snapshot().
    private volatile int lastStatsStatus = -1;

    IreeModelCounters(
            String name,
            String driver,
            String entryPoint,
            int parameterScopeCount,
            long loadNanos) {
        this.name = name;
        this.driver = driver;
        this.entryPoint = entryPoint;
        this.parameterScopeCount = parameterScopeCount;
        this.loadNanos = loadNanos;
    }

    /**
     * Records one completed forward. Called only from the model's owning thread.
     *
     * <p><b>The write order is load-bearing: count, then total, then max.</b> A reader on another
     * thread can interleave anywhere between these three volatile writes, and this order is what
     * guarantees the invariant {@code forwardMaxNanos <= forwardTotalNanos} — the max is published
     * only after the total that already includes the same sample. That ordering covers only the
     * writer: the JMM forbids reordering volatile writes with each other, which pins the publish
     * order, but a reader that reads total first and then max can still straddle the writer's
     * stores and observe the new max with the old total. The guarantee therefore also requires
     * the reader to read <b>max before total</b> — so it can see the new max only once the new
     * total is already visible — and {@code IreeSymbolBlock#toStats()} does exactly that. It is a
     * property of this write sequence plus the reader's read order, not of the field declarations.
     * Writing max first would let a reader observe a max with no total behind it, breaking an
     * assertion in {@code StatsConcurrencyIT} in a way that only shows up as a rare flake under
     * load.
     *
     * @param nanos the measured wall duration of the native invoke call
     */
    void recordForward(long nanos) {
        forwardCount = forwardCount + 1;
        forwardTotalNanos = forwardTotalNanos + nanos;
        if (nanos > forwardMaxNanos) {
            forwardMaxNanos = nanos;
        }
    }

    String name() {
        return name;
    }

    String driver() {
        return driver;
    }

    String entryPoint() {
        return entryPoint;
    }

    int parameterScopeCount() {
        return parameterScopeCount;
    }

    long loadNanos() {
        return loadNanos;
    }

    long forwardCount() {
        return forwardCount;
    }

    long forwardTotalNanos() {
        return forwardTotalNanos;
    }

    long forwardMaxNanos() {
        return forwardMaxNanos;
    }

    void recordNativeImports(long wrapped, long staged) {
        lastWrappedImports = wrapped;
        lastStagedImports = staged;
    }

    long lastWrappedImports() {
        return lastWrappedImports;
    }

    long lastStagedImports() {
        return lastStagedImports;
    }

    void recordNativeStatsStatus(int status) {
        lastStatsStatus = status;
    }

    /** 1 = native statistics available, 0 = compiled out, -1 = no successful read yet. */
    int lastStatsStatus() {
        return lastStatsStatus;
    }
}
