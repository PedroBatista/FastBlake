package eu.pedrobatista.fastblake.bench;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
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

/**
 * E014 rung 1: the four-chunk message transpose, three ways.
 *
 * <p>E013 decomposed the chunk-parallel kernel and found the scalar
 * byte-shift transpose is about 30% of it, gains nothing from wider vectors,
 * and gets worse per byte as the batch widens. This isolates that transpose so
 * a replacement can be chosen on measurement rather than expectation.
 *
 * <p>All three variants produce byte-identical word-major scratch. Each block
 * of each of four chunks contributes 16 little-endian words; the result layout
 * is {@code messages[word * 4 + lane]}.
 *
 * <ul>
 *   <li>{@link #byteShiftTranspose()} — what E011 and E013 ship today.
 *   <li>{@link #varHandleTranspose()} — cached little-endian {@code VarHandle}
 *       int reads. E010 Evidence 4 measured this loader at 3.51x the manual
 *       shifts for the scalar path; endian-neutral, since the VarHandle always
 *       reads little-endian regardless of platform byte order.
 *   <li>{@link #vectorTranspose()} — 16-byte {@code ByteVector} loads
 *       reinterpreted as ints, transposed 4x4 in registers with two-source
 *       rearranges. E007 measured this loader at roughly 25x an endian
 *       {@code MemorySegment} load. Requires native little-endian order.
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TransposeBenchmark {

    private static final VectorSpecies<Integer> S = IntVector.SPECIES_128;
    private static final VectorSpecies<Byte> BS = ByteVector.SPECIES_128;

    private static final VarHandle LE_INT = MethodHandles
            .byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    // Two-source rearrange indices. A non-negative index selects from the
    // receiver; index -n selects lane (n + VLENGTH) of the argument vector.
    private static final VectorShuffle<Integer> ZIP_LO =
            VectorShuffle.fromValues(S, 0, -4, 1, -3);
    private static final VectorShuffle<Integer> ZIP_HI =
            VectorShuffle.fromValues(S, 2, -2, 3, -1);
    private static final VectorShuffle<Integer> CAT_LO =
            VectorShuffle.fromValues(S, 0, 1, -4, -3);
    private static final VectorShuffle<Integer> CAT_HI =
            VectorShuffle.fromValues(S, 2, 3, -2, -1);

    private final byte[] chunks = new byte[4 * 1024];
    private final int[] messages = new int[64];

    public TransposeBenchmark() {
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = (byte) (i * 131 + 17);
        }
    }

    /** Current production shape: assemble each word from four bytes. */
    @Benchmark
    public int byteShiftTranspose() {
        byteShift(chunks, 0, 0, messages);
        return messages[0] ^ messages[63];
    }

    /** Same layout, little-endian {@code VarHandle} reads instead of shifts. */
    @Benchmark
    public int varHandleTranspose() {
        varHandle(chunks, 0, 0, messages);
        return messages[0] ^ messages[63];
    }

    /** Same layout, vector loads and in-register 4x4 transposes. */
    @Benchmark
    public int vectorTranspose() {
        vector(chunks, 0, 0, messages);
        return messages[0] ^ messages[63];
    }

    static void byteShift(byte[] input, int offset, int blockOffset, int[] messages) {
        for (int word = 0; word < 16; word++) {
            int withinBlock = word * 4;
            int destination = word * 4;
            for (int lane = 0; lane < 4; lane++) {
                int p = offset + lane * 1024 + blockOffset + withinBlock;
                messages[destination + lane] = (input[p] & 0xff)
                        | ((input[p + 1] & 0xff) << 8)
                        | ((input[p + 2] & 0xff) << 16)
                        | (input[p + 3] << 24);
            }
        }
    }

    static void varHandle(byte[] input, int offset, int blockOffset, int[] messages) {
        for (int word = 0; word < 16; word++) {
            int withinBlock = word * 4;
            int destination = word * 4;
            for (int lane = 0; lane < 4; lane++) {
                int p = offset + lane * 1024 + blockOffset + withinBlock;
                messages[destination + lane] = (int) LE_INT.get(input, p);
            }
        }
    }

    static void vector(byte[] input, int offset, int blockOffset, int[] messages) {
        int base = offset + blockOffset;
        // Each group covers four consecutive words of every lane: one 16-byte
        // load per chunk, then a 4x4 transpose so a vector holds one word
        // across all four chunks.
        for (int group = 0; group < 4; group++) {
            int within = group * 16;
            IntVector a = ByteVector.fromArray(BS, input, base + within)
                    .reinterpretAsInts();
            IntVector b = ByteVector.fromArray(BS, input, base + 1024 + within)
                    .reinterpretAsInts();
            IntVector c = ByteVector.fromArray(BS, input, base + 2048 + within)
                    .reinterpretAsInts();
            IntVector d = ByteVector.fromArray(BS, input, base + 3072 + within)
                    .reinterpretAsInts();
            IntVector abLo = a.rearrange(ZIP_LO, b);
            IntVector abHi = a.rearrange(ZIP_HI, b);
            IntVector cdLo = c.rearrange(ZIP_LO, d);
            IntVector cdHi = c.rearrange(ZIP_HI, d);
            int destination = group * 16;
            abLo.rearrange(CAT_LO, cdLo).intoArray(messages, destination);
            abLo.rearrange(CAT_HI, cdLo).intoArray(messages, destination + 4);
            abHi.rearrange(CAT_LO, cdHi).intoArray(messages, destination + 8);
            abHi.rearrange(CAT_HI, cdHi).intoArray(messages, destination + 12);
        }
    }
}
