package eu.pedrobatista.fastblake;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential test for the E028 preferred-width kernel.
 *
 * <p>The official-vector suite reaches a kernel only when the JVM has selected
 * it, and selection is a static decision made once per JVM. That gives the wide
 * kernel full end-to-end coverage under
 * {@code -Dfastblake.kernel=wide ./gradlew test --rerun}, but no coverage at all
 * in an ordinary build — on a machine that never selects it, which today is
 * every machine.
 *
 * <p>So this test calls the kernel directly and holds it against
 * {@link Blake3ChunkVectorScratch}, which the official vectors do cover on every
 * machine that ships it. Any lane count that is a multiple of four can be
 * checked this way, so the assertion is meaningful at 4, 8 and 16 lanes without
 * needing AVX2 or AVX-512 hardware present.
 */
class WideChunkKernelTest {

    private static final int CHUNK_LEN = 1024;
    private static final int LANES = Blake3ChunkVectorWide.LANES;

    @Test
    void laneCountIsAMultipleOfTheOracleBatch() {
        // Not a style rule: the four-chunk kernel is both the oracle below and
        // the ladder rung FastBlake runs underneath the wide kernel, and both
        // uses assume complete groups of four.
        assertTrue(LANES >= 4 && LANES % 4 == 0,
                "wide kernel lane count must be a multiple of four, was " + LANES);
        assertEquals(16 * LANES, Blake3ChunkVectorWide.packedWordsLength());
        assertEquals(8 * LANES, Blake3ChunkVectorWide.outputLength());
    }

    @Test
    void matchesTheFourChunkKernelInPlainMode() {
        assertMatchesFourChunkKernel(0L, 0);
    }

    @Test
    void matchesTheFourChunkKernelWithFlags() {
        assertMatchesFourChunkKernel(0L, 16);
    }

    /**
     * The chunk counter is split across two lane vectors, so a batch that
     * straddles 2^32 is the case where a mistake in the high half hides.
     */
    @Test
    void matchesTheFourChunkKernelAcrossTheCounterBoundary() {
        assertMatchesFourChunkKernel(0xFFFFFFFFL - 2, 0);
    }

    /** The kernel reads from an offset, not only from the start of the array. */
    @Test
    void matchesTheFourChunkKernelAtANonZeroOffset() {
        byte[] input = pseudoRandom((LANES + 1) * CHUNK_LEN, 99);
        assertArrayEquals(fourChunkCvs(input, CHUNK_LEN, 7L, 0),
                wideCvs(input, CHUNK_LEN, 7L, 0));
    }

    private void assertMatchesFourChunkKernel(long firstCounter, int flags) {
        byte[] input = pseudoRandom(LANES * CHUNK_LEN, (int) firstCounter + flags);
        assertArrayEquals(fourChunkCvs(input, 0, firstCounter, flags),
                wideCvs(input, 0, firstCounter, flags));
    }

    private static int[] wideCvs(byte[] input, int offset, long firstCounter, int flags) {
        int[] output = new int[Blake3ChunkVectorWide.outputLength()];
        Blake3ChunkVectorWide.hashChunks(input, offset, firstCounter, FastBlake.IV_WORDS,
                flags, new int[Blake3ChunkVectorWide.packedWordsLength()], output);
        return output;
    }

    private static int[] fourChunkCvs(byte[] input, int offset, long firstCounter, int flags) {
        int[] expected = new int[8 * LANES];
        int[] batch = new int[Blake3ChunkVectorScratch.outputLength()];
        int[] messages = new int[Blake3ChunkVectorScratch.packedWordsLength()];
        for (int group = 0; group < LANES / 4; group++) {
            Blake3ChunkVectorScratch.hashChunks(input, offset + group * 4 * CHUNK_LEN,
                    firstCounter + group * 4L, FastBlake.IV_WORDS, flags, messages, batch);
            System.arraycopy(batch, 0, expected, group * 32, 32);
        }
        return expected;
    }

    private static byte[] pseudoRandom(int length, int seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }
}
