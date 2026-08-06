# E011 — allocation-first Vector API recovery plan

Date: 2026-08-06
Status: rungs 1–7 implemented and measured; algorithm-correct kernel is opt-in

## Objective

Re-open four-chunk, 128-bit chunk-parallel SIMD without repeating E002–E008's
measurement error. A candidate advances only while its allocation remains
effectively zero in an isolated JMH fork. Throughput is secondary until that
gate passes.

## Proposed allocation fixes

1. Keep all vector state in 16 named `IntVector` locals. Never store vector
   wrappers in `IntVector[]`, fields, collections, lambdas, or polymorphic call
   sites.
2. Transpose each four-chunk message block once into a reusable primitive
   `int[64]` in word-major order. Load scheduled messages from this L1-resident
   scratch array; do not keep 16 message vectors live and do not repeatedly
   transpose source vectors.
3. Use fixed `IntVector.SPECIES_128`, constant message offsets, direct static
   code, and monomorphic call sites so C2 sees a constant intrinsic shape.
4. Keep compiler units small, but accept a helper boundary only after proving
   it neither makes vectors escape nor reintroduces allocation.
5. Test loop back-edges separately from straight-line expansion. E010 showed
   that box elimination is non-monotonic and can change with seemingly
   unrelated code shape.
6. Continue using direct heap byte-vector loads plus native little-endian
   reinterpretation only after the state kernel is allocation-free. E007 proved
   this loader is cheap in isolation; E010 proved that cheap loads cannot rescue
   an allocating compressor.

## Mandatory measurement ladder

Each rung runs alone in its own fork with `-prof gc`:

1. Primitive four-chunk transpose into reusable `int[64]` scratch.
2. One scheduled `IntVector` load and dependency chain.
3. One complete BLAKE3 G function.
4. One complete round: 16 named state vectors, messages loaded from scratch.
5. Seven rounds for one block.
6. Sixteen-block chunk loop.
7. Full four-chunk hashing kernel and scalar tree integration.

Stop at the first rung with algorithm-proportional allocation. Restructure that
rung before adding more code. Do not infer allocation from source or from a
successful C2 compilation.

Suggested command:

```bash
./gradlew jmh -P'jmh.args=VectorAllocationBenchmark.<rung> -f1 -wi 5 -i 5 -prof gc'
```

Acceptance for a full kernel:

- official vectors pass in every mode and incremental boundary shape;
- normalized allocation is fixed harness noise, not proportional to bytes;
- no GC occurs in the measured compression region;
- 8 MiB throughput beats the current E009 production baseline;
- results and rejected intermediate shapes are recorded in `experiments.md`.

## Initial implementation

`VectorAllocationBenchmark` implements the first four diagnostic rungs. It uses
reusable primitive scratch and named vector locals and deliberately contains no
hash dispatch. The next action is to measure each method separately, then add a
seven-round rung only if the one-round rung remains allocation-free.

## Initial results

Command:

```bash
./gradlew jmh -P'jmh.args=VectorAllocationBenchmark -f1 -wi 3 -i 3 -prof gc'
```

JMH launches a separate fork for each benchmark method.

| rung; 256 repetitions where applicable | time | normalized allocation | GC |
|---|---:|---:|---:|
| primitive four-chunk transpose | 26.449 ns | approximately 0.0001 B/op | 0 |
| scheduled vector load chain | 392.838 ns | 0.003 B/op | 0 |
| one BLAKE3 G chain | 1981.969 ns | 0.014 B/op | 0 |
| one full round, 16 named state vectors | 4743.136 ns | 0.033 B/op | 0 |

These tiny normalized figures are fixed profiler/JMH sampling noise; they are
not proportional to the number of vector operations and caused no collection.
All four rungs therefore pass the allocation gate. In particular, reading
scheduled messages from reusable primitive scratch does not by itself recreate
E004–E008's wrapper-allocation storm.

Decision: proceed to rung 5, seven BLAKE3 rounds for one block, retaining the
same named state and primitive message layout. Do not integrate with `FastBlake`
until the block and sixteen-block chunk rungs also pass independently.

## Rungs 5–7 and the allocation cliff

| shape | time | normalized allocation | GC |
|---|---:|---:|---:|
| four fully expanded rounds | 65.230 ns | ~0.001 B/op | 0 |
| six fully expanded rounds | 100.709 ns | 0.001 B/op | 0 |
| seven fully expanded rounds | 13985.551 ns | 43776.098 B/op | 67 collections/5 iterations |
| seven rounds through compact schedule loop | 114.963 ns | 0.001 B/op | 0 |
| four complete 1 KiB chunk lanes | 2092.938 ns | 0.015 B/op | 0 |

The allocation cliff is exact and non-gradual: six expanded rounds scalarize,
while adding the seventh makes C2 allocate 43,776 bytes per invocation. A
compact seven-round loop with dynamic schedule offsets is both allocation-free
and fast. This disproves the idea that a loop or dynamic schedule is inherently
fatal; on this JVM the smaller compiler graph is essential.

The four-chunk diagnostic processes 4096 bytes in 2.093 microseconds, about
1.87 GiB/s before real tree integration.

## Algorithm-correct kernel

`Blake3ChunkVectorScratch` applies the compact shape to real BLAKE3 flags,
counters, chaining values, messages, and CV extraction. Enable with:

```bash
-Dfastblake.experimental.scratchChunkVector=true
```

Correctness: the forced full official-vector suite passed in hash, keyed-hash,
derive-key, XOF, and incremental boundary modes.

Allocation gate, 8 MiB one-shot: 5554.711 B/op, or 0.00066 B/input byte, with
zero collections. This is fixed hasher/JMH setup and finalization noise rather
than algorithm-proportional allocation. For comparison, E008 allocated 1.33 GB
per operation.

Standard 8 MiB quick comparison:

| shape | Commons | Rust | E011 | E009 default |
|---|---:|---:|---:|---:|
| one-shot | 554 MiB/s | 2505 MiB/s | 1584 MiB/s | 894 MiB/s |
| reused | 546 MiB/s | 2479 MiB/s | 1569 MiB/s | 883 MiB/s |
| streaming 4 KiB | 540 MiB/s | 2301 MiB/s | 874 MiB/s | 874 MiB/s |

E011 is 1.77x E009 for contiguous large inputs and reaches about 63% of Rust's
single-threaded SIMD throughput. Streaming remains scalar because a 4096-byte
update cannot process four chunks while also retaining the rightmost chunk for
correct finalization.

Decision: retain opt-in pending a streaming batch buffer, longer confirmation,
and validation on at least one non-AArch64 JVM. The result is nevertheless the
first correct, allocation-free, end-to-end Java SIMD win in this project.
