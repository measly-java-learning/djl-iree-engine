# IRPA on the High-Level Session — Spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove — or disprove — that IREE Parameter Archive (IRPA) support can be wired into `IreeRuntime::Load` on the **existing high-level `iree_runtime_session` API**, and pin down the ownership chain the production design will depend on.

**Architecture:** Add a parameter-loading path to the JNIEnv-free core (`native/core/`). The chain is: open the `.irpa` as an `iree_io_file_handle_t` → parse it into an `iree_io_parameter_index_t` → wrap that in an `iree_io_parameter_provider_t` bound to a *scope name* → build an `io_parameters` VM module from the providers → `iree_runtime_session_append_module()` it into the session **before** the bytecode module, so the program's `io_parameters.load`/`gather` imports resolve. Everything is exercised by Catch2 + the ASan/LSan leak harness, linking `iree_djl_core` directly — no JDK, no JNI, no Java.

**Tech Stack:** C++20, IREE runtime 3.11.0 (`iree-runtime-dist` pin), Catch2 v3.15.2, CMake + Ninja, `iree-base-compiler==3.11.0` from the repo `.venv` for fixture generation.

## Why this spike is a gate, not a warm-up

This is a **go/no-go on the manifest contract itself**, not just on IRPA. Per `docs/2026-07-22-irpa-and-target-selection-scoping-notes.md`, the manifest JSON contract (schema versioning, scope→file maps, unsafe-path opt-in, well-known filename, existence checks) was designed primarily to carry **parameter archives**. If IRPA cannot be reasonably supported, the manifest is too much complexity to justify for `.vmfb` tiering alone, and the contract collapses back to a plain directory convention.

So the spike answers two questions, in order:

1. **Does it work on the high-level API?** (Tasks 2-4)
2. **Is the ownership story manageable?** (Tasks 5-6) — "it compiles" is not the bar; "a maintainer can hold the lifetime rules in their head and ASan agrees" is.

**Explicit non-goals:** manifest parsing, JSON schema, DJL integration, hwcaps/tier selection, the Panama C facade, the JNI surface, Windows. All sit above or beside this and none are blocked by it.

## Global Constraints

- **C++20**, `CMAKE_CXX_STANDARD 20` + `CMAKE_CXX_STANDARD_REQUIRED ON` — already set in `native/CMakeLists.txt`; do not remove (IREE headers require ≥C++17 and the package config supplies no `INTERFACE_COMPILE_FEATURES`).
- **The core stays JNIEnv-free.** Nothing in `native/core/` may include `jni.h` or reference a JVM type. The Catch2 suite and leak harness link `iree_djl_core` directly and must keep building with **no JDK present**.
- **Every `iree_status_t` is consumed exactly once**, via `IREE_CHECK_OR_THROW` (`native/core/iree_status.h`) and nowhere else. A non-OK status is a heap object; dropping one leaks it and its message payload.
- **Every refcounted IREE handle gets a `unique_ptr` deleter in `native/core/iree_handles.h`.** No raw handle escapes the facade.
- **Compiler must match the linked runtime exactly:** `iree-base-compiler==3.11.0` from pip's plain index. Never a nightly, never `--find-links`, never a from-source build. The dist records the pairing (`iree_compile_version: 3.11.0`, `runtime_commit: e4a3b0405d7d23554da26403658d0e8c3c5ecf25`). Mixing versions produces import-signature mismatches this project has already been bitten by once.
- **ASan and TSan are mutually exclusive** (`IREE_DJL_SANITIZE` / `IREE_DJL_TSAN`); CMake hard-errors if both are set.
- **Fixtures stay small.** The spike tests wiring, not throughput. No MobileNet-sized artifacts in the repo.
- **Existing tests must keep passing** at every commit. `Load`'s current 3-argument signature must remain source-compatible — the existing Catch2 tests and leak harness call it and must not need edits.

## Verified prerequisites (already confirmed — do not re-derive)

These were established before this plan was written. Trust them; if one turns out false, that is itself a finding worth stopping for.

**The API chain exists in the pinned dist**, headers and symbols both:

| Function | Header | Archive |
|---|---|---|
| `iree_runtime_session_append_module(session, iree_vm_module_t*)` | `runtime/session.h:147-148` | `libiree_runtime_impl.a` |
| `iree_io_parameters_module_create(vm_instance, count, providers, allocator, &module)` | `modules/io/parameters/module.h:21-25` | `libiree_modules_io_parameters_parameters.a` |
| `iree_io_file_handle_open(mode, path, allocator, &handle)` | `io/file_handle.h:207-209` | — |
| `iree_io_parameter_index_create(allocator, &index)` | `io/parameter_index.h:81-82` | — |
| `iree_io_parse_file_index(path, file_handle, index, allocator)` | `io/formats/parser_registry.h:25-27` | `libiree_io_formats_parser_registry.a` |
| `iree_io_parameter_index_provider_create(scope, index, max_concurrent, allocator, &provider)` | `io/parameter_index_provider.h:33-36` | `libiree_io_parameter_index_provider.a` |
| `iree_runtime_instance_vm_instance(instance)` | `runtime/instance.h:105` | — |

Release functions: `iree_io_file_handle_release`, `iree_io_parameter_index_release`, `iree_io_parameter_provider_release`, `iree_vm_module_release`.
Default concurrency: `IREE_IO_PARAMETER_INDEX_PROVIDER_DEFAULT_MAX_CONCURRENT_OPERATIONS` = 16 (`io/parameter_index_provider.h:22`).

**The VM context is not implicitly frozen.** The only freeze is the explicit `iree_vm_context_freeze` (`vm/context.h:118`), which the runtime session neither exposes nor calls. The repeated "only valid if the context is not yet frozen" notes in `session.h` are a caveat about a context *you* froze — not a one-append limit.

**Fixture generation works, with two gotchas:**

- `--iree-parameter-export=<scope>=<path>.irpa` on a plain constant global produces **no archive** — const-eval folds the global away first. Do not go down this road. The working approach is an MLIR global that *references* a parameter by name: `#stream.parameter.named<"model"::"weight">`.
- `iree-create-parameters --data=<key>=<shape>=<pattern>` is **broken in 3.11.0**: it writes a 4096-byte stub and then fails with a misleading `write failed, possibly out of disk space` (there was 248 GB free). Use `--splat=` for patterned values or `--data=<key>=<shape>` for zeroed storage. Both work.

