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
- Allocation gate (mandatory, added by E010): measure bytes allocated per input
  byte, in a JVM running only that kernel, *before* recording any throughput
  number. Use `-prof gc` or `ThreadMXBean.getThreadAllocatedBytes`. A kernel
  allocating materially more than zero is not measuring the algorithm it claims
  to implement; discard its throughput figure instead of recording it as
  evidence. E002-E008 skipped this, so their causal performance conclusions
  require revalidation even where allocation was not subsequently measured.
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

> **E010 audit notice for E002–E008:** the experimental properties did reach
> the forked JVMs and these branches did execute. However, the cross-chunk
> kernels measured by E010 (E004–E008) allocated 159–255 heap bytes per input
> byte because C2 did not eliminate Vector API wrapper objects. Their
> throughput and correctness remain
> factual, but their explanations about register pressure, spilling, cache
> layout, or the viability of SIMD are not established. Read E010 before using
> any “rule learned” from these entries.

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

## E008 — E004 with direct heap byte-vector loads

Date: 2026-08-06

Change: preserve E004's four-chunk layout, block-wide 16-message cache, 4x4
transposes, named state vectors, and fully expanded rounds. Change only each
source load from `IntVector.fromMemorySegment(..., LITTLE_ENDIAN)` to
`ByteVector.fromArray(...).reinterpretAsInts()`. Enable with
`-Dfastblake.experimental.heapChunkVector=true`. Dispatch is guarded by native
little-endian byte order because reinterpretation uses native lane order.

Correctness: the full official-vector `./gradlew test` suite passed with the
property enabled, including hash, keyed-hash, derive-key, XOF, and incremental
boundary cases.

Quick run: 1 fork, 3 warmup and 3 measurement iterations of one second.

| 8 MiB shape | Commons Codec | Rust | FastBlake E008 | E001 scalar |
|---|---:|---:|---:|---:|
| one-shot | 560 MiB/s | 2504 MiB/s | 51 MiB/s reported; ~74 MiB/s after C2 transition | 679 MiB/s |
| reused | 547 MiB/s | 2479 MiB/s | 73 MiB/s | 673 MiB/s |
| streaming 4 KiB | 539 MiB/s | 2315 MiB/s | 696 MiB/s (scalar route) | 661 MiB/s |

Decision: reject for default dispatch and retain as an opt-in negative
experiment. The one-shot aggregate is distorted by compilation during the
measurement: its iterations were 250.1, 109.4, and 107.3 ms. The stable last
two iterations and the stable reused result both put the compiled kernel near
73--74 MiB/s, still roughly 9x slower than E001.

Comment: the isolated heap-load primitive remained fast in the same run
(65.854 ns per 256 loads versus 1655.460 ns for endian MemorySegment loads),
confirming a roughly 25.1x load-level improvement. That improvement does not
materially improve the complete kernel over E004's compiled result. Input
loading was a real pathology, but it was not the dominant remaining bottleneck:
the large vector live set, two-source transposes, vector rotate dependency
chains, method size, and C2 compilation behaviour still dominate. Streaming
4 KiB does not enter the four-chunk branch and provides a contemporaneous
scalar control.

Rule learned: keep direct heap ByteVector loads for any future cross-chunk
prototype, but retire E004's monolithic four-chunk Java shape as an optimization
direction. Do not promote it or spend another experiment reshuffling its cache.
A credible next step needs a structurally different lowering strategy (for
example generated/native SIMD, or a much smaller JVM kernel proven by compiler
output), while the scalar implementation remains the production default.

## E009 — named-local, fully unrolled scalar compression

Date: 2026-08-06

Change: replace the compression loop's reusable `int[16]` state mutations,
schedule-table traversal, and indexed `g` helper with 16 named integer locals
and seven explicitly expanded rounds. Message indexes and state positions are
compile-time constants. Byte loading, chunk handling, tree reduction, and API
semantics are unchanged.

Correctness: a forced full rebuild and official-vector suite passed in every
mode and incremental boundary case with the new compressor selected.

Quick run: 1 fork, 3 warmup and 3 measurement iterations of one second.

| 8 MiB shape | Commons Codec | Rust | FastBlake E009 | E001 scalar | E009 vs E001 |
|---|---:|---:|---:|---:|---:|
| one-shot | 555 MiB/s | 2507 MiB/s | 894 MiB/s | 679 MiB/s | 1.32x |
| reused | 547 MiB/s | 2481 MiB/s | 883 MiB/s | 673 MiB/s | 1.31x |
| streaming 4 KiB | 540 MiB/s | 2313 MiB/s | 874 MiB/s | 661 MiB/s | 1.32x |

