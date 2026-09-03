# Mac Pro 2013 AVX1 performance measurement

Date: 2026-09-03  
Machine: Mac Pro 2013, Intel Xeon E5-1680 v2 at 3.00 GHz, 8 cores / 16 threads,
Ivy Bridge-EP, AVX1 without AVX2  
OS: macOS 12.7.6 (21H1320)  
JVM: Temurin OpenJDK 25.0.4+7-LTS  
JMH: 1.37  
Rust: 1.98.0, `blake3` 1.8.5, SIMD enabled, Rayon disabled  
Java vector width: preferred 128-bit; maximum integer 128-bit; maximum
floating point 256-bit  
Threads: 1

## Gates

`./gradlew test` passed the complete conformance suite. Capability detection
reported effective ISA `avx`, correctly distinguishing AVX1 from SSE and AVX2.
The full suite also passed with `FOUR_CHUNK`, `AVX1_CHUNK` and `EIGHT_CHUNK`
forced, confirming that selector changes did not invalidate another kernel.

The production selector initially chose `FOUR_CHUNK`. Its isolated allocation
gate was repeated and failed consistently:

| shape | allocated bytes/op | bytes/input byte | collections |
|---|---:|---:|---:|
| `oneShot` | 714,334,920 | 85.16 | 11 over 3 iterations |
| `reusedInstance` | 714,326,626 | 85.15 | 15 over 3 iterations |
| `streaming4k` | 714,326,659 | 85.15 | 14 over 3 iterations |

This is the Vector API wrapper-allocation failure described by E010: C2 did
not scalar-replace the temporary `IntVector` objects in the four-chunk kernel
on this CPU/JVM combination. The throughput from that path is therefore
diagnostic only and cannot pass the project measurement protocol.

The forced scalar allocation gate passed with no collections:

| shape | allocated bytes/op |
|---|---:|
| `oneShot` | 5,384 |
| `reusedInstance` | 2,222 |
| `streaming4k` | 2,222 |

These are fixed call-shape costs rather than input-proportional allocation.

## 8 MiB throughput

Full comparison protocol: two forks, five 1-second warmup iterations, five
1-second measurement iterations, one JMH thread. The scalar comparison was
repeated; both runs agreed within one or two MiB/s.

| shape | Commons Codec | Rust | FastBlake scalar |
|---|---:|---:|---:|
| `oneShot` | 178 | 1,683 (9.43x) | 331 (1.85x) |
| `reusedInstance` | 178 | 1,688 (9.48x) | 332 (1.87x) |
| `streaming4k` | 178 | 1,522 (8.54x) | 330 (1.85x) |

Values are MiB/s; higher is better. The immediately preceding full run measured
330/331/332 MiB/s for the same three FastBlake shapes, confirming the result.

The rejected automatic four-chunk path measured 55-67 MiB/s, depending on the
run. `dispatchAudit` supplied direct same-command comparisons before and after
the selector change:

| kernel | initial audit | post-change audit |
|---|---:|---:|
| scalar | 315 MiB/s | 324 MiB/s |
| four | 65 MiB/s | 65 MiB/s |
| eight | 24 MiB/s | 43 MiB/s |
| wide | 23 MiB/s | 65 MiB/s |

The first audit's eight/wide cells did not reproduce and are not used for a
mechanism claim. The decision-driving scalar/four comparison did reproduce:
four was 79.4-79.9% below scalar (scalar was 4.85-4.98x as fast), well outside
the 3% inconclusive band. The post-change audit reported `SCALAR` as both
selected and fastest, with `VERDICT: OK`.

That made scalar the safe initial fix, but E030 subsequently isolated and
removed the compiler failure. A direct `VectorOperators.ROR` dependency chain
allocated 12,336 B/op for 256 rotates, almost exactly one 48-byte wrapper per
rotation. Expressing the same rotations as shifts plus OR allocated effectively
zero. Critically, a helper-based BLAKE3 G still allocated 49,248 B/op, while
textually inlining those expressions into the G allocated effectively zero.

The resulting dedicated `AVX1_CHUNK` kernel passed the complete forced and
automatic conformance suites. Its automatic 8 MiB allocation gate reported no
collections and fixed costs of 12,860 B/op one-shot and about 4,786 B/op for
reused/streaming—approximately 0.0015 and 0.0006 bytes per input byte.

A separate consumer-style probe loaded the Java 21-compatible library jar on
Temurin 21.0.12.1 with the Vector API resolved. Zero-configuration selection
chose `AVX1_CHUNK`, measured about 875 MiB/s, and allocated 12,792 B/op. This
validates both the library's minimum Java version and the JDK 25 benchmark JVM;
neither run used performance flags.

Long confirmation, two forks with five warmup and five measurement iterations:

| shape | Commons Codec | Rust | FastBlake AVX1 |
|---|---:|---:|---:|
| `oneShot` | 166 | 1,680 | 867 |
| `reusedInstance` | 180 | 1,694 | 886 |
| `streaming4k` | 177 | 1,517 | 829 |

The repeated five-kernel dispatch audit measured scalar/four/AVX1/eight/wide
at 329/65/863/42/63 MiB/s and reported `AVX1_CHUNK` as both selected and fastest
with `VERDICT: OK`.

## Commands

```bash
./gradlew test
./gradlew contenders
./gradlew jmh -P'jmh.args=Blake3Benchmark -p impl=java-cpu -p size=8388608 -f1 -wi 3 -i 3 -prof gc'
./gradlew dispatchAudit
JAVA_TOOL_OPTIONS='-Dfastblake.kernel=four' ./gradlew test --rerun
JAVA_TOOL_OPTIONS='-Dfastblake.kernel=avx1' ./gradlew test --rerun
JAVA_TOOL_OPTIONS='-Dfastblake.kernel=eight' ./gradlew test --rerun
JAVA_TOOL_OPTIONS='-Dfastblake.kernel=scalar' \
  ./gradlew jmh -P'jmh.args=Blake3Benchmark -p impl=commons,rust,java-cpu -p size=8388608 -f2 -wi 5 -i 5'
```

## Decision

Select the dedicated `AVX1_CHUNK` automatically when the effective JVM profile
is AVX1 (`HAS_AVX1_ONLY`). It uses four 128-bit integer lanes and textually
inlined shift/OR rotations; no CPU-specific or kernel-selection flags are
required from consumers. Resolving the incubating Vector API module remains the
library's existing opt-in, with scalar fallback when it is absent. This is
deliberately narrower than changing all 128-bit x86 profiles: SSE retains its
existing conservative four-chunk default, while AVX2, AVX-512 and AArch64 keep
their independently selected kernels. The ISA remains reported as `avx`.
