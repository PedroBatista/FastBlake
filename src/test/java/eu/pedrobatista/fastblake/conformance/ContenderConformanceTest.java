package eu.pedrobatista.fastblake.conformance;

import eu.pedrobatista.fastblake.harness.Blake3Engine;
import eu.pedrobatista.fastblake.harness.Blake3TestVectors;
import eu.pedrobatista.fastblake.harness.Blake3TestVectors.Case;
import eu.pedrobatista.fastblake.harness.Contenders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static eu.pedrobatista.fastblake.harness.Blake3TestVectors.DEFAULT_OUTPUT_LEN;
import static eu.pedrobatista.fastblake.harness.Blake3TestVectors.EXTENDED_OUTPUT_LEN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds every available contender to the official BLAKE3 test vectors.
 *
 * <p>This is what makes the comparison mean anything: a benchmark between
 * implementations that do not compute the same function measures nothing. Every
 * contender faces the identical 35 input lengths in all three modes before any
 * of its timings are worth reading.
 *
 * <p>Contenders that cannot run on this machine — no Rust toolchain, no GPU, not
 * written yet — contribute no test cases and the suite stays green. Commons
 * Codec is always present, so the suite is never empty.
 */
class ContenderConformanceTest {

    /** Every (available contender × official vector) pair. */
    static Stream<Arguments> contendersAndCases() {
        return Contenders.available().stream().flatMap(engine ->
                Blake3TestVectors.cases().stream().map(c -> Arguments.of(engine, c)));
    }

    static Stream<Blake3Engine> contenders() {
        return Contenders.available().stream();
    }

    @ParameterizedTest(name = "{0} hash[{1}]")
    @MethodSource("contendersAndCases")
    void hashMatchesReference(Blake3Engine engine, Case c) {
        assertArrayEquals(c.hash(), extended(engine.newHasher(), c.input()));
    }

    @ParameterizedTest(name = "{0} keyed_hash[{1}]")
    @MethodSource("contendersAndCases")
    void keyedHashMatchesReference(Blake3Engine engine, Case c) {
        assertArrayEquals(
                c.keyedHash(),
                extended(engine.newKeyedHasher(Blake3TestVectors.key()), c.input()));
    }

    @ParameterizedTest(name = "{0} derive_key[{1}]")
    @MethodSource("contendersAndCases")
    void deriveKeyMatchesReference(Blake3Engine engine, Case c) {
        assertArrayEquals(
                c.deriveKey(),
                extended(
                        engine.newKeyDerivationHasher(Blake3TestVectors.context()),
                        c.input()));
    }

    /**
     * The 32-byte digest must be the prefix of the extended output, not a
     * separate function. This also exercises each contender's dedicated
     * one-shot path, which for Rust is a different native entry point than the
     * incremental one above.
     */
    @ParameterizedTest(name = "{0} one_shot[{1}]")
    @MethodSource("contendersAndCases")
    void oneShotDigestIsPrefixOfExtendedOutput(Blake3Engine engine, Case c) {
        assertArrayEquals(
                Arrays.copyOf(c.hash(), DEFAULT_OUTPUT_LEN),
                engine.hash(c.input(), DEFAULT_OUTPUT_LEN));
    }

    /**
     * Feeding the input in awkward pieces must not change the digest. The sizes
     * straddle BLAKE3's 64-byte block and 1024-byte chunk boundaries, where
     * buffering bugs live.
     */
    @ParameterizedTest(name = "{0} incremental[{1}]")
    @MethodSource("contendersAndCases")
    void incrementalUpdatesMatchOneShot(Blake3Engine engine, Case c) {
        byte[] input = c.input();
        for (int chunk : new int[] {1, 7, 63, 64, 65, 1023, 1024, 1025}) {
            try (Blake3Engine.Hasher hasher = engine.newHasher()) {
                for (int off = 0; off < input.length; off += chunk) {
                    hasher.update(input, off, Math.min(chunk, input.length - off));
                }
                byte[] actual = new byte[EXTENDED_OUTPUT_LEN];
                hasher.doFinalize(actual, 0, EXTENDED_OUTPUT_LEN);
                assertArrayEquals(c.hash(), actual, "chunk size " + chunk);
            }
        }
    }

    /** Finalizing must not consume the state — the benchmarks rely on reuse. */
    @ParameterizedTest(name = "{0} repeatable_finalize")
    @MethodSource("contenders")
    void finalizeDoesNotConsumeState(Blake3Engine engine) {
        Case c = caseOfLength(1025);
        try (Blake3Engine.Hasher hasher = engine.newHasher()) {
            hasher.update(c.input(), 0, c.inputLen());
            byte[] first = new byte[EXTENDED_OUTPUT_LEN];
            byte[] second = new byte[EXTENDED_OUTPUT_LEN];
            hasher.doFinalize(first, 0, EXTENDED_OUTPUT_LEN);
            hasher.doFinalize(second, 0, EXTENDED_OUTPUT_LEN);
            assertArrayEquals(c.hash(), first);
            assertArrayEquals(first, second, "second finalize differed");
        }
    }

