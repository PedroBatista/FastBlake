# E012 — cross-architecture validation of E001–E011 on x86-64

Date: 2026-08-06
Type: validation investigation (no production code changed)
Ledger entry: see `experiments.md` § E012, which summarises and links here.

## Summary

Every prior entry in this ledger was measured on one machine: an Apple M5, macOS,
Temurin 25.0.4, 128-bit NEON. E011 was explicitly retained opt-in pending
"validation on at least one non-AArch64 JVM". This document supplies that
validation on an Intel N97 running Linux with AVX2, and also re-runs the E010
allocation diagnosis to determine which of its findings are properties of C2 and
which are properties of AArch64.

Four things transfer, two do not.

**Transfers, to three significant figures:**

1. Correctness. All seven experimental kernels and the scalar default pass the
   full official-vector suite on x86-64.
2. E010's allocation diagnosis. E004–E008 allocate 159.02, 159.02, 212.99 and
   254.97 bytes per input byte here, against 158.76, 158.90, 212.99 and 254.61
   on the M5.
3. E011's seven-round escape-analysis cliff. **43,776 bytes per invocation on
   both architectures**, the same number.
4. E011's allocation gate. 0.00071 bytes per input byte, zero collections.

**Does not transfer:**

5. E009's scalar advantage over Commons Codec: 1.61x on the M5, **1.20x** here.
6. E011's standing against Rust: 63% of Rust on the M5, **38%** here.

Finding 6 is not a new compiler pathology. `Blake3ChunkVectorScratch` hard-codes
`IntVector.SPECIES_128`. That is the full machine width on NEON and **half** the
machine width on AVX2, while the `blake3` crate dispatches to its 8-way AVX2
kernel. E011 is racing four lanes against eight.

The uncomfortable part is that nothing in the project detects this. A kernel that
uses half the available SIMD width is still correct, still passes the allocation
gate, and still beats the scalar path — by a *wider* margin here than on the M5.
Vector width is a portability property that has to be measured per architecture,
exactly the way E010 established that allocation has to be.

## Environment

Environment B in `experiments.md`.

- Machine: Intel N97, 4 cores / 4 threads, no SMT
  - Alder Lake-N, family 6 model 190 stepping 0; Gracemont **E-cores only**,
    there is no P-core on this part
  - 3.6 GHz max, 800 MHz min
  - L1d 128 KiB (4 instances), L1i 256 KiB (4), L2 2 MiB (1), L3 6 MiB (1)
  - 14 GiB RAM, 2 GiB swap
- Relevant ISA flags: `avx`, `avx2`, `bmi1`, `bmi2`, `sse4_2`, `sha_ni`, `vaes`,
  `vpclmulqdq`, `avx_vnni`, `gfni`. **No AVX-512 of any kind.**
- OS: Linux 7.0.0-28-generic
- JVM: Temurin OpenJDK 25+36-LTS (note: a different build from environment A's
  25.0.4+7-LTS)
- Build: Gradle 9.6.0
- Native reference: Rust `blake3` 1.8.5, built with Rust/cargo 1.97.1, `default`
  + `std` features only, Rayon disabled
- Benchmark: JMH 1.37, one thread

This is a low-power part. It is roughly 2.6x slower than the M5 on Commons
Codec and 3.5x slower on FastBlake's scalar kernel. **Absolute MiB/s must not be
compared across the two environments**; only ratios within one environment mean
anything. That asymmetry — 2.6x on one kernel and 3.5x on another — is itself
finding 5 and is discussed below.

### JVM vector configuration

```
$ java --add-modules jdk.incubator.vector -cp /tmp/vecinfo VecInfo
preferred IntVector species : Species[int, 8, S_256_BIT]
preferred lane count        : 8
preferred bit size          : 256
SPECIES_128 lanes (used)    : 4
byte order                  : LITTLE_ENDIAN

$ java -XX:+PrintFlagsFinal -version | grep -E "MaxVectorSize|UseAVX|UseSSE"
intx MaxVectorSize     = 32      {C2 product} {default}
int  UseAVX            = 2       {ARCH product} {default}
int  UseSSE            = 4       {ARCH product} {default}
bool UseSSE42Intrinsics = true   {ARCH product} {default}
```

