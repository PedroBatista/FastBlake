package eu.pedrobatista.fastblake;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TreeApiTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final int BLOCK = 65536;

    private static List<String[]> fixtures() throws Exception {
        try (var stream = TreeApiTest.class.getResourceAsStream("/tree-reference.tsv")) {
            assertNotNull(stream);
            return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .lines().filter(line -> !line.startsWith("#"))
                    .map(line -> line.split("\t")).toList();
        }
    }

    static byte[] input(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 131 + (i >>> 8) * 17 + 29);
        return bytes;
    }

    @Test
    void subtreesMatchOfficialReferenceWithOffsetsAndAbsoluteCounters() throws Exception {
        for (String[] row : fixtures()) {
            if (!row[0].equals("subtree")) continue;
            int length = Integer.parseInt(row[1]);
            long first = Long.parseLong(row[2]);
            byte[] padded = new byte[length + 37];
            Arrays.fill(padded, (byte) 0xa5);
            System.arraycopy(input(length), 0, padded, 13, length);
            byte[] before = padded.clone();
            byte[] expected = HEX.parseHex(row[3]);
            assertArrayEquals(expected, FastBlake.subtreeCv(padded, 13, length, first),
                    "length=" + length + ", first=" + first);
            assertArrayEquals(before, padded);
            Arrays.fill(padded, 0, 13, (byte) 0x31);
            Arrays.fill(padded, 13 + length, padded.length, (byte) 0x74);
            assertArrayEquals(expected, FastBlake.subtreeCv(padded, 13, length, first));
            assertArrayEquals(input(length), Arrays.copyOfRange(before, 13, 13 + length));
            assertArrayEquals(input(length), Arrays.copyOfRange(padded, 13, 13 + length));
        }
    }

    @Test
    void parentsMatchOfficialReferenceAndPreserveInputs() throws Exception {
        for (String[] row : fixtures()) {
            if (!row[0].equals("parent")) continue;
            byte[] left = HEX.parseHex(row[1]);
            byte[] right = row[1].equals(row[2]) ? left : HEX.parseHex(row[2]);
            byte[] beforeLeft = left.clone(), beforeRight = right.clone();
            byte[] cv = FastBlake.parentCv(left, right);
            byte[] root = FastBlake.parentRootHash(left, right);
            assertArrayEquals(HEX.parseHex(row[3]), cv);
            assertArrayEquals(HEX.parseHex(row[4]), root);
            assertFalse(Arrays.equals(cv, root));
            assertNotSame(left, cv);
            assertNotSame(right, root);
            cv[0] ^= 1; root[0] ^= 1;
            assertArrayEquals(beforeLeft, left);
            assertArrayEquals(beforeRight, right);
            assertArrayEquals(HEX.parseHex(row[4]), FastBlake.parentRootHash(left, right));
        }
    }

    @Test
    void reconstructsCompleteInputsFrom64KiBSubtrees() throws Exception {
        for (String[] row : fixtures()) {
            if (!row[0].equals("hash")) continue;
            byte[] bytes = input(Integer.parseInt(row[1]));
            int count = (bytes.length + BLOCK - 1) / BLOCK;
            byte[][] cvs = new byte[count][];
            for (int i = 0; i < count; i++) {
                cvs[i] = FastBlake.subtreeCv(bytes, i * BLOCK,
                        Math.min(BLOCK, bytes.length - i * BLOCK), i * 64L);
            }
            byte[] expected = HEX.parseHex(row[2]);
            assertArrayEquals(expected, combine(cvs, 0, count, true));
            assertArrayEquals(expected, FastBlake.hash(bytes));
        }
    }

    private static byte[] combine(byte[][] cvs, int from, int count, boolean root) {
        if (count == 1) return cvs[from];
        int left = Integer.highestOneBit(count - 1);
        byte[] l = combine(cvs, from, left, false);
        byte[] r = combine(cvs, from + left, count - left, false);
        return root ? FastBlake.parentRootHash(l, r) : FastBlake.parentCv(l, r);
    }

    @Test
    void childOrderAndLeafCountersAffectRoot() {
        byte[] bytes = input(BLOCK + 1);
        byte[] left = FastBlake.subtreeCv(bytes, 0, BLOCK, 0);
        byte[] right = FastBlake.subtreeCv(bytes, BLOCK, 1, 64);
        byte[] root = FastBlake.parentRootHash(left, right);
        assertFalse(Arrays.equals(root, FastBlake.parentRootHash(right, left)));
        assertFalse(Arrays.equals(root, FastBlake.parentRootHash(left,
                FastBlake.subtreeCv(bytes, BLOCK, 1, 65))));
        assertFalse(Arrays.equals(left, FastBlake.hash(Arrays.copyOf(bytes, BLOCK))));
        byte[] second = FastBlake.subtreeCv(bytes, 0, BLOCK, 0);
        assertNotSame(left, second);
        second[0] ^= 1;
        assertArrayEquals(left, FastBlake.subtreeCv(bytes, 0, BLOCK, 0));
    }

    @Test
    void rejectsInvalidArgumentsAndAcceptsLastRepresentableCounter() {
        byte[] bytes = new byte[2048];
        assertThrows(NullPointerException.class, () -> FastBlake.subtreeCv(null, 0, 1, 0));
        for (int[] range : new int[][]{{-1, 1}, {0, -1}, {2048, 1}, {1, 2048},
                {Integer.MAX_VALUE, 2}, {2, Integer.MAX_VALUE}}) {
            assertThrows(IndexOutOfBoundsException.class,
                    () -> FastBlake.subtreeCv(bytes, range[0], range[1], 0));
        }
        assertThrows(IllegalArgumentException.class, () -> FastBlake.subtreeCv(bytes, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> FastBlake.subtreeCv(bytes, 0, 1, -1));
        assertThrows(IllegalArgumentException.class,
                () -> FastBlake.subtreeCv(bytes, 0, 1025, Long.MAX_VALUE));
        assertEquals(32, FastBlake.subtreeCv(bytes, 0, 1024, Long.MAX_VALUE).length);
        assertEquals(32, FastBlake.subtreeCv(bytes, 0, 2048, Long.MAX_VALUE - 1).length);
        for (var operation : List.<java.util.function.BiFunction<byte[], byte[], byte[]>>of(
                FastBlake::parentCv, FastBlake::parentRootHash)) {
            assertThrows(NullPointerException.class, () -> operation.apply(null, new byte[32]));
            assertThrows(NullPointerException.class, () -> operation.apply(new byte[32], null));
            for (int size : new int[]{0, 31, 33, 64}) {
                assertThrows(IllegalArgumentException.class,
                        () -> operation.apply(new byte[size], new byte[32]));
                assertThrows(IllegalArgumentException.class,
                        () -> operation.apply(new byte[32], new byte[size]));
            }
        }
    }

    @Test
    void simultaneousCallsMatchIndependentFixtures() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<Void>> calls = fixtures().stream().filter(row -> row[0].equals("subtree"))
                    .<Callable<Void>>map(row -> () -> {
                        int length = Integer.parseInt(row[1]);
                        assertArrayEquals(HEX.parseHex(row[3]), FastBlake.subtreeCv(input(length),
                                0, length, Long.parseLong(row[2])));
                        return null;
                    }).toList();
            for (var future : pool.invokeAll(calls)) future.get();
        }
    }

    @Test
    void allocationIsBoundedIndependentlyOfLeafCount() {
        // Emulated wider species materialize JVM vector objects; this gate
        // measures production-width allocation, while all widths retain CV tests.
        assumeTrue(KernelSelector.selected() != KernelSelector.Kernel.WIDE
                        || CpuCapabilities.WIDE_VECTOR_BITS <= CpuCapabilities.PREFERRED_VECTOR_BITS,
                "allocation measurement requires a supported vector width");
        var bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        var allocation = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(allocation.isThreadAllocatedMemorySupported());
        allocation.setThreadAllocatedMemoryEnabled(true);
        byte[] bytes = input(8 * 1024 * 1024 + 31);
        for (int i = 0; i < 100; i++) FastBlake.subtreeCv(bytes, 0, BLOCK, 64);
        // Warm the large shape as well to exclude first-use linkage/JIT allocation.
        for (int i = 0; i < 3; i++) FastBlake.subtreeCv(bytes, 0, bytes.length, 64);
        long thread = Thread.currentThread().threadId();
        long before = allocation.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 5; i++) FastBlake.subtreeCv(bytes, 0, bytes.length, 64);
        long large = (allocation.getThreadAllocatedBytes(thread) - before) / 5;
        before = allocation.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 5; i++) FastBlake.subtreeCv(bytes, 0, BLOCK, 64);
        long small = (allocation.getThreadAllocatedBytes(thread) - before) / 5;
        assertTrue(large < 65536, "allocated bytes per call: " + large);
        assertTrue(large <= small + 8192, "allocation grew with input: " + small + " -> " + large);
    }
}
