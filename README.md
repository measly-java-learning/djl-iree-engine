# djl-iree-engine

A [DJL](https://djl.ai/) engine that runs [IREE](https://iree.dev/) `.vmfb` models.

**Status: walking skeleton with manifest loading.** This exists to answer whether IREE works
as a DJL engine and at what cost. It runs a trivial `add` model end to end, and `Model.load`
accepts a model artifact that names a `.vmfb` plus scope-bound `.irpa` parameter archives in a
manifest JSON document. The go/no-go question is answered in
`docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md` (verdict: **GO**). It is
not a product — see the deferred list in the design doc and the findings doc. Linux (x86_64
and aarch64) only.

### Supported platforms

| Platform | Artifact | HAL driver | QA |
|---|---|---|---|
| `linux-x86_64` | `libiree_djl.so` | `local-sync` (default), `local-task` | Catch2 + ASan/LSan leak harness; TSan (see [Native QA](#native-qa)) |
| `linux-aarch64` | `libiree_djl.so` | `local-sync` (default), `local-task` | Catch2 + ASan/LSan leak harness |

The native library ships in a per-platform classifier jar (`<artifact>-<platform>.jar`) and is
extracted on first load to a temp file (`java.io.tmpdir`), deleted on JVM exit. Set
`IREE_LIBRARY_PATH` to load a specific library instead and bypass extraction entirely. Unlike a
production engine, there is no content-addressed extraction cache here — see
`LibUtils`'s javadoc for why that's deferred.

### Observability

`IreeEngineStats.snapshot()` returns an immutable view of engine configuration, process
totals, and every live model. It never throws — a monitoring poll must not be the thing that
breaks production.

```java
IreeStatsSnapshot stats = IreeEngineStats.snapshot();
for (IreeModelStats model : stats.getModels()) {
    long imports = model.getWrappedImports() + model.getStagedImports();
    double stagedRate = imports == 0 ? 0.0 : (double) model.getStagedImports() / imports;
    System.out.printf(
            "%s: %d forwards, %.1f%% staged, %d bytes staging%n",
            model.getName(), model.getForwardCount(), stagedRate * 100, model.getStagingBytes());
}
```

**The staged-import rate is the signal specific to this engine.** IREE imports a host buffer
zero-copy only when it meets a 64-byte alignment precondition. A Java direct `ByteBuffer`
does not — the JVM guarantees nothing stronger than 8-byte alignment — so inputs handed
straight from `NDArray.toByteBuffer()` stage a copy on every call. `stagedImports /
(stagedImports + wrappedImports)` is how you find out whether that is happening to you.

**Byte gauges use `-1` for "unavailable" and `0` for "genuinely zero".** `stagingBytes == 0`
means nothing has staged yet, which is a real state. `deviceBytesPeak == -1` means IREE's
allocator statistics were compiled out of the runtime, so the figure is unknowable — check
`isNativeStatsAvailable()`.

**JMX.** The engine registers an MXBean at `org.measly.iree:type=IreeEngineStats` on the first
model load. Disable with `-Dai.djl.iree.jmx_enabled=false`, or drive it explicitly via
`IreeEngineStats.registerMBean()` / `unregisterMBean()`. Registration failure logs one warning
and is reported as `getJmxStatus()` — it never fails a model load.

**Not `ai.djl.metric.Metrics`.** DJL's own metrics are a time-series buffer suited to
benchmarking: `Metrics.limit` defaults to 0, meaning uncapped, so every `predict()` retains
three `Metric` objects indefinitely unless you wire both `setLimit` and `setOnLimit`. Use it
for profiling; use `IreeEngineStats` for always-on monitoring.

### Declaring the dependency

The native jar is published as a Gradle variant with a per-platform capability, so Gradle
consumers should request the platform by capability rather than by classifier:

```kotlin
dependencies {
    implementation("org.measly:djl-iree-engine:<version>")
    runtimeOnly("org.measly:djl-iree-engine:<version>") {
        // Pick the platform that matches the runtime host:
        // linux-x86_64 or linux-aarch64
        capabilities { requireCapability("org.measly:djl-iree-engine-linux-x86_64") }
    }
}
```

Maven consumers add the classifier form alongside the main (classifier-less) dependency:

```xml
<dependency>
    <groupId>org.measly</groupId>
    <artifactId>djl-iree-engine</artifactId>
    <version>&lt;version&gt;</version>
    <classifier>linux-x86_64</classifier>
    <scope>runtime</scope>
</dependency>
```

The same capability/classifier shape applies to `linux-aarch64` (use
`requireCapability("org.measly:djl-iree-engine-linux-aarch64")` or
`<classifier>linux-aarch64</classifier>`) on aarch64 hosts.

## Prerequisites

The engine consumes the published `iree-runtime-dist` artifact pinned in
`native/cmake/IreeRuntimePin.cmake` — a hash-pinned tarball
of 198 static archives, fetched and verified by CMake at configure time. There is **no IREE
source tree, no IREE build tree, and no compiler required** to build or test this engine:

- JDK 17 (e.g. `/usr/lib/jvm/zulu-17-amd64`) — set `JAVA_HOME` to it.
- CMake, Ninja, and a C++20 (gcc/clang) compiler.
- One-time prerequisite — `bash tools/fetch-iree-metadata.sh` (requires the `gh` CLI —
  https://cli.github.com/ — installed and authenticated). It derives the release from
  `native/cmake/IreeRuntimePin.cmake` (the single source of truth) and feeds
  `generateIreeDataTypes`.
- Network access, to fetch the pinned `iree-runtime-dist` tarball (SHA256-verified against
  `native/cmake/IreeRuntimePin.cmake`; a tampered hash fails hard at configure time). The
  native *test* build additionally fetches Catch2 (v3.15.3) via `FetchContent`'s
  `GIT_REPOSITORY`/`GIT_TAG` (unpinned by hash) — this needs `git` on `PATH` and network
  access to GitHub as a second host.

`iree-compile` from pip is needed **only** if you want to regenerate the test fixture
(`add.vmfb`), which is otherwise committed:
`uv pip install iree-base-compiler==3.11.0`. This is the version paired with the dist's linked
runtime (`e4a3b040`, stable tag `v3.11.0`) per its `manifest.json` — no more nightly-chasing. The
pip `iree-base-runtime` wheel is still not usable at any version; it ships no headers and no
linkable library, which is exactly why the dist artifact exists.

## Model manifests (parameters)

`Model.load` can pull weights from IREE parameter archives (`.irpa`) alongside the `.vmfb`: the
model artifact names them in a manifest JSON document, and each archive is bound to the runtime
scope the compiled program references. Two obligations before you start:

- **Unarchive before loading.** This engine accepts no zip/tar and extracts nothing on the
  caller's behalf. A zipped `.irpa` must be materialised in full first; path-passing exists
  precisely to avoid whole-archive I/O.
- **Compile `.vmfb` for a baseline CPU target.** A program built with
  `--iree-llvmcpu-target-cpu=host` on a modern machine faults with an illegal instruction
  (SIGILL) on an older one. Compile for a baseline target until tier selection exists.

### Manifest schema (v1)

```json
{
  "schemaVersion": 1,
  "program": "model.vmfb",
  "entryPoint": "module.main",
  "parameters": {
    "model": "weights.irpa",
    "bias":  "bias.irpa"
  }
}
```

`schemaVersion` and `program` are required — the version must be a JSON integer and is never
assumed when absent; `entryPoint` and `parameters` are optional (an absent `parameters` is
equivalent to `{}`). Unknown fields are ignored, so the format can add keys without breaking
this engine. Every path the manifest names is resolved against the manifest's own directory,
must exist, and must stay inside that directory — checked on the resolved real path, so a
symlink escape is caught too.

### Where `Model.load` looks

| `modelPath` | Behaviour |
|---|---|
| A regular file | Parsed as a manifest, whatever its name. |
| A directory containing `djl-iree-model.json` | That file is parsed. |
| A directory with no manifest but a `<prefix>.vmfb` | Implicit single-program, zero-parameter manifest (the pre-manifest behaviour). |
| A directory with neither | Error naming the directory and both things sought. |

### Load options

| Option | Source | Default |
|---|---|---|
| `entryPoint` | load option > manifest > default | `"module.main"` |
| `device` | load option only | `"local-sync"` |
| `allowUnsafePaths` | load option only | `false` |

`entryPoint` names a function of the compiled artifact, so the manifest is its natural home; the
caller keeps an override because a `.vmfb` may export several. `device` and `allowUnsafePaths`
are policy and never read from a manifest. `allowUnsafePaths` opts out of the containment check
above by name — a manifest can never authorize its own path escapes.

### Zero-copy inputs (experimental)

The engine copies caller data into engine-owned buffers on every call by default. Set
`-Diree.engine.alignedBuffers=true` to have `NDManager.create` allocate 64-byte-aligned
buffers instead; those import into the IREE runtime zero-copy. The flag is read per allocation,
so it can be toggled around a measurement.

JDK `ByteBuffer.allocateDirect` buffers are **not** reliably importable: the JVM guarantees
only 8-byte alignment and IREE requires 64 (`IREE_HAL_HEAP_BUFFER_ALIGNMENT`), so a
user-supplied direct buffer imports zero-copy only when its malloc'd address happens to be
aligned (~40% of small allocations) and otherwise stages a copy into a per-runtime cached
staging buffer (reused across calls — the fallback no longer allocates a fresh buffer per
call; measured recovery ~85% of the staged-vs-wrapped delta at ≥ 4 MB). The engine
allocates; the user writes into what the engine hands back. Measured impact, two workload
shapes: for memory-bound kernels the staged copy costs up to ~90% of the call at 256 KB–4 MB
inputs; for compute-heavy models (MobileNet, 61.6 ms kernel) the copy is ~0.5% noise. Full
measurements: `docs/2026-08-04-borrowed-host-buffers-findings.md` §3 and
`docs/2026-08-04-staging-and-output-findings.md`.

## Build and test

```bash
./tools/export_add.sh    # regenerate add.vmfb (optional; it is committed)
./native/build.sh        # build the shim and stage it into resources
JAVA_HOME=/usr/lib/jvm/zulu-17-amd64 ./gradlew test    # JVM tests
```

The JVM suite: `IreeNativeTest` (JNI boundary, including the scale/scale2 parameter-archive
loads), `AddModelIT` (the implicit bare-`.vmfb` door), `ModelManifestTest` (schema rules),
`ModelResolverTest` (front doors + containment), and `ScaleModelIT` (manifest directory end to
end → `[2, 4, 6, 8]`).

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
  `iree-runtime-dist` tarball plus a Catch2 clone from GitHub.
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
# Catch2 units (9 cases). native/build.sh defaults to -DIREE_DJL_BUILD_TESTS=OFF — the shipping
# build stages only the .so, so it no longer clones and compiles Catch2. Opt back in to get the
# test binaries in native/build, or just run ./native/build_qa.sh, which builds them either way.
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

## Threading

`IreeSymbolBlock.forward()` is not thread-safe on the same model. Use one
`Model`/`Predictor` per thread, and never close a model with a forward in flight.

## Third-party licenses

The native library (`libiree_djl.so`) statically links third-party components from the
pinned `iree-runtime-dist` tarball. The components linked into the shipped library are:

| Component | License |
|---|---|
| IREE runtime (HAL, VM, local-sync/local-task drivers) | Apache-2.0 |
| FlatCC | Apache-2.0 |
| libbacktrace | BSD-3-Clause |
| printf | MIT |

Full license texts for these are bundled in the native classifier jar under
`META-INF/licenses/iree-runtime/` (`LICENSE` + `THIRD-PARTY-NOTICES/`), sourced verbatim
from the runtime tarball (`native/build.sh` stages them next to the `.so`). This list is
tied to the runtime pin (`native/cmake/IreeRuntimePin.cmake`); refresh it when the pin bumps.

## Docs

- Design: `docs/superpowers/specs/2026-07-19-djl-iree-engine-skeleton-design.md`
- Findings (the go/no-go writeup): `docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md`
- Plan: `docs/superpowers/plans/2026-07-19-djl-iree-engine-skeleton.md`
- IRPA manifest loading (this chunk): `docs/superpowers/specs/2026-08-02-irpa-manifest-loading-design.md`
- Wishlist for the dist project, with delivered/open status:
  `docs/superpowers/specs/iree-runtime-dist-wishlist.md`
- `iree-runtime-dist` handover (what the artifact actually ships):
  `docs/2026-07-20-djl-iree-engine-handover.md`
- Usability report on the dist artifact, with filed issues and verdict:
  `docs/2026-07-20-iree-runtime-dist-usability-report.md`
