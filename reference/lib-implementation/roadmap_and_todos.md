

The cleanest route is to treat FastBlake as two related products:

- A small, stable library that users depend on.
- A research workspace containing experimental kernels, benchmarks, diagnostics, Rust comparisons, and performance records.

The current source-set separation is a good start, but it is incomplete: all experimental kernels currently live in src/main and are therefore included in the published jar.

## Proposed target structure

FastBlake/
├── src/main/                 # production library only
├── src/test/                 # production correctness/API tests
├── src/experiment/           # unshipped experimental kernels
├── src/experimentTest/       # experimental conformance tests
├── src/harness/              # contender adapters and official vectors
├── src/jmh/                  # production and experimental benchmarks
├── native/rust-blake3/       # optional benchmark contender
└── reference/
├── performance/
├── experiments/
└── rfc/

A Gradle multi-project layout could come later, but another source set is the safest first migration. It avoids disrupting package-private performance code while immediately
controlling what enters the jar.

## 1. Define the public library contract

Only FastBlake is currently public. We should explicitly freeze and document its supported API:

- initHash()
- initKeyedHash(...)
- initKeyDerivationFunction(...)
- hash(...)
- update(...)
- doFinalize(...)
- reset()

Before a 1.0 release, I would evaluate a few usability additions:

- Constants such as HASH_LENGTH and KEY_LENGTH.
- Convenience digest() naming, possibly alongside doFinalize().
- ByteBuffer input support.
- InputStream support as a separate utility, not in the hot core.
- Clear bounds-checking and exception contracts.
- Thread-safety and instance-reuse documentation.
- A stable diagnostic API for the selected implementation.

The API should expose BLAKE3 behavior, not implementation classes or experiment names.

## 2. Classify every kernel

Create an explicit lifecycle:

Classification      Meaning                                          Shipped?
━━━━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  ━━━━━━━━━━
Production          Correct, measured, selected on supported CPUs         Yes
──────────────────  ───────────────────────────────────────────────  ──────────
Candidate           Correct and promising, still being evaluated           No
──────────────────  ───────────────────────────────────────────────  ──────────
Rejected/control    Retained to reproduce an experiment                    No
──────────────────  ───────────────────────────────────────────────  ──────────
Benchmark-only      Artificial diagnostic or micro-kernel                  No

Based on the current code, likely production candidates are:

- Scalar compressor.
- Four-chunk SIMD kernel.
- Eight-chunk/dual SIMD kernel.
- Capability detection and production dispatch.

Classes such as block-vector, heap-vector, low-live, round-cache, wide-vector, parent-vector, and old scalar controls should move to src/experiment.

The final classification should follow the experiment ledger rather than class names alone.

## 3. Remove experimental switches from production dispatch

FastBlake presently knows about every historical experiment through properties such as:

fastblake.experimental.blockVector
fastblake.experimental.heapChunkVector
fastblake.experimental.parentVector
...

That creates several problems:

- Experimental classes must remain in the distributed jar.
- Static initialization becomes fragile.
- Users can accidentally select rejected implementations.
- Production code accumulates branches that should never execute normally.
- Every new experiment changes the library implementation.

Production should only recognize:

fastblake.kernel=auto|scalar|four|eight

Experiments should be selected by an experiment-specific runner or factory under the experiment source set. Historical properties can remain there so recorded commands stay
reproducible.

## 4. Separate algorithm state from compression kernels

FastBlake currently contains both the public hasher state machine and dispatch to numerous kernels. Introduce a small internal production boundary, for example:

interface ChunkKernel {
int chunksPerBatch();
void hashChunks(...);
}

Production dispatch would choose from a fixed set:

ScalarKernel
FourChunkKernel
EightChunkKernel

Experimental kernels can implement the same internal contract in src/experiment.

This makes experiments comparable without making them part of the public API. The interface itself should remain package-private or be marked as an internal testing SPI with no
compatibility promise.

Avoid virtual calls inside the compression loop: choose the kernel once, then enter a monomorphic/static hot path. The abstraction is for organization and testing, not necessarily
runtime polymorphism.

## 5. Preserve reproducible experiments

Each experiment should have:

reference/experiments/eNNN-name/
├── README.md
├── results-apple-m5.md
├── results-intel-n97.md
└── optional supporting artifacts

Its record should contain:

- Commit hash.
- JDK vendor and exact version.
- OS and CPU.
- Exact command.
- Kernel-selection properties.
- Benchmark protocol and forks.
- Throughput and allocation results.
- Correctness result.
- Assembly observations where relevant.
- Conclusion: promote, retain, modify, or reject.

The source code remains under src/experiment, preferably with the E-number in its Javadoc. Rejected experiments should not be deleted unless Git history alone is sufficient to
reproduce them.

Machine-readable results worth retaining should not live only in ignored build/.

