# Contributing to djl-iree-engine

This file covers everything needed to build, test, and QA the engine from source. If you only
want to *use* it, [`README.md`](README.md) is the whole story — none of the prerequisites below
apply at runtime.

## Prerequisites

The engine links against the published `iree-runtime-dist` artifact pinned in
`native/cmake/IreeRuntimePin.cmake` — a hash-pinned tarball of 198 static archives, fetched and
verified by CMake at configure time. **Nothing here builds IREE itself**: there is no IREE
source tree, no IREE build tree, and `iree-compile` is not needed (see below for the one
exception). You do need an ordinary C++ toolchain, for the JNI shim in `native/`:

- JDK 17 (e.g. `/usr/lib/jvm/zulu-17-amd64`) — set `JAVA_HOME` to it.
- CMake, Ninja, and a C++20 (gcc/clang) compiler.
- One-time prerequisite — `bash tools/fetch-iree-metadata.sh` (requires the `gh` CLI —
  https://cli.github.com/ — installed and authenticated). It derives the release from
  `native/cmake/IreeRuntimePin.cmake` (the single source of truth) and feeds
  `generateIreeDataTypes`.
- Network access, to fetch the pinned `iree-runtime-dist` tarball (SHA256-verified against
  `native/cmake/IreeRuntimePin.cmake`; a tampered hash fails hard at configure time). The
  native *test* build additionally fetches Catch2 as a SHA256-pinned tarball — this
  needs network access to GitHub as a second host, but no `git`. The shipping build
  (`native/build.sh`, which defaults to `-DIREE_DJL_BUILD_TESTS=OFF`) does not fetch Catch2
  at all.

`iree-compile` from pip is needed **only** if you want to regenerate the test fixture
(`add.vmfb`), which is otherwise committed:
`uv pip install iree-base-compiler==3.11.0`. This is the version paired with the dist's linked
runtime (`e4a3b040`, stable tag `v3.11.0`) per its `manifest.json` — no more nightly-chasing. The
pip `iree-base-runtime` wheel is still not usable at any version; it ships no headers and no
linkable library, which is exactly why the dist artifact exists.

## Build and test

```bash
./tools/export_add.sh    # regenerate add.vmfb (optional; it is committed)
./native/build.sh        # build the shim and stage it into resources
JAVA_HOME=/usr/lib/jvm/zulu-17-amd64 ./gradlew test    # JVM tests
```

The JVM suite lives under `src/test/java/org/measly/iree/`. If you are orienting yourself, the
core functional path is `IreeNativeTest` (JNI boundary, including the scale/scale2
parameter-archive loads), `AddModelIT` (the implicit bare-`.vmfb` door), `ModelManifestTest`
(schema rules), `ModelResolverTest` (front doors + containment), and `ScaleModelIT` (manifest
directory end to end → `[2, 4, 6, 8]`). The rest cover observability, the JNI edge cases, and
leak stress.

## Container build

`native/local_build_wrapper.sh` runs a `native/` script inside the pinned per-platform
toolchain image and is the **blessed** way to build the shipped library: the toolchain matches
CI's, and a library built there keeps its glibc 2.28 (RHEL 8) floor. Running the `native/`
scripts directly on the host works but breaks that floor.

```bash
./native/local_build_wrapper.sh                        # defaults to native/build.sh
./native/local_build_wrapper.sh native/build_qa.sh
ITERS=2000 ./native/local_build_wrapper.sh native/bench.sh
```

The wrapper picks the image from `uname -m` (`docker/linux-x86_64.Dockerfile` or
`docker/linux-aarch64.Dockerfile`) and builds it first; Docker's layer cache makes that a
near-instant no-op after the first run. The images carry the Corretto 8 JNI headers, so the
shipped library is always compiled against the JDK 8 floor whatever the host has. The same
Dockerfiles back the CI matrix (`.github/workflows/warm-build-image.yml`). `build.sh` and
`build_qa.sh` chown their outputs back to you on exit; other `native/` scripts run through the
wrapper do not yet, and leave root-owned directories behind.

Windows x86_64 is built in CI only (`build-iree-shim-windows` in
`.github/workflows/native-build-job.yml`, on `windows-2022`, under an MSVC dev shell); there is
no container path for it.

## Editor setup (clangd)

`.clangd` points at `native/build-clangd`, a compile database that no build script touches.
Generate or refresh it with:

```bash
./native/gen_clangd_db.sh
```

The script runs one CMake configure (no compilation) into `native/build-clangd`, which is
needed before clangd can resolve IREE and JNI headers. A dedicated tree is used because the
blessed build path (`native/local_build_wrapper.sh`) runs in the manylinux container, where the
repo sits at `/workspace` — a database shared with the shipping tree would flip between host
paths and container-absolute paths that host clangd cannot resolve. Four things worth knowing:

- **A JDK is required on the host.** `native/CMakeLists.txt` calls `find_package(JNI REQUIRED)`,
  and a failure is fatal — you get *no* database at all, not just a missing entry for the JNI
  shim. The script honors `JAVA_HOME` if set, otherwise derives it from `java` on PATH or
  `/usr/lib/jvm`, and fails loudly if none exists. This affects the editor only; the shipped
  `.so` is always built against the Corretto 8 headers baked into the pinned build image
  (`docker/<platform>.Dockerfile`), whatever your host has.
- **Configure hits the network**, on the same terms as any build: the SHA256-pinned
  `iree-runtime-dist` tarball plus a SHA256-pinned Catch2 tarball from GitHub (no `git`
  required for either).
- **Sanitizer gates don't touch this database.** `native/qa` is a separate tree, so
  `-DIREE_DJL_SANITIZE=ON` / `-DIREE_DJL_TSAN=ON` builds never disturb `native/build-clangd`
  and `jni/iree_djl_jni.cpp` stays indexed.
- **Never commit the database.** Every entry carries absolute paths — the build tree, the
  fetched runtime's include dir, the host JDK, and the `.vmfb`/`.irpa` fixture paths passed as
  `-D` macro values. `native/build-clangd/` is ignored in `.gitignore`.

Re-run the script after bumping `native/cmake/IreeRuntimePin.cmake` or changing the compile
flags in `native/CMakeLists.txt`; the database is refreshed only by that script, so a stale one
keeps resolving against the previous runtime's headers, silently and with no warning.

Headers (`iree_runtime.h`, `iree_handles.h`, `iree_status.h`) never appear in the database —
clangd infers their flags from `core/iree_runtime.cpp`, which includes all three.

## Native QA

```bash
# Catch2 units: iree_runtime_test and iree_params_test. native/build.sh defaults to
# -DIREE_DJL_BUILD_TESTS=OFF — the shipping build stages only the .so, so it does not fetch
# or compile Catch2. Opt back in to get the test binaries in native/build, or just run
# ./native/build_qa.sh, which builds them either way (into native/qa instead).
./native/build.sh -DIREE_DJL_BUILD_TESTS=ON
./native/build/iree_runtime_test

# ASan/LSan sanitizer gate (this is the go/no-go checkpoint):
rm -rf native/build && ./native/build.sh -DIREE_DJL_SANITIZE=ON
ASAN_OPTIONS=detect_leaks=1 ./native/build/iree_leak_harness "" 200
ASAN_OPTIONS=detect_leaks=1 ./native/build/iree_leak_harness "" 400

# TSan over local-sync (single-threaded; clean, measured — see below):
rm -rf native/build && ./native/build.sh -DIREE_DJL_TSAN=ON
setarch $(uname -m) -R ./native/build/iree_leak_harness "" 100 local-sync

# TSan over local-task (worker pool). BLOCKED — currently false positives, see below:
./native/tsan_gate.sh
```

The TSan invocation needs `setarch $(uname -m) -R` (disabling ASLR for that one process):
TSan's shadow-memory mapping conflicts with ASLR, and on a host with ASLR enabled (the
default, `/proc/sys/kernel/randomize_va_space` = 2) it dies immediately with `FATAL:
ThreadSanitizer: unexpected memory mapping` without it.

