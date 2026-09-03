package eu.pedrobatista.fastblake;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ISA classification table, checked on every machine.
 *
 * <p>{@link CpuCapabilities} can otherwise only ever observe the one host it
 * runs on, which makes every row but that host's untested. The AVX1 row is the
 * reason this test exists: telling AVX1 from SSE is the difference between two
 * numbers the Vector API reports separately, and the mistake it guards against
 * — reading AVX1's 256-bit floating-point width as licence to emit a 256-bit
 * <em>integer</em> kernel — is invisible in a passing conformance run, because
 * a non-intrinsified species is still correct. It is only slow.
 *
 * <p>Widths below are what HotSpot's {@code Matcher::vector_width_in_bytes}
 * yields for each configuration: 32-byte vectors for {@code float} and
 * {@code double} once {@code UseAVX >= 1}, but 32-byte integral vectors only
 * from {@code UseAVX >= 2}.
 */
class CpuCapabilitiesIsaTest {

    @Test
    void avx1ReportsItselfDistinctlyFromSse() {
        // Sandy/Ivy Bridge: AVX widened floating point only.
        assertEquals("avx", CpuCapabilities.classifyIsa("x86_64", 128, 128, 256));
        assertEquals("avx", CpuCapabilities.classifyIsa("amd64", 128, 128, 256));
    }

    @Test
    void sseIsNotMistakenForAvx1() {
        // Nehalem and older: no 256-bit anything.
        assertEquals("x86-vector-128", CpuCapabilities.classifyIsa("x86_64", 128, 128, 128));
    }

    @Test
    void avx2AndAvx512AreUnchanged() {
        assertEquals("avx2", CpuCapabilities.classifyIsa("x86_64", 256, 256, 256));
        assertEquals("avx512", CpuCapabilities.classifyIsa("x86_64", 512, 512, 512));
    }

    /**
     * {@code -XX:UseAVX=1} on an AVX2 or AVX-512 part is indistinguishable from
     * real AVX1 hardware, and must be, because it is the process's actual
     * capability that decides which kernel is right.
     */
    @Test
    void anAvx512PartRestrictedToAvx1ReportsAvx1() {
        assertEquals("avx", CpuCapabilities.classifyIsa("x86_64", 128, 128, 256));
    }

    /**
     * {@code -XX:MaxVectorSize=16} clamps every lane type, floating point
     * included, so it is SSE-shaped rather than AVX1-shaped.
     */
    @Test
    void anAvx2PartClampedToSixteenBytesReportsSseWidth() {
        assertEquals("x86-vector-128", CpuCapabilities.classifyIsa("x86_64", 128, 128, 128));
    }

    @Test
    void nonX86ProfilesAreUnaffectedByTheNewWidths() {
        assertEquals("neon", CpuCapabilities.classifyIsa("aarch64", 128, 128, 128));
        assertEquals("neon", CpuCapabilities.classifyIsa("arm64", 128, 128, 128));
        assertEquals("vector-256", CpuCapabilities.classifyIsa("riscv64", 256, 256, 256));
    }

    @Test
    void noVectorApiIsScalarWhateverTheWidths() {
        assertEquals("scalar", CpuCapabilities.classifyIsa("x86_64", 0, 0, 0));
    }

    /**
     * The AVX1 flag and the AVX1 label must agree; a machine cannot be routed
     * down the AVX1 branch of {@link KernelSelector} while reporting a
     * different ISA in the same log line.
     */
    @Test
    void theRunningHostIsSelfConsistent() {
        assertEquals(CpuCapabilities.HAS_AVX1_ONLY,
                "avx".equals(CpuCapabilities.effectiveIsa()));
        if (CpuCapabilities.PREFERRED_VECTOR_BITS != 0) {
            // The preferred shape is the minimum across lane types, so it can
            // never exceed the integer-only ceiling the kernels are bound by.
            assertTrue(CpuCapabilities.MAX_INT_VECTOR_BITS >= CpuCapabilities.PREFERRED_VECTOR_BITS,
                    "preferred width " + CpuCapabilities.PREFERRED_VECTOR_BITS
                            + " exceeds the widest intrinsified integer vector "
                            + CpuCapabilities.MAX_INT_VECTOR_BITS);
        }
    }

    /** E029's measured AVX1 default; explicit diagnostic overrides remain valid. */
    @Test
    void anAvx1HostSelectsTheMeasuredScalarKernel() {
        if (!CpuCapabilities.HAS_AVX1_ONLY
                || System.getProperty("fastblake.kernel") != null) {
            return;
        }
        assertEquals(KernelSelector.Kernel.SCALAR, KernelSelector.selected(),
                "E029 rejected the Vector API kernels on AVX1; " + KernelSelector.describe());
    }

    @Test
    void automaticDispatchChangesOnlyTheMeasuredAvx1Profile() {
        assertEquals(KernelSelector.Kernel.SCALAR,
                KernelSelector.selectAutomaticallyForProfile(false, false, 0,
                        false, false, true));
        assertEquals(KernelSelector.Kernel.EIGHT_CHUNK,
                KernelSelector.selectAutomaticallyForProfile(true, true, 32,
                        false, false, false));
        assertEquals(KernelSelector.Kernel.FOUR_CHUNK,
                KernelSelector.selectAutomaticallyForProfile(true, true, 16,
                        false, false, false));
        assertEquals(KernelSelector.Kernel.WIDE,
                KernelSelector.selectAutomaticallyForProfile(true, false, 32,
                        true, false, true));
        assertEquals(KernelSelector.Kernel.FOUR_CHUNK,
                KernelSelector.selectAutomaticallyForProfile(true, false, 16,
                        false, false, true), "AVX2 remains on the measured four-chunk default");
        assertEquals(KernelSelector.Kernel.SCALAR,
                KernelSelector.selectAutomaticallyForProfile(true, false, 16,
                        false, true, true), "E029 measured AVX1 scalar as the winner");
        assertEquals(KernelSelector.Kernel.FOUR_CHUNK,
                KernelSelector.selectAutomaticallyForProfile(true, false, 16,
                        false, false, true), "the unmeasured SSE profile is unchanged");
        assertEquals(KernelSelector.Kernel.FOUR_CHUNK,
                KernelSelector.selectAutomaticallyForProfile(true, false, 0,
                        false, false, false), "unknown vector architectures are unchanged");
    }
}
