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

The current kernel classes are still compiled from `src/main` because
`FastBlake` retains historical forced-dispatch paths for reproducible
experiments. They are package-private and are not public API, but this is an
intermediate state. They must not be referenced by new library API code.

## Extraction target

When the next kernel refactoring starts, add `src/experiment` and
`src/experimentTest` source sets. Move rejected and diagnostic kernels there,
along with the `fastblake.experimental.*` switches. Production dispatch should
retain only the measured scalar, four-chunk, and eight-chunk choices and the
single diagnostic override `fastblake.kernel=auto|scalar|four|eight`.

Experiments should call a package-private kernel contract or a dedicated
experiment runner. The public `FastBlake` API must not grow experiment classes,
flags, benchmark dependencies, or machine-specific behavior.

## Release gates

Before publishing an artifact:

1. Run `./gradlew releaseCheck`.
2. Run forced scalar/four/eight conformance where the Vector API is available.
3. Inspect `build/libs/FastBlake-*.jar` through `verifyLibraryJar`.
4. Run the consumer smoke test against the generated jar rather than project
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
