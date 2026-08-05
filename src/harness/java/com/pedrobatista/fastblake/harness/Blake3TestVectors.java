package com.pedrobatista.fastblake.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The official BLAKE3 test vectors, vendored verbatim from the reference
 * implementation at {@code BLAKE3-team/BLAKE3:test_vectors/test_vectors.json}.
 *
 * <p>Every case covers all three modes (plain hash, keyed hash, key derivation)
 * and asks for 131 bytes of output, which forces the extended-output (XOF) path
 * past the first 64-byte output block.
 *
 * <p>The input for a case of length {@code n} is the byte sequence
 * {@code 0, 1, 2, ..., 250, 0, 1, ...}, i.e. {@code i % 251}.
 */
public final class Blake3TestVectors {

    private static final String RESOURCE = "/blake3/test_vectors.json";

    /** Length, in bytes, of the extended output every vector specifies. */
    public static final int EXTENDED_OUTPUT_LEN = 131;

    /** Length, in bytes, of the default BLAKE3 digest. */
    public static final int DEFAULT_OUTPUT_LEN = 32;

    // The vendored file is a fixed, flat document, so a targeted regex keeps the
    // test tree free of a JSON dependency. Any structural drift fails loudly in
    // load() rather than silently skipping vectors.
    private static final Pattern CASE = Pattern.compile(
            "\"input_len\"\\s*:\\s*(\\d+)\\s*,\\s*"
                    + "\"hash\"\\s*:\\s*\"([0-9a-fA-F]+)\"\\s*,\\s*"
                    + "\"keyed_hash\"\\s*:\\s*\"([0-9a-fA-F]+)\"\\s*,\\s*"
                    + "\"derive_key\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    private static final Vectors VECTORS = load();

    private Blake3TestVectors() {
    }

    /** A single vector: one input length and its expected output in each mode. */
    public record Case(int inputLen, byte[] hash, byte[] keyedHash, byte[] deriveKey) {

        /** The input bytes this case is defined over. */
        public byte[] input() {
            return Blake3TestVectors.input(inputLen);
        }

        @Override
        public String toString() {
            return "input_len=" + inputLen;
        }
    }

    private record Vectors(byte[] key, byte[] context, List<Case> cases) {
    }

    /** The 32-byte key shared by every {@code keyed_hash} vector. */
    public static byte[] key() {
        return VECTORS.key().clone();
    }

    /** The context string shared by every {@code derive_key} vector. */
    public static byte[] context() {
        return VECTORS.context().clone();
    }

    /** All 35 official cases, ordered by input length. */
    public static List<Case> cases() {
        return VECTORS.cases();
    }

    /** Builds the standard test input of {@code len} bytes: {@code i % 251}. */
    public static byte[] input(int len) {
        byte[] in = new byte[len];
        for (int i = 0; i < len; i++) {
            in[i] = (byte) (i % 251);
        }
        return in;
    }

    private static Vectors load() {
        String json = readResource();
        byte[] key = stringField(json, "key").getBytes(StandardCharsets.UTF_8);
        byte[] context = stringField(json, "context_string").getBytes(StandardCharsets.UTF_8);
        if (key.length != 32) {
            throw new IllegalStateException("expected a 32-byte key, got " + key.length);
        }

        HexFormat hex = HexFormat.of();
        List<Case> cases = new ArrayList<>();
        Matcher m = CASE.matcher(json);
        while (m.find()) {
            cases.add(new Case(
                    Integer.parseInt(m.group(1)),
                    hex.parseHex(m.group(2)),
                    hex.parseHex(m.group(3)),
                    hex.parseHex(m.group(4))));
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " yielded no cases; format changed?");
        }
        for (Case c : cases) {
            if (c.hash().length != EXTENDED_OUTPUT_LEN) {
                throw new IllegalStateException(
                        "case " + c + " has a " + c.hash().length + "-byte output, expected "
                                + EXTENDED_OUTPUT_LEN);
            }
        }
        return new Vectors(key, context, List.copyOf(cases));
    }

    private static String stringField(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("missing field \"" + name + "\" in " + RESOURCE);
        }
        return m.group(1);
    }

    private static String readResource() {
        try (InputStream in = Blake3TestVectors.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
