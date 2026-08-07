# FastBlake

A pure-JVM BLAKE3 implementation, optimised for throughput.

Status: **CPU implementation and comparison harness complete; SIMD kernels
validated on two architectures and awaiting streaming support before
promotion.** FastBlake implements the full BLAKE3 API in dependency-free Java.
The allocation-free scalar kernel is the production default and the correctness
baseline. Two opt-in chunk-parallel Vector API kernels sit above it: a
four-chunk kernel, and an eight-chunk interleaved one that reaches **2148 MiB/s
one-shot at 8 MiB on the Apple M5 — 85.8% of the reference Rust crate** and
3.9× Commons Codec. Both stay opt-in because 4 KiB streaming updates still
fall back to scalar, and because the eight-chunk kernel has not yet been
validated on x86-64. GPU offload remains planned work.

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
| `java-cpu` | FastBlake CPU — allocation-free scalar Java, plus opt-in chunk-parallel Vector API kernels | Implemented and always available. |
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
1.8.5. 2 forks × 5 warmup × 5×1s measurement iterations, single-threaded, all
three contenders measured in one session. MiB/s, higher is better; x-factor
against the `commons` baseline. `java-cpu` is FastBlake with the E024
eight-chunk kernel enabled (`-Dfastblake.experimental.dualChunkVector=true`).

**`oneShot`** — fresh hasher per buffer:

| size | commons | rust | java-cpu |
|---:|---:|---:|---:|
| 64 B | 525 | 834 (1.59×) | **199 (0.38×)** |
| 1 KiB | 559 | 1309 (2.34×) | 850 (1.52×) |
| 16 KiB | 544 | 2480 (4.55×) | 1269 (2.33×) |
| 256 KiB | 554 | 2503 (4.51×) | 2058 (3.71×) |
| 4 MiB | 548 | 2507 (4.57×) | 2142 (3.91×) |
| 8 MiB | 548 | 2503 (4.57×) | 2148 (3.92×) |

**`reusedInstance`** — `reset()` reuse:

| size | commons | rust | java-cpu |
|---:|---:|---:|---:|
| 64 B | 422 | 873 (2.07×) | 458 (1.09×) |
| 1 KiB | 524 | 1315 (2.51×) | 921 (1.76×) |
| 16 KiB | 498 | 2473 (4.97×) | 1286 (2.59×) |
| 256 KiB | 512 | 2506 (4.90×) | 2081 (4.07×) |
| 4 MiB | 520 | 2506 (4.82×) | 2147 (4.13×) |
| 8 MiB | 494 | 2498 (5.05×) | 2149 (4.35×) |

**`streaming4k`** — 4 KiB incremental updates:

| size | commons | rust | java-cpu |
|---:|---:|---:|---:|
| 64 B | 423 | 854 (2.02×) | 467 (1.10×) |
| 1 KiB | 529 | 1308 (2.47×) | 926 (1.75×) |
| 16 KiB | 501 | 2367 (4.72×) | 916 (1.83×) |
| 256 KiB | 505 | 2362 (4.68×) | 917 (1.82×) |
| 4 MiB | 532 | 2351 (4.42×) | 916 (1.72×) |
| 8 MiB | 507 | 2349 (4.64×) | 916 (1.81×) |

Three things this exposes that the 8 MiB headline hides:

- **`java-cpu` is 2.6× *slower* than Commons on a 64-byte one-shot** (199 vs
  525). The same input under `reusedInstance` reaches 458, so this is per-hasher
  construction cost, not compression. It is the worst number in the project and
  the one a general-purpose library would be judged on first.
- **The eight-chunk kernel needs 8 KiB before it engages at all**, so 16 KiB
  reaches only 1269 MiB/s — 59% of its 8 MiB rate — and does not saturate until
  256 KiB. Rust saturates by 16 KiB. Widening the useful range downward is a
  dispatch problem (fall back to the four-chunk kernel, then scalar), not a
  kernel problem.
- **Streaming is flat at ~916 MiB/s from 16 KiB up**, against 2148 one-shot.
  The SIMD kernels are simply not reachable from the 4 KiB update path, so
  streaming is 39% of Rust where one-shot is 86%.

Raw JSON: `build/jmh-e024-long.json`. Re-measure on your own machine before
drawing conclusions — and that is not a formality. E012 re-ran the ledger on an
Intel N97 (AVX2, Linux, Temurin 25+36) and two of the headline ratios above do
not transfer:

