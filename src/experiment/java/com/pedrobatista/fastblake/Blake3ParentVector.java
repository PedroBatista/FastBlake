package com.pedrobatista.fastblake;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/** E015 chunk-tree parent compression, with one independent parent per lane. */
final class Blake3ParentVector {
    private static final VectorSpecies<Integer> S = IntVector.SPECIES_PREFERRED;
    private static final int PARENT = 4;
    static final int LANES = S.length();

    private static final int[][] SCHEDULE = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
        {2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8},
        {3, 4, 10, 12, 13, 2, 7, 14, 6, 5, 9, 0, 11, 15, 8, 1},
        {10, 7, 12, 9, 14, 3, 13, 15, 4, 0, 11, 2, 5, 8, 1, 6},
        {12, 13, 9, 11, 15, 10, 14, 8, 7, 2, 5, 3, 0, 1, 6, 4},
        {9, 14, 11, 5, 8, 12, 15, 1, 13, 3, 0, 10, 2, 6, 4, 7},
        {11, 15, 5, 0, 1, 9, 8, 6, 14, 10, 2, 12, 3, 4, 7, 13}
    };

    private Blake3ParentVector() {}

    /**
     * Compresses {@code pairCount} adjacent CV pairs from {@code cvs} in place.
     * Input and output are lane-major; output parent CVs occupy the prefix.
     */
    static void compressPairs(int[] cvs, int pairCount, int[] key, int flags,
                              int[] messages) {
        if (pairCount < 1 || pairCount > LANES) {
            throw new IllegalArgumentException("pairCount: " + pairCount);
        }
        // Complete the transpose before overwriting cvs, making in-place output safe.
        for (int word = 0; word < 16; word++) {
            int childWord = word & 7;
            int childSide = word >>> 3;
            int destination = word * LANES;
            for (int lane = 0; lane < LANES; lane++) {
                messages[destination + lane] = lane < pairCount
                        ? cvs[((lane * 2 + childSide) * 8) + childWord] : 0;
            }
        }

        IntVector v0 = IntVector.broadcast(S, key[0]);
        IntVector v1 = IntVector.broadcast(S, key[1]);
        IntVector v2 = IntVector.broadcast(S, key[2]);
        IntVector v3 = IntVector.broadcast(S, key[3]);
        IntVector v4 = IntVector.broadcast(S, key[4]);
        IntVector v5 = IntVector.broadcast(S, key[5]);
        IntVector v6 = IntVector.broadcast(S, key[6]);
        IntVector v7 = IntVector.broadcast(S, key[7]);
        IntVector v8 = IntVector.broadcast(S, FastBlake.IV_WORDS[0]);
        IntVector v9 = IntVector.broadcast(S, FastBlake.IV_WORDS[1]);
        IntVector v10 = IntVector.broadcast(S, FastBlake.IV_WORDS[2]);
        IntVector v11 = IntVector.broadcast(S, FastBlake.IV_WORDS[3]);
        IntVector v12 = IntVector.zero(S);
        IntVector v13 = IntVector.zero(S);
        IntVector v14 = IntVector.broadcast(S, 64);
        IntVector v15 = IntVector.broadcast(S, flags | PARENT);

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

        v0.lanewise(VectorOperators.XOR, v8).intoArray(messages, 0);
        v1.lanewise(VectorOperators.XOR, v9).intoArray(messages, LANES);
        v2.lanewise(VectorOperators.XOR, v10).intoArray(messages, 2 * LANES);
        v3.lanewise(VectorOperators.XOR, v11).intoArray(messages, 3 * LANES);
        v4.lanewise(VectorOperators.XOR, v12).intoArray(messages, 4 * LANES);
        v5.lanewise(VectorOperators.XOR, v13).intoArray(messages, 5 * LANES);
        v6.lanewise(VectorOperators.XOR, v14).intoArray(messages, 6 * LANES);
        v7.lanewise(VectorOperators.XOR, v15).intoArray(messages, 7 * LANES);
        for (int lane = 0; lane < pairCount; lane++) {
            for (int word = 0; word < 8; word++) {
                cvs[lane * 8 + word] = messages[word * LANES + lane];
            }
        }
    }
}
