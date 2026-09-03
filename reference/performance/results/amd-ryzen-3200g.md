# AMD Ryzen 3 3200G performance measurement

Date: 2026-08-07  
Machine: AMD Ryzen 3 3200G with Radeon Vega Graphics, 4 cores / 4 threads,
Zen+, 3.6 GHz reported clock, AVX2  
OS: Windows 10 Pro 10.0.19045  
JVM: Temurin OpenJDK 25.0.4+7-LTS  
JMH: 1.37  
Rust: `blake3` 1.8.5, SIMD enabled, Rayon disabled  
Java vector width: preferred 256-bit; FastBlake selected `FOUR_CHUNK`  
Threads: 1

## Gates

`./gradlew contenders` reported Commons Codec, Rust, and `java-cpu` available;
the GPU contender was skipped because it is not implemented.

`./gradlew test` passed the current official-vector conformance suite.

The isolated FastBlake allocation check used one fork, three 1-second warmup
iterations, and three 1-second measurement iterations. It recorded zero
collections. Allocation is fixed-size per call rather than input-proportional:

| shape | allocated bytes/op | collections |
|---|---:|---:|
| `oneShot` | 12,854 B | 0 |
| `reusedInstance` | 4,782 B | 0 |
| `streaming4k` | 4,784 B | 0 |

## 8 MiB throughput

Full comparison protocol: two forks, five 1-second warmup iterations, five
1-second measurement iterations, one JMH thread. Values are MiB/s; higher is
better. Speedups are relative to Commons Codec.

| shape | Commons Codec | Rust | FastBlake `java-cpu` |
|---|---:|---:|---:|
| `oneShot` | 155 | 1,922 (12.42x) | 893 (5.77x) |
| `reusedInstance` | 154 | 1,973 (12.82x) | 890 (5.78x) |
| `streaming4k` | 164 | 1,064 (6.48x) | 880 (5.36x) |

## Commands

Correctness and availability:

```bash
./gradlew contenders
./gradlew test
```

Allocation gate:

```bash
./gradlew jmh -P'jmh.args=Blake3Benchmark -p impl=java-cpu -p size=8388608 -f1 -wi 3 -i 3 -prof gc'
```

Throughput comparison:

```bash
./gradlew jmh -P'jmh.args=Blake3Benchmark -p impl=commons,rust,java-cpu -p size=8388608 -f2 -wi 5 -i 5'
```

## Historical: pre-dispatch sweeps on this machine

The measurements below predate E025's capability dispatch, when `java-cpu` was
the production **scalar** kernel and every SIMD kernel was opt-in behind an
`fastblake.experimental.*` property. They are retained because they are the
project's independent x86-64 re-measurement and the origin of the E024
architecture split. The properties they name are no longer interpreted by the
shipped `FastBlake` class; current dispatch experiments use
`-Dfastblake.kernel=auto|scalar|four|avx1|eight|wide`.

This machine had no Rust toolchain at first, so the earliest runs covered only
`commons`/`java-cpu` (2 of 4, with `rust` and `java-gpu` reporting why they were
skipped, exactly as designed). Rust was then installed via `rustup` (stable,
`x86_64-pc-windows-msvc`; this machine already had the VS 2022 Build Tools Rust
needs, so no other install was required) specifically so this machine's figures
would carry a Rust ceiling like the M5/N97 rows do. `./gradlew contenders` then
reported 3 of 4, and the full official-vector suite passed all 542 tests across
all three contenders.

Commons / Rust / the FastBlake production scalar kernel, all three measured
together in one session, 2 forks × 5×1s warmup + 5×1s measurement,
single-threaded:

**`oneShot`**:

| size | commons | rust | java-cpu (scalar) |
|---:|---:|---:|---:|
| 64 B | 165 | 449 (2.73×) | 146 (0.89×) |
| 1 KiB | 182 | 810 (4.46×) | 321 (1.77×) |
| 16 KiB | 159 | 2155 (13.55×) | 320 (2.01×) |
| 256 KiB | 145 | 2183 (15.05×) | 328 (2.26×) |
| 4 MiB | 135 | 2075 (15.32×) | 328 (2.42×) |
| 8 MiB | 167 | 2033 (12.15×) | 327 (1.96×) |

**`reusedInstance`**:

| size | commons | rust | java-cpu (scalar) |
|---:|---:|---:|---:|
| 64 B | 152 | 414 (2.73×) | 170 (1.12×) |
| 1 KiB | 194 | 827 (4.27×) | 316 (1.63×) |
| 16 KiB | 163 | 2120 (12.97×) | 327 (2.00×) |
| 256 KiB | 166 | 2203 (13.26×) | 333 (2.01×) |
| 4 MiB | 167 | 2048 (12.26×) | 331 (1.98×) |
| 8 MiB | 163 | 2026 (12.45×) | 331 (2.03×) |

**`streaming4k`**:

| size | commons | rust | java-cpu (scalar) |
|---:|---:|---:|---:|
| 64 B | 150 | 413 (2.75×) | 168 (1.12×) |
| 1 KiB | 201 | 807 (4.01×) | 316 (1.57×) |
| 16 KiB | 167 | 1371 (8.21×) | 330 (1.98×) |
| 256 KiB | 168 | 1386 (8.25×) | 328 (1.95×) |
| 4 MiB | 168 | 1312 (7.79×) | 324 (1.92×) |
| 8 MiB | 165 | 1322 (7.99×) | 330 (1.99×) |

Raw JSON: `build/jmh-contenders-ryzen-full.json` (supersedes the earlier
2-contender `build/jmh-contenders-ryzen.json`, kept for history).

**Rust vs Commons is far wider on this machine than on either the M5 or the
N97** — 12.15x at 8 MiB `oneShot`, peaking at 15.32x at 4 MiB, against 4.5x
(M5) and 7.9x (N97). Both `rust` and `commons` are single-threaded scalar
executables/JVM code doing the identical work as elsewhere, so this isn't a new
mechanism, just a data point that the *size* of the Rust ceiling varies
enormously by machine — a Zen core apparently gives the 8-lane AVX2 SIMD path
much more room over undualized Commons than either the wide M5 or the narrow N97
do. `java-cpu` scalar vs Commons also improved a little on this fresh combined
run versus an earlier isolated run (1.96x vs 1.92x at 8 MiB `oneShot`) — within
normal run-to-run noise for this harness, not a regression signal.

### The opt-in E011/E014 four-chunk SIMD kernel

Re-running with `-Dfastblake.experimental.scratchChunkVector=true` against that
same scalar column:

| size | `oneShot` scalar | `oneShot` SIMD | `reusedInstance` scalar | `reusedInstance` SIMD | `streaming4k` scalar | `streaming4k` SIMD |
|---:|---:|---:|---:|---:|---:|---:|
| 64 B | 91 | 38 (0.42×) | 157 | 76 (0.48×) | 156 | 81 (0.52×) |
| 1 KiB | 283 | 223 (0.79×) | 268 | 272 (1.01×) | 298 | 271 (0.91×) |
| 16 KiB | 304 | 589 (1.94×) | 304 | 592 (1.95×) | 305 | 574 (1.88×) |
| 256 KiB | 309 | 894 (2.89×) | 308 | 898 (2.92×) | 310 | 917 (2.96×) |
| 4 MiB | 310 | 889 (2.87×) | 313 | 911 (2.91×) | 310 | 845 (2.73×) |
| 8 MiB | 318 | 888 (2.79×) | 310 | 897 (2.89×) | 310 | 870 (2.81×) |

Raw JSON: `build/jmh-vector-ryzen.json`. Adding this machine to the cross-machine
ledger, now with a real Rust ceiling instead of "n/a":

| ratio at 8 MiB | Apple M5 (NEON) | Intel N97 (AVX2) | AMD Ryzen 3 3200G (AVX2) |
|---|---:|---:|---:|
| FastBlake scalar vs Commons | 1.61x | 1.20x | 1.96x |
| FastBlake E011 SIMD vs its own scalar | 1.77x | 2.54x | 2.79x |
| FastBlake E011 SIMD as a fraction of Rust | 63% | 38% | **44%** |
| Rust vs Commons | 4.5x | 7.9x | **12.15x** |

E011's fraction-of-Rust on this machine (44%) lands close to the N97's 38% and
well below the M5's 63% — consistent with the standing explanation that E011's
hard-coded 128-bit species leaves half an AVX2 machine's lane width unused while
Rust dispatches to the full 8-lane path, on both x86-64 boxes measured so far.
That the *absolute* Rust ceiling is nearly 3x higher here than on the N97 while
E011's fraction-of-Rust is similar says the two machines' `java-cpu` SIMD kernels
are closer to each other in absolute terms than their Rust ceilings are — i.e.
most of this machine's outsized Rust/Commons gap is a Commons-side and Rust-side
story, not something `java-cpu` fails to capture proportionally more of here.

