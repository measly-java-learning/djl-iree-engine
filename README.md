# djl-iree-engine

A [DJL](https://djl.ai/) engine that runs [IREE](https://iree.dev/) `.vmfb` models.

**Status: walking skeleton.** This exists to answer whether IREE works as a DJL engine and
at what cost. It runs a trivial `add` model end to end and answers the go/no-go question in
`docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md` (verdict: **GO**). It is
not a product — see the deferred list in the design doc and the findings doc. Linux-x86_64
only.

## Prerequisites

- An IREE build tree (default `/home/corey/workspace/iree-build`) and matching source tree
  (default `/home/corey/workspace/iree`). Override with the `IREE_INSTALL` and
  `IREE_SOURCE` environment variables. The linked runtime must match the compiler used to
  produce any `.vmfb` you load — see "Version alignment" in the findings doc.
- `iree-compile` from pip, **only** if you need to regenerate the test model:
  `uv pip install iree-base-compiler==3.12.0rc20260717`. This exact version is required —
  the stable `3.11.0` release emits a `.vmfb` whose ABI mismatches the local runtime build
  and fails to load. The pip `iree-base-runtime` wheel is not usable at any version; it
  ships no headers and no linkable library.
- JDK 17 (e.g. `/usr/lib/jvm/zulu-17-amd64`) — set `JAVA_HOME` to it.
- CMake, Ninja, and a C++20 compiler.

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
./native/build/iree_runtime_test                      # Catch2 units (8 cases)

# Sanitizer gate (this is the go/no-go checkpoint):
rm -rf native/build && ./native/build.sh -DIREE_DJL_SANITIZE=ON
ASAN_OPTIONS=detect_leaks=1 ./native/build/iree_leak_harness "" 200
ASAN_OPTIONS=detect_leaks=1 ./native/build/iree_leak_harness "" 400
```

**Operational note:** the sanitizer build stages an ASan-instrumented `libiree_djl.so` into
the JVM resources directory. That instrumented `.so` breaks `./gradlew test` with "ASan
runtime does not come first," because the JVM doesn't preload ASan. After running the
sanitizer gate, **rebuild the plain `.so`** with `./native/build.sh` (no
`-DIREE_DJL_SANITIZE`) before running the JVM suite again.

There is no TSan leg: the `local-sync` HAL driver runs all work inline on the calling
thread, so there are no IREE-internal threads to inspect.

## Threading

`IreeSymbolBlock.forward()` is not thread-safe on the same model. Use one
`Model`/`Predictor` per thread, and never close a model with a forward in flight.

## Docs

- Design: `docs/superpowers/specs/2026-07-19-djl-iree-engine-skeleton-design.md`
- Findings (the go/no-go writeup): `docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md`
- Plan: `docs/superpowers/plans/2026-07-19-djl-iree-engine-skeleton.md`
