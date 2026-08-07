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

Entries E000–E011 were measured on environment **A**. E012 introduces
environment **B**; every entry must now name the environment it was measured on,
because E012 shows that several conclusions are environment-specific.

Environment A (AArch64):

- Machine: Apple M5, 10 cores
- OS: macOS 26.6 (25G72)
- JVM: Temurin OpenJDK 25.0.4+7-LTS
- Benchmark: JMH 1.37, one thread
- Native reference: Rust `blake3` 1.8.5, SIMD, Rayon disabled
- SIMD width: NEON, 128 bit. `IntVector.SPECIES_PREFERRED` is 128-bit/4-lane,
  so the kernels' hard-coded `SPECIES_128` is the full machine width.

Environment B (x86-64):

- Machine: Intel N97, 4 cores, 4 threads, Alder Lake-N (Gracemont E-cores
  only), 3.6 GHz max, 6 MiB L3
- OS: Linux 7.0.0-28-generic
- JVM: Temurin OpenJDK 25+36-LTS
- Benchmark: JMH 1.37, one thread
- Native reference: Rust `blake3` 1.8.5 built with Rust 1.97.1, SIMD, Rayon
  disabled
- SIMD width: AVX2, 256 bit (`UseAVX=2`, `MaxVectorSize=32`, no AVX-512).
  `IntVector.SPECIES_PREFERRED` is 256-bit/8-lane, so the kernels' hard-coded
  `SPECIES_128` uses **half** the available width.

Environment B is a low-power part and is much slower in absolute terms than
environment A. Absolute MiB/s must never be compared across environments; only
ratios within one environment are meaningful.

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

## E012 — cross-architecture validation of E001–E011 on x86-64

Date: 2026-08-06
Environment: **B** (Intel N97, AVX2, Linux, Temurin 25+36). All prior entries
were environment A (Apple M5, NEON, macOS, Temurin 25.0.4+7).

Type: validation of existing experiments on a second architecture. No production
code changed. Full record with all raw numbers, commands, limitations, and
reasoning: `e012-x86-64-cross-architecture-validation.md`.

Purpose: E011 was retained opt-in explicitly pending "validation on at least one
non-AArch64 JVM". This entry supplies it, and also re-runs the E010 allocation
diagnosis to determine whether its findings are properties of C2 or of AArch64.

### Toolchain note

The Rust contender initially reported unavailable: the machine's cargo 1.84.0
predates the `edition2024` feature required by `cpufeatures 0.3.0`, a transitive
dependency of `blake3` 1.8.5. `cargoBuild` recorded the reason and the build
stayed green with two contenders, which is the documented "absence is never
failure" behaviour working as designed on a second platform. Updating to Rust
1.97.1 restored the contender. Recorded because the minimum Rust version is a
real, undocumented build requirement.

### Result 1 — correctness is portable

`./gradlew test` passes on x86-64 in every configuration: 542 tests with all
three contenders present, and 362 java-cpu tests under each of the seven
experimental properties (`vector`, `blockVector`, `chunkVector4`,
`lowLiveVector`, `roundCacheVector`, `heapChunkVector`, `scratchChunkVector`),
each in its own JVM via `JAVA_TOOL_OPTIONS`.

The vector branches genuinely executed rather than being skipped: dispatch
requires `remaining > 4 * CHUNK_LEN` (4096 bytes) and the official vectors run to
102,400 bytes. `Blake3ChunkVectorScratch` is endian-neutral — it assembles words
with explicit byte shifts rather than reinterpretation — so unlike
`heapChunkVector` it needs no little-endian guard, and that portability is now
demonstrated rather than assumed.

### Result 2 — E010's allocation diagnosis is a C2 property, not an AArch64 one

Same protocol as E010: 8 MiB one-shot, one isolated fork per kernel, `-prof gc`.

| kernel | env B B/op | env B B/input byte | env A B/input byte |
|---|---:|---:|---:|
| E002 `vector` | 503,567,842 | **60.03** | not measured |
| E003 `blockVector` | 835,584,039 | **99.61** | not measured |
| E008 `heapChunkVector` | 1,333,932,852 | **159.02** | 158.76 |
| E004 `chunkVector4` | 1,333,934,658 | **159.02** | 158.90 |
| E006 `roundCacheVector` | 1,786,700,064 | **213.00** | 212.99 |
| E005 `lowLiveVector` | 2,138,849,552 | **254.97** | 254.61 |

E002 and E003 were outside E010's allocation driver and their rejection
rationales were left explicitly unverified. This entry closes that gap: both
allocate heavily and both fail the gate, so **among the kernels tested through
E012, every Vector API kernel except E011 is an allocating kernel**, and none of
the E002–E008 causal explanations survive. E013 later added a second
allocation-free kernel. Derived throughput here: 128.3 and 56.8 MiB/s.

The figures agree with environment A to three significant figures on a different
architecture, OS, and JVM build. C2's failure to eliminate `IntVector` wrappers
in these kernels is therefore a property of the compiler and the code shape, not
of AArch64 or of Apple's memory model. E010's central finding is confirmed and
its correction of E002–E008 stands on both architectures.

Their environment-B throughput is correspondingly ruined: 8.3, 7.8, 6.8 and
5.7 MiB/s, i.e. worse than environment A in the same rank order.

### Result 3 — the seven-round escape-analysis cliff reproduces exactly

Full `VectorAllocationBenchmark` ladder, one fork per method, `-prof gc`:

| rung | env B time | env B allocation | env B GC | env A time |
|---|---:|---:|---:|---:|
| primitive four-chunk transpose | 88.709 ns | 0.001 B/op | 0 | 26.449 ns |
| scheduled vector load chain | 343.835 ns | 0.002 B/op | 0 | 392.838 ns |
| one BLAKE3 G | 1531.962 ns | 0.011 B/op | 0 | 1981.969 ns |
| one round, 16 named vectors | 7358.509 ns | 0.051 B/op | 0 | 4743.136 ns |
| four expanded rounds | 116.767 ns | 0.001 B/op | 0 | 65.230 ns |
| six expanded rounds | 166.896 ns | 0.001 B/op | 0 | 100.709 ns |
| **seven expanded rounds** | 31529.766 ns | **43,776.221 B/op** | 16 | 13,985.551 ns |
| seven rounds, compact loop | 216.236 ns | 0.001 B/op | 0 | 114.963 ns |
| four complete chunk lanes | 4660.932 ns | 0.032 B/op | 0 | 2092.938 ns |

The cliff is not merely present on x86-64; the allocated volume is **43,776
bytes per invocation on both architectures**, matching environment A's
43,776.098 to within profiler noise. An identical byte count across two
architectures means the same escape-analysis decision fails on the same objects.
Six expanded rounds scalarize and seven do not, on both.

E011's rule learned — minimize the compiler graph, prefer a compact round loop
to straight-line expansion, recheck allocation after every method-size change —
is confirmed as portable guidance rather than an M5 artifact.

### Result 4 — E011's allocation gate passes on x86-64

`scratchChunkVector`, 8 MiB one-shot, isolated fork, `-prof gc`:
**5990.676 B/op**, i.e. 0.00071 bytes per input byte, with **zero collections**.
Environment A measured 5554.711 B/op. Both are fixed hasher setup and
finalization cost, not compression-proportional garbage. E011 is an
allocation-free kernel on both architectures.

### Result 5 — throughput, and where the two architectures disagree

8 MiB, 2 forks × 5 warmup × 5 measurement iterations of one second,
single-threaded. Quick-run figures (`-f1 -wi 3 -i 3`) agreed within 1–2%.

| 8 MiB shape | Commons | Rust | E011 `scratchChunkVector` | E009 default |
|---|---:|---:|---:|---:|
| one-shot | 211 | 1668 | 642 | 253 |
| reused | 210 | 1713 | 637 | 255 |
| streaming 4 KiB | 210 | 1100 | 253 (scalar route) | 259 |

Ratios, environment B against environment A:

| ratio | env B | env A |
|---|---:|---:|
| E009 scalar vs Commons | **1.20x** | 1.61x |
| E011 vs E009 scalar (one-shot) | **2.54x** | 1.77x |
| E011 vs Commons | **3.04x** | 2.86x |
| E011 as a fraction of Rust | **38%** | 63% |
| Rust vs Commons | **7.9x** | 4.5x |
| Rust streaming-4 KiB penalty | **-36%** | -6% |

Three findings, in descending order of importance.

**E011 is a larger relative win on x86-64 than on AArch64, and it validates.**
2.54x the production scalar path versus 1.77x on the M5. The kernel is correct,
allocation-free, and faster on both architectures. The acceptance criterion
"beats the current E009 production baseline" is met in environment B by a wider
margin than in environment A.

**E009's scalar advantage is largely an M5 result.** Named locals and expanded
rounds beat Commons by 1.61x on the M5 but only 1.20x here. E009's stated
mechanism was exposing independent G operations to a wide out-of-order core;
Gracemont is a narrow E-core with far less to exploit. Evidence 5 in the E010
document computed 2.5–2.6 arithmetic ops/cycle on an M5 P-core against a much
wider integer issue width, and concluded the limiter was dependency structure.
On this core the issue width itself is closer to binding. E009 remains the right
default — it is still the fastest scalar option here — but its 1.61x figure
should be quoted as environment A only.

**E011 falls further behind Rust on x86-64 because it is using half the machine.**
`Blake3ChunkVectorScratch` hard-codes `IntVector.SPECIES_128` — four lanes, four
chunks. On the M5 that is the full NEON width, and E011 reaches 63% of Rust. On
this machine `IntVector.SPECIES_PREFERRED` is `S_256_BIT`, eight lanes: the JVM
reports `UseAVX=2` and `MaxVectorSize=32`. The staged `libfastblake_rust.so`
contains `blake3_hash_many_sse41`, `_avx2` and `_avx512` (4-, 8- and 16-way);
`lscpu` shows AVX2 without AVX-512, so the crate's runtime detection selects the
8-way AVX2 kernel. E011 therefore competes at 4 lanes against Rust's 8 and lands
at 38%. The gap is a width deficit, not a new compiler pathology — the
allocation gate passes and the win over scalar grew.

Rust's own numbers corroborate the width explanation independently. Its
streaming-4 KiB penalty is -36% here versus -6% on the M5: a 4096-byte update
supplies four chunks, which fills a 128-bit NEON batch exactly but leaves an
8-lane AVX2 batch half empty. The same arithmetic that explains Rust's streaming
loss explains E011's ceiling.

Decision: E011 has now satisfied its non-AArch64 validation condition —
correctness, the allocation gate, and a throughput win over E009 all hold in
environment B. It remains opt-in pending only the streaming batch buffer.
No dispatch changed in this entry.

Rule learned: hard-coding `SPECIES_128` cost roughly half the available SIMD
throughput on the first non-AArch64 machine tried, and nothing in the test suite
or the allocation gate detects it, because a narrow kernel is still correct and
still allocation-free. Vector width is a portability property and must be
measured per architecture like allocation is. Every future performance claim
must name its environment; E012 shows that two of this project's headline ratios
(E009's 1.61x and E011's 63%-of-Rust) do not transfer.

Species audit (Result 6 in the detail document): only `Blake3BlockVector`'s
`SPECIES_128` is semantic — it holds a BLAKE3 state *row* in lanes and shuffles
over exactly four, so 4x4 is the algorithm. Every chunk-parallel kernel
(E004–E008, E011) puts one *chunk* per lane, where the lane count is only a batch
size. Their 128 traces to E003's rule learned, which prescribed "transpose 4x4
registers"; on environment A four lanes *was* the preferred width, so batch size
and machine width were the same number and never had to be separated. E002 is the
only width-generic kernel and already runs eight lanes here — and still fails the
gate at 60 B/input byte, which shows width and the allocation-free shape are
independent requirements. Note that `FastBlake` already sizes `vectorPacked` and
`vectorCvs` from `Blake3Vector`'s *preferred*-width helpers, so the harness is
provisioned for eight lanes today; only the kernels narrowed.