`MaxVectorSize=32` bytes and `UseAVX=2` confirm 256-bit AVX2 with no AVX-512.
`IntVector.SPECIES_PREFERRED` is eight lanes. The kernels use four.

## Result 0 — toolchain: an undocumented minimum Rust version

The Rust contender initially reported unavailable:

```
$ ./gradlew contenders
  [x] commons  Apache Commons Codec (scalar Java)
  [ ] rust     Reference Rust blake3 crate (SIMD, single-threaded)
            skipped: cargo build --release failed with exit code 101.
  [x] java-cpu FastBlake CPU (Java scalar; experimental Vector API available)
  [ ] java-gpu FastBlake GPU (Java, device offload)
            skipped: not implemented yet — planned contender
  2 of 4 available.
```

The cause, from building the crate by hand:

```
error: failed to download `cpufeatures v0.3.0`
Caused by: feature `edition2024` is required
  The package requires the Cargo feature called `edition2024`, but that feature
  is not stabilized in this version of Cargo (1.84.0).
```

`cpufeatures 0.3.0` is a transitive dependency of `blake3` 1.8.5 and requires
edition 2024, which needs cargo 1.85 or newer. The machine had 1.84.0.
`rustup update stable` brought it to 1.97.1 and the crate then built in 7.5 s,
restoring the contender to 3 of 4.

Two things worth recording.

First, this is the "absence is never failure" property working correctly on a
second platform, unprompted and against a real toolchain failure rather than a
deliberately broken one: `cargoBuild` recorded the reason, cleared the staged
library, and `test` and `contenders` both stayed green with one fewer column.

Second, **the project has a minimum Rust version of 1.85 and does not say so.**
It is not in the README and not in `Cargo.toml`. Anyone on a distribution-packaged
cargo older than 1.85 silently loses the ceiling contender and, if they do not
read the skip reason carefully, may conclude the crate is simply unavailable to
them. A `rust-version` field in `native/rust-blake3/Cargo.toml`, or a line in the
README, would convert a confusing skip into a clear instruction.

## Result 1 — correctness is portable

Default dispatch, all three contenders present:

```bash
./gradlew test --rerun          # 542 tests, all passed
```

Each experimental kernel, one JVM per property, via `JAVA_TOOL_OPTIONS` (the
Gradle `test` task does not forward `-D` from the command line):

```bash
for p in vector blockVector chunkVector4 lowLiveVector \
         roundCacheVector heapChunkVector scratchChunkVector; do
  JAVA_TOOL_OPTIONS="-Dfastblake.experimental.$p=true" ./gradlew test --rerun
done
```

| property | result |
|---|---|
| `vector` (E002) | passed |
| `blockVector` (E003) | passed |
| `chunkVector4` (E004) | passed |
| `lowLiveVector` (E005) | passed |
| `roundCacheVector` (E006) | passed |
| `heapChunkVector` (E008) | passed |
| `scratchChunkVector` (E011) | passed |

362 java-cpu tests per property; 542 with all contenders present.

**Evidence the vector branches actually executed**, rather than the properties
being silently ignored — the exact failure mode that wasted a measurement round
in E002:

- `Picked up JAVA_TOOL_OPTIONS: -Dfastblake.experimental.scratchChunkVector=true`
  is printed by the Gradle process, the JMH host, *and* the JMH child fork.
- Dispatch requires `chunkLength == 0 && remaining > 4 * CHUNK_LEN`, i.e. more
  than 4096 bytes (`FastBlake.java:173`). The official vectors run to 102,400
  bytes, so the branch is entered many times per suite.
- The kernels produce distinct, reproducible allocation and throughput
  signatures under measurement (Results 2 and 4), which a dead branch cannot.

`Blake3ChunkVectorScratch` needs no endianness guard and correctly has none: it
assembles message words with explicit byte shifts
(`Blake3ChunkVectorScratch.java:53-56`) rather than by reinterpreting a
`ByteVector`. `Blake3ChunkVectorHeap` does reinterpret and is correctly guarded
by `ByteOrder.nativeOrder() == LITTLE_ENDIAN` (`FastBlake.java:33-34`); x86-64 is
little-endian so it dispatched here, and its guard remains untested by this work.
**No big-endian machine was available, so that guard is still unexercised.**

