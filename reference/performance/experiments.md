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

## E003 — named row vectors with intra-block SIMD

Date: 2026-08-06

Change: represent the 16 compression words as four named 128-bit row vectors.
Column G functions operate lane-wise; diagonal G functions rotate lanes with
fixed shuffles. Message schedules use indexed Vector API loads. Unlike E002,
there are no vector arrays and no cross-chunk byte packing. Enable with
`-Dfastblake.experimental.blockVector=true`.

Correctness: full `./gradlew test` passed with the property enabled, covering
all modes, official vectors, XOF, and incremental boundary shapes.

Quick run: 1 fork, 3 warmup and 3 measurement iterations of one second.

| 8 MiB shape | Commons Codec | Rust | FastBlake E003 | E001 scalar | E003 vs E001 |
|---|---:|---:|---:|---:|---:|
| one-shot | 551 MiB/s | 2501 MiB/s | 64 MiB/s | 679 MiB/s | 0.09x |
| reused | 542 MiB/s | 2441 MiB/s | 63 MiB/s | 673 MiB/s | 0.09x |
| streaming 4 KiB | 536 MiB/s | 2282 MiB/s | 63 MiB/s | 661 MiB/s | 0.10x |

Decision: reject for default dispatch. Retain separately from E002 because it
tests a different SIMD layout and is useful negative evidence.

Comment: BLAKE3's G function has dependencies between its four scalar words;
putting the four columns into vector lanes exposes only four-way work within a
single block and requires lane shuffles for every diagonal half-round. Worse,
the permuted message schedule requires indexed gathers. On this Apple M5/JDK 25
combination those operations overwhelm the saved scalar arithmetic. This is
slower than E002 and about 10.5x slower than E001.

Rule learned: do not vectorize columns inside one BLAKE3 block on this JVM.
Return to independent chunks per lane, but keep every lane-vector as a named
local, load four contiguous words from each chunk, and transpose 4x4 registers.
No `IntVector[]`, indexed gathers, scalar word packing, or helper-owned mutable
state may appear in the compression hot path. Inspect generated assembly before
promoting any subsequent Vector API result.

## E004 — four chunks, transpose loads, fully expanded named vectors

Date: 2026-08-06

Change: hash four independent chunks in 128-bit lanes. Each 64-byte block uses
four endian-aware contiguous `IntVector.fromMemorySegment` loads per 16-byte
row, followed by 4x4 register transposes. Compression uses 16 named state
vectors, 16 named message vectors, and fully expanded G operations. There are
no vector arrays, indexed gathers, or scalar message staging. Enable with
`-Dfastblake.experimental.chunkVector4=true`.

Correctness: full `./gradlew test` passed with the property enabled.

Quick run: 1 fork, 3 warmup and 3 measurement iterations of one second.

| 8 MiB shape | Commons Codec | Rust | FastBlake E004 | E001 scalar |
|---|---:|---:|---:|---:|
| one-shot | 552 MiB/s | 2473 MiB/s | 72 MiB/s | 679 MiB/s |
| reused | 554 MiB/s | 2452 MiB/s | 41 MiB/s unstable | 673 MiB/s |
| streaming 4 KiB | 536 MiB/s | 2272 MiB/s | 671 MiB/s | 661 MiB/s |

Decision: reject for default dispatch and retain as opt-in evidence. The reused
result is not a trustworthy steady-state score: iterations improved from 264
ms to 112 ms as compilation occurred during measurement. Even the compiled
level is far below scalar, so a longer confirmation run cannot change the
decision.

Comment: E004 fixed E002's array escape and scalar packing problems, but made
all 16 message vectors live alongside 16 state vectors plus transpose
temporaries. That exceeds the usable AArch64 SIMD register budget and strongly
suggests heavy spilling. The approximately 1,000-line hot method also compiles
late. Correct SIMD structure alone is insufficient if its Java IR and live set
are hostile to C2.

Rule learned: limit the live vector set. A follow-up should load/transpose only
the message quartet needed by a small group of G operations, or split rounds
into compiler-sized methods only after verifying that vector values do not
escape. Use compilation logs and generated assembly to confirm C2 compilation,
spill traffic, and whether `fromMemorySegment` plus two-source rearranges lower
to the expected AArch64 instructions before writing another full kernel.

## E005 — low-live-set cross-chunk SIMD

Date: 2026-08-06

Change: retain E004's four independent chunk lanes and named state vectors, but
keep only two message vectors live. Each G reloads and transposes the two words
it needs through a separate `loadWord` method. This lowers the live vector set
from more than 34 to roughly 20 at the cost of repeated L1 loads and shuffles.
Enable with `-Dfastblake.experimental.lowLiveVector=true`.

Correctness: full `./gradlew test` passed with the property enabled.

Quick run: 1 fork, 3 warmup and 3 measurement iterations of one second.

| 8 MiB shape | Commons Codec | Rust | FastBlake E005 | E001 scalar |
|---|---:|---:|---:|---:|
| one-shot | 553 MiB/s | 2502 MiB/s | 36 MiB/s | 679 MiB/s |
| reused | 543 MiB/s | 2453 MiB/s | 37 MiB/s | 673 MiB/s |
| streaming 4 KiB | 538 MiB/s | 2275 MiB/s | 668 MiB/s | 661 MiB/s |

Decision: reject for default dispatch; preserve behind its property as the
low-live-set endpoint of the design space.

