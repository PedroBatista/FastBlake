package com.pedrobatista.fastblake;

import java.lang.foreign.MemorySegment;
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

    // E002 is retained for controlled follow-up experiments, but its
    // array-based vector state is substantially slower than scalar code. Never
    // enable an experimental kernel by default before the ledger shows a win.
    private static final boolean USE_EXPERIMENTAL_VECTOR =
            Boolean.getBoolean("fastblake.experimental.vector");
    private static final boolean USE_EXPERIMENTAL_BLOCK_VECTOR =
            Boolean.getBoolean("fastblake.experimental.blockVector");
    private static final boolean USE_EXPERIMENTAL_CHUNK_VECTOR4 =
            Boolean.getBoolean("fastblake.experimental.chunkVector4");
    private static final boolean USE_EXPERIMENTAL_LOW_LIVE_VECTOR =
            Boolean.getBoolean("fastblake.experimental.lowLiveVector");
    private static final boolean USE_EXPERIMENTAL_ROUND_CACHE_VECTOR =
            Boolean.getBoolean("fastblake.experimental.roundCacheVector");
    private static final boolean USE_EXPERIMENTAL_HEAP_CHUNK_VECTOR =
            Boolean.getBoolean("fastblake.experimental.heapChunkVector")
                    && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    private static final boolean USE_EXPERIMENTAL_SCRATCH_CHUNK_VECTOR =
            Boolean.getBoolean("fastblake.experimental.scratchChunkVector");
    // E024: two interleaved four-chunk batches, for latency-bound cores.
    private static final boolean USE_EXPERIMENTAL_DUAL_CHUNK_VECTOR =
            Boolean.getBoolean("fastblake.experimental.dualChunkVector");


    /**
     * E025 P3: how many chunks the streaming batch holds, and therefore which
     * chunk-parallel kernel runs.
     *
     * <p>An explicitly set {@code fastblake.experimental.*} property always
     * wins, so every experiment in the ledger stays reproducible exactly as
     * recorded. Only when none is set does {@link KernelSelector} choose from
     * the machine's capabilities.
     */
    private static int resolveStreamChunks() {
        if (USE_EXPERIMENTAL_DUAL_CHUNK_VECTOR) {
            return 8;
        }
        if (USE_EXPERIMENTAL_SCRATCH_CHUNK_VECTOR) {
            return 4;
        }
        // The remaining experimental kernels are driven from the general update
        // loop, not the streaming path. Leave that loop in charge of them.
        if (USE_EXPERIMENTAL_VECTOR || USE_EXPERIMENTAL_BLOCK_VECTOR
                || USE_EXPERIMENTAL_CHUNK_VECTOR4 || USE_EXPERIMENTAL_LOW_LIVE_VECTOR
                || USE_EXPERIMENTAL_ROUND_CACHE_VECTOR || USE_EXPERIMENTAL_HEAP_CHUNK_VECTOR
                || USE_EXPERIMENTAL_WIDE_CHUNK_VECTOR || USE_LEGACY_SCALAR) {
            return 0;
        }
        return KernelSelector.selected().chunksPerBatch;
    }

    /** The kernel this JVM selected, and why. For diagnostics and the harness. */
    public static String selectedKernel() {
        return KernelSelector.describe();
    }
    // E013 is E011's kernel at the machine's preferred vector width. On a
    // 128-bit target the two are the same shape and the same lane count; the
    // property exists so the width change can be measured on its own.
    private static final boolean USE_EXPERIMENTAL_WIDE_CHUNK_VECTOR =
            Boolean.getBoolean("fastblake.experimental.wideChunkVector");
    // E015 reduces each aligned leaf-SIMD batch to one subtree with a
    // lane-parallel parent compressor before touching the scalar CV stack.
    private static final boolean USE_EXPERIMENTAL_PARENT_VECTOR =
            Boolean.getBoolean("fastblake.experimental.parentVector");
    // E009 promoted the named-local compressor. Keep the former loop as a
    // diagnostic control so its baseline remains directly reproducible.
    private static final boolean USE_LEGACY_SCALAR =
            Boolean.getBoolean("fastblake.experimental.legacyScalar");

    // E025 P1: chunks retained across update() calls so that fragmented input
    // can still form a complete SIMD batch. E016 introduced this for the
    // four-chunk kernel; the batch size is now whatever the selected kernel
    // consumes, so the eight-chunk kernel gets the same treatment. Zero means
    // no vector kernel is active and update() uses the ordinary scalar path.
    //
    // MUST be declared after every fastblake.experimental.* flag it reads:
    // static initialisers run in textual order, and an earlier position would
    // silently observe them as false. That would make a forced experimental
    // kernel measure a different kernel than the one requested.
    private static final int VECTOR_STREAM_CHUNKS = resolveStreamChunks();

    /** True when any experimental kernel needing the vector scratch is on. */
    private static final boolean ANY_VECTOR_KERNEL = VECTOR_STREAM_CHUNKS > 0
            || USE_EXPERIMENTAL_VECTOR
            || USE_EXPERIMENTAL_CHUNK_VECTOR4
            || USE_EXPERIMENTAL_LOW_LIVE_VECTOR
            || USE_EXPERIMENTAL_ROUND_CACHE_VECTOR
            || USE_EXPERIMENTAL_HEAP_CHUNK_VECTOR
            || USE_EXPERIMENTAL_SCRATCH_CHUNK_VECTOR
            || USE_EXPERIMENTAL_DUAL_CHUNK_VECTOR
            || USE_EXPERIMENTAL_WIDE_CHUNK_VECTOR;

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
    // Sized for the widest kernel any enabled property can select. Blake3Vector
    // and Blake3ChunkVectorWide both scale with the preferred species, so this
    // already covers the four-lane kernels on a wider machine.
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
            // Sized for the widest kernel any enabled property can select.
            packed = vectorPacked = new int[Math.max(
                    Blake3ChunkVectorDual.packedWordsLength(),
                    Math.max(Blake3Vector.packedWordsLength(),
                            Blake3ChunkVectorWide.packedWordsLength()))];
        }
        return packed;
    }

    /** Per-batch chaining values, allocated when a vector kernel first runs. */
    private int[] vectorCvs() {
        int[] cvs = vectorCvs;
        if (cvs == null) {
            cvs = vectorCvs = new int[Math.max(
                    Blake3ChunkVectorDual.outputLength(),
                    Math.max(Blake3Vector.outputLength(),
                            Blake3ChunkVectorWide.outputLength()))];
        }
        return cvs;
    }

    /** The retained streaming batch, allocated on the first streaming update. */
    private byte[] vectorPending() {
        byte[] pending = vectorPending;
        if (pending == null) {
            pending = vectorPending = new byte[VECTOR_STREAM_CHUNKS * CHUNK_LEN];
        }
        return pending;
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
        MemorySegment inputSegment = USE_EXPERIMENTAL_CHUNK_VECTOR4
                || USE_EXPERIMENTAL_LOW_LIVE_VECTOR
                || USE_EXPERIMENTAL_ROUND_CACHE_VECTOR
                ? MemorySegment.ofArray(input) : null;
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

            if (USE_EXPERIMENTAL_WIDE_CHUNK_VECTOR && chunkLength == 0
                    && remaining > Blake3ChunkVectorWide.LANES * CHUNK_LEN) {
                Blake3ChunkVectorWide.hashChunks(input, position, chunksCompressed,
                        key, modeFlags, vectorPacked(), vectorCvs());
                pushVectorChunkCvs(Blake3ChunkVectorWide.LANES);
                position += Blake3ChunkVectorWide.LANES * CHUNK_LEN;
                remaining -= Blake3ChunkVectorWide.LANES * CHUNK_LEN;
                continue;
            }

            if (USE_EXPERIMENTAL_DUAL_CHUNK_VECTOR && chunkLength == 0
                    && remaining > 8 * CHUNK_LEN) {
                Blake3ChunkVectorDual.hashChunks(input, position, chunksCompressed,
                        key, modeFlags, vectorPacked(), vectorCvs());
                pushVectorChunkCvs(8);
                position += 8 * CHUNK_LEN;
                remaining -= 8 * CHUNK_LEN;
                continue;
            }

            if (USE_EXPERIMENTAL_SCRATCH_CHUNK_VECTOR && chunkLength == 0
                    && remaining > 4 * CHUNK_LEN) {
                Blake3ChunkVectorScratch.hashChunks(input, position, chunksCompressed,
                        key, modeFlags, vectorPacked(), vectorCvs());
                pushVectorChunkCvs(4);
                position += 4 * CHUNK_LEN;
                remaining -= 4 * CHUNK_LEN;
                continue;
            }

            if (USE_EXPERIMENTAL_HEAP_CHUNK_VECTOR && chunkLength == 0
                    && remaining > 4 * CHUNK_LEN) {
                Blake3ChunkVectorHeap.hashChunks(input, position, chunksCompressed,
                        key, modeFlags, vectorPacked(), vectorCvs());
                for (int lane = 0; lane < 4; lane++) {
                    System.arraycopy(vectorCvs(), lane * 8, scratchCv, 0, 8);
                    chunksCompressed++;
                    pushChunkCv(scratchCv, chunksCompressed);
                }
                position += 4 * CHUNK_LEN;
                remaining -= 4 * CHUNK_LEN;
                continue;
            }

            if (USE_EXPERIMENTAL_ROUND_CACHE_VECTOR && chunkLength == 0
                    && remaining > 4 * CHUNK_LEN) {
                Blake3ChunkVectorRoundCache.hashChunks(inputSegment, position,
                        chunksCompressed, key, modeFlags, vectorPacked(), vectorCvs());
                for (int lane = 0; lane < 4; lane++) {
                    System.arraycopy(vectorCvs(), lane * 8, scratchCv, 0, 8);
                    chunksCompressed++;
                    pushChunkCv(scratchCv, chunksCompressed);
                }
                position += 4 * CHUNK_LEN;
                remaining -= 4 * CHUNK_LEN;
                continue;
            }

            if (USE_EXPERIMENTAL_LOW_LIVE_VECTOR && chunkLength == 0
                    && remaining > 4 * CHUNK_LEN) {
                Blake3ChunkVectorLowLive.hashChunks(inputSegment, position,
                        chunksCompressed, key, modeFlags, vectorPacked(), vectorCvs());
                for (int lane = 0; lane < 4; lane++) {
                    System.arraycopy(vectorCvs(), lane * 8, scratchCv, 0, 8);
                    chunksCompressed++;
                    pushChunkCv(scratchCv, chunksCompressed);
                }
                position += 4 * CHUNK_LEN;
                remaining -= 4 * CHUNK_LEN;
                continue;
            }

            if (USE_EXPERIMENTAL_CHUNK_VECTOR4 && chunkLength == 0
                    && remaining > 4 * CHUNK_LEN) {
                Blake3ChunkVector4.hashChunks(inputSegment, position,
                        chunksCompressed, key, modeFlags, vectorPacked(), vectorCvs());
                for (int lane = 0; lane < 4; lane++) {
                    System.arraycopy(vectorCvs(), lane * 8, scratchCv, 0, 8);
                    chunksCompressed++;
                    pushChunkCv(scratchCv, chunksCompressed);
                }
                position += 4 * CHUNK_LEN;
                remaining -= 4 * CHUNK_LEN;
                continue;
            }

            if (USE_EXPERIMENTAL_VECTOR && chunkLength == 0
                    && remaining > Blake3Vector.lanes() * CHUNK_LEN) {
                int vectorBytes = Blake3Vector.lanes() * CHUNK_LEN;
                Blake3Vector.hashChunks(input, position, chunksCompressed, key,
                        modeFlags, vectorPacked(), vectorCvs());
                for (int lane = 0; lane < Blake3Vector.lanes(); lane++) {
                    System.arraycopy(vectorCvs(), lane * 8, scratchCv, 0, 8);
                    chunksCompressed++;
                    pushChunkCv(scratchCv, chunksCompressed);
                }
                position += vectorBytes;
                remaining -= vectorBytes;
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
                hashStreamBatch(vectorPending(), 0);
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
            System.arraycopy(input, position, vectorPending(), vectorPendingLength, take);
            vectorPendingLength += take;
            position += take;
            remaining -= take;
        }
        return this;
    }

    /** Runs one complete batch through the selected kernel and pushes its CVs. */
    private void hashStreamBatch(byte[] source, int sourceOffset) {
        if (VECTOR_STREAM_CHUNKS == 8) {
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
            finalChunk = vectorPending();
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
                Blake3ChunkVectorScratch.hashChunks(vectorPending(), pending * CHUNK_LEN,
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
                System.arraycopy(vectorPending(), pending * CHUNK_LEN,
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
                System.arraycopy(vectorPending(), lastOffset, pendingChunk, 0, finalChunkLength);
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
        Arrays.fill(chunk, 0, chunkLengthBeforeReset, (byte) 0);
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
        long firstChunk = chunksCompressed;
        if (!USE_EXPERIMENTAL_PARENT_VECTOR
                || count < 2
                || (count & (count - 1)) != 0
                || (firstChunk & (count - 1)) != 0
                || count / 2 > Blake3ParentVector.LANES) {
            for (int lane = 0; lane < count; lane++) {
                System.arraycopy(vectorCvs(), lane * 8, scratchCv, 0, 8);
                chunksCompressed++;
                pushChunkCv(scratchCv, chunksCompressed);
            }
            return;
        }

        int active = count;
        while (active > 1) {
            int parents = active >>> 1;
            Blake3ParentVector.compressPairs(vectorCvs, parents, key, modeFlags,
                    vectorPacked);
            active = parents;
        }
        chunksCompressed += count;
        pushSubtreeCv(vectorCvs, chunksCompressed, count);
    }

    /** Merges and pushes one already-reduced power-of-two subtree. */
    private void pushSubtreeCv(int[] newCv, long totalChunks, int subtreeChunks) {
        System.arraycopy(newCv, 0, scratchCv, 0, 8);
        long subtreeCount = totalChunks / subtreeChunks;
        while ((subtreeCount & 1) == 0) {
            int leftOffset = --cvStackLength * 8;
            parentCv(cvStack, leftOffset, scratchCv, key, modeFlags,
                    scratchCv, scratchWords, scratchState);
            subtreeCount >>>= 1;
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
        if (USE_EXPERIMENTAL_BLOCK_VECTOR) {
            Blake3BlockVector.compress(cv, block, counter, blockLength, flags, state);
            return;
        }
        if (!USE_LEGACY_SCALAR) {
            Blake3ScalarUnrolled.compress(cv, block, counter, blockLength, flags, state);
            return;
        }
        System.arraycopy(cv, 0, state, 0, 8);
        System.arraycopy(IV_WORDS, 0, state, 8, 4);
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
