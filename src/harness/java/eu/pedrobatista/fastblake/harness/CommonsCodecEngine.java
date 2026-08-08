package eu.pedrobatista.fastblake.harness;

import org.apache.commons.codec.digest.Blake3;

/**
 * Apache Commons Codec's {@link Blake3} — the scalar Java baseline.
 *
 * <p>This is the contender everything else is judged against: it is pure JVM,
 * widely deployed, and pinned to the official test vectors by
 * {@code ContenderConformanceTest}. A FastBlake that loses to it is not fast.
 */
final class CommonsCodecEngine implements Blake3Engine {

    static final CommonsCodecEngine INSTANCE = new CommonsCodecEngine();

    private CommonsCodecEngine() {
    }

    @Override
    public String id() {
        return "commons";
    }

    /** The id, so JMH parameters and JUnit display names read cleanly. */
    @Override
    public String toString() {
        return id();
    }

    @Override
    public String displayName() {
        return "Apache Commons Codec (scalar Java)";
    }

    /**
     * Uses Commons Codec's static one-shot entry point for the 32-byte digest,
     * so this contender is measured through its best one-shot path too. Added
     * alongside FastBlake's in E025 P0b; before that both used the generic
     * hasher path and the comparison was fair for a different reason.
     */
    @Override
    public byte[] hash(byte[] input, int outputLen) {
        if (outputLen == 32) {
            return Blake3.hash(input);
        }
        return Blake3Engine.super.hash(input, outputLen);
    }

    @Override
    public Hasher newHasher() {
        return new CommonsHasher(Blake3.initHash());
    }

    @Override
    public Hasher newKeyedHasher(byte[] key) {
        return new CommonsHasher(Blake3.initKeyedHash(key));
    }

    @Override
    public Hasher newKeyDerivationHasher(byte[] context) {
        return new CommonsHasher(Blake3.initKeyDerivationFunction(context));
    }

    private record CommonsHasher(Blake3 delegate) implements Hasher {

        @Override
        public Hasher update(byte[] input, int off, int len) {
            delegate.update(input, off, len);
            return this;
        }

        @Override
        public void doFinalize(byte[] output, int off, int len) {
            delegate.doFinalize(output, off, len);
        }

        @Override
        public Hasher reset() {
            delegate.reset();
            return this;
        }
    }
}