**Scope is a runtime binding, not baked into the archive.** `iree-dump-parameters` reports the archive's scope as `<global>` while the program references `"model"::"weight"`, and `--parameters=model=file.irpa` binds them at load. This is a *finding*, and it validates the manifest's scope→file map design — record it, don't re-discover it.

**A known-good oracle exists.** This exact command works and produces `2 4 6 8`:

```bash
.venv/bin/iree-run-module --module=scale.vmfb --parameters=model=weights.irpa \
  --function=scale --input="4xf32=1,2,3,4"
```

Use it whenever facade behaviour is in doubt. It converts "is it us or IREE?" into a five-second check.

## File Structure

**Created:**
- `tools/scale.mlir` — fixture source: one parameter-backed global, one function.
- `tools/export_scale.sh` — regenerates both fixture artifacts. Peer of the existing `tools/export_add.sh`.
- `src/test/resources/models/scale.vmfb` — compiled fixture (committed).
- `src/test/resources/models/scale_weights.irpa` — splat archive, value 2.0 (committed).
- `src/test/resources/models/scale_weights_zero.irpa` — zeroed archive with real on-disk storage, for the mmap check (committed).
- `native/test/iree_params_test.cpp` — Catch2 suite for the parameter path. Separate file from `iree_runtime_test.cpp` so the spike can be reverted cleanly if it is a no-go.
- `docs/2026-07-25-irpa-spike-findings.md` — the deliverable that actually matters.

**Modified:**
- `native/core/iree_handles.h` — four new deleters + aliases.
- `native/core/iree_runtime.h` — `ParameterScope` struct; 4-arg `Load` overload.
- `native/core/iree_runtime.cpp` — `RuntimeState` gains parameter members; `Load` gains the wiring.
- `native/harness/iree_leak_harness.cpp` — optional parameter-archive argv.
- `native/CMakeLists.txt` — register the new test target and fixture paths.

**Untouched:** everything under `native/jni/`, `src/main/java/`, `example/`.

---

### Task 1: Fixture generation

**Files:**
- Create: `tools/scale.mlir`
- Create: `tools/export_scale.sh`
- Create (generated, committed): `src/test/resources/models/scale.vmfb`, `src/test/resources/models/scale_weights.irpa`, `src/test/resources/models/scale_weights_zero.irpa`

**Interfaces:**
- Consumes: nothing.
- Produces: three fixture files at the paths above. The program's entry point is **`module.scale`** (fully-qualified — IREE defaults the module name to `module` when the MLIR has no module wrapper; see the long comment in `tools/export_add.sh`). The parameter scope the program references is **`model`**; the parameter key is **`weight`**; shape `4xf32`.

- [ ] **Step 1: Write the fixture MLIR**

Create `tools/scale.mlir`:

```mlir
// One parameter-backed global and one function that uses it.
// Deliberately trivial: this exercises the parameter-loading path, not math.
//
// NOTE: the global must REFERENCE a parameter (#stream.parameter.named) rather
// than hold a constant. A constant global is const-eval'd away before the
// parameter-export pass runs, which is why --iree-parameter-export produces no
// archive at all for a constant. Verified 2026-07-25.
util.global private @weight = #stream.parameter.named<"model"::"weight"> : tensor<4xf32>
func.func @scale(%input: tensor<4xf32>) -> tensor<4xf32> {
  %w = util.global.load @weight : tensor<4xf32>
  %result = arith.mulf %input, %w : tensor<4xf32>
  return %result : tensor<4xf32>
}
```

- [ ] **Step 2: Write the export script**

Create `tools/export_scale.sh` (mode 0755):

```bash
#!/usr/bin/env bash
# Regenerates the IRPA spike fixtures. Peer of export_add.sh; see that file for
# the compiler-version pairing rationale (iree-base-compiler MUST be 3.11.0 to
# match the linked runtime commit e4a3b0405d7d23554da26403658d0e8c3c5ecf25).
#
# Entry point is "module.scale" (fully qualified) for the runtime API, but
# iree-run-module's --function= takes the unqualified "scale".
#
# GOTCHA (verified 2026-07-25, iree 3.11.0): `iree-create-parameters
# --data=KEY=SHAPE=PATTERN` is broken -- it writes a 4096-byte stub then fails
# with a misleading "write failed, possibly out of disk space". Use --splat for
# patterned values, or --data=KEY=SHAPE for zeroed storage. Both work.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "${here}" rev-parse --show-toplevel)"
out_dir="${repo_root}/src/test/resources/models"
mkdir -p "${out_dir}"

IREE_COMPILE="${IREE_COMPILE:-${repo_root}/.venv/bin/iree-compile}"
IREE_CREATE_PARAMS="${IREE_CREATE_PARAMS:-${repo_root}/.venv/bin/iree-create-parameters}"

for tool in "${IREE_COMPILE}" "${IREE_CREATE_PARAMS}"; do
  if [[ ! -x "${tool}" ]]; then
    echo "missing ${tool}. Install with:" >&2
    echo "  uv pip install --python ${repo_root}/.venv 'iree-base-compiler==3.11.0'" >&2
    exit 1
  fi
done

# target-cpu=generic keeps the fixture runnable on any x86-64 host and silences
# the generic-CPU perf warning. Perf is irrelevant here.
"${IREE_COMPILE}" \
  --iree-hal-target-device=local \
  --iree-hal-local-target-device-backends=llvm-cpu \
  --iree-llvmcpu-target-cpu=generic \
  "${here}/scale.mlir" -o "${out_dir}/scale.vmfb"

# Splat: value 2.0, NO on-disk storage. Used by the wiring/math tests.
"${IREE_CREATE_PARAMS}" --splat=weight=4xf32=2.0 \
  --output="${out_dir}/scale_weights.irpa"

# Zeroed: real on-disk storage. Used by the mmap check, which needs actual
# mapped bytes -- a splat archive has nothing to map.
"${IREE_CREATE_PARAMS}" --data=weight=4xf32 \
  --output="${out_dir}/scale_weights_zero.irpa"