Not established by this entry, so these gaps are not mistaken for coverage: only
one x86-64 machine was tested and it is an E-core-only part with no P-core and
no AVX-512, so finding 5b is "E009's advantage depends on core width, shown on
one narrow core", not a general x86-64 result; E002 and E003 have allocation
figures only on environment B, so the cross-architecture comparison that exists
for E004–E008 does not exist for them; no mnemonic assembly was inspected because this Temurin build
also lacks `hsdis`, so whether `VectorOperators.ROR` lowers well on AVX2 — which
has no general 32-bit vector rotate below AVX-512's `VPRORD` — is untested and
is a plausible secondary contributor; `heapChunkVector`'s big-endian guard
remains unexercised; the machine was not thermally quiesced, though quick and
long runs agreed within 1–2%; and only 8 MiB was measured, with no size sweep.

Next experiment: a `SPECIES_PREFERRED`-width variant of
`Blake3ChunkVectorScratch` processing eight chunks per batch where the species
is 256-bit, keeping the compact round loop that E012 confirms is the
allocation-safe shape on both architectures. Gate it on allocation first, per
E010, since the cliff's exact reproduction here means method-size effects will
move with the lane count. The four-lane path must remain for 128-bit machines.
Two cheap items should precede it: add `rust-version = "1.85"` to
`native/rust-blake3/Cargo.toml` and to the README, since cargo 1.84 silently
loses the ceiling contender on this machine; and re-run
`VectorPrimitiveBenchmark` to settle the AVX2 `ROR` question before committing to
a wider kernel's rotate strategy.

## E013 — preferred-width chunk kernel: correct, allocation-free, and slower

Date: 2026-08-06
Environment: **B** (Intel N97, AVX2 256-bit, `SPECIES_PREFERRED` = 8 int lanes)

Change: add `Blake3ChunkVectorWide`, structurally identical to E011's
`Blake3ChunkVectorScratch` — reusable primitive word-major message scratch, 16
named state vectors, compact seven-round schedule loop — with the species and
every derived stride taken from `IntVector.SPECIES_PREFERRED` instead of a
hard-coded `SPECIES_128`. One chunk per lane, so the batch is as wide as the
machine. Enable with `-Dfastblake.experimental.wideChunkVector=true`. The
four-lane E011 kernel is retained unchanged and remains the 128-bit path.
Also adds `WideVectorAllocationBenchmark`, the E011 ladder re-parameterised on
the preferred width, plus an issue-width probe and a register-pressure probe.

Motivation: E012 found the hard-coded 128-bit species cost roughly half the
available width on AVX2 while the Rust crate dispatched to its 8-way kernel.

Correctness: full `./gradlew test` passed with the property enabled — 542 tests
with all contenders present, covering hash, keyed-hash, derive-key, XOF and
incremental boundary shapes. Default dispatch unchanged and re-verified.

Allocation gate (run before any throughput figure, per E010). Ladder, one fork
per method, `-prof gc`:

| rung at 8 lanes | time | allocation | GC |
|---|---:|---:|---:|
| wide transpose to scratch | 200.000 ns | 0.001 B/op | 0 |
| wide seven rounds, compact loop | 445.241 ns | 0.003 B/op | 0 |
| wide sixteen-block chunk loop | 9497.050 ns | 0.066 B/op | 0 |

Full kernel, 8 MiB one-shot, isolated fork: **5992.889 B/op**, 0.000714 B per
input byte, zero collections — indistinguishable from E011's 5990.676. Every
rung passes. The seven-round cliff did **not** reappear at the wider lane count,
which was the specific risk E012 flagged.

Throughput, 8 MiB, 2 forks × 5 warmup × 5 measurement iterations:

| 8 MiB shape | E013 wide (8 lanes) | E011 (4 lanes) | E009 scalar |
|---|---:|---:|---:|
| one-shot | 615 | 642 | 253 |
| reused | 617 | 637 | 255 |
| streaming 4 KiB | 252 (scalar route) | 253 | 259 |

**Doubling the vector width made the kernel 3–4% slower.**

Decision: reject for promotion; retain behind
`-Dfastblake.experimental.wideChunkVector=true`. It is correct, allocation-free
and a useful width-generic candidate for machines with wider *execution*, but it
does not win here and must not become the default on this evidence. A wider
machine should use it only after measurement on that CPU demonstrates a win.
E011 remains the SIMD candidate; E009 remains the production default.

### Why: the scalar transpose is the leading measured contributor

Two probes, each in its own fork.

Issue width — the same BLAKE-shaped dependency chain, same operation count, at
both widths (2 forks × 5 × 8 iterations):

| chain | time | ratio |
|---|---:|---:|
| 128-bit | 2735.789 ns | 1.00 |
| 256-bit | 3645.607 ns | **1.333** |

A 256-bit operation costs 1.333x a 128-bit one while doing twice the work, so at
the primitive level width is worth about **1.50x**. The execution units are not
simply 128 bits wide; the width is real and should have paid.

Register pressure — `wideChunkLoop` with the eight chaining vectors moved to a
primitive array, dropping the live vector set by eight:

| variant | time |
|---|---:|
| wide chunk loop | 9497.050 ns |
| wide chunk loop, chaining values in scratch | 9365.046 ns |

1.4%, inside the protocol's 3% inconclusive band. **Hypothesis not supported.**
Recorded so it is not retried in this form; note it is a weak discriminator,
because the chaining vectors are touched once per block while the 16 state
vectors are touched by every operation and cannot be removed.

Decomposition, all measured at `-f1 -wi 5 -i 5`:

| component | 4 lanes | 8 lanes | change |
|---|---:|---:|---|
| transpose, per block | 82.366 ns / 256 B | 200.000 ns / 512 B | |
| transpose, per byte | 0.3217 ns | 0.3906 ns | **+21.4% worse** |
| chunk loop, per byte | 1.1311 ns | 1.1593 ns | +2.5% worse |
| transpose share of loop | 28.4% | 33.7% | |
| compression, per byte | 0.8093 ns | 0.7687 ns | **−5.0% better** |

This decomposition identifies the scalar transpose as the largest measured
contributor. Compression *appears* 5% faster per byte at 8 lanes, not the 50%
the primitive probe predicts, while the separately measured scalar transpose is
21% worse per byte, enough to account for the observed whole-loop regression.
The subtraction is diagnostic rather than an exact attribution: the isolated
transpose and complete loop need not have identical inlining, scheduling, cache
state, or register pressure.

The transpose is therefore the leading explanation for the regression, not a
proven complete explanation. It is plain scalar byte-shift code that gains
nothing directly from wider vectors, it is measured at roughly 30% of the
kernel, and widening makes the isolated operation worse: it strides across eight
1 KiB chunks instead of four, creating eight input streams rather than four.
Confirming the mechanism requires generated assembly or hardware counters; the
32 KiB L1d observation alone does not prove cache misses.

Why compression gained only 5% rather than 50% is not established. The residual
hypothesis is that 16 state plus 2 message vectors is 18 live values against
x86-64's 16 architectural vector registers — a count that does not improve with
width, while each spill moves twice the bytes. That is consistent with the
measurements but unconfirmed: mnemonic assembly is unavailable on this JVM
(no `hsdis`), so spill traffic was not observed directly.

Rule learned: `SPECIES_PREFERRED` is the right capability source for constructing
a width-generic experimental kernel, not an automatically optimal performance
default. Explicit 128-, 256- and eventually 512-bit specializations must be
measured per machine like allocation is. More importantly, the decomposition
shows that **roughly 30% of this measured kernel shape is scalar byte-shift
transpose, and merely increasing `IntVector` width does not accelerate it.** It
is now the largest measured target in the SIMD path.

Next experiment proposed at the end of E013: vectorize the transpose. E007 measured
`ByteVector.fromArray(...).reinterpretAsInts()` at roughly 25x an endian
`MemorySegment` load, and E008 retained it; the transpose is exactly the place
that loader belongs, loading contiguous 16-byte rows per chunk and transposing
in registers rather than assembling words a byte at a time. Gate on allocation
per rung, and measure it at both widths — a cheaper transpose changes the
width trade-off, and may make E013 win where it currently loses.

### Resolution of the E013 follow-up ideas after E014

This status reconciles the E013 proposals with the E014 experiment that followed.

1. **Vectorize the input load/transpose: measured and rejected in E014.** The
   proposed contiguous `ByteVector` loads plus in-register rearrangement were
   correct and allocation-free, but measured 2.34x slower than a cached
   little-endian `VarHandle` read. E014 adopted the VarHandle instead and improved
   the complete N97 kernels by 28–30%. Do not implement another vector-transpose
   kernel without a materially different shuffle network or new ISA evidence.

2. **Retest register pressure after the loader change: defer.** The existing
   scratch-CV probe changed the wide loop by only 1.4%, inside the 3% inconclusive
   band. Removing values touched once per block is a weak test of the 18
   frequently live state/message vectors. Assembly, spill counters, or a kernel
   shape that materially shortens those live ranges would justify reopening it;
   repeating the same probe would not.

3. **Add automatic CPU dispatch: defer.** `SPECIES_PREFERRED` describes a width
   the runtime supports, not the fastest kernel. The only measured profiles are
   M5 → 128-bit and N97 → 128-bit. Keep manual properties while collecting
   results from AVX2 P-cores and AVX-512. A future selector should choose among
   separately benchmarked specializations, allow an explicit override, and run
   outside the compression loop. If both current properties are enabled, the
   present dispatch order gives `wideChunkVector` precedence; this is an
   experimental detail, not CPU selection.

4. **Use the primitive width probe as a diagnostic only.** Its 1.50x useful-work
   estimate establishes that 256-bit operations have real execution value on the
   N97. It does not predict whole-kernel speedup because it excludes transposition,
   loads, stores, live-range pressure, tree reduction, and frontend effects.

5. **Streaming batching remains independent and high-value.** E014 confirmed that
   both SIMD kernels still take the scalar route for 4 KiB updates, so its faster
   loader did not improve that workload. Complete chunks must be buffered into
   SIMD-sized batches. Keep this as a separate experiment so buffering cost is
   not confused with compression improvements.

### E013 follow-up — Apple M5 regression and dispatch audit (pre-E014)

Date: 2026-08-06
Environment: **A** (Apple M5, NEON 128-bit, preferred species = 4 int lanes)

The E013 revision after the N97 work, before E014 changed both kernels' loaders,
was recompiled and the full official-vector suite passed with `wideChunkVector`
enabled. Focused 8 MiB runs, one fork with 3 warmup and 3 measurement iterations:

| shape | E011 fixed 128-bit | E013 preferred-width | original E011 quick run |
|---|---:|---:|---:|
| one-shot | 1600 MiB/s | 1594 MiB/s | 1584 MiB/s |
| reused | 1598 MiB/s | 1592 MiB/s | 1569 MiB/s |
| streaming 4 KiB | 902 MiB/s | 900 MiB/s | 874 MiB/s |

Assessment: no M5 regression from E013. E011 and E013 both select four lanes
here and differ by less than 1%, well inside the protocol's 3% inconclusive
band. The small improvement over the historical run is ordinary
run-to-run/JIT/thermal variation, not a claimed optimization. These figures do
not describe the current E014 loader. E015 later supplied a current E011+E014
quick run at 1750/1769 MiB/s one-shot/reused; a long M5 confirmation remains
open.

