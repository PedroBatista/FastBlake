package eu.pedrobatista.fastblake;

import java.util.Locale;

/**
 * Chooses the chunk-parallel kernel for this machine.
 *
 * <p>E024 made this necessary: two interleaved four-chunk batches are a 36% win
 * on Apple M5 and a 12–14% loss on a Ryzen 3200G, because AArch64 has 32 vector
 * registers and Zen+ AVX2 has 16. Dropping the kernel would forfeit the win on
 * register-rich cores; enabling it everywhere would forfeit throughput on
 * register-poor ones. The reference Rust crate resolves the same problem the
 * same way, with per-architecture implementations selected at runtime.
 *
 * <h2>The table is measurements, not theory</h2>
 *
 * Every entry below cites the experiment that produced it. A machine profile
 * that has never been measured gets the most conservative <em>measured-good</em>
 * kernel — the four-chunk one — never an extrapolation from a neighbouring
 * architecture. Unknown hardware is a task for the ledger, not a gamble at
 * runtime.
 *
 * <p><b>This table is deliberately small.</b> Populating it properly requires
 * per-microarchitecture research — register counts, datapath widths, whether
 * 256-bit operations are split into two 128-bit micro-operations — and that
 * research is a separate task. What is here is only what FastBlake has actually
 * measured on hardware it has run on.
 *
 * <h2>Overriding</h2>
 *
 * {@code -Dfastblake.kernel=auto|scalar|four|avx1|eight|wide} forces a production
 * choice, for benchmarking and for embedders who know their fleet. Historical
 * kernel switches belong to the experiment source set and are intentionally not
 * interpreted by the shipped library.
 *
 * <p>{@code wide} is shipped and conformance-tested. On x86-64 the selector
 * uses it automatically only when the JVM exposes a native AVX-512-width
 * species: sixteen independent BLAKE3 chunks then occupy the sixteen lanes of
 * each ZMM vector while the 32-register AVX-512 file leaves room for the
 * compressor's live state. AVX2 remains conservative until it has a measured
 * winner.
 *
 * <h2>x86-64 is three profiles, not one</h2>
 *
 * {@code os.arch} reports {@code x86_64} for all of them, so the selector
 * distinguishes them by what the JVM says it will compile:
 *
 * <table>
 *   <caption>x86-64 dispatch</caption>
 *   <tr><th>Profile</th><th>Widest integer vector</th><th>Kernel</th></tr>
 *   <tr><td>AVX-512</td><td>512 bits</td><td>{@code WIDE}, sixteen lanes</td></tr>
 *   <tr><td>AVX2</td><td>256 bits</td><td>{@code FOUR_CHUNK}, pending E028</td></tr>
 *   <tr><td>AVX1</td><td>128 bits</td><td>{@code AVX1_CHUNK}, measured on Ivy Bridge-EP</td></tr>
 *   <tr><td>SSE</td><td>128 bits</td><td>{@code FOUR_CHUNK}, conservative default</td></tr>
 * </table>
 *
 * <p>AVX1 widened the floating-point datapath and not the integer one, so
 * BLAKE3 cannot use its 256-bit half. That fact limits useful integer vectors
 * to 128 bits; it does not prove that a 128-bit Vector API kernel beats scalar
 * code. E029 found that C2 materialised wrappers for the composite Vector API
 * rotate operator on a 2013 Mac Pro. E030 recovered allocation-free SIMD with
 * textually inlined primitive shift/OR rotations. See
 * {@link CpuCapabilities#HAS_AVX1_ONLY}.
 */
final class KernelSelector {

    /** The chunk-parallel kernels available for automatic selection. */
    enum Kernel {
        /** No chunk-parallel kernel; the scalar compressor handles everything. */
        SCALAR(0),
        /** E011/E014 four independent chunks in 128-bit lanes. */
        FOUR_CHUNK(4),
        /** E030 AVX1 kernel with inlined shift/OR rotations. */
        AVX1_CHUNK(4),
        /** E024 two interleaved four-chunk batches, eight chunks in flight. */
        EIGHT_CHUNK(8),
        /**
         * E013/E028 one chunk per lane at the machine's preferred width.
         *
         * <p>Batch size is the lane count, so this is four chunks on NEON or
         * SSE, eight on AVX2 and sixteen on AVX-512. It is selected
         * automatically for native AVX-512 and is otherwise reachable through
         * {@code -Dfastblake.kernel=wide}: see {@link #selectAutomatically}.
         */
        WIDE(CpuCapabilities.WIDE_LANES);

