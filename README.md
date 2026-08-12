# djl-iree-engine

A [DJL](https://djl.ai/) engine that runs [IREE](https://iree.dev/) `.vmfb` models.

Compile a model ahead of time with IREE, hand the `.vmfb` (and any `.irpa` parameter archives)
to DJL's `Model.load`, and run it from Java through the ordinary `Predictor` API. The engine
is a thin JNI shim over the IREE runtime, statically linked, published to Maven Central with a
native library per platform. It is an early library with a deliberately small surface — read
[Status and limitations](#status-and-limitations) before you depend on it.

## Quickstart

The `add` model below is committed to this repository as a test fixture, so there is nothing
to compile and no `iree-compile` needed. Gradle 8.2 or newer (the Kotlin DSL below uses
property assignment), a JDK 17 or newer, and network access are the only prerequisites.

```bash
mkdir iree-quickstart && cd iree-quickstart
mkdir -p models src/main/java
curl -Lo models/add.vmfb \
  https://raw.githubusercontent.com/measly-java-learning/djl-iree-engine/main/src/test/resources/models/add.vmfb
```

`settings.gradle.kts`:

```kotlin
rootProject.name = "iree-quickstart"
```

`build.gradle.kts`:

```kotlin
plugins { application }

repositories { mavenCentral() }

dependencies {
    implementation("ai.djl:api:0.36.0")
    implementation("org.measly:djl-iree-engine:1.3.0")
    runtimeOnly("org.measly:djl-iree-engine:1.3.0") {
        // Pick the platform that matches the runtime host:
        // linux-x86_64, linux-aarch64, or windows-x86_64.
        capabilities { requireCapability("org.measly:djl-iree-engine-linux-x86_64") }
    }
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")   // any SLF4J binding; this one just stays quiet
}

application { mainClass = "AddQuickstart" }
```

`src/main/java/AddQuickstart.java`:

```java
import ai.djl.Model;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.NoopTranslator;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

public final class AddQuickstart {

    public static void main(String[] args) throws Exception {
        try (Model model = Model.newInstance("add", "IREE")) {
            model.load(Path.of("models"), "add", Map.of("entryPoint", "module.add"));

            try (Predictor<NDList, NDList> predictor = model.newPredictor(new NoopTranslator());
                    NDManager manager = model.getNDManager().newSubManager()) {
                NDArray lhs = manager.create(new float[] {1f, 2f, 3f, 4f}, new Shape(4));
                NDArray rhs = manager.create(new float[] {10f, 20f, 30f, 40f}, new Shape(4));

                NDList out = predictor.predict(new NDList(lhs, rhs));
                System.out.println(Arrays.toString(out.get(0).toFloatArray()));
            }
        }
    }
}
```

```bash
gradle run -q
```

```
[11.0, 22.0, 33.0, 44.0]
```

`models/` holds a bare `add.vmfb` with no manifest, which is the implicit single-program door
described under [Loading models](#loading-models). `Map.of("entryPoint", "module.add")` names
the exported function; the default is `module.main`.

## Quickstart: a real model

From a clone of this repository, `example/` exports MobileNetV2 with IREE and runs
`[1,3,224,224] -> [1,1000]` through the engine:

```bash
./native/build.sh                 # build the native library; a fresh clone has none
./gradlew :example:exportModels   # writes mobilenet_v2.vmfb into example/build/models/
./gradlew :example:run            # runs org.measly.example.MobilenetExample
```

`./native/build.sh` is required and has no substitute in the Gradle build. Unlike the first
quickstart, `example/` resolves the engine as `project(":")` rather than from Maven Central, so
it does not get the published classifier jar's prebuilt library — and the library is not
committed (`.gitignore` excludes `src/main/resources/native/**`). Without this step the example
fails in `LibUtils` with "Native library not found on the classpath". Set `IREE_LIBRARY_PATH` to
an existing library to skip it. The build needs CMake, Ninja, and a C++20 compiler; see
[`CONTRIBUTING.md`](CONTRIBUTING.md) for the full list.

`:example:run` depends on `:example:exportModels`, so the last command alone is enough after the
native build; the export is spelled out because it is the slow, network-touching half. It needs
`uv` on `PATH` and network access on first run. See
[`example/README.md`](example/README.md) for the export prerequisites and the `uv` fallback.

## Declaring the dependency

The native jar is published as a Gradle variant with a per-platform capability, so Gradle
consumers should request the platform by capability rather than by classifier:

```kotlin
dependencies {
    implementation("org.measly:djl-iree-engine:<version>")
    runtimeOnly("org.measly:djl-iree-engine:<version>") {
        // Pick the platform that matches the runtime host:
        // linux-x86_64, linux-aarch64 or windows-x86_64
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

The same capability/classifier shape applies to the other two platforms: use
`requireCapability("org.measly:djl-iree-engine-linux-aarch64")` or
`<classifier>linux-aarch64</classifier>` on aarch64 Linux hosts, and
`requireCapability("org.measly:djl-iree-engine-windows-x86_64")` or
`<classifier>windows-x86_64</classifier>` on Windows x86_64 hosts.

`ai.djl:api` and an SLF4J API jar are `compileOnly` here and are not dragged in transitively —
declare them yourself, as the quickstart does.

## Supported platforms

| Platform | Artifact | HAL driver | QA |
|---|---|---|---|
| `linux-x86_64` | `libiree_djl.so` | `local-sync` (default), `local-task` | Catch2 + ASan/LSan leak harness; TSan (`local-sync` only — see [CONTRIBUTING.md](CONTRIBUTING.md#native-qa)) |
| `linux-aarch64` | `libiree_djl.so` | `local-sync` (default), `local-task` | Catch2 + ASan/LSan leak harness |
| `windows-x86_64` | `iree_djl.dll` | `local-sync` (default), `local-task` | Catch2 + static-CRT assertion |

All three are built, QA'd, and published by CI (`.github/workflows/native-build-job.yml`); a
release cannot silently omit one, because each classifier jar fails the build if its library or
license notices are missing. Windows differs from the Linux rows only in QA depth: there is no
ASan/LSan leak harness and no TSan gate there. The QA commands themselves are described in
[`CONTRIBUTING.md`](CONTRIBUTING.md).

The native library ships in a per-platform classifier jar (`<artifact>-<platform>.jar`) and is
extracted on first load into a **content-addressed cache**, keyed by the SHA-256 of the library
bytes: `%LOCALAPPDATA%\iree-djl` on Windows, else `$XDG_CACHE_HOME/iree-djl` if that variable is
set, else `~/.cache/iree-djl`. A per-JVM temp file is not used because Windows cannot delete a
loaded DLL, so every run would leak a full copy; the stable per-content directory is reused
across runs and across concurrent JVMs instead. Set `IREE_LIBRARY_PATH` to load a specific
library and bypass extraction entirely.

## Runtime requirements

- **JDK 17** or newer.
- One of the platforms in the table above.

That is all, **when you consume the published artifact**: IREE itself is statically linked into
the shipped library, so there is no IREE installation, no `iree-compile` on the inference host,
and no CMake or C++ toolchain needed to run a model. Building this repository from source is a
different matter — the native library is not committed and must be built with
`./native/build.sh`, which is why the second quickstart above starts with it. Those build
prerequisites live in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Loading models

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

## Observability

`IreeEngineStats.snapshot()` returns an immutable view of engine configuration, process
totals, and every live model. It never throws — a monitoring poll must not be the thing that
breaks production. The engine also registers an MXBean at
`org.measly.iree:type=IreeEngineStats` on the first model load.

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
zero-copy only when it meets a 64-byte alignment precondition, and a Java direct `ByteBuffer`
does not, so inputs handed straight from `NDArray.toByteBuffer()` stage a copy on every call.
`stagedImports / (stagedImports + wrappedImports)` is how you find out whether that is
happening to you.

Full detail — gauge semantics, JMX registration and its failure handling, and why this is not
`ai.djl.metric.Metrics` — is in [`docs/observability.md`](docs/observability.md).

## Performance and zero-copy inputs

The engine copies caller data into engine-owned buffers on every call by default, because IREE
requires 64-byte alignment (`IREE_HAL_HEAP_BUFFER_ALIGNMENT`) to import a host buffer zero-copy
and the JVM guarantees only 8 for `ByteBuffer.allocateDirect`. A user-supplied direct buffer
therefore imports zero-copy only when its malloc'd address happens to be aligned (~40% of small
allocations) and otherwise stages a copy into a per-runtime cached staging buffer, reused across
calls.

Set `-Diree.engine.alignedBuffers=true` to have `NDManager.create` allocate 64-byte-aligned
buffers instead; those import zero-copy. The engine allocates and the user writes into what the
engine hands back. The flag is read per allocation, so it can be toggled around a measurement.
It is **experimental**.

Whether this matters depends entirely on the workload: for memory-bound kernels the staged copy
costs up to ~90% of the call at 256 KB–4 MB inputs; for compute-heavy models (MobileNet, 61.6 ms
kernel) it is ~0.5% noise. Full measurements:
[`docs/2026-08-04-borrowed-host-buffers-findings.md`](docs/2026-08-04-borrowed-host-buffers-findings.md)
§3 and
[`docs/2026-08-04-staging-and-output-findings.md`](docs/2026-08-04-staging-and-output-findings.md).

## Threading

`IreeSymbolBlock.forward()` is not thread-safe on the same model. Use one
`Model`/`Predictor` per thread, and never close a model with a forward in flight.

## Status and limitations

**Walking skeleton with manifest loading.** This exists to answer whether IREE works
as a DJL engine and at what cost. It runs a trivial `add` model end to end, and `Model.load`
accepts a model artifact that names a `.vmfb` plus scope-bound `.irpa` parameter archives in a
manifest JSON document. The go/no-go question is answered in
[`docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md`](docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md)
(verdict: **GO**). It is not a finished product — see the deferred list in the design doc and
the findings doc.

Known limits worth knowing before you adopt it:

- CPU only: the `local-sync` and `local-task` HAL drivers, no GPU backend.
- No CPU target-tier selection — you must compile `.vmfb` for a baseline target yourself.
- No archive handling: `.irpa` and `.vmfb` must be unpacked on disk.
- `forward()` is single-threaded per model (see [Threading](#threading)).
- Zero-copy input handling is behind an experimental flag.

## Third-party licenses

The native library (`libiree_djl.so`, `iree_djl.dll`) statically links third-party components
from the pinned `iree-runtime-dist` tarball. The components linked into the shipped library are:

| Component | License |
|---|---|
| IREE runtime (HAL, VM, local-sync/local-task drivers) | Apache-2.0 |
| FlatCC | Apache-2.0 |
| libbacktrace | BSD-3-Clause |
| printf | MIT |

Full license texts for these are bundled in the native classifier jar under
`META-INF/licenses/iree-runtime/` (`LICENSE` + `THIRD-PARTY-NOTICES/`), sourced verbatim
from the runtime tarball (`native/build.sh` stages them next to the library). This list is
tied to the runtime pin (`native/cmake/IreeRuntimePin.cmake`); refresh it when the pin bumps.

## Contributing

Build prerequisites, the build and test loop, clangd editor setup, the native QA gates, and the
container build are all in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Docs

- Observability reference: [`docs/observability.md`](docs/observability.md)
- Design: [`docs/superpowers/specs/2026-07-19-djl-iree-engine-skeleton-design.md`](docs/superpowers/specs/2026-07-19-djl-iree-engine-skeleton-design.md)
- Findings (the go/no-go writeup): [`docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md`](docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md)
- Plan: [`docs/superpowers/plans/2026-07-19-djl-iree-engine-skeleton.md`](docs/superpowers/plans/2026-07-19-djl-iree-engine-skeleton.md)
- IRPA manifest loading (this chunk): [`docs/superpowers/specs/2026-08-02-irpa-manifest-loading-design.md`](docs/superpowers/specs/2026-08-02-irpa-manifest-loading-design.md)
- Windows amd64 support: [`docs/superpowers/specs/2026-08-03-windows-amd64-support-design.md`](docs/superpowers/specs/2026-08-03-windows-amd64-support-design.md)
- Wishlist for the dist project, with delivered/open status:
  [`docs/superpowers/specs/iree-runtime-dist-wishlist.md`](docs/superpowers/specs/iree-runtime-dist-wishlist.md)
- `iree-runtime-dist` handover (what the artifact actually ships):
  [`docs/2026-07-20-djl-iree-engine-handover.md`](docs/2026-07-20-djl-iree-engine-handover.md)
- Usability report on the dist artifact, with filed issues and verdict:
  [`docs/2026-07-20-iree-runtime-dist-usability-report.md`](docs/2026-07-20-iree-runtime-dist-usability-report.md)