## Result 2 — E010's allocation diagnosis is a property of C2, not of AArch64

Same protocol as E010: 8 MiB one-shot, one isolated fork per kernel, `-prof gc`.

```bash
JAVA_TOOL_OPTIONS="-Dfastblake.experimental.<kernel>=true" \
  ./gradlew jmh -P'jmh.args=oneShot -p impl=java-cpu -p size=8388608 -f1 -wi 2 -i 2 -prof gc'
```

| kernel | ns/op | B/op | B per input byte | env A B/input byte | gc.count |
|---|---:|---:|---:|---:|---:|
| E002 `vector` | 62,353,318 | 503,567,842 | **60.030** | not measured | 19 |
| E003 `blockVector` | 140,902,632 | 835,584,039 | **99.610** | not measured | 10 |
| E008 `heapChunkVector` | 965,715,828 | 1,333,932,852 | **159.017** | 158.76 | 4 |
| E004 `chunkVector4` | 1,022,644,560 | 1,333,934,658 | **159.017** | 158.90 | 3 |
| E006 `roundCacheVector` | 1,169,385,487 | 1,786,700,064 | **212.991** | 212.99 | 2 |
| E005 `lowLiveVector` | 1,407,521,087 | 2,138,849,552 | **254.971** | 254.61 | 3 |

Derived throughput: 128.3, 56.8, 8.28, 7.82, 6.84 and 5.68 MiB/s respectively.
The four kernels E010 measured keep the same rank order as environment A, and
are worse in absolute terms as expected on a slower part.

E002 and E003 were **not** in E010's allocation driver; their causal
explanations were left explicitly unverified rather than disproved. This entry
closes that gap. Both allocate heavily — 60.03 and 99.61 bytes per input byte —
so both fail the allocation gate, and their E002/E003 rejection rationales
(array escape and scalar packing; lane shuffles and indexed gathers overwhelming
the saved arithmetic) join E004–E008's as unproven. Every Vector API kernel in
this project except E011 is an allocating kernel.

E002 is the interesting one, because it is the only width-generic kernel in the
tree — see Result 6.

This is the strongest result in the document. The allocation figures agree with
environment A to three significant figures across a **different architecture,
different operating system, different JVM build, and different C2 backend**.
Whatever is preventing scalar replacement of `IntVector` wrappers in these
kernels is a decision C2 makes from the code shape, not something about AArch64,
Apple's memory model, or macOS.

Note again the ordering that E010 pointed out: `lowLiveVector`, designed
specifically to reduce the live vector set, allocates the *most* on this machine
too. The register-pressure theory predicts it should be best; the boxing theory
predicts it should be worst because it materialises 112 message vectors per block
instead of 16. The x86-64 data agrees with the boxing theory, independently.

E010's correction of E002–E008 therefore stands on both architectures, and its
"rule learned" — a throughput number from a kernel with unmeasured allocation is
not a result — is confirmed as portable.

## Result 3 — the seven-round escape-analysis cliff reproduces exactly

Full E011 ladder, one fork per benchmark method, GC profiler on:

```bash
./gradlew jmh -P'jmh.args=VectorAllocationBenchmark -f1 -wi 3 -i 3 -prof gc'
```

| rung | env B time | env B B/op | env B gc | env A time | env A B/op |
|---|---:|---:|---:|---:|---:|
| primitive four-chunk transpose | 88.709 ns | 0.001 | 0 | 26.449 ns | ~0.0001 |
| scheduled vector load chain, 256 reps | 343.835 ns | 0.002 | 0 | 392.838 ns | 0.003 |
| one BLAKE3 G, 256 reps | 1531.962 ns | 0.011 | 0 | 1981.969 ns | 0.014 |
| one round, 16 named vectors, 256 reps | 7358.509 ns | 0.051 | 0 | 4743.136 ns | 0.033 |
| four expanded rounds | 116.767 ns | 0.001 | 0 | 65.230 ns | ~0.001 |
| six expanded rounds | 166.896 ns | 0.001 | 0 | 100.709 ns | 0.001 |
| **seven expanded rounds** | 31,529.766 ns | **43,776.221** | 16 | 13,985.551 ns | **43,776.098** |
| seven rounds, compact loop | 216.236 ns | 0.001 | 0 | 114.963 ns | 0.001 |
| four complete chunk lanes | 4660.932 ns | 0.032 | 0 | 2092.938 ns | 0.015 |

