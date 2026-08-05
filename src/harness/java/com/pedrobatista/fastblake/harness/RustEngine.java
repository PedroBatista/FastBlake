package com.pedrobatista.fastblake.harness;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The reference Rust BLAKE3, bound through the Foreign Function &amp; Memory API.
 *
 * <p>This is the performance ceiling: hand-written SIMD (NEON on aarch64,
 * SSE/AVX2/AVX-512 on x86) with runtime dispatch. It is single-threaded — the
 * crate's {@code rayon} feature is deliberately off — so it races one core
 * against one Java thread.
 *
 * <p>It is called in-process rather than by shelling out to {@code b3sum},
 * which would measure process startup and file I/O instead of the hash.
 *
 * <h2>Fairness</h2>
 * Downcalls are linked with {@link Linker.Option#critical(boolean)} so heap
 * {@code byte[]} buffers are passed straight through with no copy — the Rust
 * contender hashes the very same array the Java contenders do. The cost is that
 * GC cannot run during a call, which is fine for a benchmark harness and would
 * not be for production code.
 *
 * <h2>Absence is normal</h2>
 * No Rust toolchain, a failed crate build, an unsupported platform, or a JVM
 * without FFM all resolve to an {@link #unavailableReason()} and a skipped
 * contender. Nothing here throws at class-initialisation time.
 */
final class RustEngine implements Blake3Engine {

    static final RustEngine INSTANCE = new RustEngine();

    /** Must match {@code ABI_VERSION} in {@code native/rust-blake3/src/lib.rs}. */
    private static final int EXPECTED_ABI_VERSION = 1;

    private static final String LIBRARY_NAME = "fastblake_rust";

    private RustEngine() {
    }

    @Override
    public String id() {
        return "rust";
    }

    /** The id, so JMH parameters and JUnit display names read cleanly. */
    @Override
    public String toString() {
        return id();
    }

    @Override
    public String displayName() {
        return "Reference Rust blake3 crate (SIMD, single-threaded)";
    }

    @Override
    public String unavailableReason() {
        return Binding.FAILURE;
    }

    @Override
    public Hasher newHasher() {
        return new RustHasher(call(Binding.get().newHasher));
    }

    @Override
    public Hasher newKeyedHasher(byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("BLAKE3 keys are 32 bytes, got " + key.length);
        }
        Binding binding = Binding.get();
        try {
            MemorySegment handle = (MemorySegment) binding.newKeyedHasher
                    .invokeExact(MemorySegment.ofArray(key));
            return new RustHasher(requireNonNull(handle, "fb_hasher_new_keyed"));
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    @Override
    public Hasher newKeyDerivationHasher(byte[] context) {
        Binding binding = Binding.get();
        try {
            MemorySegment handle = (MemorySegment) binding.newDeriveKeyHasher
                    .invokeExact(MemorySegment.ofArray(context), (long) context.length);
            return new RustHasher(requireNonNull(handle, "fb_hasher_new_derive_key"));
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /** Uses the crate's one-shot entry point: one boundary crossing, not four. */
    @Override
    public byte[] hash(byte[] input, int outputLen) {
        byte[] out = new byte[outputLen];
        try {
            Binding.get().hash.invokeExact(
                    MemorySegment.ofArray(input), (long) input.length,
                    MemorySegment.ofArray(out), (long) outputLen);
        } catch (Throwable t) {
            throw wrap(t);
        }
        return out;
    }

    private static MemorySegment call(MethodHandle constructor) {
        try {
            return requireNonNull((MemorySegment) constructor.invokeExact(), "fb_hasher_new");
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    private static MemorySegment requireNonNull(MemorySegment handle, String function) {
        if (handle == null || handle.address() == 0) {
            throw new IllegalStateException(function + " returned null");
        }
        return handle;
    }

    private static RuntimeException wrap(Throwable t) {
        if (t instanceof RuntimeException re) {
            return re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException("native BLAKE3 call failed", t);
    }

    private static final class RustHasher implements Hasher {

        private MemorySegment handle;

        RustHasher(MemorySegment handle) {
            this.handle = handle;
        }

        @Override
        public Hasher update(byte[] input, int off, int len) {
            MemorySegment slice = MemorySegment.ofArray(input).asSlice(off, len);
            try {
                Binding.get().update.invokeExact(active(), slice, (long) len);
            } catch (Throwable t) {
                throw wrap(t);
            }
            return this;
        }

        @Override
        public void doFinalize(byte[] output, int off, int len) {
            MemorySegment slice = MemorySegment.ofArray(output).asSlice(off, len);
            try {
                Binding.get().finalizeXof.invokeExact(active(), slice, (long) len);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        @Override
        public Hasher reset() {
            try {
                Binding.get().reset.invokeExact(active());
            } catch (Throwable t) {
                throw wrap(t);
            }
            return this;
        }

        @Override
        public void close() {
            MemorySegment toFree = handle;
            if (toFree == null) {
                return;
            }
            // Cleared first so a failure in free() cannot leave a double-free
            // waiting on the next close().
            handle = null;
            try {
                Binding.get().free.invokeExact(toFree);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        private MemorySegment active() {
            MemorySegment current = handle;
            if (current == null) {
                throw new IllegalStateException("hasher already closed");
            }
            return current;
        }
    }

    /**
     * Lazily resolved native binding. Any failure is captured as a message in
     * {@link #FAILURE} instead of propagating, so that merely asking whether the
     * Rust contender exists never breaks a build.
     */
    private static final class Binding {

        private static final Binding INSTANCE;
        private static final String FAILURE;

        static {
            Binding binding = null;
            String failure;
            try {
                binding = new Binding();
                failure = null;
            } catch (LinkageError | RuntimeException e) {
                failure = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            INSTANCE = binding;
            FAILURE = failure;
        }

        private final MethodHandle newHasher;
        private final MethodHandle newKeyedHasher;
        private final MethodHandle newDeriveKeyHasher;
        private final MethodHandle update;
        private final MethodHandle finalizeXof;
        private final MethodHandle reset;
        private final MethodHandle free;
        private final MethodHandle hash;

        static Binding get() {
            if (INSTANCE == null) {
                throw new IllegalStateException("Rust contender unavailable: " + FAILURE);
            }
            return INSTANCE;
        }

        private Binding() {
            Path library = locateLibrary();
            Linker linker = Linker.nativeLinker();
            // Arena.global(): the library stays mapped for the life of the JVM,
            // which is exactly the lifetime of a benchmark run.
            SymbolLookup lookup = SymbolLookup.libraryLookup(library, Arena.global());

            ValueLayout.OfLong size = ValueLayout.JAVA_LONG;
            var ptr = ValueLayout.ADDRESS;
            Linker.Option critical = Linker.Option.critical(true);

            MethodHandle abiVersion = link(linker, lookup,
                    "fb_abi_version", FunctionDescriptor.of(ValueLayout.JAVA_INT));
            int actualAbi;
            try {
                actualAbi = (int) abiVersion.invokeExact();
            } catch (Throwable t) {
                throw new IllegalStateException("could not read the native ABI version", t);
            }
            if (actualAbi != EXPECTED_ABI_VERSION) {
                throw new IllegalStateException("native library at " + library + " reports ABI v"
                        + actualAbi + ", this build expects v" + EXPECTED_ABI_VERSION
                        + ". Re-run ./gradlew cargoBuild.");
            }

            newHasher = link(linker, lookup, "fb_hasher_new", FunctionDescriptor.of(ptr));
            newKeyedHasher = link(linker, lookup, "fb_hasher_new_keyed",
                    FunctionDescriptor.of(ptr, ptr), critical);
            newDeriveKeyHasher = link(linker, lookup, "fb_hasher_new_derive_key",
                    FunctionDescriptor.of(ptr, ptr, size), critical);
            update = link(linker, lookup, "fb_hasher_update",
                    FunctionDescriptor.ofVoid(ptr, ptr, size), critical);
            finalizeXof = link(linker, lookup, "fb_hasher_finalize",
                    FunctionDescriptor.ofVoid(ptr, ptr, size), critical);
            reset = link(linker, lookup, "fb_hasher_reset", FunctionDescriptor.ofVoid(ptr));
            free = link(linker, lookup, "fb_hasher_free", FunctionDescriptor.ofVoid(ptr));
            hash = link(linker, lookup, "fb_hash",
                    FunctionDescriptor.ofVoid(ptr, size, ptr, size), critical);
        }

        private static MethodHandle link(Linker linker, SymbolLookup lookup, String symbol,
                                         FunctionDescriptor descriptor, Linker.Option... options) {
            MemorySegment address = lookup.find(symbol).orElseThrow(() -> new IllegalStateException(
                    "symbol " + symbol + " missing from the native library; it is out of date"));
            return linker.downcallHandle(address, descriptor, options);
        }

        /**
         * Finds the staged shared library, or explains precisely what to do
         * about its absence.
         */
        private static Path locateLibrary() {
            String fileName = System.mapLibraryName(LIBRARY_NAME);
            for (Path candidate : searchPath(fileName)) {
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            throw new IllegalStateException(stagedBuildFailure()
                    .orElse("no " + fileName + " found. Run ./gradlew cargoBuild "
                            + "(needs a Rust toolchain: https://rustup.rs)."));
        }

        /**
         * Where to look for the library.
         *
         * <p>When {@code fastblake.native.dir} is set the build is driving us
         * and that directory is the <em>only</em> answer: {@code cargoBuild}
         * clears it whenever the crate fails to compile, and a stale artifact
         * left in the crate's own {@code target/} must not resurrect a
         * contender this build could not produce. Benchmarking native code that
         * does not match the current sources is worse than having no Rust
         * column at all.
         *
         * <p>The crate-relative path is the fallback for running outside Gradle
         * — from an IDE, say — where nothing has staged anything.
         */
        private static Path[] searchPath(String fileName) {
            String staged = System.getProperty("fastblake.native.dir");
            return staged != null
                    ? new Path[] {Path.of(staged, fileName)}
                    : new Path[] {Path.of("native", "rust-blake3", "target", "release", fileName)};
        }

        /** Surfaces the reason cargoBuild recorded, rather than a generic miss. */
        private static Optional<String> stagedBuildFailure() {
            String staged = System.getProperty("fastblake.native.dir");
            if (staged == null) {
                return Optional.empty();
            }
            Path status = Path.of(staged, "rust-status.txt");
            if (!Files.isRegularFile(status)) {
                return Optional.empty();
            }
            try {
                String text = Files.readString(status, StandardCharsets.UTF_8).strip();
                if (!text.startsWith("unavailable")) {
                    return Optional.empty();
                }
                String detail = text.substring("unavailable".length()).strip();
                return Optional.of(detail.isEmpty() ? "cargoBuild reported it unavailable" : detail);
            } catch (IOException e) {
                return Optional.empty();
            }
        }
    }
}
