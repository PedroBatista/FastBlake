package eu.pedrobatista.fastblake.bench;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Diagnostic microbenchmarks for the AArch64 Vector API lowering used by BLAKE3. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class VectorPrimitiveBenchmark {

    private static final VectorSpecies<Integer> S = IntVector.SPECIES_128;
    private static final VectorSpecies<Byte> BS = ByteVector.SPECIES_128;
    private static final VectorShuffle<Integer> TWO_SOURCE =
            VectorShuffle.fromArray(S, new int[]{0, 4, 1, 5}, 0);
    private static final VectorShuffle<Byte> ROR_8_BYTES = VectorShuffle.fromArray(BS,
            new int[]{1, 2, 3, 0, 5, 6, 7, 4, 9, 10, 11, 8, 13, 14, 15, 12}, 0);
    private static final VectorShuffle<Byte> ROR_16_BYTES = VectorShuffle.fromArray(BS,
            new int[]{2, 3, 0, 1, 6, 7, 4, 5, 10, 11, 8, 9, 14, 15, 12, 13}, 0);
    private static final int REPETITIONS = 256;

    static {
        int[] probe = {0x12345678, 0x89abcdef, 0x0f1e2d3c, 0x4b5a6978};
        ByteVector bytes = IntVector.fromArray(S, probe, 0).reinterpretAsBytes();
        int ror8 = bytes.rearrange(ROR_8_BYTES).reinterpretAsInts().lane(0);
        int ror16 = bytes.rearrange(ROR_16_BYTES).reinterpretAsInts().lane(0);
        if (ror8 != Integer.rotateRight(probe[0], 8)
                || ror16 != Integer.rotateRight(probe[0], 16)) {
            throw new AssertionError("byte shuffle does not implement integer rotate-right");
        }
    }

    private final byte[] bytes = new byte[4096];
    private final MemorySegment memory = MemorySegment.ofArray(bytes);
    private final int[] ints = {0x12345678, 0x89abcdef, 0x0f1e2d3c, 0x4b5a6978,
            0x76543210, 0xfedcba98, 0xc3d2e1f0, 0x8796a5b4};

    public VectorPrimitiveBenchmark() {
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 131 + 17);
        }
    }

    @Benchmark
    public int vectorRotateDependencyChain() {
        IntVector value = IntVector.fromArray(S, ints, 0);
        for (int i = 0; i < REPETITIONS; i++) {
            value = value.lanewise(VectorOperators.ROR, 7);
        }
        return value.lane(0);
    }

    @Benchmark
    public int vectorRotate8DependencyChain() {
        IntVector value = IntVector.fromArray(S, ints, 0);
        for (int i = 0; i < REPETITIONS; i++) {
            value = value.lanewise(VectorOperators.ROR, 8);
        }
        return value.lane(0);
    }

    @Benchmark
    public int vectorRotate16DependencyChain() {
        IntVector value = IntVector.fromArray(S, ints, 0);
        for (int i = 0; i < REPETITIONS; i++) {
            value = value.lanewise(VectorOperators.ROR, 16);
        }
        return value.lane(0);
    }

    @Benchmark
    public int vectorRotate8ByteShuffleDependencyChain() {
        ByteVector value = IntVector.fromArray(S, ints, 0).reinterpretAsBytes();
        for (int i = 0; i < REPETITIONS; i++) {
            value = value.rearrange(ROR_8_BYTES);
        }
        return value.reinterpretAsInts().lane(0);
    }

    @Benchmark
    public int vectorRotate16ByteShuffleDependencyChain() {
        ByteVector value = IntVector.fromArray(S, ints, 0).reinterpretAsBytes();
        for (int i = 0; i < REPETITIONS; i++) {
            value = value.rearrange(ROR_16_BYTES);
        }
        return value.reinterpretAsInts().lane(0);
    }

    @Benchmark
    public int scalarRotateFourDependencyChains() {
        int a = ints[0], b = ints[1], c = ints[2], d = ints[3];
        for (int i = 0; i < REPETITIONS; i++) {
            a = Integer.rotateRight(a, 7);
            b = Integer.rotateRight(b, 7);
            c = Integer.rotateRight(c, 7);
            d = Integer.rotateRight(d, 7);
        }
        return a ^ b ^ c ^ d;
    }

    @Benchmark
    public int vectorTwoSourceRearrangeDependencyChain() {
        IntVector a = IntVector.fromArray(S, ints, 0);
        IntVector b = IntVector.fromArray(S, ints, 4);
        for (int i = 0; i < REPETITIONS; i++) {
            a = a.rearrange(TWO_SOURCE, b);
            b = b.rearrange(TWO_SOURCE, a);
        }
        return a.lane(0) ^ b.lane(0);
    }

    @Benchmark
    public int vectorLittleEndianLoads() {
        IntVector sum = IntVector.zero(S);
        for (int i = 0; i < REPETITIONS; i++) {
            long offset = (i & 255) << 4;
            sum = sum.add(IntVector.fromMemorySegment(S, memory, offset,
                    ByteOrder.LITTLE_ENDIAN));
        }
        return sum.lane(0);
    }

    @Benchmark
    public int vectorHeapByteLoads() {
        IntVector sum = IntVector.zero(S);
        for (int i = 0; i < REPETITIONS; i++) {
            int offset = (i & 255) << 4;
            IntVector loaded = ByteVector.fromArray(ByteVector.SPECIES_128, bytes, offset)
                    .reinterpretAsInts();
            sum = sum.add(loaded);
        }
        return sum.lane(0);
    }

    @Benchmark
    public int vectorBlakeMix() {
        IntVector a = IntVector.fromArray(S, ints, 0);
        IntVector b = IntVector.fromArray(S, ints, 4);
        for (int i = 0; i < REPETITIONS; i++) {
            a = a.add(b);
            b = b.lanewise(VectorOperators.XOR, a)
                    .lanewise(VectorOperators.ROR, 7);
        }
        return a.lane(0) ^ b.lane(0);
    }
}