The cliff is not merely present on x86-64. **The allocated volume is the same
number on both architectures**: 43,776.221 B/op here against 43,776.098 there,
a difference well inside profiler noise. An identical byte count across two
architectures means the same escape-analysis decision is failing on the same set
of objects, at the same code-size boundary. Six fully expanded rounds scalarize;
adding the seventh does not; a compact seven-iteration loop with dynamic schedule
offsets restores scalar replacement and runs ~146x faster than the expanded form.

Every other rung stays allocation-free with zero collections, so E010's proposed
primitive-`int[64]` message layout survives on x86-64 as well.

E011's rule learned — on this C2 build, minimizing the compiler graph matters
more than making fixed rounds straight-line, and allocation must be rechecked
after every method-size change — is confirmed as portable guidance rather than an
M5 artifact. It should be read as a statement about C2, not about NEON.

The one caution: because the cliff sits at a *code-size* boundary and reproduces
at the same boundary on both machines, any change to the lane count will move
method size and must re-run this ladder. That is a direct constraint on the
8-lane follow-up proposed at the end of this document.

## Result 4 — E011 passes the allocation gate on x86-64

```bash
JAVA_TOOL_OPTIONS="-Dfastblake.experimental.scratchChunkVector=true" \
  ./gradlew jmh -P'jmh.args=oneShot -p impl=java-cpu -p size=8388608 -f1 -wi 3 -i 3 -prof gc'
```

| metric | value |
|---|---:|
| per-iteration `gc.alloc.rate.norm` | 5991.494, 5989.333, 5991.200 B/op |
| aggregate | 5990.676 ± 21.377 B/op |
| per input byte | **0.000714 B** |
| `gc.count` | **≈ 0** |
| implied throughput | 636.8 MiB/s |

Environment A measured 5554.711 B/op. Both are fixed hasher setup and
finalization cost that does not scale with input size — for comparison, E008
allocates 1.33 **GB** for the same operation, 222,000x more. E011 is an
allocation-free kernel on both architectures.

## Result 5 — throughput, and the two ratios that do not transfer

Long run: 2 forks × 5 warmup × 5 measurement iterations of one second,
single-threaded, 8 MiB.

```bash
./gradlew jmh -P'jmh.args=-p impl=commons,rust,java-cpu -p size=8388608 -f2 -wi 5 -i 5'
JAVA_TOOL_OPTIONS="-Dfastblake.experimental.scratchChunkVector=true" \
  ./gradlew jmh -P'jmh.args=-p impl=java-cpu -p size=8388608 -f2 -wi 5 -i 5'
```

| 8 MiB shape | Commons | Rust | E011 `scratchChunkVector` | E009 default |
|---|---:|---:|---:|---:|
| one-shot | 211 | 1668 | 642 | 253 |
| reused | 210 | 1713 | 637 | 255 |
| streaming 4 KiB | 210 | 1100 | 253 (scalar route) | 259 |

Quick-run cross-check (`-f1 -wi 3 -i 3`), which agreed within 1–2% throughout and
is therefore inside the protocol's 3% inconclusive band:

| 8 MiB shape | Commons | Rust | E011 | E009 default |
|---|---:|---:|---:|---:|
| one-shot | 210 | 1714 | 649 | 254 |
| reused | 198 | 1713 | 644 | 255 |
| streaming 4 KiB | 209 | 1092 | 251 | 258 |

Ratios against environment A:

| ratio | env B | env A |
|---|---:|---:|
| E009 scalar vs Commons | **1.20x** | 1.61x |
| E011 vs E009 scalar, one-shot | **2.54x** | 1.77x |
| E011 vs Commons | **3.04x** | 2.86x |
| E011 as a fraction of Rust | **38%** | 63% |
| Rust vs Commons | **7.9x** | 4.5x |
| Rust streaming-4 KiB penalty | **-36%** | -6% |

