package com.pedrobatista.fastblake.consumer;

import com.pedrobatista.fastblake.FastBlake;

import java.util.HexFormat;

/**
 * Compiled only against the generated FastBlake jar. This is intentionally a
 * plain main program rather than an in-project JUnit test.
 */
public final class LibraryConsumerSmokeTest {
    private static final String EMPTY_HASH =
            "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262";

    private LibraryConsumerSmokeTest() {
    }

    public static void main(String[] args) {
        byte[] digest = FastBlake.hash(new byte[0]);
        String actual = HexFormat.of().formatHex(digest);
        if (!EMPTY_HASH.equals(actual)) {
            throw new AssertionError("empty BLAKE3 mismatch: " + actual);
        }
        byte[] xof = new byte[64];
        FastBlake.initHash().doFinalize(xof);
        if (!java.util.Arrays.equals(digest, java.util.Arrays.copyOf(xof, digest.length))) {
            throw new AssertionError("Commons-compatible doFinalize(byte[]) XOF mismatch");
        }

        byte[] key = new byte[32];
        byte[] keyed = FastBlake.keyedHash(key, new byte[0]);
        byte[] keyedViaInstance = new byte[32];
        FastBlake.initKeyedHash(key).doFinalize(keyedViaInstance);
        if (!java.util.Arrays.equals(keyed, keyedViaInstance)) {
            throw new AssertionError("Commons-compatible keyedHash API mismatch");
        }

        FastBlake hasher = FastBlake.initHash();
        hasher.update(new byte[] {1, 2, 3, 4}).doFinalize(new byte[32], 0, 32);
        hasher.reset();
        if (!EMPTY_HASH.equals(HexFormat.of().formatHex(FastBlake.hash(new byte[0])))) {
            throw new AssertionError("reset/second library call failed");
        }
    }
}
