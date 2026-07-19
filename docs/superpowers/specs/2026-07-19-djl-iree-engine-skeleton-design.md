# DJL IREE Engine — Walking Skeleton Design

**Date:** 2026-07-19
**Status:** Approved
**Supersedes/refines:** `iree-java-jni-bootstrap.md` (the outline this design was derived from)

## Purpose

Answer one question as cheaply as possible: **will IREE work as a DJL engine, and is it a
reasonable amount of effort?** If the answer is no, we want to know before building a full
engine on top of an unvalidated foundation.

This is a walking skeleton, not a product. It runs a trivial two-input `add` model end to end
through DJL, with the native layer's real risks — IREE's C-API refcounting, `iree_status_t`
ownership, and host-buffer import behavior — validated under sanitizers before any JVM code
exists.

`djl-executorch-engine` (`/home/corey/workspace/djl-executorch-engine`) is the reference
implementation. Where this document says "lifted," take that structure directly.

## Reconciling the outline with reality

`iree-java-jni-bootstrap.md` describes a standalone Java/JNI inference runtime and never
mentions DJL. `djl-executorch-engine` is a full DJL engine plugin. The outline in fact
describes the **bottom two layers** of what the ExecuTorch project actually is. This design
covers the outline's scope *plus* the thinnest viable DJL layer above it.

Two concrete divergences from the ExecuTorch native core, both derived from the outline:

1. **Load from bytes, not a path.** `EtRuntime` takes `const std::string& ptePath`; `IreeRuntime`
   takes `std::span<const std::byte>`. This follows the outline (§4), suits DJL (`IreeModel`
   already holds the file contents), and lets Catch2 tests construct artifacts in memory.

2. **No `ForwardResult` analog.** ExecuTorch's `ForwardResult` is a borrow-view type: it owns the
   EValue vector and hands out `OutputView`s pointing into ExecuTorch's arena, with copy-out done
   by the consumer. Outline §6 forbids this shape for IREE — "Release every IREE handle before
   `invoke` returns. Nothing IREE-side outlives the call." So `Invoke` returns **owning**
   `OutputBuffer`s and copy-out happens inside the facade. This is a simplification, not a
   complication.

## Scope decisions

| Decision | Choice | Rationale |
|---|---|---|
| Target shape | Walking skeleton, DJL-shaped | Front-loads native risk; still ends at something that runs under DJL |
| First model | `add.vmfb` | MobileNet drags in the torch→StableHLO export chain, a separate risk that would muddy the signal |
| Runtime source | Local build via `IREE_INSTALL` seam | The pip runtime wheel is not linkable (see below) |
| Packaging | Skeleton-minimum, linux-x86_64 only | All deferred machinery solves a *distribution* problem this project does not have yet |
| Dist repo | Stubbed `IreeRuntimePin.cmake` | Cheap now, awkward to retrofit |

## Runtime consumption

### `iree-base-runtime` (pip) cannot be linked against — verified

The `iree_base_runtime-3.11.0-cp313-cp313t-manylinux_2_28_x86_64.whl` contains 59 entries:
**0 headers, 0 CMake config, 0 linkable runtime library.** Its only `.so`s are
`_runtime.cpython-313t-*.so` (the Python extension) and a vendored libzstd. It is a closed
Python binding, not a C dev tree. The outline's own aside is consistent — it calls
`iree-base-runtime` a *Python path*, not a linking target.

### The local build at `/home/corey/workspace/iree-build` is sufficient

Verified against outline §5/§8 requirements:

- `IREE_ENABLE_POSITION_INDEPENDENT_CODE=ON` — confirmed empirically: the `local_sync` driver
  archive carries only `R_X86_64_PC32`/`PLT32` relocations and **zero `R_X86_64_32S`**. Non-PIC
  static libs cannot link into a shared library on x86_64, so this would otherwise have been a
  hard stop.
- `IREE_HAL_DRIVER_LOCAL_SYNC=ON` — the §5 driver the threading argument rests on.
  `LOCAL_TASK=ON` too, so the comparison stays available.