Dispatch audit: the code is width-capable but not yet CPU-policy-aware.
`scratchChunkVector` always selects four lanes and `wideChunkVector` always
selects `SPECIES_PREFERRED`; both require manual properties and the production
default remains scalar. Selecting the widest species automatically would be
wrong on current evidence: four lanes win on both measured machines, while
preferred/eight lanes lose by 3–4% on the N97. The correct future policy needs
separate specialized kernels plus a measured profile/override, not an
assumption that lane count predicts throughput. Known profiles today are M5 →
128-bit and N97 → 128-bit; AVX2 P-cores and AVX-512 remain unknown.

## E014 — little-endian VarHandle transpose: +28% on the SIMD kernels

Date: 2026-08-06
Environment: **B** (Intel N97, AVX2)

Change: replace the per-word manual byte-shift assembly in the chunk-parallel
message transpose with a cached little-endian `VarHandle` int read, in both
`Blake3ChunkVectorScratch` (E011, four lanes) and `Blake3ChunkVectorWide`
(E013, preferred width). Nothing else changed: same word-major scratch layout,
same named state vectors, same compact seven-round loop, same dispatch.

```java
private static final VarHandle LE_INT = MethodHandles
        .byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
// messages[destination + lane] = (int) LE_INT.get(input, p);
```

Motivation: E013's decomposition found the scalar transpose was ~30% of the
kernel, gained nothing from vector width, and got 21% worse per byte as the
batch widened. E010 Evidence 4 had already measured this loader at 3.51x the
manual shifts for the scalar path, and E009's own rule learned nominated it.

Endian-neutrality is preserved and still needs no guard: the VarHandle always
interprets the bytes as little-endian, byte-swapping on a big-endian platform,
which is exactly BLAKE3's definition. Plain `get` on a byte-array view does not
require alignment; only atomic access modes do.

### Rung 1 — the transpose in isolation

`TransposeBenchmark`, 2 forks × 5 warmup × 5 measurement iterations, `-prof gc`.
All three variants were first verified to produce byte-identical word-major
scratch for all 16 blocks of a chunk before being timed.

| transpose variant | time | vs current | allocation |
|---|---:|---:|---:|
| manual byte shifts (E011/E013 shipping) | 78.730 ns | 1.00x | 0.001 B/op |
| **little-endian `VarHandle`** | **26.616 ns** | **2.96x** | ~0 B/op |
| vector loads + in-register 4x4 transpose | 62.185 ns | 1.27x | ~0 B/op |

The vector transpose — 16-byte `ByteVector.fromArray(...).reinterpretAsInts()`
loads plus two-source rearranges, the shape E012 nominated as the obvious next
step — is **2.34x slower than the VarHandle** and only 1.27x better than the
byte shifts it replaces. E007 measured two-source rearranges at 0.799 ns each;
this transpose needs 32 of them plus 16 loads and 16 stores per block, and that
shuffle traffic costs more than the byte assembly it removes. Recorded as a
rejected variant: the fast *loader* E007 and E008 identified is real, but
feeding it through an in-register transpose gives the gain straight back.

### Correctness and allocation gate

Full `./gradlew test` passed with each property enabled: 542 tests, all
contenders, every mode and incremental boundary shape. Default dispatch
unchanged.

Allocation, 8 MiB one-shot, isolated fork per kernel, `-prof gc`:

| kernel | B/op | B per input byte | GC |
|---|---:|---:|---:|
| E011 + E014 `scratchChunkVector` | 5971.880 | 0.000712 | 0 |
| E013 + E014 `wideChunkVector` | 5973.128 | 0.000712 | 0 |

Unchanged from before the transpose swap, as expected — the VarHandle is a
plain primitive read.

### Throughput

8 MiB, 2 forks × 5 warmup × 5 measurement iterations:

| 8 MiB shape | E011+E014 | E011 before | E013+E014 | E013 before | E009 scalar | Commons | Rust |
|---|---:|---:|---:|---:|---:|---:|---:|
| one-shot | **822** | 642 | 802 | 615 | 253 | 211 | 1668 |
| reused | **820** | 637 | 803 | 617 | 255 | 210 | 1713 |
| streaming 4 KiB | 251 | 253 | 252 | 252 | 259 | 210 | 1100 |

**+28% on the four-lane kernel and +30% on the preferred-width kernel**, from
one loader change. E013's decomposition predicted 1.23x for a 2.96x faster
transpose occupying 28.4% of the loop; the measured 1.28x is close, and the
small excess is consistent with the byte-shift version also costing issue slots
the VarHandle does not.

Standing after E014, all environment B:

| ratio at 8 MiB, one-shot | before E014 | after E014 |
|---|---:|---:|
| best SIMD vs E009 scalar | 2.54x | **3.25x** |
| best SIMD vs Commons | 3.04x | **3.90x** |
| best SIMD as a fraction of Rust | 38% | **49%** |

Decision: keep both kernels opt-in; do not promote yet. E011+E014 is now
3.25x the production scalar path and the strongest SIMD result this project has
recorded, but promotion still needs the streaming batch buffer (4 KiB updates
remain on the scalar route at 251 MiB/s) and a confirmation run on environment
A, since the transpose change has only been measured on x86-64.

**E013 still loses to E011, by 2.5%.** The transpose fix helped both kernels by
about the same proportion, so it did not change the width verdict: making the
transpose cheaper did not make eight lanes pay. E013's conclusion stands.

Rule learned: the biggest available win was not in the vector code at all. Three
consecutive experiments (E004–E008 layouts, E013 width) tried to make the SIMD
faster while ~30% of the kernel sat in scalar byte-shift loading that none of
them touched. Decompose a kernel and measure the parts before optimising the
part you assume is hot. Note also that the intuitive follow-up — do the
transpose with vectors, since vectors are what this kernel is about — measured
2.3x worse than the boring primitive read.

Next experiments, in order:

1. Apply the same VarHandle to the **scalar** word loader, which still uses
   manual byte shifts. E010 Evidence 4 measured 3.51x on the loader and ~6.7%
   end-to-end for E009. This changes the production default, so it needs its own
   entry and a longer confirmation.
2. Streaming batch buffer, the last item gating E011's promotion.
3. Re-measure E011+E014 and E013+E014 on environment A. E015 later supplied a
   one-fork E011+E014 quick run at 1750/1769 MiB/s; a long confirmation and a
   current E013 run remain open. E013's width verdict may differ where
   `SPECIES_PREFERRED` is 128-bit and the wide and narrow kernels have the same
   lane count.

### E014 effect on earlier recorded figures

E011's environment A results — 1584 MiB/s one-shot and 1569 MiB/s reused, "about
1.77x the production scalar path and 63% of Rust" — were measured with the
manual byte-shift transpose that E014 has now removed from
`Blake3ChunkVectorScratch`. Those figures stand as written for the kernel as it
existed then, per this ledger's no-rewrite rule, but **they no longer describe
the code in the tree**. E015 later measured the current E011+E014 kernel at
1750/1769 MiB/s in a one-fork quick run; a long confirmation remains open. The
same historical qualification applies to the README's 63%-of-Rust figure and to
E012's environment A column wherever it quotes E011.

On environment B the same change was worth +28%. If it transfers, E011+E014 on
the M5 would be materially above 1584 MiB/s and above 63% of Rust, but that is a
projection, not a measurement, and must not be quoted as a result until run.

## Conclusion — the x86-64 validation campaign, E012 to E014

Date: 2026-08-06

The task was to validate this ledger on Intel x86-64, since everything through
E011 was measured on a single Apple M5. Three entries came out of it. This
section states what is now settled, what changed, and what is still open, so the
campaign can be closed and picked up cleanly later.

### What was proven portable

- **Correctness.** All ten dispatch configurations — the default, the legacy
  scalar control, and all eight experimental kernels — pass the full
  official-vector suite on x86-64: 542 tests each, every mode, XOF, and
  incremental boundary shape.
- **E010's allocation diagnosis.** E004–E008 allocate 159.02, 159.02, 212.99 and
  254.97 bytes per input byte here against 158.76, 158.90, 212.99 and 254.61 on
  the M5 — agreement to three significant figures across a different
  architecture, OS, and JVM build. C2's failure to scalar-replace `IntVector`
  wrappers is a compiler property, not an AArch64 one. E012 also closed E010's
  own gap by measuring E002 (60.03) and E003 (99.61): **among E002–E012, every
  Vector API kernel except E011 is an allocating kernel**, so no E002–E008 causal
  explanation survives. E013 is the second allocation-free kernel.
- **E011's seven-round escape-analysis cliff.** 43,776 bytes per invocation on
  *both* architectures — the same number. Six expanded rounds scalarize, seven do
  not, and a compact loop restores it, on both. E011's "minimize the compiler
  graph" rule is portable guidance about C2, not about NEON.
- **E011's allocation gate.** 0.00071 B per input byte with zero collections
  here, against 0.00066 on the M5.

### What did not transfer

- **E009's scalar advantage.** 1.61x Commons on the M5, 1.20x here. Its mechanism
  is exposing instruction-level parallelism to a wide out-of-order core, and this
  is a narrow Gracemont E-core. E009 is still the right default and still ahead,
  but 1.61x is an environment A figure.
- **E011's standing against Rust.** 63% there, 38% here before E014. Width was an
  obvious capability difference — `SPECIES_128` is full width on NEON and half
  width on AVX2, while Rust dispatches to `blake3_hash_many_avx2` — but E013
  disproved the stronger causal claim: merely moving Java to eight lanes was
  3–4% slower. Core width, rotate lowering, loading, and register pressure remain
  possible contributors to the cross-machine gap.

### What the campaign changed in the code

Only two things, both opt-in, neither promoted:

1. **E013** made the chunk-parallel kernel width-generic
   (`Blake3ChunkVectorWide`, `-Dfastblake.experimental.wideChunkVector=true`).
   The hard-coded species was a genuine portability defect and is now fixed —
   but the fix is 3–4% *slower* than four lanes on this machine, so it is
   evidence, not an improvement. Only `Blake3BlockVector`'s 128-bit species was
   ever semantic; every chunk-parallel kernel's was inherited from E003's
   "transpose 4x4 registers" rule, which was free on a machine where four lanes
   *was* the preferred width.
2. **E014** replaced the transpose's manual byte-shift word assembly with a
   cached little-endian `VarHandle` read, in both kernels. +28% and +30%.

### The methodological finding

E013's decomposition is the most useful thing this campaign produced. About 30%
of the chunk-parallel kernel was scalar byte-shift loading, and **five
consecutive experiments optimised around it** — E004 through E008 reshaped vector
caching and layout, E013 doubled the vector width — while none of them touched
it. E010 had already measured that exact loader at 3.51x, and E009's own rule
learned nominated it; it was applied to neither path. When E014 finally applied
it, one loader change beat every layout experiment in the ledger's history.

Two corollaries, both measured rather than argued:

- The intuitive fix was wrong. Doing the transpose *with vectors* — the natural
  instinct in a kernel whose whole point is vectors — is 2.3x slower than the
  plain primitive read, because the in-register rearranges cost more than the
  byte assembly they remove.
- A stated hypothesis was tested and failed. E013's register-pressure
  explanation was checked by moving the eight chaining vectors to scratch: 1.4%,
  inside the noise band. Recorded as unsupported rather than quietly dropped.

Add to the protocol, alongside the allocation gate: **decompose a kernel and
measure its parts before optimising the part you assume is hot.** A component
that is not vector code will not be improved by better vector code, and will not
show up in a whole-kernel throughput number as anything but "still slow".

