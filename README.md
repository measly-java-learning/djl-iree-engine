# djl-iree-engine

A [DJL](https://djl.ai/) engine that runs [IREE](https://iree.dev/) `.vmfb` models.

**Status: walking skeleton.** This exists to answer whether IREE works as a DJL engine and
at what cost. It runs a trivial `add` model end to end and answers the go/no-go question in
`docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md` (verdict: **GO**). It is
not a product — see the deferred list in the design doc and the findings doc. Linux-x86_64
only.

## Prerequisites

The engine consumes the published `iree-runtime-dist` v3.11.0-3 artifact — a hash-pinned tarball
of 198 static archives, fetched and verified by CMake at configure time. There is **no IREE
source tree, no IREE build tree, and no compiler required** to build or test this engine:

- JDK 17 (e.g. `/usr/lib/jvm/zulu-17-amd64`) — set `JAVA_HOME` to it.
- CMake, Ninja, and a C++20 (gcc/clang) compiler.
- Network access, to fetch the pinned `iree-runtime-dist` tarball (SHA256-verified against
  `native/cmake/IreeRuntimePin.cmake`; a tampered hash fails hard at configure time). The
  native *test* build additionally fetches Catch2 (v3.5.2) via `FetchContent`'s
  `GIT_REPOSITORY`/`GIT_TAG` (unpinned by hash) — this needs `git` on `PATH` and network
  access to GitHub as a second host.

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

The JVM suite is 5 tests: `IreeNativeTest` (×4) and `AddModelIT` (×1), which runs `add.vmfb`
through a real DJL `Predictor` and checks the output `[11, 22, 33, 44]`.

## Native QA

```bash
./native/build/iree_runtime_test                      # Catch2 units (9 cases)

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

## Threading

`IreeSymbolBlock.forward()` is not thread-safe on the same model. Use one
`Model`/`Predictor` per thread, and never close a model with a forward in flight.

## Docs

- Design: `docs/superpowers/specs/2026-07-19-djl-iree-engine-skeleton-design.md`
- Findings (the go/no-go writeup): `docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md`
- Plan: `docs/superpowers/plans/2026-07-19-djl-iree-engine-skeleton.md`
- Wishlist for the dist project, with delivered/open status:
  `docs/superpowers/specs/iree-runtime-dist-wishlist.md`
- `iree-runtime-dist` handover (what the artifact actually ships):
  `docs/2026-07-20-djl-iree-engine-handover.md`
- Usability report on the dist artifact, with filed issues and verdict:
  `docs/2026-07-20-iree-runtime-dist-usability-report.md`