    /** Output length must be free: a short read is a prefix of a long one. */
    @ParameterizedTest(name = "{0} partial_output")
    @MethodSource("contenders")
    void shorterOutputIsAPrefix(Blake3Engine engine) {
        Case c = caseOfLength(2049);
        try (Blake3Engine.Hasher hasher = engine.newHasher()) {
            hasher.update(c.input(), 0, c.inputLen());
            for (int len : new int[] {1, 31, 32, 33, 64, 65, EXTENDED_OUTPUT_LEN}) {
                byte[] actual = new byte[len];
                hasher.doFinalize(actual, 0, len);
                assertArrayEquals(Arrays.copyOf(c.hash(), len), actual, "output length " + len);
            }
        }
    }

    @ParameterizedTest(name = "{0} reset")
    @MethodSource("contenders")
    void resetRestoresInitialState(Blake3Engine engine) {
        Case c = caseOfLength(4097);
        try (Blake3Engine.Hasher hasher = engine.newHasher()) {
            hasher.update(Blake3TestVectors.input(4096), 0, 4096);
            hasher.reset();
            hasher.update(c.input(), 0, c.inputLen());
            byte[] actual = new byte[EXTENDED_OUTPUT_LEN];
            hasher.doFinalize(actual, 0, EXTENDED_OUTPUT_LEN);
            assertArrayEquals(c.hash(), actual);
        }
    }

    /**
     * Writing into the middle of a caller's buffer must not touch its
     * surroundings — an easy thing to get wrong in a hand-rolled output path.
     */
    @ParameterizedTest(name = "{0} output_offset")
    @MethodSource("contenders")
    void respectsOutputOffset(Blake3Engine engine) {
        Case c = caseOfLength(1024);
        byte[] canvas = new byte[EXTENDED_OUTPUT_LEN + 16];
        Arrays.fill(canvas, (byte) 0xAB);
        try (Blake3Engine.Hasher hasher = engine.newHasher()) {
            hasher.update(c.input(), 0, c.inputLen());
            hasher.doFinalize(canvas, 8, EXTENDED_OUTPUT_LEN);
        }
        assertArrayEquals(c.hash(), Arrays.copyOfRange(canvas, 8, 8 + EXTENDED_OUTPUT_LEN));
        for (int i : new int[] {0, 7, EXTENDED_OUTPUT_LEN + 8, EXTENDED_OUTPUT_LEN + 15}) {
            assertEquals((byte) 0xAB, canvas[i], "clobbered byte " + i);
        }
    }

    /** Likewise for reading from the middle of a caller's input buffer. */
    @ParameterizedTest(name = "{0} input_offset")
    @MethodSource("contenders")
    void respectsInputOffset(Blake3Engine engine) {
        Case c = caseOfLength(3072);
        byte[] padded = new byte[c.inputLen() + 24];
        Arrays.fill(padded, (byte) 0xCD);
        System.arraycopy(c.input(), 0, padded, 11, c.inputLen());
        try (Blake3Engine.Hasher hasher = engine.newHasher()) {
            hasher.update(padded, 11, c.inputLen());
            byte[] actual = new byte[EXTENDED_OUTPUT_LEN];
            hasher.doFinalize(actual, 0, EXTENDED_OUTPUT_LEN);
            assertArrayEquals(c.hash(), actual);
        }
    }

    @Test
    void vectorsCoverTheExpectedShape() {
        List<Case> cases = Blake3TestVectors.cases();
        assertEquals(35, cases.size(), "official vector count");
        assertEquals(0, cases.getFirst().inputLen());
        assertEquals(102400, cases.getLast().inputLen());
        assertEquals(32, Blake3TestVectors.key().length);
    }

    /**
     * A contender may be absent, but never silently: an unavailable one has to
     * say why, so a run with a missing column is self-explaining.
     */
    @Test
    void unavailableContendersExplainThemselves() {
        for (Blake3Engine engine : Contenders.all()) {
            assertNotNull(engine.id());
            assertNotNull(engine.displayName());
            String reason = engine.unavailableReason();
            if (reason != null) {
                assertFalse(reason.isBlank(),
                        engine.id() + " is unavailable without saying why");
            }
        }
        assertTrue(Contenders.available().contains(Contenders.require("commons")),
                "the Commons Codec baseline must always be available");
    }

    private static Case caseOfLength(int len) {
        return Blake3TestVectors.cases().stream()
                .filter(c -> c.inputLen() == len)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no official vector of length " + len));
    }

    private static byte[] extended(Blake3Engine.Hasher hasher, byte[] input) {
        try (hasher) {
            hasher.update(input, 0, input.length);
            byte[] out = new byte[EXTENDED_OUTPUT_LEN];
            hasher.doFinalize(out, 0, EXTENDED_OUTPUT_LEN);
            return out;
        }
    }
}
