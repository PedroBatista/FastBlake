# E022 — library/experiment extraction performance check (Apple M5)

Date: 2026-08-07  
Change under test: historical kernels moved from `src/main` to
`src/experiment`; production `FastBlake` retains scalar, four-chunk, and
eight-chunk dispatch only.  
Machine/JVM/protocol: same as [`apple-m5.md`](apple-m5.md), two forks, five
1-second warmups, five 1-second measurements, one thread, 8 MiB.

Command:

```bash
./gradlew jmh -P'jmh.args=Blake3Benchmark -p impl=commons,rust,java-cpu -p size=8388608 -f2 -wi 5 -i 5'
```

The selector still reported AArch64, 128-bit vectors, approximately 32 vector
registers, and `EIGHT_CHUNK`.

## Results

| shape | Commons | Rust | FastBlake | recorded FastBlake | delta |
|---|---:|---:|---:|---:|---:|
| `oneShot` | 568 | 2,508 | **2,146** | 2,116 | +1.4% |
| `reusedInstance` | 545 | 2,490 | **2,134** | 2,114 | +0.9% |
| `streaming4k` | 541 | 2,330 | **2,089** | 2,063 | +1.3% |

The raw FastBlake scores were 3,727,023 ns/op, 3,748,599 ns/op, and
3,828,826 ns/op respectively. The comparison baseline is the corresponding
table in `reference/performance/results/apple-m5.md`.

## Conclusion

All FastBlake deltas are below the project's 3% inconclusive/noise margin. The
source-set extraction did not cause a measurable throughput regression, and the
production selector remains unchanged at the measured choice. The Commons and
Rust values also moved between sessions, which reinforces using the within-run
FastBlake comparison and the documented margin rather than treating the last
digit as a code effect.
