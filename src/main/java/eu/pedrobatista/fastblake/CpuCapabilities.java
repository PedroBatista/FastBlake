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

    /**
     * Widest <em>integer</em> vector this JVM will compile to real instructions,
     * or 0 when the Vector API is unavailable.
     *
     * <p>{@link #PREFERRED_VECTOR_BITS} cannot answer this question, because
     * the preferred shape is the width every lane type supports — the minimum
     * across {@code byte} through {@code double}. When one lane type is
     * narrower than the rest, the preferred shape reports the narrow width and
     * says nothing about which type caused it.
     *
     * <p>That distinction is exactly AVX1: it widened the floating-point
     * datapath to 256 bits and left integer operations at 128. BLAKE3 is
     * integer-only, so this field, not the preferred width, is the ceiling on
     * anything FastBlake can emit.
     */
    static final int MAX_INT_VECTOR_BITS = probeLargestBits(int.class);

    /**
     * Widest floating-point vector this JVM will compile, or 0 when the Vector
     * API is unavailable.
     *
     * <p>Useless for hashing; kept solely because the gap between it and
     * {@link #MAX_INT_VECTOR_BITS} is what identifies an AVX1 machine.
     */
    static final int MAX_FP_VECTOR_BITS = probeLargestBits(double.class);

    static {
        warnIfVectorApiMissing();
    }

    static final boolean IS_AARCH64 = isAarch64(ARCH);
    static final boolean IS_X86_64 = isX86_64(ARCH);

    /**
     * Whether this is an AVX1 machine: 256-bit floating point, 128-bit integer.
     *
     * <p>Sandy Bridge and Ivy Bridge, including the Ivy Bridge-EP Xeons in the
     * 2013 Mac Pro, implement AVX without AVX2. AVX2 is what widened integer
     * SIMD to 256 bits; AVX1 widened only the floating-point datapath. HotSpot
     * encodes that limit directly — with {@code UseAVX=1} it allows 32-byte
     * vectors for {@code float} and {@code double} and 16-byte vectors for
     * every integral type — so an AVX1 host reports a 128-bit preferred shape
     * and a 256-bit largest floating-point shape.
     *
     * <p><b>The integer-vector ceiling on AVX1 is 128 bits.</b>
     * BLAKE3's compression function is add, xor and rotate on 32-bit words;
     * there is no floating-point work to put in the wide half of a YMM
     * register. Asking for {@code IntVector.SPECIES_256} here does not fail —
     * it silently stops being intrinsified and runs the Vector API's Java
     * fallback. Whether the native 128-bit Vector API kernel beats scalar code
     * remains a measurement question: E029 found that it does not on Ivy
     * Bridge-EP because C2 materialises vector wrappers. See
     * {@code KernelSelector}.
     *
     * <p>AVX1 is not merely SSE, and FastBlake does benefit from the
     * difference without asking: at {@code UseAVX >= 1} HotSpot emits the
     * VEX-encoded, three-operand forms of the same 128-bit integer
     * instructions, which removes the register-copy that the two-operand SSE
     * encoding forces ahead of every destructive operation.
     */
    static final boolean HAS_AVX1_ONLY = IS_X86_64 && PREFERRED_VECTOR_BITS != 0
            && MAX_INT_VECTOR_BITS <= 128 && MAX_FP_VECTOR_BITS >= 256;

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

    /**
     * Whether the JVM selected a native 512-bit preferred species on x86-64.
     *
     * <p>This is intentionally a JVM capability rather than an attempt to
     * parse OS-specific CPU feature files. The Vector API honours startup
     * restrictions such as {@code -XX:UseAVX} and {@code -XX:MaxVectorSize};
     * using its preferred species therefore means FastBlake never emits a
     * 512-bit shape the running JVM has elected not to use. On HotSpot x86-64,
     * a 512-bit preferred integer species is the usable AVX-512 configuration.
     */
    static final boolean HAS_NATIVE_AVX512 = IS_X86_64 && PREFERRED_VECTOR_BITS >= 512;

    /** The ISA shape the running JVM can actually use for integer vectors. */
    static String effectiveIsa() {
        return classifyIsa(ARCH, PREFERRED_VECTOR_BITS, MAX_INT_VECTOR_BITS, MAX_FP_VECTOR_BITS);
    }

    /**
     * The ISA classification, as a pure function of the four probed numbers.
     *
     * <p>Kept separate from the static fields so the table below can be tested
     * on any machine. Every profile FastBlake claims to distinguish is a case
     * in {@code CpuCapabilitiesIsaTest}; without this seam the AVX1 row could
     * only be checked by owning an Ivy Bridge.
     *
     * <table>
     *   <caption>x86-64 profiles</caption>
     *   <tr><th>preferred</th><th>max int</th><th>max fp</th><th>result</th></tr>
     *   <tr><td>512</td><td>512</td><td>512</td><td>{@code avx512}</td></tr>
     *   <tr><td>256</td><td>256</td><td>256</td><td>{@code avx2}</td></tr>
     *   <tr><td>128</td><td>128</td><td>256</td><td>{@code avx}</td></tr>
     *   <tr><td>128</td><td>128</td><td>128</td><td>{@code x86-vector-128}</td></tr>
     * </table>
     */
    static String classifyIsa(String arch, int preferredBits, int maxIntBits, int maxFpBits) {
        if (preferredBits == 0) {
            return "scalar";
        }
        if (isX86_64(arch)) {
            if (preferredBits >= 512) {
                return "avx512";
            }
            if (preferredBits >= 256) {
                return "avx2";
            }
            // AVX1: the floating-point datapath is wide and the integer one is
            // not. Reported distinctly because "x86-vector-128" would otherwise
            // make an Ivy Bridge indistinguishable from a Core 2, and the two
            // differ in the VEX encoding FastBlake's kernel is compiled to.
            if (maxIntBits <= 128 && maxFpBits >= 256) {
                return "avx";
            }
            return "x86-vector-" + preferredBits;
        }
        if (isAarch64(arch)) {
            return "neon";
        }
        return "vector-" + preferredBits;
    }

    private static boolean isAarch64(String arch) {
        return arch.equals("aarch64") || arch.equals("arm64");
    }

    private static boolean isX86_64(String arch) {
        return arch.equals("x86_64") || arch.equals("amd64");
    }

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
     * The widest species of one lane type this JVM will actually intrinsify.
     *
     * <p>{@code ofLargestShape} is the per-lane-type counterpart of
     * {@code ofPreferred}: it reports the JVM's own {@code max_vector_size}
     * for that type rather than the minimum across all types. Probed through
     * the same defensive {@link Throwable} catch as everything else here, so a
     * JVM without {@code jdk.incubator.vector} yields 0 instead of failing
     * class initialisation.
     */
    private static int probeLargestBits(Class<?> laneType) {
        if (PREFERRED_VECTOR_BITS == 0) {
            return 0;
        }
        try {
            return jdk.incubator.vector.VectorSpecies.ofLargestShape(laneType).vectorBitSize();
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
        sb.append(", effective ISA ").append(effectiveIsa())
                .append(", preferred vector width ").append(PREFERRED_VECTOR_BITS).append(" bits");
        if (HAS_AVX1_ONLY) {
            // Without this line a 128-bit width on a machine advertising AVX
            // Distinguish AVX1 from SSE without implying that the available
            // 128-bit Vector API kernel is necessarily the fastest choice.
            sb.append(" (AVX1: ").append(MAX_FP_VECTOR_BITS)
                    .append("-bit floating point but only ").append(MAX_INT_VECTOR_BITS)
                    .append("-bit integer vectors)");
        }
        if (WIDE_VECTOR_BITS != PREFERRED_VECTOR_BITS) {
            // A pinned width is a correctness lever that silently changes what
            // any throughput number means, so it is never left implicit.
            sb.append(" (wide kernel pinned to ").append(WIDE_VECTOR_BITS)
                    .append(" bits by -Dfastblake.wideBits; not a valid measurement");
            if (MAX_INT_VECTOR_BITS > 0 && WIDE_VECTOR_BITS > MAX_INT_VECTOR_BITS) {
                // Distinct from an ordinary pin: above this ceiling the kernel
                // is not merely running on a split species, it has left the
                // intrinsics entirely and is executing the Vector API's Java
                // fallback. Correct, and orders of magnitude slower.
                sb.append(", and above the ").append(MAX_INT_VECTOR_BITS)
                        .append("-bit integer vectors this JVM intrinsifies");
            }
            sb.append(')');
        }
        if (VECTOR_REGISTERS > 0) {
            sb.append(", ~").append(VECTOR_REGISTERS).append(" vector registers");
        } else {
            sb.append(", vector register count unknown");
        }
        return sb.toString();
    }
}