### Standing at close of campaign, environment B, 8 MiB

| shape | Commons | E009 default | E011+E014 | E013+E014 | Rust |
|---|---:|---:|---:|---:|---:|
| one-shot | 211 | 253 | **822** | 802 | 1668 |
| reused | 210 | 255 | **820** | 803 | 1713 |
| streaming 4 KiB | 210 | 259 | 251 | 252 | 1100 |

Best SIMD is 3.25x the production scalar path, 3.90x Commons, and 49% of Rust.

### Open items, in priority order

1. **Apply the E014 loader to the scalar path.** It still uses manual byte
   shifts. E010 measured 3.51x on the loader and ~6.7% end-to-end for E009. This
   changes the production default and needs its own entry.
2. **Streaming batch buffer.** 4 KiB updates stay on the scalar route at
   ~251 MiB/s. This is the last item gating E011's promotion out of opt-in.
3. **Re-measure on environment A.** E015 later supplied an E011+E014 one-fork
   quick run at 1750/1769 MiB/s. A long confirmation is still required, and the
   current E013 path remains unmeasured where `SPECIES_PREFERRED` is 128-bit.
4. **Re-measure on a wide x86-64 core.** Every environment B conclusion about
   core width rests on one narrow E-core with no AVX-512. A Golden Cove or Zen
   part would settle whether E009's 1.20x and E013's width loss are typical of
   x86-64 or specific to Gracemont — and on an AVX-512 machine the crate would be
   running its 16-way kernel, changing E013's trade-off again.
5. **Confirm `ROR` lowering on AVX2**, which has no general 32-bit vector rotate
   below AVX-512's `VPRORD`. Still unmeasured; `VectorPrimitiveBenchmark` exists
   to answer it.
6. **Document the Rust minimum version** (1.85, for `edition2024` via
   `cpufeatures`). A one-line `rust-version` in `Cargo.toml` turns a confusing
   contender skip into an actionable message.

Items 5 and 6 are cheap. Item 1 is the largest remaining measured win. Item 3 is
required before any figure in this ledger's environment A column is quoted again.

## E015 plan — SIMD parent compression and batched tree reduction

Date: 2026-08-06

Observed gap: after E014 on environment B, the best Java leaf-SIMD path reaches
822 MiB/s against Rust's 1668 MiB/s. SIMD width alone is not the explanation:
E013's eight-lane Java kernel remains slower than E011's four-lane kernel. The
strongest structural difference is that the Rust implementation supplies
architecture-specific `hash_many` and tree machinery, while FastBlake converts
leaf results to lane-major arrays, copies and pushes every CV separately, and
compresses every parent through the scalar path. C2 instruction scheduling,
AVX2 rotate lowering, and spills remain plausible kernel-level contributors but
are unconfirmed without assembly or counters; JNI overhead is amortized at 8 MiB
and is not a credible primary explanation.

Experiment: add an opt-in preferred-species parent compressor. When an existing
leaf SIMD call returns an aligned power-of-two batch, transpose sibling CV pairs
into one parent-message batch, compress the independent parents together, and
repeat level by level until one subtree CV remains. Push that subtree once using
the same lazy binary-tree invariant. Unaligned input and non-SIMD paths retain
the existing scalar `pushChunkCv` behavior. This isolates parent batching from
leaf-kernel changes and preserves the final/rightmost chunk for root/XOF output.

Gates, in order: full official-vector correctness in hash, keyed, derive-key and
XOF modes; zero input-proportional allocation; then 8 MiB one-shot and reused
throughput against the identical leaf kernel without parent batching. Streaming
4 KiB is expected to remain scalar until the separate streaming batch buffer is
implemented. Retain the experiment only if the complete hash improves outside
the 3% inconclusive band; an isolated parent microbenchmark is not sufficient.

### E015 result — correct and allocation-free, but four-leaf reduction loses

Environment: **A** (Apple M5, NEON 128-bit, preferred species = 4 int lanes)

Implementation: `Blake3ParentVector` compresses one independent parent per lane.
With `scratchChunkVector` and `parentVector` enabled together, each aligned
four-leaf result is reduced in place: two level-1 parents, then one level-2
parent, followed by one subtree-stack push. Existing `vectorPacked` and
`vectorCvs` arrays are reused; unaligned batches retain scalar pushes. The
production default and the leaf kernel are unchanged.

Correctness: the complete rerun of the official-vector suite passed with both
properties enabled, including hash, keyed hash, derive-key, XOF, offsets and all
incremental boundary shapes. The 102,400-byte cases prove that the new branch
executed rather than merely falling through to scalar processing.

Allocation gate, 8 MiB one-shot, `-prof gc`: **5552.508 B/op**, 0.000662 B per
input byte, zero collections. This is fixed hasher/finalization cost and matches
the earlier M5 E011 setup volume; the parent kernel introduces no
input-proportional allocation.

Paired focused runs, one fork, 3 warmup and 3 measurement iterations:

| 8 MiB shape | E011+E014 baseline | + E015 parent SIMD | change |
|---|---:|---:|---:|
| one-shot | 1750 MiB/s | 1713 MiB/s | -2.1% |
| reused | 1769 MiB/s | 1690 MiB/s | -4.5% |
| streaming 4 KiB | 907 MiB/s | 896 MiB/s | -1.2% (scalar route/noise) |

The one-shot result is inside the 3% inconclusive band, while reused crosses the
rejection threshold in the wrong direction. There is no evidence of an
end-to-end improvement. Decision: do not promote; retain behind
`-Dfastblake.experimental.parentVector=true` as a reproducible negative
experiment, meaningful only together with an allocation-free leaf-SIMD property.

Explanation: a four-leaf subtree contains two independent parents at its first
level and one at its second. On a four-lane M5, E015 therefore executes complete
vector rounds at only 2/4 and then 1/4 useful-lane occupancy. It replaces three
strong named-scalar parent compressions with two underfilled vector
compressions, plus transposition and lane extraction. It also vectorizes only
the parents internal to each four-leaf batch; merges between batches remain on
the scalar stack. This result does not show that parent SIMD is intrinsically
bad. It shows that parent SIMD must aggregate a larger forest before reduction:
at least eight leaves give 4/4 occupancy at the first level, while 16 leaves
allow two full first-level calls followed by 4/4, 2/4 and 1/4. Such buffering is
a distinct E016 design and should not be inferred as a win from E015.

Incidental E014 M5 closure: the paired baseline above is the first measurement
of the current VarHandle leaf kernel on environment A. Against the pre-E014
follow-up (1600/1598 MiB/s), it reaches 1750/1769 MiB/s, roughly +9% to +11%.
That is a real-looking improvement but remains a one-fork focused result; quote
it as the current quick run, not as a long confirmation.

## E016 — JDK 28 hdis baseline and AVX2 rotate diagnosis

Date: 2026-08-06
Environment: **B** (Intel N97, AVX2), custom OpenJDK 28-internal with working
mnemonic `PrintAssembly`; Rust 1.97.1 and `blake3` 1.8.5.

Purpose: close E012–E014's machine-code evidence gap before changing another
SIMD layout. Gradle 9.6.1 cannot run its Groovy build scripts on class-file
version 72, so Gradle and compilation used the configured JDK 25 toolchain and
the generated JMH classes were launched directly with
`/opt/jdk-28-custom/bin/java`. JMH confirmed that its child forks also used
JDK 28. The Rust cdylib was rebuilt in release mode and loaded in-process.

Focused 8 MiB baseline, one fork, 2x1 s warmup and 3x1 s measurement:

| implementation | one-shot | reused | streaming 4 KiB |
|---|---:|---:|---:|
| Commons scalar | 202 MiB/s | 215 MiB/s | 213 MiB/s |
| Rust AVX2 | 1561 MiB/s | 1574 MiB/s | 1027 MiB/s |
| E009 Java scalar | 362 MiB/s | 363 MiB/s | 365 MiB/s |
| E011+E014 Java SIMD | 774 MiB/s | 793 MiB/s | 361 MiB/s |

These are diagnostic one-fork figures, not replacements for the longer E014
ledger. They establish that JDK 28 preserves the important shape: the
four-lane SIMD path reaches about 50% of Rust on contiguous input, while 4 KiB
updates remain on the scalar route. The JDK 28 allocation gate passes at
5857 B/op, 0.000698 B per input byte, with zero collections.

### hdis result

The stable non-OSR C2 compilation of
`Blake3ChunkVectorScratch.hashChunks` is 7648 bytes with a 368-byte stack
frame. The static generated body contains 398 vector stack moves. Its compact
round-loop body lowers all four rotate constants identically: 32 `vpsrld`, 32
`vpslld`, and 32 `vpor`, with no `vpshufb`. AVX2 has no general packed rotate,
so each Java Vector API `ROR` costs three instructions.

The Rust `blake3_hash_many_avx2` disassembly uses `vpshufb` for the byte-aligned
ROR16 and ROR8 operations, and shift/shift/OR only for ROR12 and ROR7. This is
the first confirmed instruction-selection difference that directly applies to
the compression rounds. Replacing the sixteen byte-aligned rotates in one
BLAKE3 round should reduce the rotate sequence from 96 to roughly 64 vector
instructions. It cannot explain the whole twofold gap, but it is the smallest
high-information kernel experiment now available.

### E016 execution plan

1. Express ROR8 and ROR16 as `ByteVector.rearrange` operations in isolated
   dependency-chain probes. Require one `vpshufb` in hdis, correct output, and
   effectively zero allocation.
2. If the probes pass, integrate ROR16 and ROR8 into only the four-lane E011
   kernel, one constant at a time. After each change run official-vector
   conformance, the allocation gate, hdis, and paired 8 MiB JMH.
3. Implement a bounded four-chunk pending buffer for incremental updates. A
   full 4 KiB update currently cannot enter SIMD because the implementation
   must retain the possible rightmost chunk; the next byte makes the preceding
   four chunks safe to hash as one batch.
4. Independently replace the production scalar full-block byte-shift loader
   with E014's little-endian VarHandle. Keep partial blocks on the existing
   bounded byte loop.

Raw JDK 28 results are `build/jmh-jdk28-baseline.json`,
`build/jmh-jdk28-scratch.json`, and `build/jmh-jdk28-scratch-gc.json`.

### E016 rotation result — ideal isolated lowering, rejected in the full kernel

The ROR8 and ROR16 byte-shuffle probes passed their local gates. A one-step
assertion checked both masks against `Integer.rotateRight`. Each 256-operation
dependency chain allocated about 0.001 B/op with zero collections, and hdis
showed one `vpshufb` per operation with no shift/shift/OR sequence.

| 256 dependent operations | Vector API `ROR` | byte shuffle | speedup |
|---|---:|---:|---:|
| ROR16 | 194.972 ns | 89.950 ns | 2.17x |
| ROR8 | 198.411 ns | 87.479 ns | 2.27x |

Integration stopped at the first allocation rung. Replacing only the eight
ROR16 sites in the compact E011 round body with the proven shuffle helper made
the 8 MiB hash allocate **205,426,540 B/op**, about 24.49 bytes per input byte,
and reduced one-shot throughput from 774 to 245 MiB/s. The full official-vector
suite still passed, proving the path executed correctly, but its throughput is
an allocation/GC result and is rejected. ROR8 was not integrated because it
adds the same reinterpret/rearrange wrapper graph. The leaf kernel was restored
to Vector API `ROR`; only the diagnostic probes remain.