        final int chunksPerBatch;

        Kernel(int chunksPerBatch) {
            this.chunksPerBatch = chunksPerBatch;
        }
    }

    private static final String OVERRIDE_PROPERTY = "fastblake.kernel";

    private static final Kernel SELECTED;
    private static final String REASON;

    static {
        String override = System.getProperty(OVERRIDE_PROPERTY, "auto")
                .trim().toLowerCase(Locale.ROOT);
        Kernel forced = switch (override) {
            case "scalar" -> Kernel.SCALAR;
            case "four", "four-chunk" -> Kernel.FOUR_CHUNK;
            case "avx1", "avx1-chunk" -> Kernel.AVX1_CHUNK;
            case "eight", "eight-chunk", "dual" -> Kernel.EIGHT_CHUNK;
            case "wide", "preferred" -> Kernel.WIDE;
            default -> null;
        };
        if (forced != null && forced != Kernel.SCALAR
                && CpuCapabilities.PREFERRED_VECTOR_BITS == 0) {
            // Forcing a vector kernel on a JVM that has no Vector API used to
            // fail with NoClassDefFoundError on the first input large enough to
            // form a batch: the kernel classes hold their species in a static
            // final field, so merely reaching one initialises it. The override
            // is a benchmarking convenience and must not be able to break the
            // documented scalar fallback.
            SELECTED = Kernel.SCALAR;
            REASON = "-D" + OVERRIDE_PROPERTY + "=" + override + " requests a vector kernel, but "
                    + CpuCapabilities.VECTOR_UNAVAILABLE_REASON;
        } else if (forced != null) {
            SELECTED = forced;
            REASON = "forced by -D" + OVERRIDE_PROPERTY + "=" + override;
        } else if (!override.equals("auto")) {
            SELECTED = selectAutomatically();
            REASON = "unrecognised -D" + OVERRIDE_PROPERTY + "=" + override
                    + ", falling back to automatic selection; " + automaticReason();
        } else {
            SELECTED = selectAutomatically();
            REASON = automaticReason();
        }
    }

    private KernelSelector() {
    }

    /** The kernel chosen for this machine. */
    static Kernel selected() {
        return SELECTED;
    }

    /** Why {@link #selected()} chose what it did, for diagnostics. */
    static String reason() {
        return REASON;
    }

    /** A one-line report of machine and choice. */
    static String describe() {
        return CpuCapabilities.describe() + " -> " + SELECTED + " (" + REASON + ")";
    }

    private static Kernel selectAutomatically() {
        return selectAutomaticallyForProfile(
                CpuCapabilities.PREFERRED_VECTOR_BITS != 0,
                CpuCapabilities.IS_AARCH64,
                CpuCapabilities.VECTOR_REGISTERS,
                CpuCapabilities.HAS_NATIVE_AVX512,
                CpuCapabilities.HAS_AVX1_ONLY,
                CpuCapabilities.IS_X86_64);
    }