### 5a. E011 validates, and is a larger relative win here

2.54x the production scalar path against 1.77x on the M5. E011's acceptance
criteria from `e011-allocation-first-vector-plan.md` are all met in environment
B: official vectors pass in every mode, normalized allocation is fixed harness
noise rather than proportional to bytes, no GC occurs in the measured region, and
8 MiB throughput beats the E009 production baseline. The outstanding condition
"validation on at least one non-AArch64 JVM" is discharged.

Streaming remains on the scalar route at 253 MiB/s, matching the default's 259
within noise, because a 4096-byte update cannot supply four chunks while also
retaining the rightmost chunk for correct finalization. That is the known
limitation, unchanged, and it is the only remaining item gating promotion.

### 5b. E009's scalar advantage is largely an M5 result

E009 beats Commons Codec by 1.61x on the M5 and only 1.20x here. Both kernels
slowed on this machine, but by different factors: Commons by 2.6x, FastBlake's
scalar by 3.5x. The gap closed because FastBlake lost more.

E009's stated mechanism was that named locals and fully expanded rounds let C2
keep compression state in registers and expose independent G operations to an
out-of-order core. That is a claim about available instruction-level parallelism,
and it is exactly the thing a Gracemont E-core has least of. Evidence 5 in the
E010 document computed that E009 sustains 2.5–2.6 arithmetic ops/cycle on an M5
P-core "against an integer issue width several times wider", and concluded the
limiter was BLAKE3's dependency structure rather than instruction count. On this
core the issue width itself moves much closer to binding, so the same restructure
buys proportionally less.

E009 remains the correct production default — it is still the fastest scalar
option available here, and it is still ahead of Commons. But **the 1.61x figure
is an environment A result and must be quoted as one.** The same caution applies
to E010 idea 4 (scalar two-chunk interleave), whose entire rationale is the
2.6 ops/cycle headroom measured on the M5; that headroom is smaller here, and the
register-pressure risk it already flagged is unchanged.

### 5c. E011 falls further behind Rust because it is using half the machine

`Blake3ChunkVectorScratch.java:9` hard-codes:

```java
private static final VectorSpecies<Integer> S = IntVector.SPECIES_128;
```

Four lanes, four chunks per batch. On the M5 that is the full NEON width, and
E011 reaches 63% of Rust. Here `IntVector.SPECIES_PREFERRED` is `S_256_BIT` —
eight lanes — and the crate dispatches to an 8-way AVX2 kernel. E011 competes at
four lanes against eight and lands at 38%.

Direct evidence for the Rust side, rather than inference from the crate's
documentation:

```
$ nm target/release/libfastblake_rust.so | grep -oE "blake3_(hash_many|compress)_[a-z0-9_]+" | sort -u
blake3_compress_in_place_avx512
blake3_compress_in_place_sse2
blake3_compress_in_place_sse41
blake3_compress_xof_avx512
blake3_compress_xof_sse2
blake3_compress_xof_sse41
blake3_hash_many_avx2
blake3_hash_many_avx512
blake3_hash_many_sse2
blake3_hash_many_sse41
```

The staged library contains 4-way (`sse41`), 8-way (`avx2`) and 16-way
(`avx512`) `hash_many` kernels. `cpufeatures` performs runtime detection and the
crate selects the widest supported; `lscpu` shows AVX2 present and no AVX-512, so
the 8-way AVX2 kernel is the one running.

**Rust's own numbers corroborate the width explanation independently.** Its
streaming-4 KiB penalty is -36% here against -6% on the M5. A 4096-byte update
supplies exactly four chunks. Four chunks fill a 128-bit NEON batch completely —
hence the small M5 penalty — but leave an 8-lane AVX2 batch half empty, hence the
large penalty here. The same arithmetic that explains Rust's streaming loss on
this machine explains E011's ceiling on it. Two independent measurements, one
mechanism.

This is a width deficit, not a compiler pathology: the allocation gate passes,
the cliff behaves identically, and the win over scalar grew. Doubling the lane
count is the obvious remaining move, and unlike every direction E002–E008
explored, it is not a redesign — it is a species change plus an eight-lane
transpose, on a kernel whose compiler shape is already proven on both
architectures.

