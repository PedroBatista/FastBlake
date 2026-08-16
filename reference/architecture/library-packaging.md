# Library and experiment boundary

FastBlake has two responsibilities: provide a stable, dependency-free BLAKE3
library and provide a reproducible laboratory for new kernels. They share the
repository, but only the production implementation belongs in the published
artifact.

## Current boundary

`src/main` is the library source set. `src/harness`, `src/test`, and `src/jmh`
are development-only source sets. The Gradle `jar` task currently includes the
production classes only; `verifyLibraryJar` checks the generated artifact and
rejects harness, benchmark, and test-vector entries. `releaseCheck` runs the
test suite, creates the Maven POM, and performs that jar check.

The measured four- and eight-chunk kernels remain in `src/main`. Historical
block, preferred-width, heap, low-live, round-cache, wide, parent, and original
vector kernels are compiled from `src/experiment` and are not included in the
library jar. They are package-private and are available to JMH through its
experiment classpath.

## Extraction target

New rejected and diagnostic kernels go into `src/experiment`. Their switches
must remain in experiment-only runners. Production dispatch retains only the
measured scalar, four-chunk, eight-chunk and preferred-width choices and the
single diagnostic override `fastblake.kernel=auto|scalar|four|eight|wide`.

E028 added `wide` and, with it, the only case where a kernel ships without any
machine selecting it automatically. That is not a loophole in the rule above: it
is conformance-tested and production-quality, and the dispatch table simply has
no measurement authorising it yet. `-Dfastblake.wideBits=128|256|512` pins its
lane width and is a **correctness lever for tests only** — a pinned species
wider than the hardware is emulated by splitting, so it is always slower than
the native width and must never appear in a throughput measurement.

Experiments should call a package-private kernel contract or a dedicated
experiment runner. The public `FastBlake` API must not grow experiment classes,
flags, benchmark dependencies, or machine-specific behavior.

## Release gates

Before publishing an artifact:

1. Run `./gradlew releaseCheck`.
2. Run forced scalar/four/eight conformance where the Vector API is available.
3. Inspect `build/libs/FastBlake-*.jar` through `verifyLibraryJar`.
4. Run `consumerSmokeTest` against the generated jar rather than project
   classes.
5. Record 8 MiB throughput, allocation rate, and retained footprint for the
   reference machines in `reference/performance/results/`.

The jar is intentionally dependency-free. Commons Codec, JMH, JOL, the Rust
contender, official test vectors, and experiment notes remain development
assets.

## Compatibility policy

Until version 1.0, public API changes are allowed but must be documented. A
1.0 release should freeze the `FastBlake` API, state the minimum JDK, document
the optional `jdk.incubator.vector` module requirement, and publish source and
Javadoc jars alongside the binary. Automatic dispatch may change performance,
but never digest bytes.

The overlapping Commons Codec `Blake3` API is the compatibility baseline:
`initHash`, `initKeyedHash`, `initKeyDerivationFunction`, `hash`, `keyedHash`,
`update`, `doFinalize`, and `reset` have matching signatures and behavior where
the concepts overlap. FastBlake additionally exposes offset/XOF-oriented
extensions and diagnostics such as `selectedKernel`; those are intentionally
FastBlake-specific.