`IREE_DJL_SANITIZE` (ASan) and `IREE_DJL_TSAN` are mutually exclusive; enabling both fails
fast at CMake configure time with a clear error rather than a cryptic compiler failure.

**Operational note:** either sanitizer build stages an instrumented `libiree_djl.so` into
the JVM resources directory. That instrumented `.so` breaks `./gradlew test` (e.g. "ASan
runtime does not come first"), because the JVM doesn't preload sanitizer runtimes. After
running a sanitizer gate, **rebuild the plain `.so`** with `./native/build.sh` (no
`-DIREE_DJL_SANITIZE` / `-DIREE_DJL_TSAN`) before running the JVM suite again.

The `iree-runtime-dist` artifact ships `IREE_ENABLE_THREADING=ON` with the `local-task` HAL
driver compiled in, so TSan behavior depends on which driver the harness selects (argv[3],
default `local-sync`):

- **`local-sync` (default): TSan clean, measured.** With the facade selecting `local-sync`,
  TSan ran clean over 100 cycles, `strace -f` recorded **zero** `clone`/`clone3` syscalls, and
  `/proc/<pid>/status` read `Threads: 1` mid-invoke. Treat this as a measured property to
  re-verify if driver selection changes, not as an invariant of the linked binary.
- **`local-task` (worker pool): TSan is BLOCKED on false positives.** `./native/tsan_gate.sh`
  drives `local-task` and reported data races on the first observed iteration in every run to
  date, but they are false positives — that is a measured result, not a construction guarantee. The dist `default` runtime is an uninstrumented Release build (`BUILDINFO`:
  `variant=default`; no `__tsan` symbols), and TSan requires whole-program instrumentation to
  observe a library's synchronization — so it cannot see IREE's atomics / task-executor
  semaphores and flags the normal main↔worker submit/execute and refcounted-free handoffs as
  races. The harness completes correctly (right results, no crash) every run. This becomes a
  real race gate only with a TSan-instrumented runtime variant
  ([iree-runtime-dist#9](https://github.com/measly-java-learning/iree-runtime-dist/issues/9));
  until then `local-task` is covered for correctness by the Catch2 and JVM tests, not for races.

On Windows there is no ASan/LSan harness and no TSan gate. CI runs the Catch2 units via
`native/build_qa.sh` and asserts the static-CRT link with
`native/tests/check_windows_crt.sh` before the DLL is uploaded.

## Regenerating `add.vmfb`

`src/test/resources/models/add.vmfb` is committed, so this is only needed when the fixture
itself changes:

```bash
uv pip install iree-base-compiler==3.11.0
./tools/export_add.sh
```

## Design records

`docs/` holds the measurement writeups and investigation notes behind the decisions in this
engine — the zero-copy and staging measurements, the IRPA spike, the `iree-runtime-dist`
handover and usability report. `docs/superpowers/` holds the design and plan documents for
each chunk of work.

These are dated working records, not maintained documentation: they say what was true and
what was decided at the time. Where one disagrees with the code, the code wins. Reach for
them to answer "why is it like this", not "how do I use this" — for that, see the
[README](README.md) and [`docs/observability.md`](docs/observability.md).