## 6. Make experimental conformance automatic

Every experimental kernel must pass the same official vectors as production before it can be benchmarked seriously.

Add tasks along these lines:

./gradlew test                 # shipped library only
./gradlew experimentTest       # every retained experiment
./gradlew jmh                  # normal comparison
./gradlew experimentJmh        # explicitly selected experimental kernels
./gradlew releaseCheck         # everything required for publication

test should remain fast enough for ordinary development. releaseCheck should include:

- Official BLAKE3 vectors.
- Incremental boundary tests.
- Keyed and derive-key modes.
- XOF behavior.
- Repeated finalization.
- Reset and offsets.
- Forced scalar/four/eight kernels.
- Vector API absent/fallback behavior.
- Jar-content inspection.
- Consumer smoke tests.

## 7. Guarantee that experiments cannot leak into the jar

Add a verification task that opens the generated jar and rejects:

- Experimental kernel classes.
- Harness classes.
- JMH classes.
- Commons Codec classes.
- JUnit classes.
- Test vectors.
- Native Rust artifacts.
- Performance documents.

Also verify the published POM has no runtime dependencies unless deliberately introduced.

This should run as part of check and publication.

## 8. Decide the Vector API distribution contract

This is the most important packaging decision.

The current code compiles against jdk.incubator.vector, while the runtime can fall back to scalar when the module is unavailable. Users need clear documentation that SIMD may
require:

java --add-modules jdk.incubator.vector ...

There are two viable release models:

1. One artifact, current approach:
    - Dependency-free jar.
    - Scalar fallback.
    - SIMD enabled when the Vector API module is available.
    - Simplest now, but tied to a recent JDK and an incubating API.

2. Scalar core plus optional vector artifact:

   fastblake-core
   fastblake-vector
    - Core can target an older Java baseline.
    - Vector implementation can target the required modern JDK.
    - Cleaner long-term compatibility.
    - More complex dispatch and testing.

For an initial release, I recommend one Java 25 artifact, with the Vector API requirement documented precisely. Once the public API is stable, splitting the implementation behind
it remains possible.

## 9. Add real library publishing metadata

The build currently lacks publication configuration. Add:

- java-library
- maven-publish
- signing
- Source jar.
- Javadoc jar.
- Artifact name and description.
- Project URL.
- SCM coordinates.
- Apache-2.0 license metadata.
- Developer metadata.
- Reproducible archive settings.
- Maven Central publishing configuration.
- Snapshot and release version policy.

The likely coordinate can initially be:

eu.pedrobatista:fastblake:0.1.0

The `eu.pedrobatista` namespace is registered in Sonatype Central Portal and is
the coordinate used by the first publication.

## 10. Add a consumer integration test

Create a tiny separate Gradle test project that depends only on the generated jar, not project classes. It should verify:

- One-shot hashing.
- Streaming hashing.
- Keyed hashing.
- XOF output.
- Running without the Vector API flag.
- Running with the Vector API flag.
- No undeclared dependencies.
- Correct behavior from both classpath and module path, if module-path support is promised.

This catches packaging errors that ordinary in-project tests cannot see.

## 11. Establish compatibility rules

Before 1.0:

- Semantic versioning.
- No public experimental classes.
- Public API changes recorded in release notes.
- Binary compatibility checking between releases.
- Minimum supported JDK explicitly stated.
- Supported architectures listed separately from “expected to work.”
- Automatic kernel selection must never change the digest, only performance.
- Unknown CPUs should use a conservative kernel.
- Overrides must remain available for diagnosing fleet-specific regressions.

## 12. Release gates

A production release should require:

- All correctness tests green.
- No experimental code in the jar.
- No runtime dependencies.
- Consumer smoke test green.
- Scalar fallback green.
- SIMD kernels green on available CI architectures.
- Allocation limits green.
- Retained-footprint limits green.
- No material 8 MiB regression on the reference machines.
- Small-input regression checks retained even though 8 MiB is the primary workload.
- Performance results recorded against the release candidate commit.
- Signed source, Javadoc, and binary artifacts.

## Recommended implementation order

1. Write the production/experimental classification document.
2. Create experiment and experimentTest source sets.
3. Move rejected and diagnostic kernels out of main.
4. Remove fastblake.experimental.* handling from FastBlake.
5. Create a clean production kernel selector.
6. Preserve experiment selection in an experiment runner.
7. Add jar-content and dependency checks.
8. Add consumer integration tests.
9. Improve and freeze the public API.
10. Add publication and signing configuration.
11. Run correctness, allocation, footprint, and M5/N97 performance gates.
12. Publish an initial 0.x release before declaring API stability.

The key principle is: experiments remain first-class citizens of the repository, but they are no longer first-class citizens of the distributed artifact. That preserves
FastBlake’s performance laboratory without making downstream users carry its entire history.

