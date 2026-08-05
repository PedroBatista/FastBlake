package com.pedrobatista.fastblake;

/**
 * BLAKE3, optimised for JVM throughput.
 *
 * <p><b>Not implemented yet.</b> This is the API skeleton the harness is wired
 * against so that conformance tests and benchmarks compile and run today; every
 * method throws {@link UnsupportedOperationException}. The contender reports
 * itself unavailable and is skipped, so the pipeline stays green until the
 * bodies are filled in.
 *
 * <p>The shape deliberately mirrors {@code org.apache.commons.codec.digest.Blake3}
 * — the baseline this is measured against — so the two are trivially
 * interchangeable in the harness. Change it freely if a different API serves the
 * implementation better; only {@code JavaCpuEngine} needs updating to match.
 *
 * <p>Instances are not thread-safe.
 */
public final class FastBlake {

    private static final String TODO = "FastBlake does not implement BLAKE3 yet";

    private FastBlake() {
    }

    /** A hasher in plain hashing mode. */
    public static FastBlake initHash() {
        throw new UnsupportedOperationException(TODO);
    }

    /** A hasher in keyed mode. {@code key} must be exactly 32 bytes. */
    public static FastBlake initKeyedHash(byte[] key) {
        throw new UnsupportedOperationException(TODO);
    }

    /** A hasher in key-derivation mode over a UTF-8 context string. */
    public static FastBlake initKeyDerivationFunction(byte[] context) {
        throw new UnsupportedOperationException(TODO);
    }

    /** One-shot 32-byte digest. */
    public static byte[] hash(byte[] input) {
        throw new UnsupportedOperationException(TODO);
    }

    public FastBlake update(byte[] input) {
        throw new UnsupportedOperationException(TODO);
    }

    public FastBlake update(byte[] input, int offset, int length) {
        throw new UnsupportedOperationException(TODO);
    }

    /**
     * Writes {@code length} bytes of extended output. Does not consume the
     * state — a hasher may be finalized more than once, at different lengths.
     */
    public FastBlake doFinalize(byte[] output, int offset, int length) {
        throw new UnsupportedOperationException(TODO);
    }

    /** Extended output of {@code length} bytes as a fresh array. */
    public byte[] doFinalize(int length) {
        throw new UnsupportedOperationException(TODO);
    }

    /** Returns to the initial state, keeping mode and key. */
    public FastBlake reset() {
        throw new UnsupportedOperationException(TODO);
    }
}