Rule learned: a Vector API expression can scalar-replace and lower perfectly in
isolation yet cross the escape-analysis cliff when inserted eight times into a
large method. `vpshufb` remains a useful future target only through a compiler
intrinsic or a structurally smaller compression kernel; it is not a viable
incremental edit to E011 on this C2 build. Raw probe and rejected-integration
results are `build/jmh-jdk28-rotate-probes.json` and
`build/jmh-jdk28-ror16.json`.

### E016 streaming result — four-chunk pending batch accepted

The scratch-vector update path now retains at most one 4096-byte batch. A
following byte proves that the retained chunks cannot be the BLAKE3 root, so
the complete batch can be sent to the existing four-lane leaf kernel. If the
stream ends at the retained batch, finalization scalar-compresses its first
zero to three chunks into a copied CV stack and preserves the last chunk for
ROOT/XOF output. Finalization therefore remains repeatable and does not mutate
the hasher.

The forced, uncached official-vector run passed with
`scratchChunkVector=true`, including ordinary, keyed and derive-key hashing,
XOF, offsets, reset, repeated finalization, and incremental boundaries around
1024 and 4096 bytes. The in-process Rust contender was present for the same
run.

Focused JDK 28 results after combining the pending buffer and the scalar loader,
one fork, 3x1 s warmup and 3x1 s measurement:

| 8 MiB shape | pre-buffer E011+E014 | final E016 | change |
|---|---:|---:|---:|
| one-shot | 774 MiB/s | 747 MiB/s | -3.5% |
| reused | 793 MiB/s | 779 MiB/s | -1.8% |
| streaming 4 KiB | 361 MiB/s | 747 MiB/s | **+107%** |

Streaming is now the same performance class as contiguous input instead of
falling through to scalar. Reused and streaming allocation is about 4.7 KiB
per operation (0.000557 B per input byte) with zero collections; it is fixed
hasher/finalization cost, not input-proportional allocation. One-shot is about
12.8 KiB/op because that benchmark also constructs the hasher. Decision:
retain the buffer as part of the opt-in scratch-vector path. The small
contiguous-input movement should be rechecked in the longer promotion run, but
does not outweigh the deliberately targeted 2.07x streaming gain.

Raw results are `build/jmh-jdk28-stream-buffer.json` and
`build/jmh-jdk28-final-simd.json`.

### E016 scalar result — little-endian VarHandle loader accepted

The production scalar full-block loader now uses a cached little-endian
`byteArrayViewVarHandle`; the bounded partial-block loop is unchanged. This is
the same endian-neutral load primitive that passed E014's isolated and SIMD
gates, applied independently to the scalar compressor.

| 8 MiB scalar shape | byte shifts | VarHandle | change |
|---|---:|---:|---:|
| one-shot | 362 MiB/s | 390 MiB/s | +7.7% |
| reused | 363 MiB/s | 378 MiB/s | +4.1% |
| streaming 4 KiB | 365 MiB/s | 365 MiB/s | neutral |

The normal full suite and the forced scratch-vector suite both pass. Allocation
remains fixed-size with zero collections. Decision: keep the loader in the
production scalar path; it improves two shapes and does not regress the third.
Raw result: `build/jmh-jdk28-scalar-varhandle.json`.

### Next performance step after E016

On this N97 the final Java SIMD path reaches 747/779/747 MiB/s versus Rust's
1561/1574/1027 MiB/s. Streaming is now 73% of Rust, while contiguous hashing is
still only 48–49%. The rotate experiment proved that instruction selection can
be improved in isolation but also proved that inserting Vector wrapper graphs
into the 7648-byte kernel is not viable on this C2 build. The next experiment
should therefore measure, rather than infer, the remaining kernel cost:

1. collect paired cycles, instructions, branches and cache-miss counters for
   Java's leaf kernel and Rust's `blake3_hash_many_avx2` on the same pinned core;
2. add allocation-free phase probes for transpose/load, seven-round compression,
   and CV extraction, so the 398 static vector stack moves can be tied to time;
3. only then prototype a smaller-live-set compression boundary, with hdis and
   allocation as first gates. A design that merely hides shuffle rotations in a
   helper is rejected unless hdis proves that the helper neither allocates nor
   adds calls in the hot loop.

Promotion of the scratch-vector path remains separate: repeat the longer paired
suite on environment A and at least one additional AVX2 CPU, then choose
dispatch by measured architecture rather than `SPECIES_PREFERRED` alone.

### E016 follow-up — Apple M5 confirmation

The same forced conformance and 8 MiB gates passed on environment A with JDK
25.0.4. A stable five-iteration one-shot rerun measured 1759 MiB/s; the paired
three-shape run measured 1763 MiB/s reused and 1726 MiB/s streaming 4 KiB.
Against the recorded pre-E016 E011+E014 control of 1750/1769/907 MiB/s, the
contiguous changes are +0.5% and -0.3%, inside the 3% inconclusive band, while
streaming improves by approximately 90%.

The allocation gate also transfers: 12,455.674 B/op one-shot and approximately
4,752 B/op reused/streaming, respectively 0.001485 and 0.000567 bytes per input
byte, with zero collections. The production scalar VarHandle path measured
923/919/921 MiB/s, directionally +3% to +5% over the earlier E009 M5 quick run.
Full commands, timings and qualifications are preserved in
`reference/performance/results/e016-m5.md`.

Decision: E016 does not regress contiguous hashing on M5 and decisively fixes
the 4 KiB streaming route. This closes the environment-A quick confirmation;
the longer multi-fork promotion run and another AVX2 CPU remain open.

## E017 — N97 phase decomposition and smaller-live-set control

Date: 2026-08-06

The three-fork CPU-3-pinned control measures the current Java SIMD path at
801 MiB/s and Rust AVX2 at 1599 MiB/s: Java is 50.1% of Rust. After the machine
owner enabled user-space counters, the operation-normalized paired run measured
29.75M versus 14.66M cycles and 89.83M versus 20.99M instructions per 8 MiB
hash. Java executes **4.28x** the instructions but sustains 3.02 IPC versus
Rust's 1.43, reducing the final cycle gap to 2.03x. Cache references and misses
are only 15% higher; Java branches are 61.45x higher but rarely mispredict.

Exact allocation-free probes close to within 0.1% of the full four-chunk
kernel: 16 blocks of VarHandle load/transpose account for 387.2 ns (9.2%), 16
seven-round compressions account for 3825.6 ns (90.5%), and CV extraction costs
17.1 ns (0.4%), versus 4225.7 ns measured end to end. Compression, not loading
or extraction, is the remaining target.

Phase counters agree: the exact batch retires 37,620 instructions, while the
separately measured phase sum predicts 36,931 (within 1.8%). Sixteen
compression phases contribute about 31,817 instructions. This is an
instruction-volume problem, not evidence that the core is starved for work.

A bounded state-scratch prototype reduced the nominal live vector set to one G
but measured 387.6 ns per seven rounds versus 247.1 ns for the named-vector
loop, **56.8% slower**, with both paths at 0.002 B/op. hdis shows a separate
1344-byte `g` method, repeated range checks, and explicit four-load/four-store
state traffic on each of 56 calls. Decision: reject for production and retain
only as a benchmark control. Full commands, raw-result names, qualifications,
and the counter-unblock procedure are in
`reference/performance/results/e017-n97.md`.

## E018 — partial unrolling and the three-copy cliff

Date: 2026-08-06

E017's next fork tested whether compression's instruction count could be
reduced by partially unrolling the seven-round Vector API loop. Raising C2's
global `LoopUnrollLimit` produced no useful improvement. Explicit 2x and 3x
forms with a peeled seventh round contain respectively three and four static
round-body copies; both cross escape analysis and reach about 43,776 B/op.

A guarded 2x form handles the odd tail with only two static copies. It passes
the allocation and constructor-correctness gates. After the larger compilation
had seven warmup iterations, CPU-3 counters measured 227.476 ns, 688.4 cycles,
1,942.0 instructions and 136.2 branches per block, versus 239.551 ns, 696.6
cycles, 1,992.3 instructions and 139.5 branches for the compact loop. That is a
5.0% isolated time improvement but only 2.5% fewer instructions and 2.3% fewer
branches. hdis shows why composition is risky: stable C2 code grows from 4,720
to 20,872 bytes.

The risk materialized in the exact production-kernel gate. The integrated
guarded form remained correct and allocation-free but took 6,515.209 ns per
four-chunk batch versus 4,286.171 ns after restoring the compact loop, a
**52.0% regression**. Decision: reject partial unrolling and leave production
unchanged. Loop bookkeeping is quantitatively too small to explain the
Java/Rust gap, while source duplication destroys full-method compiler quality.
The next candidate should be a custom-JDK constant ROR8/ROR16 lowering to
AVX2 `vpshufb`, preserving the compact Java graph. Full results and commands are
in `reference/performance/results/e018-n97.md`.

## E019 — custom-JDK AVX2 ROR8/ROR16 lowering

Date: 2026-08-06

E019 implemented the compiler experiment nominated by E018. On `UseAVX=2`, C2
now preserves constant packed-int rotate-right nodes and lowers ROR8/ROR16 to a
single memory-source `vpshufb`; ROR12/ROR7 retain their shift/shift/OR fallback.
The stable full-kernel body contains 16 shuffles and 16 of each fallback
instruction, exactly matching the intended BLAKE3 rotate split.

Two correctness bugs in the first matcher versions were caught before timing:
an unguarded pre-existing EVEX rule caused `SIGILL` on the N97, and an incomplete
temporary-register contract miscompiled OSR. With an explicit EVEX predicate
and both AVX2 macro destinations declared temporary, all 542 forced SIMD tests
pass with normal OSR and Rust present. Allocation remains at the profiler floor.

Paired CPU-3 counters improve the exact four-chunk kernel from 4,385 to 3,601 ns
and from 12,207 to 10,138 cycles, both about **17%**, while retired instructions
fall only 2.0% and IPC rises from 3.08 to 3.64. The dependency-chain shortening
therefore matters more than the raw instruction count alone. A shorter 8 MiB
composition run improves one-shot from 817 to 941 MiB/s and streaming from 788
to 932 MiB/s. Java reaches about 56% of pinned Rust on contiguous input and 87%
on streaming.

Decision: accept as strong compiler-level evidence, not yet as a generally safe
JDK patch. The next FastBlake experiment is to retest E013's 256-bit kernel on
this VM because the new rule already handles YMM vectors and the old width loss
was only 2.5%. Full implementation notes, failure analysis, build details,
counters and raw artifact names are in
`reference/performance/results/e019-n97.md`.

## E020 — preferred-width retest on the AVX2-rotate VM

Date: 2026-08-06

E020 retested E013's eight-lane kernel with E019's compiler lowering and added
an exact complete-batch benchmark so four and eight lanes could be normalized
per input byte. All 542 forced-wide conformance tests pass with normal OSR and
Rust present. The exact kernels remain at the allocation-profiler floor.

The 2x2 counter control gives the important result. On the stock JDK the exact
eight-lane kernel is 5.2% faster per byte than four lanes; on the patched JDK it
is 4.7% faster. The patch improves four lanes by 20.3% and eight lanes by 19.8%
in elapsed time. It benefits both widths almost equally rather than uniquely
unlocking YMM. At the patched width comparison, instructions per KiB fall 44.6%
but IPC falls from 3.65 to 2.21, limiting the width gain. hdis confirms real YMM
operations and the intended 16 `vpshufb`, 16 `vpsrld`, 16 `vpslld`, and 16
`vpor` static rotate split in the hot method.

A longer 8 MiB composition diagnostic measured 1020/998 MiB/s one-shot/reused
for eight lanes, versus 909/898 MiB/s for the current four-lane route and
1658/1647 MiB/s for Rust. The exact result is the stronger width comparison;
the end-to-end methods have wider variance and use different update routes.
The existing wide route also cannot batch 4 KiB updates and falls back to the
scalar path, measuring 398 MiB/s in a short diagnostic.

