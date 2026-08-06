# E011 — allocation-first Vector API recovery plan

Date: 2026-08-06
Status: rungs 1–4 implemented and measured; no hashing dispatch changed

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