| ratio at 8 MiB | Apple M5 (NEON) | Intel N97 (AVX2) |
|---|---:|---:|
| FastBlake scalar vs Commons | 1.61x | 1.20x |
| FastBlake E011 SIMD vs its own scalar | 1.77x | 2.54x |
| FastBlake E011 SIMD as a fraction of Rust | 63% | 38% |
| Rust vs Commons | 4.5x | 7.9x |

These are the E011-era figures that prompted the cross-architecture work; they
are kept because the *divergence* is the point. The M5 SIMD row has since been
superseded twice, by E014's loader and E024's interleaved kernel, and now stands
at 85.8% of Rust — see the E024 paragraph below. The N97 column has its own
later history under E016 and E019.

Correctness, the allocation gate, and C2's escape-analysis behaviour *did*
transfer, to three significant figures. What did not transfer is anything whose
mechanism depends on core width: the scalar kernel's instruction-level
parallelism has less to exploit on a narrow E-core, and the SIMD kernel's
hard-coded 128-bit species uses only half of an AVX2 machine while the Rust
crate dispatches to 8-lane AVX2. See `reference/performance/experiments.md`
§ E012.

What this says about where the work is:

- **Commons Codec is flat at ~500–550 MiB/s across three orders of magnitude.**
  It never engages data parallelism, because it has none to engage. That flat
  line was the original headroom estimate, and `java-cpu` has now taken most of
  it: 3.9× Commons at 8 MiB.
- **Rust's curve is SIMD engaging as the input grows**, saturating by 16 KiB
  where its 8-lane AVX2/NEON batch first fills. `java-cpu` traces the same
  shape one step to the right, saturating at 256 KiB, because its batch is
  8 KiB of input rather than Rust's smaller working set. The remaining 14% at
  8 MiB is the narrower part of the gap; the wide part is everything below
  256 KiB.
- **`reset()` reuse buys nothing at size, but it is decisive at 64 B** — 458
  against 199 for `java-cpu`. For Commons and Rust the two shapes are close,
  so this is FastBlake-specific hasher construction cost and a fixable one.
- **Streaming in 4 KiB pieces costs Rust ~6% and Commons nothing, but costs
  `java-cpu` 57%.** Rust gives up a little batching width at the piece
  boundary; Commons has none to give up; FastBlake loses its SIMD path
  entirely and falls back to scalar.

### Independent re-measurement: AMD Ryzen 3 3200G

AMD Ryzen 3 3200G (Zen, 4C/4T), Windows 10, Temurin JDK 25.0.4, Commons Codec
1.22.0. Same harness, same protocol: 2 forks × 5×1s warmup + 5×1s measurement,
single-threaded.

This machine had no Rust toolchain at first, so the earliest runs here covered
only `commons`/`java-cpu` (2 of 4, with `rust` and `java-gpu` reporting why
they were skipped, exactly as designed) — those numbers are still below,
unchanged. Rust was then installed via `rustup` (stable,
`x86_64-pc-windows-msvc`; this machine already had the VS 2022 Build Tools
Rust needs, so no other install was required) specifically so this machine's
figures would carry a Rust ceiling like the M5/N97 rows do. `./gradlew
contenders` now reports 3 of 4, and the full official-vector suite passes all
542 tests across all three contenders (`./gradlew test --rerun`).

Commons / Rust / the FastBlake production scalar kernel, all three now
measured together in one session:

**`oneShot`**:

| size | commons | rust | java-cpu |
|---:|---:|---:|---:|
| 64 B | 165 | 449 (2.73×) | 146 (0.89×) |
| 1 KiB | 182 | 810 (4.46×) | 321 (1.77×) |
| 16 KiB | 159 | 2155 (13.55×) | 320 (2.01×) |
| 256 KiB | 145 | 2183 (15.05×) | 328 (2.26×) |
| 4 MiB | 135 | 2075 (15.32×) | 328 (2.42×) |
| 8 MiB | 167 | 2033 (12.15×) | 327 (1.96×) |

**`reusedInstance`**:

| size | commons | rust | java-cpu |
|---:|---:|---:|---:|
| 64 B | 152 | 414 (2.73×) | 170 (1.12×) |
| 1 KiB | 194 | 827 (4.27×) | 316 (1.63×) |
| 16 KiB | 163 | 2120 (12.97×) | 327 (2.00×) |
| 256 KiB | 166 | 2203 (13.26×) | 333 (2.01×) |
| 4 MiB | 167 | 2048 (12.26×) | 331 (1.98×) |
| 8 MiB | 163 | 2026 (12.45×) | 331 (2.03×) |

