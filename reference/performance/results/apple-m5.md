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
