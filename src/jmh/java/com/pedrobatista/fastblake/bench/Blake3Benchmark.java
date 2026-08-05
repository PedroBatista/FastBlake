package com.pedrobatista.fastblake.bench;

import com.pedrobatista.fastblake.harness.Blake3Engine;
import com.pedrobatista.fastblake.harness.Contenders;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The contender comparison: every available BLAKE3 implementation, same call
 * shapes, same bytes, same JMH iteration.
 *
 * <p>Run it with {@code ./gradlew jmh}. {@link BenchmarkRunner} fills the
 * {@code impl} parameter with whatever this machine can execute, so a box with
 * no Rust toolchain simply produces one fewer row.
 *
 * <p>Results are nanoseconds per hashed buffer; the runner prints a MiB/s table
 * at the end. To convert by hand: {@code MiB/s = size / (ns per op) × 953.7}.
 *
 * <p>The size sweep is deliberate. 64 B and 1 KiB sit at or below BLAKE3's
 * 1024-byte chunk boundary, where per-call overhead dominates and a GPU or a
 * native boundary crossing loses badly. 16 KiB is the smallest input that can
 * fill a 16-lane SIMD batch. The larger sizes measure steady-state throughput,
 * which is where SIMD and device offload are supposed to pay off.
 *
 * <pre>
 * ./gradlew jmh
 * ./gradlew jmh -P'jmh.args=oneShot -p size=1048576 -f 1'
 * ./gradlew jmh -P'jmh.args=-p impl=rust,commons'
 * </pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgs = {"-Xms2g", "-Xmx2g", "--enable-native-access=ALL-UNNAMED"})
public class Blake3Benchmark {

    /**
     * Contender id. The default is a placeholder: {@link BenchmarkRunner}
     * overrides it with the ids that are actually available, so this value only
     * applies when the benchmark is driven by something else.
     */
    @Param({"commons"})
    public String impl;

    @Param({"64", "1024", "16384", "262144", "4194304"})
    public int size;

    private Blake3Engine engine;
    private byte[] input;
    private byte[] output;
    private Blake3Engine.Hasher reusedHasher;

    @Setup(Level.Trial)
    public void setUp() {
        engine = Contenders.require(impl);
        String unavailable = engine.unavailableReason();
        if (unavailable != null) {
            // Reached only if a contender is named explicitly on the command
            // line. Fail loudly rather than silently reporting a bogus score.
            throw new IllegalStateException("contender '" + impl + "' is unavailable: " + unavailable);
        }
        input = new byte[size];
        // Fixed seed so every contender and every run sees identical bytes.
        new Random(0x5EED_B14E_3L).nextBytes(input);
        output = new byte[32];
        reusedHasher = engine.newHasher();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (reusedHasher != null) {
            reusedHasher.close();
            reusedHasher = null;
        }
    }

    /** Hash a whole buffer with a fresh hasher — the common call shape. */
    @Benchmark
    public byte[] oneShot() {
        return engine.hash(input, 32);
    }

    /**
     * The same work reusing one hasher. Isolates state setup and allocation
     * from raw compression throughput; for the native and device contenders it
     * also removes per-call handle construction.
     */
    @Benchmark
    public byte[] reusedInstance() {
        reusedHasher.reset().update(input, 0, input.length);
        reusedHasher.doFinalize(output, 0, output.length);
        return output;
    }

    /**
     * The same bytes fed in 4 KiB pieces, as a file or socket reader would.
     * This is the shape that punishes implementations needing the whole input
     * up front to parallelise.
     */
    @Benchmark
    public byte[] streaming4k() {
        reusedHasher.reset();
        for (int off = 0; off < input.length; off += 4096) {
            reusedHasher.update(input, off, Math.min(4096, input.length - off));
        }
        reusedHasher.doFinalize(output, 0, output.length);
        return output;
    }
}
