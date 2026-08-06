# E010 — why every Vector API kernel failed: box elimination, not register pressure

Date: 2026-08-06
Type: diagnostic investigation (no production code changed)
Ledger entry: see `experiments.md` § E010, which summarises and links here.

## Summary

E002–E008 were each rejected with an explanation built on register pressure,
spilling, method size, or the Vector API being unsuited to this JVM. Those
explanations are wrong, or at best incidental.

**Every rejected vector kernel allocates 159–255 bytes of heap per input byte.
The scalar default allocates zero.** C2 is failing to eliminate `IntVector`
boxes, so each vector operation heap-allocates a wrapper object and runs through
generic fallback code. Those kernels never executed a NEON instruction in the
hot path. Their 20–32 MiB/s scores measure boxed scalar code plus a garbage
collection storm — they are not evidence about SIMD.

This matters beyond bookkeeping: the "rules learned" recorded in E006 and E008
steer future work *away* from chunk-parallel SIMD, which is the single largest
known throughput opportunity and the one the reference Rust implementation uses
to reach 2500 MiB/s.

The guide already required this check — §7.1 ("Target zero allocation … verify
with JMH's gc profiler/JFR, not assumption") and §10.7 ("Add -prof gc to prove
allocation claims"). It was never run for E002–E008. One allocation gate would
have caught E002 immediately and saved six experiments.

## Environment

- Machine: Apple M5, 10 cores (4 performance via `hw.perflevel0.physicalcpu`,
  6 efficiency via `hw.perflevel1.physicalcpu`)
- OS: macOS 26.6
- JVM: Temurin OpenJDK 25.0.4+7-LTS
- Measurement: standalone drivers, not JMH. Deliberate: the questions here are
  "does this method get compiled" and "does this allocate", which need JIT logs
  and per-thread allocation counters rather than steady-state scores. Throughput
  figures below are therefore indicative and consistent with the JMH numbers in
  the ledger, not replacements for them.

## Evidence 1 — allocation per hashed byte

Driver: hash an 8 MiB buffer three times after three warmup hashes, reading
`com.sun.management.ThreadMXBean.getThreadAllocatedBytes` around the measured
region. Heap fixed at `-Xmx2g`.

| kernel | throughput | allocated | per input byte |
|---|---:|---:|---:|
| E009 scalar (default) | 827 MiB/s | 15,120 B | **0.00** |
| E008 `heapChunkVector` | 32 MiB/s | 3,995,420,784 B | **158.76** |
| E004 `chunkVector4` | 31 MiB/s | 3,998,758,920 B | **158.90** |
| E006 `roundCacheVector` | 21 MiB/s | 5,360,077,896 B | **212.99** |
| E005 `lowLiveVector` | 22 MiB/s | 6,407,500,872 B | **254.61** |

Roughly 4 GB of garbage to hash 24 MB. Cross-checked with `-Xlog:gc` over
6 × 8 MiB: the scalar path causes **0** young collections, `heapChunkVector`
causes **34**.

Note the ordering: E005 `lowLiveVector`, which was explicitly designed to reduce
the live vector set, allocates the *most*. Under the register-pressure theory it
should have been the best of the vector kernels. Under the boxing theory it is
the worst because it materialises the most vector values (112 message vectors
per block instead of 16). The data fits the boxing theory and contradicts the
register-pressure theory.

## Evidence 2 — two plausible causes, tested and discarded

Both were my hypotheses before measuring. Recording them so they are not retried.

**Huge-method limit.** `DontCompileHugeMethods` defaults to `true` and blocks JIT
compilation of methods over 8000 bytecodes. `Blake3ChunkVectorRoundCache::hashChunks`
is 11,433 bytes, over the limit, and never appears in a `-XX:+PrintCompilation`
log except through its tiny `xor`/`ror` helpers. That looked decisive. It is not:

| E006 `roundCacheVector` | throughput |
|---|---:|
| default | 21.0 MiB/s |
| `-XX:-DontCompileHugeMethods` | 20.7 MiB/s |

Lifting the limit lets C2 compile the method and changes nothing.

**Tiered compilation.** C1 refuses these methods outright:

```
COMPILE SKIPPED: out of virtual registers in LIR generator (retry at different tier)
```

— reported for both `Blake3ChunkVectorHeap::hashChunks` (7051 bytes) and
`Blake3ChunkVectorRoundCache::hashChunks` (11433 bytes). C2 then compiles them,
often via OSR, and the tier-4 entry is subsequently marked `blocked`. So the
tiered pipeline is genuinely disturbed. But forcing C2-only changes nothing:

| kernel | tiered (default) | `-XX:-TieredCompilation` |
|---|---:|---:|
| E008 `heapChunkVector` | 32.6 MiB/s | 32.4 MiB/s |
| E004 `chunkVector4` | 31.2 MiB/s | 31.2 MiB/s |
| E009 scalar | 808 MiB/s | 909 MiB/s |

The C1 register-allocation failure is real and worth knowing, but it is not the
cause of the throughput collapse.

Method sizes for reference (largest method per kernel class, from `javap`
bytecode offsets; the two marked \* are confirmed by `PrintCompilation`):

| class | `hashChunks` bytecodes |
|---|---:|
| `Blake3ChunkVectorRoundCache` | 11,433 \* |
| `Blake3ChunkVectorLowLive` | ~7,527 |
| `Blake3ChunkVector4` | ~7,161 |
| `Blake3ChunkVectorHeap` | 7,051 \* |
| `Blake3ScalarUnrolled` (compiles fine) | ~5,016 |

## Evidence 3 — box elimination is fragile and non-monotonic

A 128-bit `IntVector` dependency chain across a loop back-edge, 20,000,000
iterations, **one JVM per variant**:

| kernel body | ns/iter | allocated |
|---|---:|---:|
| `add` | 4.911 | 48.0 B/iter |
| `add + xor` | 5.379 | 48.0 B/iter |
| `add + xor + ROR` | 1.770 | **0 B** |
| `add + xor + shift/or rotate` | 1.754 | **0 B** |

The *simplest* loop boxes. Adding work makes it stop boxing and run 3× faster.
Box elimination is not monotonic in code size, so it cannot be predicted from
source — it must be measured.

Two secondary conclusions:

- `VectorOperators.ROR` is fine on AArch64 (0 bytes, 1.770 ns). The manual
  shift/or rotate is not needed as a workaround; it is equal within noise.
- E007's conclusion that vector rotate is intrinsified stands.

**Measurement caution.** Running all four variants in one JVM gives a different
answer for the ROR case — 48 B/iter and 3.691 ns instead of 0 B and 1.770 ns.
The same bytecode boxes or does not box depending on what else that JVM compiled
(here, a call site that became megamorphic across four lambdas). Any future
allocation check must isolate each kernel in its own JVM.

An earlier probe (`BoxTest`) compared 8 live vectors in straight-line code
against 32 vectors held in an `IntVector[]`; both allocated (720 and 2304
bytes per round). It shares the shared-JVM flaw above and is superseded by the
isolated results, but it is consistent with them.

## Evidence 4 — the scalar loader

Isolated measurement of BLAKE3's per-block little-endian word loading over
8 MiB (131,072 blocks), best of 12:

| loader | time |
|---|---:|
| current manual byte shifts | 0.835 ms |
| cached little-endian `VarHandle` | 0.238 ms |
| | **3.51× faster** |

An 8 MiB hash at 894 MiB/s takes 8.95 ms, so the 0.597 ms saved is **≈6.7%
end-to-end** if nothing else changes. This confirms E009's own "rule learned",
which nominated the cached little-endian VarHandle as the next scalar test.

```java
private static final VarHandle LE_INT = MethodHandles
        .byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
// words[i] = (int) LE_INT.get(input, offset + i * 4);
```

## Evidence 5 — how much scalar headroom remains

BLAKE3 compression is 7 rounds × 8 G functions × 14 arithmetic ops = 784 ops per
64-byte block. At E009's 894 MiB/s that is 14.65e6 blocks/s, or 11.5e9 ops/s.
At an M5 P-core's ~4.4–4.6 GHz that is **2.5–2.6 arithmetic ops per cycle**,
against an integer issue width several times wider.

The limiter is the dependency structure, not the instruction count: one BLAKE3
compression exposes only 4-way ILP per half-round (4 independent G functions),
and each G is an 8-deep dependent chain. This is what makes a two-chunk scalar
interleave worth one experiment — see idea 4.

## Process fix

Add a hard allocation gate to the measurement protocol in `experiments.md`:

> Every kernel candidate must be measured for bytes allocated per input byte, in
> its own JVM, **before** any throughput number is recorded. A kernel allocating
> materially more than zero is not measuring the algorithm it claims to
> implement; discard the throughput figure rather than recording it as evidence.

`-prof gc` in JMH, or `ThreadMXBean.getThreadAllocatedBytes` in a standalone
driver, both work. The guide already requires this; the gate makes it
unskippable.

## Ideas, ranked by expected value

### 1. Re-open 4-lane NEON, built allocation-first — target ~2× (894 → ~1800–2400 MiB/s)

The headroom is demonstrated, not speculative: the reference Rust implementation
reaches 2500 MiB/s using exactly this 4-lane NEON chunk-parallel structure on
this machine. The reason Java failed to reach it is now known and mechanical.

Method, and this is the part that differs from E002–E008: build the kernel
incrementally and measure bytes/byte after *every* addition, stopping the moment
allocation goes non-zero, then restructure at that boundary. Do not write a
7000-bytecode kernel and measure at the end.

The untried structural variant to try first: transpose the message words once
per block into a scratch `int[64]`, keep only the 16 *state* vectors as vector
values, and read message words back from the array as needed. Every one of
E004–E008 kept messages as live vector values — as a full block-wide cache
(E004, E008), retransposed per use (E005), or retransposed per round (E006).
Reading them from an L1-resident array is cheap and is the one point in that
design space nobody has sampled.

### 2. Fix the scalar data path — ~15–25%, low risk, no Vector API

Three independent wins, each testable alone:

- **Word loader.** Replace manual byte shifts with the cached little-endian
  `VarHandle`. Measured 3.51× on the loader, ≈6.7% end-to-end.
- **Direct-input bypass.** `update()` currently `System.arraycopy`s every byte
  through the 1 KiB `chunk[]` buffer (`FastBlake.java:241`). When a full chunk is
  available, compress straight from the caller's array. This removes an entire
  read+write pass over 8 MiB. This is zeebo/blake3's technique, already noted in
  the guide §16.2.
- **Message locals.** Messages round-trip through an `int[16]`, so compression
  performs 112 bounds-checked array loads per block. Named locals would remove
  them, exactly as E009 did for the state words.

### 3. Multicore contender — ~6–8×, the largest absolute number available

4 P-cores plus 6 E-cores. Even at the current 894 MiB/s, coarse subtree
parallelism across ~6 cores exceeds 5 GB/s, which beats single-threaded Rust
outright. Guide §9 and P4 already specify the design: aligned subtrees, a size
threshold, an explicit method rather than an implicit `update()` behaviour.

This requires the `rust-mt` contender (the `blake3` crate's `rayon` feature and
`update_rayon`) to be a fair comparison — otherwise it repeats the handicap-match
problem already flagged for the GPU contender, where a multi-core implementation
is scored against a deliberately single-threaded opponent.

### 4. Scalar two-chunk interleave — uncertain, one cheap experiment

Compression sustains only ~2.6 ops/cycle (Evidence 5) because a single BLAKE3
compression exposes 4-way ILP per half-round. Interleaving two independent chunks
doubles the available parallelism with no Vector API involvement.

The risk is concrete: 2 × 16 state words = 32 live ints against 31 general
purpose registers on AArch64, so it may spill immediately and gain nothing. Cheap
to test and easy to abandon.

### Suggested sequence

2 → 1 → 3. Idea 2 is low-risk, compounds with everything after it, and its
largest component is already measured. Idea 1 is where the algorithmic prize is
and is now unblocked. Idea 3 is the largest absolute win but changes what the
project is comparing, so it needs the fair-comparison work first.

## Reproducing this

Diagnostic drivers were written to the session scratchpad rather than the repo.
The essential ones are small enough to reproduce from the descriptions above; the
allocation driver is the one worth keeping:

```java
ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
long tid = Thread.currentThread().threadId();
// ... warm up, then:
long before = bean.getThreadAllocatedBytes(tid);
// ... hash N bytes ...
long allocated = bean.getThreadAllocatedBytes(tid) - before;
// report allocated / bytesHashed
```

Commands used:

```bash
# is the kernel compiled at all?
java --add-modules jdk.incubator.vector -cp build/classes/java/main:$SP \
  -Dfastblake.experimental.roundCacheVector=true -XX:+PrintCompilation Driver 4 \
  | grep -iE "hashChunks|COMPILE SKIPPED|not compilable|too large"

# discarded hypotheses
java ... -XX:-DontCompileHugeMethods Driver 8
java ... -XX:-TieredCompilation Driver 10

# allocation, one JVM per kernel
java ... -Xmx2g -Dfastblake.experimental.heapChunkVector=true AllocDriver

# GC cross-check
java ... -Xlog:gc -Xmx2g Driver 6 | grep -c "Pause Young"

# method sizes
javap -c -p build/classes/java/main/com/pedrobatista/fastblake/Blake3ChunkVector4.class
```
