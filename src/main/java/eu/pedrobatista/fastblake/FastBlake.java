package eu.pedrobatista.fastblake;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
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

    /** The production selector chooses only measured, shipped kernels. */
    private static final int VECTOR_STREAM_CHUNKS = KernelSelector.selected().chunksPerBatch;

    // E028: the preferred-width kernel's batch size is its lane count, which is
    // 4, 8 or 16 depending on the machine, so it cannot be identified by
    // VECTOR_STREAM_CHUNKS the way the fixed-width kernels are. A separate
    // static final flag keeps the fixed-width dispatch below a constant-folded
    // integer compare on machines that do not select it -- which is every
    // machine that does not ask for it by property.
    private static final boolean WIDE_KERNEL =
            KernelSelector.selected() == KernelSelector.Kernel.WIDE;

    /** The kernel this JVM selected, and why. For diagnostics and the harness. */
    public static String selectedKernel() {
        return KernelSelector.describe();
    }
    // E025 P1: chunks retained across update() calls so that fragmented input
    // can still form a complete SIMD batch. E016 introduced this for the
    // four-chunk kernel; the batch size is now whatever the selected kernel
    // consumes, so the eight-chunk kernel gets the same treatment. Zero means
    // no vector kernel is active and update() uses the ordinary scalar path.
    //
    private static final int OUT_LEN = 32;
    private static final int KEY_LEN = 32;
    private static final int BLOCK_LEN = 64;
    private static final int CHUNK_LEN = 1024;
    private static final int MAX_DEPTH = 54;
    private static final VarHandle LITTLE_ENDIAN_INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private static final int CHUNK_START = 1;
    private static final int CHUNK_END = 2;
    private static final int PARENT = 4;
    private static final int ROOT = 8;
    private static final int KEYED_HASH = 16;
    private static final int DERIVE_KEY_CONTEXT = 32;
    private static final int DERIVE_KEY_MATERIAL = 64;

    static final int[] IV_WORDS = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
            0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
    };

    private final int[] key = new int[8];
    private final int modeFlags;
    private final byte[] chunk = new byte[CHUNK_LEN];
    // E016: retain one complete four-chunk SIMD batch across update() calls.
    // A following byte proves every chunk in this buffer is non-final, at
    // which point the whole batch can be committed without changing BLAKE3's
    // rightmost-chunk/ROOT semantics.
    // E025 P0a: allocated on first use, not in the constructor. A 64-byte hash
    // needs none of these, and eagerly allocating them cost 3.4 KB per hasher
    // -- 53 bytes of garbage per input byte at that size, against 0.0007 at
    // 8 MiB. Steady-state large-input behaviour is unchanged: these are
    // allocated exactly once per hasher, on the first call that needs them.
    private byte[] vectorPending;
    private int[] cvStack;

    // Scratch used only by mutating update/reset operations. Finalization owns
    // separate scratch so it cannot consume or perturb the hasher state.
    private final int[] scratchState = new int[16];
    private final int[] scratchWords = new int[16];
    private final int[] scratchCv = new int[8];
    // Sized for the production four- and eight-chunk kernels.
    private int[] vectorPacked;
    private int[] vectorCvs;

    /** The chaining-value stack, allocated when the input first exceeds one chunk. */
    private int[] cvStack() {
        int[] stack = cvStack;
        if (stack == null) {
            stack = cvStack = new int[MAX_DEPTH * 8];
        }
        return stack;
    }

    /** Transposed message scratch, allocated when a vector kernel first runs. */
    private int[] vectorPacked() {
        int[] packed = vectorPacked;
        if (packed == null) {
            // Sized for the selected kernel. The four-chunk kernel shares this
            // scratch as the ladder's lower rung, and every selectable batch is
            // at least four chunks, so the selected kernel's requirement is
            // always the larger of the two. Sizing stays inside this lazy
            // method on purpose: naming a kernel class from a static
            // initialiser would load it, and loading it on a JVM without
            // jdk.incubator.vector is exactly what the scalar fallback exists
            // to avoid.
            packed = vectorPacked = new int[WIDE_KERNEL
                    ? Blake3ChunkVectorWide.packedWordsLength()
                    : Math.max(Blake3ChunkVectorDual.packedWordsLength(),
                            Blake3ChunkVectorScratch.packedWordsLength())];
        }
        return packed;
    }

    /** Per-batch chaining values, allocated when a vector kernel first runs. */
    private int[] vectorCvs() {
        int[] cvs = vectorCvs;
        if (cvs == null) {
            cvs = vectorCvs = new int[WIDE_KERNEL
                    ? Blake3ChunkVectorWide.outputLength()
                    : Math.max(Blake3ChunkVectorDual.outputLength(),
                            Blake3ChunkVectorScratch.outputLength())];
        }
        return cvs;
    }

    /**
     * The buffer holding retained streaming bytes, sized to what is needed.
     *
     * <p>E027 P0d: while at most one chunk has been seen, the existing 1 KiB
     * {@code chunk} array is the buffer. The full batch array is allocated only
     * once input pushes past a chunk, i.e. only once a batch can actually be
     * formed. Before this, a hasher that had done nothing but hash 64 bytes
     * retained 9.4 KiB, because every update routed through the streaming path
     * and touched the 8 KiB batch on first use — E025 P0a's laziness bought
     * nothing for the common small case.
     *
     * <p>{@code chunk} is free to serve this purpose: when a chunk-parallel
     * kernel is active, {@code update} always goes through
     * {@link #updateVectorStream}, so {@code chunkLength} stays zero and the two
     * uses never overlap.
     *
     * @param requiredCapacity bytes that must fit after this call
     */
    private byte[] pendingBuffer(int requiredCapacity) {
        byte[] pending = vectorPending;
        if (pending != null) {
            return pending;
        }
        if (requiredCapacity <= CHUNK_LEN) {
            return chunk;
        }
        pending = vectorPending = new byte[VECTOR_STREAM_CHUNKS * CHUNK_LEN];
        if (vectorPendingLength > 0) {
            System.arraycopy(chunk, 0, pending, 0, vectorPendingLength);
        }
        return pending;
    }

    /** The buffer currently holding retained bytes, without allocating. */
    private byte[] retainedBuffer() {
        return vectorPending != null ? vectorPending : chunk;
    }

    private int chunkLength;
    private int vectorPendingLength;
    private long chunksCompressed;
    private int cvStackLength;

    private FastBlake(int[] initialKey, int flags) {
        System.arraycopy(initialKey, 0, key, 0, 8);
        modeFlags = flags;
    }

    /** Returns a hasher in ordinary hashing mode. */
    public static FastBlake initHash() {
        return new FastBlake(IV_WORDS, 0);
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
        FastBlake contextHasher = new FastBlake(IV_WORDS, DERIVE_KEY_CONTEXT);
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
        return hash(input, 0, input.length, OUT_LEN);
    }

    /**
     * Computes a keyed 32-byte BLAKE3 hash, matching Commons Codec's
     * {@code Blake3.keyedHash(key, data)} convenience method.
     *
     * @param key 32-byte secret key
     * @param input message bytes
     * @return the 32-byte keyed digest
     */
    public static byte[] keyedHash(byte[] key, byte[] input) {
        Objects.requireNonNull(input, "input");
        return initKeyedHash(key).update(input).doFinalize(OUT_LEN);
    }

    /**
     * Computes a one-shot digest of any length over a range of {@code input}.
     *
     * <p>E025 P0b: an input of at most one chunk is its own root node, so it
     * needs no chaining-value stack, no chunk buffer and no tree machinery.
     * That path is taken here directly from the caller's array, with no copy
     * and no hasher instance. Larger inputs use the ordinary incremental path.
     */
    public static byte[] hash(byte[] input, int offset, int length, int outputLength) {
        Objects.requireNonNull(input, "input");
        Objects.checkFromIndexSize(offset, length, input.length);
        if (outputLength < 0) {
            throw new IllegalArgumentException("output length must not be negative");
        }
        if (length <= CHUNK_LEN) {
            return hashSingleChunk(input, offset, length, outputLength);
        }
        return initHash().update(input, offset, length).doFinalize(outputLength);
    }

    /**
     * Hashes at most one chunk straight from the caller's array.
     *
     * <p>Allocates only the three small scratch arrays and the result. No
     * hasher, no 1 KiB chunk copy, no CV stack: at 64 bytes those dominated
     * everything else. Plain hashing mode only, which is what the static
     * one-shot entry points expose.
     */
    private static byte[] hashSingleChunk(byte[] input, int offset, int length,
                                          int outputLength) {
        int[] words = new int[16];
        int[] state = new int[16];
        int[] cv = Arrays.copyOf(IV_WORDS, 8);

        int blocksBeforeLast = length == 0 ? 0 : (length - 1) / BLOCK_LEN;
        for (int block = 0; block < blocksBeforeLast; block++) {
            bytesToWords(input, offset + block * BLOCK_LEN, words, 16);
            compress(cv, words, 0, BLOCK_LEN, block == 0 ? CHUNK_START : 0, state);
            System.arraycopy(state, 0, cv, 0, 8);
        }

        Arrays.fill(words, 0);
        int lastOffset = blocksBeforeLast * BLOCK_LEN;
        int lastLength = length - lastOffset;
        partialBytesToWords(input, offset + lastOffset, lastLength, words);
        int flags = CHUNK_END | (blocksBeforeLast == 0 ? CHUNK_START : 0) | ROOT;

        byte[] output = new byte[outputLength];
        long outputBlockCounter = 0;
        int written = 0;
        while (written < outputLength) {
            compress(cv, words, outputBlockCounter++, lastLength, flags, state);
            int take = Math.min(BLOCK_LEN, outputLength - written);
            wordsToBytes(state, output, written, take);
            written += take;
        }
        return output;
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

        if (VECTOR_STREAM_CHUNKS > 0) {
            return updateVectorStream(input, offset, length);
        }

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

            if (VECTOR_STREAM_CHUNKS == 8 && chunkLength == 0
                    && remaining > 8 * CHUNK_LEN) {
                Blake3ChunkVectorDual.hashChunks(input, position, chunksCompressed,
                        key, modeFlags, vectorPacked(), vectorCvs());
                pushVectorChunkCvs(8);
                position += 8 * CHUNK_LEN;
                remaining -= 8 * CHUNK_LEN;
                continue;
            }

            if (VECTOR_STREAM_CHUNKS == 4 && chunkLength == 0
                    && remaining > 4 * CHUNK_LEN) {
                Blake3ChunkVectorScratch.hashChunks(input, position, chunksCompressed,
                        key, modeFlags, vectorPacked(), vectorCvs());
                pushVectorChunkCvs(4);
                position += 4 * CHUNK_LEN;
                remaining -= 4 * CHUNK_LEN;
                continue;
            }

            int take = Math.min(remaining, CHUNK_LEN - chunkLength);
            System.arraycopy(input, position, chunk, chunkLength, take);
            chunkLength += take;
            position += take;
            remaining -= take;
        }
        return this;
    }

    /** Incremental path that retains one four-chunk batch for the SIMD kernel. */
    /**
     * Streaming update for whichever chunk-parallel kernel is active.
     *
     * <p>At most one complete batch is retained. A following byte proves every
     * chunk in that batch is non-final, at which point it can be committed
     * without disturbing BLAKE3's rightmost-chunk/ROOT semantics. When the
     * buffer is empty and more than a full batch remains, the kernel reads the
     * caller's array directly and the copy is skipped entirely.
     */
    private FastBlake updateVectorStream(byte[] input, int offset, int length) {
        int position = offset;
        int remaining = length;
        int batchBytes = VECTOR_STREAM_CHUNKS * CHUNK_LEN;
        while (remaining > 0) {
            if (vectorPendingLength == batchBytes) {
                hashStreamBatch(retainedBuffer(), 0);
                vectorPendingLength = 0;
            }

            if (vectorPendingLength == 0 && remaining > batchBytes) {
                hashStreamBatch(input, position);
                position += batchBytes;
                remaining -= batchBytes;
                continue;
            }

            // E025 P2, the ladder's second rung. Below one full batch there is
            // still enough for the narrower kernel, and without this rung that
            // tail was compressed one chunk at a time in finalization. At
            // 16 KiB that was 7 of 16 chunks running scalar. Only meaningful
            // when the selected batch is wider than four chunks; the four-chunk
            // kernel is already the rung above.
            if (VECTOR_STREAM_CHUNKS > 4 && vectorPendingLength == 0
                    && remaining > 4 * CHUNK_LEN) {
                Blake3ChunkVectorScratch.hashChunks(input, position, chunksCompressed,
                        key, modeFlags, vectorPacked(), vectorCvs());
                pushVectorChunkCvs(4);
                position += 4 * CHUNK_LEN;
                remaining -= 4 * CHUNK_LEN;
                continue;
            }

            int take = Math.min(remaining, batchBytes - vectorPendingLength);
            System.arraycopy(input, position,
                    pendingBuffer(vectorPendingLength + take), vectorPendingLength, take);
            vectorPendingLength += take;
            position += take;
            remaining -= take;
        }
        return this;
    }

    /** Runs one complete batch through the selected kernel and pushes its CVs. */
    private void hashStreamBatch(byte[] source, int sourceOffset) {
        if (WIDE_KERNEL) {
            Blake3ChunkVectorWide.hashChunks(source, sourceOffset, chunksCompressed,
                    key, modeFlags, vectorPacked(), vectorCvs());
        } else if (VECTOR_STREAM_CHUNKS == 8) {
            Blake3ChunkVectorDual.hashChunks(source, sourceOffset, chunksCompressed,
                    key, modeFlags, vectorPacked(), vectorCvs());
        } else {
            Blake3ChunkVectorScratch.hashChunks(source, sourceOffset, chunksCompressed,
                    key, modeFlags, vectorPacked(), vectorCvs());
        }
        pushVectorChunkCvs(VECTOR_STREAM_CHUNKS);
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
        byte[] finalChunk = chunk;
        int finalChunkLength = chunkLength;
        long finalChunkCounter = chunksCompressed;
        int[] finalStack = cvStack;
        int finalStackLength = cvStackLength;

        // Only when a partial batch is actually retained. vectorPendingLength
        // is zero only for empty input, because a complete batch is committed
        // solely on proof that more input follows.
        if (VECTOR_STREAM_CHUNKS > 0 && vectorPendingLength > 0
                && vectorPendingLength <= CHUNK_LEN) {
            // The whole retained batch is one partial chunk, so it *is* the
            // final chunk: no CVs to push, nothing to copy. This is the common
            // case for small inputs and must not pay for the drain machinery.
            finalChunk = retainedBuffer();
            finalChunkLength = vectorPendingLength;
        } else if (VECTOR_STREAM_CHUNKS > 0 && vectorPendingLength > 0) {
            finalStack = Arrays.copyOf(cvStack(), cvStack().length);
            byte[] pendingChunk = new byte[CHUNK_LEN];
            int chunksBeforeLast = (vectorPendingLength - 1) / CHUNK_LEN;
            int pending = 0;

            // E025 P2b: the ladder, applied to the drain as well as the update
            // loop. Every non-final chunk retained here is provably not the
            // root, so complete groups of four can go through the four-chunk
            // kernel instead of one at a time through the scalar compressor.
            // Under 4 KiB streaming updates the P2 rung never fires -- each
            // update is exactly one batch-fill, so input accumulates here
            // instead -- which is why streaming stayed flat while one-shot
            // gained 21%. This is the path that shape actually takes.
            while (chunksBeforeLast - pending >= 4) {
                Blake3ChunkVectorScratch.hashChunks(retainedBuffer(), pending * CHUNK_LEN,
                        finalChunkCounter, key, modeFlags, vectorPacked(), vectorCvs());
                for (int lane = 0; lane < 4; lane++) {
                    System.arraycopy(vectorCvs(), lane * 8, rightCv, 0, 8);
                    finalChunkCounter++;
                    finalStackLength = pushChunkCv(finalStack, finalStackLength, rightCv,
                            finalChunkCounter, key, modeFlags, words, state);
                }
                pending += 4;
            }

            for (; pending < chunksBeforeLast; pending++) {
                System.arraycopy(retainedBuffer(), pending * CHUNK_LEN,
                        pendingChunk, 0, CHUNK_LEN);
                chunkChainingValue(pendingChunk, CHUNK_LEN, finalChunkCounter,
                        key, modeFlags, rightCv, words, state);
                finalChunkCounter++;
                finalStackLength = pushChunkCv(finalStack, finalStackLength, rightCv,
                        finalChunkCounter, key, modeFlags, words, state);
            }
            int lastOffset = chunksBeforeLast * CHUNK_LEN;
            finalChunkLength = vectorPendingLength - lastOffset;
            if (finalChunkLength > 0) {
                System.arraycopy(retainedBuffer(), lastOffset, pendingChunk, 0, finalChunkLength);
            }
            finalChunk = pendingChunk;
        }

        Output root = chunkOutput(finalChunk, finalChunkLength, finalChunkCounter,
                key, modeFlags, words, state);

        for (int stackIndex = finalStackLength - 1; stackIndex >= 0; stackIndex--) {
            root.chainingValue(rightCv, state);
            int[] parentWords = new int[16];
            System.arraycopy(finalStack, stackIndex * 8, parentWords, 0, 8);
            System.arraycopy(rightCv, 0, parentWords, 8, 8);
            root = new Output(key, parentWords, 0, BLOCK_LEN, modeFlags | PARENT);
        }
        root.rootBytes(output, offset, length, state);
        return this;
    }

    /**
     * Writes an XOF result whose length is {@code output.length}.
     *
     * <p>This overload is source- and behavior-compatible with Commons Codec's
     * {@code Blake3.doFinalize(byte[])} method.
     *
     * @param output destination array; its length selects the XOF output length
     * @return this hasher
     */
    public FastBlake doFinalize(byte[] output) {
        Objects.requireNonNull(output, "output");
        return doFinalize(output, 0, output.length);
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
        int chunkLengthBeforeReset = chunkLength;
        int pendingLengthBeforeReset = vectorPendingLength;
        chunkLength = 0;
        vectorPendingLength = 0;
        chunksCompressed = 0;
        cvStackLength = 0;
        // Retained bytes may live in `chunk` (small inputs) or in the batch
        // array (once promoted); clear whichever prefix was valid.
        int chunkValid = Math.max(chunkLengthBeforeReset,
                vectorPending == null ? pendingLengthBeforeReset : 0);
        Arrays.fill(chunk, 0, Math.min(chunkValid, chunk.length), (byte) 0);
        if (vectorPending != null) {
            Arrays.fill(vectorPending, 0, pendingLengthBeforeReset, (byte) 0);
        }
        return this;
    }

    /** Local-stack counterpart used by repeatable finalization of a pending batch. */
    private static int pushChunkCv(int[] stack, int stackLength, int[] newCv,
                                   long totalChunks, int[] key, int flags,
                                   int[] words, int[] state) {
        long count = totalChunks;
        while ((count & 1) == 0) {
            int leftOffset = --stackLength * 8;
            parentCv(stack, leftOffset, newCv, key, flags, newCv, words, state);
            count >>>= 1;
        }
        System.arraycopy(newCv, 0, stack, stackLength * 8, 8);
        return stackLength + 1;
    }

    private void pushChunkCv(int[] newCv, long totalChunks) {
        System.arraycopy(newCv, 0, scratchCv, 0, 8);
        long count = totalChunks;
        while ((count & 1) == 0) {
            int leftOffset = --cvStackLength * 8;
            parentCv(cvStack(), leftOffset, scratchCv, key, modeFlags,
                    scratchCv, scratchWords, scratchState);
            count >>>= 1;
        }
        System.arraycopy(scratchCv, 0, cvStack(), cvStackLength * 8, 8);
        cvStackLength++;
    }

    /** Pushes one leaf-SIMD result, optionally reducing its aligned subtree first. */
    private void pushVectorChunkCvs(int count) {
        for (int lane = 0; lane < count; lane++) {
            System.arraycopy(vectorCvs(), lane * 8, scratchCv, 0, 8);
            chunksCompressed++;
            pushChunkCv(scratchCv, chunksCompressed);
        }
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
        Blake3ScalarUnrolled.compress(cv, block, counter, blockLength, flags, state);
    }

    private static void bytesToWords(byte[] input, int offset, int[] words, int count) {
        for (int i = 0; i < count; i++) {
            words[i] = (int) LITTLE_ENDIAN_INT.get(input, offset + i * Integer.BYTES);
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
