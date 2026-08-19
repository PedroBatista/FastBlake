package eu.pedrobatista.fastblake;

import eu.pedrobatista.fastblake.harness.Blake3Engine;
import eu.pedrobatista.fastblake.harness.Contenders;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Standalone benchmark entry point packaged by the {@code testJar} task.
 *
 * <p>It accepts ordinary JMH command-line options, plus
 * {@code --json-output-dir DIR} or {@code --json-output FILE}. The JMH console
 * output remains visible while a machine-readable report is written after the
 * run. No JSON library is needed in the test artifact: the report contains
 * only strings, numbers, booleans and arrays emitted by this class.
 */
public final class TestBenchmarkMain {

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final double BYTES_PER_MIB = 1024.0 * 1024.0;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private TestBenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        ParsedArguments parsed = ParsedArguments.parse(args);
        CommandLineOptions commandLine = new CommandLineOptions(parsed.jmhArgs.toArray(String[]::new));
        if (commandLine.shouldHelp()) {
            commandLine.showHelp();
            System.out.println("\nFastBlake options:\n"
                    + "  --json-output-dir DIR  write a timestamped JSON report there\n"
                    + "  --json-output FILE     write the report to this exact path");
            return;
        }

        List<String> selected = resolveContenders(commandLine);
        if (selected == null) {
            System.exit(2);
            return;
        }

        List<String> forkArgs = new ArrayList<>(commandLine.getJvmArgsAppend().orElse(List.of()));
        forkArgs.add("--add-modules=jdk.incubator.vector");
        ChainedOptionsBuilder builder = new OptionsBuilder()
                .parent(commandLine)
                .jvmArgsAppend(forkArgs.toArray(String[]::new));
        if (!commandLine.getParameter("impl").hasValue()) {
            builder.param("impl", selected.toArray(String[]::new));
        }

        System.out.println("FastBlake capabilities: " + CpuCapabilities.describe());
        System.out.println("Benchmarking: " + String.join(", ", selected));
        Collection<RunResult> results = new Runner(builder.build()).run();

