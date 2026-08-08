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

    private CpuCapabilities() {
    }

    private static int probePreferredVectorBits() {
        try {
            return jdk.incubator.vector.IntVector.SPECIES_PREFERRED.vectorBitSize();
        } catch (Throwable notAvailable) {
            return 0;
        }
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
        if (VECTOR_REGISTERS > 0) {
            sb.append(", ~").append(VECTOR_REGISTERS).append(" vector registers");
        } else {
            sb.append(", vector register count unknown");
        }
        return sb.toString();
    }
}