Decision: record the architectural result but make no production change.
FastBlake is a public library and cannot require a private JDK build. E016
already tried the code-only `ByteVector.rearrange` expression: it produced the
right isolated `vpshufb`, but integrating only ROR16 allocated 205,426,540 B per
8 MiB hash and cut throughput from 774 to 245 MiB/s. Until a stock JDK provides
the direct lowering, or a library-only expression passes the full allocation
and composition gates, E019/E020 have no shippable speedup. Keep ordinary
`VectorOperators.ROR`, keep both SIMD kernels experimental, and treat the data
as evidence for an upstream HotSpot improvement. Full results are in
`reference/performance/results/e020-n97.md`.

## E021 — x86 message-load folding audit

Date: 2026-08-06

E021 tested whether C2's failure to fold message loads into AVX2 `vpaddd` could
jointly explain the N97 instruction gap and vector-register pressure. The
premise needed two corrections: the compact loop has 16 static message loads
executed over seven rounds, hence 112 dynamic loads per block; and the
four-lane kernel uses XMM, not YMM. The 4,720-byte E018 compilation is the
isolated seven-round benchmark, while production `hashChunks` is the roughly
7.6–7.9 KiB method.

The stable non-OSR bodies on both stock and E019-patched JDK 28 give the same
answer: 8 memory-source `vpaddd` and 8 separate message-array `vmovdqu` loads
per static round body. That is 56 folded and 56 separate loads per block, or
896 of each per complete four-chunk batch. Folding every remaining load could
remove at most 896 of the stock exact kernel's 37,900.6 instructions, only
2.36%.

Scaled to E017's 8 MiB counters, the remaining separate loads account for about
1.834M instructions: 2.04% of Java's 89.83M and 2.67% of the 68.84M Java/Rust
excess. Perfect folding would move the ratio only from 4.28× to approximately
4.19×. Even the counterfactual where none of the 112 loads folded could explain
only 5.33% of the excess.

Decision: reject missing memory-operand folding as a primary cause of the N97
gap and as the unified explanation for the source-level 18-versus-16 live-value
count. One message operand per G is already consumed directly from memory; the
other introduces only a transient register. Restructuring Java solely to fold
that remaining half has a roughly 2.4% instruction ceiling and cannot close the
gap. Keep the Apple M5 diagnosis separate: x86 memory operands are inapplicable
there, and no N97 counter result should be projected onto AArch64. Full counts
and qualifications are in `reference/performance/results/e021-n97.md`.

## E022 — first AArch64 generated-code audit, and a rejected bounds-check fix

Date: 2026-08-07
Environment: **A** (Apple M5, macOS 26.6), custom OpenJDK 28-internal with a
working `hsdis-aarch64.dylib`. This is the project's first mnemonic AArch64
disassembly; every earlier M5 entry had to reason without one.

E021 closed by requiring an independent M5 audit before any N97 cause was
assigned to the Apple gap. This is that audit.

### The generated code

Stable non-OSR `Blake3ChunkVectorScratch::hashChunks`, seven-round loop body:
**245 instructions**.

| class | count | theoretical minimum |
|---|---:|---:|
| vector shift/rotate (`ushr`+`sli`) | 64 | 64 |
| vector add | 48 | 48 |
| vector eor | 32 | 32 |
| vector message load | 16 | 16 |
| scalar index/address | 49 | 0 |
| branch/compare | 36 | 0 |
| **vector spill traffic** | **0** | 0 |

Two results, and they point in opposite directions.

**The vector arithmetic is already optimal.** 48/32/64/16 matches the exact
minimum for one four-lane BLAKE3 round. C2 lowers `VectorOperators.ROR` to
`ushr`+`sli`, which is the best AArch64 offers without a dedicated vector
rotate. There is nothing left to win inside the mixing math on this
architecture.

**There is no spilling.** Not one vector store or reload to `[sp]` in the round
body. The 18-live-values-against-16-registers pressure that shapes every N97
result does not exist on AArch64's 32 vector registers. E021's warning was
correct and is now confirmed from generated code rather than inferred: N97
conclusions must not be transferred to environment A.

That leaves 85 instructions, 35% of the round body, in scalar index arithmetic
and bounds checking. The pattern repeats at all 16 message loads:

```asm
lsl   w4, w3, #2              ; schedule[i] * 4
cmp   w4, w10                 ; bounds check vs messages.length
b.hs  #0x11b9a76d0            ; -> uncommon trap
add   x4, x6, w4, sxtw #2     ; address
ldur  q18, [x4, #0xc]         ; the load
```

Four overhead instructions per useful load. The index comes from
`SCHEDULE[round][i]`, so it is data-dependent and C2 cannot prove its range.

### The rejected fix

Hypothesis: flatten `SCHEDULE` to a pre-multiplied `int[112]` and mask each use
site with `& 60`. The index is then provably `[0, 60]`, so with `messages` at
least 64 long the only remaining check is `messages.length >= 64`, which is
loop-invariant and should hoist out of the round loop. Projected 13-20% fewer
instructions in the round body.

Correctness: full forced conformance passed, 542 tests, with
`JAVA_TOOL_OPTIONS=-Dfastblake.experimental.scratchChunkVector=true`.

Allocation gate: 36,744 bytes total for 25,165,824 bytes hashed, 0.00 B per
input byte. Passed.

Generated code, same JDK, before and after:

| round body | E011 baseline | E022 flat+mask |
|---|---:|---:|
| total instructions | 245 | **245** |
| vector | 160 | 160 |
| scalar | 85 | 85 |
| `cmp` | 18 | **17** |

Paired interleaved throughput, 8 MiB one-shot, three rounds each:

| | run 1 | run 2 | run 3 | mean |
|---|---:|---:|---:|---:|
| E011 baseline | 1890.2 | 1889.8 | 1886.3 | 1888.8 |
| E022 flat+mask | 1874.3 | 1886.4 | 1878.1 | 1879.6 |

**The mask removed exactly one bounds check** — the 2D `SCHEDULE[round]`
dereference that flattening eliminated. The 16 per-load checks survived
untouched, and the 0.5% throughput difference is inside the protocol's 3%
inconclusive band.

Decision: reject and revert. Production is unchanged.

Comment: the masking trick works on ordinary array loads but does not reach the
range check that `IntVector.fromArray` generates inside its own intrinsic. C2's
range analysis proves the *index* is `[0, 60]` and still emits the check, so the
bound is not being derived from the masked index at all. This is a Vector API
lowering property, not something reachable by reshaping Java source — which
puts it in the same category as E016's `vpshufb` result.

Rule learned: the 85 scalar instructions are real and measured, but they are not
removable from Java. Before the next attempt at them, establish whether the M5
round loop is even issue-bound — if the vector dependency chain is the critical
path, scalar index work issuing in parallel on separate units may be costing
nothing, and the whole 35% is a phantom target. That measurement (a probe with
the message loads replaced by constant vectors, preserving the vector chain)
should precede any further work here, and it is cheap.

Artifacts, this machine:

```text
build/e022-m5-baseline-nonosr.txt        # isolated stable non-OSR body
build/e022-m5-baseline-hashchunks.log    # full capture, E011 baseline
build/e022-m5-flatmask-hashchunks.log    # full capture, rejected candidate
```

## E023 — issue-bound probe: the M5 round body's scalar overhead is free

Date: 2026-08-07
Environment: **A** (Apple M5), custom OpenJDK 28-internal.

E022 measured 85 of 245 instructions in the AArch64 round body as scalar index
arithmetic and bounds checking, and closed by requiring this probe before any
further attempt to remove them.

Method: two variants generated mechanically from the production kernel source.

* **A** — `Blake3ChunkVectorScratch` verbatim, renamed.
* **B** — identical vector arithmetic, but the 16 per-round message operands are
  loop-invariant vectors hoisted out of the block loop. Every message load,
  index computation and bounds check disappears; the vector dependency chain,
  the live-vector count and the round structure are unchanged.

B computes a wrong digest by construction. It is a diagnostic control, never a
candidate. The transpose still runs in both, so the input is still read.

Per four-chunk batch, interleaved, best of 12 repetitions each, isolated JVMs:

| round | A (real kernel) | B (no message loads) |
|---|---:|---:|
| 1 | 1778.829 ns | 1759.481 ns |
| 2 | 1766.052 ns | 1758.931 ns |
| 3 | 1767.395 ns | 1756.429 ns |
| **mean** | **1770.759 ns** | **1758.280 ns** |

Both allocate 0 bytes in the measured repetition.

**Removing 35% of the round body's instructions buys 0.7%.**

Conclusion: the M5 four-chunk round loop is latency-bound on the vector
dependency chain, not issue-bound. The scalar index and bounds-check work
issues in parallel on scalar units and costs essentially nothing.

A cycle estimate agrees. The critical path is about 18 dependent vector
operations per G, two G groups per round, seven rounds, sixteen blocks — roughly
8,000 cycles per batch at Apple P-core NEON latencies, or about 1,830 ns at
4.4 GHz against 1,771 ns measured. The body retires roughly 27,400 instructions
in that window, well under the core's issue capacity.

Decision: retire instruction-count reduction as an optimization direction for
the M5 four-chunk kernel. That closes message-load folding, bounds-check
elimination, index flattening, schedule restructuring, and partial unrolling as
avenues on this architecture — E018, E021 and E022 were all, in retrospect,
attacking a target that does not exist here.

Comment: this also explains E022's null result completely. Even if the mask had
eliminated all sixteen bounds checks, the measured ceiling for doing so was
0.7%. The experiment was unwinnable before it was written, and the probe costs
minutes where the prototype cost hours.

Rule learned: before optimizing an instruction count, prove the loop is
issue-bound. The probe is cheap and mechanical — hoist the operand loads out,
keep the arithmetic, compare. Add it to the measurement protocol alongside the
allocation gate.

Consequence for direction: a latency-bound loop goes faster only by putting more
independent work in flight, not by shrinking the work it already has. The M5
kernel exposes 4-way ILP (four independent G functions per half-round). The
natural next candidate is two interleaved four-chunk batches, raising it to
8-way. AArch64's 32 vector registers are the reason this is worth trying here
and not on the N97: 32 state vectors plus message operands is tight but close to
fitting, and E022 measured zero spill traffic at the current 16, so there is
headroom to spend. Note that `SPECIES_PREFERRED` is 128-bit on this machine, so
E013's wide kernel is not the same experiment — interleaving batches, not
widening lanes, is what adds parallelism on NEON.

Artifacts: probe sources are generated from the kernel by the script in this
entry's session; variants A and B differ only in the 16 `fromArray` sites.

## E024 — two interleaved four-chunk batches: +36%, the first ILP win

Date: 2026-08-07
Environment: **A** (Apple M5), custom OpenJDK 28-internal for the paired driver
runs; the standard quick comparison uses the project toolchain as usual.

E023 proved the four-chunk round loop is latency-bound, so the only remaining
lever is more independent work in flight. This runs two independent four-chunk
groups (8 chunks, 8192 bytes) through one interleaved round body, raising ILP
from four independent G chains per half-round to eight. Lanes stay at
`SPECIES_128`: on NEON, parallelism comes from more batches, not wider vectors,
so this is not E013's width experiment repeated.

`Blake3ChunkVectorDual`, enabled with
`-Dfastblake.experimental.dualChunkVector=true`. The round body is generated
mechanically from `Blake3ChunkVectorScratch` by duplicating and interleaving its
eight G-pair groups, so the two kernels cannot drift apart in arithmetic.

