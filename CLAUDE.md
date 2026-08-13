# CLAUDE.md

## What this is

`djl-iree-engine` is a [DJL](https://djl.ai/) engine that runs [IREE](https://iree.dev/)
`.vmfb` models through a statically-linked C++ JNI shim. Start with
[`README.md`](README.md) for what it does and how to use it, and
[`CONTRIBUTING.md`](CONTRIBUTING.md) for how to build, test, and QA it from source.

## Repo map

- `src/` — the Java engine. `src/main/java/org/measly/iree/jni` is the internal JNI
  boundary (`@apiNote` marked, not public API).
- `native/core` — the IREE runtime facade (`iree_runtime.h`/`.cpp`), the ownership/lifetime
  contract layer.
- `native/jni` — the JNI shim (`iree_djl_jni.cpp`) binding `IreeNative` to `native/core`.
- `native/{test,harness,bench}` — Catch2 units, the ASan/LSan/TSan leak harness, and a C++
  copy-cost benchmark.
- `example/` — a MobileNetV2 example and JMH benchmarks, consuming the engine via
  `project(":")`.
- `docs/` — `observability.md` plus dated design records and measurement writeups.
- `tools/` — fixture export scripts (`export_add.sh`, etc.) and `fetch-iree-metadata.sh`.
- `buildSrc/` — the `IreeDataTypeCodegen.kt` generator for `IreeDataTypes.java`.

## Commands that actually get run

Not an exhaustive task list — see `CONTRIBUTING.md` for everything else.

```bash
./native/build.sh                  # build the shim, stage it into src/main/resources
./gradlew test                     # JVM suite, against the plain (non-instrumented) library
./native/build_qa.sh               # native Catch2 + leak-harness QA, stages into native/qa/
./native/ubsan_gate.sh             # UBSan over the JNI shim, driven by the JVM suite
./gradlew :example:exportModels    # exports mobilenet_v2.vmfb for the example module
```

## Trip-wires

- **`./gradlew test` reporting `UP-TO-DATE` has not run anything.** Gradle replays a cached
  result, and the build still prints `BUILD SUCCESSFUL` — so it is easy to report "tests pass"
  on the strength of a run that never happened. Use `--rerun-tasks` when the point is to
  verify, and check for `N actionable tasks: N executed` rather than `up-to-date`.
- **`JAVA_HOME` usually needs setting** before any Gradle or native command (a JDK 17 lives at
  `/usr/lib/jvm/zulu-17-amd64` on this host; check what exists). The native build also fails on
  a stale CMake cache left in the git-ignored `native/build/` — deleting that directory is
  safe.
- **A fresh clone has no native library.** `.gitignore` excludes
  `src/main/resources/native/**/*.so|*.dll|*.json|licenses/`. Anything that resolves the
  engine via `project(":")` — including `example/` — fails in `LibUtils.loadLibrary` with
  "Native library not found on the classpath" until `./native/build.sh` has run once.
- **A sanitizer or QA build leaves an instrumented library staged.** `-DIREE_DJL_SANITIZE=ON`
  / `-DIREE_DJL_TSAN=ON` stage an instrumented `.so` into JVM resources, which breaks
  `./gradlew test` (e.g. "ASan runtime does not come first" — the JVM doesn't preload
  sanitizer runtimes). Rebuild plain with `./native/build.sh` before running JVM tests again.
- **TSan needs `setarch $(uname -m) -R`.** TSan's shadow-memory mapping conflicts with ASLR;
  without disabling it for that one process, the harness dies immediately with `FATAL:
  ThreadSanitizer: unexpected memory mapping`.
- **`local-task` TSan reports are known false positives**, not a new bug — the dist runtime is
  an uninstrumented Release build with no `__tsan` symbols, so TSan cannot see IREE's internal
  synchronization and flags normal worker handoffs as races. Correctness there is covered by
  Catch2 and the JVM suite, not by TSan.
- **Never commit `native/build-clangd/`.** Every entry carries absolute paths — build tree,
  fetched runtime include dir, host JDK, fixture paths. It also goes stale silently: it will
  report phantom compile errors in `native/test/*.cpp` that do not exist in a real build.
  Regenerate with `./native/gen_clangd_db.sh` before trusting clangd diagnostics there.
- **`add.vmfb` is committed.** `iree-compile` (pip) is only needed to regenerate fixtures, not
  to build or test the engine.
- **The pip `iree-base-runtime` wheel is unusable at any version** — no headers, no linkable
  library. The pinned `iree-runtime-dist` tarball (`native/cmake/IreeRuntimePin.cmake`) is the
  only source; that file is the single source of truth for the runtime version.
- **Three platforms ship**, including `windows-x86_64` — do not write "Linux only" anywhere.
- **The native library is resolved through a SHA-256 content-addressed cache**
  (`%LOCALAPPDATA%\iree-djl` on Windows, else `$XDG_CACHE_HOME`, else `~/.cache/iree-djl`), not
  extracted to a temp file. `IREE_LIBRARY_PATH` wins and bypasses extraction entirely.
- **`IreeDataTypes.java` is generated** by `buildSrc/src/main/kotlin/IreeDataTypeCodegen.kt`.
  Editing the generated output directly does nothing — it is overwritten on the next build.
- **`native/ubsan/` must never be staged into `src/main/resources`.** Same hazard as the
  ASan tree, different flag. `./native/ubsan_gate.sh` reaches its instrumented `.so`
  through `IREE_LIBRARY_PATH` instead, so — unlike the ASan and TSan gates — it leaves the
  plain tree alone and needs no rebuild afterwards.
- **A UB hit under `ubsan_gate.sh` is a JVM hard crash, not a test failure.** `-Xmx`-style
  JVM crash output will dominate; the actual finding is the `runtime error:` line and its
  stack trace above it. Do not read the crash as a flaky test.
- **Gradle cannot run in the pinned container.** `JAVA_HOME` there is Corretto 1.8.0_502
  (deliberate: the oldest supported `jni.h`), while the wrapper is Gradle 9.6.1 and
  `build.gradle.kts` sets a JDK 17 toolchain. Native builds go in the container, JVM runs
  never do. `ubsan_gate.sh` splits along that line by itself — `IREE_DJL_UBSAN_MODE=auto`
  builds only when it sees `IREE_DJL_PINNED_IMAGE` — and refuses the JVM phase there rather
  than letting Gradle fail obscurely.
- **GCC has no UBSan ignorelist.** `-fsanitize-ignorelist` and `-fsanitize-blacklist` are
  unrecognized options, and `UBSAN_OPTIONS=suppressions=` does not suppress these checks
  (measured, gcc 13.3). Silencing a diagnostic means
  `__attribute__((no_sanitize("undefined")))` on the function, a per-TU
  `set_source_files_properties` override, or `-fno-sanitize=<check>` — each needs a comment
  naming what was given up, or a check silently leaves the gate.
- **`oomTest` and `stressTest` do not run in CI.** `oomTest` needs pip `iree-compile` for
  its fixture. It is the only reproduction of issue 16's allocation-failure paths, so the
  JNI failure contract is verified only when someone runs the full local sequence.

## Before claiming done

- `./native/build.sh` compiles clean and `./gradlew test` passes against the plain library —
  from a real run, not a cached one (see the first trip-wire).
- `./gradlew javadoc` still reports zero warnings. The published javadoc jar is at zero and
  regressions there are silent, since warnings do not fail the build by design.
- Never commit the clangd database (`native/build-clangd/`), an instrumented library
  (`.so`/`.dll` built with a sanitizer flag), or other large artifacts.

## Code style

- **Java:** javadoc on all public API. The `org.measly.iree.jni` package additionally carries
  `@apiNote` markers noting it is internal, not public API (`build.gradle.kts` registers the
  `apiNote` tag so it renders in generated docs).
- **C++:** plain `//` comments in the style of `native/core/iree_runtime.h`; every function
  carries an ownership and lifetime contract; error handling goes through `iree_status.h`
  conversion.
- **Both:** why-comments over what-comments.
- **Markdown:** no emoji in `README.md`, `CONTRIBUTING.md`, `CLAUDE.md`, or anything under
  `docs/`. Emphasis comes from wording, bold, and structure. Keep counts and pinned versions
  out of prose where a source of truth already exists — they go stale silently.
