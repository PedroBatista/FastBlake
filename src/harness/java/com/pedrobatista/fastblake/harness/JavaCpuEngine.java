package com.pedrobatista.fastblake.harness;

import com.pedrobatista.fastblake.FastBlake;

/**
 * FastBlake's CPU implementation — the contender this project exists to build.
 *
 * <p>Target: beat the Commons Codec baseline decisively and close as much of the
 * gap to the Rust contender as the JVM allows. The levers are the Vector API for
 * BLAKE3's 8-way/16-way chunk parallelism, and flattening per-call setup.
 *
 * <p>The scalar implementation is always available. Vectorized kernels can be
 * selected internally when the runtime and input shape support them.
 */
final class JavaCpuEngine implements Blake3Engine {

    static final JavaCpuEngine INSTANCE = new JavaCpuEngine();

    private JavaCpuEngine() {
    }

    @Override
    public String id() {
        return "java-cpu";
    }

    /** The id, so JMH parameters and JUnit display names read cleanly. */
    @Override
    public String toString() {
        return id();
    }

    @Override
    public String displayName() {
        return "FastBlake CPU — " + FastBlake.selectedKernel();
    }

    /**
     * Uses FastBlake's dedicated one-shot entry point, which takes a
     * single-chunk fast path for inputs of at most 1 KiB (E025 P0b).
     */
    @Override
    public byte[] hash(byte[] input, int outputLen) {
        return FastBlake.hash(input, 0, input.length, outputLen);
    }

    @Override
    public Hasher newHasher() {
        return new FastBlakeHasher(FastBlake.initHash());
    }

    @Override
    public Hasher newKeyedHasher(byte[] key) {
        return new FastBlakeHasher(FastBlake.initKeyedHash(key));
    }

    @Override
    public Hasher newKeyDerivationHasher(byte[] context) {
        return new FastBlakeHasher(FastBlake.initKeyDerivationFunction(context));
    }

    private record FastBlakeHasher(FastBlake delegate) implements Hasher {

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
