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
 * {@code -Dfastblake.kernel=auto|scalar|four|eight} forces a production choice,
 * for benchmarking and for embedders who know their fleet. Historical kernel
 * switches belong to the experiment source set and are intentionally not
 * interpreted by the shipped library.
 */
final class KernelSelector {

    /** The chunk-parallel kernels available for automatic selection. */
    enum Kernel {
        /** No chunk-parallel kernel; the scalar compressor handles everything. */
        SCALAR(0),
        /** E011/E014 four independent chunks in 128-bit lanes. */
        FOUR_CHUNK(4),
        /** E024 two interleaved four-chunk batches, eight chunks in flight. */
        EIGHT_CHUNK(8);

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
            case "eight", "eight-chunk", "dual" -> Kernel.EIGHT_CHUNK;
            default -> null;
        };
        if (forced != null) {
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
        if (CpuCapabilities.PREFERRED_VECTOR_BITS == 0) {
            return Kernel.SCALAR;
        }
        // MEASURED: E024, Apple M5. Eight chunks in flight across 32 NEON
        // registers is +36% over four; spill traffic exists but is nearly free
        // in a latency-bound loop (E023).
        if (CpuCapabilities.IS_AARCH64 && CpuCapabilities.VECTOR_REGISTERS >= 32) {
            return Kernel.EIGHT_CHUNK;
        }
        // MEASURED: E024, Ryzen 3 3200G (Zen+, AVX2, 16 YMM registers). The
        // same kernel is 12-14% *slower* there: 32 state vectors into 16
        // registers spills more than the added parallelism buys. E011/E014's
        // four-chunk kernel is the measured-good choice on x86-64 so far.
        if (CpuCapabilities.IS_X86_64) {
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
        if (CpuCapabilities.IS_X86_64) {
            return "x86-64 with " + CpuCapabilities.VECTOR_REGISTERS + " vector registers; "
                    + "E024 measured eight chunks in flight 12-14% slower than four here";
        }
        return "unmeasured architecture '" + CpuCapabilities.ARCH
                + "'; using the conservative measured-good four-chunk kernel. "
                + "Measure this machine and add it to KernelSelector";
    }
}
