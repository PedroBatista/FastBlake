package com.pedrobatista.fastblake;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
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

/** E017 exact probes for the current four-chunk production-candidate kernel. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ChunkVectorKernelBenchmark {

    private static final VectorSpecies<Integer> S = IntVector.SPECIES_128;
    private static final int[][] SCHEDULE = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8},
            {3, 4, 10, 12, 13, 2, 7, 14, 6, 5, 9, 0, 11, 15, 8, 1},
            {10, 7, 12, 9, 14, 3, 13, 15, 4, 0, 11, 2, 5, 8, 1, 6},
            {12, 13, 9, 11, 15, 10, 14, 8, 7, 2, 5, 3, 0, 1, 6, 4},
            {9, 14, 11, 5, 8, 12, 15, 1, 13, 3, 0, 10, 2, 6, 4, 7},
            {11, 15, 5, 0, 1, 9, 8, 6, 14, 10, 2, 12, 3, 4, 7, 13}
    };

    private final byte[] chunks = new byte[4 * 1024];
    private final int[] key = FastBlake.IV_WORDS.clone();
    private final int[] messages = new int[64];
    private final int[] output = new int[32];
    private final int[] vectorWords = new int[32];
    private final int[] initialState = new int[64];
    private final int[] stateScratch = new int[64];

    public ChunkVectorKernelBenchmark() {
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = (byte) (i * 131 + 17);
        }
        for (int i = 0; i < vectorWords.length; i++) {
            vectorWords[i] = i * 0x9e3779b9 + 0x6a09e667;
        }
        for (int i = 0; i < messages.length; i++) {
            messages[i] = i * 0x7f4a7c15 + 0x3c6ef372;
            initialState[i] = i * 0x6a09e667 + 0x510e527f;
        }
        verifyStateScratch();
    }

    /** Load, transpose, compress 16 blocks, chain and extract four chunk CVs. */
    @Benchmark
    public int completeFourChunkBatch() {
        Blake3ChunkVectorScratch.hashChunks(chunks, 0, 0, key, 0, messages, output);
        return output[0] ^ output[31];
    }

    /** Exact lane-by-lane CV extraction used at the end of the current kernel. */
    @Benchmark
    public int extractCvLanes() {
        IntVector cv0 = IntVector.fromArray(S, vectorWords, 0);
        IntVector cv1 = IntVector.fromArray(S, vectorWords, 4);
        IntVector cv2 = IntVector.fromArray(S, vectorWords, 8);
        IntVector cv3 = IntVector.fromArray(S, vectorWords, 12);
        IntVector cv4 = IntVector.fromArray(S, vectorWords, 16);
        IntVector cv5 = IntVector.fromArray(S, vectorWords, 20);
        IntVector cv6 = IntVector.fromArray(S, vectorWords, 24);
        IntVector cv7 = IntVector.fromArray(S, vectorWords, 28);

        output[0] = cv0.lane(0);
        output[1] = cv1.lane(0);
        output[2] = cv2.lane(0);
        output[3] = cv3.lane(0);
        output[4] = cv4.lane(0);
        output[5] = cv5.lane(0);
        output[6] = cv6.lane(0);
        output[7] = cv7.lane(0);
        output[8] = cv0.lane(1);
        output[9] = cv1.lane(1);
        output[10] = cv2.lane(1);
        output[11] = cv3.lane(1);
        output[12] = cv4.lane(1);
        output[13] = cv5.lane(1);
        output[14] = cv6.lane(1);
        output[15] = cv7.lane(1);
        output[16] = cv0.lane(2);
        output[17] = cv1.lane(2);
        output[18] = cv2.lane(2);
        output[19] = cv3.lane(2);
        output[20] = cv4.lane(2);
        output[21] = cv5.lane(2);
        output[22] = cv6.lane(2);
        output[23] = cv7.lane(2);
        output[24] = cv0.lane(3);
        output[25] = cv1.lane(3);
        output[26] = cv2.lane(3);
        output[27] = cv3.lane(3);
        output[28] = cv4.lane(3);
        output[29] = cv5.lane(3);
        output[30] = cv6.lane(3);
        output[31] = cv7.lane(3);
        return output[0] ^ output[31];
    }

    /**
     * Smaller-live-set control: keep the 16 state vectors in primitive scratch
     * and materialize only one G's four state vectors and two messages at once.
     */
    @Benchmark
    public int sevenRoundsStateScratch() {
        System.arraycopy(initialState, 0, stateScratch, 0, initialState.length);
        for (int round = 0; round < 7; round++) {
            int[] s = SCHEDULE[round];
            g(stateScratch, 0, 4, 8, 12, s[0], s[1]);
            g(stateScratch, 1, 5, 9, 13, s[2], s[3]);
            g(stateScratch, 2, 6, 10, 14, s[4], s[5]);
            g(stateScratch, 3, 7, 11, 15, s[6], s[7]);
            g(stateScratch, 0, 5, 10, 15, s[8], s[9]);
            g(stateScratch, 1, 6, 11, 12, s[10], s[11]);
            g(stateScratch, 2, 7, 8, 13, s[12], s[13]);
            g(stateScratch, 3, 4, 9, 14, s[14], s[15]);
        }
        return stateScratch[0] ^ stateScratch[63];
    }

    private void g(int[] state, int a, int b, int c, int d, int mx, int my) {
        int ao = a * 4;
        int bo = b * 4;
        int co = c * 4;
        int doff = d * 4;
        IntVector av = IntVector.fromArray(S, state, ao);
        IntVector bv = IntVector.fromArray(S, state, bo);
        IntVector cv = IntVector.fromArray(S, state, co);
        IntVector dv = IntVector.fromArray(S, state, doff);
        av = av.add(bv).add(IntVector.fromArray(S, messages, mx * 4));
        dv = dv.lanewise(VectorOperators.XOR, av).lanewise(VectorOperators.ROR, 16);
        cv = cv.add(dv);
        bv = bv.lanewise(VectorOperators.XOR, cv).lanewise(VectorOperators.ROR, 12);
        av = av.add(bv).add(IntVector.fromArray(S, messages, my * 4));
        dv = dv.lanewise(VectorOperators.XOR, av).lanewise(VectorOperators.ROR, 8);
        cv = cv.add(dv);
        bv = bv.lanewise(VectorOperators.XOR, cv).lanewise(VectorOperators.ROR, 7);
        av.intoArray(state, ao);
        bv.intoArray(state, bo);
        cv.intoArray(state, co);
        dv.intoArray(state, doff);
    }

    private void verifyStateScratch() {
        int[] expected = initialState.clone();
        for (int round = 0; round < 7; round++) {
            int[] s = SCHEDULE[round];
            scalarG(expected, 0, 4, 8, 12, s[0], s[1]);
            scalarG(expected, 1, 5, 9, 13, s[2], s[3]);
            scalarG(expected, 2, 6, 10, 14, s[4], s[5]);
            scalarG(expected, 3, 7, 11, 15, s[6], s[7]);
            scalarG(expected, 0, 5, 10, 15, s[8], s[9]);
            scalarG(expected, 1, 6, 11, 12, s[10], s[11]);
            scalarG(expected, 2, 7, 8, 13, s[12], s[13]);
            scalarG(expected, 3, 4, 9, 14, s[14], s[15]);
        }
        sevenRoundsStateScratch();
        if (!Arrays.equals(expected, stateScratch)) {
            throw new AssertionError("state-scratch vector rounds differ from scalar reference");
        }
    }

    private void scalarG(int[] state, int a, int b, int c, int d, int mx, int my) {
        for (int lane = 0; lane < 4; lane++) {
            int ai = a * 4 + lane;
            int bi = b * 4 + lane;
            int ci = c * 4 + lane;
            int di = d * 4 + lane;
            state[ai] = state[ai] + state[bi] + messages[mx * 4 + lane];
            state[di] = Integer.rotateRight(state[di] ^ state[ai], 16);
            state[ci] += state[di];
            state[bi] = Integer.rotateRight(state[bi] ^ state[ci], 12);
            state[ai] = state[ai] + state[bi] + messages[my * 4 + lane];
            state[di] = Integer.rotateRight(state[di] ^ state[ai], 8);
            state[ci] += state[di];
            state[bi] = Integer.rotateRight(state[bi] ^ state[ci], 7);
        }
    }
}
