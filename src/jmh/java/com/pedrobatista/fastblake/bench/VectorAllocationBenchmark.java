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

/** Allocation-first ladder for recovering four-chunk Vector API compression. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class VectorAllocationBenchmark {

    private static final VectorSpecies<Integer> S = IntVector.SPECIES_128;
    private static final int REPETITIONS = 256;

    private final byte[] chunks = new byte[4 * 1024];
    private final int[] messages = new int[64];
    private final int[] initialState = new int[64];

    public VectorAllocationBenchmark() {
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = (byte) (i * 131 + 17);
        }
        for (int word = 0; word < 16; word++) {
            for (int lane = 0; lane < 4; lane++) {
                messages[word * 4 + lane] = word * 0x9e3779b9 + lane * 0x7f4a7c15;
                initialState[word * 4 + lane] = word * 0x6a09e667 + lane;
            }
        }
    }

    @Benchmark
    public int transposeFourChunksToScratch() {
        int checksum = 0;
        for (int word = 0; word < 16; word++) {
            int withinBlock = word * 4;
            int destination = word * 4;
            for (int lane = 0; lane < 4; lane++) {
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

    @Benchmark
    public int scheduledLoadDependencyChain() {
        IntVector value = IntVector.fromArray(S, messages, 0);
        for (int i = 0; i < REPETITIONS; i++) {
            IntVector message = IntVector.fromArray(S, messages, (i & 15) * 4);
            value = value.add(message)
                    .lanewise(VectorOperators.XOR, message)
                    .lanewise(VectorOperators.ROR, 7);
        }
        return value.lane(0);
    }

    @Benchmark
    public int oneBlakeG() {
        IntVector a = IntVector.fromArray(S, initialState, 0);
        IntVector b = IntVector.fromArray(S, initialState, 16);
        IntVector c = IntVector.fromArray(S, initialState, 32);
        IntVector d = IntVector.fromArray(S, initialState, 48);
        for (int i = 0; i < REPETITIONS; i++) {
            IntVector mx = IntVector.fromArray(S, messages, (i & 15) * 4);
            IntVector my = IntVector.fromArray(S, messages, ((i + 1) & 15) * 4);
            a = a.add(b).add(mx);
            d = d.lanewise(VectorOperators.XOR, a).lanewise(VectorOperators.ROR, 16);
            c = c.add(d);
            b = b.lanewise(VectorOperators.XOR, c).lanewise(VectorOperators.ROR, 12);
            a = a.add(b).add(my);
            d = d.lanewise(VectorOperators.XOR, a).lanewise(VectorOperators.ROR, 8);
            c = c.add(d);
            b = b.lanewise(VectorOperators.XOR, c).lanewise(VectorOperators.ROR, 7);
        }
        return a.lane(0) ^ b.lane(0) ^ c.lane(0) ^ d.lane(0);
    }

    @Benchmark
    public int oneRoundFromScratch() {
        IntVector v0 = IntVector.fromArray(S, initialState, 0);
        IntVector v1 = IntVector.fromArray(S, initialState, 4);
        IntVector v2 = IntVector.fromArray(S, initialState, 8);
        IntVector v3 = IntVector.fromArray(S, initialState, 12);
        IntVector v4 = IntVector.fromArray(S, initialState, 16);
        IntVector v5 = IntVector.fromArray(S, initialState, 20);
        IntVector v6 = IntVector.fromArray(S, initialState, 24);
        IntVector v7 = IntVector.fromArray(S, initialState, 28);
        IntVector v8 = IntVector.fromArray(S, initialState, 32);
        IntVector v9 = IntVector.fromArray(S, initialState, 36);
        IntVector v10 = IntVector.fromArray(S, initialState, 40);
        IntVector v11 = IntVector.fromArray(S, initialState, 44);
        IntVector v12 = IntVector.fromArray(S, initialState, 48);
        IntVector v13 = IntVector.fromArray(S, initialState, 52);
        IntVector v14 = IntVector.fromArray(S, initialState, 56);
        IntVector v15 = IntVector.fromArray(S, initialState, 60);

        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            IntVector mx = IntVector.fromArray(S, messages, 0);
            IntVector my = IntVector.fromArray(S, messages, 4);
            v0 = v0.add(v4).add(mx); v12 = v12.lanewise(VectorOperators.XOR, v0).lanewise(VectorOperators.ROR, 16);
            v8 = v8.add(v12); v4 = v4.lanewise(VectorOperators.XOR, v8).lanewise(VectorOperators.ROR, 12);
            v0 = v0.add(v4).add(my); v12 = v12.lanewise(VectorOperators.XOR, v0).lanewise(VectorOperators.ROR, 8);
            v8 = v8.add(v12); v4 = v4.lanewise(VectorOperators.XOR, v8).lanewise(VectorOperators.ROR, 7);

            mx = IntVector.fromArray(S, messages, 8); my = IntVector.fromArray(S, messages, 12);
            v1 = v1.add(v5).add(mx); v13 = v13.lanewise(VectorOperators.XOR, v1).lanewise(VectorOperators.ROR, 16);
            v9 = v9.add(v13); v5 = v5.lanewise(VectorOperators.XOR, v9).lanewise(VectorOperators.ROR, 12);
            v1 = v1.add(v5).add(my); v13 = v13.lanewise(VectorOperators.XOR, v1).lanewise(VectorOperators.ROR, 8);
            v9 = v9.add(v13); v5 = v5.lanewise(VectorOperators.XOR, v9).lanewise(VectorOperators.ROR, 7);

            mx = IntVector.fromArray(S, messages, 16); my = IntVector.fromArray(S, messages, 20);
            v2 = v2.add(v6).add(mx); v14 = v14.lanewise(VectorOperators.XOR, v2).lanewise(VectorOperators.ROR, 16);
            v10 = v10.add(v14); v6 = v6.lanewise(VectorOperators.XOR, v10).lanewise(VectorOperators.ROR, 12);
            v2 = v2.add(v6).add(my); v14 = v14.lanewise(VectorOperators.XOR, v2).lanewise(VectorOperators.ROR, 8);
            v10 = v10.add(v14); v6 = v6.lanewise(VectorOperators.XOR, v10).lanewise(VectorOperators.ROR, 7);

            mx = IntVector.fromArray(S, messages, 24); my = IntVector.fromArray(S, messages, 28);
            v3 = v3.add(v7).add(mx); v15 = v15.lanewise(VectorOperators.XOR, v3).lanewise(VectorOperators.ROR, 16);
            v11 = v11.add(v15); v7 = v7.lanewise(VectorOperators.XOR, v11).lanewise(VectorOperators.ROR, 12);
            v3 = v3.add(v7).add(my); v15 = v15.lanewise(VectorOperators.XOR, v3).lanewise(VectorOperators.ROR, 8);
            v11 = v11.add(v15); v7 = v7.lanewise(VectorOperators.XOR, v11).lanewise(VectorOperators.ROR, 7);

            mx = IntVector.fromArray(S, messages, 32); my = IntVector.fromArray(S, messages, 36);
            v0 = v0.add(v5).add(mx); v15 = v15.lanewise(VectorOperators.XOR, v0).lanewise(VectorOperators.ROR, 16);
            v10 = v10.add(v15); v5 = v5.lanewise(VectorOperators.XOR, v10).lanewise(VectorOperators.ROR, 12);
            v0 = v0.add(v5).add(my); v15 = v15.lanewise(VectorOperators.XOR, v0).lanewise(VectorOperators.ROR, 8);
            v10 = v10.add(v15); v5 = v5.lanewise(VectorOperators.XOR, v10).lanewise(VectorOperators.ROR, 7);

            mx = IntVector.fromArray(S, messages, 40); my = IntVector.fromArray(S, messages, 44);
            v1 = v1.add(v6).add(mx); v12 = v12.lanewise(VectorOperators.XOR, v1).lanewise(VectorOperators.ROR, 16);
            v11 = v11.add(v12); v6 = v6.lanewise(VectorOperators.XOR, v11).lanewise(VectorOperators.ROR, 12);
            v1 = v1.add(v6).add(my); v12 = v12.lanewise(VectorOperators.XOR, v1).lanewise(VectorOperators.ROR, 8);
            v11 = v11.add(v12); v6 = v6.lanewise(VectorOperators.XOR, v11).lanewise(VectorOperators.ROR, 7);

            mx = IntVector.fromArray(S, messages, 48); my = IntVector.fromArray(S, messages, 52);
            v2 = v2.add(v7).add(mx); v13 = v13.lanewise(VectorOperators.XOR, v2).lanewise(VectorOperators.ROR, 16);
            v8 = v8.add(v13); v7 = v7.lanewise(VectorOperators.XOR, v8).lanewise(VectorOperators.ROR, 12);
            v2 = v2.add(v7).add(my); v13 = v13.lanewise(VectorOperators.XOR, v2).lanewise(VectorOperators.ROR, 8);
            v8 = v8.add(v13); v7 = v7.lanewise(VectorOperators.XOR, v8).lanewise(VectorOperators.ROR, 7);

            mx = IntVector.fromArray(S, messages, 56); my = IntVector.fromArray(S, messages, 60);
            v3 = v3.add(v4).add(mx); v14 = v14.lanewise(VectorOperators.XOR, v3).lanewise(VectorOperators.ROR, 16);
            v9 = v9.add(v14); v4 = v4.lanewise(VectorOperators.XOR, v9).lanewise(VectorOperators.ROR, 12);
            v3 = v3.add(v4).add(my); v14 = v14.lanewise(VectorOperators.XOR, v3).lanewise(VectorOperators.ROR, 8);
            v9 = v9.add(v14); v4 = v4.lanewise(VectorOperators.XOR, v9).lanewise(VectorOperators.ROR, 7);
        }
        return v0.lane(0) ^ v1.lane(0) ^ v2.lane(0) ^ v3.lane(0)
                ^ v4.lane(0) ^ v5.lane(0) ^ v6.lane(0) ^ v7.lane(0)
                ^ v8.lane(0) ^ v9.lane(0) ^ v10.lane(0) ^ v11.lane(0)
                ^ v12.lane(0) ^ v13.lane(0) ^ v14.lane(0) ^ v15.lane(0);
    }
}
