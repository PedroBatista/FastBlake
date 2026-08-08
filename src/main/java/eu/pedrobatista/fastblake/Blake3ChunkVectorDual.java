package eu.pedrobatista.fastblake;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * E024: two interleaved four-chunk batches -- eight chunks, 8192 bytes.
 *
 * E023 proved the four-chunk round loop is latency-bound on the vector
 * dependency chain, so shrinking its instruction count cannot help. This raises
 * instruction-level parallelism instead, from four independent G chains per
 * half-round to eight, by running two independent chunk groups through one
 * interleaved round body. Lanes stay at SPECIES_128: on NEON, added parallelism
 * comes from more batches in flight, not wider vectors.
 */
final class Blake3ChunkVectorDual {
    private static final VectorSpecies<Integer> S = IntVector.SPECIES_128;
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

    static int packedWordsLength() { return 128; }
    static int outputLength() { return 64; }

    private Blake3ChunkVectorDual() {}

    static void hashChunks(byte[] input, int offset, long firstCounter,
                                  int[] key, int flags, int[] messages, int[] output) {
        messages[0] = (int) (firstCounter + 0);
        messages[1] = (int) (firstCounter + 1);
        messages[2] = (int) (firstCounter + 2);
        messages[3] = (int) (firstCounter + 3);
        messages[4] = (int) ((firstCounter + 0) >>> 32);
        messages[5] = (int) ((firstCounter + 1) >>> 32);
        messages[6] = (int) ((firstCounter + 2) >>> 32);
        messages[7] = (int) ((firstCounter + 3) >>> 32);
        IntVector aCounterLow = IntVector.fromArray(S, messages, 0);
        IntVector aCounterHigh = IntVector.fromArray(S, messages, 4);
        messages[0] = (int) (firstCounter + 4);
        messages[1] = (int) (firstCounter + 5);
        messages[2] = (int) (firstCounter + 6);
        messages[3] = (int) (firstCounter + 7);
        messages[4] = (int) ((firstCounter + 4) >>> 32);
        messages[5] = (int) ((firstCounter + 5) >>> 32);
        messages[6] = (int) ((firstCounter + 6) >>> 32);
        messages[7] = (int) ((firstCounter + 7) >>> 32);
        IntVector bCounterLow = IntVector.fromArray(S, messages, 0);
        IntVector bCounterHigh = IntVector.fromArray(S, messages, 4);
        IntVector acv0 = IntVector.broadcast(S, key[0]);
        IntVector acv1 = IntVector.broadcast(S, key[1]);
        IntVector acv2 = IntVector.broadcast(S, key[2]);
        IntVector acv3 = IntVector.broadcast(S, key[3]);
        IntVector acv4 = IntVector.broadcast(S, key[4]);
        IntVector acv5 = IntVector.broadcast(S, key[5]);
        IntVector acv6 = IntVector.broadcast(S, key[6]);
        IntVector acv7 = IntVector.broadcast(S, key[7]);
        IntVector bcv0 = IntVector.broadcast(S, key[0]);
        IntVector bcv1 = IntVector.broadcast(S, key[1]);
        IntVector bcv2 = IntVector.broadcast(S, key[2]);
        IntVector bcv3 = IntVector.broadcast(S, key[3]);
        IntVector bcv4 = IntVector.broadcast(S, key[4]);
        IntVector bcv5 = IntVector.broadcast(S, key[5]);
        IntVector bcv6 = IntVector.broadcast(S, key[6]);
        IntVector bcv7 = IntVector.broadcast(S, key[7]);
        for (int block = 0; block < 16; block++) {
            int blockOffset = block * 64;
            for (int word = 0; word < 16; word++) {
                int destination = word * 4;
                for (int lane = 0; lane < 4; lane++) {
                    int p = offset + lane * 1024 + blockOffset + word * 4;
                    messages[destination + lane] = (int) LE_INT.get(input, p);
                    messages[64 + destination + lane] = (int) LE_INT.get(input, p + 4096);
                }
            }
            IntVector a0 = acv0;
            IntVector a1 = acv1;
            IntVector a2 = acv2;
            IntVector a3 = acv3;
            IntVector a4 = acv4;
            IntVector a5 = acv5;
            IntVector a6 = acv6;
            IntVector a7 = acv7;
            IntVector a8 = IntVector.broadcast(S, 0x6A09E667);
            IntVector a9 = IntVector.broadcast(S, 0xBB67AE85);
            IntVector a10 = IntVector.broadcast(S, 0x3C6EF372);
            IntVector a11 = IntVector.broadcast(S, 0xA54FF53A);
            IntVector a12 = aCounterLow;
            IntVector a13 = aCounterHigh;
            IntVector a14 = IntVector.broadcast(S, 64);
            IntVector a15 = IntVector.broadcast(S, flags
                    | (block == 0 ? 1 : 0)
                    | (block == 15 ? 2 : 0));
            IntVector b0 = bcv0;
            IntVector b1 = bcv1;
            IntVector b2 = bcv2;
            IntVector b3 = bcv3;
            IntVector b4 = bcv4;
            IntVector b5 = bcv5;
            IntVector b6 = bcv6;
            IntVector b7 = bcv7;
            IntVector b8 = IntVector.broadcast(S, 0x6A09E667);
            IntVector b9 = IntVector.broadcast(S, 0xBB67AE85);
            IntVector b10 = IntVector.broadcast(S, 0x3C6EF372);
            IntVector b11 = IntVector.broadcast(S, 0xA54FF53A);
            IntVector b12 = bCounterLow;
            IntVector b13 = bCounterHigh;
            IntVector b14 = IntVector.broadcast(S, 64);
            IntVector b15 = IntVector.broadcast(S, flags
                    | (block == 0 ? 1 : 0)
                    | (block == 15 ? 2 : 0));
            for (int round = 0; round < 7; round++) {
                int[] schedule = SCHEDULE[round];
                IntVector amx;
                IntVector amy;
                IntVector bmx;
                IntVector bmy;
                amx = IntVector.fromArray(S, messages, schedule[0] * 4);
                amy = IntVector.fromArray(S, messages, schedule[1] * 4);
                a0 = a0.add(a4).add(amx);
                a12 = a12.lanewise(VectorOperators.XOR, a0).lanewise(VectorOperators.ROR, 16);
                a8 = a8.add(a12);
                a4 = a4.lanewise(VectorOperators.XOR, a8).lanewise(VectorOperators.ROR, 12);
                a0 = a0.add(a4).add(amy);
                a12 = a12.lanewise(VectorOperators.XOR, a0).lanewise(VectorOperators.ROR, 8);
                a8 = a8.add(a12);
                a4 = a4.lanewise(VectorOperators.XOR, a8).lanewise(VectorOperators.ROR, 7);
                bmx = IntVector.fromArray(S, messages, schedule[0] * 4 + 64);
                bmy = IntVector.fromArray(S, messages, schedule[1] * 4 + 64);
                b0 = b0.add(b4).add(bmx);
                b12 = b12.lanewise(VectorOperators.XOR, b0).lanewise(VectorOperators.ROR, 16);
                b8 = b8.add(b12);
                b4 = b4.lanewise(VectorOperators.XOR, b8).lanewise(VectorOperators.ROR, 12);
                b0 = b0.add(b4).add(bmy);
                b12 = b12.lanewise(VectorOperators.XOR, b0).lanewise(VectorOperators.ROR, 8);
                b8 = b8.add(b12);
                b4 = b4.lanewise(VectorOperators.XOR, b8).lanewise(VectorOperators.ROR, 7);
                amx = IntVector.fromArray(S, messages, schedule[2] * 4);
                amy = IntVector.fromArray(S, messages, schedule[3] * 4);
                a1 = a1.add(a5).add(amx);
                a13 = a13.lanewise(VectorOperators.XOR, a1).lanewise(VectorOperators.ROR, 16);
                a9 = a9.add(a13);
                a5 = a5.lanewise(VectorOperators.XOR, a9).lanewise(VectorOperators.ROR, 12);
                a1 = a1.add(a5).add(amy);
                a13 = a13.lanewise(VectorOperators.XOR, a1).lanewise(VectorOperators.ROR, 8);
                a9 = a9.add(a13);
                a5 = a5.lanewise(VectorOperators.XOR, a9).lanewise(VectorOperators.ROR, 7);
                bmx = IntVector.fromArray(S, messages, schedule[2] * 4 + 64);
                bmy = IntVector.fromArray(S, messages, schedule[3] * 4 + 64);
                b1 = b1.add(b5).add(bmx);
                b13 = b13.lanewise(VectorOperators.XOR, b1).lanewise(VectorOperators.ROR, 16);
                b9 = b9.add(b13);
                b5 = b5.lanewise(VectorOperators.XOR, b9).lanewise(VectorOperators.ROR, 12);
                b1 = b1.add(b5).add(bmy);
                b13 = b13.lanewise(VectorOperators.XOR, b1).lanewise(VectorOperators.ROR, 8);
                b9 = b9.add(b13);
                b5 = b5.lanewise(VectorOperators.XOR, b9).lanewise(VectorOperators.ROR, 7);
                amx = IntVector.fromArray(S, messages, schedule[4] * 4);
                amy = IntVector.fromArray(S, messages, schedule[5] * 4);
                a2 = a2.add(a6).add(amx);
                a14 = a14.lanewise(VectorOperators.XOR, a2).lanewise(VectorOperators.ROR, 16);
                a10 = a10.add(a14);
                a6 = a6.lanewise(VectorOperators.XOR, a10).lanewise(VectorOperators.ROR, 12);
                a2 = a2.add(a6).add(amy);
                a14 = a14.lanewise(VectorOperators.XOR, a2).lanewise(VectorOperators.ROR, 8);
                a10 = a10.add(a14);
                a6 = a6.lanewise(VectorOperators.XOR, a10).lanewise(VectorOperators.ROR, 7);
                bmx = IntVector.fromArray(S, messages, schedule[4] * 4 + 64);
                bmy = IntVector.fromArray(S, messages, schedule[5] * 4 + 64);
                b2 = b2.add(b6).add(bmx);
                b14 = b14.lanewise(VectorOperators.XOR, b2).lanewise(VectorOperators.ROR, 16);
                b10 = b10.add(b14);
                b6 = b6.lanewise(VectorOperators.XOR, b10).lanewise(VectorOperators.ROR, 12);
                b2 = b2.add(b6).add(bmy);
                b14 = b14.lanewise(VectorOperators.XOR, b2).lanewise(VectorOperators.ROR, 8);
                b10 = b10.add(b14);
                b6 = b6.lanewise(VectorOperators.XOR, b10).lanewise(VectorOperators.ROR, 7);
                amx = IntVector.fromArray(S, messages, schedule[6] * 4);
                amy = IntVector.fromArray(S, messages, schedule[7] * 4);
                a3 = a3.add(a7).add(amx);
                a15 = a15.lanewise(VectorOperators.XOR, a3).lanewise(VectorOperators.ROR, 16);
                a11 = a11.add(a15);
                a7 = a7.lanewise(VectorOperators.XOR, a11).lanewise(VectorOperators.ROR, 12);
                a3 = a3.add(a7).add(amy);
                a15 = a15.lanewise(VectorOperators.XOR, a3).lanewise(VectorOperators.ROR, 8);
                a11 = a11.add(a15);
                a7 = a7.lanewise(VectorOperators.XOR, a11).lanewise(VectorOperators.ROR, 7);
                bmx = IntVector.fromArray(S, messages, schedule[6] * 4 + 64);
                bmy = IntVector.fromArray(S, messages, schedule[7] * 4 + 64);
                b3 = b3.add(b7).add(bmx);
                b15 = b15.lanewise(VectorOperators.XOR, b3).lanewise(VectorOperators.ROR, 16);
                b11 = b11.add(b15);
                b7 = b7.lanewise(VectorOperators.XOR, b11).lanewise(VectorOperators.ROR, 12);
                b3 = b3.add(b7).add(bmy);
                b15 = b15.lanewise(VectorOperators.XOR, b3).lanewise(VectorOperators.ROR, 8);
                b11 = b11.add(b15);
                b7 = b7.lanewise(VectorOperators.XOR, b11).lanewise(VectorOperators.ROR, 7);
                amx = IntVector.fromArray(S, messages, schedule[8] * 4);
                amy = IntVector.fromArray(S, messages, schedule[9] * 4);
                a0 = a0.add(a5).add(amx);
                a15 = a15.lanewise(VectorOperators.XOR, a0).lanewise(VectorOperators.ROR, 16);
                a10 = a10.add(a15);
                a5 = a5.lanewise(VectorOperators.XOR, a10).lanewise(VectorOperators.ROR, 12);
                a0 = a0.add(a5).add(amy);
                a15 = a15.lanewise(VectorOperators.XOR, a0).lanewise(VectorOperators.ROR, 8);
                a10 = a10.add(a15);
                a5 = a5.lanewise(VectorOperators.XOR, a10).lanewise(VectorOperators.ROR, 7);
                bmx = IntVector.fromArray(S, messages, schedule[8] * 4 + 64);
                bmy = IntVector.fromArray(S, messages, schedule[9] * 4 + 64);
                b0 = b0.add(b5).add(bmx);
                b15 = b15.lanewise(VectorOperators.XOR, b0).lanewise(VectorOperators.ROR, 16);
                b10 = b10.add(b15);
                b5 = b5.lanewise(VectorOperators.XOR, b10).lanewise(VectorOperators.ROR, 12);
                b0 = b0.add(b5).add(bmy);
                b15 = b15.lanewise(VectorOperators.XOR, b0).lanewise(VectorOperators.ROR, 8);
                b10 = b10.add(b15);
                b5 = b5.lanewise(VectorOperators.XOR, b10).lanewise(VectorOperators.ROR, 7);
                amx = IntVector.fromArray(S, messages, schedule[10] * 4);
                amy = IntVector.fromArray(S, messages, schedule[11] * 4);
                a1 = a1.add(a6).add(amx);
                a12 = a12.lanewise(VectorOperators.XOR, a1).lanewise(VectorOperators.ROR, 16);
                a11 = a11.add(a12);
                a6 = a6.lanewise(VectorOperators.XOR, a11).lanewise(VectorOperators.ROR, 12);
                a1 = a1.add(a6).add(amy);
                a12 = a12.lanewise(VectorOperators.XOR, a1).lanewise(VectorOperators.ROR, 8);
                a11 = a11.add(a12);
                a6 = a6.lanewise(VectorOperators.XOR, a11).lanewise(VectorOperators.ROR, 7);
                bmx = IntVector.fromArray(S, messages, schedule[10] * 4 + 64);
                bmy = IntVector.fromArray(S, messages, schedule[11] * 4 + 64);
                b1 = b1.add(b6).add(bmx);
                b12 = b12.lanewise(VectorOperators.XOR, b1).lanewise(VectorOperators.ROR, 16);
                b11 = b11.add(b12);
                b6 = b6.lanewise(VectorOperators.XOR, b11).lanewise(VectorOperators.ROR, 12);
                b1 = b1.add(b6).add(bmy);
                b12 = b12.lanewise(VectorOperators.XOR, b1).lanewise(VectorOperators.ROR, 8);
                b11 = b11.add(b12);
                b6 = b6.lanewise(VectorOperators.XOR, b11).lanewise(VectorOperators.ROR, 7);
                amx = IntVector.fromArray(S, messages, schedule[12] * 4);
                amy = IntVector.fromArray(S, messages, schedule[13] * 4);
                a2 = a2.add(a7).add(amx);
                a13 = a13.lanewise(VectorOperators.XOR, a2).lanewise(VectorOperators.ROR, 16);
                a8 = a8.add(a13);
                a7 = a7.lanewise(VectorOperators.XOR, a8).lanewise(VectorOperators.ROR, 12);
                a2 = a2.add(a7).add(amy);
                a13 = a13.lanewise(VectorOperators.XOR, a2).lanewise(VectorOperators.ROR, 8);
                a8 = a8.add(a13);
                a7 = a7.lanewise(VectorOperators.XOR, a8).lanewise(VectorOperators.ROR, 7);
                bmx = IntVector.fromArray(S, messages, schedule[12] * 4 + 64);
                bmy = IntVector.fromArray(S, messages, schedule[13] * 4 + 64);
                b2 = b2.add(b7).add(bmx);
                b13 = b13.lanewise(VectorOperators.XOR, b2).lanewise(VectorOperators.ROR, 16);
                b8 = b8.add(b13);
                b7 = b7.lanewise(VectorOperators.XOR, b8).lanewise(VectorOperators.ROR, 12);
                b2 = b2.add(b7).add(bmy);
                b13 = b13.lanewise(VectorOperators.XOR, b2).lanewise(VectorOperators.ROR, 8);
                b8 = b8.add(b13);
                b7 = b7.lanewise(VectorOperators.XOR, b8).lanewise(VectorOperators.ROR, 7);
                amx = IntVector.fromArray(S, messages, schedule[14] * 4);
                amy = IntVector.fromArray(S, messages, schedule[15] * 4);
                a3 = a3.add(a4).add(amx);
                a14 = a14.lanewise(VectorOperators.XOR, a3).lanewise(VectorOperators.ROR, 16);
                a9 = a9.add(a14);
                a4 = a4.lanewise(VectorOperators.XOR, a9).lanewise(VectorOperators.ROR, 12);
                a3 = a3.add(a4).add(amy);
                a14 = a14.lanewise(VectorOperators.XOR, a3).lanewise(VectorOperators.ROR, 8);
                a9 = a9.add(a14);
                a4 = a4.lanewise(VectorOperators.XOR, a9).lanewise(VectorOperators.ROR, 7);
                bmx = IntVector.fromArray(S, messages, schedule[14] * 4 + 64);
                bmy = IntVector.fromArray(S, messages, schedule[15] * 4 + 64);
                b3 = b3.add(b4).add(bmx);
                b14 = b14.lanewise(VectorOperators.XOR, b3).lanewise(VectorOperators.ROR, 16);
                b9 = b9.add(b14);
                b4 = b4.lanewise(VectorOperators.XOR, b9).lanewise(VectorOperators.ROR, 12);
                b3 = b3.add(b4).add(bmy);
                b14 = b14.lanewise(VectorOperators.XOR, b3).lanewise(VectorOperators.ROR, 8);
                b9 = b9.add(b14);
                b4 = b4.lanewise(VectorOperators.XOR, b9).lanewise(VectorOperators.ROR, 7);
            }
            acv0 = a0.lanewise(VectorOperators.XOR, a8);
            acv1 = a1.lanewise(VectorOperators.XOR, a9);
            acv2 = a2.lanewise(VectorOperators.XOR, a10);
            acv3 = a3.lanewise(VectorOperators.XOR, a11);
            acv4 = a4.lanewise(VectorOperators.XOR, a12);
            acv5 = a5.lanewise(VectorOperators.XOR, a13);
            acv6 = a6.lanewise(VectorOperators.XOR, a14);
            acv7 = a7.lanewise(VectorOperators.XOR, a15);
            bcv0 = b0.lanewise(VectorOperators.XOR, b8);
            bcv1 = b1.lanewise(VectorOperators.XOR, b9);
            bcv2 = b2.lanewise(VectorOperators.XOR, b10);
            bcv3 = b3.lanewise(VectorOperators.XOR, b11);
            bcv4 = b4.lanewise(VectorOperators.XOR, b12);
            bcv5 = b5.lanewise(VectorOperators.XOR, b13);
            bcv6 = b6.lanewise(VectorOperators.XOR, b14);
            bcv7 = b7.lanewise(VectorOperators.XOR, b15);
        }
        output[0] = acv0.lane(0);
        output[1] = acv1.lane(0);
        output[2] = acv2.lane(0);
        output[3] = acv3.lane(0);
        output[4] = acv4.lane(0);
        output[5] = acv5.lane(0);
        output[6] = acv6.lane(0);
        output[7] = acv7.lane(0);
        output[8] = acv0.lane(1);
        output[9] = acv1.lane(1);
        output[10] = acv2.lane(1);
        output[11] = acv3.lane(1);
        output[12] = acv4.lane(1);
        output[13] = acv5.lane(1);
        output[14] = acv6.lane(1);
        output[15] = acv7.lane(1);
        output[16] = acv0.lane(2);
        output[17] = acv1.lane(2);
        output[18] = acv2.lane(2);
        output[19] = acv3.lane(2);
        output[20] = acv4.lane(2);
        output[21] = acv5.lane(2);
        output[22] = acv6.lane(2);
        output[23] = acv7.lane(2);
        output[24] = acv0.lane(3);
        output[25] = acv1.lane(3);
        output[26] = acv2.lane(3);
        output[27] = acv3.lane(3);
        output[28] = acv4.lane(3);
        output[29] = acv5.lane(3);
        output[30] = acv6.lane(3);
        output[31] = acv7.lane(3);
        output[32] = bcv0.lane(0);
        output[33] = bcv1.lane(0);
        output[34] = bcv2.lane(0);
        output[35] = bcv3.lane(0);
        output[36] = bcv4.lane(0);
        output[37] = bcv5.lane(0);
        output[38] = bcv6.lane(0);
        output[39] = bcv7.lane(0);
        output[40] = bcv0.lane(1);
        output[41] = bcv1.lane(1);
        output[42] = bcv2.lane(1);
        output[43] = bcv3.lane(1);
        output[44] = bcv4.lane(1);
        output[45] = bcv5.lane(1);
        output[46] = bcv6.lane(1);
        output[47] = bcv7.lane(1);
        output[48] = bcv0.lane(2);
        output[49] = bcv1.lane(2);
        output[50] = bcv2.lane(2);
        output[51] = bcv3.lane(2);
        output[52] = bcv4.lane(2);
        output[53] = bcv5.lane(2);
        output[54] = bcv6.lane(2);
        output[55] = bcv7.lane(2);
        output[56] = bcv0.lane(3);
        output[57] = bcv1.lane(3);
        output[58] = bcv2.lane(3);
        output[59] = bcv3.lane(3);
        output[60] = bcv4.lane(3);
        output[61] = bcv5.lane(3);
        output[62] = bcv6.lane(3);
        output[63] = bcv7.lane(3);
    }
}
