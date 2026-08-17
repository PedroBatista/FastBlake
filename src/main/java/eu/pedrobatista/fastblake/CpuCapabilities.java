package eu.pedrobatista.fastblake;

/**
 * What this machine can execute, as far as the JVM is willing to say.
 *
 * <p>The JVM exposes very little: the architecture name, and the preferred
 * vector width if the incubating Vector API is present. Everything else —
 * notably the vector register count, which E024 showed decides whether an
 * eight-chunk kernel is a 36% win or a 13% loss — has to be derived from those
 * two facts plus documented microarchitecture behaviour.
 *
 * <p><b>The Vector API is probed defensively.</b> FastBlake must remain usable
 * on a JVM started without {@code --add-modules jdk.incubator.vector}. The probe
 * catches {@link Throwable} so a missing module yields "no vector support" and a
 * scalar kernel, rather than a {@link NoClassDefFoundError} escaping from a
 * static initialiser and taking the whole class down.
 *
 * <p>Detection is performed once. Nothing here is on a hot path.
 */
final class CpuCapabilities {

    /** {@code os.arch}, lowercased. */
    static final String ARCH = System.getProperty("os.arch", "unknown")
            .toLowerCase(java.util.Locale.ROOT);

    /** Preferred vector width in bits, or 0 when the Vector API is unavailable. */
    static final int PREFERRED_VECTOR_BITS = probePreferredVectorBits();

    /** Why {@link #PREFERRED_VECTOR_BITS} is zero, or {@code null} when it is not. */
    static final String VECTOR_UNAVAILABLE_REASON = PREFERRED_VECTOR_BITS == 0
            ? probeFailureReason() : null;

    static {
        warnIfVectorApiMissing();
    }

    static final boolean IS_AARCH64 = ARCH.equals("aarch64") || ARCH.equals("arm64");
    static final boolean IS_X86_64 = ARCH.equals("x86_64") || ARCH.equals("amd64");

    /**
     * Architectural vector register count, or 0 when unknown.
     *
     * <p>Derived, not measured, and deliberately coarse:
     *
     * <ul>
     *   <li>AArch64 NEON has 32 128-bit registers.
     *   <li>x86-64 has 16 XMM/YMM registers, rising to 32 ZMM with AVX-512.
     *       A 512-bit preferred width is taken as evidence of AVX-512.
     * </ul>
     *
     * <p>This is the single number the kernel table turns on, so it is stated
     * explicitly rather than buried in the selection logic.
     */
    static final int VECTOR_REGISTERS = deriveVectorRegisters();

    /**
     * Lane width in bits used by the preferred-width kernel, or 0 when the
     * Vector API is unavailable.
     *
     * <p>Normally the preferred width. {@code -Dfastblake.wideBits=128|256|512}
     * pins it instead, which is how the eight- and sixteen-lane paths are made
     * testable on a 128-bit machine — the Vector API implements a species wider
     * than the hardware by splitting it, so the result is correct but slow.
     * Never pin this for a throughput measurement.
     */
    static final int WIDE_VECTOR_BITS = resolveWideVectorBits();

    /** Chunks the preferred-width kernel consumes per batch, or 0 if unavailable. */
    static final int WIDE_LANES = WIDE_VECTOR_BITS / Integer.SIZE;

    private CpuCapabilities() {
    }

    private static int probePreferredVectorBits() {
        try {
            return jdk.incubator.vector.IntVector.SPECIES_PREFERRED.vectorBitSize();
        } catch (Throwable notAvailable) {
            return 0;
        }
    }

    /**
     * Warns once, at class-initialisation time, when the SIMD kernels are
     * unreachable because the incubating Vector API was not resolved.
     *
     * <p>Silence here is the expensive failure: FastBlake keeps working and
     * keeps returning correct digests, so a deployment that simply forgot the
     * flag looks healthy while running the scalar kernel. The flag cannot be
     * set programmatically — the module graph is fixed before any library code
     * runs — so telling the operator is the only available remedy.
     *
     * <p>Routed through {@link System.Logger} so a host application's logging
     * backend can capture it; with no backend installed it reaches stderr.
     * Set {@code -Dfastblake.quiet=true} to suppress it.
     */
    private static void warnIfVectorApiMissing() {
        if (PREFERRED_VECTOR_BITS != 0
                || Boolean.getBoolean("fastblake.quiet")) {
            return;
        }
        System.getLogger("eu.pedrobatista.fastblake").log(System.Logger.Level.WARNING,
                "FastBlake: Vector API unavailable, falling back to the scalar kernel. "
                        + "Add --add-modules jdk.incubator.vector to the JVM command line "
                        + "to enable SIMD. Suppress with -Dfastblake.quiet=true.");
    }

    private static String probeFailureReason() {
        try {
            Class.forName("jdk.incubator.vector.IntVector");
            return "the Vector API is present but did not report a preferred width";
        } catch (Throwable missing) {
            return "jdk.incubator.vector is not available; "
                    + "start the JVM with --add-modules jdk.incubator.vector to enable SIMD kernels";
        }
    }

    private static int resolveWideVectorBits() {
        if (PREFERRED_VECTOR_BITS == 0) {
            return 0;
        }
        String requested = System.getProperty("fastblake.wideBits");
        if (requested == null) {
            return PREFERRED_VECTOR_BITS;
        }
        return switch (requested.trim()) {
            case "128" -> 128;
            case "256" -> 256;
            case "512" -> 512;
            default -> PREFERRED_VECTOR_BITS;
        };
    }

    private static int deriveVectorRegisters() {
        if (PREFERRED_VECTOR_BITS == 0) {
            return 0;
        }
        if (IS_AARCH64) {
            return 32;
        }
        if (IS_X86_64) {
            return PREFERRED_VECTOR_BITS >= 512 ? 32 : 16;
        }
        return 0;
    }

    /** One line describing the detected machine, for diagnostics. */
    static String describe() {
        StringBuilder sb = new StringBuilder(ARCH);
        if (PREFERRED_VECTOR_BITS == 0) {
            return sb.append(", no Vector API (").append(VECTOR_UNAVAILABLE_REASON)
                    .append(')').toString();
        }
        sb.append(", preferred vector width ").append(PREFERRED_VECTOR_BITS).append(" bits");
        if (WIDE_VECTOR_BITS != PREFERRED_VECTOR_BITS) {
            // A pinned width is a correctness lever that silently changes what
            // any throughput number means, so it is never left implicit.
            sb.append(" (wide kernel pinned to ").append(WIDE_VECTOR_BITS)
                    .append(" bits by -Dfastblake.wideBits; not a valid measurement)");
        }
        if (VECTOR_REGISTERS > 0) {
            sb.append(", ~").append(VECTOR_REGISTERS).append(" vector registers");
        } else {
            sb.append(", vector register count unknown");
        }
        return sb.toString();
    }
}
