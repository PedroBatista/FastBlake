# FastBlake

A pure-JVM BLAKE3 implementation, optimised for throughput.

Status: **CPU implementation and comparison harness complete; SIMD kernel
validated on two architectures and awaiting streaming support before
promotion.** FastBlake implements the full BLAKE3 API in dependency-free Java.
The allocation-free scalar kernel is the production default and the correctness
baseline. An opt-in chunk-parallel Vector API kernel reaches 3.25× it on large
contiguous inputs, and stays opt-in only because 4 KiB streaming updates still
fall back to scalar. GPU offload remains planned work.

```
./gradlew contenders   # what can run here, and why anything can't
./gradlew test         # conformance: every contender vs. the official vectors
./gradlew jmh          # the comparison table
```

## The contenders

| id | what it is | role |
|---|---|---|
| `commons` | Apache Commons Codec `Blake3`, scalar Java | The floor. Always available. |
| `rust` | Reference [`blake3`][crate] crate via FFM, SIMD, single-threaded | The ceiling. Optional. |
| `java-cpu` | FastBlake CPU — allocation-free scalar Java | Implemented and always available. |
| `java-gpu` | FastBlake GPU — device offload | **Planned.** |

Everything is measured through one interface, `Blake3Engine`, so all four face
identical call shapes on identical bytes. Adding a contender is one class plus
one line in `Contenders` — no test or benchmark changes.

**Absence is never failure.** A contender that cannot run here — no Rust
toolchain or no GPU — reports *why* and is skipped. A machine
with no Rust gets the same green build with one fewer column. That property is
tested, not assumed: breaking the crate build on purpose leaves `test` and `jmh`
passing with `rust` correctly reported as unavailable.

Commons Codec, JMH and the harness are all off the `main` source set. The
shipped jar has no dependencies and never will.

## Correctness first

A benchmark between implementations that compute different functions measures
nothing. So every contender is held to the **official BLAKE3 test vectors**,
vendored verbatim from the reference implementation:

```
src/harness/resources/blake3/test_vectors.json
  <- https://github.com/BLAKE3-team/BLAKE3/blob/master/test_vectors/test_vectors.json
```

35 input lengths from 0 to 102,400 bytes, each checked in all three modes
(`hash`, `keyed_hash`, `derive_key`) at 131 bytes of extended output — long
enough to exercise the XOF path past the first output block. On top of that,
per contender:

- the one-shot 32-byte digest is a prefix of the extended output (this also
  exercises Rust's separate one-shot native entry point),
- every case re-hashed in chunk sizes 1/7/63/64/65/1023/1024/1025, straddling
  the 64-byte block and 1024-byte chunk boundaries where buffering bugs live,
- finalizing twice yields the same bytes and does not consume the state,
- a short output is a prefix of a long one,
- `reset()` returns to the initial state,
- input and output offsets are honoured without clobbering neighbouring bytes.

The suite covers hundreds of cases with Rust present and remains fully usable
without a native toolchain.

## Benchmarks

Three call shapes, because they stress different things:

| benchmark | what it isolates |
|---|---|
| `oneShot` | Realistic call: fresh hasher per buffer. Includes setup and allocation. |
| `reusedInstance` | `reset()`-reuse. Raw compression throughput. |
| `streaming4k` | 4 KiB incremental updates — the file/socket shape, which punishes anything needing the whole input up front to parallelise. |

Sizes sweep `64, 1024, 16384, 262144, 4194304, 8388608`. 64 B and 1 KiB sit at
or below BLAKE3's 1024-byte chunk boundary where per-call overhead dominates;
16 KiB is the smallest input that can fill a 16-lane SIMD batch; the larger
sizes measure steady-state throughput, with 8 MiB representing the project's
typical workload.

```bash
./gradlew jmh                                          # every available contender
./gradlew jmh -P'jmh.args=oneShot -p size=1048576 -f1' # one case, fast
./gradlew jmh -P'jmh.args=-p impl=rust,commons'        # pick contenders
./gradlew jmh -P'jmh.args=-rf json -rff build/x.json'  # machine-readable
```

JMH prints ns/op; the runner appends a MiB/s table with speedups against the
`commons` baseline. Naming an unavailable contender explicitly fails fast with
the reason, rather than running for ten minutes first.

### Threading

**Every number below is single-threaded, on both sides**, and that is a
deliberate choice rather than an oversight:

- JMH runs one thread (`@State(Scope.Thread)`, no `@Threads`).
- The `blake3` crate is compiled with only `default` + `std` — the `rayon`
  feature is off, so `update` does not fan out across cores.
- Commons Codec is scalar and single-threaded anyway.

So the comparison is honest as far as it goes: one core against one core, which
is the right way to judge implementation quality. But it leaves two axes
unmeasured, and one of them matters a lot for what comes next.

1. **Intra-hash parallelism** — one hash spread over many cores. BLAKE3's tree
   structure makes every 1 KiB chunk independent, so this scales close to
   linearly; it is the crate's headline feature, exposed as `update_rayon`.
2. **Concurrent throughput** — many independent hashes on many threads. Already
   supported: `-t N` works today, since each JMH thread gets its own buffers and
   hasher. At 1 MiB with `-t 4`, per-thread throughput held at 516 MiB/s
   (`commons`) and 2334 MiB/s (`rust`), i.e. it scales with cores rather than
   contending.

The catch is the GPU contender. A GPU hashing one buffer across thousands of
device threads, compared against a deliberately single-threaded CPU number, is
not a comparison — it is a handicap match that the GPU wins by construction.
Before `java-gpu` lands, the ladder needs a multithreaded rung: a separate
`rust-mt` contender built with the `rayon` feature, so the ceiling is visible in
both regimes and the GPU is measured against a CPU that is also using the whole
machine.

### Results

Apple M5 (10 core), Temurin JDK 25.0.4, Commons Codec 1.22.0, `blake3` crate
1.8.5. 2 forks × 5×1s measurement iterations, single-threaded. MiB/s, higher is
better; x-factor against the `commons` baseline.

| size | `oneShot` commons | `oneShot` rust | `reusedInstance` commons | `reusedInstance` rust | `streaming4k` commons | `streaming4k` rust |
|---:|---:|---:|---:|---:|---:|---:|
| 64 B | 525 | 820 (1.56×) | 459 | 844 (1.84×) | 450 | 838 (1.86×) |
| 1 KiB | 562 | 1274 (2.27×) | 558 | 1257 (2.25×) | 522 | 1262 (2.42×) |
| 16 KiB | 545 | 2384 (4.37×) | 552 | 2374 (4.30×) | 549 | 2256 (4.11×) |
| 256 KiB | 552 | 2386 (4.32×) | 542 | 2388 (4.41×) | 539 | 2254 (4.18×) |
| 4 MiB | 543 | 2388 (4.40×) | 541 | 2387 (4.41×) | 538 | 2251 (4.19×) |
| 8 MiB | 544 | 2420 (4.45×) | 538 | 2402 (4.46×) | 534 | 2240 (4.19×) |

Raw JSON: `build/jmh-contenders.json`. Re-measure on your own machine before
drawing conclusions — and that is not a formality. E012 re-ran the ledger on an
Intel N97 (AVX2, Linux, Temurin 25+36) and two of the headline ratios above do
not transfer:

| ratio at 8 MiB | Apple M5 (NEON) | Intel N97 (AVX2) |
|---|---:|---:|
| FastBlake scalar vs Commons | 1.61x | 1.20x |
| FastBlake E011 SIMD vs its own scalar | 1.77x | 2.54x |
| FastBlake E011 SIMD as a fraction of Rust | 63% | 38% |
| Rust vs Commons | 4.5x | 7.9x |

Correctness, the allocation gate, and C2's escape-analysis behaviour *did*
transfer, to three significant figures. What did not transfer is anything whose
mechanism depends on core width: the scalar kernel's instruction-level
parallelism has less to exploit on a narrow E-core, and the SIMD kernel's
hard-coded 128-bit species uses only half of an AVX2 machine while the Rust
crate dispatches to 8-lane AVX2. See `reference/performance/experiments.md`
§ E012.

What this says about where the work is:

- **Commons Codec is flat at ~540 MiB/s from 1 KiB to 8 MiB. Rust triples from
  820 to about 2400.** Rust's curve is SIMD engaging as the input grows enough to fill
  a lane batch — it saturates by 16 KiB, exactly where 16 chunks first become
  available. Commons never engages anything, because there is nothing to engage.
  That flat line is the headroom, and closing it is the entire point of
  `java-cpu`.
- **The gap is 4.4× at size, but only 1.6× at 64 B.** Small inputs are dominated
  by per-call setup, not compression, and there SIMD has nothing to work with.
  A Vector API implementation should expect to reach parity quickly on large
  buffers and struggle to justify itself on tiny ones.
- **`reset()` reuse buys nothing** — it is *slower* than a fresh hasher at 64 B
  for both contenders. Per-call cost is setup, not allocation, so a
  zero-allocation API is not where the small-input win lives.
- **Streaming in 4 KiB pieces costs Rust ~6% at size and Commons nothing.**
  Rust gives up a little chunk-batching width at the piece boundary; the scalar
  implementation has no width to give up.

## How the Rust contender is wired

`cargo build --release` produces a `cdylib` from `native/rust-blake3`, which the
JVM calls through the Foreign Function & Memory API. It is measured *in process*
— shelling out to `b3sum` would measure process startup and file I/O instead of
the hash.

Downcalls use `Linker.Option.critical(true)`, so heap `byte[]` buffers pass
straight through with no copy: Rust hashes the very same array the Java
contenders do. The cost is that GC cannot run during a call — fine for a
benchmark harness, not something to imitate in production code.

The crate pins `panic = "abort"` and leaves the `rayon` feature off, so it races
one core against one Java thread. `fb_abi_version` is checked at link time, so a
stale library is rejected rather than silently mismeasured.

**Requires cargo 1.85 or newer.** `blake3` 1.8.5 pulls in `cpufeatures` 0.3.0,
which needs edition 2024. On an older cargo the crate fails to build and the
contender is skipped with the cargo error as its reason — the build stays green,
but the ceiling column silently disappears. `rustup update stable` fixes it.

Gradle's `cargoBuild` task is best-effort: missing cargo, a failed compile, or a
platform that produces no shared library each record a reason in
`build/native/rust-status.txt` and clear the staged library. The Java side treats
that directory as authoritative — a stale artifact in the crate's own `target/`
will **not** resurrect a contender the current build failed to produce.

## Current FastBlake CPU implementation

`FastBlake` supports ordinary hashing, keyed hashing, context-based key
derivation, arbitrary-length XOF output, incremental updates, repeatable
finalization and reset. Its update hot path reuses flat primitive scratch and a
flat chaining-value stack; completed chunks allocate nothing. The final chunk
is deliberately retained until finalization so the correct `ROOT` node remains
available for XOF output.

A focused 8 MiB run (1 fork, 3 warmup and 3 measurement iterations) measured
894 MiB/s one-shot, 883 MiB/s with instance reuse, and 874 MiB/s with 4 KiB
streaming updates: 1.61–1.62× Commons Codec on this machine. The production
compressor uses 16 named integer locals and seven fully expanded rounds; the
former array-and-loop compressor remains selectable with
`-Dfastblake.experimental.legacyScalar=true` for diagnostic comparisons. The
Vector API `hash_many` kernel over independent 1 KiB chunks has since landed as
E011, described below; the remaining CPU milestones are reducing the measured
SIMD kernel cost and confirming automatic dispatch across architectures. E016
completed the streaming batch buffer and promoted the scalar little-endian
VarHandle loader; direct-input bypass and message-local experiments remain
possible follow-ups. The first array-based Vector API
experiment is retained behind `-Dfastblake.experimental.vector=true` but is
disabled by default because measurement showed a severe regression. A second
intra-block row-vector experiment is retained behind
`-Dfastblake.experimental.blockVector=true` and is also disabled after indexed
gathers and diagonal shuffles proved even slower. See
`reference/performance/experiments.md` for the results and follow-up design.
The later E010 allocation audit supersedes the causal explanations originally
attached to these Vector API results. The properties reached the JMH forks and
the vector branches ran, but the cross-chunk kernels allocated 159–255 heap
bytes per input byte because C2 failed to eliminate vector wrapper objects.
Their low throughput measures allocation and GC overhead, not the ceiling of
allocation-free SIMD; register-pressure and cache-layout conclusions from
E002–E008 are therefore unproven. The kernels remain opt-in diagnostic
artifacts, and future SIMD work starts with a mandatory per-fork allocation
gate.

E011 follows that gate with reusable primitive transposed-message scratch and a
compact seven-round loop. It is the first correct allocation-free SIMD win:
1584 MiB/s one-shot and 1569 MiB/s reused at 8 MiB, about 1.77x the production
scalar path and 63% of Rust on the Apple M5. **Those M5 figures predate E014 and
no longer describe the code in the tree**. A later focused current-code run
measured 1750 MiB/s one-shot and 1769 MiB/s reused; this is a one-fork quick
result rather than a long confirmation. E012 validated it on x86-64, where
it is a *larger* relative win — 2.54x the scalar path — while still passing the
allocation gate and the full official-vector suite. It remains opt-in behind
`-Dfastblake.experimental.scratchChunkVector=true`; E016 has now completed its
streaming batching, while a long environment A rerun and wider CPU coverage
remain before automatic dispatch.

E013 answered the width question E012 raised. `Blake3ChunkVectorWide` is E011's
kernel with the species and every derived stride taken from
`IntVector.SPECIES_PREFERRED`, so one chunk per lane means as many chunks as the
machine is wide; enable it with
`-Dfastblake.experimental.wideChunkVector=true`. It is correct, passes the
allocation gate at 0.00071 B/input byte, and does not hit the seven-round cliff
at eight lanes — and it is **3–4% slower** than the four-lane kernel on AVX2, so
it is not promoted. Doubling the width helped compression by only 5% per byte,
while the *scalar* byte-shift transpose — about 30% of the kernel, and untouched
by any amount of vector width — got 21% worse per byte from striding across
eight chunks instead of four.

E014 acted on that. Replacing the transpose's manual byte-shift word assembly
with a cached little-endian `VarHandle` read — 2.96× faster in isolation, and
endian-neutral, so no guard is needed — gained **+28% on the four-lane kernel
and +30% on the preferred-width one**, from a one-line loader change. At 8 MiB
the four-lane SIMD kernel now reaches 822 MiB/s one-shot: 3.25× the production
scalar path, 3.90× Commons, and 49% of Rust on this machine. Both remain opt-in
pending a long environment A confirmation and broader dispatch measurements.
The intuitive alternative — doing the transpose with vector loads and in-register
4×4 rearranges — was measured and **rejected at 2.3× slower** than the plain
VarHandle read. A fully expanded seven-round variant is retained only as
diagnostic evidence: it crosses a C2 cliff and allocates
43,776 bytes per block invocation, while the compact loop allocates effectively
zero.

A pre-E014 follow-up on the Apple M5 found no regression from E013 itself: the
fixed and preferred-width kernels measured 1600/1598 and 1594/1592 MiB/s for
one-shot/reuse respectively. Both were four-lane kernels on NEON and differed by
less than 1%. Those runs used the old byte-shift loader and therefore do not
measure the current E014 code; the later current-code run measured 1750/1769
MiB/s one-shot/reused. Kernel selection is not automatic yet: the N97
result proves that `SPECIES_PREFERRED` is a capability signal, not a guarantee
that the widest kernel is fastest. Current width comparisons favor 128 bits on
both M5 and N97; other AVX2 cores and AVX-512 still require measurements.

E015 tested preferred-species parent compression and batched reduction of each
aligned four-leaf SIMD result. It is correct and allocation-free, but underfills
the four M5 lanes with only two parents and then one: 1713/1690 MiB/s versus the
1750/1769 baseline. It remains opt-in behind
`-Dfastblake.experimental.parentVector=true` as negative evidence. A future
parent experiment must aggregate at least eight leaves before reducing them.

E016 used the custom JDK 28 hdis build to compare Java and Rust on the Intel
N97. Rust's AVX2 kernel uses `vpshufb` for ROR8/ROR16 while Java emits
shift/shift/OR. Isolated shuffle probes were 2.17–2.27× faster, but inserting
even ROR16 into the full Vector kernel crossed an escape-analysis cliff and was
rejected. The accepted four-chunk pending buffer instead lifted 4 KiB streaming
from 361 to 747 MiB/s, matching Java's 747/779 MiB/s one-shot/reused class, with
fixed-size allocation and full conformance. The production scalar VarHandle
loader also improved one-shot/reused from 362/363 to 390/378 MiB/s, with
streaming neutral at 365 MiB/s. Rust remains ahead at 1561/1574/1027 MiB/s; the
next investigation is paired hardware counters and phase-level probes before
changing the large, spill-heavy compression kernel again.

The E016 follow-up on Apple M5 also passed. Current scratch-vector throughput is
1759 MiB/s one-shot, 1763 MiB/s reused and 1726 MiB/s streaming 4 KiB. Relative
to the pre-E016 1750/1769/907 control, contiguous input is unchanged within 1%
and streaming improves by about 90%. Allocation remains fixed-size with zero
collections. The production scalar path measured 923/919/921 MiB/s, a
directional 3–5% improvement over the earlier M5 quick run.

E017 decomposed the current SIMD kernel on the N97. A three-fork core-pinned
run measures Java at 801 MiB/s and Rust at 1599 MiB/s. Exact phase probes put
90.5% of Java kernel time in the seven compression rounds, 9.2% in
load/transpose, and 0.4% in CV extraction; their sum matches the complete
kernel within 0.1%. A smaller-live-set state-scratch boundary was allocation-free
but 56.8% slower and is retained only as a negative benchmark control. Linux
hardware counters then established the main mechanism: Java executes 4.28× as
many instructions and 61× as many branches as Rust, while sustaining 3.02 IPC
versus Rust's 1.43 and recording only 15% more cache misses. The remaining 2.03×
cycle gap is therefore instruction volume, not poor issue utilization or a
cache bottleneck. Details and reproducible commands are in the E017 result
document.

E018 rules out Java-source partial unrolling as the remedy. The best
allocation-free guarded 2x block removes only 2.5% of instructions in
isolation; its C2 body grows from 4.7 KiB to 20.9 KiB, and integration regresses
the exact four-chunk kernel by 52%. Production therefore retains the compact
seven-iteration loop; the next useful target is custom-JDK AVX2 lowering of
constant ROR8/ROR16 without expanding the Java Vector API graph.

E019 validates that compiler target. A custom JDK 28 lowers packed-int ROR8 and
ROR16 to one `vpshufb` each on AVX2 while leaving ROR12/ROR7 as
shift/shift/OR. All 542 forced SIMD tests pass with normal OSR, and allocation
remains at the profiler floor. On the N97 the exact four-chunk kernel drops from
4385 to 3601 ns and from 12,207 to 10,138 cycles; a diagnostic 8 MiB run improves
one-shot from 817 to 941 MiB/s and streaming from 788 to 932 MiB/s. Java is now
about 56% of pinned Rust on contiguous input and 87% on streaming. The change
is still an experimental HotSpot patch, not part of the shipped library; E020
below retests the 256-bit kernel under the new lowering.

E020 completes that retest. In an exact load/compress/extract probe, the
eight-lane kernel is 4.7% faster per byte than four lanes on the patched VM;
the same comparison is already 5.2% faster on the unmodified VM. The rotate
patch improves both widths by about 20%, rather than uniquely unlocking YMM.
A longer diagnostic reaches 1020 MiB/s one-shot at eight lanes, about 62% of
Rust, but this is not a public-library result: it requires the private VM, and
the only stock-JDK source expression tried for `vpshufb` allocated 205 MB per
8 MiB hash when integrated. Production therefore does not select either SIMD
kernel. E019/E020 are retained as evidence for an upstream HotSpot improvement;
FastBlake continues to require a stock-JDK, allocation-free win before
promotion.

E021 rules out missing x86 memory-operand folding as the explanation for the
remaining N97 gap. In both stock and E019-patched C2 code, 8 of the compact
round body's 16 static message loads fold into `vpaddd` and 8 remain separate
`vmovdqu` instructions: 56/56 dynamically per block. Folding the remaining
half could remove only 2.36% of the stock exact kernel's instructions and 2.7%
of the measured 8 MiB Java/Rust instruction excess. The N97's 4.28× instruction
ratio would still be about 4.19×. This x86 result says nothing about the Apple
M5 gap, which still needs its own AArch64 counters and disassembly.

The primitive probe found that C2 does intrinsify the Vector API, but endian
MemorySegment vector loads are about 25× slower than direct heap ByteVector
loads plus reinterpretation on this runtime. E008 applied that load change alone
to the best four-chunk layout. Correctness passed and the isolated loads stayed
about 25× faster, but the complete kernel allocated roughly 159 bytes per input
byte, invalidating its throughput as a SIMD comparison. It remains available
behind `-Dfastblake.experimental.heapChunkVector=true` on little-endian systems;
the production dispatch remains scalar. Details are in the experiment ledger.

The GPU contender comes later. BLAKE3 suits a GPU well — the tree structure
makes every 1 KiB chunk independent, so a large input decomposes into thousands
of parallel chunk compressions with only a small parent-node merge left. Expect
the mirror image of the CPU contenders: badly beaten on small inputs where
kernel launch and host-to-device transfer dominate, competitive only once the
input amortises both. Transfer cost stays inside the measured region — a number
that excludes it would not describe anything a caller can actually get.

[crate]: https://crates.io/crates/blake3
