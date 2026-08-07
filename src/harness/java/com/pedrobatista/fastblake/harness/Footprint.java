package com.pedrobatista.fastblake.harness;

import org.openjdk.jol.info.GraphLayout;

import java.util.List;

/**
 * Deep retained size of one hasher, per contender, at three lifecycle points.
 *
 * <p>Allocation rate — bytes of garbage per operation — has been gated since
 * E010. This measures the other axis: how much memory a <em>live</em> hasher
 * holds. The two are independent. FastBlake's update path is allocation-free at
 * 8 MiB and still retains buffers afterwards, and a caller that pools one hasher
 * per connection pays for the second number, not the first.
 *
 * <h2>What is counted</h2>
 *
 * {@link GraphLayout#parseInstance} walks every object reachable from the
 * hasher, so instance arrays are included with their real padded sizes. Three
 * caveats belong with any figure this prints:
 *
 * <ul>
 *   <li><b>Reachable statics are counted.</b> If an implementation reaches a
 *       shared constant table through an instance field, that table is included
 *       once per hasher even though it is shared. Compare the <em>growth</em>
 *       columns as well as the absolute ones.
 *   <li><b>Native memory is invisible.</b> The Rust contender's hasher is a Java
 *       wrapper around an off-heap allocation; JOL sees only the wrapper. Its
 *       row is a floor, not a total.
 *   <li><b>Sizes depend on the JVM.</b> Compressed oops and heap size change
 *       header and reference widths, so record the flags alongside the numbers.
 * </ul>
 */
public final class Footprint {

    private static final int SMALL = 64;
    private static final int LARGE = 8 * 1024 * 1024;

    private Footprint() {
    }

    public static void main(String[] args) {
        byte[] small = new byte[SMALL];
        byte[] large = new byte[LARGE];
        byte[] out = new byte[32];

        System.out.println("Retained size of one hasher (deep, bytes) — " + vmDetail());
        System.out.println();
        System.out.printf("  %-10s %12s %12s %12s   %s%n",
                "contender", "fresh", "after 64 B", "after 8 MiB", "growth");
        System.out.printf("  %-10s %12s %12s %12s   %s%n",
                "----------", "-----", "----------", "-----------", "------");

        for (Blake3Engine engine : Contenders.available()) {
            long fresh;
            long afterSmall;
            long afterLarge;
            try (Blake3Engine.Hasher hasher = engine.newHasher()) {
                fresh = retained(hasher);
                hasher.update(small, 0, small.length);
                hasher.doFinalize(out, 0, out.length);
                afterSmall = retained(hasher);
                hasher.reset();
                hasher.update(large, 0, large.length);
                hasher.doFinalize(out, 0, out.length);
                afterLarge = retained(hasher);
            }
            System.out.printf("  %-10s %12s %12s %12s   %+d B%n",
                    engine.id(), format(fresh), format(afterSmall), format(afterLarge),
                    afterLarge - fresh);
        }

        System.out.println();
        System.out.println("Notes:");
        System.out.println("  - 'fresh' is a hasher that has done no work.");
        System.out.println("  - 'after 8 MiB' is the steady state a pooled hasher settles at.");
        System.out.println("  - rust is a Java wrapper over off-heap state; its row is a floor.");
        System.out.println("  - one-shot static entry points retain nothing between calls.");
        List<Blake3Engine> unavailable = Contenders.all().stream()
                .filter(e -> !e.isAvailable()).toList();
        if (!unavailable.isEmpty()) {
            System.out.println("  - not measured (unavailable here): "
                    + unavailable.stream().map(Blake3Engine::id).toList());
        }
    }

    private static long retained(Object instance) {
        return GraphLayout.parseInstance(instance).totalSize();
    }

    private static String format(long bytes) {
        if (bytes >= 1024) {
            return String.format("%,d B (%.1f KiB)", bytes, bytes / 1024.0);
        }
        return String.format("%,d B", bytes);
    }

    private static String vmDetail() {
        return System.getProperty("java.vm.name") + " " + System.getProperty("java.version")
                + ", " + System.getProperty("os.arch");
    }
}
