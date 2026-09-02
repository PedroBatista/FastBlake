# FastBlake

A fast, dependency-free BLAKE3 hash for the JVM.

```
eu.pedrobatista:fastblake:0.1.1
```

- **Fast.** 3.9× Apache Commons Codec on Apple M5, 5.8× on a Ryzen 3200G, and
  85% of the reference Rust crate — single-threaded, on the same bytes.
- **Zero dependencies.** The jar contains one package and nothing else.
- **Complete BLAKE3.** Hashing, keyed hashing, key derivation, arbitrary-length
  XOF output, incremental updates, repeatable finalization, reset.
- **Verified.** Every release passes the official BLAKE3 test vectors, in all
  three modes, across 35 input lengths and eight streaming chunk sizes.
- **No configuration.** SIMD kernels are picked from the machine's capabilities
  at startup. If the Vector API is unavailable, it falls back to scalar and
  produces identical digests.

Requires **Java 21 or newer**.

The SIMD kernels need the incubating Vector API, so start your JVM with
`--add-modules jdk.incubator.vector`. Without it FastBlake still runs and
returns identical digests, but on the scalar kernel — it logs a warning at
startup saying so. Silence that warning with `-Dfastblake.quiet=true`.

## Get it

**Gradle**

```groovy
dependencies {
    implementation 'eu.pedrobatista:fastblake:0.1.1'
}
```

**Maven**

```xml
<dependency>
    <groupId>eu.pedrobatista</groupId>
    <artifactId>fastblake</artifactId>
    <version>0.1.1</version>
</dependency>
```

To get the SIMD kernels, run your JVM with the incubating Vector API enabled:

```
--add-modules jdk.incubator.vector
```

Without it FastBlake still works, still returns the same digests, and just uses
the scalar kernel. Nothing in the public API exposes vector types, so you never
need this flag at compile time.

## Quick start

Copy this into `Example.java` and run it:

```java
import eu.pedrobatista.fastblake.FastBlake;
import java.util.HexFormat;

public class Example {
    public static void main(String[] args) {
        byte[] digest = FastBlake.hash("hello world".getBytes());
        System.out.println(HexFormat.of().formatHex(digest));
    }
}
```

```console
$ java --add-modules jdk.incubator.vector -cp fastblake-0.1.1.jar Example.java
d74981efa70a0c880b8d8c1985d075dbcbf679b99a5f9914e5aaf96b831a9e24
```

## Usage

**One-shot hash** — the fast path. Inputs up to 1 KiB never allocate a hasher.

```java
byte[] digest = FastBlake.hash(data);                    // 32 bytes
byte[] part   = FastBlake.hash(data, 16, 512, 32);       // offset, length, output length
```

**Keyed hash** — the key must be exactly 32 bytes.

```java
byte[] mac = FastBlake.keyedHash(key32, message);
```

**Key derivation** — the context string should be a hardcoded, application-unique
constant, per the BLAKE3 spec.

```java
byte[] sessionKey = FastBlake.initKeyDerivationFunction("example.com 2026 session key")
                             .update(secret)
                             .doFinalize(32);
```

**Streaming** — feed it a file or socket in whatever pieces arrive.

```java
FastBlake hasher = FastBlake.initHash();
byte[] buffer = new byte[8192];
int read;
while ((read = in.read(buffer)) != -1) {
    hasher.update(buffer, 0, read);
}
byte[] digest = hasher.doFinalize(32);
```

**Extended output (XOF)** — ask for as many bytes as you want. A short output is
always a prefix of a longer one.

```java
byte[] stream = FastBlake.initHash().update(data).doFinalize(1024);
FastBlake.initHash().update(data).doFinalize(out, 0, out.length);  // into your array
```

**Reuse** — `doFinalize` does not consume the state, so you can finalize twice
or keep reading XOF output. `reset()` returns to the initial state, keeping the
mode and key.

```java
hasher.reset();
```

Instances are mutable and **not thread-safe**; give each thread its own hasher.
The static `hash`/`keyedHash` methods are safe to call from anywhere.

### Coming from Commons Codec

`initHash`, `initKeyedHash`, `initKeyDerivationFunction`, `hash`, `keyedHash`,
`update`, `doFinalize` and `reset` match `org.apache.commons.codec.digest.Blake3`
in signature and behavior, so migration is usually just the import. FastBlake
adds offset/XOF-oriented overloads and `selectedKernel()` on top.

```java
System.out.println(FastBlake.selectedKernel());
// aarch64, preferred vector width 128 bits, ~32 vector registers -> EIGHT_CHUNK (...)
```

## Performance

