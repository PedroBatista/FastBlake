package eu.pedrobatista.fastblake.harness;

/**
 * One BLAKE3 implementation entered into the comparison.
 *
 * <p>Conformance tests and benchmarks are both written against this interface,
 * so every contender is measured through the same call shapes on the same
 * bytes. Adding a contender means adding an implementation here and one line in
 * {@link Contenders} — nothing in the tests or benchmarks changes.
 *
 * <p><b>Availability.</b> A contender that cannot run on this machine — no Rust
 * toolchain, no GPU, not written yet — reports why via
 * {@link #unavailableReason()} and is skipped rather than failed. A run on a
 * machine with no Rust toolchain produces the same green build with one fewer
 * column of numbers.
 */
public interface Blake3Engine {

    /** Stable short id, used as the JMH {@code impl} parameter value. */
    String id();

    /** Human-readable name for reports, including the version being measured. */
    String displayName();

    /**
     * Why this contender cannot run here, or {@code null} if it can.
     *
     * <p>Implementations must answer without throwing, on any machine.
     */
    default String unavailableReason() {
        return null;
    }

    default boolean isAvailable() {
        return unavailableReason() == null;
    }

    /** A hasher in plain hashing mode. */
    Hasher newHasher();

    /** A hasher in keyed mode. {@code key} must be exactly 32 bytes. */
    Hasher newKeyedHasher(byte[] key);

    /** A hasher in key-derivation mode over a UTF-8 context string. */
    Hasher newKeyDerivationHasher(byte[] context);

    /**
     * One-shot convenience hash. Contenders that have a cheaper dedicated
     * one-shot path (fewer allocations, fewer native crossings) should override
     * this — small-input benchmarks are sensitive to exactly that difference.
     */
    default byte[] hash(byte[] input, int outputLen) {
        try (Hasher hasher = newHasher()) {
            hasher.update(input, 0, input.length);
            byte[] out = new byte[outputLen];
            hasher.doFinalize(out, 0, outputLen);
            return out;
        }
    }

    /**
     * Incremental hashing state.
     *
     * <p>{@link #close()} matters for contenders holding off-heap or device
     * memory; pure-Java ones inherit a no-op.
     */
    interface Hasher extends AutoCloseable {

        /** Appends {@code len} bytes starting at {@code off}. */
        Hasher update(byte[] input, int off, int len);

        /**
         * Writes {@code len} bytes of extended output without consuming the
         * state, so a hasher may be finalized more than once.
         */
        void doFinalize(byte[] output, int off, int len);

        /** Returns to the initial state, keeping mode and key. */
        Hasher reset();

        @Override
        default void close() {
            // Nothing to release for heap-only implementations.
        }
    }
}