## Result 6 — where the width is hard-coded, and why

Audit of every `VectorSpecies` declaration in the tree:

| file | species | is 4 lanes semantic? |
|---|---|---|
| `Blake3Vector` (E002) | `SPECIES_PREFERRED` | **no — fully width-generic** |
| `Blake3BlockVector` (E003) | `SPECIES_128` | **yes** — intrinsic to the algorithm |
| `Blake3ChunkVector4` (E004) | `SPECIES_128` | no — arbitrary batch size |
| `Blake3ChunkVectorLowLive` (E005) | `SPECIES_128` | no |
| `Blake3ChunkVectorRoundCache` (E006) | `SPECIES_128` | no |
| `Blake3ChunkVectorHeap` (E008) | `SPECIES_128` | no |
| `Blake3ChunkVectorScratch` (E011) | `SPECIES_128` | no |
| `VectorPrimitiveBenchmark`, `VectorAllocationBenchmark` | `SPECIES_128` | diagnostic only |

Only E003's 128 is a real constraint. It places the four words of a BLAKE3 state
*row* in lanes and rotates them with fixed `VectorShuffle`s (`LEFT_1`, `LEFT_2`,
`LEFT_3` over exactly four lanes) for the diagonal half-rounds. BLAKE3's state is
4x4; that kernel cannot be widened, and it was rejected on other grounds anyway.

Every chunk-parallel kernel — E004 through E008 and E011 — puts one *chunk* per
lane. Lane count there is just batch size. Nothing in BLAKE3 requires four.

The hard-coding entered at E003's "rule learned", which prescribed "load four
contiguous words from each chunk, and transpose 4x4 registers". On environment A
that was free: `IntVector.SPECIES_PREFERRED` is 128-bit on NEON, so "four lanes",
"a 4x4 transpose" and "the full machine width" were the same number and never had
to be distinguished. E004 adopted 4 for the transpose geometry and every
successor inherited it. The assumption only became visible on a machine where
preferred width and 4 differ.

Two pieces of evidence that width-generality was the original intent and was lost
rather than rejected. First, E002 is titled "preferred-width Vector API chunk
compression" and is written generically throughout — `LANES = SPECIES.length()`,
`packedWordsLength() = 16 * LANES`, `outputLength() = 8 * LANES`, and loops over
`LANES` rather than a literal. Second, and more telling, **`FastBlake` still
sizes its scratch buffers for the preferred width**: `vectorPacked` is
`new int[Blake3Vector.packedWordsLength()]` and `vectorCvs` is
`new int[Blake3Vector.outputLength()]` (`FastBlake.java:82-95`), for *all* the
vector kernels including E011. On this machine those are `int[128]` and
`int[64]`, while `Blake3ChunkVectorScratch` only ever touches the first 64 and 32
entries. The harness is already provisioned for eight lanes; only the kernels
narrowed.

**E002 does not, however, show that preferred width is sufficient on its own.**
It runs at eight lanes on this machine and still only reaches 128.3 MiB/s, half
the scalar path, because it allocates 60 bytes per input byte (Result 2). Width
and the allocation-free compiler shape are independent requirements: E002 has the
width and fails the gate, E011 has the shape and is half-width. The win is
grafting E011's shape onto E002's genericity, which is what idea 1 proposes.

What a width change actually touches in `Blake3ChunkVectorScratch`, none of which
is a one-line species swap:

- the transpose stride, `destination = word * 4` and
  `for (lane = 0; lane < 4; lane++)` with `offset + lane * 1024` (lines 48-57);
- the counter staging, which occupies `messages[0..3]` for the low words and
  `messages[4..7]` for the high words (lines 28-37) — that layout collides with
  the message scratch as soon as the lane count changes;
- CV extraction, 32 explicit `cvN.lane(0..3)` statements (lines 172-203), which
  would become 64 at eight lanes;
- the message scratch length, `16 * LANES` rather than a literal 64;
- in `FastBlake`, the dispatch guard `remaining > 4 * CHUNK_LEN`, the
  `for (lane = 0; lane < 4; lane++)` CV push loop, and the four
  `chunksCompressed++` increments (lines 173-185).

