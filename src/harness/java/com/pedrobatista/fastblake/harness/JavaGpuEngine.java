package com.pedrobatista.fastblake.harness;

/**
 * FastBlake's GPU implementation — planned, not started.
 *
 * <p>It is registered now so the comparison has a fourth column from day one and
 * nothing about the harness has to change when it lands.
 *
 * <p>BLAKE3 suits a GPU well: the tree structure makes every 1 KiB chunk
 * independent, so a large input decomposes into thousands of parallel chunk
 * compressions with only the small parent-node merge left to do. The shape to
 * expect is therefore the mirror image of the CPU contenders — badly beaten on
 * small inputs, where kernel launch and host-to-device transfer dominate, and
 * competitive only once the input is large enough to amortise both. That is why
 * the benchmark sweeps up to 4 MiB and why transfer cost stays inside the
 * measured region: a number that excludes it would not describe anything a
 * caller can actually get.
 *
 * <p><b>To switch this contender on:</b> implement the device path, delegate to
 * it below, and delete the {@link #unavailableReason()} override — leaving it
 * reporting the real reason ("no compatible device", "driver not found") on
 * machines that cannot run it, so those machines still get a green build.
 */
final class JavaGpuEngine implements Blake3Engine {

    static final JavaGpuEngine INSTANCE = new JavaGpuEngine();

    private JavaGpuEngine() {
    }

    @Override
    public String id() {
        return "java-gpu";
    }

    /** The id, so JMH parameters and JUnit display names read cleanly. */
    @Override
    public String toString() {
        return id();
    }

    @Override
    public String displayName() {
        return "FastBlake GPU (Java, device offload)";
    }

    @Override
    public String unavailableReason() {
        // Replace with a genuine device/driver probe when the implementation lands.
        return "not implemented yet — planned contender";
    }

    @Override
    public Hasher newHasher() {
        throw new UnsupportedOperationException(unavailableReason());
    }

    @Override
    public Hasher newKeyedHasher(byte[] key) {
        throw new UnsupportedOperationException(unavailableReason());
    }

    @Override
    public Hasher newKeyDerivationHasher(byte[] context) {
        throw new UnsupportedOperationException(unavailableReason());
    }
}