Decision: promote E009 to the production default. Retain the former loop behind
`-Dfastblake.experimental.legacyScalar=true` as an executable comparison and
rollback control.

Default-dispatch confirmation after promotion, using the same quick-run counts
and FastBlake only: 885 MiB/s one-shot, 884 MiB/s reused, and 881 MiB/s
streaming 4 KiB. The small movement from the full comparison is normal
run-to-run noise and confirms that the property-free path selects E009.

Comment: the improvement is large, stable across iterations, and consistent
across all three call shapes. Named locals allow C2 to keep the compression
state in registers and expose independent G operations to the out-of-order
core. Removing state-array indexing and runtime schedule traversal matters much
more on this target than the attempted Vector API layouts. E009 is 1.61--1.62x
Commons, though Rust SIMD remains about 2.8x faster than FastBlake.

Rule learned: establish the best scalar machine-code shape before adding SIMD.
Future vector work should borrow E009's constant schedules and compact state
lifetime, and must beat 894/883/874 MiB/s rather than the obsolete E001
baseline. The next scalar experiment should isolate full-block word loading;
the guide's cached little-endian VarHandle is a plausible single-variable test.

## E010 — vector allocation invalidates E004-E008 throughput interpretation

Date: 2026-08-06

Type: diagnostic investigation. No production code changed. Full record with all
raw numbers, commands, and reasoning: `e010-vector-allocation-diagnosis.md`.

Change: measured bytes allocated per input byte for the E004–E008 cross-chunk
kernels — the check required by the guide (§7.1, §10.7) that the earlier
experiments skipped. E002 and E003 were not included in this allocation run;
their causal interpretations remain unverified rather than disproved.

| kernel | throughput | allocated per input byte |
|---|---:|---:|
| E009 scalar (default) | 827 MiB/s | 0.00 B |
| E008 `heapChunkVector` | 32 MiB/s | 158.76 B |
| E004 `chunkVector4` | 31 MiB/s | 158.90 B |
| E006 `roundCacheVector` | 21 MiB/s | 212.99 B |
| E005 `lowLiveVector` | 22 MiB/s | 254.61 B |

Roughly 4 GB of garbage to hash 24 MB. Cross-checked with `-Xlog:gc`: 0 young
collections for scalar, 34 for `heapChunkVector` over the same work.

Finding: C2 is not eliminating `IntVector` boxes in these kernels. Their
20-32 MiB/s scores are dominated by wrapper allocation and a GC storm, so they
are not evidence about the attainable performance of allocation-free SIMD.
Allocation alone does not prove whether individual intrinsified operations
also executed NEON instructions; mnemonic assembly was unavailable. Therefore
the earlier machine-code and register-pressure explanations are unproven.

Focused JMH audit after this document was added: `heapChunkVector`, 8 MiB
one-shot, one isolated fork with `-prof gc`, reported 32 MiB/s and
1,330,120,188.8 B/op (158.56 allocated bytes/input byte), with five collections
in the measurement iteration. `JAVA_TOOL_OPTIONS` was printed by both the
Gradle benchmark process and the JMH child JVM, proving that the property
reached the fork; the allocation signature and throughput match the standalone
E010 driver and prove the experimental dispatch ran.

Two competing hypotheses were tested and discarded. `DontCompileHugeMethods`
blocks JIT compilation above 8000 bytecodes and E006's kernel is 11,433 bytes,
but `-XX:-DontCompileHugeMethods` moves it only from 21.0 to 20.7 MiB/s. C1 does
refuse these methods outright ("COMPILE SKIPPED: out of virtual registers in LIR
generator"), but `-XX:-TieredCompilation` changes nothing (32.6 to 32.4 MiB/s for
E008). Neither is the cause.

Supporting result: box elimination is fragile and non-monotonic in code size. In
isolated JVMs, a 128-bit `add` chain allocates 48 B/iter at 4.911 ns, while
`add + xor + ROR` allocates nothing at 1.770 ns. The simplest loop boxes; adding
work stops the boxing. It cannot be predicted from source, only measured — and
the same bytecode gives different answers when other code shares the JVM, so
each kernel must be isolated.

Also measured: the cached little-endian `VarHandle` loader nominated by E009's
rule learned is 3.51x faster than the current manual byte shifts over 8 MiB
(0.835 ms to 0.238 ms), worth about 6.7% end-to-end on its own.

Decision: do not change any dispatch. Add the allocation gate to the measurement
protocol above. Prior entries stand as written; this entry corrects their
interpretation without rewriting them.