Changing only the species constant would compile and would silently mis-hash:
the transpose stride would no longer match the species length, so lanes 4-7 would
read from the wrong offsets, and half the chunk CVs would never be extracted. The
conformance suite would catch it — but it is a coordinated change to six
locations, not a one-line swap.

## What this document does not establish

Recorded so these gaps are not mistaken for coverage.

- **One x86-64 machine, and an unrepresentative one.** The N97 is an E-core-only
  low-power part with no P-core, no AVX-512, and 6 MiB of L3. A Golden Cove,
  Zen 4/5, or any AVX-512 machine could give materially different answers,
  particularly for finding 5b, which is specifically about core width. Finding
  5b should be read as "E009's advantage depends on core width, demonstrated by
  one narrow core", not as a general x86-64 result.
- **E002 and E003 now have measured allocation (Result 2), but only on
  environment B.** Both fail the gate here; neither was measured on environment
  A, so the E010-style comparison that exists for E004–E008 does not exist for
  them.
- **No mnemonic assembly was inspected.** This Temurin build has no `hsdis`
  library either (`$JAVA_HOME/lib/hsdis*` does not exist), so the same limitation
  as environment A applies: allocation behaviour is proven, but the exact
  instruction mix — in particular whether `VectorOperators.ROR` lowers well on
  AVX2, which lacks a general 32-bit vector rotate below AVX-512's `VPRORD` — was
  not confirmed. This is a plausible secondary contributor to E011's standing
  here and is untested.
- **The big-endian guard on `heapChunkVector` is still unexercised.** x86-64 is
  little-endian; no big-endian machine was available.
- **The machine was not quiesced.** ~6 GiB of 14 GiB RAM was in use at the start
  and frequency scaling was active (`CPU(s) scaling MHz: 69%`). Quick and long
  runs agreed within 1–2%, which argues the numbers are stable, but this is not a
  controlled thermal environment.
- **Single-threaded throughout**, on both sides, as in every prior entry. The
  `rust-mt` contender the README calls for still does not exist, and neither does
  `java-gpu`.
- **No `-p size` sweep.** Only 8 MiB was measured, per the ledger protocol. The
  small-input behaviour (64 B, 1 KiB) that the README table reports for
  environment A was not re-measured here.

## Reproducing this

```bash
# environment
lscpu; free -h; java -version; cargo --version
java -XX:+PrintFlagsFinal -version | grep -E "MaxVectorSize|UseAVX|UseSSE"

# contenders and the rust minimum-version issue
./gradlew contenders
(cd native/rust-blake3 && cargo build --release)   # needs cargo >= 1.85

# correctness, one JVM per kernel
./gradlew test --rerun
for p in vector blockVector chunkVector4 lowLiveVector \
         roundCacheVector heapChunkVector scratchChunkVector; do
  JAVA_TOOL_OPTIONS="-Dfastblake.experimental.$p=true" ./gradlew test --rerun
done

# allocation ladder (E011 rungs, one fork each)
./gradlew jmh -P'jmh.args=VectorAllocationBenchmark -f1 -wi 3 -i 3 -prof gc'

# allocation gate, one isolated fork per kernel
JAVA_TOOL_OPTIONS="-Dfastblake.experimental.scratchChunkVector=true" \
  ./gradlew jmh -P'jmh.args=oneShot -p impl=java-cpu -p size=8388608 -f1 -wi 3 -i 3 -prof gc'

# throughput
./gradlew jmh -P'jmh.args=-p impl=commons,rust,java-cpu -p size=8388608 -f2 -wi 5 -i 5'
JAVA_TOOL_OPTIONS="-Dfastblake.experimental.scratchChunkVector=true" \
  ./gradlew jmh -P'jmh.args=-p impl=java-cpu -p size=8388608 -f2 -wi 5 -i 5'

# which SIMD backend is Rust actually running
nm native/rust-blake3/target/release/libfastblake_rust.so \
  | grep -oE "blake3_hash_many_[a-z0-9]+" | sort -u
```

`JAVA_TOOL_OPTIONS` is required rather than `-D`: the Gradle `test` and `jmh`
tasks do not forward system properties from the command line, and the JMH child
fork must inherit the property. Both the host and the child print
`Picked up JAVA_TOOL_OPTIONS: ...`, which is the cheap proof that it arrived.