echo "wrote:"
ls -la "${out_dir}"/scale.vmfb "${out_dir}"/scale_weights*.irpa
```

- [ ] **Step 3: Run the export**

Run: `chmod +x tools/export_scale.sh && ./tools/export_scale.sh`
Expected: three files written; both `iree-create-parameters` invocations print a `Parameter scope <global> (1 entries, 16 total bytes)` banner and exit 0.

- [ ] **Step 4: Verify against the oracle before trusting the fixture**

Run:
```bash
.venv/bin/iree-run-module --module=src/test/resources/models/scale.vmfb \
  --parameters=model=src/test/resources/models/scale_weights.irpa \
  --function=scale --input="4xf32=1,2,3,4"
```
Expected: `result[0]: hal.buffer_view` then `4xf32=2 4 6 8`.

If this fails, **stop** — the fixture is wrong and every later task would be debugging the wrong layer.

- [ ] **Step 5: Record the archive's own view of itself**

Run: `.venv/bin/iree-dump-parameters --parameters=model=src/test/resources/models/scale_weights.irpa`
Expected: reports scope `<global>`, not `model`. Note the output; it is evidence for the "scope is a runtime binding" finding in Task 8.

- [ ] **Step 6: Commit**

```bash
git add tools/scale.mlir tools/export_scale.sh src/test/resources/models/scale.vmfb src/test/resources/models/scale_weights.irpa src/test/resources/models/scale_weights_zero.irpa
git commit -m "test: add IRPA spike fixtures (parameter-backed scale model)"
```

---

### Task 2: RAII handles for the parameter types

**Files:**
- Modify: `native/core/iree_handles.h`

**Interfaces:**
- Consumes: nothing.
- Produces: `measly::iree::FileHandlePtr`, `ParameterIndexPtr`, `ParameterProviderPtr`, `VmModulePtr` — `std::unique_ptr` aliases with release-calling deleters, matching the existing `InstancePtr`/`DevicePtr`/`SessionPtr`/`BufferViewPtr` pattern.

- [ ] **Step 1: Add the deleters and aliases**

In `native/core/iree_handles.h`, add these after `BufferViewDeleter` and before the `using` block. Add `#include "iree/io/parameter_index.h"`, `#include "iree/io/parameter_provider.h"`, and `#include "iree/io/file_handle.h"` at the top alongside the existing `iree/runtime/api.h`:

```cpp
struct FileHandleDeleter {
  void operator()(iree_io_file_handle_t* p) const { iree_io_file_handle_release(p); }
};
struct ParameterIndexDeleter {
  void operator()(iree_io_parameter_index_t* p) const { iree_io_parameter_index_release(p); }
};
struct ParameterProviderDeleter {
  void operator()(iree_io_parameter_provider_t* p) const {
    iree_io_parameter_provider_release(p);
  }
};
struct VmModuleDeleter {
  void operator()(iree_vm_module_t* p) const { iree_vm_module_release(p); }
};
```

And in the `using` block:

```cpp
using FileHandlePtr = std::unique_ptr<iree_io_file_handle_t, FileHandleDeleter>;
using ParameterIndexPtr = std::unique_ptr<iree_io_parameter_index_t, ParameterIndexDeleter>;
using ParameterProviderPtr =
    std::unique_ptr<iree_io_parameter_provider_t, ParameterProviderDeleter>;
using VmModulePtr = std::unique_ptr<iree_vm_module_t, VmModuleDeleter>;
```

- [ ] **Step 2: Verify it still compiles**

Run: `./native/build.sh` (or `cmake --build native/build`)
Expected: clean build. Nothing uses the new aliases yet — this step only proves the headers and release symbols resolve.

- [ ] **Step 3: Commit**

```bash
git add native/core/iree_handles.h
git commit -m "feat(core): add RAII handles for IREE parameter types"
```

---

### Task 3: Wire io_parameters into Load (the load-bearing task)

This is spike questions **Q1/Q2/Q3**. If this task fails after honest effort, the spike has produced its answer and Task 8 writes up a no-go.

**Files:**
- Modify: `native/core/iree_runtime.h`
- Modify: `native/core/iree_runtime.cpp`
- Create: `native/test/iree_params_test.cpp`
- Modify: `native/CMakeLists.txt`

**Interfaces:**
- Consumes: `FileHandlePtr`, `ParameterIndexPtr`, `ParameterProviderPtr`, `VmModulePtr` (Task 2); fixtures (Task 1).
- Produces:
  - `struct measly::iree::ParameterScope { std::string scope; std::string path; };`
  - `static std::unique_ptr<IreeRuntime> IreeRuntime::Load(std::span<const std::byte> vmfb, std::string_view entryPoint, std::string_view driver, std::span<const ParameterScope> parameters);`
  - The existing 3-argument `Load` remains and is unchanged for callers.

- [ ] **Step 1: Declare the public surface**

In `native/core/iree_runtime.h`, add above `class IreeRuntime`:

```cpp
// One parameter archive bound to a scope name. `scope` is the name the compiled
// program references (e.g. "model" for #stream.parameter.named<"model"::"weight">);
// an empty scope binds the archive's global scope. `path` is a filesystem path --
// IRPA is mmap'd from disk by design, so we never marshal the bytes.
//
// NOTE: the scope is a RUNTIME BINDING, not a property of the archive. The same
// .irpa can be bound under any scope name.
struct ParameterScope {
  std::string scope;
  std::string path;
};
```

And inside `class IreeRuntime`, directly below the existing `Load` declaration:

```cpp
  // As above, but also registers parameter archives. Each archive is opened,
  // parsed, and wrapped in a provider; all providers are composed into a single
  // io_parameters VM module which is appended to the session BEFORE the bytecode
  // module so the program's parameter imports resolve.
  static std::unique_ptr<IreeRuntime> Load(std::span<const std::byte> vmfb,
                                           std::string_view entryPoint,
                                           std::string_view driver,
                                           std::span<const ParameterScope> parameters);
```

- [ ] **Step 2: Write the failing test**

Create `native/test/iree_params_test.cpp`:

```cpp
#include <catch2/catch_test_macros.hpp>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iterator>
#include <span>
#include <string>
#include <vector>
#include "core/iree_runtime.h"

using measly::iree::InputDesc;
using measly::iree::IreeRuntime;
using measly::iree::ParameterScope;

namespace {
std::vector<std::byte> ReadFile(const char* path) {
  std::ifstream in(path, std::ios::binary);
  REQUIRE(in.good());
  std::vector<char> raw((std::istreambuf_iterator<char>(in)),
                        std::istreambuf_iterator<char>());
  std::vector<std::byte> bytes(raw.size());
  std::memcpy(bytes.data(), raw.data(), raw.size());
  return bytes;
}
// Set by CMake; see native/CMakeLists.txt.
constexpr const char* kScaleVmfb = IREE_DJL_SCALE_VMFB;
constexpr const char* kScaleIrpa = IREE_DJL_SCALE_IRPA;
constexpr const char* kEntryPoint = "module.scale";
constexpr int32_t kF32 = 0x21000020;  // IREE_HAL_ELEMENT_TYPE_FLOAT_32
}  // namespace

TEST_CASE("loads a vmfb with a parameter archive", "[params]") {
  auto bytes = ReadFile(kScaleVmfb);
  const ParameterScope scopes[] = {{"model", kScaleIrpa}};
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint, "local-sync", scopes);
  REQUIRE(runtime != nullptr);
}

TEST_CASE("golden vector: parameter-backed scale", "[params]") {
  auto bytes = ReadFile(kScaleVmfb);
  const ParameterScope scopes[] = {{"model", kScaleIrpa}};
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint, "local-sync", scopes);

  const std::vector<float> input = {1.0f, 2.0f, 3.0f, 4.0f};
  InputDesc desc;
  desc.data = input.data();
  desc.nbytes = input.size() * sizeof(float);
  desc.shape = {4};
  desc.elementType = kF32;

  const InputDesc inputs[] = {desc};
  auto outputs = runtime->Invoke(inputs);

  REQUIRE(outputs.size() == 1);
  REQUIRE(outputs[0].shape == std::vector<int64_t>{4});
  REQUIRE(outputs[0].data.size() == 4 * sizeof(float));

  std::vector<float> got(4);
  std::memcpy(got.data(), outputs[0].data.data(), outputs[0].data.size());
  // The archive is a splat of 2.0, so the program computes input * 2.
  // Matches the iree-run-module oracle: 4xf32=1,2,3,4 -> 2 4 6 8.
  const std::vector<float> want = {2.0f, 4.0f, 6.0f, 8.0f};
  for (size_t i = 0; i < want.size(); ++i) {
    REQUIRE(std::fabs(got[i] - want[i]) < 1e-6f);
  }
}

TEST_CASE("loading without the required parameters fails", "[params]") {
  auto bytes = ReadFile(kScaleVmfb);
  // No archives supplied: the program's parameter import cannot resolve.
  // Asserting it THROWS rather than silently producing garbage is the point.
  REQUIRE_THROWS(IreeRuntime::Load(bytes, kEntryPoint, "local-sync",
                                   std::span<const ParameterScope>{}));
}
```

- [ ] **Step 3: Register the test target**

In `native/CMakeLists.txt`, after the existing `iree_runtime_test` block:

```cmake
add_executable(iree_params_test test/iree_params_test.cpp)
target_link_libraries(iree_params_test PRIVATE iree_djl_core Catch2::Catch2WithMain)
target_compile_definitions(iree_params_test PRIVATE
    IREE_DJL_SCALE_VMFB="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/scale.vmfb"
    IREE_DJL_SCALE_IRPA="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/scale_weights.irpa"
    IREE_DJL_SCALE_IRPA_ZERO="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/scale_weights_zero.irpa")
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./native/build.sh && ./native/build/iree_params_test`
Expected: **compile failure** — no 4-argument `Load` is defined yet. That is the correct failure.

- [ ] **Step 5: Implement the wiring**

In `native/core/iree_runtime.cpp`:

Add includes at the top, after the existing ones:

```cpp
#include "iree/io/file_handle.h"
#include "iree/io/formats/parser_registry.h"
#include "iree/io/parameter_index.h"
#include "iree/io/parameter_index_provider.h"
#include "iree/modules/io/parameters/module.h"
```

Extend `RuntimeState`. **Field order matters** — members are destroyed in reverse declaration order, so anything the session may still reference must be declared *before* the session:

```cpp
struct RuntimeState {
  // Owns a copy of the flatbuffer. append_bytecode_module_from_memory with a
  // null allocator does NOT copy, so these bytes must outlive the session.
  // Declared first so it is destroyed last.
  std::vector<std::byte> vmfb;
  std::string entryPoint;
  InstancePtr instance;
  DevicePtr device;
  // Parameter chain. Declared BEFORE `session` so all of it outlives the
  // session: the io_parameters module is retained by the context, but whether
  // the provider/index/file-handle chain is transitively retained is exactly
  // what Task 5 probes. Holding them here is the conservative starting point.
  std::vector<FileHandlePtr> paramFiles;
  std::vector<ParameterIndexPtr> paramIndices;
  std::vector<ParameterProviderPtr> paramProviders;
  VmModulePtr paramsModule;
  SessionPtr session;
  std::vector<IreeRuntime::ImportOutcome> lastImportOutcomes;
};
```

Keep the existing 3-argument `Load` as a thin forwarder, and add the real one:

```cpp
std::unique_ptr<IreeRuntime> IreeRuntime::Load(std::span<const std::byte> vmfb,
                                               std::string_view entryPoint,
                                               std::string_view driver) {
  return Load(vmfb, entryPoint, driver, std::span<const ParameterScope>{});
}

std::unique_ptr<IreeRuntime> IreeRuntime::Load(
    std::span<const std::byte> vmfb, std::string_view entryPoint,
    std::string_view driver, std::span<const ParameterScope> parameters) {
  auto state = std::make_unique<RuntimeState>();
  state->vmfb.assign(vmfb.begin(), vmfb.end());
  state->entryPoint = std::string(entryPoint);

  iree_runtime_instance_options_t options;
  iree_runtime_instance_options_initialize(&options);
  iree_runtime_instance_options_use_all_available_drivers(&options);

  iree_runtime_instance_t* raw_instance = nullptr;
  IREE_CHECK_OR_THROW(iree_runtime_instance_create(
      &options, iree_allocator_system(), &raw_instance));
  state->instance.reset(raw_instance);

  iree_hal_device_t* raw_device = nullptr;
  // Copy into a std::string: std::string_view is not guaranteed null-terminated.
  std::string driver_name(driver);
  IREE_CHECK_OR_THROW(iree_runtime_instance_try_create_default_device(
      state->instance.get(),
      iree_make_string_view(driver_name.data(), driver_name.size()),
      &raw_device));
  state->device.reset(raw_device);

  iree_runtime_session_options_t session_options;
  iree_runtime_session_options_initialize(&session_options);
  iree_runtime_session_t* raw_session = nullptr;
  IREE_CHECK_OR_THROW(iree_runtime_session_create_with_device(
      state->instance.get(), &session_options, state->device.get(),
      iree_allocator_system(), &raw_session));
  state->session.reset(raw_session);

  // Parameter archives, if any. This MUST happen before the bytecode module is
  // appended: import resolution runs when the bytecode module is registered
  // against the modules already in the context.
  if (!parameters.empty()) {
    std::vector<iree_io_parameter_provider_t*> raw_providers;
    raw_providers.reserve(parameters.size());

    for (const auto& param : parameters) {
      // 1. Open the archive. RANDOM_ACCESS is the mmap-friendly mode.
      iree_io_file_handle_t* raw_file = nullptr;
      IREE_CHECK_OR_THROW(iree_io_file_handle_open(
          IREE_IO_FILE_MODE_READ | IREE_IO_FILE_MODE_RANDOM_ACCESS,
          iree_make_string_view(param.path.data(), param.path.size()),
          iree_allocator_system(), &raw_file));
      state->paramFiles.emplace_back(raw_file);

      // 2. Parse it into an index. The path is passed too so the registry can
      //    pick a parser by extension.
      iree_io_parameter_index_t* raw_index = nullptr;
      IREE_CHECK_OR_THROW(
          iree_io_parameter_index_create(iree_allocator_system(), &raw_index));
      state->paramIndices.emplace_back(raw_index);
      IREE_CHECK_OR_THROW(iree_io_parse_file_index(
          iree_make_string_view(param.path.data(), param.path.size()),
          state->paramFiles.back().get(), state->paramIndices.back().get(),
          iree_allocator_system()));

      // 3. Wrap in a provider bound to the caller's scope name.
      iree_io_parameter_provider_t* raw_provider = nullptr;
      IREE_CHECK_OR_THROW(iree_io_parameter_index_provider_create(
          iree_make_string_view(param.scope.data(), param.scope.size()),
          state->paramIndices.back().get(),
          IREE_IO_PARAMETER_INDEX_PROVIDER_DEFAULT_MAX_CONCURRENT_OPERATIONS,
          iree_allocator_system(), &raw_provider));
      state->paramProviders.emplace_back(raw_provider);
      raw_providers.push_back(state->paramProviders.back().get());
    }

    // 4. One module for all providers -- module_create takes an array, so
    //    multiple scopes need one module, not several.
    iree_vm_module_t* raw_module = nullptr;
    IREE_CHECK_OR_THROW(iree_io_parameters_module_create(
        iree_runtime_instance_vm_instance(state->instance.get()),
        raw_providers.size(), raw_providers.data(), iree_allocator_system(),
        &raw_module));
    state->paramsModule.reset(raw_module);

    // 5. Append BEFORE the bytecode module.
    IREE_CHECK_OR_THROW(iree_runtime_session_append_module(
        state->session.get(), state->paramsModule.get()));
  }

  IREE_CHECK_OR_THROW(iree_runtime_session_append_bytecode_module_from_memory(
      state->session.get(),
      iree_make_const_byte_span(state->vmfb.data(), state->vmfb.size()),
      iree_allocator_null()));

  // Fail fast at load time if the entry point does not exist.
  iree_vm_function_t function;
  IREE_CHECK_OR_THROW(iree_runtime_session_lookup_function(
      state->session.get(),
      iree_make_string_view(state->entryPoint.data(), state->entryPoint.size()),
      &function));

  return std::make_unique<IreeRuntime>(std::move(state));
}
```

- [ ] **Step 6: Run the tests**

Run: `./native/build.sh && ./native/build/iree_params_test`
Expected: all three test cases PASS.

**If "loads a vmfb with a parameter archive" fails**, this is spike question Q2 — append ordering. Before concluding anything, try: (a) appending the params module before `session_create_with_device` is impossible, so instead (b) check whether the error names an unresolved import (ordering/wiring problem) versus a missing symbol (link problem) versus a scope mismatch (fixture problem, re-verify with the oracle from Task 1 Step 4). Record whichever it is — the *error text* is the finding.

- [ ] **Step 7: Verify no regression**

Run: `./native/build/iree_runtime_test`
Expected: PASS. The 3-argument `Load` forwarder must not have changed existing behaviour.

- [ ] **Step 8: Commit**

```bash
git add native/core/iree_runtime.h native/core/iree_runtime.cpp native/test/iree_params_test.cpp native/CMakeLists.txt
git commit -m "feat(core): load IRPA parameter archives via the high-level session"
```

---

### Task 4: Multiple scopes

Spike question **Q7**. The manifest contract promises a scope→file *map*, so one-archive support is not enough to validate it.

**Files:**
- Create: `tools/scale2.mlir` (added to `tools/export_scale.sh`)
- Create: `src/test/resources/models/scale2.vmfb`, `src/test/resources/models/scale2_bias.irpa`
- Modify: `tools/export_scale.sh`
- Modify: `native/test/iree_params_test.cpp`
- Modify: `native/CMakeLists.txt`

**Interfaces:**
- Consumes: the 4-argument `Load` (Task 3).
- Produces: fixture `scale2.vmfb` with entry point `module.scale2`, referencing **two** scopes — `model::weight` (4xf32) and `bias::offset` (4xf32).

- [ ] **Step 1: Write the two-scope MLIR**

Create `tools/scale2.mlir`:

```mlir
// Two parameters in DIFFERENT scopes, to prove a provider array composes.
util.global private @weight = #stream.parameter.named<"model"::"weight"> : tensor<4xf32>
util.global private @offset = #stream.parameter.named<"bias"::"offset"> : tensor<4xf32>
func.func @scale2(%input: tensor<4xf32>) -> tensor<4xf32> {
  %w = util.global.load @weight : tensor<4xf32>
  %b = util.global.load @offset : tensor<4xf32>
  %scaled = arith.mulf %input, %w : tensor<4xf32>
  %result = arith.addf %scaled, %b : tensor<4xf32>
  return %result : tensor<4xf32>
}
```

- [ ] **Step 2: Extend the export script**

Append to `tools/export_scale.sh`, before the final `echo "wrote:"`:

```bash
"${IREE_COMPILE}" \
  --iree-hal-target-device=local \
  --iree-hal-local-target-device-backends=llvm-cpu \
  --iree-llvmcpu-target-cpu=generic \
  "${here}/scale2.mlir" -o "${out_dir}/scale2.vmfb"

# Second archive, bound under a different scope at load time.
"${IREE_CREATE_PARAMS}" --splat=offset=4xf32=10.0 \
  --output="${out_dir}/scale2_bias.irpa"
```