    /**
     * Pure dispatch table used by production selection and architecture tests.
     * Keeping the probed host values outside this method lets every profile be
     * checked on every development machine instead of testing only the host's
     * one reachable branch.
     */
    static Kernel selectAutomaticallyForProfile(boolean vectorApiAvailable,
                                                boolean aarch64,
                                                int vectorRegisters,
                                                boolean nativeAvx512,
                                                boolean avx1Only,
                                                boolean x86_64) {
        if (!vectorApiAvailable) {
            return Kernel.SCALAR;
        }
        // MEASURED: E024, Apple M5. Eight chunks in flight across 32 NEON
        // registers is +36% over four; spill traffic exists but is nearly free
        // in a latency-bound loop (E023).
        if (aarch64 && vectorRegisters >= 32) {
            return Kernel.EIGHT_CHUNK;
        }
        // AVX-512 has 32 ZMM registers and a native 512-bit species has
        // sixteen int lanes. WIDE maps one complete BLAKE3 chunk to each lane,
        // so it processes sixteen independent chunks per batch with the full
        // vector width. Unlike the dual kernel, it has only 16 persistent
        // state vectors, leaving half the register file for the round's
        // temporaries and message loads.
        //
        // This is deliberately based on the Vector API's preferred species,
        // not raw CPUID: a JVM started with AVX-512 disabled must keep using
        // the best shape it can actually compile.
        if (nativeAvx512) {
            return Kernel.WIDE;
        }
        // AVX1 (Sandy Bridge, Ivy Bridge, and the Ivy Bridge-EP Xeons in the
        // 2013 Mac Pro). Handled before the general x86-64 branch because this
        // is a measured exception to that branch's four-chunk default.
        //
        // AVX2, not AVX1, is what widened integer SIMD to 256 bits. AVX1's
        // 256-bit half is floating point only, and BLAKE3 is add/xor/rotate on
        // 32-bit words with no floating-point work to put there. The 128-bit
        // four-chunk kernel therefore uses the widest integer species, but ISA
        // width alone does not make that Java call graph profitable.
        //
        // What AVX1 does buy is free: at UseAVX >= 1 HotSpot emits the
        // VEX-encoded three-operand forms of these same 128-bit instructions,
        // dropping the register copy the two-operand SSE encoding needs before
        // each destructive operation. No kernel change is required to get it.
        //
        // MEASURED: E029/E030, Ivy Bridge-EP in a 2013 Mac Pro, Temurin JDK
        // 25.0.4. The ordinary four-chunk kernel allocated about 714 MB per
        // 8 MiB hash because C2 boxed the composite VectorOperators.ROR path.
        // E030's dedicated kernel expresses each rotate as inlined primitive
        // shifts plus OR, restoring fixed allocation and more than doubling
        // scalar throughput. AVX2, AVX-512, AArch64 and the unmeasured SSE
        // profile retain their existing branches below.
        if (avx1Only) {
            return Kernel.AVX1_CHUNK;
        }
        // MEASURED: E024, Ryzen 3 3200G (Zen+, AVX2, 16 YMM registers). The
        // same kernel is 12-14% *slower* there: 32 state vectors into 16
        // registers spills more than the added parallelism buys. E011/E014's
        // four-chunk kernel is the measured-good choice on x86-64 so far.
        //
        // UNRESOLVED, and the largest known headroom in the project: this
        // four-chunk kernel is 128-bit, so on x86-64 it emits XMM and uses half
        // an AVX2 datapath or a quarter of an AVX-512 one (E021 confirmed the
        // XMM encoding from disassembly). Kernel.WIDE reaches eight chunks in
        // flight the way the register file allows here -- eight lanes wide
        // rather than E024's two interleaved four-lane batches -- and E028
        // predicts it wins on AVX2 for exactly the reason E024 loses.
        //
        // It stays out of automatic selection until a machine measures it,
        // because rule 1 of this table admits no extrapolation. Running
        // `./gradlew dispatchAudit` on an AVX2 or AVX-512 machine is the whole
        // experiment; a MISMATCH verdict there is what flips this branch.
        if (x86_64) {
            return Kernel.FOUR_CHUNK;
        }
        // UNMEASURED profile. Take the conservative measured-good kernel.
        return Kernel.FOUR_CHUNK;
    }

    private static String automaticReason() {
        if (CpuCapabilities.PREFERRED_VECTOR_BITS == 0) {
            return CpuCapabilities.VECTOR_UNAVAILABLE_REASON;
        }
        if (CpuCapabilities.IS_AARCH64 && CpuCapabilities.VECTOR_REGISTERS >= 32) {
            return "AArch64 with 32 vector registers; E024 measured eight chunks "
                    + "in flight at +36% over four";
        }
        if (CpuCapabilities.HAS_NATIVE_AVX512) {
            return "x86-64 with native 512-bit Vector API species and 32 ZMM registers; "
                    + "using the sixteen-lane AVX-512 wide kernel";
        }
        if (CpuCapabilities.HAS_AVX1_ONLY) {
            return "x86-64 with AVX1 but not AVX2: " + CpuCapabilities.MAX_FP_VECTOR_BITS
                    + "-bit floating-point vectors and only " + CpuCapabilities.MAX_INT_VECTOR_BITS
                    + "-bit integer vectors. E030 uses an allocation-safe four-chunk kernel "
                    + "with inlined shift/OR rotations because composite ROR boxed on this profile";
        }
        if (CpuCapabilities.IS_X86_64) {
            return "x86-64 with " + CpuCapabilities.VECTOR_REGISTERS + " vector registers; "
                    + "E024 measured eight chunks in flight 12-14% slower than four here";
        }
        return "unmeasured architecture '" + CpuCapabilities.ARCH
                + "'; using the conservative measured-good four-chunk kernel. "
                + "Measure this machine and add it to KernelSelector";
    }
}
