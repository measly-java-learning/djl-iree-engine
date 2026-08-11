package org.measly.example;

import ai.djl.engine.Engine;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Java-side input-copy cost of {@code manager.create(float[], Shape)}, plain
 * vs {@code iree.engine.alignedBuffers=true} — the denominator pair for the
 * W2 copy measurement (native bench in §3 of the findings doc gives kernel
 * time; this gives the Java copy overhead on top of it).
 *
 * <p>Both arms run the identical {@code allocateDirect + copyBuffer} path
 * inside {@code create}; the aligned arm's {@code allocateDirect} is the
 * engine's 64-byte-aligned native allocation instead of the JVM's. Expect the
 * two arms to be ≈ equal: the memcpy into the buffer dominates both, and the
 * import outcome difference (wrapped vs staged) is asserted by the JNI/native
 * tests, not here.
 *
 * <p>Deviation from a naive "return the array": the created array is closed
 * before returning. {@code NDManager.create} attaches the array to the
 * manager, so leaving it open would retain a direct buffer per invocation —
 * unbounded memory at the 16 M (64 MB) parameter, and a dirty
 * manager.close() at trial end. Closing detaches from the manager (a map
 * remove, negligible next to the copy) and makes the buffer GC/Cleaner
 *-eligible; both arms pay the same close, so the copy comparison is unchanged.
 */
public class CopyCostBenchmark {

    private static final String ENGINE = "IREE";

    /** Shared per-fork state: source array, flag, and one manager per trial. */
    @State(Scope.Benchmark)
    public static class Cfg {

        @Param({"4096", "65536", "1048576", "16777216"})
        int n;

        @Param({"false", "true"})
        boolean aligned;

        float[] source;
        NDManager manager;

        @Setup(Level.Trial)
        public void setup() {
            // The flag is read per allocation (IreeNDManager.allocateDirect),
            // so the fork-local property is honored; cleared in teardown so
            // the JVM exits with the default state.
            System.setProperty("iree.engine.alignedBuffers", String.valueOf(aligned));
            source = new float[n]; // allocation outside the timed region
            manager = Engine.getEngine(ENGINE).newBaseManager();
        }

        @TearDown(Level.Trial)
        public void teardown() {
            manager.close();
            System.clearProperty("iree.engine.alignedBuffers");
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public NDArray createCopy(Cfg c) {
        NDArray array = c.manager.create(c.source, new Shape(c.n));
        array.close();
        return array; // blackholed; its memory is already returned to the pool
    }
}