**`streaming4k`**:

| size | commons | rust | java-cpu |
|---:|---:|---:|---:|
| 64 B | 150 | 413 (2.75×) | 168 (1.12×) |
| 1 KiB | 201 | 807 (4.01×) | 316 (1.57×) |
| 16 KiB | 167 | 1371 (8.21×) | 330 (1.98×) |
| 256 KiB | 168 | 1386 (8.25×) | 328 (1.95×) |
| 4 MiB | 168 | 1312 (7.79×) | 324 (1.92×) |
| 8 MiB | 165 | 1322 (7.99×) | 330 (1.99×) |

Raw JSON: `build/jmh-contenders-ryzen-full.json` (supersedes the earlier
2-contender `build/jmh-contenders-ryzen.json`, kept for history). x-factors
are against `commons`; `java-cpu` here is the production scalar kernel, not
either opt-in SIMD kernel below.

**Rust vs Commons is far wider on this machine than on either the M5 or the
N97** — 12.15x at 8 MiB `oneShot`, peaking at 15.32x at 4 MiB, against 4.5x
(M5) and 7.9x (N97). Both `rust` and `commons` are single-threaded scalar
executables/JVM code doing the identical work as elsewhere, so this isn't a
new mechanism, just a data point that the *size* of the Rust ceiling varies
enormously by machine — a Zen core apparently gives the 8-lane AVX2 SIMD path
much more room over undualized Commons than either the wide M5 or the narrow
N97 do. `java-cpu` scalar vs Commons also improved a little on this fresh
combined run versus the earlier isolated run (1.96x vs the previously
reported 1.92x at 8 MiB `oneShot`) — within normal run-to-run noise for this
harness, not a regression signal.

Re-running with the opt-in E011/E014 SIMD kernel
(`-Dfastblake.experimental.scratchChunkVector=true`) against that same scalar
column:

| size | `oneShot` scalar | `oneShot` SIMD | `reusedInstance` scalar | `reusedInstance` SIMD | `streaming4k` scalar | `streaming4k` SIMD |
|---:|---:|---:|---:|---:|---:|---:|
| 64 B | 91 | 38 (0.42×) | 157 | 76 (0.48×) | 156 | 81 (0.52×) |
| 1 KiB | 283 | 223 (0.79×) | 268 | 272 (1.01×) | 298 | 271 (0.91×) |
| 16 KiB | 304 | 589 (1.94×) | 304 | 592 (1.95×) | 305 | 574 (1.88×) |
| 256 KiB | 309 | 894 (2.89×) | 308 | 898 (2.92×) | 310 | 917 (2.96×) |
| 4 MiB | 310 | 889 (2.87×) | 313 | 911 (2.91×) | 310 | 845 (2.73×) |
| 8 MiB | 318 | 888 (2.79×) | 310 | 897 (2.89×) | 310 | 870 (2.81×) |

Raw JSON: `build/jmh-vector-ryzen.json`. Adding this machine to the ledger,
now with a real Rust ceiling instead of "n/a":

| ratio at 8 MiB | Apple M5 (NEON) | Intel N97 (AVX2) | AMD Ryzen 3 3200G (AVX2) |
|---|---:|---:|---:|
| FastBlake scalar vs Commons | 1.61x | 1.20x | 1.96x |
| FastBlake E011 SIMD vs its own scalar | 1.77x | 2.54x | 2.79x |
| FastBlake E011 SIMD as a fraction of Rust | 63% | 38% | **44%** |
| Rust vs Commons | 4.5x | 7.9x | **12.15x** |

E011's fraction-of-Rust on this machine (44%) lands close to the N97's 38% and
well below the M5's 63% — consistent with the standing explanation that E011's
hard-coded 128-bit species leaves half an AVX2 machine's lane width unused
while Rust dispatches to the full 8-lane path, on both x86-64 boxes measured
so far. That the *absolute* Rust ceiling is nearly 3x higher here than on the
N97 (2033 vs an implied lower N97 figure) while E011's fraction-of-Rust is
similar says the two machines' `java-cpu` SIMD kernels are closer to each
other in absolute terms than their Rust ceilings are — i.e. most of this
machine's outsized Rust/Commons gap is a Commons-side and Rust-side story, not
something `java-cpu` is failing to capture proportionally more of here.