Comment: the practical damage was in the rules learned. E006 and E008 concluded
that the Vector API layouts were exhausted and that a "structurally different
lowering strategy" was needed, steering work away from chunk-parallel SIMD. That
is the single largest known throughput opportunity — the reference Rust
implementation reaches 2500 MiB/s with exactly the 4-lane NEON structure these
experiments were attempting. The direction was right; the kernels were simply
running their vector branches but not as the allocation-free vector kernels the
experiments intended to test.

Rule learned: a throughput number from a kernel with unmeasured allocation is not
a result. When re-opening chunk-parallel SIMD, build the kernel incrementally and
check bytes/byte after every addition, stopping at the first non-zero reading
rather than writing a complete kernel and measuring at the end. The untried
structural variant to sample first is transposing message words once per block
into a scratch `int[64]` and keeping only the 16 state vectors as vector values;
E004-E008 all kept messages as live vector values and none tried reading them
back from an array.

## E011 — allocation-first vector recovery, rungs 1–4

Date: 2026-08-06

Change: add an isolated `VectorAllocationBenchmark` ladder and the detailed
plan in `e011-allocation-first-vector-plan.md`. Messages use reusable primitive
`int[64]` word-major scratch; vector state uses 16 named locals. No production
dispatch changed.

Command: one fork per benchmark, 3 warmup and 3 measurement iterations, GC
profiler enabled:
`./gradlew jmh -P'jmh.args=VectorAllocationBenchmark -f1 -wi 3 -i 3 -prof gc'`.

| diagnostic rung | time | allocation | collections |
|---|---:|---:|---:|
| primitive four-chunk transpose | 26.449 ns | ~0.0001 B/op | 0 |
| scheduled vector load chain, 256 reps | 392.838 ns | 0.003 B/op | 0 |
| one G, 256 reps | 1981.969 ns | 0.014 B/op | 0 |
| one round with 16 named vectors, 256 reps | 4743.136 ns | 0.033 B/op | 0 |

Decision: all four rungs pass the allocation gate. The sub-byte normalized
figures are fixed profiler noise and do not scale with thousands of vector
operations. Proceed to a seven-round block rung, then a sixteen-block chunk
rung, before touching hash dispatch.

Comment: E010's proposed primitive-message layout survives a complete round
without wrapper allocation. This is the first positive end-to-end compiler
shape evidence for the recovered SIMD effort; it is still a diagnostic result,
not a BLAKE3 throughput claim.

### E011 continuation — compiler cliff and complete kernel

Rung 5 located a sharp escape-analysis cliff. Four and six fully expanded
rounds allocate effectively zero and take 65.230 ns and 100.709 ns. Seven
expanded rounds allocate 43,776.098 B/op and take 13,985.551 ns. Replacing the
expanded rounds with a compact seven-iteration schedule loop restores effective
zero allocation and takes 114.963 ns.

Rung 6 combines scalar transpose, the compact round loop, and chaining across
16 blocks: four complete chunk lanes take 2092.938 ns, approximately 1.87 GiB/s,
with 0.015 B/op and no GC.

Rung 7 adds algorithm-correct `Blake3ChunkVectorScratch`, available behind
`-Dfastblake.experimental.scratchChunkVector=true`. The forced full conformance
suite passed. An isolated 8 MiB one-shot GC-profile run measured 5554.711 B/op
(0.00066 B/input byte), zero collections, and 1601 MiB/s; the fixed allocation
is setup/finalization noise rather than compression-proportional garbage.

Standard quick comparison:

| 8 MiB shape | Commons | Rust | FastBlake E011 | E009 default |
|---|---:|---:|---:|---:|
| one-shot | 554 MiB/s | 2505 MiB/s | 1584 MiB/s | 894 MiB/s |
| reused | 546 MiB/s | 2479 MiB/s | 1569 MiB/s | 883 MiB/s |
| streaming 4 KiB | 540 MiB/s | 2301 MiB/s | 874 MiB/s | 874 MiB/s |

Decision: keep opt-in while adding streaming batching, a longer confirmation,
and non-AArch64 validation. E011 is a successful implementation result: 1.77x
the production scalar path for large contiguous inputs and about 63% of Rust,
without algorithm-proportional allocation.

Rule learned: on this C2 build, minimizing the compiler graph is more important
than making fixed rounds straight-line. Six expanded rounds scalarize; seven do
not. A compact round loop preserves vector scalar replacement even with dynamic
schedule offsets. Allocation must be checked after every method-size change.
