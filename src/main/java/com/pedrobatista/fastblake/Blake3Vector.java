package com.pedrobatista.fastblake;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/** Chunk-parallel BLAKE3 compression. Each vector lane hashes one chunk. */
final class Blake3Vector {

    private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;
    private static final int LANES = SPECIES.length();
    private static final int CHUNK_LEN = 1024;
    private static final int BLOCK_LEN = 64;
    private static final int CHUNK_START = 1;
    private static final int CHUNK_END = 2;

    private static final int[] IV = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
            0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
    };

    private static final byte[][] SCHEDULE = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8},
            {3, 4, 10, 12, 13, 2, 7, 14, 6, 5, 9, 0, 11, 15, 8, 1},
            {10, 7, 12, 9, 14, 3, 13, 15, 4, 0, 11, 2, 5, 8, 1, 6},
            {12, 13, 9, 11, 15, 10, 14, 8, 7, 2, 5, 3, 0, 1, 6, 4},
            {9, 14, 11, 5, 8, 12, 15, 1, 13, 3, 0, 10, 2, 6, 4, 7},
            {11, 15, 5, 0, 1, 9, 8, 6, 14, 10, 2, 12, 3, 4, 7, 13}
    };

    private Blake3Vector() {
    }

    static int lanes() {
        return LANES;
    }

    static int packedWordsLength() {
        return 16 * LANES;
    }

    static int outputLength() {
        return 8 * LANES;
    }

    /** Hashes exactly {@link #lanes()} consecutive complete chunks. */
    static void hashChunks(byte[] input, int offset, long firstCounter, int[] key,
                           int flags, int[] packed, int[] output) {
        IntVector[] cv = new IntVector[8];
        IntVector[] state = new IntVector[16];
        IntVector[] message = new IntVector[16];
        for (int i = 0; i < 8; i++) {
            cv[i] = IntVector.broadcast(SPECIES, key[i]);
        }

        for (int block = 0; block < 16; block++) {
            for (int word = 0; word < 16; word++) {
                int packedOffset = word * LANES;
                for (int lane = 0; lane < LANES; lane++) {
                    int p = offset + lane * CHUNK_LEN + block * BLOCK_LEN + word * 4;
                    packed[packedOffset + lane] = (input[p] & 0xff)
                            | ((input[p + 1] & 0xff) << 8)
                            | ((input[p + 2] & 0xff) << 16)
                            | (input[p + 3] << 24);
                }
                message[word] = IntVector.fromArray(SPECIES, packed, packedOffset);
            }

            System.arraycopy(cv, 0, state, 0, 8);
            for (int i = 0; i < 4; i++) {
                state[8 + i] = IntVector.broadcast(SPECIES, IV[i]);
            }
            for (int lane = 0; lane < LANES; lane++) {
                long counter = firstCounter + lane;
                packed[lane] = (int) counter;
                packed[LANES + lane] = (int) (counter >>> 32);
            }
            state[12] = IntVector.fromArray(SPECIES, packed, 0);
            state[13] = IntVector.fromArray(SPECIES, packed, LANES);
            state[14] = IntVector.broadcast(SPECIES, BLOCK_LEN);
            state[15] = IntVector.broadcast(SPECIES, flags
                    | (block == 0 ? CHUNK_START : 0)
                    | (block == 15 ? CHUNK_END : 0));

            for (byte[] schedule : SCHEDULE) {
                g(state, message, schedule, 0, 4, 8, 12, 0, 1);
                g(state, message, schedule, 1, 5, 9, 13, 2, 3);
                g(state, message, schedule, 2, 6, 10, 14, 4, 5);
                g(state, message, schedule, 3, 7, 11, 15, 6, 7);
                g(state, message, schedule, 0, 5, 10, 15, 8, 9);
                g(state, message, schedule, 1, 6, 11, 12, 10, 11);
                g(state, message, schedule, 2, 7, 8, 13, 12, 13);
                g(state, message, schedule, 3, 4, 9, 14, 14, 15);
            }
            for (int i = 0; i < 8; i++) {
                cv[i] = state[i].lanewise(VectorOperators.XOR, state[i + 8]);
            }
        }

        // The tree code consumes chunk-major CVs.
        for (int lane = 0; lane < LANES; lane++) {
            for (int word = 0; word < 8; word++) {
                output[lane * 8 + word] = cv[word].lane(lane);
            }
        }
    }

    private static void g(IntVector[] v, IntVector[] m, byte[] s,
                          int a, int b, int c, int d, int mx, int my) {
        v[a] = v[a].add(v[b]).add(m[s[mx]]);
        v[d] = v[d].lanewise(VectorOperators.XOR, v[a])
                .lanewise(VectorOperators.ROR, 16);
        v[c] = v[c].add(v[d]);
        v[b] = v[b].lanewise(VectorOperators.XOR, v[c])
                .lanewise(VectorOperators.ROR, 12);
        v[a] = v[a].add(v[b]).add(m[s[my]]);
        v[d] = v[d].lanewise(VectorOperators.XOR, v[a])
                .lanewise(VectorOperators.ROR, 8);
        v[c] = v[c].add(v[d]);
        v[b] = v[b].lanewise(VectorOperators.XOR, v[c])
                .lanewise(VectorOperators.ROR, 7);
    }
}