8 MiB one-shot hash, single-threaded on both sides, MiB/s (higher is better).
`rust` is the reference [`blake3`](https://crates.io/crates/blake3) crate called
in-process through the FFM API, with Rayon off — it is the ceiling, not a
competitor.

| machine | Commons Codec | FastBlake | vs. Commons | Rust | % of Rust |
|---|---:|---:|---:|---:|---:|
| Apple M5 (NEON) | 537 | **2,116** | 3.9× | 2,480 | 85% |
| AMD Ryzen 3 3200G (AVX2) | 155 | **893** | 5.8× | 1,922 | 46% |
| Intel N97 (AVX2) | 157 | **716** | 4.6× | 1,429 | 50% |

All three call shapes are close together — on the M5, streaming 4 KiB at a time
costs 2.5% against one-shot. FastBlake matches or beats Commons Codec at every
measured size and shape, from 64 bytes up.

On an x86-64 JVM whose preferred Vector API width is 512 bits, FastBlake
automatically hashes 16 complete chunks in parallel using the AVX-512 wide
kernel. The selection follows the JVM's effective vector configuration, so
starting HotSpot with AVX-512 disabled safely selects a narrower kernel instead.

Older x86-64 parts are handled the same way, from the other end. On an **AVX1**
CPU — Sandy Bridge, Ivy Bridge, the Ivy Bridge-EP Xeons in a 2013 Mac Pro —
FastBlake runs the 128-bit four-chunk kernel and reports its ISA as `avx`. That
is not a fallback: AVX2, not AVX1, widened integer SIMD to 256 bits, and BLAKE3
is integer-only, so 128 bits is the entire machine for this workload. The
VEX-encoded three-operand instructions AVX1 does add are emitted by HotSpot
without any change on FastBlake's side.

Allocation is fixed per call, not proportional to input: a 8 MiB hash allocates
about 0.002 bytes per input byte, with zero collections. A hasher that has
streamed 8 MiB retains 12 KiB, most of it the batch buffer that makes streaming
fast; a hasher doing small work retains 1.4 KiB and does not grow.

Measure it yourself on your own hardware:

```bash
./gradlew jmh          # full comparison against Commons Codec and Rust
./gradlew dispatchAudit # confirm the kernel picked here is the fastest available
./gradlew testWide512  # conformance gate for the sixteen-lane AVX-512 shape
./gradlew testJar      # build a standalone benchmark/test fat JAR
./gradlew testBundleZip # build a Gradle-free transferable bundle
```

The test artifact is `build/libs/fastblake-<version>-test.jar`. Run it with
`java -jar build/libs/fastblake-<version>-test.jar -p size=8388608`; it detects
the effective CPU/vector ISA (including the AVX1, AVX2 and AVX-512 distinctions),
prints the normal JMH output, and writes a timestamped JSON report such as
`fastblake-benchmark-20260819T143012.417Z.json` in the current directory.
Use `--json-output-dir DIR` or `--json-output FILE` to control the report path.

For a computer without Gradle, build `build/distributions/fastblake-test-<version>.zip`
and copy it to the target machine. Extract it and provide the JDK directory to
the launcher:

```bash
./run-fastblake-benchmark.sh --jdk /opt/jdk-25 -p impl=java-cpu -p size=8388608
```

On Windows, use `run-fastblake-benchmark.bat --jdk C:\\JDK\\jdk-25 ...`. The
launcher validates the supplied `bin/java` and passes the Vector API module and
native-access flags required by the benchmark harness. A JDK is still required
at runtime; the current benchmark harness requires JDK 25 or newer. The bundle
removes the Gradle requirement but does not embed or redistribute a JDK.

Full protocol, per-machine detail and size sweeps are in
[`reference/performance/results/`](reference/performance/results/).

## Building from source

```bash
./gradlew test          # official BLAKE3 test vectors
./gradlew contenders    # what can be benchmarked here, and why anything can't
./gradlew jmh           # benchmarks
./gradlew releaseCheck  # conformance + jar boundary + downstream consumer smoke test
```

See [TESTING.md](TESTING.md) for the complete test procedure and the portable
benchmark bundle instructions.

## Documentation

| | |
|---|---|
| [Benchmark harness](reference/architecture/benchmark-harness.md) | Contenders, correctness gates, call shapes, threading policy |
| [Library packaging](reference/architecture/library-packaging.md) | What ships and what doesn't; release gates; compatibility policy |
| [Experiment ledger](reference/performance/experiments.md) | Every performance change, including the failed ones (E001–E028) |
| [Measured results](reference/performance/results/) | Per-machine throughput, allocation and footprint |
| [Publishing](reference/publishing/central-portal.md) | Maven Central release process |

## Status

FastBlake is at **0.1.1** and the API is not frozen yet. Until 1.0, public API
changes are allowed but documented; digest bytes never change. GPU offload and
intra-hash threading are planned.

## License

See [LICENSE](LICENSE).
