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
- `native/{test,harness,bench}` — Catch2 units, the ASan/LSan/TSan leak harness, JMH-style
  native benchmarks.
- `example/` — a MobileNetV2 example and JMH benchmarks, consuming the engine via
  `project(":")`.
- `docs/` — design docs, findings, and the observability reference.
- `tools/` — fixture export scripts (`export_add.sh`, etc.) and `fetch-iree-metadata.sh`.
- `buildSrc/` — the `IreeDataTypeCodegen.kt` generator for `IreeDataTypes.java`.

## Commands that actually get run

Not an exhaustive task list — see `CONTRIBUTING.md` for everything else.

```bash
./native/build.sh                  # build the shim, stage it into src/main/resources
./gradlew test                     # JVM suite, against the plain (non-instrumented) library
./native/build_qa.sh               # native Catch2 + leak-harness QA, stages into native/qa/
./gradlew :example:exportModels    # exports mobilenet_v2.vmfb for the example module
```

## Trip-wires

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

## Before claiming done

- `./native/build.sh` compiles clean and `./gradlew test` passes against the plain library.
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
