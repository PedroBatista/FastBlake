package com.pedrobatista.fastblake;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/** Four-lane, intra-block BLAKE3 compression for 128-bit SIMD machines. */
final class Blake3BlockVector {

    private static final VectorSpecies<Integer> S = IntVector.SPECIES_128;
    private static final VectorShuffle<Integer> LEFT_1 = VectorShuffle.fromArray(S,
            new int[]{1, 2, 3, 0}, 0);
    private static final VectorShuffle<Integer> LEFT_2 = VectorShuffle.fromArray(S,
            new int[]{2, 3, 0, 1}, 0);
    private static final VectorShuffle<Integer> LEFT_3 = VectorShuffle.fromArray(S,
            new int[]{3, 0, 1, 2}, 0);

    private static final byte[][] SCHEDULE = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8},
            {3, 4, 10, 12, 13, 2, 7, 14, 6, 5, 9, 0, 11, 15, 8, 1},
            {10, 7, 12, 9, 14, 3, 13, 15, 4, 0, 11, 2, 5, 8, 1, 6},
            {12, 13, 9, 11, 15, 10, 14, 8, 7, 2, 5, 3, 0, 1, 6, 4},
            {9, 14, 11, 5, 8, 12, 15, 1, 13, 3, 0, 10, 2, 6, 4, 7},
            {11, 15, 5, 0, 1, 9, 8, 6, 14, 10, 2, 12, 3, 4, 7, 13}
    };

    // Gather maps arrange the two message inputs for the four simultaneous Gs.
    private static final int[][] MX_COLUMNS = maps(0);
    private static final int[][] MY_COLUMNS = maps(1);
    private static final int[][] MX_DIAGONALS = maps(8);
    private static final int[][] MY_DIAGONALS = maps(9);

    private Blake3BlockVector() {
    }

    static void compress(int[] cv, int[] block, long counter, int blockLength,
                         int flags, int[] output) {
        IntVector a = IntVector.fromArray(S, cv, 0);
        IntVector b = IntVector.fromArray(S, cv, 4);
        IntVector c = IntVector.fromArray(S, FastBlake.IV_WORDS, 0);
        output[12] = (int) counter;
        output[13] = (int) (counter >>> 32);
        output[14] = blockLength;
        output[15] = flags;
        IntVector d = IntVector.fromArray(S, output, 12);

        for (int round = 0; round < 7; round++) {
            IntVector mx = IntVector.fromArray(S, block, 0, MX_COLUMNS[round], 0);
            IntVector my = IntVector.fromArray(S, block, 0, MY_COLUMNS[round], 0);
            a = a.add(b).add(mx);
            d = ror(xor(d, a), 16);
            c = c.add(d);
            b = ror(xor(b, c), 12);
            a = a.add(b).add(my);
            d = ror(xor(d, a), 8);
            c = c.add(d);
            b = ror(xor(b, c), 7);

            b = b.rearrange(LEFT_1);
            c = c.rearrange(LEFT_2);
            d = d.rearrange(LEFT_3);
            mx = IntVector.fromArray(S, block, 0, MX_DIAGONALS[round], 0);
            my = IntVector.fromArray(S, block, 0, MY_DIAGONALS[round], 0);
            a = a.add(b).add(mx);
            d = ror(xor(d, a), 16);
            c = c.add(d);
            b = ror(xor(b, c), 12);
            a = a.add(b).add(my);
            d = ror(xor(d, a), 8);
            c = c.add(d);
            b = ror(xor(b, c), 7);
            b = b.rearrange(LEFT_3);
            c = c.rearrange(LEFT_2);
            d = d.rearrange(LEFT_1);
        }

        xor(a, c).intoArray(output, 0);
        xor(b, d).intoArray(output, 4);
        xor(c, IntVector.fromArray(S, cv, 0)).intoArray(output, 8);
        xor(d, IntVector.fromArray(S, cv, 4)).intoArray(output, 12);
    }

    private static IntVector ror(IntVector value, int bits) {
        return value.lanewise(VectorOperators.ROR, bits);
    }

    private static IntVector xor(IntVector left, IntVector right) {
        return left.lanewise(VectorOperators.XOR, right);
    }

    private static int[][] maps(int start) {
        int[][] maps = new int[7][4];
        for (int round = 0; round < 7; round++) {
            for (int lane = 0; lane < 4; lane++) {
                maps[round][lane] = SCHEDULE[round][start + lane * 2];
            }
        }
        return maps;
    }
}
