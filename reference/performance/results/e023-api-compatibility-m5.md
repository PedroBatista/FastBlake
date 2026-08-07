# E023 — Commons-compatible API additions performance check (Apple M5)

Date: 2026-08-07  
Change under test: added Commons-compatible `keyedHash(byte[], byte[])` and
`doFinalize(byte[])` overloads. The latter preserves Commons Codec's XOF
semantics and writes `output.length` bytes.  
Machine/JVM/protocol: same as [`apple-m5.md`](apple-m5.md), two forks, five
1-second warmups, five 1-second measurements, one thread, 8 MiB.

Command:

```bash
./gradlew jmh -P'jmh.args=Blake3Benchmark -p impl=commons,rust,java-cpu -p size=8388608 -f2 -wi 5 -i 5'
```

The selector reported the same Apple M5 `EIGHT_CHUNK` production kernel.

## Results

| shape | Commons | Rust | FastBlake | recorded FastBlake | delta |
|---|---:|---:|---:|---:|---:|
| `oneShot` | 558 | 2,517 | **2,137** | 2,116 | +1.0% |
| `reusedInstance` | 546 | 2,491 | **2,141** | 2,114 | +1.3% |
| `streaming4k` | 546 | 2,333 | **2,083** | 2,063 | +1.0% |

Raw FastBlake scores were 3,743,335 ns/op, 3,736,799 ns/op, and 3,840,352
ns/op respectively. The comparison baseline is the corresponding table in
`reference/performance/results/apple-m5.md`.

## Conclusion

All FastBlake deltas are below the project's 3% inconclusive/noise margin. The
new public convenience overloads do not affect the measured hot path. Keep the
API additions.