The SIMD kernel is a **loss** at 64 B and roughly break-even at 1 KiB on this
machine — 0.42-0.52x and 0.79-1.01x respectively — before crossing over
between 1 KiB and 16 KiB and settling near 2.8-2.9x at 8 MiB. That crossover
point matches the architecture note above: 16 KiB is the smallest input that
fills a 4-lane SIMD batch, and below it the kernel pays batching overhead with
nothing to amortize it against.

Getting a correct SIMD number here required a harness fix first:
`BenchmarkRunner` called `OptionsBuilder.jvmArgsAppend("--add-modules=...")`
after `.parent(cmdLine)`, which *replaces* rather than merges the
`-jvmArgsAppend` JMH already captured from the command line — so passing
`-jvmArgsAppend -Dfastblake.experimental.scratchChunkVector=true` on the
`jmh.args` command line was silently dropped, and the first attempt at this
measurement quietly re-ran the scalar kernel under the `SIMD` label. Confirmed
by inspecting the fork's `# VM options:` line in the JMH log — it never
contained the property — then fixed by merging the two lists explicitly. Every
prior experiment in `reference/performance/experiments.md` sidestepped this by
setting the flag via `JAVA_TOOL_OPTIONS` instead (see E012), which was never
affected and remains the more foolproof way to flip these flags for
`./gradlew jmh`.

#### E024 on this machine: a regression, not a repeat of the M5 win

E024 interleaves two independent four-chunk batches (8 chunks, 8192 bytes) in
one round body, still at 128-bit lanes — more independent work per round
rather than wider vectors. On Apple M5 it beat E011 by 1.36-1.37x (see the
main Results table above and `reference/performance/experiments.md` § E024).
This machine is the first non-AArch64 measurement of it.

Correctness passes (`JAVA_TOOL_OPTIONS=-Dfastblake.experimental.dualChunkVector=true
./gradlew test --rerun`, full official-vector suite) and the allocation gate
holds: ~5,980 B fixed per 8 MiB call, i.e. ~0.0007 B per input byte, matching
E011's near-zero figure.

The first full-sweep throughput run (2 forks × 5+5×1s, same protocol as
everything else on this page) was contaminated by a handful of extreme
single-iteration outliers — an 11x spike at 8 MiB `oneShot` (118.6M ns against
a ~10.3M ns cluster) and a 14x spike at 256 KiB `reusedInstance` (4.57M ns
against a ~400K ns cluster), both visible in the raw JSON's `rawData` and
absent from every neighboring iteration and size. On a shared desktop with no
core pinning that reads as OS/background-process jitter, not kernel behavior,
but it skewed the tool's plain-average summary table badly enough to report
an implausible dip at those two cells. Re-running just those cells at 3 forks
× 10 iterations resolved it:

| shape / size | first sweep (contaminated) | re-check (3f×10i) |
|---|---:|---:|
| `oneShot` 256 KiB | 785 | **775** |
| `oneShot` 8 MiB | 379 | **778** |
| `reusedInstance` 256 KiB | 317 | **784** |
| `reusedInstance` 8 MiB | 771 | 777 (unaffected, kept as a check) |

Clean numbers against the E011 single four-chunk kernel and the scalar/Commons
baselines already measured on this machine, at 8 MiB:

| | commons | rust | scalar (`java-cpu`) | E011 (4-chunk SIMD) | E024 (8-chunk SIMD) |
|---|---:|---:|---:|---:|---:|
| `oneShot` | 166 | 2033 | 318 | 888 (44% of rust) | **778 (38% of rust, 0.88x of E011)** |
| `reusedInstance` | 166 | 2026 | 310 | 897 (44% of rust) | **777 (38% of rust, 0.87x of E011)** |
| `streaming4k` | 155 | 1322 | 310 | 870 (66% of rust) | ~300 (unchanged — see below) |

