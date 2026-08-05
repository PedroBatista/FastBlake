# FastBlake performance experiments

This is the permanent experiment ledger. Add an entry for every performance
change, including failed ideas. Never replace an old result: later experiments
may combine details from several attempts.

## Measurement protocol

- Correctness gate: `./gradlew test`
- Main workload: 8 MiB (`size=8388608`)
- Shapes: one-shot, reused instance, and 4 KiB streaming
- Quick comparison command:
  `./gradlew jmh -P'jmh.args=-p impl=commons,rust,java-cpu -p size=8388608 -f1 -wi 3 -i 3'`
- Report MiB/s, not only ns/op, and retain the JMH fork/warmup/iteration count.
- Compare on the same machine and JVM. Thermal state and background load can
  move short runs, so treat differences below roughly 3% as inconclusive until
  confirmed by a longer run.
- Record the correctness result, decision, and explanation with every entry.

## Environment

- Machine: Apple M5, 10 cores
- OS: macOS 26.6 (25G72)
- JVM: Temurin OpenJDK 25.0.4+7-LTS
- Benchmark: JMH 1.37, one thread
- Native reference: Rust `blake3` 1.8.5, SIMD, Rayon disabled

## E000 — external baselines before FastBlake

Date: 2026-08-05

Long run: 2 forks, 5 warmup and 5 measurement iterations of one second.

| 8 MiB shape | Commons Codec | Rust |
|---|---:|---:|
| one-shot | 544 MiB/s | 2420 MiB/s |
| reused | 538 MiB/s | 2402 MiB/s |
| streaming 4 KiB | 534 MiB/s | 2240 MiB/s |

Comment: Commons is the scalar Java floor. Rust shows the available headroom
from chunk-parallel SIMD. All measurements are single-threaded.

## E001 — conformant scalar FastBlake

Date: 2026-08-05

Change: complete scalar BLAKE3, flat CV stack, delayed final chunk, reusable hot
scratch, and allocation-free compression of completed chunks.

Quick run: 1 fork, 3 warmup and 3 measurement iterations of one second.

| 8 MiB shape | Commons Codec | Rust | FastBlake scalar | vs Commons |
|---|---:|---:|---:|---:|
| one-shot | 552 MiB/s | 2505 MiB/s | 679 MiB/s | 1.23x |
| reused | 543 MiB/s | 2476 MiB/s | 673 MiB/s | 1.24x |
| streaming 4 KiB | 539 MiB/s | 2297 MiB/s | 661 MiB/s | 1.23x |

Correctness: full `./gradlew test` passed against official vectors in hash,
keyed-hash, and derive-key modes, including 131-byte XOF output and incremental
boundary cases.

Decision: keep as the scalar baseline and fallback.

Comment: this is already consistently faster than Commons but remains about
3.7x behind Rust at 8 MiB. Independent-chunk SIMD is the next experiment.

## E002 — preferred-width Vector API chunk compression

Date: 2026-08-06

Change: process one preferred-width batch of independent complete chunks with
the Vector API. Each lane owns one chunk. Byte-to-lane packing and CV tree
merging remain scalar. The rightmost chunk is kept for scalar root/XOF handling.

Correctness: full `./gradlew test` passed.

Harness note: the first measurement attempt produced no FastBlake samples
because JMH child JVMs did not inherit `--add-modules=jdk.incubator.vector` from
Gradle. Added the module explicitly through `OptionsBuilder.jvmArgsAppend` and
restarted the measurement. This was a harness configuration failure, not an
algorithm failure.

Quick run: 1 fork, 3 warmup and 3 measurement iterations of one second.

| 8 MiB shape | Commons Codec | Rust | FastBlake E002 | E001 scalar | E002 vs E001 |
|---|---:|---:|---:|---:|---:|
| one-shot | 547 MiB/s | 2479 MiB/s | 108 MiB/s | 679 MiB/s | 0.16x |
| reused | 552 MiB/s | 2443 MiB/s | 107 MiB/s | 673 MiB/s | 0.16x |
| streaming 4 KiB | 535 MiB/s | 2275 MiB/s | 663 MiB/s | 661 MiB/s | 1.00x |

Decision: reject for default dispatch. Preserve the kernel behind
`-Dfastblake.experimental.vector=true` so its loading/packing ideas can be
profiled or combined with later work. Default operation remains E001 scalar.

Comment: the 4 KiB shape did not enter the vector branch and confirms that the
scalar result is stable. The vector path stores immutable vectors in
`IntVector[]` arrays and updates them through a helper. Those values escape
through array stores, which prevents the JIT from treating the whole kernel as
straight-line register state. It also scalar-packs every message word. The next
vector version must use named local vectors, fully unrolled rounds, and loads
that transpose contiguous words without a scalar staging array. Batched parent
merging should follow only after chunk compression itself wins.

Default-dispatch confirmation after rejecting E002:

| 8 MiB shape | FastBlake default | E001 scalar | assessment |
|---|---:|---:|---|
| one-shot | 670 MiB/s | 679 MiB/s | within quick-run noise |
| reused | 673 MiB/s | 673 MiB/s | unchanged |
| streaming 4 KiB | 661 MiB/s | 661 MiB/s | unchanged |

Correctness after dispatch change: full `./gradlew test` passed. The default
path has no material regression. These confirmation figures came from a
FastBlake-only run using the same fork/warmup/measurement counts; the immediately
preceding full comparison supplied the contemporaneous Commons and Rust values.