The SIMD kernel is a **loss** at 64 B and roughly break-even at 1 KiB on this
machine — 0.42-0.52x and 0.79-1.01x respectively — before crossing over between
1 KiB and 16 KiB and settling near 2.8-2.9x at 8 MiB. That crossover point
matches the size rationale in `reference/architecture/benchmark-harness.md`:
16 KiB is the smallest input that fills a 4-lane SIMD batch, and below it the
kernel pays batching overhead with nothing to amortize it against.

#### Harness bug found in the process

Getting a correct SIMD number here required a harness fix first:
`BenchmarkRunner` called `OptionsBuilder.jvmArgsAppend("--add-modules=...")`
after `.parent(cmdLine)`, which *replaces* rather than merges the
`-jvmArgsAppend` JMH already captured from the command line — so passing
`-jvmArgsAppend -Dfastblake.experimental.scratchChunkVector=true` on the
`jmh.args` command line was silently dropped, and the first attempt at this
measurement quietly re-ran the scalar kernel under the `SIMD` label. Confirmed by
inspecting the fork's `# VM options:` line in the JMH log — it never contained
the property — then fixed by merging the two lists explicitly. Every prior
experiment in `reference/performance/experiments.md` sidestepped this by setting
the flag via `JAVA_TOOL_OPTIONS` instead (see E012), which was never affected and
remains the more foolproof way to flip these flags for `./gradlew jmh`.

### E024 on this machine: a regression, not a repeat of the M5 win

E024 interleaves two independent four-chunk batches (8 chunks, 8192 bytes) in
one round body, still at 128-bit lanes — more independent work per round rather
than wider vectors. On Apple M5 it beat E011 by 1.36-1.37x (see
`reference/performance/experiments.md` § E024). This machine was the first
non-AArch64 measurement of it, and the result is what produced the
architecture split now encoded in `KernelSelector`.

Correctness passes (`JAVA_TOOL_OPTIONS=-Dfastblake.experimental.dualChunkVector=true
./gradlew test --rerun`, full official-vector suite) and the allocation gate
holds: ~5,980 B fixed per 8 MiB call, i.e. ~0.0007 B per input byte, matching
E011's near-zero figure.

The first full-sweep throughput run (2 forks × 5+5×1s) was contaminated by a
handful of extreme single-iteration outliers — an 11x spike at 8 MiB `oneShot`
(118.6M ns against a ~10.3M ns cluster) and a 14x spike at 256 KiB
`reusedInstance` (4.57M ns against a ~400K ns cluster), both visible in the raw
JSON's `rawData` and absent from every neighboring iteration and size. On a
shared desktop with no core pinning that reads as OS/background-process jitter,
not kernel behavior, but it skewed the tool's plain-average summary table badly
enough to report an implausible dip at those two cells. This is the incident that
motivated the corroboration gate later formalised by E026. Re-running just those
cells at 3 forks × 10 iterations resolved it:

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

**E024 is about 12-14% slower than E011 here**, not 1.36x faster as on the M5 —
though it still beats scalar by ~2.4-2.5x and Commons by ~4.7x. This is exactly
the inversion the experiment doc's open item #2 flagged as a risk before
promotion: AVX2 gives the JIT only 16 architectural vector registers against
AArch64's 32, and doubling the interleaved round body from 4 to 8 independent
chunks raises live vector state accordingly. What is a free latency-hiding win on
a register-rich core can cost more in spills/pressure than it buys in independent
work on a narrower one. This machine and the N97 share that 16-register
constraint. Against Rust the picture is unambiguous either way: on this machine's
outsized ~12x Rust ceiling, neither opt-in kernel gets past the mid-40s percent on
the direct path, so E024's loss to E011 here is a real regression, not a rounding
difference near parity.

`streaming4k` stayed at scalar-level throughput (~300 MiB/s) regardless of the
kernel, because E024 had no streaming-batch integration at the time — it only
took the direct one-shot/reused path, the same limitation E011 had before E016.
E025 P1 subsequently closed this.

Raw JSON: `build/jmh-dual-ryzen.json` (full sweep, includes the outlier
iterations for anyone who wants to see them), plus the two targeted re-checks
noted above.

## Result

On this Ryzen 3 3200G, the shipped FastBlake CPU path is about 5.4-5.8x
faster than Commons Codec at 8 MiB and reaches 45-83% of the single-threaded
Rust ceiling. The three call shapes are close together: streaming is only 1.5%
below one-shot, while Rust's streaming path falls to 55% of its one-shot rate.
The result is allocation-safe under the protocol, with no measured garbage
collections in the isolated gate.