### Gates, in protocol order

**Equivalence** (standalone, before integration): the dual kernel's 64 output
words equal two sequential four-chunk batches, over counters {0, 4, 1024,
999996} x flags {0, 16, 64}. PASS.

**Allocation gate**, isolated JVM per variant, 8 MiB end to end:

| kernel | allocated | per input byte |
|---|---:|---:|
| E011 `scratchChunkVector` | 37,896 B | 0.00 |
| E024 `dualChunkVector` | **17,208 B** | **0.00** |

Doubling the round body did **not** cross the escape-analysis cliff. Given E011
rung 5 (six expanded rounds fine, seven at 43,776 B/op) this was the main risk
and it did not materialise: the compact seven-iteration loop is preserved, and
only the body inside it grew.

**Conformance**: full official-vector suite, 542 tests, with
`JAVA_TOOL_OPTIONS=-Dfastblake.experimental.dualChunkVector=true`. PASS. Default
dispatch re-verified unchanged at 542.

**Throughput, kernel only**, per 8192 bytes, interleaved, best of 12:

| | run 1 | run 2 | run 3 | mean |
|---|---:|---:|---:|---:|
| single four-chunk | 3549.927 | 3534.465 | 3549.927 | 3544.773 ns |
| **dual** | 2589.438 | 2585.368 | 2627.604 | **2600.803 ns** |

**1.363x.**

**Throughput, end to end**, 8 MiB one-shot, interleaved, JDK 28:

| | run 1 | run 2 | run 3 | mean |
|---|---:|---:|---:|---:|
| E011 | 1895.9 | 1899.1 | 1866.3 | 1887.1 MiB/s |
| **E024** | 2475.2 | 2470.5 | 2456.2 | **2467.3 MiB/s** |

**1.307x.**

**Standard quick comparison**, 1 fork, 3 warmup, 3 measurement:

| 8 MiB shape | Commons | Rust | E024 | E011 | E024 vs E011 |
|---|---:|---:|---:|---:|---:|
| one-shot | 553 | 2510 | **2149** | 1584 | 1.36x |
| reused | 549 | 2491 | **2149** | 1569 | 1.37x |
| streaming 4 KiB | 544 | 2344 | 905 | 874 | unchanged |

FastBlake is now at **85.6% of Rust** on large contiguous input, up from 63%.

### Generated code: why it wins while doing more work

Stable non-OSR round body, AArch64:

| | E011 single | E024 dual | vs 2x single (490) |
|---|---:|---:|---:|
| instructions | 245 | 533 | **+8.8%** |
| vector-referencing | 160 | 348 | |
| scalar/branch | 85 | 185 | |
| **vector spill traffic** | **0** | **30** | |

The dual kernel executes 8.8% *more* instructions per unit of work than two
single batches, and it spills — 32 state vectors plus message operands exceeds
AArch64's 32 vector registers, exactly as E023 predicted. It is 36% faster
anyway.

That is the clearest possible confirmation of E023: in a latency-bound loop,
instruction count and even spill traffic are close to free, while independent
work in flight is everything. The same 30 spills in an issue-bound loop would
have been a regression.

Decision: keep opt-in behind `-Dfastblake.experimental.dualChunkVector=true`
pending streaming support and non-AArch64 validation, matching how E011 was
handled. Production default is unchanged.

Open items before promotion:

1. Streaming is untouched at 905 MiB/s — the dual kernel only takes the direct
   path, with no equivalent of E016's pending-batch retention. An 8192-byte
   pending batch is the obvious follow-up and is worth more than further
   compression work.
2. Validate on environment B. The N97 is issue-bound and register-starved at 16
   architectural vector registers, so the mechanism that makes E024 win here may
   invert there. Do not promote on environment A evidence alone.
3. A four-way interleave (16 chunks) is the natural next rung, but spill traffic
   will grow faster than ILP at some point. E023's probe should be re-run against
   E024 first to establish whether it is still latency-bound.

Rule learned: when a loop is latency-bound, evaluate candidates by independent
work in flight, not by instruction count or register pressure. E013, E016, E018,
E021 and E022 all optimised the wrong quantity; E023 identified it and E024 is
the first experiment since E014 to move the number.

Artifacts: `build/e024-m5-dual-hashchunks.log`.

## E025 plan — capability dispatch and the re-ranked priorities

Date: 2026-08-07
Status: plan. P0 in progress; P1-P3 not started.

E024 is the first optimisation whose *sign* depends on the machine: +36% on
Apple M5, -12 to -14% on a Ryzen 3200G. That is not a defect in the kernel and
must not be resolved by dropping it. The reference Rust crate carries separate
SSE2/SSE4.1/AVX2/AVX-512/NEON implementations and dispatches at runtime, and
that is a large part of why it is the ceiling in this ladder. FastBlake needs
the same structure.

At the same time the 2-fork long run exposed that the largest remaining gaps
are no longer in compression at all. This entry records both, because they are
one design: the dispatch ladder and the size ladder are the same decision.

### What the M5/Zen+ split actually says

The goal E024 established is **eight chunks in flight**, not any particular
kernel. The mechanism that achieves it differs by register file:

| target | vector registers | 8-in-flight mechanism | state vectors | fit |
|---|---:|---|---:|---|
| AArch64 NEON | 32 x 128-bit | 2 interleaved 4-lane batches (E024) | 32 | 32/32, 30 spill instrs, wins |
| x86-64 AVX2 | 16 x 256-bit | 8 lanes wide (E013) | 16 | 16/16, untested at this goal |
| x86-64 AVX2 | 16 x 128-bit | 2 interleaved 4-lane batches (E024) | 32 | **32/16, spills, loses** |

Dual-on-NEON and wide-on-AVX2 are register-equivalent: 32 state vectors into 32
registers, 16 into 16. E013 and E020 rejected the wide kernel, but against the
*four*-chunk kernel and before eight-in-flight was known to be the objective.
**Retesting wide against E024 and E011 on the Zen+ machine is the first
experiment this plan calls for**, and it is a genuine prediction, not a
rationalisation: if the register-file model is right, wide should win there.

Zen+ adds a wrinkle worth measuring rather than assuming. Zen and Zen+ execute
256-bit AVX2 operations as two 128-bit micro-operations, so the wide kernel may
gain no throughput from width itself while still halving instruction count and,
more importantly, fitting the register file. Whether that nets out positive is
exactly the kind of thing this project measures instead of predicting.

### P3 — the capability database

Data collection is a separate research task, owned by the project maintainer:
vector register counts, datapath widths, and micro-op splitting behaviour per
microarchitecture, from vendor documentation. This entry specifies only the
*mechanism* that consumes it, so the data can land without redesign.

What the JVM actually exposes is thin: `os.arch` and
`IntVector.SPECIES_PREFERRED.vectorBitSize()`. Register count is not available
through any API and must be derived from architecture plus width.

```
CpuCapabilities   arch, preferred vector bits, derived register count/width
KernelSelector    (capabilities, remaining bytes, pending batch) -> kernel
```

Three rules keep the table honest:

1. **Every entry cites a ledger experiment.** An unmeasured (arch, width) pair
   selects the most conservative *measured-good* kernel, currently the
   four-chunk E011 kernel, never an extrapolation. Unknown hardware produces a
   task, not a gamble.
2. **`-Dfastblake.kernel=` overrides selection** for benchmarking and for
   embedders who know their fleet. `./gradlew contenders` prints the selected
   kernel and the reason it was selected.
3. **`./gradlew dispatchAudit` runs every available kernel on the current
   machine and reports whether the selector's choice actually won.** This turns
   the table from an assumption into a testable claim and is what keeps it
   correct as JDKs change: E024 already showed a ~15% swing between Temurin 25
   and a locally built JDK 28 on identical code.

Conformance requirement: every kernel reachable through the selector runs the
full official-vector suite, not only the default one.

### The re-ranked priorities

The 2-fork long run (Results table in README.md) puts one-shot at 8 MiB within
14% of Rust while streaming is 61% behind and 64-byte one-shot is *behind
Commons*. Compression is no longer where the gap lives.

| rank | target | current (M5) | expected | rationale |
|---|---|---|---|---|
| **P0** | small-input one-shot | 199 MiB/s, 0.38x Commons | ~500, >= Commons | shipping worse than the baseline it exists to beat |
| **P1** | streaming batching for E024 | 916 MiB/s, 39% of Rust | ~2000 | E016's mechanism, already proven at four chunks |
| **P2** | size ladder | 16 KiB at 59% of peak | ~1800 | all-or-nothing dispatch wastes the mid range |
| **P3** | capability dispatch | opt-in only | default-on | makes P0-P2 reach users |

P1, P2 and P3 are one design. The ladder *is* the dispatch: each call selects on
`(capabilities, remaining bytes, pending batch state)`. Implementing them
separately would build the same decision three times.

### P0 — the small-input regression

Measured root cause, M5, 64-byte input, isolated JVM:

| | time | allocated |
|---|---:|---:|
| one-shot, scalar default | 368.0 ns | **3,400 B/op** |
| one-shot, dual kernel enabled | 360.9 ns | **4,200 B/op** |
| reused instance | 122.5 ns | 288 B/op |

Hashing 64 bytes allocates 3.4 KB: **53 bytes of garbage per input byte**, 66
with a vector kernel enabled. Construction is 245 ns of the 368 ns total, 67%.
The reused-instance figure of 498 MiB/s proves compression is not the problem.

Eagerly allocated per hasher, whatever the input size:

| field | bytes | needed when |
|---|---:|---|
| `cvStack` (`int[432]`) | ~1,744 | input exceeds one chunk |
| `chunk` (`byte[1024]`) | ~1,040 | always, but avoidable for one-shot |
| `vectorPacked` + `vectorCvs` | ~800 | a vector kernel actually runs |
| `vectorPending` (`byte[4096]`) | ~4,112 | streaming, scratch kernel only |
| `scratchState/Words/Cv`, `key` | ~256 | always |

**This is a gap in the allocation gate, not just in the code.** The gate has
been mandatory since E010 but has only ever been run at 8 MiB, where fixed
setup divides away to 0.0007 B per input byte. The same code is at 53 B per
input byte at 64 bytes. A gate that only samples the size at which it cannot
fail is not a gate. Amend the protocol: **run the allocation gate at 64 B and
1 KiB as well as 8 MiB.**

P0 is two independent changes, measured separately per the one-idea-at-a-time
rule:

* **P0a — lazy allocation.** Allocate `cvStack`, the vector scratch and
  `vectorPending` on first use rather than in the constructor. Contained, and
  it benefits every call shape that constructs a hasher.
* **P0b — dedicated single-chunk path.** For inputs of at most 1024 bytes there
  is no tree, no CV stack and no vector batch: compress the blocks and finalize.
  Guide section 17.1; Blake3.NET does exactly this. Note the benchmark's
  `oneShot` reaches FastBlake through `newHasher()`, not the static
  `FastBlake.hash`, so P0b only shows up in the measured number if the harness
  path benefits too.

Gates for both: full official-vector conformance; the allocation gate at 64 B,
1 KiB and 8 MiB; and paired throughput at 64 B and 8 MiB to prove no regression
at size.

### E025 P0a result — lazy allocation: 2.05x at 64 bytes

Date: 2026-08-07
Environment: **A** (Apple M5). JMH figures are the project toolchain (Temurin
25); allocation figures are the custom JDK 28 driver.

Change: `cvStack`, `vectorPacked`, `vectorCvs` and `vectorPending` move from
constructor-time initialisation to allocation on first use, behind private
accessors. No algorithm, kernel, or dispatch change.

