# FastBlake 0.1.3

Adds three public unkeyed BLAKE3 tree operations:

```java
public static byte[] subtreeCv(byte[] input, int offset, int length, long firstChunk);
public static byte[] parentCv(byte[] leftCv, byte[] rightCv);
public static byte[] parentRootHash(byte[] leftCv, byte[] rightCv);
```

All results are independent 32-byte arrays. CVs are eight little-endian 32-bit
words. `subtreeCv` and `parentCv` return non-root chaining values, which are
not ordinary complete-input digests. `parentRootHash` finalizes the parent
with PARENT | ROOT and output block counter zero.

Subtree slices must have positive length and valid bounds. `firstChunk` is the
absolute **1,024-byte chunk counter**, with subsequent counters incrementing
by one. Negative counters and overflow beyond `Long.MAX_VALUE` are rejected.
Tree shape depends on local leaf count, supports partial leaves and arbitrary
positive lengths, and adds no padding. Parents accept exactly 32 bytes per
child, preserve child order, and allow both inputs to reference the same array.
Inputs are never modified; independent calls can run concurrently.

Full chunk batches reuse the selected SIMD engine; scalar operation remains
available without the Vector API. Scratch allocation stays bounded regardless
of subtree length. Existing digest, streaming, keyed, derive-key, and XOF APIs
retain their behavior and Java 21 baseline.

A complete blob with only one subtree must still use `FastBlake.hash`; its
finished CV cannot recover the root hash. Proof serialization, tree positioning,
storage, range selection, and verification protocols belong to the caller.

Validation includes reproducible fixtures generated from the official Rust
reference, absolute counters above 2^32 and up to Long.MAX_VALUE, nonzero slice
offsets, 64 KiB subtree reconstruction through 8 MiB + 31 bytes, concurrency,
input immutability, invalid arguments, allocation bounds, scalar/SIMD coverage,
and consumer execution without the Vector API. Existing official-vector suites
remain in the release gate.
