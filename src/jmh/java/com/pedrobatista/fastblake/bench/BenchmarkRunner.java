package com.pedrobatista.fastblake.bench;

import com.pedrobatista.fastblake.harness.Blake3Engine;
import com.pedrobatista.fastblake.harness.Contenders;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Entry point for {@code ./gradlew jmh}.
 *
 * <p>Stands in for {@code org.openjdk.jmh.Main} to do two things it cannot:
 *
 * <ol>
 *   <li><b>Skip absent contenders.</b> JMH has no notion of a parameter value
 *       that does not apply to this machine — naming one that cannot run aborts
 *       the whole run. So the {@code impl} parameter is filled in here from
 *       {@link Contenders#availableIds()}. No Rust toolchain, no GPU, or an
 *       unimplemented contender all just mean one fewer row, never a failure.
 *   <li><b>Report the comparison.</b> JMH prints ns/op per benchmark; what this
 *       project actually wants is throughput per contender side by side, with
 *       the speedup against the Commons Codec baseline.
 * </ol>
 *
 * <p>All ordinary JMH arguments still work and take precedence — including an
 * explicit {@code -p impl=...}, which is validated against what is available so
 * the failure is a clear message rather than a stack trace ten minutes in.
 */
public final class BenchmarkRunner {

    /** The contender every other one is reported relative to. */
    private static final String BASELINE_ID = "commons";

    private static final double NANOS_PER_SECOND = 1e9;
    private static final double BYTES_PER_MIB = 1024 * 1024;

    private BenchmarkRunner() {
    }

    public static void main(String[] args) throws Exception {
        CommandLineOptions cmdLine = new CommandLineOptions(args);
        if (cmdLine.shouldHelp()) {
            cmdLine.showHelp();
            return;
        }

        System.out.println(Contenders.report());
        System.out.println();

        List<String> selected = resolveContenders(cmdLine);
        if (selected == null) {
            System.exit(2);
            return;
        }
        System.out.println("Benchmarking: " + String.join(", ", selected));
        System.out.println();

        // ChainedOptionsBuilder#jvmArgsAppend(...) *replaces* whatever
        // jvmArgsAppend .parent(cmdLine) just copied from the command line
        // instead of adding to it, so a caller-supplied -jvmArgsAppend (e.g.
        // to flip an experimental kernel on) would otherwise be silently
        // dropped. Merge explicitly instead.
        List<String> jvmArgsAppend = new ArrayList<>(cmdLine.getJvmArgsAppend().orElse(List.of()));
        // JMH forks a new JVM, and incubator modules are not inherited
        // reliably from the Gradle JavaExec process.
        jvmArgsAppend.add("--add-modules=jdk.incubator.vector");
        ChainedOptionsBuilder builder = new OptionsBuilder()
                .parent(cmdLine)
                .jvmArgsAppend(jvmArgsAppend.toArray(String[]::new));
        if (!cmdLine.getParameter("impl").hasValue()) {
            builder.param("impl", selected.toArray(String[]::new));
        }

        Collection<RunResult> results = new Runner(builder.build()).run();
        printComparison(results);
    }

    /**
     * Decides which contenders to run: the explicit {@code -p impl=...} if given
     * and every value is runnable here, otherwise everything available.
     *
     * @return the contender ids, or {@code null} if the request cannot be met
     */
    private static List<String> resolveContenders(CommandLineOptions cmdLine) {
        List<String> available = Contenders.availableIds();
        if (available.isEmpty()) {
            System.err.println("No contender can run on this machine; nothing to benchmark.");
            return null;
        }

        var requested = cmdLine.getParameter("impl");
        if (!requested.hasValue()) {
            return available;
        }

        List<String> ids = new ArrayList<>(requested.get());
        List<String> problems = new ArrayList<>();
        for (String id : ids) {
            Blake3Engine engine = Contenders.find(id).orElse(null);
            if (engine == null) {
                problems.add("  " + id + ": unknown contender");
            } else if (!engine.isAvailable()) {
                problems.add("  " + id + ": " + engine.unavailableReason());
            }
        }
        if (!problems.isEmpty()) {
            System.err.println("Cannot benchmark the contenders you asked for:");
            problems.forEach(System.err::println);
            System.err.println("Available here: " + String.join(", ", available));
            return null;
        }
        return ids;
    }

    /**
     * Prints one table per call shape: throughput per contender at each input
     * size, with the speedup over the baseline.
     */
    private static void printComparison(Collection<RunResult> results) {
        if (results.isEmpty()) {
            return;
        }

        // benchmark method -> input size -> contender -> ns/op
        Map<String, Map<Integer, Map<String, Double>>> byShape = new LinkedHashMap<>();
        Set<String> contenders = new LinkedHashSet<>();
        for (RunResult result : results) {
            var params = result.getParams();
            String shape = simpleName(params.getBenchmark());
            String impl = params.getParam("impl");
            String sizeParam = params.getParam("size");
            if (impl == null || sizeParam == null) {
                continue;
            }
            contenders.add(impl);
            byShape.computeIfAbsent(shape, k -> new LinkedHashMap<>())
                    .computeIfAbsent(Integer.parseInt(sizeParam), k -> new LinkedHashMap<>())
                    .put(impl, result.getPrimaryResult().getScore());
        }
        if (byShape.isEmpty()) {
            return;
        }

        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("Throughput by contender (MiB/s; higher is better)");
        boolean hasBaseline = contenders.contains(BASELINE_ID);
        if (hasBaseline) {
            System.out.println("x-factors are relative to the '" + BASELINE_ID + "' baseline.");
        }
        System.out.println("=".repeat(78));

        for (var shapeEntry : byShape.entrySet()) {
            System.out.println();
            System.out.println(shapeEntry.getKey());

            StringBuilder header = new StringBuilder(String.format("  %10s", "size"));
            for (String impl : contenders) {
                header.append(String.format("  %20s", impl));
            }
            System.out.println(header);

            for (var sizeEntry : new TreeSet<>(shapeEntry.getValue().keySet())) {
                Map<String, Double> scores = shapeEntry.getValue().get(sizeEntry);
                StringBuilder row = new StringBuilder(String.format("  %10s", humanSize(sizeEntry)));
                Double baseline = hasBaseline ? scores.get(BASELINE_ID) : null;
                for (String impl : contenders) {
                    Double nanos = scores.get(impl);
                    if (nanos == null || nanos <= 0) {
                        row.append(String.format("  %20s", "-"));
                        continue;
                    }
                    String cell = String.format(Locale.ROOT, "%.0f", throughputMiB(sizeEntry, nanos));
                    if (baseline != null && baseline > 0 && !impl.equals(BASELINE_ID)) {
                        cell += String.format(Locale.ROOT, " (%.2fx)", baseline / nanos);
                    }
                    row.append(String.format("  %20s", cell));
                }
                System.out.println(row);
            }
        }
        System.out.println();
    }

    private static double throughputMiB(int size, double nanosPerOp) {
        return size / nanosPerOp * NANOS_PER_SECOND / BYTES_PER_MIB;
    }

    private static String simpleName(String fullyQualifiedBenchmark) {
        int lastDot = fullyQualifiedBenchmark.lastIndexOf('.');
        return lastDot < 0 ? fullyQualifiedBenchmark : fullyQualifiedBenchmark.substring(lastDot + 1);
    }

    private static String humanSize(int bytes) {
        if (bytes >= 1024 * 1024) {
            return (bytes / (1024 * 1024)) + " MiB";
        }
        if (bytes >= 1024) {
            return (bytes / 1024) + " KiB";
        }
        return bytes + " B";
    }
}
