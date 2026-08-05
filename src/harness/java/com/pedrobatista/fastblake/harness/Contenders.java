package com.pedrobatista.fastblake.harness;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The registry of BLAKE3 implementations under comparison.
 *
 * <p>Declaration order is the reporting order, chosen to read as a ladder:
 * the scalar Java baseline, the native ceiling, then the two contenders this
 * project is building towards it.
 *
 * <p>Run {@code ./gradlew contenders} to print what this machine can actually
 * execute, and why anything is out.
 */
public final class Contenders {

    private static final List<Blake3Engine> ALL = List.of(
            CommonsCodecEngine.INSTANCE,
            RustEngine.INSTANCE,
            JavaCpuEngine.INSTANCE,
            JavaGpuEngine.INSTANCE);

    private Contenders() {
    }

    /** Every registered contender, available or not, in reporting order. */
    public static List<Blake3Engine> all() {
        return ALL;
    }

    /**
     * The contenders that can run here. Callers iterate this; the ones that
     * cannot run are skipped, never failed.
     */
    public static List<Blake3Engine> available() {
        return ALL.stream().filter(Blake3Engine::isAvailable).toList();
    }

    /** The ids of {@link #available()}, for wiring into JMH's {@code impl} parameter. */
    public static List<String> availableIds() {
        return available().stream().map(Blake3Engine::id).toList();
    }

    public static Optional<Blake3Engine> find(String id) {
        return ALL.stream().filter(engine -> engine.id().equals(id)).findFirst();
    }

    /** Looks up a contender by id, failing loudly with the valid ids listed. */
    public static Blake3Engine require(String id) {
        return find(id).orElseThrow(() -> new NoSuchElementException(
                "unknown contender '" + id + "'; known ids: "
                        + ALL.stream().map(Blake3Engine::id).toList()));
    }

    /** A short availability report, one line per contender. */
    public static String report() {
        StringBuilder sb = new StringBuilder("BLAKE3 contenders\n");
        for (Blake3Engine engine : ALL) {
            String reason = engine.unavailableReason();
            sb.append("  [").append(reason == null ? "x" : " ").append("] ")
                    .append(String.format("%-9s", engine.id()))
                    .append(engine.displayName());
            if (reason != null) {
                sb.append("\n").append("            skipped: ").append(reason.lines().findFirst().orElse(""));
            }
            sb.append('\n');
        }
        long ready = available().size();
        sb.append("  ").append(ready).append(" of ").append(ALL.size()).append(" available.");
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println(report());
    }
}
