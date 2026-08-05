package com.pedrobatista.fastblake;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * A dependency-free BLAKE3 implementation.
 *
 * <p>The implementation keeps the final chunk uncommitted until more input is
 * seen. That is required because the final node needs the {@code ROOT} flag and
 * is also the source of extended output. Instances are mutable and not
 * thread-safe; finalization is repeatable and does not consume the state.
 */
public final class FastBlake {

    private static final int OUT_LEN = 32;
    private static final int KEY_LEN = 32;
    private static final int BLOCK_LEN = 64;
    private static final int CHUNK_LEN = 1024;
    private static final int MAX_DEPTH = 54;

    private static final int CHUNK_START = 1;
    private static final int CHUNK_END = 2;
    private static final int PARENT = 4;
    private static final int ROOT = 8;
    private static final int KEYED_HASH = 16;
    private static final int DERIVE_KEY_CONTEXT = 32;
    private static final int DERIVE_KEY_MATERIAL = 64;

    private static final int[] IV = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
            0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
    };

    /** The message word order for each of BLAKE3's seven rounds. */
    private static final byte[][] SCHEDULE = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8},
            {3, 4, 10, 12, 13, 2, 7, 14, 6, 5, 9, 0, 11, 15, 8, 1},
            {10, 7, 12, 9, 14, 3, 13, 15, 4, 0, 11, 2, 5, 8, 1, 6},
            {12, 13, 9, 11, 15, 10, 14, 8, 7, 2, 5, 3, 0, 1, 6, 4},
            {9, 14, 11, 5, 8, 12, 15, 1, 13, 3, 0, 10, 2, 6, 4, 7},
            {11, 15, 5, 0, 1, 9, 8, 6, 14, 10, 2, 12, 3, 4, 7, 13}
    };

    private final int[] key = new int[8];
    private final int modeFlags;
    private final byte[] chunk = new byte[CHUNK_LEN];
    private final int[] cvStack = new int[MAX_DEPTH * 8];

    // Scratch used only by mutating update/reset operations. Finalization owns
    // separate scratch so it cannot consume or perturb the hasher state.
    private final int[] scratchState = new int[16];
    private final int[] scratchWords = new int[16];
    private final int[] scratchCv = new int[8];

    private int chunkLength;
    private long chunksCompressed;
    private int cvStackLength;

    private FastBlake(int[] initialKey, int flags) {
        System.arraycopy(initialKey, 0, key, 0, 8);
        modeFlags = flags;
    }

    /** Returns a hasher in ordinary hashing mode. */
    public static FastBlake initHash() {
        return new FastBlake(IV, 0);
    }

    /** Returns a keyed hasher. The key must contain exactly 32 bytes. */
    public static FastBlake initKeyedHash(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length != KEY_LEN) {
            throw new IllegalArgumentException("BLAKE3 key must be exactly 32 bytes");
        }
        int[] words = new int[8];
        bytesToWords(key, 0, words, 8);
        return new FastBlake(words, KEYED_HASH);
    }

    /** Returns a key-derivation hasher over a UTF-8 context string. */
    public static FastBlake initKeyDerivationFunction(byte[] context) {
        Objects.requireNonNull(context, "context");
        FastBlake contextHasher = new FastBlake(IV, DERIVE_KEY_CONTEXT);
        contextHasher.update(context);
        byte[] contextKey = contextHasher.doFinalize(KEY_LEN);
        int[] words = new int[8];
        bytesToWords(contextKey, 0, words, 8);
        return new FastBlake(words, DERIVE_KEY_MATERIAL);
    }

    /** Convenience overload for a Java context string. */
    public static FastBlake initKeyDerivationFunction(String context) {
        Objects.requireNonNull(context, "context");
        return initKeyDerivationFunction(context.getBytes(StandardCharsets.UTF_8));
    }

    /** Computes a one-shot 32-byte digest. */
    public static byte[] hash(byte[] input) {
        Objects.requireNonNull(input, "input");
        return initHash().update(input).doFinalize(OUT_LEN);
    }

    /** Appends all input bytes. */
    public FastBlake update(byte[] input) {
        Objects.requireNonNull(input, "input");
        return update(input, 0, input.length);
    }

    /** Appends {@code length} bytes starting at {@code offset}. */
    public FastBlake update(byte[] input, int offset, int length) {
        Objects.requireNonNull(input, "input");
        Objects.checkFromIndexSize(offset, length, input.length);

        int remaining = length;
        int position = offset;
        while (remaining > 0) {
            // A full chunk is retained until another byte proves it is not the
            // root/rightmost chunk.
            if (chunkLength == CHUNK_LEN) {
                chunkChainingValue(chunk, CHUNK_LEN, chunksCompressed, key, modeFlags,
                        scratchCv, scratchWords, scratchState);
                chunksCompressed++;
                pushChunkCv(scratchCv, chunksCompressed);
                chunkLength = 0;
            }

            int take = Math.min(remaining, CHUNK_LEN - chunkLength);
            System.arraycopy(input, position, chunk, chunkLength, take);
            chunkLength += take;
            position += take;
            remaining -= take;
        }
        return this;
    }

    /**
     * Writes extended output without consuming this hasher.
     */
    public FastBlake doFinalize(byte[] output, int offset, int length) {
        Objects.requireNonNull(output, "output");
        Objects.checkFromIndexSize(offset, length, output.length);
        if (length == 0) {
            return this;
        }

        int[] state = new int[16];
        int[] words = new int[16];
        int[] rightCv = new int[8];
        Output root = chunkOutput(chunk, chunkLength, chunksCompressed, key, modeFlags,
                words, state);

        for (int stackIndex = cvStackLength - 1; stackIndex >= 0; stackIndex--) {
            root.chainingValue(rightCv, state);
            int[] parentWords = new int[16];
            System.arraycopy(cvStack, stackIndex * 8, parentWords, 0, 8);
            System.arraycopy(rightCv, 0, parentWords, 8, 8);
            root = new Output(key, parentWords, 0, BLOCK_LEN, modeFlags | PARENT);
        }
        root.rootBytes(output, offset, length, state);
        return this;
    }

    /** Returns extended output in a new array. */
    public byte[] doFinalize(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("negative output length: " + length);
        }
        byte[] output = new byte[length];
        doFinalize(output, 0, length);
        return output;
    }

    /** Restores the initial state while retaining mode and key. */
    public FastBlake reset() {
        chunkLength = 0;
        chunksCompressed = 0;
        cvStackLength = 0;
        Arrays.fill(chunk, (byte) 0);
        return this;
    }

    private void pushChunkCv(int[] newCv, long totalChunks) {
        System.arraycopy(newCv, 0, scratchCv, 0, 8);
        long count = totalChunks;
        while ((count & 1) == 0) {
            int leftOffset = --cvStackLength * 8;
            parentCv(cvStack, leftOffset, scratchCv, key, modeFlags,
                    scratchCv, scratchWords, scratchState);
            count >>>= 1;
        }
        System.arraycopy(scratchCv, 0, cvStack, cvStackLength * 8, 8);
        cvStackLength++;
    }

    private static Output chunkOutput(byte[] input, int length, long chunkCounter,
                                      int[] key, int flags, int[] words, int[] state) {
        int[] cv = Arrays.copyOf(key, 8);
        int blocksBeforeLast = length == 0 ? 0 : (length - 1) / BLOCK_LEN;
        for (int block = 0; block < blocksBeforeLast; block++) {
            bytesToWords(input, block * BLOCK_LEN, words, 16);
            int blockFlags = flags | (block == 0 ? CHUNK_START : 0);
            compress(cv, words, chunkCounter, BLOCK_LEN, blockFlags, state);
            System.arraycopy(state, 0, cv, 0, 8);
        }

        Arrays.fill(words, 0);
        int lastOffset = blocksBeforeLast * BLOCK_LEN;
        int lastLength = length - lastOffset;
        partialBytesToWords(input, lastOffset, lastLength, words);
        int outputFlags = flags | CHUNK_END | (blocksBeforeLast == 0 ? CHUNK_START : 0);
        return new Output(cv, Arrays.copyOf(words, 16), chunkCounter, lastLength, outputFlags);
    }

    private static void chunkChainingValue(byte[] input, int length, long chunkCounter,
                                           int[] key, int flags, int[] output,
                                           int[] words, int[] state) {
        // This path is invoked once per non-final chunk, so it must remain
        // allocation-free. Finalization uses chunkOutput instead because it
        // needs to retain the last compression input for ROOT/XOF output.
        System.arraycopy(key, 0, output, 0, 8);
        int blocks = (length + BLOCK_LEN - 1) / BLOCK_LEN;
        for (int block = 0; block < blocks; block++) {
            int blockOffset = block * BLOCK_LEN;
            int blockLength = Math.min(BLOCK_LEN, length - blockOffset);
            if (blockLength == BLOCK_LEN) {
                bytesToWords(input, blockOffset, words, 16);
            } else {
                Arrays.fill(words, 0);
                partialBytesToWords(input, blockOffset, blockLength, words);
            }
            int blockFlags = flags
                    | (block == 0 ? CHUNK_START : 0)
                    | (block == blocks - 1 ? CHUNK_END : 0);
            compress(output, words, chunkCounter, blockLength, blockFlags, state);
            System.arraycopy(state, 0, output, 0, 8);
        }
    }

    private static void parentCv(int[] left, int leftOffset, int[] right, int[] key, int flags,
                                 int[] output, int[] words, int[] state) {
        System.arraycopy(left, leftOffset, words, 0, 8);
        System.arraycopy(right, 0, words, 8, 8);
        compress(key, words, 0, BLOCK_LEN, flags | PARENT, state);
        System.arraycopy(state, 0, output, 0, 8);
    }

    private static final class Output {
        private final int[] inputCv;
        private final int[] blockWords;
        private final long counter;
        private final int blockLength;
        private final int flags;

        private Output(int[] inputCv, int[] blockWords, long counter, int blockLength, int flags) {
            this.inputCv = inputCv;
            this.blockWords = blockWords;
            this.counter = counter;
            this.blockLength = blockLength;
            this.flags = flags;
        }

        private void chainingValue(int[] output, int[] state) {
            compress(inputCv, blockWords, counter, blockLength, flags, state);
            System.arraycopy(state, 0, output, 0, 8);
        }

        private void rootBytes(byte[] output, int offset, int length, int[] state) {
            long outputBlockCounter = 0;
            int written = 0;
            while (written < length) {
                compress(inputCv, blockWords, outputBlockCounter++, blockLength, flags | ROOT, state);
                int take = Math.min(BLOCK_LEN, length - written);
                wordsToBytes(state, output, offset + written, take);
                written += take;
            }
        }
    }

    private static void compress(int[] cv, int[] block, long counter, int blockLength,
                                 int flags, int[] state) {
        System.arraycopy(cv, 0, state, 0, 8);
        System.arraycopy(IV, 0, state, 8, 4);
        state[12] = (int) counter;
        state[13] = (int) (counter >>> 32);
        state[14] = blockLength;
        state[15] = flags;

        for (byte[] schedule : SCHEDULE) {
            g(state, 0, 4, 8, 12, block[schedule[0]], block[schedule[1]]);
            g(state, 1, 5, 9, 13, block[schedule[2]], block[schedule[3]]);
            g(state, 2, 6, 10, 14, block[schedule[4]], block[schedule[5]]);
            g(state, 3, 7, 11, 15, block[schedule[6]], block[schedule[7]]);
            g(state, 0, 5, 10, 15, block[schedule[8]], block[schedule[9]]);
            g(state, 1, 6, 11, 12, block[schedule[10]], block[schedule[11]]);
            g(state, 2, 7, 8, 13, block[schedule[12]], block[schedule[13]]);
            g(state, 3, 4, 9, 14, block[schedule[14]], block[schedule[15]]);
        }

        for (int i = 0; i < 8; i++) {
            state[i] ^= state[i + 8];
            state[i + 8] ^= cv[i];
        }
    }

    private static void g(int[] state, int a, int b, int c, int d, int mx, int my) {
        state[a] = state[a] + state[b] + mx;
        state[d] = Integer.rotateRight(state[d] ^ state[a], 16);
        state[c] += state[d];
        state[b] = Integer.rotateRight(state[b] ^ state[c], 12);
        state[a] = state[a] + state[b] + my;
        state[d] = Integer.rotateRight(state[d] ^ state[a], 8);
        state[c] += state[d];
        state[b] = Integer.rotateRight(state[b] ^ state[c], 7);
    }

    private static void bytesToWords(byte[] input, int offset, int[] words, int count) {
        for (int i = 0; i < count; i++) {
            int p = offset + i * 4;
            words[i] = (input[p] & 0xff)
                    | ((input[p + 1] & 0xff) << 8)
                    | ((input[p + 2] & 0xff) << 16)
                    | (input[p + 3] << 24);
        }
    }

    private static void partialBytesToWords(byte[] input, int offset, int length, int[] words) {
        for (int i = 0; i < length; i++) {
            words[i >>> 2] |= (input[offset + i] & 0xff) << ((i & 3) * 8);
        }
    }

    private static void wordsToBytes(int[] words, byte[] output, int offset, int length) {
        for (int i = 0; i < length; i++) {
            output[offset + i] = (byte) (words[i >>> 2] >>> ((i & 3) * 8));
        }
    }
}