Comment: reducing register pressure alone is not enough. E005 materializes 112
message vectors per block instead of 16, and each materialization performs four
loads plus transpose shuffles. It is roughly half E004's compiled one-shot
throughput and about 18x slower than scalar. Streaming remains scalar and again
confirms default-path stability.

Rule learned: neither keeping all 16 transposed message vectors live nor
retransposing every word is viable. The next plausible compromise is a
round-local message cache whose lifetime ends after one round, combined with a
smaller round method to control compilation size. That retains 16
materializations per round (the minimum for the permuted schedule) but prevents
message vectors from remaining live across all seven rounds. Compiler logs and
assembly inspection should precede its full 8 MiB measurement.

## E006 — round-local message cache

Date: 2026-08-06

Change: four independent chunks remain in lanes. Each of the seven rounds loads
the four rows from all four chunks, transposes them into 16 message vectors,
executes that round, and exits a lexical scope intended to end the message
cache's lifetime. This performs 16 source-vector loads per round rather than
E005's 64. Enable with `-Dfastblake.experimental.roundCacheVector=true`.

Correctness: full `./gradlew test` passed with the property enabled.

Quick run: 1 fork, 3 warmup and 3 measurement iterations of one second.

| 8 MiB shape | Commons Codec | Rust | FastBlake E006 | E001 scalar |
|---|---:|---:|---:|---:|
| one-shot | 552 MiB/s | 2496 MiB/s | 21 MiB/s | 679 MiB/s |
| reused | 542 MiB/s | 2453 MiB/s | 20 MiB/s | 673 MiB/s |
| streaming 4 KiB | 535 MiB/s | 2277 MiB/s | 670 MiB/s | 661 MiB/s |

Decision: reject for default dispatch and retain as opt-in negative evidence.

Comment: source-level lexical scopes did not produce the hoped-for machine
register lifetime. E006 still exposes a large IR/live set during each round and
adds seven full message load/transpose passes per block. It is slower than both
E004's single block-wide cache and E005's two-message approach.

Rule learned: stop iterating on cache lifetime by Java source structure alone.
E004 through E006 cover the relevant caching continuum and all regress. Before
another Vector API kernel, capture C2 compilation logs and generated machine
code for a minimal compression microbenchmark. Determine whether vector
rotates, two-source rearranges, endian MemorySegment loads, and vector values
actually lower to NEON instructions without unexpected allocation or runtime
stubs. The next implementation decision must be driven by that evidence.

## E007 — Vector API primitive and C2 lowering probe

Date: 2026-08-06

Change: add `VectorPrimitiveBenchmark`, isolating 128-bit rotate-right,
two-source rearrange, endian MemorySegment loads, direct heap byte-vector loads,
and a BLAKE-like add/XOR/rotate dependency chain. Each benchmark performs 256
repetitions. This is diagnostic infrastructure, not a hashing implementation.

Command:
`./gradlew jmh -P'jmh.args=VectorPrimitiveBenchmark -f1 -wi 5 -i 5'`

| primitive, 256 repetitions | total | approximate per repetition |
|---|---:|---:|
| four scalar rotate chains | 61.201 ns | 0.239 ns for four scalar rotates |
| one four-lane vector rotate chain | 178.891 ns | 0.699 ns |
| vector BLAKE add/XOR/rotate | 393.157 ns | 1.536 ns |
| two-source rearrange chain (two per repetition) | 409.100 ns | 0.799 ns/rearrange |
| endian MemorySegment vector load | 1672.477 ns | 6.533 ns/load |
| heap ByteVector load + reinterpret | 65.696 ns | 0.257 ns/load |

Compilation evidence:

- `-XX:+LogCompilation -XX:+PrintIntrinsics` recorded the focused
  `vectorBlakeMix` run in `/private/tmp/fastblake-e007-hotspot.xml`.
- `vectorBlakeMix` reached C2 level 4 (`compile_id=934`) after about 0.137 s;
  this is not an interpreter or C1-only result.
- VectorSupport binary and shift paths were recognized as intrinsics and the
  vector wrappers were force-inlined by their annotations.
- This Temurin distribution has no working `hsdis` library. `PrintAssembly`
  emits AArch64 instruction words but not mnemonic disassembly, so exact
  instruction-by-instruction confirmation was unavailable locally.

Decision: keep the diagnostic benchmark. Do not use
`IntVector.fromMemorySegment(..., LITTLE_ENDIAN)` for heap `byte[]` BLAKE3
input on this runtime. Use `ByteVector.fromArray(...).reinterpretAsInts()` on
little-endian AArch64; it is approximately 25.4x faster in isolation.

Comment: the Vector API is intrinsified and C2-compiled, but that does not make
every operation cheap. Four scalar rotate chains are about 2.9x faster than one
four-lane vector rotate dependency chain. AArch64 NEON lacks a single general
vector rotate-right instruction, so shifts/inserts are expected. Two-source
rearranges also have meaningful latency. Most importantly, the endian
MemorySegment load used by E004-E006 is catastrophically expensive relative to
a direct heap byte load and reinterpret.

Rule learned: the next cross-chunk experiment should first change only the load
mechanism in the best prior cross-chunk layout (E004), using 128-bit
`ByteVector.fromArray` loads and native little-endian reinterpretation. Keep it
opt-in and architecture-guarded. This is now a measured, specific hypothesis;
do not redesign caching and loading simultaneously.