## Ideas, ranked by expected value

> **Outcome note.** Ideas 1 and 4 below have since been run; this section is
> left as written. Idea 1 became **E013**: the preferred-width kernel is
> correct, allocation-free, does not hit the seven-round cliff at eight lanes —
> and is 3–4% *slower* than four lanes on this machine. Its decomposition found
> that ~30% of the kernel was scalar byte-shift transpose, which no vector width
> touches. Replacing that transpose with a little-endian `VarHandle` became
> **E014** and gained 28–30% on both kernels, far more than the width change it
> was meant to support. The "vectorize the transpose" suggestion at the end of
> Result 5c was measured in E014 and rejected: in-register 4x4 transposes are
> 2.3x slower than the plain VarHandle read. See `experiments.md` §§ E013, E014.

### 1. Preferred-width chunk kernel — target ~2x E011 on AVX2 machines

Parameterise `Blake3ChunkVectorScratch` on `IntVector.SPECIES_PREFERRED`, or add
an eight-lane sibling selected when the preferred species is 256-bit, keeping the
four-lane path for 128-bit machines. Eight chunks per batch on AVX2 against the
current four. The upside is bounded by Rust's 1668 MiB/s here; E011 is at 642.

This is the cheapest large win available, because the hard part is already done.
The compiler shape that E011 established — reusable primitive `int[64]`
word-major message scratch, named state locals, compact seven-round loop — is now
proven allocation-free on two architectures. Only the species, the transpose
width, the scratch size, and the CV extraction change.

Constraints, both established above and both non-negotiable:

- **Re-run the full allocation ladder first.** The seven-round cliff sits at a
  code-size boundary that reproduces at the same boundary on both architectures.
  Doubling the lane count changes method size and the transpose, so the compact
  loop's safety does not automatically carry over. Gate on allocation per rung
  before recording any throughput, per E010.
- **Keep the four-lane path.** It is the full width on NEON and must remain the
  choice there; this is a dispatch addition, not a replacement. Guard on the
  actual preferred species rather than on an architecture string.
- Dispatch also needs `remaining > 8 * CHUNK_LEN` for the wide path, and the
  scalar tree merge must absorb eight CVs per batch.

### 2. Document and enforce the Rust minimum version — trivial, prevents a silent skip

Add `rust-version = "1.85"` to `native/rust-blake3/Cargo.toml` and a line to the
README. Costs nothing and turns a confusing contender skip into an actionable
message. Result 0.

### 3. Re-measure on a wide x86-64 core before trusting finding 5b — cheap, high information

Finding 5b says E009's scalar advantage depends on core width, on evidence from
one narrow core. A single run of the standard quick comparison on any modern
P-core or Zen machine would establish whether 1.20x or 1.61x is the typical
x86-64 answer. This matters because E010's idea 4 (scalar two-chunk interleave)
is justified entirely by ILP headroom measured on the M5.

If such a machine has AVX-512, it also settles whether idea 1 should target
16 lanes rather than 8 — the crate ships `blake3_hash_many_avx512` and would be
using it there, which would put E011's four lanes at a 4:1 disadvantage rather
than 2:1.

### 4. Confirm `ROR` lowering on AVX2 — one benchmark, resolves a known unknown

AVX2 has no general 32-bit vector rotate; `VPRORD` is AVX-512. E011 uses
`VectorOperators.ROR` at four rotate counts per G. E010 Evidence 3 showed ROR is
free on AArch64 and that the manual shift/or workaround is unnecessary *there*.
That result was not re-run here, and `VectorPrimitiveBenchmark` already exists to
answer it. If ROR lowers badly on AVX2, a shift/or variant is a small, isolated
change — and it would need its own allocation check, since E010 Evidence 3 showed
the two forms can differ in boxing behaviour.

### Suggested sequence

2 → 4 → 1 → 3. Idea 2 is a one-line fix. Idea 4 is a single existing benchmark
that removes an unknown from idea 1's design. Idea 1 is where the throughput is.
Idea 3 is the widest-information run but needs hardware not available here.