And add `"${out_dir}"/scale2.vmfb` to the closing `ls -la`.

- [ ] **Step 3: Regenerate and verify against the oracle**

Run:
```bash
./tools/export_scale.sh
.venv/bin/iree-run-module --module=src/test/resources/models/scale2.vmfb \
  --parameters=model=src/test/resources/models/scale_weights.irpa \
  --parameters=bias=src/test/resources/models/scale2_bias.irpa \
  --function=scale2 --input="4xf32=1,2,3,4"
```
Expected: `4xf32=12 14 16 18` (input × 2 + 10).

If the oracle rejects two `--parameters=` flags, that is a genuine finding about multi-scope support — record it and stop this task.

- [ ] **Step 4: Add the CMake definitions**

In `native/CMakeLists.txt`, add to `iree_params_test`'s `target_compile_definitions`:

```cmake
    IREE_DJL_SCALE2_VMFB="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/scale2.vmfb"
    IREE_DJL_SCALE2_BIAS_IRPA="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/scale2_bias.irpa"
```

- [ ] **Step 5: Write the failing test**

Append to `native/test/iree_params_test.cpp`:

```cpp
TEST_CASE("two archives bound to two scopes", "[params]") {
  auto bytes = ReadFile(IREE_DJL_SCALE2_VMFB);
  const ParameterScope scopes[] = {
      {"model", kScaleIrpa},
      {"bias", IREE_DJL_SCALE2_BIAS_IRPA},
  };
  auto runtime = IreeRuntime::Load(bytes, "module.scale2", "local-sync", scopes);

  const std::vector<float> input = {1.0f, 2.0f, 3.0f, 4.0f};
  InputDesc desc;
  desc.data = input.data();
  desc.nbytes = input.size() * sizeof(float);
  desc.shape = {4};
  desc.elementType = kF32;

  const InputDesc inputs[] = {desc};
  auto outputs = runtime->Invoke(inputs);

  REQUIRE(outputs.size() == 1);
  std::vector<float> got(4);
  std::memcpy(got.data(), outputs[0].data.data(), outputs[0].data.size());
  // input * 2 (model::weight splat) + 10 (bias::offset splat)
  const std::vector<float> want = {12.0f, 14.0f, 16.0f, 18.0f};
  for (size_t i = 0; i < want.size(); ++i) {
    REQUIRE(std::fabs(got[i] - want[i]) < 1e-6f);
  }
}
```

- [ ] **Step 6: Run it**

Run: `./native/build.sh && ./native/build/iree_params_test`
Expected: PASS with no implementation change — Task 3's loop already builds a provider array. If it passes untouched, that *is* the Q7 answer.

- [ ] **Step 7: Commit**

```bash
git add tools/scale2.mlir tools/export_scale.sh src/test/resources/models/scale2.vmfb src/test/resources/models/scale2_bias.irpa native/test/iree_params_test.cpp native/CMakeLists.txt
git commit -m "test: prove multi-scope parameter archives compose"
```

---

### Task 5: Probe the ownership chain

Spike questions **Q4/Q5** — the real payload. Task 3 conservatively holds *everything* in `RuntimeState`. This task determines what is actually required, by deliberately dropping references and seeing what breaks under ASan.

**Files:**
- Modify: `native/core/iree_runtime.cpp` (experimentally, then settle)
- Create: `docs/2026-07-25-irpa-spike-findings.md` (started here, finished in Task 8)

**Interfaces:**
- Consumes: everything from Task 3.
- Produces: a documented answer to "what must `RuntimeState` hold, and in what order" — and, if references can safely be dropped, a smaller `RuntimeState`.

- [ ] **Step 1: Establish a clean ASan baseline**

Run:
```bash
cmake -S native -B native/asan -G Ninja -DIREE_DJL_SANITIZE=ON
cmake --build native/asan
./native/asan/iree_params_test
```
Expected: tests PASS, **zero** LeakSanitizer reports. If the baseline already leaks, fix that before probing — you cannot read a differential against a dirty baseline.

- [ ] **Step 2: Probe — drop the module reference after append**

`session.h:143` says "The module will be retained by the context." Test it. In `Load`, immediately after `iree_runtime_session_append_module` succeeds, replace `state->paramsModule.reset(raw_module)` retention with a local that releases at end of scope:

```cpp
    // PROBE: does the context's retain make our reference redundant?
    // Replace `state->paramsModule.reset(raw_module);` with a scoped local.
    VmModulePtr scoped_module(raw_module);
    IREE_CHECK_OR_THROW(iree_runtime_session_append_module(
        state->session.get(), scoped_module.get()));
    // scoped_module releases here, before Load returns.
```

Run: `cmake --build native/asan && ./native/asan/iree_params_test`
Record: PASS + clean, or use-after-free, or leak. **Write the result down immediately** — this is the deliverable, not the code.

- [ ] **Step 3: Probe — drop the provider references**

Restore Step 2's change if it failed. Now test whether the module retains the providers: scope `state->paramProviders` locally so they release once `iree_io_parameters_module_create` has returned.

Run: `cmake --build native/asan && ./native/asan/iree_params_test`
Record the result.

- [ ] **Step 4: Probe — drop the index references**

Test whether the provider retains the index: scope `state->paramIndices` locally, releasing after `iree_io_parameter_index_provider_create` returns.

Run: `cmake --build native/asan && ./native/asan/iree_params_test`
Record the result.

- [ ] **Step 5: Probe — drop the file-handle references**

Test whether the index retains the file handle: scope `state->paramFiles` locally, releasing after `iree_io_parse_file_index` returns.

**Predict before running:** this is the one most likely to fail, because the mmap must stay alive for lazy parameter reads. A failure here is *expected and informative*, not a bug.

Run: `cmake --build native/asan && ./native/asan/iree_params_test`
Record the result.

- [ ] **Step 6: Settle on the minimal correct RuntimeState**

Keep only the members the probes proved necessary. Add a comment above the parameter block stating **what was empirically verified**, in the style of the existing `vmfb` comment — e.g.:

```cpp
  // Parameter chain. VERIFIED 2026-07-25 by deliberate-drop probing under ASan:
  // <fill in exactly what the probes showed, e.g. "the context retains the
  // module, and the module retains the providers, but the file handle must be
  // held here: releasing it after parse produces a use-after-free on first
  // parameter read.">
  // Declared before `session` so it is destroyed after it.
```

Run: `cmake --build native/asan && ./native/asan/iree_params_test && ./native/asan/iree_runtime_test`
Expected: PASS, zero leaks, both suites.

- [ ] **Step 7: Start the findings document**

Create `docs/2026-07-25-irpa-spike-findings.md` with an `## Ownership chain (Q4/Q5)` section recording, for each of the four handle types: *retained by whom, must we hold it, and what the failure mode was when we did not.* A table is the right shape.

- [ ] **Step 8: Commit**

```bash
git add native/core/iree_runtime.cpp docs/2026-07-25-irpa-spike-findings.md
git commit -m "fix(core): minimal verified ownership for the parameter chain"
```

---

### Task 6: Error behaviour and the zero-byte case

Spike question **Q9**. The manifest contract's existence-only check plus the documented `touch` bypass guarantees a zero-byte `.irpa` can reach IREE. This task confirms that failure is diagnosable rather than a crash.

**Files:**
- Modify: `native/test/iree_params_test.cpp`

**Interfaces:**
- Consumes: the 4-argument `Load`.
- Produces: recorded error strings for four failure modes.

- [ ] **Step 1: Write the failing tests**

Append to `native/test/iree_params_test.cpp`:

```cpp
#include <cstdio>

namespace {
// Writes a temp file with the given contents and returns its path. Caller is
// responsible for nothing -- these are tiny and land in the build dir.
std::string WriteTempFile(const char* name, const char* data, size_t len) {
  std::string path = std::string("./") + name;
  std::ofstream out(path, std::ios::binary | std::ios::trunc);
  REQUIRE(out.good());
  if (len > 0) out.write(data, static_cast<std::streamsize>(len));
  out.close();
  return path;
}
}  // namespace

TEST_CASE("missing archive file throws", "[params][errors]") {
  auto bytes = ReadFile(kScaleVmfb);
  const ParameterScope scopes[] = {{"model", "/nonexistent/nope.irpa"}};
  REQUIRE_THROWS(IreeRuntime::Load(bytes, kEntryPoint, "local-sync", scopes));
}

TEST_CASE("zero-byte archive throws rather than crashing", "[params][errors]") {
  // This is the `touch` bypass case from the manifest contract: existence-only
  // checking means an empty file WILL reach IREE. It must fail cleanly.
  auto path = WriteTempFile("empty.irpa", "", 0);
  auto bytes = ReadFile(kScaleVmfb);
  const ParameterScope scopes[] = {{"model", path.c_str()}};
  REQUIRE_THROWS(IreeRuntime::Load(bytes, kEntryPoint, "local-sync", scopes));
}

TEST_CASE("truncated archive throws rather than crashing", "[params][errors]") {
  auto path = WriteTempFile("truncated.irpa", "IRPA\x00\x00\x00\x00", 8);
  auto bytes = ReadFile(kScaleVmfb);
  const ParameterScope scopes[] = {{"model", path.c_str()}};
  REQUIRE_THROWS(IreeRuntime::Load(bytes, kEntryPoint, "local-sync", scopes));
}

TEST_CASE("wrong scope name throws", "[params][errors]") {
  auto bytes = ReadFile(kScaleVmfb);
  // Archive is fine, but bound under a scope the program does not reference.
  const ParameterScope scopes[] = {{"not_the_model", kScaleIrpa}};
  REQUIRE_THROWS(IreeRuntime::Load(bytes, kEntryPoint, "local-sync", scopes));
}
```

- [ ] **Step 2: Run them**

Run: `./native/build.sh && ./native/build/iree_params_test "[errors]"`
Expected: all four PASS (i.e. all four throw).

Any that **crashes** instead of throwing is a significant finding — it means the existence-only manifest check is insufficient and the contract needs revisiting. Stop and record it.

- [ ] **Step 3: Capture the actual error messages**

Run: `./native/build/iree_params_test "[errors]" -s 2>&1 | tee /tmp/irpa-errors.txt`

For each case, copy the `std::runtime_error` message into the findings document under `## Error behaviour (Q9)`. The question the document must answer: *could an operator diagnose the problem from this string alone?*

- [ ] **Step 4: Confirm under ASan too**

Run: `cmake --build native/asan && ./native/asan/iree_params_test "[errors]"`
Expected: PASS, zero leaks. Error paths are where statuses leak — this is exactly the case `iree_status.h` warns about.

- [ ] **Step 5: Commit**

```bash
git add native/test/iree_params_test.cpp docs/2026-07-25-irpa-spike-findings.md
git commit -m "test: pin IRPA error behaviour incl. the zero-byte bypass case"
```

---

### Task 7: Is the mmap real?

Spike question **Q8**. mmap is the entire justification for the path-based manifest contract. If IREE reads archives into host memory instead, the contract still stands but its rationale must be reworded.

**Files:**
- Modify: `native/harness/iree_leak_harness.cpp`
- Modify: `docs/2026-07-25-irpa-spike-findings.md`

**Interfaces:**
- Consumes: the 4-argument `Load`; the zeroed archive `scale_weights_zero.irpa` (splat archives have no on-disk storage and cannot demonstrate mapping).
- Produces: a recorded answer with evidence.

- [ ] **Step 1: Extend the leak harness to take an archive**

In `native/harness/iree_leak_harness.cpp`, accept an optional 4th argv as `scope=path` and pass it through as a `ParameterScope`. Keep the existing argv contract intact so `native/tsan_gate.sh` and any existing invocations still work:

```cpp
  // argv[4], if present, is "scope=path" for a parameter archive.
  std::vector<measly::iree::ParameterScope> params;
  if (argc > 4) {
    std::string spec(argv[4]);
    auto eq = spec.find('=');
    if (eq == std::string::npos) {
      std::fprintf(stderr, "expected scope=path, got %s\n", argv[4]);
      std::exit(64);
    }
    params.push_back({spec.substr(0, eq), spec.substr(eq + 1)});
  }
```

and pass `params` to the 4-argument `Load` in the cycle function.

- [ ] **Step 2: Build and run it against the zeroed archive**

