package com.pedrobatista.fastblake;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * E013 preferred-width chunk-parallel kernel.
 *
 * <p>Structurally identical to {@link Blake3ChunkVectorScratch}: reusable
 * primitive word-major message scratch, sixteen named state vectors, and the
 * compact seven-round schedule loop that E011 found to be the shape C2 keeps
 * allocation-free. The only change is that the species, and every stride
 * derived from it, come from {@link IntVector#SPECIES_PREFERRED} instead of a
 * hard-coded 128 bits, so one chunk per lane means as many chunks as the
 * machine is actually wide.
 *
 * <p>E012 measured the cost of the hard-coding: on a 256-bit AVX2 target the
 * four-lane kernel used half the available width while the reference Rust
 * implementation dispatched to its eight-way kernel. Lane count here is only a
 * batch size — unlike {@link Blake3BlockVector}, where four lanes hold a BLAKE3
 * state row and are intrinsic to the algorithm.
 *
 * <p>Endian-neutral: message words are assembled with explicit byte shifts
 * rather than by reinterpreting a byte vector, so no little-endian guard is
 * needed.
 */
final class Blake3ChunkVectorWide {
    private static final VectorSpecies<Integer> S = IntVector.SPECIES_PREFERRED;

    /** Chunks processed per call; also the message scratch stride. */
    static final int LANES = S.length();

    // E014: always reads little-endian regardless of platform byte order, so
    // the kernel stays endian-neutral and needs no guard.
    private static final VarHandle LE_INT = MethodHandles
            .byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private static final int[][] SCHEDULE = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
        {2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8},
        {3, 4, 10, 12, 13, 2, 7, 14, 6, 5, 9, 0, 11, 15, 8, 1},
        {10, 7, 12, 9, 14, 3, 13, 15, 4, 0, 11, 2, 5, 8, 1, 6},
        {12, 13, 9, 11, 15, 10, 14, 8, 7, 2, 5, 3, 0, 1, 6, 4},
        {9, 14, 11, 5, 8, 12, 15, 1, 13, 3, 0, 10, 2, 6, 4, 7},
        {11, 15, 5, 0, 1, 9, 8, 6, 14, 10, 2, 12, 3, 4, 7, 13}
    };

    private Blake3ChunkVectorWide() {}

    /** Message scratch length required by {@link #hashChunks}. */
    static int packedWordsLength() {
        return 16 * LANES;
    }

    /** Output length required by {@link #hashChunks}, eight CV words per lane. */
    static int outputLength() {
        return 8 * LANES;
    }

    /**
     * Hashes exactly {@link #LANES} consecutive complete chunks starting at
     * {@code offset}, writing lane-major chaining values into {@code output}.
     *
     * <p>{@code messages} is reusable scratch of at least
     * {@link #packedWordsLength()} ints; its contents are not preserved.
     */
    static void hashChunks(byte[] input, int offset, long firstCounter,
                           int[] key, int flags, int[] messages, int[] output) {
        // Counters are staged through the message scratch to avoid allocating.
        // Safe because the block loop below rewrites every slot it reads, and
        // both vectors are materialised before the first rewrite.
        for (int lane = 0; lane < LANES; lane++) {
            long counter = firstCounter + lane;
            messages[lane] = (int) counter;
            messages[LANES + lane] = (int) (counter >>> 32);
        }
        IntVector counterLow = IntVector.fromArray(S, messages, 0);
        IntVector counterHigh = IntVector.fromArray(S, messages, LANES);
        IntVector cv0 = IntVector.broadcast(S, key[0]);
        IntVector cv1 = IntVector.broadcast(S, key[1]);
        IntVector cv2 = IntVector.broadcast(S, key[2]);
        IntVector cv3 = IntVector.broadcast(S, key[3]);
        IntVector cv4 = IntVector.broadcast(S, key[4]);
        IntVector cv5 = IntVector.broadcast(S, key[5]);
        IntVector cv6 = IntVector.broadcast(S, key[6]);
        IntVector cv7 = IntVector.broadcast(S, key[7]);
        for (int block = 0; block < 16; block++) {
            int blockOffset = block * 64;
            for (int word = 0; word < 16; word++) {
                int withinBlock = word * 4;
                int destination = word * LANES;
                for (int lane = 0; lane < LANES; lane++) {
                    int p = offset + lane * 1024 + blockOffset + withinBlock;
                    messages[destination + lane] = (int) LE_INT.get(input, p);
                }
            }
            IntVector v0 = cv0;
            IntVector v1 = cv1;
            IntVector v2 = cv2;
            IntVector v3 = cv3;
            IntVector v4 = cv4;
            IntVector v5 = cv5;
            IntVector v6 = cv6;
            IntVector v7 = cv7;
            IntVector v8 = IntVector.broadcast(S, FastBlake.IV_WORDS[0]);
            IntVector v9 = IntVector.broadcast(S, FastBlake.IV_WORDS[1]);
            IntVector v10 = IntVector.broadcast(S, FastBlake.IV_WORDS[2]);
            IntVector v11 = IntVector.broadcast(S, FastBlake.IV_WORDS[3]);
            IntVector v12 = counterLow;
            IntVector v13 = counterHigh;
            IntVector v14 = IntVector.broadcast(S, 64);
            IntVector v15 = IntVector.broadcast(S, flags
                    | (block == 0 ? 1 : 0)
                    | (block == 15 ? 2 : 0));
            for (int round = 0; round < 7; round++) {
                int[] schedule = SCHEDULE[round];
                IntVector mx;
                IntVector my;
                mx = IntVector.fromArray(S, messages, schedule[0] * LANES);
                my = IntVector.fromArray(S, messages, schedule[1] * LANES);
                v0 = v0.add(v4).add(mx);
                v12 = v12.lanewise(VectorOperators.XOR, v0).lanewise(VectorOperators.ROR, 16);
                v8 = v8.add(v12);
                v4 = v4.lanewise(VectorOperators.XOR, v8).lanewise(VectorOperators.ROR, 12);
                v0 = v0.add(v4).add(my);
                v12 = v12.lanewise(VectorOperators.XOR, v0).lanewise(VectorOperators.ROR, 8);
                v8 = v8.add(v12);
                v4 = v4.lanewise(VectorOperators.XOR, v8).lanewise(VectorOperators.ROR, 7);
                mx = IntVector.fromArray(S, messages, schedule[2] * LANES);
                my = IntVector.fromArray(S, messages, schedule[3] * LANES);
                v1 = v1.add(v5).add(mx);
                v13 = v13.lanewise(VectorOperators.XOR, v1).lanewise(VectorOperators.ROR, 16);
                v9 = v9.add(v13);
                v5 = v5.lanewise(VectorOperators.XOR, v9).lanewise(VectorOperators.ROR, 12);
                v1 = v1.add(v5).add(my);
                v13 = v13.lanewise(VectorOperators.XOR, v1).lanewise(VectorOperators.ROR, 8);
                v9 = v9.add(v13);
                v5 = v5.lanewise(VectorOperators.XOR, v9).lanewise(VectorOperators.ROR, 7);
                mx = IntVector.fromArray(S, messages, schedule[4] * LANES);
                my = IntVector.fromArray(S, messages, schedule[5] * LANES);
                v2 = v2.add(v6).add(mx);
                v14 = v14.lanewise(VectorOperators.XOR, v2).lanewise(VectorOperators.ROR, 16);
                v10 = v10.add(v14);
                v6 = v6.lanewise(VectorOperators.XOR, v10).lanewise(VectorOperators.ROR, 12);
                v2 = v2.add(v6).add(my);
                v14 = v14.lanewise(VectorOperators.XOR, v2).lanewise(VectorOperators.ROR, 8);
                v10 = v10.add(v14);
                v6 = v6.lanewise(VectorOperators.XOR, v10).lanewise(VectorOperators.ROR, 7);
                mx = IntVector.fromArray(S, messages, schedule[6] * LANES);
                my = IntVector.fromArray(S, messages, schedule[7] * LANES);
                v3 = v3.add(v7).add(mx);
                v15 = v15.lanewise(VectorOperators.XOR, v3).lanewise(VectorOperators.ROR, 16);
                v11 = v11.add(v15);
                v7 = v7.lanewise(VectorOperators.XOR, v11).lanewise(VectorOperators.ROR, 12);
                v3 = v3.add(v7).add(my);
                v15 = v15.lanewise(VectorOperators.XOR, v3).lanewise(VectorOperators.ROR, 8);
                v11 = v11.add(v15);
                v7 = v7.lanewise(VectorOperators.XOR, v11).lanewise(VectorOperators.ROR, 7);
                mx = IntVector.fromArray(S, messages, schedule[8] * LANES);
                my = IntVector.fromArray(S, messages, schedule[9] * LANES);
                v0 = v0.add(v5).add(mx);
                v15 = v15.lanewise(VectorOperators.XOR, v0).lanewise(VectorOperators.ROR, 16);
                v10 = v10.add(v15);
                v5 = v5.lanewise(VectorOperators.XOR, v10).lanewise(VectorOperators.ROR, 12);
                v0 = v0.add(v5).add(my);
                v15 = v15.lanewise(VectorOperators.XOR, v0).lanewise(VectorOperators.ROR, 8);
                v10 = v10.add(v15);
                v5 = v5.lanewise(VectorOperators.XOR, v10).lanewise(VectorOperators.ROR, 7);
                mx = IntVector.fromArray(S, messages, schedule[10] * LANES);
                my = IntVector.fromArray(S, messages, schedule[11] * LANES);
                v1 = v1.add(v6).add(mx);
                v12 = v12.lanewise(VectorOperators.XOR, v1).lanewise(VectorOperators.ROR, 16);
                v11 = v11.add(v12);
                v6 = v6.lanewise(VectorOperators.XOR, v11).lanewise(VectorOperators.ROR, 12);
                v1 = v1.add(v6).add(my);
                v12 = v12.lanewise(VectorOperators.XOR, v1).lanewise(VectorOperators.ROR, 8);
                v11 = v11.add(v12);
                v6 = v6.lanewise(VectorOperators.XOR, v11).lanewise(VectorOperators.ROR, 7);
                mx = IntVector.fromArray(S, messages, schedule[12] * LANES);
                my = IntVector.fromArray(S, messages, schedule[13] * LANES);
                v2 = v2.add(v7).add(mx);
                v13 = v13.lanewise(VectorOperators.XOR, v2).lanewise(VectorOperators.ROR, 16);
                v8 = v8.add(v13);
                v7 = v7.lanewise(VectorOperators.XOR, v8).lanewise(VectorOperators.ROR, 12);
                v2 = v2.add(v7).add(my);
                v13 = v13.lanewise(VectorOperators.XOR, v2).lanewise(VectorOperators.ROR, 8);
                v8 = v8.add(v13);
                v7 = v7.lanewise(VectorOperators.XOR, v8).lanewise(VectorOperators.ROR, 7);
                mx = IntVector.fromArray(S, messages, schedule[14] * LANES);
                my = IntVector.fromArray(S, messages, schedule[15] * LANES);
                v3 = v3.add(v4).add(mx);
                v14 = v14.lanewise(VectorOperators.XOR, v3).lanewise(VectorOperators.ROR, 16);
                v9 = v9.add(v14);
                v4 = v4.lanewise(VectorOperators.XOR, v9).lanewise(VectorOperators.ROR, 12);
                v3 = v3.add(v4).add(my);
                v14 = v14.lanewise(VectorOperators.XOR, v3).lanewise(VectorOperators.ROR, 8);
                v9 = v9.add(v14);
                v4 = v4.lanewise(VectorOperators.XOR, v9).lanewise(VectorOperators.ROR, 7);
            }
            cv0 = v0.lanewise(VectorOperators.XOR, v8);
            cv1 = v1.lanewise(VectorOperators.XOR, v9);
            cv2 = v2.lanewise(VectorOperators.XOR, v10);
            cv3 = v3.lanewise(VectorOperators.XOR, v11);
            cv4 = v4.lanewise(VectorOperators.XOR, v12);
            cv5 = v5.lanewise(VectorOperators.XOR, v13);
            cv6 = v6.lanewise(VectorOperators.XOR, v14);
            cv7 = v7.lanewise(VectorOperators.XOR, v15);
        }
        // Store word-major, then transpose to the lane-major layout the tree
        // reduction consumes. Reusing the (now dead) message scratch keeps this
        // allocation-free and avoids a variable-index lane() per word.
        cv0.intoArray(messages, 0);
        cv1.intoArray(messages, LANES);
        cv2.intoArray(messages, 2 * LANES);
        cv3.intoArray(messages, 3 * LANES);
        cv4.intoArray(messages, 4 * LANES);
        cv5.intoArray(messages, 5 * LANES);
        cv6.intoArray(messages, 6 * LANES);
        cv7.intoArray(messages, 7 * LANES);
        for (int lane = 0; lane < LANES; lane++) {
            int base = lane * 8;
            for (int word = 0; word < 8; word++) {
                output[base + word] = messages[word * LANES + lane];
            }
        }
    }
}
