# FastBlake

A pure-JVM BLAKE3 implementation, optimised for throughput.

Status: **CPU implementation and comparison harness complete.** FastBlake now
implements the full BLAKE3 API in dependency-free Java. The scalar kernel is the
correctness and performance baseline for the forthcoming SIMD kernel; GPU
offload remains planned work.

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
E011, described below; the remaining CPU milestones are batching that kernel
across streaming updates, batched parent compression, and the scalar data-path
work (little-endian VarHandle loader, direct-input bypass, message locals) that
compounds with both. The first array-based Vector API
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
scalar path and 63% of Rust on the Apple M5. E012 validated it on x86-64, where
it is a *larger* relative win — 2.54x the scalar path — while still passing the
allocation gate and the full official-vector suite. It remains opt-in behind
`-Dfastblake.experimental.scratchChunkVector=true` while streaming batching is
completed. A fully expanded seven-round variant is
retained only as diagnostic evidence: it crosses a C2 cliff and allocates
43,776 bytes per block invocation, while the compact loop allocates effectively
zero.
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