- `IREE_HAL_EXECUTABLE_LOADER_EMBEDDED_ELF=ON` — matches the `llvm-cpu` backend per §8.
- `libiree_runtime_impl.a` — the unified high-level runtime behind `iree/runtime/api.h`, the
  API surface §3/§12 targets.
- Release, `BUILD_SHARED_LIBS=OFF` — link static, LTO-trim per §8.

Headers come from the source tree: `/home/corey/workspace/iree/runtime/src/iree/runtime/api.h`.

### Gap: no `iree-compile`

The build has `IREE_BUILD_COMPILER=OFF`, so there is no way to produce a `.vmfb`. **Do not
rebuild for this** — enabling the compiler pulls in the full LLVM build (hours, tens of GB) for
a tool we invoke twice and never link.

Resolution: `uv pip install iree-base-compiler`, which ships a prebuilt `iree-compile`. This is
the tooling-vs-linking split: the pip *runtime* wheel is useless because we need headers and
libs; the pip *compiler* wheel is fine because we need only an executable.

- **Link** against `/home/corey/workspace/iree-build` (build tree + source headers)
- **Compile artifacts** with pip's `iree-compile`
- `iree-run-module` and `iree-dump-module` are present in the build tree and are used to confirm
  the exported VM symbol name (§12)

### Known wart

`CMAKE_INSTALL_PREFIX` is `/usr/local` and nothing has been installed there, so there is no
`IREERuntimeConfig.cmake` to `find_package`. The CMake seam points at the **build tree + source
include dirs** directly — less clean than ExecuTorch's `find_package(executorch)` against an
install tree. Revisit when the dist repo lands. A `ninja install` into a local prefix would give
a tidier seam but is not required.

## Repository layout

```
djl-iree-engine/
  build.gradle.kts              # java-library, JDK 17, DJL compileOnly — no publishing block yet
  settings.gradle.kts
  gradle/libs.versions.toml     # lifted (djl-api, slf4j, junit, mockito)
  native/
    CMakeLists.txt
    cmake/IreeRuntimePin.cmake  # STUB: FetchContent branch written, unreachable; IREE_INSTALL wins
    core/iree_runtime.{h,cpp}   # JNIEnv-free facade — RAII over the IREE C API
    jni/iree_djl_jni.cpp        # thin shim; jlong handle
    test/iree_runtime_test.cpp  # Catch2 units + golden vector
    harness/iree_leak_harness.cpp  # ASan/LSan, plain main(), no JVM
    build.sh                    # host build, linux-x86_64 only
  src/main/java/org/measly/iree/
    engine/  IreeEngineProvider, IreeEngine, IreeModel, IreeSymbolBlock,
             IreeNDManager, IreeNDArray, IreeDataTypes, LibUtils
    jni/     IreeNative, IreeTensor
  src/main/resources/META-INF/services/ai.djl.engine.EngineProvider
  src/test/resources/models/add.vmfb
  docs/superpowers/specs/
```

Coordinates `org.measly:djl-iree-engine`, package `org.measly.iree`, type prefix `Iree`.

**Dropped vs ExecuTorch:** `translate/` (no Translator library), the `et_logging` slf4j PAL
bridge, `native/tests/` shell suite, `local_build_wrapper.sh`, `build_qa.sh` / `bench.sh` /
`build_variants.sh`, `EtMethodMeta`, `example/`.

## Native core — `measly::iree::IreeRuntime`

The JNIEnv-free C++ core. Deliberately free of any JVM dependency so the JNI shim, the Catch2
tests, and the leak harness can all link it.

