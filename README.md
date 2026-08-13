# djl-iree-engine

A [DJL](https://djl.ai/) engine that runs [IREE](https://iree.dev/) `.vmfb` models.

Compile a model ahead of time with IREE, hand the `.vmfb` (and any `.irpa` parameter archives)
to DJL's `Model.load`, and run it from Java through the ordinary `Predictor` API. The engine
is a thin JNI shim over the IREE runtime, statically linked, published to Maven Central with a
native library per platform. It runs on CPU; see
[Status and limitations](#status-and-limitations) for the current boundaries.

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

That fixture is for **x86_64**, on Linux and Windows alike. On an aarch64 host, take the
aarch64 build of the same model instead:

```bash
curl -Lo models/add.vmfb \
  https://raw.githubusercontent.com/measly-java-learning/djl-iree-engine/main/src/test/resources/models/aarch64/add.vmfb
```

A `.vmfb` is compiled for one CPU architecture and does not run on another. It is portable
across operating systems: these fixtures are built as architecture-generic embedded ELF, which
IREE's loader reads the same way on any OS.

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
| `linux-x86_64` | `libiree_djl.so` | `local-sync` (default), `local-task` | Catch2 + ASan/LSan leak harness; TSan over both drivers (see [CONTRIBUTING.md](CONTRIBUTING.md#native-qa)) |
| `linux-aarch64` | `libiree_djl.so` | `local-sync` (default), `local-task` | Catch2 + ASan/LSan leak harness |
| `windows-x86_64` | `iree_djl.dll` | `local-sync` (default), `local-task` | Catch2 + static-CRT assertion |

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
  (SIGILL) on an older one. Compile for a baseline target until tier selection exists. This
  applies to models you compile; the fixtures in this repository are already built that way.

### Manifest schema (v1)

Minimal — `schemaVersion` and `program` are the only required keys:

```json
{
  "schemaVersion": 1,
  "program": "model.vmfb"
}
```

With parameter archives, naming an entry point and binding each `.irpa` to the runtime scope
the compiled program references:

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

`entryPoint` and `parameters` are optional. Every path a manifest names is resolved against the
manifest's own directory and must stay inside it.

### Where `Model.load` looks

| `modelPath` | Behaviour |
|---|---|
| A regular file | Parsed as a manifest, whatever its name. |
| A directory containing `djl-iree-model.json` | That file is parsed. |
| A directory with no manifest but a `<prefix>.vmfb` | Loaded as a single program with no parameters. |
| A directory with neither | Error naming the directory and both things sought. |

### Load options

| Option | Source | Default |
|---|---|---|
| `entryPoint` | load option > manifest > default | `"module.main"` |
| `device` | load option only | `"local-sync"` |
| `allowUnsafePaths` | load option only | `false` |

`entryPoint` names a function of the compiled artifact and belongs in the manifest, but a
`.vmfb` may export several, so the caller can override it. `device` selects the HAL driver.
`allowUnsafePaths` opts out of the containment check above; it is a load option only, so a
manifest can never authorize its own path escapes.

## Observability

The engine registers an MXBean at `org.measly.iree:type=IreeEngineStats` on the first model
load, so any JMX console sees it without extra wiring. `IreeEngineStats.snapshot()` returns the
same data programmatically.

Expect per-model inference latency — load time, forward count, total and worst-case forward
nanoseconds — and memory utilisation, both the bytes spent staging inputs and IREE's own device
allocator peak and live figures. [`docs/observability.md`](docs/observability.md) covers the
full surface.

## Performance and zero-copy inputs

The engine has two input modes. By default it copies your data into an engine-owned buffer on
every call, because IREE imports a host buffer zero-copy only at 64-byte alignment
(`IREE_HAL_HEAP_BUFFER_ALIGNMENT`) and the JVM guarantees only 8 for
`ByteBuffer.allocateDirect`; anything unaligned stages a copy into a per-runtime staging buffer
that is reused across calls. Set `-Diree.engine.alignedBuffers=true` and `NDManager.create`
hands back 64-byte-aligned buffers instead, which import with no copy at all.

Whether the copy is worth eliminating is a property of your workload, and the honest answer is
that you have to measure it. The smaller the model, the more the fixed copy cost matters
relative to the kernel; for a large compute-bound model it disappears into noise. The
staged-import rate in the statistics above tells you whether inputs are being copied at all,
which is the first thing to establish.

## Threading

`IreeSymbolBlock.forward()` is not thread-safe on the same model. Use one
`Model`/`Predictor` per thread, and never close a model with a forward in flight.

## Status and limitations

What the engine does not do, as of this release:

- CPU only: the `local-sync` and `local-task` HAL drivers, no GPU backend.
- No CPU target-tier selection — you must compile `.vmfb` for a baseline target yourself.
- No archive handling: `.irpa` and `.vmfb` must be unpacked on disk.
- `forward()` is single-threaded per model (see [Threading](#threading)).
- Zero-copy input is opt-in and changes where your input buffers come from (see
  [Performance and zero-copy inputs](#performance-and-zero-copy-inputs)).

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

