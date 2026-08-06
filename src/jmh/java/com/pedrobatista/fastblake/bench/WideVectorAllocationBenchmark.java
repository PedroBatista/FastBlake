package com.pedrobatista.fastblake.bench;

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

/**
 * E013 allocation ladder at {@link IntVector#SPECIES_PREFERRED} width.
 *
 * <p>This is {@code VectorAllocationBenchmark}'s ladder with the species and
 * every derived stride parameterised on the machine's preferred width, so the
 * rungs can be re-gated at the lane count the target actually has. E012 showed
 * the seven-round escape-analysis cliff sits at a code-size boundary that
 * reproduces identically on AArch64 and x86-64; changing the lane count moves
 * the transpose body and the extraction, so E011's clearance does not carry
 * over and has to be re-measured here.
 *
 * <p>Run one fork per method with the GC profiler, and read allocation before
 * throughput:
 * {@snippet :
 * ./gradlew jmh -P'jmh.args=WideVectorAllocationBenchmark -f1 -wi 3 -i 3 -prof gc'
 * }
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class WideVectorAllocationBenchmark {

    private static final VectorSpecies<Integer> S = IntVector.SPECIES_PREFERRED;
    private static final int LANES = S.length();
    private static final int[][] SCHEDULE = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
        {2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8},
        {3, 4, 10, 12, 13, 2, 7, 14, 6, 5, 9, 0, 11, 15, 8, 1},
        {10, 7, 12, 9, 14, 3, 13, 15, 4, 0, 11, 2, 5, 8, 1, 6},
        {12, 13, 9, 11, 15, 10, 14, 8, 7, 2, 5, 3, 0, 1, 6, 4},
        {9, 14, 11, 5, 8, 12, 15, 1, 13, 3, 0, 10, 2, 6, 4, 7},
        {11, 15, 5, 0, 1, 9, 8, 6, 14, 10, 2, 12, 3, 4, 7, 13}
    };

    private final byte[] chunks = new byte[LANES * 1024];
    private final int[] messages = new int[16 * LANES];
    private final int[] initialState = new int[16 * LANES];
    private final int[] cvScratch = new int[8 * LANES];

    public WideVectorAllocationBenchmark() {
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = (byte) (i * 131 + 17);
        }
        for (int word = 0; word < 16; word++) {
            for (int lane = 0; lane < LANES; lane++) {
                messages[word * LANES + lane] = word * 0x9e3779b9 + lane * 0x7f4a7c15;
                initialState[word * LANES + lane] = word * 0x6a09e667 + lane;
            }
        }
    }

    /**
     * Issue-width probe: an identical BLAKE-shaped dependency chain at 128 and
     * 256 bits, with the same number of vector operations in each.
     *
     * <p>{@code SPECIES_PREFERRED} reports the widest vector the ISA can name,
     * which is not the same as the width the core can retire per cycle. If the
     * 256-bit chain costs about twice the 128-bit chain for the same op count,
     * the execution units are 128 bits wide and each 256-bit operation is being
     * split, so doubling the lane count buys no throughput. If the two cost
     * about the same, the wider chain is doing twice the useful work per
     * operation. That establishes useful primitive width, but does not predict a
     * complete-kernel win because loads, transpose, stores, and register pressure
     * are intentionally absent from this probe.
     */
    @Benchmark
    public int mixChain128() {
        return mixChain(IntVector.SPECIES_128);
    }

    /** @see #mixChain128() */
    @Benchmark
    public int mixChain256() {
        return mixChain(IntVector.SPECIES_256);
    }

    private int mixChain(VectorSpecies<Integer> species) {
        IntVector a = IntVector.broadcast(species, 0x6A09E667);
        IntVector b = IntVector.broadcast(species, 0xBB67AE85);
        IntVector c = IntVector.broadcast(species, 0x3C6EF372);
        IntVector d = IntVector.broadcast(species, 0xA54FF53A);
        for (int i = 0; i < 1024; i++) {
            a = a.add(b);
            d = d.lanewise(VectorOperators.XOR, a).lanewise(VectorOperators.ROR, 16);
            c = c.add(d);
            b = b.lanewise(VectorOperators.XOR, c).lanewise(VectorOperators.ROR, 12);
        }
        return a.lane(0) ^ b.lane(0) ^ c.lane(0) ^ d.lane(0);
    }

    /** Rung 1: the scalar transpose that feeds every later rung. */
    @Benchmark
    public int wideTransposeToScratch() {
        int checksum = 0;
        for (int word = 0; word < 16; word++) {
            int withinBlock = word * 4;
            int destination = word * LANES;
            for (int lane = 0; lane < LANES; lane++) {
                int p = lane * 1024 + withinBlock;
                int value = (chunks[p] & 0xff)
                        | ((chunks[p + 1] & 0xff) << 8)
                        | ((chunks[p + 2] & 0xff) << 16)
                        | (chunks[p + 3] << 24);
                messages[destination + lane] = value;
                checksum ^= value;
            }
        }
        return checksum;
    }

    /** Rung 2: seven rounds for one block through the compact schedule loop. */
    @Benchmark
    public int wideSevenRoundsLoop() {
        IntVector v0 = IntVector.fromArray(S, initialState, 0);
        IntVector v1 = IntVector.fromArray(S, initialState, LANES);
        IntVector v2 = IntVector.fromArray(S, initialState, 2 * LANES);
        IntVector v3 = IntVector.fromArray(S, initialState, 3 * LANES);
        IntVector v4 = IntVector.fromArray(S, initialState, 4 * LANES);
        IntVector v5 = IntVector.fromArray(S, initialState, 5 * LANES);
        IntVector v6 = IntVector.fromArray(S, initialState, 6 * LANES);
        IntVector v7 = IntVector.fromArray(S, initialState, 7 * LANES);
        IntVector v8 = IntVector.fromArray(S, initialState, 8 * LANES);
        IntVector v9 = IntVector.fromArray(S, initialState, 9 * LANES);
        IntVector v10 = IntVector.fromArray(S, initialState, 10 * LANES);
        IntVector v11 = IntVector.fromArray(S, initialState, 11 * LANES);
        IntVector v12 = IntVector.fromArray(S, initialState, 12 * LANES);
        IntVector v13 = IntVector.fromArray(S, initialState, 13 * LANES);
        IntVector v14 = IntVector.fromArray(S, initialState, 14 * LANES);
        IntVector v15 = IntVector.fromArray(S, initialState, 15 * LANES);
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
        return v0.lane(0) ^ v8.lane(0) ^ v15.lane(0);
    }

    /** Rung 3: transpose, compress and chain across all 16 blocks of a chunk. */
    @Benchmark
    public int wideChunkLoop() {
        IntVector cv0 = IntVector.fromArray(S, initialState, 0);
        IntVector cv1 = IntVector.fromArray(S, initialState, LANES);
        IntVector cv2 = IntVector.fromArray(S, initialState, 2 * LANES);
        IntVector cv3 = IntVector.fromArray(S, initialState, 3 * LANES);
        IntVector cv4 = IntVector.fromArray(S, initialState, 4 * LANES);
        IntVector cv5 = IntVector.fromArray(S, initialState, 5 * LANES);
        IntVector cv6 = IntVector.fromArray(S, initialState, 6 * LANES);
        IntVector cv7 = IntVector.fromArray(S, initialState, 7 * LANES);
        for (int block = 0; block < 16; block++) {
            int blockOffset = block * 64;
            for (int word = 0; word < 16; word++) {
                int withinBlock = word * 4;
                int destination = word * LANES;
                for (int lane = 0; lane < LANES; lane++) {
                    int p = lane * 1024 + blockOffset + withinBlock;
                    messages[destination + lane] = (chunks[p] & 0xff)
                            | ((chunks[p + 1] & 0xff) << 8)
                            | ((chunks[p + 2] & 0xff) << 16)
                            | (chunks[p + 3] << 24);
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
            IntVector v8 = IntVector.broadcast(S, 0x6A09E667);
            IntVector v9 = IntVector.broadcast(S, 0xBB67AE85);
            IntVector v10 = IntVector.broadcast(S, 0x3C6EF372);
            IntVector v11 = IntVector.broadcast(S, 0xA54FF53A);
            IntVector v12 = IntVector.broadcast(S, block);
            IntVector v13 = IntVector.broadcast(S, 0);
            IntVector v14 = IntVector.broadcast(S, 64);
            IntVector v15 = IntVector.broadcast(S, 0);
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
        return cv0.lane(0) ^ cv7.lane(LANES - 1);
    }

    /**
     * Register-pressure discriminator: {@link #wideChunkLoop()} with the eight
     * chaining vectors held in a primitive array between blocks instead of as
     * live vector locals.
     *
     * <p>x86-64 has 16 architectural vector registers regardless of width, so
     * widening does not add registers — it only doubles the bytes moved by each
     * spill. The full loop keeps 16 state + 8 chaining + 2 message vectors live,
     * comfortably over 16. If dropping the eight chaining vectors makes the wide
     * loop faster, spill traffic is the reason width does not pay here.
     */
    @Benchmark
    public int wideChunkLoopLowLive() {
        System.arraycopy(initialState, 0, cvScratch, 0, 8 * LANES);
        for (int block = 0; block < 16; block++) {
            int blockOffset = block * 64;
            for (int word = 0; word < 16; word++) {
                int withinBlock = word * 4;
                int destination = word * LANES;
                for (int lane = 0; lane < LANES; lane++) {
                    int p = lane * 1024 + blockOffset + withinBlock;
                    messages[destination + lane] = (chunks[p] & 0xff)
                            | ((chunks[p + 1] & 0xff) << 8)
                            | ((chunks[p + 2] & 0xff) << 16)
                            | (chunks[p + 3] << 24);
                }
            }
            IntVector v0 = IntVector.fromArray(S, cvScratch, 0);
            IntVector v1 = IntVector.fromArray(S, cvScratch, LANES);
            IntVector v2 = IntVector.fromArray(S, cvScratch, 2 * LANES);
            IntVector v3 = IntVector.fromArray(S, cvScratch, 3 * LANES);
            IntVector v4 = IntVector.fromArray(S, cvScratch, 4 * LANES);
            IntVector v5 = IntVector.fromArray(S, cvScratch, 5 * LANES);
            IntVector v6 = IntVector.fromArray(S, cvScratch, 6 * LANES);
            IntVector v7 = IntVector.fromArray(S, cvScratch, 7 * LANES);
            IntVector v8 = IntVector.broadcast(S, 0x6A09E667);
            IntVector v9 = IntVector.broadcast(S, 0xBB67AE85);
            IntVector v10 = IntVector.broadcast(S, 0x3C6EF372);
            IntVector v11 = IntVector.broadcast(S, 0xA54FF53A);
            IntVector v12 = IntVector.broadcast(S, block);
            IntVector v13 = IntVector.broadcast(S, 0);
            IntVector v14 = IntVector.broadcast(S, 64);
            IntVector v15 = IntVector.broadcast(S, 0);
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
            v0.lanewise(VectorOperators.XOR, v8).intoArray(cvScratch, 0);
            v1.lanewise(VectorOperators.XOR, v9).intoArray(cvScratch, LANES);
            v2.lanewise(VectorOperators.XOR, v10).intoArray(cvScratch, 2 * LANES);
            v3.lanewise(VectorOperators.XOR, v11).intoArray(cvScratch, 3 * LANES);
            v4.lanewise(VectorOperators.XOR, v12).intoArray(cvScratch, 4 * LANES);
            v5.lanewise(VectorOperators.XOR, v13).intoArray(cvScratch, 5 * LANES);
            v6.lanewise(VectorOperators.XOR, v14).intoArray(cvScratch, 6 * LANES);
            v7.lanewise(VectorOperators.XOR, v15).intoArray(cvScratch, 7 * LANES);
        }
        return cvScratch[0] ^ cvScratch[8 * LANES - 1];
    }
}
