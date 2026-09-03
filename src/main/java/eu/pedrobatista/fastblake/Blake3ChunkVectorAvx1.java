package eu.pedrobatista.fastblake;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/** AVX1 four-chunk kernel using primitive shift/OR rotates to avoid C2 ROR boxing. */
final class Blake3ChunkVectorAvx1 {
    private static final VectorSpecies<Integer> S = IntVector.SPECIES_128;

    // E014: the message transpose was about 30% of this kernel as byte shifts.
    // This VarHandle always reads little-endian regardless of platform byte
    // order, so it keeps the kernel endian-neutral and needs no guard.
    private static final VarHandle LE_INT = MethodHandles
            .byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    // E030: do not extract the shift/OR rotations below into a helper. On
    // Temurin 21 and 25 with effective AVX1, direct ROR boxed one vector per
    // rotation and a shift/OR helper still boxed four results per G. Textually
    // inlining the same expression made the complete kernel allocation-free.
    private static final int[][] SCHEDULE = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
        {2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8},
        {3, 4, 10, 12, 13, 2, 7, 14, 6, 5, 9, 0, 11, 15, 8, 1},
        {10, 7, 12, 9, 14, 3, 13, 15, 4, 0, 11, 2, 5, 8, 1, 6},
        {12, 13, 9, 11, 15, 10, 14, 8, 7, 2, 5, 3, 0, 1, 6, 4},
        {9, 14, 11, 5, 8, 12, 15, 1, 13, 3, 0, 10, 2, 6, 4, 7},
        {11, 15, 5, 0, 1, 9, 8, 6, 14, 10, 2, 12, 3, 4, 7, 13}
    };

    private Blake3ChunkVectorAvx1() {}

    static int packedWordsLength() { return 64; }
    static int outputLength() { return 32; }

    static void hashChunks(byte[] input, int offset, long firstCounter,
                           int[] key, int flags, int[] messages, int[] output) {
        long counter0 = firstCounter + 0;
        long counter1 = firstCounter + 1;
        long counter2 = firstCounter + 2;
        long counter3 = firstCounter + 3;
        messages[0] = (int) counter0;
        messages[1] = (int) counter1;
        messages[2] = (int) counter2;
        messages[3] = (int) counter3;
        messages[4] = (int) (counter0 >>> 32);
        messages[5] = (int) (counter1 >>> 32);
        messages[6] = (int) (counter2 >>> 32);
        messages[7] = (int) (counter3 >>> 32);
        IntVector counterLow = IntVector.fromArray(S, messages, 0);
        IntVector counterHigh = IntVector.fromArray(S, messages, 4);
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
                int destination = word * 4;
                for (int lane = 0; lane < 4; lane++) {
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
                mx = IntVector.fromArray(S, messages, schedule[0] * 4);
                my = IntVector.fromArray(S, messages, schedule[1] * 4);
                v0 = v0.add(v4).add(mx);
                v12 = v12.lanewise(VectorOperators.XOR, v0);
                v12 = v12.lanewise(VectorOperators.LSHR, 16)
                        .or(v12.lanewise(VectorOperators.LSHL, 16));
                v8 = v8.add(v12);
                v4 = v4.lanewise(VectorOperators.XOR, v8);
                v4 = v4.lanewise(VectorOperators.LSHR, 12)
                        .or(v4.lanewise(VectorOperators.LSHL, 20));
                v0 = v0.add(v4).add(my);
                v12 = v12.lanewise(VectorOperators.XOR, v0);
                v12 = v12.lanewise(VectorOperators.LSHR, 8)
                        .or(v12.lanewise(VectorOperators.LSHL, 24));
                v8 = v8.add(v12);
                v4 = v4.lanewise(VectorOperators.XOR, v8);
                v4 = v4.lanewise(VectorOperators.LSHR, 7)
                        .or(v4.lanewise(VectorOperators.LSHL, 25));
                mx = IntVector.fromArray(S, messages, schedule[2] * 4);
                my = IntVector.fromArray(S, messages, schedule[3] * 4);
                v1 = v1.add(v5).add(mx);
                v13 = v13.lanewise(VectorOperators.XOR, v1);
                v13 = v13.lanewise(VectorOperators.LSHR, 16)
                        .or(v13.lanewise(VectorOperators.LSHL, 16));
                v9 = v9.add(v13);
                v5 = v5.lanewise(VectorOperators.XOR, v9);
                v5 = v5.lanewise(VectorOperators.LSHR, 12)
                        .or(v5.lanewise(VectorOperators.LSHL, 20));
                v1 = v1.add(v5).add(my);
                v13 = v13.lanewise(VectorOperators.XOR, v1);
                v13 = v13.lanewise(VectorOperators.LSHR, 8)
                        .or(v13.lanewise(VectorOperators.LSHL, 24));
                v9 = v9.add(v13);
                v5 = v5.lanewise(VectorOperators.XOR, v9);
                v5 = v5.lanewise(VectorOperators.LSHR, 7)
                        .or(v5.lanewise(VectorOperators.LSHL, 25));
                mx = IntVector.fromArray(S, messages, schedule[4] * 4);
                my = IntVector.fromArray(S, messages, schedule[5] * 4);
                v2 = v2.add(v6).add(mx);
                v14 = v14.lanewise(VectorOperators.XOR, v2);
                v14 = v14.lanewise(VectorOperators.LSHR, 16)
                        .or(v14.lanewise(VectorOperators.LSHL, 16));
                v10 = v10.add(v14);
                v6 = v6.lanewise(VectorOperators.XOR, v10);
                v6 = v6.lanewise(VectorOperators.LSHR, 12)
                        .or(v6.lanewise(VectorOperators.LSHL, 20));
                v2 = v2.add(v6).add(my);
                v14 = v14.lanewise(VectorOperators.XOR, v2);
                v14 = v14.lanewise(VectorOperators.LSHR, 8)
                        .or(v14.lanewise(VectorOperators.LSHL, 24));
                v10 = v10.add(v14);
                v6 = v6.lanewise(VectorOperators.XOR, v10);
                v6 = v6.lanewise(VectorOperators.LSHR, 7)
                        .or(v6.lanewise(VectorOperators.LSHL, 25));
                mx = IntVector.fromArray(S, messages, schedule[6] * 4);
                my = IntVector.fromArray(S, messages, schedule[7] * 4);
                v3 = v3.add(v7).add(mx);
                v15 = v15.lanewise(VectorOperators.XOR, v3);
                v15 = v15.lanewise(VectorOperators.LSHR, 16)
                        .or(v15.lanewise(VectorOperators.LSHL, 16));
                v11 = v11.add(v15);
                v7 = v7.lanewise(VectorOperators.XOR, v11);
                v7 = v7.lanewise(VectorOperators.LSHR, 12)
                        .or(v7.lanewise(VectorOperators.LSHL, 20));
                v3 = v3.add(v7).add(my);
                v15 = v15.lanewise(VectorOperators.XOR, v3);
                v15 = v15.lanewise(VectorOperators.LSHR, 8)
                        .or(v15.lanewise(VectorOperators.LSHL, 24));
                v11 = v11.add(v15);
                v7 = v7.lanewise(VectorOperators.XOR, v11);
                v7 = v7.lanewise(VectorOperators.LSHR, 7)
                        .or(v7.lanewise(VectorOperators.LSHL, 25));
                mx = IntVector.fromArray(S, messages, schedule[8] * 4);
                my = IntVector.fromArray(S, messages, schedule[9] * 4);
                v0 = v0.add(v5).add(mx);
                v15 = v15.lanewise(VectorOperators.XOR, v0);
                v15 = v15.lanewise(VectorOperators.LSHR, 16)
                        .or(v15.lanewise(VectorOperators.LSHL, 16));
                v10 = v10.add(v15);
                v5 = v5.lanewise(VectorOperators.XOR, v10);
                v5 = v5.lanewise(VectorOperators.LSHR, 12)
                        .or(v5.lanewise(VectorOperators.LSHL, 20));
                v0 = v0.add(v5).add(my);
                v15 = v15.lanewise(VectorOperators.XOR, v0);
                v15 = v15.lanewise(VectorOperators.LSHR, 8)
                        .or(v15.lanewise(VectorOperators.LSHL, 24));
                v10 = v10.add(v15);
                v5 = v5.lanewise(VectorOperators.XOR, v10);
                v5 = v5.lanewise(VectorOperators.LSHR, 7)
                        .or(v5.lanewise(VectorOperators.LSHL, 25));
                mx = IntVector.fromArray(S, messages, schedule[10] * 4);
                my = IntVector.fromArray(S, messages, schedule[11] * 4);
                v1 = v1.add(v6).add(mx);
                v12 = v12.lanewise(VectorOperators.XOR, v1);
                v12 = v12.lanewise(VectorOperators.LSHR, 16)
                        .or(v12.lanewise(VectorOperators.LSHL, 16));
                v11 = v11.add(v12);
                v6 = v6.lanewise(VectorOperators.XOR, v11);
                v6 = v6.lanewise(VectorOperators.LSHR, 12)
                        .or(v6.lanewise(VectorOperators.LSHL, 20));
                v1 = v1.add(v6).add(my);
                v12 = v12.lanewise(VectorOperators.XOR, v1);
                v12 = v12.lanewise(VectorOperators.LSHR, 8)
                        .or(v12.lanewise(VectorOperators.LSHL, 24));
                v11 = v11.add(v12);
                v6 = v6.lanewise(VectorOperators.XOR, v11);
                v6 = v6.lanewise(VectorOperators.LSHR, 7)
                        .or(v6.lanewise(VectorOperators.LSHL, 25));
                mx = IntVector.fromArray(S, messages, schedule[12] * 4);
                my = IntVector.fromArray(S, messages, schedule[13] * 4);
                v2 = v2.add(v7).add(mx);
                v13 = v13.lanewise(VectorOperators.XOR, v2);
                v13 = v13.lanewise(VectorOperators.LSHR, 16)
                        .or(v13.lanewise(VectorOperators.LSHL, 16));
                v8 = v8.add(v13);
                v7 = v7.lanewise(VectorOperators.XOR, v8);
                v7 = v7.lanewise(VectorOperators.LSHR, 12)
                        .or(v7.lanewise(VectorOperators.LSHL, 20));
                v2 = v2.add(v7).add(my);
                v13 = v13.lanewise(VectorOperators.XOR, v2);
                v13 = v13.lanewise(VectorOperators.LSHR, 8)
                        .or(v13.lanewise(VectorOperators.LSHL, 24));
                v8 = v8.add(v13);
                v7 = v7.lanewise(VectorOperators.XOR, v8);
                v7 = v7.lanewise(VectorOperators.LSHR, 7)
                        .or(v7.lanewise(VectorOperators.LSHL, 25));
                mx = IntVector.fromArray(S, messages, schedule[14] * 4);
                my = IntVector.fromArray(S, messages, schedule[15] * 4);
                v3 = v3.add(v4).add(mx);
                v14 = v14.lanewise(VectorOperators.XOR, v3);
                v14 = v14.lanewise(VectorOperators.LSHR, 16)
                        .or(v14.lanewise(VectorOperators.LSHL, 16));
                v9 = v9.add(v14);
                v4 = v4.lanewise(VectorOperators.XOR, v9);
                v4 = v4.lanewise(VectorOperators.LSHR, 12)
                        .or(v4.lanewise(VectorOperators.LSHL, 20));
                v3 = v3.add(v4).add(my);
                v14 = v14.lanewise(VectorOperators.XOR, v3);
                v14 = v14.lanewise(VectorOperators.LSHR, 8)
                        .or(v14.lanewise(VectorOperators.LSHL, 24));
                v9 = v9.add(v14);
                v4 = v4.lanewise(VectorOperators.XOR, v9);
                v4 = v4.lanewise(VectorOperators.LSHR, 7)
                        .or(v4.lanewise(VectorOperators.LSHL, 25));
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
    }
}