```cpp
namespace measly::iree {

// Borrowed input: host pointer the caller keeps valid across invoke(). May be
// imported zero-copy or staged — see lastImportOutcomes().
struct InputDesc {
  const void* data;
  size_t nbytes;
  std::vector<int64_t> shape;
  int32_t elementType;          // iree_hal_element_type_t
};

// OWNING output. Unlike ExecuTorch's OutputView, this holds its own bytes:
// every IREE handle is released before invoke() returns.
struct OutputBuffer {
  std::vector<int64_t> shape;
  int32_t elementType;
  std::vector<std::byte> data;
};

class IreeRuntime {
 public:
  static std::unique_ptr<IreeRuntime> Load(std::span<const std::byte> vmfb,
                                           std::string_view entryPoint);
  ~IreeRuntime();
  IreeRuntime(const IreeRuntime&) = delete;
  IreeRuntime& operator=(const IreeRuntime&) = delete;

  std::vector<OutputBuffer> Invoke(std::span<const InputDesc> inputs);

  // Empirical answer to outline §6 — did the last Invoke wrap or stage each input?
  enum class ImportOutcome { kWrapped, kStaged };
  std::span<const ImportOutcome> lastImportOutcomes() const;

 private:
  std::unique_ptr<RuntimeState> state_;  // pimpl: instance, device, session, call — all RAII
};
}
```

Four things carry the design:

- **One `unique_ptr<T, Deleter>` per IREE handle type**, the deleter calling the matching
  `iree_*_release`. Instance, device, session, buffer view, call. No raw handle escapes
  `RuntimeState`. A dropped release is a leak; a double release is a use-after-free (§3).

- **`IREE_CHECK_OR_THROW(expr)`** — the single status-consuming guard. Evaluates an
  `iree_status_t`, and on failure extracts the message, calls `iree_status_free`, and throws
  `std::runtime_error`. On success it consumes/ignores the OK status. **No status is ever
  silently dropped.** The JNI shim's native→`jthrow` translation sits above this unchanged.

- **`Load` takes bytes**, per the divergence noted above.

- **`lastImportOutcomes()` is deliberately API surface**, not a log line. The outline says
  "verify empirically… don't assume borrow" — making the outcome *assertable* turns that from a
  manual investigation into a test. If it reports `kStaged` for a Java direct `ByteBuffer`, that
  is a finding this project exists to deliver, not a failure.

**Driver: `local-sync`** (§5). No IREE-internal threads, therefore no TSan surface — which is
why the harness is ASan/LSan only, dropping ExecuTorch's TSan leg. The single-threaded-handle
contract becomes a whole-stack property rather than a boundary convention.

**Input marshaling is import-or-copy, never unconditional borrow** (§6): attempt
`iree_hal_allocator_import_buffer`; on unmet preconditions, stage a copy. An imported input
buffer must never escape `Invoke`.

## JNI shim

Lifted from ExecuTorch nearly verbatim — outline §7 says this layer is unchanged. Only the
facade type behind the `jlong` differs.

- Opaque `jlong` handle to the `IreeRuntime`
- Direct `ByteBuffer` I/O via `GetDirectBufferAddress`
- `JNI_OnLoad` with cached class/method IDs
- One native→`jthrow` translation point, fed by `IREE_CHECK_OR_THROW`

Dropped: the `et_logging` slf4j PAL bridge (deferred).

## DJL layer

The thinnest path that makes one `Predictor` call work.

- `IreeEngineProvider` — SPI registration via `META-INF/services/ai.djl.engine.EngineProvider`
- `IreeEngine` — rank 10; exposes the IREE version string
- `IreeModel` — reads `.vmfb` bytes, calls `IreeNative.load(byte[], String entryPoint)`, owns the
  native handle. The entry point is resolved from the DJL load options key `entryPoint`,
  defaulting to `module.main` when absent; the default is confirmed against `add.vmfb` with
  `iree-dump-module` in milestone 1 rather than assumed
- `IreeSymbolBlock` — `forward()`, marshals `NDList` ↔ `IreeTensor[]`
- `IreeNDManager` / `IreeNDArray` / `IreeDataTypes` — minimal factory; float32 only for `add.vmfb`
- `LibUtils` — `IREE_LIBRARY_PATH` env override, then classpath extraction from
  `/native/linux-x86_64/`. Plain copy, no content-addressed cache (Linux-only; no Windows
  DLL-locking problem to solve).

**Threading contract:** `IreeSymbolBlock.forward()` is not thread-safe on the same model. One
`Model`/`Predictor` per thread; never `close()` a model with a forward in flight. Same as
`EtSymbolBlock`, now backed end-to-end by `local-sync`.

