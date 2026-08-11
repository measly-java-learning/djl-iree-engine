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
     * only after the total that already includes the same sample. The JMM forbids reordering
     * volatile writes with each other, so the guarantee is real, but it is a property of this
     * sequence and not of the field declarations. Writing max first would let a reader observe a
     * max with no total behind it, breaking an assertion in {@code StatsConcurrencyIT} in a way
     * that only shows up as a rare flake under load.
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
}
