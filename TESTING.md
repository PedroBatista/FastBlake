# FastBlake testing and benchmark instructions

This document covers both repository verification and the portable benchmark
bundle. The library itself requires Java 21 or newer. The benchmark harness is
compiled with the project toolchain and currently requires JDK 25 or newer
because it uses the finalized Foreign Function & Memory API.

## Tests on a development machine

Run the complete conformance suite from the repository root:

```bash
./gradlew test
```

The suite compares all available contenders with the official BLAKE3 vectors.
The Rust contender is optional; if its toolchain or native library is
unavailable, it is reported as skipped and the Java/Commons tests still run.

To exercise the sixteen-lane wide kernel on a machine that does not have
AVX-512, use the Vector API's split-species emulation:

```bash
./gradlew testWide512
```

This validates correctness only. It is not a performance measurement.

For a local benchmark from Gradle:

```bash
./gradlew jmh
./gradlew jmh -P'jmh.args=oneShot -p impl=java-cpu -p size=8388608 -f 1'
./gradlew dispatchAudit
```

`dispatchAudit` compares the selectable kernels and reports whether automatic
dispatch chose the fastest measured option on the current machine.

## Build the portable benchmark bundle

Gradle is needed only to build the bundle:

```bash
./gradlew testBundleZip
```

This creates:

```text
build/distributions/fastblake-test-<version>.zip
```

The archive contains:

```text
fastblake-test.jar                 fat executable JAR
run-fastblake-benchmark.sh         Unix/macOS launcher
run-fastblake-benchmark.bat        Windows launcher
```

Copy and extract the ZIP on the target computer. The target computer does not
need Gradle, the FastBlake source tree, or the project dependency cache.

## Run on another computer

The launcher takes the JDK directory explicitly. The directory must contain an
executable `bin/java` (or `bin/java.exe` on Windows).

Unix/macOS:

```bash
./run-fastblake-benchmark.sh \
  --jdk /opt/jdk-25 \
  -p impl=java-cpu \
  -p size=8388608
```

The same launcher accepts the compact form:

```bash
./run-fastblake-benchmark.sh --jdk=/opt/jdk-25 -p impl=java-cpu
```

Windows:

```bat
run-fastblake-benchmark.bat --jdk C:\Java\jdk-25 -p impl=java-cpu -p size=8388608
```

If `--jdk` is omitted, the launchers use `JAVA_HOME`. They fail early with a
clear error if no usable JDK is supplied. A JDK is necessarily still required
at runtime: the bundle removes the Gradle requirement but does not embed or
redistribute a JDK.

The launcher automatically adds:

```text
--add-modules jdk.incubator.vector
--enable-native-access=ALL-UNNAMED
```

Those flags enable the SIMD kernels and the optional Rust FFM contender.

## Choosing benchmarks and contenders

The arguments after the launcher options are ordinary JMH arguments. Useful
examples:

```bash
# Java CPU only, one size, short smoke run
./run-fastblake-benchmark.sh --jdk /opt/jdk-25 \
  -p impl=java-cpu -p size=1048576 -f 1 -wi 1 -i 1 -w 100ms -r 100ms

# Compare every contender available on the target machine
./run-fastblake-benchmark.sh --jdk /opt/jdk-25 -p size=8388608

# Select benchmark methods by name
./run-fastblake-benchmark.sh --jdk /opt/jdk-25 \
  '.*Blake3Benchmark.*' -p impl=java-cpu -p size=8388608
```

`java-cpu` and `commons` are normally available. The portable bundle does not
embed the platform-specific Rust library, so `rust` is normally reported as
skipped there; the harness explains why. GPU support is currently reported as
unavailable.

## JSON reports

After a benchmark run, the entry point writes a report in the current directory
with a UTC timestamp in its filename:

```text
fastblake-benchmark-20260819T162940.534Z.json
```

Choose another directory or an exact filename with:

```bash
--json-output-dir results
--json-output results/my-run.json
```

The report includes:

- run timestamp and the JMH arguments;
- architecture and effective ISA;
- preferred Vector API width and estimated vector-register count;
- widest intrinsified integer and floating-point vector widths;
- effective AVX1, AVX2 and AVX-512 flags;
- the selected FastBlake kernel and dispatch reason;
- each benchmark's parameters, score, score unit, error, sample count, and
  BLAKE3 throughput in MiB/s where an input size is available.

## CPU capability detection

`os.arch` identifies the broad architecture, but it is not enough to distinguish
SSE, AVX1, AVX2 and AVX-512: all are reported as `x86_64`. FastBlake therefore
probes the Vector API after the JVM starts and reports the effective
configuration from two numbers:

- `IntVector.SPECIES_PREFERRED` — the width shared by *every* lane type, and the
  width the kernels are built at;
- `VectorSpecies.ofLargestShape(int.class)` and `ofLargestShape(double.class)` —
  the widest integer and floating-point vectors this JVM will actually compile.

| Preferred | Max int | Max FP | Reported effective ISA | Typical x86 meaning |
|---:|---:|---:|---|---|
| unavailable | — | — | `scalar` | Vector API not enabled/available |
| 128 bits | 128 | 128 | `x86-vector-128` | SSE-width effective configuration |
| 128 bits | 128 | 256 | `avx` | **AVX1**: 256-bit FP, 128-bit integer |
| 256 bits | 256 | 256 | `avx2` | AVX2-width effective configuration |
| 512 bits | 512 | 512 | `avx512` | AVX-512-width effective configuration |

The AVX1 row is why the second and third columns exist. AVX2, not AVX1, widened
*integer* SIMD to 256 bits; AVX1 widened only the floating-point datapath.
HotSpot encodes that limit directly (`UseAVX=1` permits 32-byte `float`/`double`
vectors and 16-byte integral ones), so on a Sandy Bridge or Ivy Bridge part —
including the Ivy Bridge-EP Xeons in a 2013 Mac Pro — the preferred shape is
128 bits. BLAKE3 is add/xor/rotate on 32-bit words with no floating-point work,
so 128 bits is the widest useful vector shape on an AVX1 machine. Width is not
the same as profitability, however. E029 measured the ordinary four-chunk
kernel on a 2013 Mac Pro with Temurin JDK 25.0.4: C2 materialised Vector API
wrappers, allocating about 714 MB per 8 MiB hash. E030 isolated the composite
`VectorOperators.ROR` lowering and recovered allocation-free SIMD by textually
inlining shift/OR rotations. Automatic dispatch therefore uses the dedicated
AVX1 kernel while continuing to report the ISA as `avx`; consumers need no JVM
flags.

Forcing a wider integer species anyway (`-Dfastblake.wideBits=256`) does not
fail. It stops being intrinsified and runs the Vector API's Java fallback:
still correct, dramatically slower. `describe()` calls that case out by name.

On AArch64, a 128-bit preferred species is reported as `neon`. The probe uses
the JVM's effective vector settings, so startup restrictions such as `-XX:UseAVX`
and `-XX:MaxVectorSize` are respected. A CPU may physically support AVX-512 while
a JVM configured to use only 256-bit vectors reports and runs as effective AVX2;
that is the safe behavior for the process actually executing the benchmark.
Likewise `-XX:UseAVX=1` on a modern part reports and runs as `avx`, which is the
correct answer for that process.

The first lines of every portable run print the same capability summary that is
stored in JSON, making it easy to verify that a remote machine selected the
expected kernel before trusting its measurements.
