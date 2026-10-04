# Independent tree fixtures

`reference_impl.rs` is the unchanged official BLAKE3 reference from tag **1.8.2**:
https://github.com/BLAKE3-team/BLAKE3/blob/1.8.2/reference_impl/reference_impl.rs

SHA-256: `6e89a18be72e3c4d838644e1796e04d896cb4a16bd7bb803d4380d7a363fbfd2`.
Upstream offers Apache 2.0, Apache 2.0 with LLVM exceptions, or CC0; the
corresponding license files are retained in this directory. These sources and
fixtures are test tools and do not ship in the library JAR.

Run `python3 tools/tree-reference/generate.py` with `rustc` on PATH to regenerate
`src/test/resources/tree-reference.tsv`. No Cargo packages or network access
are needed. Normal Java tests use the checked-in fixtures without requiring Rust.

The script appends `harness.rs` to the unmodified reference, giving the harness
access to its private `ChunkState`, `Output`, `parent_cv`, and `parent_output`.
Leaves are initialized with explicit absolute counters; recursive splitting
uses local leaf counts. Parent fixtures include arbitrary child words and
identical children. Complete hashes come from the reference's ordinary Hasher.
Input byte i is `(i * 131 + (i >> 8) * 17 + 29) mod 256`.

`releaseCheck` runs the fixtures with scalar, four, AVX1, eight, and wide (512-bit)
kernels in separate JVMs. Consumer smoke tests exercise tree reconstruction on
Java 21 and the build JVM without resolving the Vector API. The allocation
test measures warmed per-thread allocated bytes for 64 KiB and 8 MiB inputs,
with a fixed upper bound and tolerance for runtime overhead; it skips when
thread allocation measurement is unavailable or a forced wider vector species
is emulated by the JVM; correctness fixtures still run for those species.
