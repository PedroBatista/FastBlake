# FastBlake

A pure-JVM BLAKE3 implementation, optimised for throughput.

Status: **CPU implementation, comparison harness, and capability-based kernel
dispatch complete.** FastBlake implements the full BLAKE3 API in
dependency-free Java. A chunk-parallel Vector API kernel is now selected
automatically from the machine's capabilities rather than being opt-in: on
Apple M5 that is an eight-chunk interleaved kernel reaching **2135 MiB/s
one-shot at 8 MiB — 85% of the reference Rust crate** and 3.8× Commons Codec,
while x86-64 gets the four-chunk kernel, which measured faster there. All three
call shapes have converged — 4 KiB streaming updates now run at 2094 MiB/s,
within 3% of one-shot, where an earlier version of the streaming path fell back
to scalar at 911. FastBlake matches or beats Commons Codec at every measured
size and shape. A JVM
started without `jdk.incubator.vector` falls back to the scalar kernel
automatically and still produces identical digests. GPU offload remains planned
work.

The generated library jar contains the public API and measured production
kernels. Historical and candidate kernels live under `src/experiment` and can
be compiled with `./gradlew experimentClasses`; JMH receives them on its
benchmark classpath. The old `fastblake.experimental.*` properties are archived
experiment records and are no longer interpreted by `FastBlake`. Use
`-Dfastblake.kernel=auto|scalar|four|eight` for current dispatch experiments.

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
| `java-cpu` | FastBlake CPU — allocation-free scalar Java plus capability-selected chunk-parallel Vector API kernels | Implemented and always available. |
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
against the `commons` baseline. **This is the shipped default**: no property is
set, and `java-cpu` is whatever the capability-based dispatch selects — on this
machine, the eight-chunk kernel. Every contender is reached through its own
best one-shot entry point in the `oneShot` shape.

**`oneShot`** — fresh hasher per buffer:

| size | commons | rust | java-cpu |
|---:|---:|---:|---:|
| 64 B | 525 | 835 (1.59×) | 536 (1.02×) |
| 1 KiB | 568 | 1311 (2.31×) | 940 (1.66×) |
| 16 KiB | 554 | 2480 (4.47×) | 1510 (2.72×) |
| 256 KiB | 561 | 2506 (4.47×) | 2088 (3.72×) |
| 4 MiB | 559 | 2501 (4.48×) | 2140 (3.83×) |
| 8 MiB | 560 | 2505 (4.48×) | 2135 (3.81×) |

**`reusedInstance`** — `reset()` reuse:

| size | commons | rust | java-cpu |
|---:|---:|---:|---:|
| 64 B | 460 | 877 (1.91×) | 483 (1.05×) † |
| 1 KiB | 571 | 1317 (2.31×) | 929 (1.63×) |
| 16 KiB | 557 | 2492 (4.47×) | 1531 (2.75×) |
| 256 KiB | 562 | 2511 (4.46×) | 2109 (3.75×) |
| 4 MiB | 550 | 2516 (4.58×) | 2149 (3.91×) |
| 8 MiB | 550 | 2520 (4.59×) | 2159 (3.93×) |

**`streaming4k`** — 4 KiB incremental updates:

| size | commons | rust | java-cpu |
|---:|---:|---:|---:|
| 64 B | 421 | 848 (2.01×) | 475 (1.13×) † |
| 1 KiB | 508 | 1303 (2.57×) | 921 (1.81×) |
| 16 KiB | 483 | 2370 (4.90×) | 1509 (3.12×) |
| 256 KiB | 481 | 2368 (4.92×) | 2050 (4.26×) |
| 4 MiB | 495 | 2348 (4.74×) | 2102 (4.25×) |
| 8 MiB | 497 | 2364 (4.76×) | 2094 (4.21×) |

The three call shapes are within a few percent of each other at every size
from 16 KiB up — streaming updates reach the same SIMD kernel as one-shot
calls, rather than falling back to scalar. Below the 1 KiB chunk boundary,
per-call overhead dominates and the SIMD kernel has nothing to batch, which is
why 64 B and 1 KiB trail the larger sizes.

Independently re-measured on an AMD Ryzen 3 3200G and an Intel N97 (both
x86-64): `java-cpu` reaches 45-90% of the Rust ceiling depending on machine and
call shape, always ahead of Commons Codec. Full per-machine numbers, protocol,
and reproduction commands are in
[`reference/performance/results/`](reference/performance/results/).

### Memory

Throughput is one axis; how much memory a hasher holds is another, and they can
move in opposite directions. `./gradlew footprint` reports deep retained size
per hasher via JOL, at three lifecycle points:

| contender | fresh | after 64 B | after 8 MiB |
|---|---:|---:|---:|
| `commons` | 464 B | 464 B | 1,136 B |
| `rust` | 88 B † | 88 B † | 88 B † |
| `java-cpu` | 1,384 B | 1,384 B | 12,136 B |

† `rust` is a Java wrapper over an off-heap `blake3::Hasher`; JOL cannot see the
native allocation, so that row is a floor rather than a total.

A hasher that has streamed 8 MiB retains 12 KiB, most of it the 8 KiB batch
buffer that makes streaming fast, paid only by hashers that actually stream. A
hasher doing small work retains 1.4 KiB and does not grow: the batch buffer is
allocated only once input exceeds one chunk. Static one-shot entry points
retain nothing between calls, so this axis only matters for callers that hold
hasher instances.

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

Two Vector API kernels sit on top of the scalar path — a four-chunk kernel and
an eight-chunk kernel that interleaves two four-chunk groups for more
independent work per round. `KernelSelector` picks between them automatically
based on the JVM's reported vector width and register count (AArch64's 32
vector registers favor the eight-chunk kernel; x86-64's 16 favor the
four-chunk one), falling back to scalar when `jdk.incubator.vector` is
unavailable. All paths produce identical digests; the choice only affects
throughput. A handful of historical/candidate kernels and loaders that did not
win — array-based and intra-block Vector API layouts, a preferred-width
kernel, SIMD parent compression, a MemorySegment-based loader — are kept as
opt-in diagnostic artifacts behind `-Dfastblake.experimental.*` flags rather
than deleted, since they're recorded as negative results in the experiment
log.

Every kernel tried, what was measured on each machine, and why the ones above
were chosen over the rest is recorded in
[`reference/performance/experiments.md`](reference/performance/experiments.md)
(experiments E001–E027), with per-machine protocol and raw numbers under
[`reference/performance/results/`](reference/performance/results/).

The GPU contender comes later. BLAKE3 suits a GPU well — the tree structure
makes every 1 KiB chunk independent, so a large input decomposes into thousands
of parallel chunk compressions with only a small parent-node merge left. Expect
the mirror image of the CPU contenders: badly beaten on small inputs where
kernel launch and host-to-device transfer dominate, competitive only once the
input amortises both. Transfer cost stays inside the measured region — a number
that excludes it would not describe anything a caller can actually get.

[crate]: https://crates.io/crates/blake3