Run:
```bash
./native/build.sh
./native/build/iree_leak_harness src/test/resources/models/scale2.vmfb 50 local-sync \
  "model=$(pwd)/src/test/resources/models/scale_weights_zero.irpa"
```
Expected: completes without error. (Note: `scale2.vmfb` needs two scopes; if the harness supports only one archive, use `scale.vmfb` with entry point `module.scale` instead — adjust the harness's hardcoded `kEntryPoint` accordingly.)

- [ ] **Step 3: Check for an actual mapping**

Run:
```bash
./native/build/iree_leak_harness src/test/resources/models/scale.vmfb 100000 local-sync \
  "model=$(pwd)/src/test/resources/models/scale_weights_zero.irpa" &
HARNESS_PID=$!
sleep 1
grep -c "scale_weights_zero.irpa" /proc/$HARNESS_PID/maps || echo "NOT MAPPED"
kill $HARNESS_PID
```
Expected: a non-zero count means IREE genuinely `mmap`s the archive. `NOT MAPPED` means it reads into host memory.

Either answer is acceptable and useful — record which.

- [ ] **Step 4: Confirm no leak with parameters in play**

Run:
```bash
cmake --build native/asan
./native/asan/iree_leak_harness src/test/resources/models/scale.vmfb 50 local-sync \
  "model=$(pwd)/src/test/resources/models/scale_weights_zero.irpa"
```
Expected: zero LeakSanitizer reports across 50 load/invoke/close cycles. **This is the strongest single signal in the whole spike** — it exercises the full parameter lifetime repeatedly.

- [ ] **Step 5: Record and commit**

Add an `## mmap (Q8)` section to the findings document with the `/proc/<pid>/maps` evidence.

```bash
git add native/harness/iree_leak_harness.cpp docs/2026-07-25-irpa-spike-findings.md
git commit -m "test: exercise parameter archives in the leak harness"
```

---

### Task 8: Findings write-up and the go/no-go call

The spike's actual deliverable. Everything before this was evidence-gathering.

**Files:**
- Modify: `docs/2026-07-25-irpa-spike-findings.md`
- Modify: `docs/2026-07-22-irpa-and-target-selection-scoping-notes.md`
- Modify: `docs/panama-research-sketch.md`

**Interfaces:**
- Consumes: recorded results from Tasks 3-7.
- Produces: a go/no-go recommendation on IRPA, and therefore on the manifest contract.

- [ ] **Step 1: Complete the findings document**

`docs/2026-07-25-irpa-spike-findings.md` must contain, in this order:

1. **Verdict** — go or no-go on IRPA, in one sentence, first.
2. **Q1/Q2/Q3 — wiring.** Did `append_module` before the bytecode module resolve imports? Did order matter relative to the internally-registered HAL module? Did it fit inside `Load` without restructuring?
3. **Q4/Q5 — ownership.** The table from Task 5. This is the section the production implementation will actually be read for.
4. **Q6 — scope naming.** Confirm the pre-established finding: scope is a *runtime binding*, the archive reports `<global>`. State what this means for the manifest's scope→file map.
5. **Q7 — multiple scopes.** Did the provider array compose without code changes?
6. **Q8 — mmap.** Mapped or copied, with the `/proc` evidence.
7. **Q9 — errors.** The four error strings, and a judgement on whether each is operator-diagnosable.
8. **Cost estimate for production IRPA** — now that the shape is known, how much work beyond this spike?

- [ ] **Step 2: Make the go/no-go call explicit**

State the recommendation against this bar, drawn from the spike's framing:

- **GO** if: wiring works on the high-level API, the ownership chain is expressible in `iree_handles.h` with rules a maintainer can state in a sentence, and the leak harness is clean across repeated cycles.
- **NO-GO** if: it requires the low-level `iree_vm_context` after all, *or* the ownership rules cannot be stated without conditionals, *or* the harness leaks in ways that are not straightforwardly fixable.

Then follow the consequence through, explicitly:

- **On GO:** the manifest contract is justified. Proceed to the load-options ABI shape in `panama-research-sketch.md`.
- **On NO-GO:** the manifest is too much complexity for `.vmfb` tiering alone. Recommend reverting the manifest contract in favour of a plain directory convention (glibc-hwcaps style: tier subdirectories, loose files), which was the pre-manifest leaning. Say so plainly rather than leaving it implied.

- [ ] **Step 3: Update the scoping notes**

In `docs/2026-07-22-irpa-and-target-selection-scoping-notes.md`:
- Part 1's "Big rock" section: replace the "DISPROVEN — pending spike" framing with the empirical result, linking to the findings doc.
- Part 4: mark the spike complete and link the findings.
- If NO-GO: mark the Part 3 manifest contract as **superseded**, with the reason. Do not delete it — the reasoning stays useful.

- [ ] **Step 4: Update the Panama sketch**

In `docs/panama-research-sketch.md`:
- The "IRPA is what makes direct binding indefensible" section carries an explicit note that the handle *count* was unverified pending this spike's Q4/Q5. Replace that hedge with the verified count.
- "Remaining work" item 8 (IRPA fixture for the leak harness) is now done — mark it and point at the fixtures.
- If NO-GO: revisit the load-options ABI recommendation, which is shaped around parameter scopes.

- [ ] **Step 5: Commit**

```bash
git add docs/2026-07-25-irpa-spike-findings.md docs/2026-07-22-irpa-and-target-selection-scoping-notes.md docs/panama-research-sketch.md
git commit -m "docs: IRPA spike findings and go/no-go call"
```

---

## Self-review notes

**Spike coverage:** Q1/Q2/Q3 → Task 3. Q4/Q5 → Task 5. Q6 → pre-established, confirmed in Task 8 Step 1. Q7 → Task 4. Q8 → Task 7. Q9 → Task 6. Fixture prerequisite → Task 1. Go/no-go → Task 8.

**Deliberate ordering:** Task 3 is the gate — if it fails, Tasks 4-7 are moot and the executor should jump straight to Task 8 and write the no-go. This is stated in Task 3's preamble.

**Type consistency:** `ParameterScope{scope, path}` is declared in Task 3 Step 1 and used identically in Tasks 4, 6, and 7. The 4-argument `Load` signature is fixed in Task 3 Step 1 and unchanged thereafter. Handle aliases from Task 2 are used only in Task 3 and Task 5.

**Known soft spot:** Task 7 Step 2 hardcodes `kEntryPoint` in the leak harness, which currently assumes `module.add`. The step flags this and gives the workaround. It is called out rather than silently assumed.