**E024 is about 12-14% slower than E011 here**, not 1.36x faster as on the
M5 — though it still beats scalar by ~2.4-2.5x and Commons by ~4.7x. This is
exactly the inversion the experiment doc's open item #2 flagged as a risk
before promotion: AVX2 gives the JIT only 16 architectural vector registers
against AArch64's 32, and doubling the interleaved round body from 4 to 8
independent chunks raises live vector state accordingly. What is a free
latency-hiding win on a register-rich core can cost more in spills/pressure
than it buys in independent work on a narrower one. This machine and the N97
share that 16-register constraint, so this result is a concrete data point
for — not a substitute for — running E024 on the N97 itself. Against Rust the
picture is unambiguous either way: on this machine's outsized ~12x Rust
ceiling, neither opt-in kernel gets past the mid-40s percent on the direct
path, so E024's loss to E011 here is a real regression, not a rounding
difference near parity.

`streaming4k` stays at scalar-level throughput (~300 MiB/s) regardless of the
kernel, because E024 has no streaming-batch integration yet (open item #1 in
the experiment doc) — it only takes the direct one-shot/reused path, same
limitation E011 had before E016.

Raw JSON: `build/jmh-dual-ryzen.json` (full sweep, includes the outlier
iterations for anyone who wants to see them), plus the two targeted re-checks
noted above.

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
E011, described below; the remaining CPU milestones are streaming support for
the E024 eight-chunk kernel, x86-64 validation of it, and confirming automatic
dispatch across architectures. Reducing the SIMD kernel's instruction count is
no longer among them — E023 measured that target at 0.7% on AArch64. E016
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

E022 supplied that M5 audit, using a locally built OpenJDK 28 with a working
`hsdis-aarch64` — the project's first mnemonic AArch64 disassembly. The
four-chunk round body is 245 instructions, of which the vector arithmetic is
already at the exact theoretical minimum (48 add, 32 eor, 64 rotate as
`ushr`+`sli`, 16 loads), with **zero** vector spill traffic. AArch64's 32 vector
registers mean the register pressure that shapes every N97 result simply does
not exist here. The remaining 85 instructions are scalar index arithmetic and
per-load bounds checks. A flat pre-multiplied schedule with a masked index,
intended to let C2 hoist those checks, removed exactly one of them and moved
throughput 0.5% — inside the inconclusive band. Rejected and reverted: masking
does not reach the range check that `IntVector.fromArray` emits inside its own
intrinsic.

E023 then asked whether those 85 instructions were worth attacking at all, with
a probe that keeps the vector arithmetic identical but replaces every message
operand with a loop-invariant vector, deleting all loads, index arithmetic and
bounds checks. **Removing 35% of the round body's instructions bought 0.7%.**
The M5 kernel is latency-bound on the vector dependency chain, not issue-bound.
That retires instruction-count reduction as a direction on this architecture and
explains E022's null result completely — the experiment had a 0.7% ceiling
before it was written. The protocol now requires proving a loop is issue-bound
*before* optimising its instruction count, alongside the allocation gate.

E024 acts on the corrected model. A latency-bound loop goes faster only with
more independent work in flight, so `Blake3ChunkVectorDual` runs two independent
four-chunk groups — eight chunks, 8192 bytes — through one interleaved round
body, doubling instruction-level parallelism from four independent G chains per
half-round to eight. Lanes stay at 128-bit: on NEON parallelism comes from more
batches, not wider vectors, so this is not E013's width experiment repeated. It
passes equivalence against two sequential four-chunk batches, the full 542-test
official-vector suite, and the allocation gate at 0.00 B per input byte —
doubling the round body did not cross the escape-analysis cliff. At 8 MiB it
measures **2149 MiB/s one-shot and reused, 1.36× the four-chunk kernel and 85.6%
of Rust**, up from 63%; the 2-fork long run in the Results table above confirms
this at 2148 one-shot and 2149 reused, or 85.8% of Rust. Enable with
`-Dfastblake.experimental.dualChunkVector=true`.

The generated code is the interesting part: the dual kernel executes **8.8% more
instructions** than two sequential four-chunk batches and **does spill** (30
vector spill instructions, since 32 state vectors plus message operands exceed
AArch64's 32 registers) — and is 36% faster anyway. In a latency-bound loop,
instruction count and spill traffic are nearly free while independent work in
flight is everything. The same 30 spills in an issue-bound loop would have been
a regression, which is precisely why this kernel must be validated on the
issue-bound, register-starved N97 before any promotion. Streaming is also still
untouched at 905 MiB/s, since the dual kernel has no equivalent of E016's
pending-batch retention; with one-shot at 85% of Rust and streaming at 39%, that
gap is now worth more than any further compression work.

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