        Path output = parsed.output.resolveOutput();
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, reportJson(results, parsed.jmhArgs), StandardCharsets.UTF_8);
        System.out.println("JSON report: " + output.toAbsolutePath());
    }

    private static List<String> resolveContenders(CommandLineOptions commandLine) {
        List<String> available = Contenders.availableIds();
        if (available.isEmpty()) {
            System.err.println("No contender can run on this machine; nothing to benchmark.");
            return null;
        }
        var requested = commandLine.getParameter("impl");
        if (!requested.hasValue()) {
            return available;
        }
        List<String> selected = new ArrayList<>(requested.get());
        List<String> problems = new ArrayList<>();
        for (String id : selected) {
            Blake3Engine engine = Contenders.find(id).orElse(null);
            if (engine == null) {
                problems.add("  " + id + ": unknown contender");
            } else if (!engine.isAvailable()) {
                problems.add("  " + id + ": " + engine.unavailableReason());
            }
        }
        if (!problems.isEmpty()) {
            System.err.println("Cannot benchmark the requested contenders:");
            problems.forEach(System.err::println);
            System.err.println("Available here: " + String.join(", ", available));
            return null;
        }
        return selected;
    }

    private static String reportJson(Collection<RunResult> results, List<String> jmhArgs) {
        StringBuilder json = new StringBuilder(16_384);
        json.append('{');
        field(json, "generatedAt", Instant.now().toString()).append(',');
        field(json, "architecture", CpuCapabilities.ARCH).append(',');
        field(json, "effectiveIsa", CpuCapabilities.effectiveIsa()).append(',');
        field(json, "vectorApiAvailable", CpuCapabilities.PREFERRED_VECTOR_BITS != 0).append(',');
        field(json, "preferredVectorBits", CpuCapabilities.PREFERRED_VECTOR_BITS).append(',');
        field(json, "vectorRegisters", CpuCapabilities.VECTOR_REGISTERS).append(',');
        field(json, "avx2Effective", CpuCapabilities.IS_X86_64
                && CpuCapabilities.PREFERRED_VECTOR_BITS >= 256).append(',');
        field(json, "avx512Effective", CpuCapabilities.HAS_NATIVE_AVX512).append(',');
        field(json, "selectedKernel", FastBlake.selectedKernel()).append(',');
        json.append("\"jmhArgs\":");
        array(json, jmhArgs);
        json.append(",\"results\":[");
        boolean first = true;
        for (RunResult run : results) {
            if (!first) {
                json.append(',');
            }
            first = false;
            Result result = run.getPrimaryResult();
            json.append('{');
            field(json, "benchmark", run.getParams().getBenchmark()).append(',');
            json.append("\"parameters\":{");
            boolean firstParam = true;
            for (Object key : run.getParams().getParamsKeys()) {
                if (!firstParam) {
                    json.append(',');
                }
                firstParam = false;
                field(json, key.toString(), run.getParams().getParam(key.toString()));
            }
            json.append("},");
            field(json, "score", result.getScore()).append(',');
            field(json, "scoreUnit", result.getScoreUnit()).append(',');
            field(json, "scoreError", finiteOrNull(result.getScoreError())).append(',');
            field(json, "samples", result.getSampleCount());
            String impl = run.getParams().getParam("impl");
            String size = run.getParams().getParam("size");
            if (impl != null) {
                json.append(',');
                field(json, "implementation", impl);
            }
            if (size != null && result.getScore() > 0) {
                json.append(',');
                field(json, "throughputMiBPerSecond",
                        Integer.parseInt(size) / result.getScore() * NANOS_PER_SECOND / BYTES_PER_MIB);
            }
            json.append('}');
        }
        json.append("]}\n");
        return json.toString();
    }

    private static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static StringBuilder field(StringBuilder json, String name, Object value) {
        json.append('"').append(escape(name)).append("\":");
        if (value == null) {
            return json.append("null");
        }
        if (value instanceof Number || value instanceof Boolean) {
            return json.append(value);
        }
        return json.append('"').append(escape(value.toString())).append('"');
    }

    private static void array(StringBuilder json, List<String> values) {
        json.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(escape(values.get(i))).append('"');
        }
        json.append(']');
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private record ParsedArguments(List<String> jmhArgs, Output output) {
        static ParsedArguments parse(String[] args) {
            List<String> jmh = new ArrayList<>();
            Output output = Output.defaultOutput();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--json-output-dir")) {
                    output = Output.directory(Path.of(requireValue(args, ++i, arg)));
                } else if (arg.startsWith("--json-output-dir=")) {
                    output = Output.directory(Path.of(arg.substring(arg.indexOf('=') + 1)));
                } else if (arg.equals("--json-output")) {
                    output = Output.exact(Path.of(requireValue(args, ++i, arg)));
                } else if (arg.startsWith("--json-output=")) {
                    output = Output.exact(Path.of(arg.substring(arg.indexOf('=') + 1)));
                } else if (arg.equals("--jdk")) {
                    // The Windows launcher cannot portably remove an arbitrary
                    // quoted argument after SHIFT. It validates --jdk itself
                    // and leaves this launcher-only pair for us to discard.
                    requireValue(args, ++i, arg);
                } else if (arg.startsWith("--jdk=")) {
                    // Same option in the form that is convenient for paths
                    // containing spaces on Windows.
                } else {
                    jmh.add(arg);
                }
            }
            return new ParsedArguments(List.copyOf(jmh), output);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }

    private record Output(Path path, boolean exact) {
        static Output defaultOutput() {
            return directory(Path.of("."));
        }

        static Output directory(Path path) {
            return new Output(path, false);
        }

        static Output exact(Path path) {
            return new Output(path, true);
        }

        Path resolveOutput() throws IOException {
            if (exact) {
                return path;
            }
            return path.resolve("fastblake-benchmark-" + FILE_TIMESTAMP.format(Instant.now()) + ".json");
        }
    }
}