**No `IreeMethodMeta`.** ExecuTorch needed it because `EtSymbolBlock` introspects input
arity/dtypes. For `add.vmfb` the entry point and signature are known, and §12 says to confirm
the symbol with `iree-dump-module` rather than query it at runtime. Reintroduce if MobileNet
needs it.

## Test strategy

Three layers, mapping to outline §9 and §11.

**Native Catch2 (`iree_runtime_test.cpp`)** — no JVM:
- Golden vector: `add.vmfb` with known inputs → known outputs
- Error paths, each forced to walk a distinct status: corrupt `.vmfb`, wrong entry-point name,
  shape mismatch, wrong element type
- `lastImportOutcomes()` asserted — records whether inputs wrapped or staged

**ASan/LSan harness (`iree_leak_harness.cpp`)** — plain `main()`, JVM out of the picture:
- N× load/invoke/close loop; flat RSS and zero LSan leaks proves retain/release balance
- Every error path invoked in a loop — where a dropped `iree_status_t` surfaces. Per §3 this is
  the highest-value thing the harness does, because error paths are the least hand-tested
- Assert no imported input buffer escapes `Invoke`
- **No TSan leg** — `local-sync` has no internal threads, which is the point

**JVM (JUnit 5):**
- `AddModelIT` — `add.vmfb` through a real DJL `Predictor` with a passthrough translator
- Unit tests for `LibUtils`, `IreeDataTypes`, and handle lifecycle (double-close,
  use-after-close)

**The import-or-copy question is answered at two levels deliberately:** natively with a
`posix_memalign`'d buffer (best case), and through JNI with a real Java direct `ByteBuffer` (the
case that actually matters). If those disagree, that *is* the finding — it means JVM buffer
alignment fails IREE's import preconditions and inputs are silently staging a copy.

## Milestones and gates

1. **Native core stands up** — CMake seam + `IreeRuntime` RAII + `IREE_CHECK_OR_THROW`;
   `add.vmfb` produced via pip `iree-compile`, exported symbol confirmed with `iree-dump-module`.
   *Gate: golden vector passes natively.*

2. **Sanitizers clean** — Catch2 error paths + ASan/LSan harness.
   *Gate: zero leaks across the load/invoke/close loop, all error paths walked.*
   **This is the go/no-go point.** If RAII-over-C-API is going to be miserable, it is miserable
   here, before any JVM code exists.

3. **JNI shim** — handle marshaling, exception translation, direct `ByteBuffer` I/O.
   *Gate: import outcome measured through a real Java direct `ByteBuffer`.*

4. **DJL layer** — provider through `IreeSymbolBlock`.
   *Gate: `AddModelIT` green.*

## Explicitly deferred

Recorded so the next milestone can lift them from ExecuTorch wholesale, where that repo is the
reference implementation for each:

- MobileNet v2 parity check (outline §11) and the torch→StableHLO export chain
- `manylinux_2_28` container wrapper and the glibc floor
- Windows/MSVC support, the `/MT` static-CRT pin row, `check_windows_crt.sh`
- Per-platform classifier JARs and the Maven publishing block
- Third-party license bundling
- The `et_logging` → slf4j PAL bridge
- The `Translator` support library (`translate/`)
- Swapping the stub `IreeRuntimePin.cmake` for a real `iree-runtime-dist` FetchContent pin
- Content-addressed native-library extraction cache in `LibUtils`
- Timing/benchmark harness and the JMH example module

## Verify against installed headers

Per outline §12 — these drift across IREE releases and must be confirmed against the build at
`/home/corey/workspace/iree` before use, not recalled from memory:

- Signatures in `iree/runtime/api.h`: `iree_runtime_instance_create`,
  `iree_runtime_session_create_with_device`, `iree_runtime_call_initialize_by_name`,
  `*_inputs_push_back_buffer_view`, `iree_runtime_call_invoke`,
  `*_outputs_pop_front_buffer_view`, and the buffer-view create/map/import calls
- `iree-compile` flag spelling (`--iree-hal-target-backends=llvm-cpu`)
- The exported VM function symbol in `add.vmfb` (`iree-dump-module`)
- `iree_hal_allocator_import_buffer` preconditions — memory type, usage bits, alignment
