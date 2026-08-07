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

## Result

On this Ryzen 3 3200G, the shipped FastBlake CPU path is about 5.4-5.8x
faster than Commons Codec at 8 MiB and reaches 45-83% of the single-threaded
Rust ceiling. The three call shapes are close together: streaming is only 1.5%
below one-shot, while Rust's streaming path falls to 55% of its one-shot rate.
The result is allocation-safe under the protocol, with no measured garbage
collections in the isolated gate.