**Conformance**: 542 tests, run four times — default dispatch and each of
`scratchChunkVector`, `dualChunkVector`, `wideChunkVector` forced. All pass.
Every kernel path was re-checked because lazy allocation changes when the
scratch arrays come into existence on all of them.

**Allocation gate**, now run at the three sizes the protocol amendment
requires:

| 64-byte one-shot | before | after |
|---|---:|---:|
| scalar default | 3,400 B/op | **1,656 B/op** |
| dual kernel enabled | 4,200 B/op | **1,656 B/op** |
| time | 368.0 ns | **184.6 ns** |

At 8 MiB, allocation remains 0.00 B per input byte on both paths, and
throughput is unchanged: 2395.6 MiB/s dual, 869.8 MiB/s scalar in the same
driver. Enabling a vector kernel no longer costs anything at small sizes — the
0.8 KB of vector scratch was previously allocated even when the input could
never reach a vector kernel, and the two configurations now allocate
identically.

**Throughput**, JMH, `oneShot`, 2 forks x 5 warmup x 5 measurement:

| size | commons | java-cpu before | java-cpu after |
|---|---:|---:|---:|
| 64 B | 530 | 199 (0.38x) | **408 (0.77x)** |
| 1 KiB | 567 | 850 (1.52x) | 903 (1.59x) |
| 8 MiB | 549 | 2148 (3.92x) | 2147 (3.91x) |

**2.05x at 64 bytes, no measurable change at 8 MiB.**

Decision: keep. This is a strict improvement with no size regression and no
behavioural change.

**P0 is not finished.** At 0.77x Commons the 64-byte one-shot is still slower
than the baseline FastBlake exists to beat, so P0b remains open. The remaining
1,656 B/op is now dominated by:

| remaining allocation | bytes |
|---|---:|
| `chunk` (`byte[1024]`) | ~1,040 |
| `doFinalize` temporaries (`state`, `words`, `rightCv`) | ~208 |
| `scratchState`/`scratchWords`/`scratchCv` | ~208 |
| `key`, object header | ~112 |

P0b's single-chunk path addresses the largest of these directly: for inputs of
at most one chunk there is no reason to copy into `chunk` at all, and no reason
to build tree scratch. The `doFinalize` temporaries are a separate, smaller,
independently measurable change and should not be bundled with it.

Comment: this also closes the protocol gap the plan identified. The allocation
gate had been mandatory since E010 but only ever run at 8 MiB, where 3.4 KB of
fixed setup divides away to 0.0007 B per input byte and cannot fail. The same
code was at 53 B per input byte at 64 bytes for fifteen experiments without
anyone seeing it.

### E025 P0b result — single-chunk one-shot path: P0 target met

Date: 2026-08-07
Environment: **A** (Apple M5). JMH on the project toolchain; allocation on the
custom JDK 28 driver.

Change: an input of at most one chunk is its own root node, so it needs no
chaining-value stack, no 1 KiB chunk copy and no tree machinery. `FastBlake`
gains `hash(byte[], int offset, int length, int outputLength)`, which takes that
path directly from the caller's array with no hasher instance. Plain hashing
mode only, which is all the static one-shot entry points expose. Larger inputs
fall through to the ordinary incremental path unchanged.

**Harness change, stated explicitly because it moves a baseline.** The
benchmark's `oneShot` shape reaches a contender through `Blake3Engine.hash`,
whose default implementation builds a hasher. `RustEngine` has always overridden
it to use the crate's one-shot native entry point. `JavaCpuEngine` now overrides
it too — and so does `CommonsCodecEngine`, using Commons Codec's own static
`Blake3.hash`, so FastBlake is not credited with a fast path its baseline was
denied. In the event the Commons figures did not move materially (528-568
across sizes, against 525-567 before), so the comparison is unchanged in
substance; but the change was made before the measurement, not after seeing it.

**Conformance**: 542 tests, default dispatch and with `dualChunkVector` forced.
Both pass. The suite exercises `engine.hash(...)` over all 35 official vectors,
so the new path is covered at length 0, at every boundary below one chunk, and
above it where it must fall through.

**Allocation gate**, static one-shot path:

| input | allocated | throughput |
|---|---:|---:|
| 64 B | **256 B/op** | 559.6 MiB/s |
| 1 KiB | **256 B/op** | 961.0 MiB/s |
| 8 KiB (falls through, control) | 3,816 B/op | 934.3 MiB/s |

At 64 bytes: 3,400 B/op originally, 1,656 after P0a, **256 after P0b** — a 13x
reduction, and 4 bytes per input byte where the original code was at 53.

**Throughput**, JMH `oneShot`, 2 forks x 5 warmup x 5 measurement:

| size | commons | rust | before P0 | after P0a | after P0b |
|---|---:|---:|---:|---:|---:|
| 64 B | 528 | 828 | 199 (0.38x) | 408 (0.77x) | **538 (1.02x)** |
| 1 KiB | 568 | 1306 | 850 (1.52x) | 903 (1.59x) | **939 (1.65x)** |
| 16 KiB | 554 | 2478 | 1269 | 1271 | 1271 (2.30x) |
| 8 MiB | 558 | 2497 | 2148 | 2147 | 2140 (3.84x) |

**P0 is complete: 2.7x at 64 bytes, and FastBlake is no longer slower than the
baseline it exists to beat at any measured size.** Nothing above one chunk
moved, which is the intended blast radius.

Decision: keep. Remaining gap at 64 bytes is to Rust (538 against 828, 65%), not
to Commons.

Comment: the two halves of P0 attacked different costs and the split was worth
keeping. P0a removed eager per-hasher buffers and helped every call shape that
constructs a hasher, including `reusedInstance` construction and the streaming
path. P0b removed the remaining 1 KiB chunk copy and the tree scratch for the
single-chunk case only. Bundling them would have hidden that P0a alone gets to
0.77x and is not sufficient.

Next: P1 (streaming batching for the E024 kernel) is now the largest remaining
gap at 39% of Rust, followed by P2's size ladder, which the 16 KiB row above
shows untouched at 2.30x Commons against 3.84x at 8 MiB.

### E025 P1 result — streaming batching for the eight-chunk kernel: 2.26x

Date: 2026-08-07
Environment: **A** (Apple M5). JMH on the project toolchain; allocation on the
custom JDK 28 driver.

Change: E016 gave the four-chunk kernel a retained pending batch so fragmented
`update()` calls could still form a complete SIMD batch. That was hard-coded to
four chunks. It is now generalised: one constant, `VECTOR_STREAM_CHUNKS`,
describes the batch of whichever kernel is selected, the retained buffer sizes
itself from it, and `updateVectorStream` dispatches to the kernel. The
eight-chunk kernel gets 8192-byte batching with no duplicated finalization
logic.

**Two regressions found and fixed while gating this**, both pre-existing and
both amplified by the larger batch:

* `reset()` zeroed the entire retained buffer. Bytes past `vectorPendingLength`
  are never read, so this was pure memset — 4 KiB per reset before, 8 KiB after
  the batch doubled. It now zeroes only the valid prefix.
* `doFinalize` copied the CV stack and allocated a 1 KiB chunk buffer on every
  finalize whenever streaming was enabled, even when the retained batch was a
  single partial chunk with no CVs to push. A batch of at most one chunk *is*
  the final chunk and needs neither. This had been costing the four-chunk
  kernel **3,072 B/op on every small streamed hash since E016** and nobody had
  measured it, because the allocation gate only ran at 8 MiB.

Update-path allocation at 64 bytes, after the fixes:

| kernel | before P1 | after P1 |
|---|---:|---:|
| scalar | 288 B/op | 288 B/op |
| four-chunk | 3,072 B/op | **288 B/op** |
| eight-chunk | 3,072 B/op | **288 B/op** |

**Conformance**: 542 tests, default dispatch and with each of
`scratchChunkVector` and `dualChunkVector` forced. All pass. The suite's
fragmented-update cases (chunk sizes 1/7/63/64/65/1023/1024/1025 across all 35
vectors) are exactly the path this change rewrites.

**Throughput**, JMH `streaming4k`, 2 forks x 5 warmup x 5 measurement:

| size | commons | rust | before P1 | after P1 |
|---|---:|---:|---:|---:|
| 64 B | 473 | 849 | 495 | 476 (1.01x) |
| 16 KiB | 556 | 2354 | 909 | **1240 (2.23x)** |
| 8 MiB | 546 | 2336 | 911 | **2054 (3.76x)** |

**2.26x at 8 MiB. Streaming goes from 39% of Rust to 88%** — now marginally
*better* relative to Rust than the one-shot shape is (85.4%), because Rust's
own streaming path also gives up throughput at the 4 KiB boundary.

Decision: keep. P1 closes what was the largest remaining gap in the ladder.

Comment: the two shapes have essentially converged on this machine — 2140
one-shot against 2054 streaming, where before P1 it was 2140 against 911. The
remaining structural gap is P2's size ladder: 16 KiB reaches only 1240,
because the eight-chunk kernel needs 8192 bytes to engage and there is still no
rung between it and scalar.

### E025 P2 result — ladder rung: +21% mid-size on two shapes, none on the third

Date: 2026-08-07
Environment: **A** (Apple M5).

Change: a second rung in the update ladder. When a full eight-chunk batch does
not remain but more than four chunks do, use the four-chunk kernel instead of
falling through to the scalar tail. Guarded so it only applies when the selected
batch is wider than four chunks.

Motivation, from tracing 16 KiB through the post-P1 code: the eight-chunk kernel
consumed 8 chunks, the remaining 8192 bytes were retained, and finalization then
compressed **7 of those 8 chunks one at a time in scalar**. Nearly half the
input ran at scalar speed.

**Conformance**: 542 tests, default and with each of `scratchChunkVector` and
`dualChunkVector` forced. All pass.

**Throughput**, JMH, 2 forks x 5 warmup x 5 measurement:

| size | shape | post-P1 | post-P2 |
|---|---|---:|---:|
| 16 KiB | oneShot | 1240 | **1494 (+20.5%)** |
| 16 KiB | reusedInstance | 1247 | **1520 (+21.9%)** |
| 16 KiB | streaming4k | 1238 | 1242 (unchanged) |
| 8 MiB | oneShot | 2119 | 2126 |
| 8 MiB | reusedInstance | 2135 | 2131 |
| 8 MiB | streaming4k | 2076 | 2076 |

Full mid-size curve after P2:

| size | oneShot | reusedInstance | streaming4k |
|---|---:|---:|---:|
| 4 KiB | 855 | 881 | 878 |
| 8 KiB | 1153 | 1173 | 881 |
| 16 KiB | 1494 | 1520 | 1242 |
| 256 KiB | 2050 | 2082 | 1991 |
| 8 MiB | 2126 | 2131 | 2076 |

Decision: keep. +21% mid-size on two of three shapes with no change at 8 MiB.

**P2 is incomplete, and the measurement says exactly why.** The new rung fires
only on the direct path, which requires `vectorPendingLength == 0 && remaining >
4 * CHUNK_LEN`. Under 4 KiB streaming updates `remaining` is never more than
4096, so the rung never fires: input accumulates in the retained buffer and
finalization still drains up to 7 chunks scalar. That is precisely why
`streaming4k` at 8 KiB and 16 KiB did not move while the other two shapes gained
21%.

**P2b**: apply the same ladder inside the finalization drain. When the retained
batch holds four or more whole non-final chunks, compress them four at a time
with the four-chunk kernel instead of one at a time with the scalar path. This
is a separate change to a separate code path and should be measured separately;
`streaming4k` at 8-16 KiB is its target and its control.
