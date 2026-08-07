# Intel N97 performance measurement

Date: 2026-08-07  
Machine: Intel N97, 4 cores / 4 threads, Alder Lake-N (Gracemont E-cores),
3.6 GHz maximum, AVX2, 6 MiB L3  
OS: Linux 7.0.0-28-generic  
JVM: Temurin OpenJDK 25.0.3+9-LTS  
JMH: 1.37  
Rust: `blake3` 1.8.5, SIMD enabled, Rayon disabled  
Java vector width: preferred 256-bit; FastBlake selected `FOUR_CHUNK`  
Threads: 1

## Gates

`./gradlew contenders` reported Commons Codec, Rust, and `java-cpu` available;
the GPU contender was skipped because it is not implemented.

`./gradlew test` passed the full official-vector conformance suite across all
three available contenders.

The isolated four-chunk kernel allocation check measured **0.031 B/op** and
zero collections. The production 8 MiB call-shape check also recorded zero
collections; its per-operation figures include expected result and retained
hasher/buffer allocations, so they are not used as the kernel allocation gate:

| shape | allocated bytes/op | collections |
|---|---:|---:|
| `oneShot` | 12,872 B | 0 |
| `reusedInstance` | 4,801 B | 0 |
| `streaming4k` | 4,822 B | 0 |

## 8 MiB throughput

Quick comparison protocol: one fork, three 1-second warmup iterations, three
1-second measurement iterations, one JMH thread. Values are MiB/s; higher is
better. Speedups are relative to Commons Codec.

| shape | Commons Codec | Rust | FastBlake `java-cpu` |
|---|---:|---:|---:|
| `oneShot` | 157 | 1,429 (9.10x) | 716 (4.56x) |
| `reusedInstance` | 166 | 1,648 (9.91x) | 682 (4.10x) |
| `streaming4k` | 165 | 1,006 (6.11x) | 548 (3.33x) |

The one-shot row came from the first completed quick comparison; the two
other rows were re-run separately to avoid output truncation. Small quick-run
differences should be treated cautiously; the experiment protocol considers
changes below roughly 3% inconclusive until confirmed with a longer run.

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
./gradlew jmh -P'jmh.args=Blake3Benchmark -p impl=commons,rust,java-cpu -p size=8388608 -f1 -wi 3 -i 3'
```

The Gradle cache was redirected to `/tmp/fastblake-gradle` on this machine;
that does not affect the benchmark JVM or its selected kernel.

## Result

On this N97, the shipped FastBlake CPU path is about 4.1–4.6x faster than
Commons Codec at 8 MiB and reaches 42–50% of the single-threaded Rust ceiling.
Streaming is slower than contiguous one-shot/reused input, but remains about
3.3x faster than Commons. Absolute MiB/s should not be compared with the
Apple M5 results; ratios within this machine are the meaningful comparison.
