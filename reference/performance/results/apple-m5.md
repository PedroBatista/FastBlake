# Apple M5 performance measurement

Date: 2026-08-07  
Machine: Apple M5, 10 cores / 10 threads (4 performance + 6 efficiency),
NEON 128-bit, 32 vector registers, 6 MiB L2  
OS: macOS 26.6  
JVM: Temurin OpenJDK 25.0.4+7-LTS  
JMH: 1.37  
Rust: `blake3` 1.8.5, SIMD enabled, Rayon disabled  
Java vector width: preferred 128-bit; FastBlake selected `EIGHT_CHUNK`  
Threads: 1

This is the development machine, and the one where the eight-chunk interleaved
kernel is selected. The other two measured machines are x86-64 with 16 vector
registers and select `FOUR_CHUNK`; see `amd-ryzen-3200g.md` and `intel-n97.md`.

## Gates

`./gradlew contenders` reported Commons Codec, Rust, and `java-cpu` available;
the GPU contender was skipped because it is not implemented. The selection line
records the kernel and the reason:

```
[x] java-cpu FastBlake CPU — aarch64, preferred vector width 128 bits,
    ~32 vector registers -> EIGHT_CHUNK (AArch64 with 32 vector registers;
    E024 measured eight chunks in flight at +36% over four)
```

`./gradlew test` passed the full official-vector conformance suite, 542 tests
across all three available contenders.

The isolated allocation check used one fork, three 1-second warmup iterations,
and three 1-second measurement iterations. It recorded zero collections.
Allocation is fixed-size per call rather than input-proportional:

| shape | allocated bytes/op | collections |
|---|---:|---:|
| `oneShot` | 16,913 B | 0 |
| `reusedInstance` | 4,745 B | 0 |
| `streaming4k` | 4,746 B | 0 |

At 8 MiB these figures are 0.0020 and 0.00057 bytes per input byte. They
include the result array and per-call setup, and do not scale with input, which
is what the gate tests for.

## 8 MiB throughput

Full comparison protocol: two forks, five 1-second warmup iterations, five
1-second measurement iterations, one JMH thread. Values are MiB/s; higher is
better. Speedups are relative to Commons Codec.

| shape | Commons Codec | Rust | FastBlake `java-cpu` |
|---|---:|---:|---:|
| `oneShot` | 537 | 2,480 (4.61x) | 2,116 (3.94x) |
| `reusedInstance` | 527 | 2,456 (4.66x) | 2,114 (4.01x) |
| `streaming4k` | 521 | 2,288 (4.39x) | 2,063 (3.96x) |

## Full size sweep

A separate post-E025 session, same protocol (2 forks × 5 warmup × 5×1s
measurement, single-threaded), sweeping every benchmark size with all three
contenders measured together. This is the **shipped default**: no property is
set, and `java-cpu` is whatever E025 P3's capability dispatch selects — on this
machine the eight-chunk kernel. Every contender is reached through its own best
one-shot entry point in the `oneShot` shape. MiB/s, higher is better; x-factor
against the `commons` baseline.

Absolute values differ by 1-3% from the 8 MiB table above because it is a
different session; the protocol's inconclusive band is 3%.

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

† These two cells carry the **audited** values, not what the sweep reported. The
sweep produced 368 MiB/s (`reusedInstance`) and 363 (`streaming4k`) at 64 bytes,
which reads as a 25%-wrong regression and had a plausible mechanism reasoned
backwards from it. `dispatchAudit` at 64 bytes contradicted it: scalar, four and
eight are all within 2% of each other, and the default costs nothing at this
size. The bad values are named rather than silently overwritten, because "a sweep
can produce a 25%-wrong cell" is information a reader needs when weighing every
other number in the same table. See E026; reproduce with:

```bash
./gradlew dispatchAudit -PauditShape=reusedInstance -PauditSize=64
```

Three things the sweep exposes that the 8 MiB headline hides:

- **The three call shapes have converged.** Before E025, streaming ran at
  911 MiB/s against 2140 one-shot at 8 MiB — the SIMD kernel was simply not
  reachable from a 4 KiB update path. P1 gave the eight-chunk kernel the same
  retained-batch treatment E016 gave the four-chunk one, and P2/P2b added a
  four-chunk rung to both the update loop and the finalization drain. Streaming
  is now within 3% of one-shot at every size from 16 KiB up, and at 8 MiB it is
  85% of Rust where it used to be 39%.
- **Mid-sized inputs still lag.** 16 KiB reaches 1510 MiB/s, 71% of the 8 MiB
  rate, where Rust is already saturated by 16 KiB. The eight-chunk kernel needs
  8 KiB of input before it engages at all, and the rungs beneath it are coarse.
  This is now the largest structural gap — item 1 of the E028 ranking.
- **The 64-byte one-shot was the worst number in the project and is now fine.**
  It measured 199 MiB/s, 0.38× Commons, before E025 P0 removed the eager
  per-hasher buffers and added a single-chunk path that hashes straight from the
  caller's array. It is now 536, or 1.02× Commons, having gone from 3,400 to
  256 bytes allocated per 64-byte hash.

## Retained footprint

Deep retained size of one hasher, measured with JOL. This axis is independent of
allocation rate and was added after the other two machines were measured, so
they have no comparable rows yet.

| contender | fresh | after 64 B | after 8 MiB |
|---|---:|---:|---:|
| Commons Codec | 464 B | 464 B | 1,136 B |
| Rust | 88 B † | 88 B † | 88 B † |
| FastBlake `java-cpu` | 1,384 B | 1,384 B | 12,136 B |

† The Rust row is a Java wrapper over an off-heap `blake3::Hasher`; JOL cannot
see the native allocation, so it is a floor rather than a total.

A hasher that has streamed 8 MiB retains 12 KiB, most of it the 8 KiB batch
buffer that makes streaming fast. A hasher doing small work retains 1.4 KiB and
does not grow, because the batch buffer is allocated only once input exceeds one
chunk. Static one-shot entry points retain nothing between calls.

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

Retained footprint, and confirmation that the selected kernel is the fastest
one available here:

```bash
./gradlew footprint
./gradlew dispatchAudit
```

The Gradle daemon on this machine is pinned to a Java 25 toolchain via
`gradle/gradle-daemon-jvm.properties`. Without it the daemon follows whatever
`java` is first on PATH, and a Java 27 early-access build there fails every
Gradle invocation with "Unsupported class file major version 71" — including
benchmark runs, which die silently and produce empty results.

## Result

On this Apple M5, the shipped FastBlake CPU path is about 3.9-4.0x faster than
Commons Codec at 8 MiB and reaches 85-90% of the single-threaded Rust ceiling —
the closest to Rust of the three measured machines, where the two x86-64 boxes
reach 45-83% and 42-50%. The three call shapes are within 3% of each other:
streaming costs 2.5% against one-shot, while Rust's streaming path gives up 7.7%
of its own one-shot rate. The result is allocation-safe under the protocol, with
zero collections in the isolated gate.

Absolute MiB/s should not be compared across machines; ratios within a machine
are the meaningful comparison. The reason this machine reaches a higher fraction
of Rust than the x86-64 ones is the kernel it selects: AArch64's 32 vector
registers hold eight chunks in flight, where 16 registers cannot (E024).
