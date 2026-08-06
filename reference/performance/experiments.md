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

### Why: the bottleneck is the scalar transpose, not the vector width

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

Next experiment: vectorize the transpose. E007 measured
`ByteVector.fromArray(...).reinterpretAsInts()` at roughly 25x an endian
`MemorySegment` load, and E008 retained it; the transpose is exactly the place
that loader belongs, loading contiguous 16-byte rows per chunk and transposing
in registers rather than assembling words a byte at a time. Gate on allocation
per rung, and measure it at both widths — a cheaper transpose changes the
width trade-off, and may make E013 win where it currently loses.

### Evaluation of the E013 follow-up ideas

The ideas are ordered by evidential value, not implementation convenience.

1. **Vectorize the input load/transpose: proceed.** This directly attacks the
   largest measured non-vector component. Implement it as new opt-in kernels at
   explicit 128- and 256-bit widths, preserving E011 and E013 as controls. Start
   with contiguous little-endian `ByteVector.fromArray(...).reinterpretAsInts()`
   loads and register rearrangement. Do not replace the endian-neutral path or
   claim a win until correctness, allocation, and 8 MiB throughput gates pass on
   both M5 and N97. Benchmark the load/transpose rung alone and the complete
   kernel: improving the isolated rung without improving the hash is insufficient.

2. **Retest register pressure after the transpose changes: defer.** The existing
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

5. **Streaming batching remains independent and high-value.** Both SIMD kernels
   take the scalar route for 4 KiB updates, so neither the width experiment nor a
   faster transpose improves that workload until complete chunks are buffered
   into SIMD-sized batches. Keep this as a separate experiment so buffering cost
   is not confused with compression improvements.

### E013 follow-up — Apple M5 regression and dispatch audit

Date: 2026-08-06
Environment: **A** (Apple M5, NEON 128-bit, preferred species = 4 int lanes)

The current `main` revision after the N97 work was recompiled and the full
official-vector suite passed with `wideChunkVector` enabled. Focused 8 MiB
runs, one fork with 3 warmup and 3 measurement iterations:

| shape | E011 fixed 128-bit | E013 preferred-width | original E011 quick run |
|---|---:|---:|---:|
| one-shot | 1600 MiB/s | 1594 MiB/s | 1584 MiB/s |
| reused | 1598 MiB/s | 1592 MiB/s | 1569 MiB/s |
| streaming 4 KiB | 902 MiB/s | 900 MiB/s | 874 MiB/s |

Assessment: no M5 regression. E011 and E013 both select four lanes here and
differ by less than 1%, well inside the protocol's 3% inconclusive band. The
small improvement over the historical run is ordinary run-to-run/JIT/thermal
variation, not a claimed optimization.

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
3. Re-measure E011+E014 and E013+E014 on environment A. The transpose was 30% of
   the kernel on x86-64; its share on the M5 is unmeasured, and E013's width
   verdict may differ where `SPECIES_PREFERRED` is 128-bit and the wide and
   narrow kernels are the same thing.

### E014 effect on earlier recorded figures

E011's environment A results — 1584 MiB/s one-shot and 1569 MiB/s reused, "about
1.77x the production scalar path and 63% of Rust" — were measured with the
manual byte-shift transpose that E014 has now removed from
`Blake3ChunkVectorScratch`. Those figures stand as written for the kernel as it
existed then, per this ledger's no-rewrite rule, but **they no longer describe
the code in the tree**. The current kernel's environment A throughput is
unmeasured. The same applies to the README's 63%-of-Rust figure and to E012's
environment A column wherever it quotes E011.

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
  own gap by measuring E002 (60.03) and E003 (99.61): **every Vector API kernel
  in this project except E011 is an allocating kernel**, so no E002–E008 causal
  explanation survives.
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
- **E011's standing against Rust.** 63% there, 38% here before E014. The cause
  was mechanical: `SPECIES_128` is full width on NEON and half width on AVX2,
  while the crate dispatches to `blake3_hash_many_avx2`.

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
3. **Re-measure on environment A.** E011's recorded M5 figures predate E014 and
   no longer describe the code. E013's width verdict is also untested where
   `SPECIES_PREFERRED` is 128-bit.
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
