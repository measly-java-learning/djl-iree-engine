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
  `native/cmake/IreeRuntimePin.cmake`; a tampered hash fails hard at configure time).

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

# TSan gate (measured, not guaranteed by construction — see below):
rm -rf native/build && ./native/build.sh -DIREE_DJL_TSAN=ON
./native/build/iree_leak_harness "" 100
```

`IREE_DJL_SANITIZE` (ASan) and `IREE_DJL_TSAN` are mutually exclusive; enabling both fails
fast at CMake configure time with a clear error rather than a cryptic compiler failure.

**Operational note:** either sanitizer build stages an instrumented `libiree_djl.so` into
the JVM resources directory. That instrumented `.so` breaks `./gradlew test` (e.g. "ASan
runtime does not come first"), because the JVM doesn't preload sanitizer runtimes. After
running a sanitizer gate, **rebuild the plain `.so`** with `./native/build.sh` (no
`-DIREE_DJL_SANITIZE` / `-DIREE_DJL_TSAN`) before running the JVM suite again.

The `iree-runtime-dist` artifact ships `IREE_ENABLE_THREADING=ON` with the `local-task` HAL
driver compiled in, so a TSan-clean result is no longer guaranteed by construction the way it
was when `local-sync` was the only driver linked in. It is, however, empirically true: with the
facade selecting `local-sync` at runtime, TSan ran clean over 100 cycles, `strace -f` recorded
**zero** `clone`/`clone3` syscalls across the whole run, and `/proc/<pid>/status` sampled
mid-invoke read `Threads: 1`. Treat this as a measured property to re-verify if the runtime
driver selection ever changes, not as an invariant of the linked binary.

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
