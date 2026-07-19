# DJL IREE Engine Walking Skeleton — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run a trivial `add.vmfb` model end-to-end through DJL on an IREE runtime, with the native layer's lifetime risks proven clean under ASan/LSan before any JVM code exists.

**Architecture:** Three layers, mirroring `/home/corey/workspace/djl-executorch-engine`. A JNIEnv-free C++ core (`measly::iree::IreeRuntime`) imposes RAII over IREE's C API — one owning wrapper per handle type, one status-consuming macro. A thin JNI shim marshals across the boundary with an opaque `jlong` handle. A minimal DJL engine sits on top. The core is linked by the shim, the Catch2 tests, and the leak harness alike, so sanitizer runs never involve a JVM.

**Tech Stack:** C++20, CMake + Ninja, Catch2 v3, IREE runtime (static, `local-sync` driver), JNI, Java 17, Gradle Kotlin DSL, DJL 0.36.0, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-07-19-djl-iree-engine-skeleton-design.md`

## Global Constraints

- **JDK floor: 17.** Classic hand-written JNI. No Panama/FFM.
- **C++ standard: 20**, stated explicitly in CMake (`CMAKE_CXX_STANDARD 20`, `CMAKE_CXX_STANDARD_REQUIRED ON`). Do not drop these as redundant — ExecuTorch hit a hard `#error` on MSVC from exactly this omission.
- **Platform: linux-x86_64 only.** Host build. No container, no Windows, no glibc-floor discipline.
- **Coordinates:** `org.measly:djl-iree-engine`. Java package `org.measly.iree`. Native lib `libiree_djl.so`. Type prefix `Iree`.
- **IREE runtime source:** link the local build via `IREE_INSTALL`, defaulting to `/home/corey/workspace/iree-build`, headers from `/home/corey/workspace/iree` (`IREE_SOURCE`). Nothing is installed to a prefix, so there is **no** `IREERuntimeConfig.cmake` — link the static archives directly.
- **HAL driver: `local-sync`.** No IREE-internal threads. **No TSan leg anywhere in this plan.**
- **`iree-compile` comes from pip** (`iree-base-compiler`), never from the local build (`IREE_BUILD_COMPILER=OFF` there). Never link the compiler.
- **Every `iree_status_t` must be consumed.** Non-OK statuses are heap objects; dropping one is a leak. All status handling goes through `IREE_CHECK_OR_THROW`.
- **No IREE handle outlives `Invoke`.** Outputs are copied into owning `std::vector<std::byte>` before returning.
- Do not modify `/home/corey/workspace/iree` or `/home/corey/workspace/iree-build`. They are read-only inputs.

## Corrections to the spec found during header verification

These were verified against the installed headers and **supersede** the corresponding spec text:

1. **`iree_runtime_call_t` is a value type, not refcounted.** It is initialized in place by `iree_runtime_call_initialize_by_name(session, name, out_call)` and torn down with `void iree_runtime_call_deinitialize(iree_runtime_call_t*)`. There is no `iree_runtime_call_release`. Its RAII wrapper is therefore a **scope guard over a stack value**, not a `unique_ptr` with a release deleter. The spec listed "call" among the `unique_ptr`-wrapped handles; that is wrong.
2. **Loading from memory does not copy.** `iree_runtime_session_append_bytecode_module_from_memory(session, flatbuffer_data, flatbuffer_allocator)` takes an allocator; passing `iree_allocator_null()` means IREE does **not** take a copy and the caller's bytes must outlive the session. `IreeRuntime` therefore **owns a copy** of the `.vmfb` bytes in a member.
3. `iree_hal_buffer_view_allocate_buffer_copy` lives in **`iree/hal/buffer_view_util.h`**, not `buffer_view.h`.
4. Copy-out uses **`iree_hal_buffer_map_read(buffer, offset, target, length)`** — a straight copy, simpler than `map_range`/`unmap_range`.
5. The import path uses `iree_hal_external_buffer_t` with `type = IREE_HAL_EXTERNAL_BUFFER_TYPE_HOST_ALLOCATION` and the union member **`host_allocation.ptr`**.

## File Structure

| File | Responsibility |
|---|---|
| `native/CMakeLists.txt` | Resolves IREE, defines core/shim/test/harness targets |
| `native/cmake/IreeRuntimePin.cmake` | Stub dist-repo seam; `IREE_INSTALL` short-circuits it |
| `native/cmake/ResolveIree.cmake` | Turns `IREE_INSTALL`/`IREE_SOURCE` into one `iree::runtime` interface target |
| `native/core/iree_status.h` | `IREE_CHECK_OR_THROW` — the single status-consuming guard |
| `native/core/iree_handles.h` | One RAII wrapper per IREE handle type |
| `native/core/iree_runtime.h` | Public facade API — the only header the shim/tests include |
| `native/core/iree_runtime.cpp` | Facade implementation (pimpl `RuntimeState`) |
| `native/jni/iree_djl_jni.cpp` | JNI shim; `jlong` handle, exception translation |
| `native/test/iree_runtime_test.cpp` | Catch2 golden vector + error paths + import outcome |
| `native/harness/iree_leak_harness.cpp` | ASan/LSan `main()`; loops load/invoke/close and error paths |
| `native/build.sh` | Host build + stage `.so` into resources |
| `tools/export_add.sh` | Produces `add.vmfb` via pip `iree-compile` |
| `src/main/java/org/measly/iree/jni/IreeNative.java` | `native` declarations; loads the `.so` |
| `src/main/java/org/measly/iree/jni/IreeTensor.java` | Marshalling struct |
| `src/main/java/org/measly/iree/engine/LibUtils.java` | Locates and loads the native library |
| `src/main/java/org/measly/iree/engine/Iree*.java` | DJL SPI implementation |

Split rationale: `iree_status.h` and `iree_handles.h` are separated from `iree_runtime.cpp` because both are pure header-only policy that the tests assert on directly, and keeping them out of the implementation file keeps that file focused on call sequencing.

---

### Task 1: Build seam — link the IREE runtime and prove it

**Files:**
- Create: `native/CMakeLists.txt`, `native/cmake/ResolveIree.cmake`, `native/cmake/IreeRuntimePin.cmake`, `native/build.sh`
- Create: `native/test/link_smoke_test.cpp`

**Interfaces:**
- Consumes: nothing
- Produces: CMake interface target `iree::runtime` (include dirs + static archives); CMake option `IREE_INSTALL` (default `/home/corey/workspace/iree-build`) and `IREE_SOURCE` (default `/home/corey/workspace/iree`)

This task exists on its own because linking IREE's static archives without a CMake package config is the first thing that can fail outright, and a reviewer should be able to reject it independently of any facade design.

- [ ] **Step 1: Write the failing link smoke test**

Create `native/test/link_smoke_test.cpp`:

```cpp
// Proves the IREE runtime links and the local-sync driver is present.
// Deliberately has no dependency on our facade — this is a link test.
#include <cstdio>
#include "iree/runtime/api.h"

int main() {
  iree_runtime_instance_options_t options;
  iree_runtime_instance_options_initialize(&options);
  iree_runtime_instance_options_use_all_available_drivers(&options);

  iree_runtime_instance_t* instance = nullptr;
  iree_status_t status = iree_runtime_instance_create(
      &options, iree_allocator_system(), &instance);
  if (!iree_status_is_ok(status)) {
    iree_status_fprint(stderr, status);
    iree_status_free(status);
    return 1;
  }

  iree_hal_device_t* device = nullptr;
  status = iree_runtime_instance_try_create_default_device(
      instance, iree_make_cstring_view("local-sync"), &device);
  if (!iree_status_is_ok(status)) {
    iree_status_fprint(stderr, status);
    iree_status_free(status);
    iree_runtime_instance_release(instance);
    return 2;
  }

  std::printf("ok: local-sync device created\n");
  iree_hal_device_release(device);
  iree_runtime_instance_release(instance);
  return 0;
}
```

- [ ] **Step 2: Write the IREE resolution module**

Create `native/cmake/ResolveIree.cmake`:

```cmake
# Resolves the IREE runtime into a single `iree::runtime` interface target.
#
# There is no IREERuntimeConfig.cmake to find_package(): the local build at
# IREE_INSTALL was never `ninja install`ed, so we point at the build tree for
# archives and the source tree for headers. When the iree-runtime-dist repo
# lands, IreeRuntimePin.cmake replaces this with a proper package.

set(IREE_INSTALL "/home/corey/workspace/iree-build" CACHE PATH
    "IREE build (or install) tree to link against")
set(IREE_SOURCE "/home/corey/workspace/iree" CACHE PATH
    "IREE source tree supplying public headers")

if(NOT EXISTS "${IREE_INSTALL}/runtime/src/iree/runtime/libiree_runtime_impl.a")
  message(FATAL_ERROR
      "No IREE runtime at IREE_INSTALL=${IREE_INSTALL}. "
      "Expected runtime/src/iree/runtime/libiree_runtime_impl.a. "
      "Point -DIREE_INSTALL at an IREE build tree.")
endif()

# Generated headers (flatbuffer schemas, config) live in the build tree;
# public headers live in the source tree. Both are required.
add_library(iree_runtime_iface INTERFACE)
target_include_directories(iree_runtime_iface INTERFACE
    "${IREE_SOURCE}/runtime/src"
    "${IREE_INSTALL}/runtime/src")

# The unified high-level runtime archive plus the driver/loader we selected.
# --start-group/--end-group because the IREE static archives are mutually
# recursive and single-pass resolution leaves undefined symbols.
target_link_libraries(iree_runtime_iface INTERFACE
    -Wl,--start-group
    "${IREE_INSTALL}/runtime/src/iree/runtime/libiree_runtime_impl.a"
    -Wl,--end-group
    ${CMAKE_DL_LIBS} m pthread)

add_library(iree::runtime ALIAS iree_runtime_iface)
```

- [ ] **Step 3: Write the stub pin file**

Create `native/cmake/IreeRuntimePin.cmake`:

```cmake
# GENERATED SEAM — placeholder.
#
# When the iree-runtime-dist repo exists this file is replaced wholesale with
# the pin asset from its release (hash-pinned FetchContent of a build-attested
# tarball), exactly as native/cmake/EtRuntimePin.cmake works in
# djl-executorch-engine. The SHA256 change is the supply-chain review gate.
#
# Until then this is intentionally unreachable: ResolveIree.cmake handles
# everything via IREE_INSTALL. Do not add logic here — replace the file.

if(NOT DEFINED IREE_INSTALL OR IREE_INSTALL STREQUAL "")
  message(FATAL_ERROR
      "No IREE_INSTALL set and no runtime pin is available yet. "
      "Set -DIREE_INSTALL=/path/to/iree-build.")
endif()
```

- [ ] **Step 4: Write the top-level CMakeLists**

Create `native/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22)
project(iree_djl LANGUAGES C CXX)

# IREE's headers require C++17+; state the standard ourselves because no
# package config supplies INTERFACE_COMPILE_FEATURES. Do not delete.
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}/cmake")
include(ResolveIree)

option(IREE_DJL_SANITIZE "Build with ASan/LSan" OFF)
if(IREE_DJL_SANITIZE)
  add_compile_options(-fsanitize=address -fno-omit-frame-pointer -g)
  add_link_options(-fsanitize=address)
endif()

# Link smoke test (Task 1)
add_executable(link_smoke_test test/link_smoke_test.cpp)
target_link_libraries(link_smoke_test PRIVATE iree::runtime)
```

- [ ] **Step 5: Write the build script**

Create `native/build.sh`:

```bash
#!/usr/bin/env bash
# Host build, linux-x86_64 only. No container: this skeleton has no glibc
# floor to hold because it ships nothing. See the spec's deferred list.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
build_dir="${here}/build"
build_type="${BUILD_TYPE:-RelWithDebInfo}"

cmake -S "${here}" -B "${build_dir}" -G Ninja \
  -DCMAKE_BUILD_TYPE="${build_type}" \
  ${IREE_INSTALL:+-DIREE_INSTALL="${IREE_INSTALL}"} \
  ${IREE_SOURCE:+-DIREE_SOURCE="${IREE_SOURCE}"} \
  "$@"

cmake --build "${build_dir}"

# Stage the shim where LibUtils' classpath fallback expects it, once it exists.
lib="${build_dir}/libiree_djl.so"
if [[ -f "${lib}" ]]; then
  dest="${here}/../src/main/resources/native/linux-x86_64"
  mkdir -p "${dest}"
  cp "${lib}" "${dest}/"
  echo "staged: ${dest}/libiree_djl.so"
fi
```

Then `chmod +x native/build.sh`.

- [ ] **Step 6: Run the build and the smoke test**

```bash
./native/build.sh
./native/build/link_smoke_test
```

Expected: `ok: local-sync device created`, exit 0.

If the link fails with undefined `iree_hal_*` symbols, the single archive is insufficient — add the driver and loader archives inside the `--start-group`/`--end-group` block in `ResolveIree.cmake`:

```cmake
    "${IREE_INSTALL}/runtime/src/iree/hal/drivers/local_sync/registration/libiree_hal_drivers_local_sync_registration_registration.a"
    "${IREE_INSTALL}/runtime/src/iree/hal/drivers/local_sync/libiree_hal_drivers_local_sync_sync_driver.a"
    "${IREE_INSTALL}/runtime/src/iree/hal/local/loaders/libiree_hal_local_loaders_embedded_elf_loader.a"
```

Locate any other missing archive with:
```bash
find /home/corey/workspace/iree-build -name 'libiree*.a' | xargs -I{} sh -c 'nm -g --defined-only {} 2>/dev/null | grep -q SYMBOL_NAME && echo {}'
```

- [ ] **Step 7: Commit**

```bash
git add native/CMakeLists.txt native/cmake native/build.sh native/test/link_smoke_test.cpp
git commit -m "build(native): CMake seam linking the local IREE runtime

Resolves IREE via IREE_INSTALL/IREE_SOURCE against the build tree, since
nothing was installed to a prefix and there is no IREERuntimeConfig.cmake.
IreeRuntimePin.cmake is a stub seam for the future dist repo.

A link smoke test asserts the local-sync driver survives the link."
```

---

### Task 2: Produce `add.vmfb` and confirm its exported symbol

**Files:**
- Create: `tools/export_add.sh`, `tools/add.mlir`
- Create: `src/test/resources/models/add.vmfb` (generated, committed)

**Interfaces:**
- Consumes: nothing
- Produces: `src/test/resources/models/add.vmfb`; the confirmed entry-point name recorded in `tools/export_add.sh` (expected `module.add`, but **confirmed empirically, not assumed** — §12)

- [ ] **Step 1: Write the MLIR input**

Create `tools/add.mlir`:

```mlir
// Two f32 tensors of 4 elements, added elementwise.
// Deliberately trivial: this exists to exercise the runtime layer, not math.
func.func @add(%lhs: tensor<4xf32>, %rhs: tensor<4xf32>) -> tensor<4xf32> {
  %result = arith.addf %lhs, %rhs : tensor<4xf32>
  return %result : tensor<4xf32>
}
```

- [ ] **Step 2: Write the export script**

Create `tools/export_add.sh`:

```bash
#!/usr/bin/env bash
# Produces add.vmfb using pip's iree-compile.
#
# The local IREE build has IREE_BUILD_COMPILER=OFF, so it has no iree-compile.
# Enabling it would pull in a full LLVM build (hours, tens of GB) for a tool we
# invoke twice and never link. pip ships a prebuilt binary instead.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="${here}/../src/test/resources/models/add.vmfb"
mkdir -p "$(dirname "${out}")"

if ! command -v iree-compile >/dev/null 2>&1; then
  echo "iree-compile not found. Install it with:" >&2
  echo "  uv pip install iree-base-compiler" >&2
  exit 1
fi

iree-compile \
  --iree-hal-target-device=local \
  --iree-hal-local-target-device-backends=llvm-cpu \
  "${here}/add.mlir" -o "${out}"

echo "wrote ${out}"

# Confirm the exported symbol rather than assuming it (spec §12).
dump="${IREE_INSTALL:-/home/corey/workspace/iree-build}/tools/iree-dump-module"
if [[ -x "${dump}" ]]; then
  echo "--- exported functions ---"
  "${dump}" "${out}" | grep -iE '^\s*\[[0-9]+\]|exported|function' | head -20
fi
```

Then `chmod +x tools/export_add.sh`.

- [ ] **Step 3: Install the compiler and run the export**

```bash
uv pip install iree-base-compiler
./tools/export_add.sh
```

Expected: `wrote .../add.vmfb`, followed by a dump listing the exported functions.

**If the `--iree-hal-target-device` flags are rejected**, the installed compiler uses the older spelling. Fall back to:
```bash
iree-compile --iree-hal-target-backends=llvm-cpu tools/add.mlir -o src/test/resources/models/add.vmfb
```
and update the script to match. Flag drift across releases is expected — §12 calls this out specifically. Confirm with `iree-compile --help | grep target`.

- [ ] **Step 4: Record the confirmed entry point**

Run:
```bash
/home/corey/workspace/iree-build/tools/iree-dump-module src/test/resources/models/add.vmfb | head -40
```

Read the exported function name from the output. It is expected to be `module.add`. **Write the actual observed name** into a comment at the top of `tools/export_add.sh`:

```bash
# CONFIRMED ENTRY POINT (iree-dump-module, 2026-07-19): module.add
```

This value is consumed by Tasks 3, 4, 7, and 9. If it is not `module.add`, use the observed value everywhere those tasks say `module.add`.

- [ ] **Step 5: Verify the module actually runs**

```bash
/home/corey/workspace/iree-build/tools/iree-run-module \
  --module=src/test/resources/models/add.vmfb \
  --device=local-sync \
  --function=add \
  --input="4xf32=[1.0 2.0 3.0 4.0]" \
  --input="4xf32=[10.0 20.0 30.0 40.0]"
```

Expected output contains `4xf32=11 22 33 44`. These are the golden values Task 4 asserts on.

- [ ] **Step 6: Commit**

```bash
git add tools/add.mlir tools/export_add.sh src/test/resources/models/add.vmfb
git commit -m "test(models): add.vmfb fixture plus its export script

Compiled with pip's iree-compile (the local build has the compiler off).
Entry point confirmed with iree-dump-module rather than assumed, and the
golden values verified with iree-run-module."
```

---

### Task 3: Status guard, handle wrappers, and `Load`

**Files:**
- Create: `native/core/iree_status.h`, `native/core/iree_handles.h`, `native/core/iree_runtime.h`, `native/core/iree_runtime.cpp`
- Create: `native/test/iree_runtime_test.cpp`
- Modify: `native/CMakeLists.txt`

**Interfaces:**
- Consumes: `iree::runtime` target from Task 1; `add.vmfb` and the entry point from Task 2
- Produces: `measly::iree::IreeRuntime::Load(std::span<const std::byte>, std::string_view) -> std::unique_ptr<IreeRuntime>`; `IreeRuntime::ImportOutcome{kWrapped,kStaged}`; structs `InputDesc{data,nbytes,shape,elementType}` and `OutputBuffer{shape,elementType,data}`; macro `IREE_CHECK_OR_THROW(expr)`

- [ ] **Step 1: Write the status guard**

Create `native/core/iree_status.h`:

```cpp
#ifndef MEASLY_IREE_STATUS_H
#define MEASLY_IREE_STATUS_H

#include <stdexcept>
#include <string>
#include "iree/base/api.h"

namespace measly::iree {

// Consumes an iree_status_t exactly once. A non-OK status is a HEAP OBJECT:
// dropping it without free leaks it along with its message payload. Error
// paths are the least hand-tested code, which is why every status in this
// codebase funnels through here and nowhere else.
inline void ConsumeStatusOrThrow(iree_status_t status, const char* expr) {
  if (iree_status_is_ok(status)) {
    iree_status_ignore(status);
    return;
  }
  // Render the message BEFORE freeing — the buffer is owned by the status.
  std::string message;
  iree_host_size_t length = 0;
  char* buffer = nullptr;
  if (iree_status_to_string(status, &iree_allocator_system(), &buffer, &length)) {
    message.assign(buffer, length);
    iree_allocator_free(iree_allocator_system(), buffer);
  } else {
    message = "unknown IREE error";
  }
  iree_status_free(status);
  throw std::runtime_error(std::string(expr) + ": " + message);
}

}  // namespace measly::iree

#define IREE_CHECK_OR_THROW(expr) \
  ::measly::iree::ConsumeStatusOrThrow((expr), #expr)

#endif  // MEASLY_IREE_STATUS_H
```

> If `iree_status_to_string` has a different arity in the installed headers, check it with:
> `grep -n 'iree_status_to_string' /home/corey/workspace/iree/runtime/src/iree/base/status.h`
> and adapt. The contract that must hold regardless: render first, then free exactly once.

- [ ] **Step 2: Write the handle wrappers**

Create `native/core/iree_handles.h`:

```cpp
#ifndef MEASLY_IREE_HANDLES_H
#define MEASLY_IREE_HANDLES_H

#include <memory>
#include "iree/runtime/api.h"

namespace measly::iree {

// One owning wrapper per refcounted IREE handle type. Construction acquires,
// destruction releases, exactly once. No raw handle escapes the facade.
struct InstanceDeleter {
  void operator()(iree_runtime_instance_t* p) const { iree_runtime_instance_release(p); }
};
struct DeviceDeleter {
  void operator()(iree_hal_device_t* p) const { iree_hal_device_release(p); }
};
struct SessionDeleter {
  void operator()(iree_runtime_session_t* p) const { iree_runtime_session_release(p); }
};
struct BufferViewDeleter {
  void operator()(iree_hal_buffer_view_t* p) const { iree_hal_buffer_view_release(p); }
};

using InstancePtr = std::unique_ptr<iree_runtime_instance_t, InstanceDeleter>;
using DevicePtr = std::unique_ptr<iree_hal_device_t, DeviceDeleter>;
using SessionPtr = std::unique_ptr<iree_runtime_session_t, SessionDeleter>;
using BufferViewPtr = std::unique_ptr<iree_hal_buffer_view_t, BufferViewDeleter>;

// iree_runtime_call_t is a VALUE type, not a refcounted handle: it is
// initialized in place and torn down with deinitialize (there is no
// iree_runtime_call_release). So it gets a scope guard, not a unique_ptr.
class CallGuard {
 public:
  CallGuard() = default;
  ~CallGuard() {
    if (initialized_) iree_runtime_call_deinitialize(&call_);
  }
  CallGuard(const CallGuard&) = delete;
  CallGuard& operator=(const CallGuard&) = delete;

  iree_runtime_call_t* get() { return &call_; }
  void mark_initialized() { initialized_ = true; }
  bool initialized() const { return initialized_; }

 private:
  iree_runtime_call_t call_{};
  bool initialized_ = false;
};

}  // namespace measly::iree
#endif  // MEASLY_IREE_HANDLES_H
```

- [ ] **Step 3: Write the facade header**

Create `native/core/iree_runtime.h`:

```cpp
#ifndef MEASLY_IREE_RUNTIME_H
#define MEASLY_IREE_RUNTIME_H

#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace measly::iree {

// Borrowed input: a host pointer the caller keeps valid across Invoke().
// May be imported zero-copy or staged — see lastImportOutcomes().
struct InputDesc {
  const void* data;
  size_t nbytes;
  std::vector<int64_t> shape;
  int32_t elementType;  // iree_hal_element_type_t
};

// OWNING output. Unlike ExecuTorch's borrow-view OutputView, this holds its
// own bytes: every IREE handle is released before Invoke() returns.
struct OutputBuffer {
  std::vector<int64_t> shape;
  int32_t elementType;
  std::vector<std::byte> data;
};

struct RuntimeState;  // pimpl

class IreeRuntime {
 public:
  enum class ImportOutcome { kWrapped, kStaged };

  // Throws std::runtime_error on failure. The vmfb bytes are COPIED: IREE's
  // append-from-memory with a null allocator does not take ownership, so the
  // data must outlive the session and we own that lifetime ourselves.
  static std::unique_ptr<IreeRuntime> Load(std::span<const std::byte> vmfb,
                                           std::string_view entryPoint);

  ~IreeRuntime();
  IreeRuntime(const IreeRuntime&) = delete;
  IreeRuntime& operator=(const IreeRuntime&) = delete;

  std::vector<OutputBuffer> Invoke(std::span<const InputDesc> inputs);

  // Empirical answer to "did the import zero-copy or silently stage?".
  // Deliberately part of the API, not a log line, so tests can assert it.
  std::span<const ImportOutcome> lastImportOutcomes() const;

  explicit IreeRuntime(std::unique_ptr<RuntimeState> state);

 private:
  std::unique_ptr<RuntimeState> state_;
};

}  // namespace measly::iree
#endif  // MEASLY_IREE_RUNTIME_H
```

- [ ] **Step 4: Write the failing load test**

Create `native/test/iree_runtime_test.cpp`:

```cpp
#include <catch2/catch_test_macros.hpp>
#include <cstddef>
#include <fstream>
#include <span>
#include <vector>
#include "core/iree_runtime.h"

using measly::iree::IreeRuntime;

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
// Set by CMake to the absolute path of src/test/resources/models/add.vmfb.
constexpr const char* kAddVmfb = IREE_DJL_ADD_VMFB;
constexpr const char* kEntryPoint = "module.add";
}  // namespace

TEST_CASE("loads a valid vmfb", "[runtime]") {
  auto bytes = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint);
  REQUIRE(runtime != nullptr);
}
```

- [ ] **Step 5: Wire Catch2 and the test target into CMake**

Append to `native/CMakeLists.txt`:

```cmake
add_library(iree_djl_core STATIC core/iree_runtime.cpp)
target_include_directories(iree_djl_core PUBLIC "${CMAKE_CURRENT_LIST_DIR}")
target_link_libraries(iree_djl_core PUBLIC iree::runtime)

include(FetchContent)
FetchContent_Declare(Catch2
    GIT_REPOSITORY https://github.com/catchorg/Catch2.git
    GIT_TAG v3.5.2)
FetchContent_MakeAvailable(Catch2)

add_executable(iree_runtime_test test/iree_runtime_test.cpp)
target_link_libraries(iree_runtime_test PRIVATE iree_djl_core Catch2::Catch2WithMain)
target_compile_definitions(iree_runtime_test PRIVATE
    IREE_DJL_ADD_VMFB="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/add.vmfb")
```

- [ ] **Step 6: Run the test to verify it fails**

```bash
./native/build.sh && ./native/build/iree_runtime_test
```

Expected: build FAILS — `core/iree_runtime.cpp` does not exist yet.

- [ ] **Step 7: Implement `Load`**

Create `native/core/iree_runtime.cpp`:

```cpp
#include "core/iree_runtime.h"

#include <cstring>
#include "core/iree_handles.h"
#include "core/iree_status.h"
#include "iree/hal/buffer_view_util.h"
#include "iree/runtime/api.h"

namespace measly::iree {

struct RuntimeState {
  // Owns a copy of the flatbuffer. append_bytecode_module_from_memory with a
  // null allocator does NOT copy, so these bytes must outlive the session.
  // Declared first so it is destroyed last.
  std::vector<std::byte> vmfb;
  std::string entryPoint;
  InstancePtr instance;
  DevicePtr device;
  SessionPtr session;
  std::vector<IreeRuntime::ImportOutcome> lastImportOutcomes;
};

IreeRuntime::IreeRuntime(std::unique_ptr<RuntimeState> state)
    : state_(std::move(state)) {}
IreeRuntime::~IreeRuntime() = default;

std::unique_ptr<IreeRuntime> IreeRuntime::Load(std::span<const std::byte> vmfb,
                                               std::string_view entryPoint) {
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
  IREE_CHECK_OR_THROW(iree_runtime_instance_try_create_default_device(
      state->instance.get(), iree_make_cstring_view("local-sync"), &raw_device));
  state->device.reset(raw_device);

  iree_runtime_session_options_t session_options;
  iree_runtime_session_options_initialize(&session_options);
  iree_runtime_session_t* raw_session = nullptr;
  IREE_CHECK_OR_THROW(iree_runtime_session_create_with_device(
      state->instance.get(), &session_options, state->device.get(),
      iree_allocator_system(), &raw_session));
  state->session.reset(raw_session);

  IREE_CHECK_OR_THROW(iree_runtime_session_append_bytecode_module_from_memory(
      state->session.get(),
      iree_make_const_byte_span(state->vmfb.data(), state->vmfb.size()),
      iree_allocator_null()));

  // Fail fast at load time if the entry point does not exist, rather than at
  // first Invoke. Cheap, and it makes the wrong-entry-point error path testable
  // without a successful load first.
  iree_vm_function_t function;
  IREE_CHECK_OR_THROW(iree_runtime_session_lookup_function(
      state->session.get(),
      iree_make_string_view(state->entryPoint.data(), state->entryPoint.size()),
      &function));

  return std::make_unique<IreeRuntime>(std::move(state));
}

std::span<const IreeRuntime::ImportOutcome> IreeRuntime::lastImportOutcomes() const {
  return state_->lastImportOutcomes;
}

}  // namespace measly::iree
```

- [ ] **Step 8: Run the test to verify it passes**

```bash
./native/build.sh && ./native/build/iree_runtime_test
```

Expected: `All tests passed (1 assertion in 1 test case)`.

- [ ] **Step 9: Commit**

```bash
git add native/core native/test/iree_runtime_test.cpp native/CMakeLists.txt
git commit -m "feat(native): RAII facade over the IREE C API with Load

One owning wrapper per refcounted handle; every status funnelled through
IREE_CHECK_OR_THROW so none is dropped. Two things the headers corrected:
iree_runtime_call_t is a value type (scope guard, not unique_ptr), and
append-from-memory with a null allocator does not copy, so the facade owns
the vmfb bytes itself."
```

---

### Task 4: `Invoke` — import-or-copy in, copy out

**Files:**
- Modify: `native/core/iree_runtime.cpp`
- Modify: `native/test/iree_runtime_test.cpp`

**Interfaces:**
- Consumes: `IreeRuntime::Load`, `InputDesc`, `OutputBuffer`, `CallGuard` from Task 3
- Produces: `IreeRuntime::Invoke(std::span<const InputDesc>) -> std::vector<OutputBuffer>`; populated `lastImportOutcomes()`

- [ ] **Step 1: Write the failing golden-vector test**

Append to `native/test/iree_runtime_test.cpp`:

```cpp
#include <cstdint>

namespace {
// IREE_HAL_ELEMENT_TYPE_FLOAT_32 as an int32_t, so the facade header needs no
// IREE include. Asserted equal to the real constant in the test below.
constexpr int32_t kF32 = 0x00000120;
}  // namespace

TEST_CASE("golden vector: add", "[runtime]") {
  auto bytes = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint);

  const float lhs[4] = {1.0f, 2.0f, 3.0f, 4.0f};
  const float rhs[4] = {10.0f, 20.0f, 30.0f, 40.0f};
  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, sizeof(lhs), {4}, kF32},
      {rhs, sizeof(rhs), {4}, kF32},
  };

  auto outputs = runtime->Invoke(inputs);

  REQUIRE(outputs.size() == 1);
  REQUIRE(outputs[0].shape == std::vector<int64_t>{4});
  REQUIRE(outputs[0].data.size() == 4 * sizeof(float));

  const float* result = reinterpret_cast<const float*>(outputs[0].data.data());
  REQUIRE(result[0] == 11.0f);
  REQUIRE(result[1] == 22.0f);
  REQUIRE(result[2] == 33.0f);
  REQUIRE(result[3] == 44.0f);
}

TEST_CASE("import outcome is recorded for every input", "[runtime][import]") {
  auto bytes = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint);

  // Over-aligned host allocation: the best case for a zero-copy import.
  void* aligned = nullptr;
  REQUIRE(posix_memalign(&aligned, 64, 4 * sizeof(float)) == 0);
  float* lhs = static_cast<float*>(aligned);
  for (int i = 0; i < 4; ++i) lhs[i] = static_cast<float>(i);
  const float rhs[4] = {0.0f, 0.0f, 0.0f, 0.0f};

  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, 4 * sizeof(float), {4}, kF32},
      {rhs, sizeof(rhs), {4}, kF32},
  };
  auto outputs = runtime->Invoke(inputs);

  // We assert only that an outcome is RECORDED per input. Whether it is
  // kWrapped or kStaged is the finding this project exists to produce — the
  // test must not prejudge it.
  auto outcomes = runtime->lastImportOutcomes();
  REQUIRE(outcomes.size() == 2);

  WARN("import outcome[0] = "
       << (outcomes[0] == IreeRuntime::ImportOutcome::kWrapped ? "WRAPPED"
                                                               : "STAGED"));
  free(aligned);
}
```

Add `#include <cstdlib>` to the test file's includes.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./native/build.sh && ./native/build/iree_runtime_test
```

Expected: build FAILS — `IreeRuntime::Invoke` is declared but not defined (undefined reference at link).

- [ ] **Step 3: Implement `Invoke`**

Add to `native/core/iree_runtime.cpp`, inside `namespace measly::iree`:

```cpp
namespace {

// Attempts a zero-copy import of host memory; falls back to a staged copy when
// the allocator's preconditions (memory type / usage / alignment) are unmet.
// Returns the buffer view and reports which path was taken.
BufferViewPtr ImportOrCopy(iree_hal_device_t* device,
                           iree_hal_allocator_t* allocator,
                           const InputDesc& input,
                           IreeRuntime::ImportOutcome* out_outcome) {
  std::vector<iree_hal_dim_t> shape(input.shape.begin(), input.shape.end());

  iree_hal_buffer_params_t params = {};
  params.type = IREE_HAL_MEMORY_TYPE_DEVICE_LOCAL | IREE_HAL_MEMORY_TYPE_HOST_VISIBLE;
  params.usage = IREE_HAL_BUFFER_USAGE_DEFAULT | IREE_HAL_BUFFER_USAGE_MAPPING;
  params.access = IREE_HAL_MEMORY_ACCESS_ALL;

  // 1. Try the import. const_cast is safe: params.access is read-only for our
  //    use and the buffer never escapes Invoke.
  iree_hal_external_buffer_t external = {};
  external.type = IREE_HAL_EXTERNAL_BUFFER_TYPE_HOST_ALLOCATION;
  external.flags = IREE_HAL_EXTERNAL_BUFFER_FLAG_NONE;
  external.size = static_cast<iree_device_size_t>(input.nbytes);
  external.handle.host_allocation.ptr = const_cast<void*>(input.data);

  iree_hal_buffer_t* imported = nullptr;
  iree_status_t import_status = iree_hal_allocator_import_buffer(
      allocator, params, &external,
      iree_hal_buffer_release_callback_null(), &imported);

  if (iree_status_is_ok(import_status)) {
    iree_status_ignore(import_status);
    iree_hal_buffer_view_t* view = nullptr;
    iree_status_t view_status = iree_hal_buffer_view_create(
        imported, shape.size(), shape.data(),
        static_cast<iree_hal_element_type_t>(input.elementType),
        IREE_HAL_ENCODING_TYPE_DENSE_ROW_MAJOR, iree_allocator_system(), &view);
    // The view retains the buffer; drop our own reference either way.
    iree_hal_buffer_release(imported);
    IREE_CHECK_OR_THROW(view_status);
    *out_outcome = IreeRuntime::ImportOutcome::kWrapped;
    return BufferViewPtr(view);
  }

  // 2. Import refused. Consume the status — this is exactly the path where a
  //    dropped iree_status_t would leak — and stage a copy instead.
  iree_status_free(import_status);

  iree_hal_buffer_view_t* view = nullptr;
  IREE_CHECK_OR_THROW(iree_hal_buffer_view_allocate_buffer_copy(
      device, allocator, shape.size(), shape.data(),
      static_cast<iree_hal_element_type_t>(input.elementType),
      IREE_HAL_ENCODING_TYPE_DENSE_ROW_MAJOR, params,
      iree_make_const_byte_span(input.data, input.nbytes), &view));
  *out_outcome = IreeRuntime::ImportOutcome::kStaged;
  return BufferViewPtr(view);
}

}  // namespace

std::vector<OutputBuffer> IreeRuntime::Invoke(std::span<const InputDesc> inputs) {
  CallGuard call;
  IREE_CHECK_OR_THROW(iree_runtime_call_initialize_by_name(
      state_->session.get(),
      iree_make_string_view(state_->entryPoint.data(), state_->entryPoint.size()),
      call.get()));
  call.mark_initialized();

  iree_hal_allocator_t* allocator =
      iree_runtime_session_device_allocator(state_->session.get());

  // Every input view is owned here and released when this vector goes out of
  // scope — including on the throw paths below.
  std::vector<BufferViewPtr> input_views;
  input_views.reserve(inputs.size());
  state_->lastImportOutcomes.assign(inputs.size(), ImportOutcome::kStaged);

  for (size_t i = 0; i < inputs.size(); ++i) {
    auto view = ImportOrCopy(state_->device.get(), allocator, inputs[i],
                             &state_->lastImportOutcomes[i]);
    IREE_CHECK_OR_THROW(iree_runtime_call_inputs_push_back_buffer_view(
        call.get(), view.get()));
    input_views.push_back(std::move(view));
  }

  IREE_CHECK_OR_THROW(iree_runtime_call_invoke(call.get(), /*flags=*/0));

  // Copy out. Nothing IREE-side may outlive this function, so each output is
  // materialised into an owning vector and its view released immediately.
  std::vector<OutputBuffer> outputs;
  for (;;) {
    iree_hal_buffer_view_t* raw_view = nullptr;
    iree_status_t status = iree_runtime_call_outputs_pop_front_buffer_view(
        call.get(), &raw_view);
    if (!iree_status_is_ok(status)) {
      // Exhausted the output list: consume the status and stop.
      iree_status_free(status);
      break;
    }
    iree_status_ignore(status);
    BufferViewPtr view(raw_view);

    OutputBuffer out;
    out.elementType =
        static_cast<int32_t>(iree_hal_buffer_view_element_type(view.get()));
    const iree_host_size_t rank = iree_hal_buffer_view_shape_rank(view.get());
    out.shape.reserve(rank);
    for (iree_host_size_t d = 0; d < rank; ++d) {
      out.shape.push_back(
          static_cast<int64_t>(iree_hal_buffer_view_shape_dim(view.get(), d)));
    }

    const iree_device_size_t nbytes = iree_hal_buffer_view_byte_length(view.get());
    out.data.resize(static_cast<size_t>(nbytes));
    // CPU host memory is coherent, so this is a straight copy. A non-CPU
    // backend would need an invalidate-range before reading.
    IREE_CHECK_OR_THROW(iree_hal_buffer_map_read(
        iree_hal_buffer_view_buffer(view.get()), 0, out.data.data(), nbytes));

    outputs.push_back(std::move(out));
  }

  return outputs;
  // input_views destructs here: every imported/staged buffer released before
  // return, so no imported input can outlive the caller's pinned region.
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./native/build.sh && ./native/build/iree_runtime_test
```

Expected: `All tests passed`, plus a `WARN` line reporting `import outcome[0] = WRAPPED` or `= STAGED`.

**Record whichever it says in the commit message** — that is a primary deliverable of this project.

If `kF32` mismatches the real constant, the invoke fails with an element-type error. Get the true value with:
```bash
grep -n 'IREE_HAL_ELEMENT_TYPE_FLOAT_32' /home/corey/workspace/iree/runtime/src/iree/hal/buffer_view.h
```
and compute it from the `IREE_HAL_ELEMENT_TYPE_VALUE` macro.

- [ ] **Step 5: Commit**

```bash
git add native/core/iree_runtime.cpp native/test/iree_runtime_test.cpp
git commit -m "feat(native): Invoke with import-or-copy in and copy-out

Inputs attempt iree_hal_allocator_import_buffer and fall back to a staged
copy when preconditions are unmet, recording which happened per input.
Outputs are materialised into owning vectors so no IREE handle outlives
the call.

Native import outcome for a 64-byte-aligned host buffer: <WRAPPED|STAGED>"
```

---

### Task 5: Error paths — force every status to be walked

**Files:**
- Modify: `native/test/iree_runtime_test.cpp`

**Interfaces:**
- Consumes: `IreeRuntime::Load`, `IreeRuntime::Invoke`
- Produces: nothing new — this task adds coverage that Task 6 then runs under LSan

- [ ] **Step 1: Write the failing error-path tests**

Append to `native/test/iree_runtime_test.cpp`:

```cpp
// These four tests exist to WALK the error paths, not merely to assert
// messages. Task 6 runs them under LSan, where a dropped iree_status_t on any
// of these paths shows up as a leak. Error paths are the least hand-tested
// code in any runtime, which is exactly why they get forced here.

TEST_CASE("rejects a corrupt vmfb", "[runtime][errors]") {
  std::vector<std::byte> garbage(256, std::byte{0xAB});
  REQUIRE_THROWS_AS(IreeRuntime::Load(garbage, kEntryPoint), std::runtime_error);
}

TEST_CASE("rejects an empty vmfb", "[runtime][errors]") {
  std::vector<std::byte> empty;
  REQUIRE_THROWS_AS(IreeRuntime::Load(empty, kEntryPoint), std::runtime_error);
}

TEST_CASE("rejects an unknown entry point", "[runtime][errors]") {
  auto bytes = ReadFile(kAddVmfb);
  REQUIRE_THROWS_AS(IreeRuntime::Load(bytes, "module.does_not_exist"),
                    std::runtime_error);
}

TEST_CASE("rejects a shape mismatch", "[runtime][errors]") {
  auto bytes = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint);

  const float lhs[8] = {1, 2, 3, 4, 5, 6, 7, 8};  // model expects 4xf32
  const float rhs[8] = {1, 2, 3, 4, 5, 6, 7, 8};
  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, sizeof(lhs), {8}, kF32},
      {rhs, sizeof(rhs), {8}, kF32},
  };
  REQUIRE_THROWS_AS(runtime->Invoke(inputs), std::runtime_error);
}

TEST_CASE("rejects a wrong element type", "[runtime][errors]") {
  auto bytes = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint);

  const int32_t lhs[4] = {1, 2, 3, 4};
  const int32_t rhs[4] = {1, 2, 3, 4};
  constexpr int32_t kI32 = 0x00000220;  // IREE_HAL_ELEMENT_TYPE_SINT_32
  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, sizeof(lhs), {4}, kI32},
      {rhs, sizeof(rhs), {4}, kI32},
  };
  REQUIRE_THROWS_AS(runtime->Invoke(inputs), std::runtime_error);
}
```

- [ ] **Step 2: Run the tests**

```bash
./native/build.sh && ./native/build/iree_runtime_test
```

Expected: all pass. If any error case does **not** throw, that is a real defect — the facade is swallowing a status somewhere. Fix `iree_runtime.cpp` so the path routes through `IREE_CHECK_OR_THROW` rather than relaxing the test.

If the shape-mismatch case does not throw, IREE may be accepting the call and failing later; in that case assert on the invoke result instead and note it in the commit.

- [ ] **Step 3: Commit**

```bash
git add native/test/iree_runtime_test.cpp
git commit -m "test(native): force every error path to be walked

Corrupt vmfb, empty vmfb, unknown entry point, shape mismatch, wrong
element type. These are staged for the LSan harness, where a dropped
iree_status_t on an error path surfaces as a leak."
```

---

### Task 6: ASan/LSan harness — **the go/no-go gate**

**Files:**
- Create: `native/harness/iree_leak_harness.cpp`
- Modify: `native/CMakeLists.txt`

**Interfaces:**
- Consumes: `IreeRuntime::Load`, `IreeRuntime::Invoke`
- Produces: executable `iree_leak_harness`; exit code 0 means retain/release balanced and no status leaked

This is the milestone-2 gate from the spec. If RAII-over-C-API is going to be miserable, it is miserable here — before any JVM code exists.

- [ ] **Step 1: Write the harness**

Create `native/harness/iree_leak_harness.cpp`:

```cpp
// Plain native main(). The JVM is deliberately out of the picture: libjvm's
// allocator, JIT, and signal handlers generate ASan/TSan noise that would bury
// real findings. No TSan leg — the local-sync driver has no internal threads.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <span>
#include <stdexcept>
#include <vector>

#include "core/iree_runtime.h"

using measly::iree::InputDesc;
using measly::iree::IreeRuntime;

namespace {
constexpr int32_t kF32 = 0x00000120;
constexpr const char* kEntryPoint = "module.add";

std::vector<std::byte> ReadFile(const char* path) {
  std::ifstream in(path, std::ios::binary);
  if (!in.good()) {
    std::fprintf(stderr, "cannot read %s\n", path);
    std::exit(70);
  }
  std::vector<char> raw((std::istreambuf_iterator<char>(in)),
                        std::istreambuf_iterator<char>());
  std::vector<std::byte> bytes(raw.size());
  std::memcpy(bytes.data(), raw.data(), raw.size());
  return bytes;
}

// A single load/invoke/close cycle. Looping this is what makes an unbalanced
// retain/release visible: a missing release grows RSS and trips LSan; an extra
// release trips ASan with a use-after-free.
void HappyPathCycle(const std::vector<std::byte>& vmfb) {
  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint);
  const float lhs[4] = {1.0f, 2.0f, 3.0f, 4.0f};
  const float rhs[4] = {10.0f, 20.0f, 30.0f, 40.0f};
  std::vector<InputDesc> inputs = {
      {lhs, sizeof(lhs), {4}, kF32},
      {rhs, sizeof(rhs), {4}, kF32},
  };
  auto outputs = runtime->Invoke(inputs);
  if (outputs.size() != 1) {
    std::fprintf(stderr, "expected 1 output, got %zu\n", outputs.size());
    std::exit(71);
  }
  const float* r = reinterpret_cast<const float*>(outputs[0].data.data());
  if (r[0] != 11.0f || r[3] != 44.0f) {
    std::fprintf(stderr, "golden mismatch: %f %f\n", r[0], r[3]);
    std::exit(72);
  }
}

// Every throw path, looped. This is the highest-value part of the harness:
// a non-OK iree_status_t is a heap object, and dropping one leaks it along
// with its message payload.
void ErrorPathCycle(const std::vector<std::byte>& vmfb) {
  std::vector<std::byte> garbage(256, std::byte{0xAB});
  try { IreeRuntime::Load(garbage, kEntryPoint); } catch (const std::runtime_error&) {}
  try { IreeRuntime::Load({}, kEntryPoint); } catch (const std::runtime_error&) {}
  try { IreeRuntime::Load(vmfb, "module.nope"); } catch (const std::runtime_error&) {}

  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint);
  const float wide[8] = {1, 2, 3, 4, 5, 6, 7, 8};
  std::vector<InputDesc> bad_shape = {
      {wide, sizeof(wide), {8}, kF32},
      {wide, sizeof(wide), {8}, kF32},
  };
  try { runtime->Invoke(bad_shape); } catch (const std::runtime_error&) {}

  const int32_t ints[4] = {1, 2, 3, 4};
  constexpr int32_t kI32 = 0x00000220;
  std::vector<InputDesc> bad_type = {
      {ints, sizeof(ints), {4}, kI32},
      {ints, sizeof(ints), {4}, kI32},
  };
  try { runtime->Invoke(bad_type); } catch (const std::runtime_error&) {}
}

// The imported input buffer must not outlive Invoke. Here the source buffer is
// freed immediately after the call returns; if anything IREE-side still held a
// pointer into it, a subsequent invoke would be a use-after-free under ASan.
void ImportEscapeCheck(const std::vector<std::byte>& vmfb) {
  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint);

  void* aligned = nullptr;
  if (posix_memalign(&aligned, 64, 4 * sizeof(float)) != 0) std::exit(73);
  float* lhs = static_cast<float*>(aligned);
  for (int i = 0; i < 4; ++i) lhs[i] = static_cast<float>(i);
  const float rhs[4] = {1.0f, 1.0f, 1.0f, 1.0f};

  std::vector<InputDesc> inputs = {
      {lhs, 4 * sizeof(float), {4}, kF32},
      {rhs, sizeof(rhs), {4}, kF32},
  };
  auto outputs = runtime->Invoke(inputs);
  auto outcome = runtime->lastImportOutcomes()[0];
  std::printf("import outcome (aligned host alloc): %s\n",
              outcome == IreeRuntime::ImportOutcome::kWrapped ? "WRAPPED" : "STAGED");

  free(aligned);  // poisoned by ASan from here on

  // If an imported buffer escaped Invoke, this second call touches freed memory.
  const float safe_lhs[4] = {1.0f, 2.0f, 3.0f, 4.0f};
  std::vector<InputDesc> again = {
      {safe_lhs, sizeof(safe_lhs), {4}, kF32},
      {rhs, sizeof(rhs), {4}, kF32},
  };
  runtime->Invoke(again);
}
}  // namespace

int main(int argc, char** argv) {
  const char* path = argc > 1 ? argv[1] : IREE_DJL_ADD_VMFB;
  const int iterations = argc > 2 ? std::atoi(argv[2]) : 100;

  auto vmfb = ReadFile(path);

  for (int i = 0; i < iterations; ++i) HappyPathCycle(vmfb);
  std::printf("happy path: %d cycles ok\n", iterations);

  for (int i = 0; i < iterations; ++i) ErrorPathCycle(vmfb);
  std::printf("error paths: %d cycles ok\n", iterations);

  ImportEscapeCheck(vmfb);
  std::printf("import escape check ok\n");

  std::printf("HARNESS PASS\n");
  return 0;
}
```

- [ ] **Step 2: Add the harness target**

Append to `native/CMakeLists.txt`:

```cmake
add_executable(iree_leak_harness harness/iree_leak_harness.cpp)
target_link_libraries(iree_leak_harness PRIVATE iree_djl_core)
target_compile_definitions(iree_leak_harness PRIVATE
    IREE_DJL_ADD_VMFB="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/add.vmfb")
```

- [ ] **Step 3: Build and run WITHOUT sanitizers first**

```bash
./native/build.sh
./native/build/iree_leak_harness
```

Expected: `happy path: 100 cycles ok`, `error paths: 100 cycles ok`, `import escape check ok`, `HARNESS PASS`.

Establish the functional baseline before adding sanitizer noise.

- [ ] **Step 4: Build and run WITH ASan/LSan — the gate**

```bash
rm -rf native/build
BUILD_TYPE=RelWithDebInfo ./native/build.sh -DIREE_DJL_SANITIZE=ON
ASAN_OPTIONS=detect_leaks=1:abort_on_error=0 \
  LSAN_OPTIONS=suppressions=/dev/null \
  ./native/build/iree_leak_harness "" 200
```

Expected: `HARNESS PASS` with **no** `ERROR: LeakSanitizer: detected memory leaks` and **no** `ERROR: AddressSanitizer`.

**This is the go/no-go gate.** Interpreting failures:

| Symptom | Meaning | Where to look |
|---|---|---|
| LSan leak growing with iteration count, traced to `iree_status_*` | A dropped status on an error path | Any `iree_status_t` not routed through `IREE_CHECK_OR_THROW` |
| LSan leak traced to `iree_hal_*` or `iree_runtime_*` | A missing release | The wrapper for that handle type in `iree_handles.h` |
| ASan heap-use-after-free during teardown | A double release | A handle released manually *and* by its wrapper |
| ASan heap-use-after-free in `ImportEscapeCheck` | An imported buffer outlived `Invoke` | `input_views` scope in `Invoke` |

- [ ] **Step 5: Record the result and commit**

```bash
git add native/harness/iree_leak_harness.cpp native/CMakeLists.txt
git commit -m "test(native): ASan/LSan harness over load/invoke/close and error paths

Loops the happy path and every error path so a dropped iree_status_t or an
unbalanced retain/release shows up as a leak or a UAF. Also asserts an
imported input buffer cannot outlive Invoke. No TSan leg: local-sync has no
internal threads.

Result: <clean | findings>. Import outcome: <WRAPPED|STAGED>."
```

**STOP HERE and report to the user before continuing to Task 7.** This is the decision point the whole project was scoped around. Report: whether sanitizers were clean, the import outcome, and a subjective read on how painful the RAII-over-C-API layer was to get right.

---

### Task 7: JNI shim

**Files:**
- Create: `native/jni/iree_djl_jni.cpp`
- Modify: `native/CMakeLists.txt`

**Interfaces:**
- Consumes: `IreeRuntime::Load`, `IreeRuntime::Invoke`
- Produces: JNI symbols `Java_org_measly_iree_jni_IreeNative_load`, `..._invoke`, `..._close`, `..._lastImportOutcomes`, `..._ireeVersion`

- [ ] **Step 1: Write the shim**

Create `native/jni/iree_djl_jni.cpp`:

```cpp
// Thin marshalling layer only. All lifetime logic lives in the facade; this
// file translates types and routes errors. The opaque jlong is a pointer to a
// heap-allocated IreeRuntime.
#include <jni.h>

#include <cstring>
#include <memory>
#include <new>
#include <span>
#include <string>
#include <vector>

#include "core/iree_runtime.h"

using measly::iree::InputDesc;
using measly::iree::IreeRuntime;

namespace {

jclass g_runtime_exception = nullptr;

IreeRuntime* AsRuntime(jlong handle) {
  return reinterpret_cast<IreeRuntime*>(static_cast<intptr_t>(handle));
}

// The single native->jthrow translation point, fed by whatever
// IREE_CHECK_OR_THROW raised. Keeps JNI exception logic out of the facade and
// out of the sanitizer harness.
void ThrowJava(JNIEnv* env, const char* message) {
  if (g_runtime_exception != nullptr) {
    env->ThrowNew(g_runtime_exception, message);
  }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK) {
    return JNI_ERR;
  }
  jclass local = env->FindClass("java/lang/RuntimeException");
  if (local == nullptr) return JNI_ERR;
  g_runtime_exception = static_cast<jclass>(env->NewGlobalRef(local));
  env->DeleteLocalRef(local);
  return JNI_VERSION_1_8;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK) return;
  if (g_runtime_exception != nullptr) {
    env->DeleteGlobalRef(g_runtime_exception);
    g_runtime_exception = nullptr;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_measly_iree_jni_IreeNative_load(JNIEnv* env, jclass,
                                         jbyteArray vmfb, jstring entryPoint) {
  const jsize length = env->GetArrayLength(vmfb);
  std::vector<std::byte> bytes(static_cast<size_t>(length));
  env->GetByteArrayRegion(vmfb, 0, length,
                          reinterpret_cast<jbyte*>(bytes.data()));

  const char* entry = env->GetStringUTFChars(entryPoint, nullptr);
  if (entry == nullptr) {
    ThrowJava(env, "entryPoint was null");
    return 0;
  }
  std::string entry_copy(entry);
  env->ReleaseStringUTFChars(entryPoint, entry);

  try {
    auto runtime = IreeRuntime::Load(bytes, entry_copy);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(runtime.release()));
  } catch (const std::exception& e) {
    ThrowJava(env, e.what());
    return 0;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_org_measly_iree_jni_IreeNative_close(JNIEnv*, jclass, jlong handle) {
  delete AsRuntime(handle);
}

// Inputs and outputs both cross as direct ByteBuffers. Input addresses are
// borrowed for exactly the duration of this call, which is what makes the
// facade's import-or-copy safe: the Java region stays pinned across the
// boundary for precisely that window.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_measly_iree_jni_IreeNative_invoke(JNIEnv* env, jclass, jlong handle,
                                           jobjectArray inputBuffers,
                                           jobjectArray inputShapes,
                                           jintArray inputTypes) {
  IreeRuntime* runtime = AsRuntime(handle);
  if (runtime == nullptr) {
    ThrowJava(env, "invoke on a closed handle");
    return nullptr;
  }

  const jsize count = env->GetArrayLength(inputBuffers);
  std::vector<InputDesc> inputs(static_cast<size_t>(count));
  std::vector<std::vector<int64_t>> shapes(static_cast<size_t>(count));

  jint* types = env->GetIntArrayElements(inputTypes, nullptr);

  for (jsize i = 0; i < count; ++i) {
    jobject buffer = env->GetObjectArrayElement(inputBuffers, i);
    void* address = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity < 0) {
      env->ReleaseIntArrayElements(inputTypes, types, JNI_ABORT);
      ThrowJava(env, "input must be a direct ByteBuffer");
      return nullptr;
    }

    jlongArray shapeArray =
        static_cast<jlongArray>(env->GetObjectArrayElement(inputShapes, i));
    const jsize rank = env->GetArrayLength(shapeArray);
    shapes[i].resize(static_cast<size_t>(rank));
    env->GetLongArrayRegion(shapeArray, 0, rank,
                            reinterpret_cast<jlong*>(shapes[i].data()));

    inputs[i].data = address;
    inputs[i].nbytes = static_cast<size_t>(capacity);
    inputs[i].shape = shapes[i];
    inputs[i].elementType = static_cast<int32_t>(types[i]);
  }
  env->ReleaseIntArrayElements(inputTypes, types, JNI_ABORT);

  std::vector<measly::iree::OutputBuffer> outputs;
  try {
    outputs = runtime->Invoke(inputs);
  } catch (const std::exception& e) {
    ThrowJava(env, e.what());
    return nullptr;
  }

  jclass tensor_class = env->FindClass("org/measly/iree/jni/IreeTensor");
  if (tensor_class == nullptr) return nullptr;
  jmethodID ctor = env->GetMethodID(tensor_class, "<init>",
                                    "(Ljava/nio/ByteBuffer;[JI)V");
  if (ctor == nullptr) return nullptr;

  jobjectArray result =
      env->NewObjectArray(static_cast<jsize>(outputs.size()), tensor_class, nullptr);

  for (size_t i = 0; i < outputs.size(); ++i) {
    auto& out = outputs[i];
    // Allocate a direct buffer the JVM owns and copy into it. The facade's
    // OutputBuffer dies with this function; nothing native outlives the call.
    jobject buffer = env->NewDirectByteBuffer(out.data.data(),
                                              static_cast<jlong>(out.data.size()));
    jbyteArray tmp = env->NewByteArray(static_cast<jsize>(out.data.size()));
    env->SetByteArrayRegion(tmp, 0, static_cast<jsize>(out.data.size()),
                            reinterpret_cast<const jbyte*>(out.data.data()));
    jclass bb = env->FindClass("java/nio/ByteBuffer");
    jmethodID allocate = env->GetStaticMethodID(bb, "allocateDirect",
                                                "(I)Ljava/nio/ByteBuffer;");
    jobject owned = env->CallStaticObjectMethod(
        bb, allocate, static_cast<jint>(out.data.size()));
    std::memcpy(env->GetDirectBufferAddress(owned), out.data.data(),
                out.data.size());
    env->DeleteLocalRef(buffer);
    env->DeleteLocalRef(tmp);

    jlongArray shape = env->NewLongArray(static_cast<jsize>(out.shape.size()));
    env->SetLongArrayRegion(shape, 0, static_cast<jsize>(out.shape.size()),
                            reinterpret_cast<const jlong*>(out.shape.data()));

    jobject tensor = env->NewObject(tensor_class, ctor, owned, shape,
                                    static_cast<jint>(out.elementType));
    env->SetObjectArrayElement(result, static_cast<jsize>(i), tensor);
    env->DeleteLocalRef(tensor);
    env->DeleteLocalRef(shape);
    env->DeleteLocalRef(owned);
  }
  return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_org_measly_iree_jni_IreeNative_lastImportOutcomes(JNIEnv* env, jclass,
                                                       jlong handle) {
  IreeRuntime* runtime = AsRuntime(handle);
  if (runtime == nullptr) {
    ThrowJava(env, "lastImportOutcomes on a closed handle");
    return nullptr;
  }
  auto outcomes = runtime->lastImportOutcomes();
  jintArray result = env->NewIntArray(static_cast<jsize>(outcomes.size()));
  std::vector<jint> values(outcomes.size());
  for (size_t i = 0; i < outcomes.size(); ++i) {
    values[i] = outcomes[i] == IreeRuntime::ImportOutcome::kWrapped ? 1 : 0;
  }
  env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()),
                         values.data());
  return result;
}
```

- [ ] **Step 2: Add the shim target**

Append to `native/CMakeLists.txt`:

```cmake
find_package(JNI REQUIRED)
add_library(iree_djl SHARED jni/iree_djl_jni.cpp)
target_include_directories(iree_djl PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(iree_djl PRIVATE iree_djl_core)
# Hide IREE's symbols so they cannot collide with anything else in the JVM.
target_link_options(iree_djl PRIVATE -Wl,--exclude-libs,ALL)
```

- [ ] **Step 3: Build and verify the symbols are exported**

```bash
rm -rf native/build && ./native/build.sh
nm -D --defined-only src/main/resources/native/linux-x86_64/libiree_djl.so | grep Java_org_measly
```

Expected: five `Java_org_measly_iree_jni_IreeNative_*` symbols (`load`, `close`, `invoke`, `lastImportOutcomes`), plus `JNI_OnLoad`/`JNI_OnUnload`.

- [ ] **Step 4: Commit**

```bash
git add native/jni/iree_djl_jni.cpp native/CMakeLists.txt
git commit -m "feat(jni): shim over the facade with an opaque jlong handle

Direct ByteBuffer I/O, one native->jthrow translation point, cached
exception class in JNI_OnLoad. Input addresses are borrowed only for the
duration of the call, which is what keeps the facade's import-or-copy safe."
```

---

### Task 8: Java JNI binding and library loading

**Files:**
- Create: `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`
- Create: `src/main/java/org/measly/iree/jni/IreeNative.java`, `src/main/java/org/measly/iree/jni/IreeTensor.java`
- Create: `src/main/java/org/measly/iree/engine/LibUtils.java`
- Create: `src/test/java/org/measly/iree/jni/IreeNativeTest.java`

**Interfaces:**
- Consumes: the JNI symbols from Task 7
- Produces: `IreeNative.load(byte[], String) -> long`, `IreeNative.invoke(long, ByteBuffer[], long[][], int[]) -> IreeTensor[]`, `IreeNative.close(long)`, `IreeNative.lastImportOutcomes(long) -> int[]`; `IreeTensor(ByteBuffer data, long[] shape, int elementType)`; `LibUtils.loadLibrary()`

- [ ] **Step 1: Write the Gradle files**

Create `settings.gradle.kts`:

```kotlin
rootProject.name = "djl-iree-engine"
```

Create `gradle/libs.versions.toml`:

```toml
[versions]
djl = "0.36.0"
slf4j = "2.0.16"
junit = "5.11.3"

[libraries]
djl-api = { module = "ai.djl:api", version.ref = "djl" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
logback-classic = { module = "ch.qos.logback:logback-classic", version = "1.5.12" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher", version = "1.11.3" }
```

Create `build.gradle.kts`:

```kotlin
plugins {
    `java-library`
}

group = "org.measly"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

repositories { mavenCentral() }

dependencies {
    compileOnly(libs.djl.api)
    compileOnly(libs.slf4j.api)

    testImplementation(libs.djl.api)
    testImplementation(libs.slf4j.api)
    testImplementation(libs.logback.classic)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// LibUtils resolves the native library from IREE_LIBRARY_PATH before falling
// back to the classpath copy, so this variable changes WHICH .so is under test.
// Undeclared, it is invisible to the up-to-date check: point it elsewhere and
// Gradle would replay a cached pass for a run that loaded something else.
tasks.withType<Test>().configureEach {
    inputs.property(
        "ireeLibraryPath",
        providers.environmentVariable("IREE_LIBRARY_PATH").orElse("")
    )
}
```

- [ ] **Step 2: Write the marshalling struct**

Create `src/main/java/org/measly/iree/jni/IreeTensor.java`:

```java
package org.measly.iree.jni;

import java.nio.ByteBuffer;

/** A tensor crossing the JNI boundary: direct buffer, shape, IREE element type. */
public final class IreeTensor {

    private final ByteBuffer data;
    private final long[] shape;
    private final int elementType;

    /** Invoked from native code — keep the signature in sync with the shim. */
    public IreeTensor(ByteBuffer data, long[] shape, int elementType) {
        this.data = data;
        this.shape = shape;
        this.elementType = elementType;
    }

    public ByteBuffer getData() {
        return data;
    }

    public long[] getShape() {
        return shape;
    }

    public int getElementType() {
        return elementType;
    }
}
```

- [ ] **Step 3: Write the library loader**

Create `src/main/java/org/measly/iree/engine/LibUtils.java`:

```java
package org.measly.iree.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Resolves and loads the native shim. IREE_LIBRARY_PATH wins; otherwise the
 * library is extracted from the classpath to a temp file and loaded.
 *
 * <p>No content-addressed cache here, unlike the ExecuTorch engine: that exists
 * to work around Windows refusing to delete a loaded DLL, and this skeleton is
 * Linux-only.
 */
public final class LibUtils {

    private static final String LIB_NAME = "libiree_djl.so";
    private static final String PLATFORM = "linux-x86_64";
    private static boolean loaded;

    private LibUtils() {}

    public static synchronized void loadLibrary() {
        if (loaded) {
            return;
        }
        String override = System.getenv("IREE_LIBRARY_PATH");
        if (override != null && !override.isEmpty()) {
            System.load(Path.of(override).toAbsolutePath().toString());
            loaded = true;
            return;
        }
        System.load(extractFromClasspath().toAbsolutePath().toString());
        loaded = true;
    }

    private static Path extractFromClasspath() {
        String resource = "/native/" + PLATFORM + "/" + LIB_NAME;
        try (InputStream in = LibUtils.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Native library not found on the classpath at " + resource
                                + ". Build it with ./native/build.sh, or set"
                                + " IREE_LIBRARY_PATH to an existing "
                                + LIB_NAME + ".");
            }
            Path dir = Files.createTempDirectory("iree-djl");
            dir.toFile().deleteOnExit();
            Path target = dir.resolve(LIB_NAME);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract " + resource, e);
        }
    }
}
```

- [ ] **Step 4: Write the native declarations**

Create `src/main/java/org/measly/iree/jni/IreeNative.java`:

```java
package org.measly.iree.jni;

import java.nio.ByteBuffer;
import org.measly.iree.engine.LibUtils;

/** The JNI boundary. Every method here has a counterpart in iree_djl_jni.cpp. */
public final class IreeNative {

    static {
        LibUtils.loadLibrary();
    }

    private IreeNative() {}

    /** Returns an opaque handle to the native runtime. Caller must close it. */
    public static native long load(byte[] vmfb, String entryPoint);

    /**
     * Runs the model. Inputs must be direct ByteBuffers; their addresses are
     * borrowed only for the duration of this call.
     */
    public static native IreeTensor[] invoke(
            long handle, ByteBuffer[] inputs, long[][] shapes, int[] elementTypes);

    /** Releases the native runtime. Safe to call once per handle. */
    public static native void close(long handle);

    /**
     * Per-input import outcome from the last invoke: 1 = zero-copy wrap,
     * 0 = staged copy. Exposed so tests can assert what actually happened
     * rather than assuming a borrow.
     */
    public static native int[] lastImportOutcomes(long handle);

    /** Forces the class to initialise, loading the library. */
    public static void ensureLoaded() {
        LibUtils.loadLibrary();
    }
}
```

- [ ] **Step 5: Write the failing JNI round-trip test**

Create `src/test/java/org/measly/iree/jni/IreeNativeTest.java`:

```java
package org.measly.iree.jni;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.junit.jupiter.api.Test;

class IreeNativeTest {

    private static final int F32 = 0x00000120;
    private static final String ENTRY_POINT = "module.add";

    private static byte[] addVmfb() throws IOException {
        try (InputStream in =
                IreeNativeTest.class.getResourceAsStream("/models/add.vmfb")) {
            assertTrue(in != null, "add.vmfb missing — run ./tools/export_add.sh");
            return in.readAllBytes();
        }
    }

    private static ByteBuffer directFloats(float... values) {
        ByteBuffer buffer =
                ByteBuffer.allocateDirect(values.length * Float.BYTES)
                        .order(ByteOrder.nativeOrder());
        buffer.asFloatBuffer().put(values);
        return buffer;
    }

    @Test
    void loadInvokeClose() throws IOException {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT);
        assertTrue(handle != 0L);
        try {
            IreeTensor[] outputs =
                    IreeNative.invoke(
                            handle,
                            new ByteBuffer[] {
                                directFloats(1f, 2f, 3f, 4f),
                                directFloats(10f, 20f, 30f, 40f)
                            },
                            new long[][] {{4L}, {4L}},
                            new int[] {F32, F32});

            assertEquals(1, outputs.length);
            assertArrayEquals(new long[] {4L}, outputs[0].getShape());

            FloatBuffer result =
                    outputs[0].getData().order(ByteOrder.nativeOrder()).asFloatBuffer();
            assertEquals(11f, result.get(0));
            assertEquals(22f, result.get(1));
            assertEquals(33f, result.get(2));
            assertEquals(44f, result.get(3));
        } finally {
            IreeNative.close(handle);
        }
    }

    /**
     * The answer this project exists to produce: does a Java direct ByteBuffer
     * meet IREE's import preconditions, or does it silently stage a copy? The
     * test asserts only that an outcome is reported — it must not prejudge it.
     */
    @Test
    void reportsImportOutcomeForJavaDirectBuffers() throws IOException {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT);
        try {
            IreeNative.invoke(
                    handle,
                    new ByteBuffer[] {directFloats(1f, 2f, 3f, 4f), directFloats(1f, 1f, 1f, 1f)},
                    new long[][] {{4L}, {4L}},
                    new int[] {F32, F32});

            int[] outcomes = IreeNative.lastImportOutcomes(handle);
            assertEquals(2, outcomes.length);
            System.out.println(
                    "JAVA DIRECT BYTEBUFFER IMPORT OUTCOME: "
                            + (outcomes[0] == 1 ? "WRAPPED (zero-copy)" : "STAGED (copied)"));
        } finally {
            IreeNative.close(handle);
        }
    }

    @Test
    void rejectsCorruptModel() {
        byte[] garbage = new byte[256];
        assertThrows(RuntimeException.class, () -> IreeNative.load(garbage, ENTRY_POINT));
    }

    @Test
    void rejectsUnknownEntryPoint() throws IOException {
        assertThrows(
                RuntimeException.class,
                () -> IreeNative.load(addVmfb(), "module.does_not_exist"));
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

```bash
./gradlew test --tests 'org.measly.iree.jni.IreeNativeTest'
```

Expected: FAIL — `UnsatisfiedLinkError` or the library-not-found `IllegalStateException` if the shim was not staged.

- [ ] **Step 7: Build the shim and re-run**

```bash
./native/build.sh
./gradlew test --tests 'org.measly.iree.jni.IreeNativeTest'
```

Expected: 4 tests pass, with `JAVA DIRECT BYTEBUFFER IMPORT OUTCOME: ...` in the output.

**Record that line.** If it says STAGED while the native aligned-buffer test said WRAPPED, that is the headline finding: JVM direct buffers do not meet IREE's alignment preconditions, and inputs are silently copying.

- [ ] **Step 8: Commit**

```bash
git add build.gradle.kts settings.gradle.kts gradle src/main/java src/test/java
git commit -m "feat(jni): Java binding, library loader, and round-trip tests

IreeNative declares the boundary; LibUtils resolves IREE_LIBRARY_PATH then
the classpath. Tests assert the golden vector through JNI and report the
import outcome for a real Java direct ByteBuffer.

Java direct ByteBuffer import outcome: <WRAPPED|STAGED>"
```

---

### Task 9: DJL engine layer

**Files:**
- Create: `src/main/java/org/measly/iree/engine/IreeEngineProvider.java`, `IreeEngine.java`, `IreeModel.java`, `IreeSymbolBlock.java`, `IreeNDManager.java`, `IreeNDArray.java`, `IreeDataTypes.java`
- Create: `src/main/resources/META-INF/services/ai.djl.engine.EngineProvider`
- Create: `src/test/java/org/measly/iree/AddModelIT.java`

**Interfaces:**
- Consumes: `IreeNative`, `IreeTensor`, `LibUtils` from Task 8
- Produces: a DJL `Engine` named `IREE`, loadable via `Criteria`/`Model.newInstance("...", "IREE")`

- [ ] **Step 1: Write the dtype mapping**

Create `src/main/java/org/measly/iree/engine/IreeDataTypes.java`:

```java
package org.measly.iree.engine;

import ai.djl.ndarray.types.DataType;

/**
 * Maps DJL data types to iree_hal_element_type_t values.
 *
 * <p>Only the types the skeleton exercises are mapped. Values are computed by
 * IREE's IREE_HAL_ELEMENT_TYPE_VALUE macro; verify against
 * iree/hal/buffer_view.h before adding more.
 */
public final class IreeDataTypes {

    public static final int FLOAT_32 = 0x00000120;
    public static final int SINT_32 = 0x00000220;

    private IreeDataTypes() {}

    public static int toIree(DataType type) {
        switch (type) {
            case FLOAT32:
                return FLOAT_32;
            case INT32:
                return SINT_32;
            default:
                throw new UnsupportedOperationException(
                        "Unsupported data type for the IREE skeleton: " + type);
        }
    }

    public static DataType fromIree(int elementType) {
        switch (elementType) {
            case FLOAT_32:
                return DataType.FLOAT32;
            case SINT_32:
                return DataType.INT32;
            default:
                throw new UnsupportedOperationException(
                        "Unsupported IREE element type: 0x"
                                + Integer.toHexString(elementType));
        }
    }
}
```

- [ ] **Step 2: Write the failing integration test**

Create `src/test/java/org/measly/iree/AddModelIT.java`:

```java
package org.measly.iree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** The walking-skeleton gate: add.vmfb through a real DJL Model. */
class AddModelIT {

    @Test
    void runsAddThroughDjl() throws Exception {
        Path modelDir = Paths.get("src/test/resources/models");

        try (Model model = Model.newInstance("add", "IREE")) {
            model.load(modelDir, "add");

            try (NDManager manager = model.getNDManager().newSubManager()) {
                NDArray lhs = manager.create(new float[] {1f, 2f, 3f, 4f}, new Shape(4));
                NDArray rhs = manager.create(new float[] {10f, 20f, 30f, 40f}, new Shape(4));

                NDList outputs = model.getBlock().forward(null, new NDList(lhs, rhs), false);

                assertEquals(1, outputs.size());
                assertArrayEquals(new long[] {4L}, outputs.get(0).getShape().getShape());
                assertArrayEquals(
                        new float[] {11f, 22f, 33f, 44f},
                        outputs.get(0).toFloatArray(),
                        1e-6f);
            }
        }
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

```bash
./gradlew test --tests 'org.measly.iree.AddModelIT'
```

Expected: FAIL — no engine named `IREE` is registered.

- [ ] **Step 4: Write the NDArray and NDManager**

Create `src/main/java/org/measly/iree/engine/IreeNDArray.java`:

```java
package org.measly.iree.engine;

import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.NDArrayAdapter;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.ndarray.types.SparseFormat;
import java.nio.ByteBuffer;

/**
 * Minimal NDArray backed by a direct ByteBuffer.
 *
 * <p>Extends NDArrayAdapter so the large NDArray surface throws
 * UnsupportedOperationException by default: this skeleton moves tensors across
 * the boundary and does no maths on the Java side.
 */
public class IreeNDArray extends NDArrayAdapter {

    private ByteBuffer data;

    IreeNDArray(NDManager manager, ByteBuffer data, Shape shape, DataType dataType) {
        super(manager, manager, shape, dataType, java.util.UUID.randomUUID().toString());
        this.data = data;
    }

    @Override
    public void intern(ai.djl.ndarray.NDArray replaced) {
        this.data = ((IreeNDArray) replaced).data;
    }

    @Override
    public void detach() {
        manager = ai.djl.ndarray.NDManager.newBaseManager();
    }

    @Override
    public ByteBuffer toByteBuffer(boolean tryDirect) {
        data.rewind();
        return data;
    }

    @Override
    public SparseFormat getSparseFormat() {
        return SparseFormat.DENSE;
    }
}
```

Create `src/main/java/org/measly/iree/engine/IreeNDManager.java`:

```java
package org.measly.iree.engine;

import ai.djl.Device;
import ai.djl.engine.Engine;
import ai.djl.ndarray.BaseNDManager;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal tensor factory. Direct buffers only — the JNI layer requires them. */
public class IreeNDManager extends BaseNDManager {

    private static final IreeNDManager SYSTEM_MANAGER = new SystemManager();

    private IreeNDManager(NDManager parent, Device device) {
        super(parent, device);
    }

    static IreeNDManager getSystemManager() {
        return SYSTEM_MANAGER;
    }

    @Override
    public IreeNDManager newSubManager(Device device) {
        IreeNDManager manager = new IreeNDManager(this, device);
        attachUncappedInternal(manager.uid, manager);
        return manager;
    }

    @Override
    public Engine getEngine() {
        return Engine.getEngine(IreeEngine.ENGINE_NAME);
    }

    @Override
    public NDArray create(Buffer data, Shape shape, DataType dataType) {
        int size = Math.toIntExact(shape.size()) * dataType.getNumOfBytes();
        ByteBuffer direct = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        BaseNDManager.copyBuffer(data, direct);
        direct.rewind();
        return new IreeNDArray(this, direct, shape, dataType);
    }

    NDArray create(ByteBuffer directData, Shape shape, DataType dataType) {
        return new IreeNDArray(this, directData, shape, dataType);
    }

    /** The root manager, which is never closed. */
    private static final class SystemManager extends IreeNDManager
            implements SystemNDManager {

        SystemManager() {
            super(null, null);
        }
    }
}
```

Add `import java.nio.Buffer;` to `IreeNDManager.java`.

> The exact `NDArrayAdapter` / `BaseNDManager` signatures vary across DJL versions. Confirm against the DJL 0.36.0 sources before assuming these compile:
> ```bash
> find ~/.gradle/caches/modules-2 -name 'api-0.36.0-sources.jar' 2>/dev/null
> ```
> If `NDArrayAdapter` is absent, implement `NDArray` directly and throw `UnsupportedOperationException` from every method the skeleton does not need. Compare with `EtNDArray`/`EtNDManager` in `/home/corey/workspace/djl-executorch-engine/src/main/java/org/measly/executorch/engine/`, which solved this exact problem against the same DJL version.

- [ ] **Step 5: Write the SymbolBlock**

Create `src/main/java/org/measly/iree/engine/IreeSymbolBlock.java`:

```java
package org.measly.iree.engine;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.AbstractSymbolBlock;
import ai.djl.training.ParameterStore;
import ai.djl.util.PairList;
import java.nio.ByteBuffer;
import org.measly.iree.jni.IreeNative;
import org.measly.iree.jni.IreeTensor;

/**
 * Runs the model's entry point.
 *
 * <p><b>Not thread-safe on the same model.</b> One Model/Predictor per thread,
 * and never close a model with a forward in flight. An IREE session is not safe
 * for concurrent invocation; with the local-sync driver this contract holds all
 * the way down rather than only at this boundary.
 */
public class IreeSymbolBlock extends AbstractSymbolBlock implements AutoCloseable {

    private final IreeNDManager manager;
    private long handle;

    IreeSymbolBlock(IreeNDManager manager, long handle) {
        this.manager = manager;
        this.handle = handle;
    }

    @Override
    protected NDList forwardInternal(
            ParameterStore parameterStore,
            NDList inputs,
            boolean training,
            PairList<String, Object> params) {

        if (handle == 0L) {
            throw new IllegalStateException("forward on a closed model");
        }

        int count = inputs.size();
        ByteBuffer[] buffers = new ByteBuffer[count];
        long[][] shapes = new long[count][];
        int[] types = new int[count];

        for (int i = 0; i < count; i++) {
            NDArray input = inputs.get(i);
            ByteBuffer buffer = input.toByteBuffer(true);
            if (!buffer.isDirect()) {
                throw new IllegalArgumentException(
                        "IREE inputs must be backed by direct buffers; input "
                                + i + " was not");
            }
            buffers[i] = buffer;
            shapes[i] = input.getShape().getShape();
            types[i] = IreeDataTypes.toIree(input.getDataType());
        }

        IreeTensor[] outputs = IreeNative.invoke(handle, buffers, shapes, types);

        NDList result = new NDList(outputs.length);
        for (IreeTensor tensor : outputs) {
            result.add(
                    manager.create(
                            tensor.getData(),
                            new Shape(tensor.getShape()),
                            IreeDataTypes.fromIree(tensor.getElementType())));
        }
        return result;
    }

    /** Per-input import outcome from the last forward: 1 = wrapped, 0 = staged. */
    public int[] getLastImportOutcomes() {
        return IreeNative.lastImportOutcomes(handle);
    }

    @Override
    public void close() {
        if (handle != 0L) {
            IreeNative.close(handle);
            handle = 0L;
        }
    }
}
```

- [ ] **Step 6: Write the Model, Engine, and Provider**

Create `src/main/java/org/measly/iree/engine/IreeModel.java`:

```java
package org.measly.iree.engine;

import ai.djl.BaseModel;
import ai.djl.Model;
import ai.djl.ndarray.NDManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.measly.iree.jni.IreeNative;

/** Loads a .vmfb and owns the native handle. */
public class IreeModel extends BaseModel {

    /**
     * IREE prefixes exported functions with the module name. Confirmed against
     * add.vmfb with iree-dump-module — see tools/export_add.sh.
     */
    private static final String DEFAULT_ENTRY_POINT = "module.add";

    IreeModel(String name, NDManager manager) {
        super(name);
        this.manager = manager;
        this.manager.setName("ireeModel");
        this.dataType = ai.djl.ndarray.types.DataType.FLOAT32;
    }

    @Override
    public void load(Path modelPath, String prefix, Map<String, ?> options)
            throws IOException {
        setModelDir(modelPath);
        if (prefix == null) {
            prefix = modelName;
        }
        Path file = modelDir.resolve(prefix + ".vmfb");
        if (!Files.isRegularFile(file)) {
            throw new java.io.FileNotFoundException("No .vmfb found at " + file);
        }

        String entryPoint = DEFAULT_ENTRY_POINT;
        if (options != null && options.get("entryPoint") != null) {
            entryPoint = options.get("entryPoint").toString();
        }

        byte[] bytes = Files.readAllBytes(file);
        long handle = IreeNative.load(bytes, entryPoint);
        block = new IreeSymbolBlock((IreeNDManager) manager, handle);
    }

    @Override
    public void close() {
        if (block instanceof IreeSymbolBlock symbolBlock) {
            symbolBlock.close();
        }
        super.close();
    }
}
```

Create `src/main/java/org/measly/iree/engine/IreeEngine.java`:

```java
package org.measly.iree.engine;

import ai.djl.Device;
import ai.djl.Model;
import ai.djl.engine.Engine;
import ai.djl.ndarray.NDManager;
import ai.djl.nn.SymbolBlock;
import ai.djl.training.GradientCollector;

/** DJL engine backed by the IREE runtime. CPU only, inference only. */
public final class IreeEngine extends Engine {

    public static final String ENGINE_NAME = "IREE";
    static final int RANK = 10;

    private IreeEngine() {}

    static Engine newInstance() {
        LibUtils.loadLibrary();
        return new IreeEngine();
    }

    @Override
    public String getEngineName() {
        return ENGINE_NAME;
    }

    @Override
    public int getRank() {
        return RANK;
    }

    @Override
    public String getVersion() {
        return "0.1.0-SNAPSHOT";
    }

    @Override
    public boolean hasCapability(String capability) {
        return false;
    }

    @Override
    public Model newModel(String name, Device device) {
        return new IreeModel(name, newBaseManager(device));
    }

    @Override
    public SymbolBlock newSymbolBlock(NDManager manager) {
        throw new UnsupportedOperationException(
                "IREE models are loaded from .vmfb; build a block via Model.load");
    }

    @Override
    public NDManager newBaseManager() {
        return newBaseManager(null);
    }

    @Override
    public NDManager newBaseManager(Device device) {
        return IreeNDManager.getSystemManager().newSubManager(device);
    }

    @Override
    public GradientCollector newGradientCollector() {
        throw new UnsupportedOperationException("IREE engine is inference-only");
    }

    @Override
    public void setRandomSeed(int seed) {
        throw new UnsupportedOperationException("IREE engine is inference-only");
    }
}
```

Create `src/main/java/org/measly/iree/engine/IreeEngineProvider.java`:

```java
package org.measly.iree.engine;

import ai.djl.engine.Engine;
import ai.djl.engine.EngineProvider;

/** Registered via META-INF/services/ai.djl.engine.EngineProvider. */
public class IreeEngineProvider implements EngineProvider {

    private static volatile Engine engine;

    @Override
    public String getEngineName() {
        return IreeEngine.ENGINE_NAME;
    }

    @Override
    public int getEngineRank() {
        return IreeEngine.RANK;
    }

    @Override
    public Engine getEngine() {
        if (engine == null) {
            synchronized (IreeEngineProvider.class) {
                if (engine == null) {
                    engine = IreeEngine.newInstance();
                }
            }
        }
        return engine;
    }
}
```

Create `src/main/resources/META-INF/services/ai.djl.engine.EngineProvider` containing exactly:

```
org.measly.iree.engine.IreeEngineProvider
```

- [ ] **Step 7: Run the integration test**

```bash
./native/build.sh
./gradlew test --tests 'org.measly.iree.AddModelIT'
```

Expected: PASS.

If DJL API signatures do not match, diff against the ExecuTorch equivalents — they target the same DJL version and are known to compile:

```bash
diff <(ls /home/corey/workspace/djl-executorch-engine/src/main/java/org/measly/executorch/engine/) \
     <(ls src/main/java/org/measly/iree/engine/)
```

- [ ] **Step 8: Run the whole suite**

```bash
./gradlew test
```

Expected: all tests pass — `IreeNativeTest` (4) and `AddModelIT` (1).

- [ ] **Step 9: Commit**

```bash
git add src/main/java src/main/resources src/test/java
git commit -m "feat(engine): minimal DJL engine running add.vmfb end to end

Provider through SymbolBlock, with a float32-only NDArray surface. No
MethodMeta: the entry point and signature are known ahead of time and
confirmed with iree-dump-module rather than introspected at runtime.

AddModelIT green — the walking skeleton walks."
```

---

### Task 10: Findings write-up

**Files:**
- Create: `docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: results from Tasks 4, 6, and 8
- Produces: the go/no-go recommendation this project exists to deliver

- [ ] **Step 1: Write the findings document**

Create `docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md` with these sections, filled in from observed output — **not** from expectation:

```markdown
# DJL IREE Engine Skeleton — Findings

## Verdict
<GO | NO-GO | GO WITH CAVEATS> — one paragraph.

## Import behaviour (the headline question)
- Native, 64-byte-aligned host allocation: <WRAPPED | STAGED>
- Java direct ByteBuffer through JNI: <WRAPPED | STAGED>
- Interpretation: <if these differ, JVM buffers fail IREE's import
  preconditions and every input silently copies — quantify the cost for a
  MobileNet-sized input before committing to the borrow-in design>

## Sanitizer results
- ASan: <clean | findings>
- LSan: <clean | findings>
- Cycles run: <N>
- Any leak traced to a dropped iree_status_t: <yes/no — detail>

## Effort assessment
- RAII-over-C-API: <how much friction in practice>
- Surprises vs the bootstrap outline: <list>
- Corrections the headers forced: value-type iree_runtime_call_t; the
  non-copying append-from-memory; <any others found>

## Recommended next milestone
<MobileNet parity | fix X first | stop>
```

- [ ] **Step 2: Write the README**

Create `README.md`:

```markdown
# djl-iree-engine

A [DJL](https://djl.ai/) engine that runs [IREE](https://iree.dev/) `.vmfb` models.

**Status: walking skeleton.** This exists to answer whether IREE works as a DJL
engine and at what cost. It runs a trivial `add` model end to end. It is not a
product — see the deferred list in the design doc.

## Prerequisites

- An IREE build tree (default `/home/corey/workspace/iree-build`) and matching
  source tree (`/home/corey/workspace/iree`). Override with `IREE_INSTALL` and
  `IREE_SOURCE`.
- `iree-compile` from pip (`uv pip install iree-base-compiler`) — only needed to
  regenerate the test model.
- JDK 17, CMake, Ninja, a C++20 compiler.

## Build and test

```bash
./tools/export_add.sh    # regenerate add.vmfb (optional; it is committed)
./native/build.sh        # build the shim and stage it into resources
./gradlew test           # JVM tests
```

## Native QA

```bash
./native/build/iree_runtime_test                      # Catch2 units
rm -rf native/build && ./native/build.sh -DIREE_DJL_SANITIZE=ON
ASAN_OPTIONS=detect_leaks=1 ./native/build/iree_leak_harness "" 200
```

There is no TSan leg: the `local-sync` HAL driver runs all work inline on the
calling thread, so there are no IREE-internal threads to inspect.

## Threading

`IreeSymbolBlock.forward()` is not thread-safe on the same model. Use one
`Model`/`Predictor` per thread, and never close a model with a forward in
flight.

## Docs

- Design: `docs/superpowers/specs/2026-07-19-djl-iree-engine-skeleton-design.md`
- Findings: `docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md`
- Plan: `docs/superpowers/plans/2026-07-19-djl-iree-engine-skeleton.md`
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md README.md
git commit -m "docs: findings and README for the IREE skeleton

Records the import-outcome answer, sanitizer results, and the go/no-go
recommendation."
```

---

## Self-Review

**Spec coverage.** Every spec section maps to a task: repository layout → Tasks 1/8/9; native core with the four design pillars → Tasks 3/4; `lastImportOutcomes` as API surface → Tasks 3/4/7/8; `local-sync` → Task 3 and the Global Constraints; import-or-copy → Task 4; copy-out with nothing outliving the call → Task 4; JNI shim → Task 7; DJL layer with no `IreeMethodMeta` → Task 9; three-layer test strategy → Tasks 3/4/5 (Catch2), 6 (ASan/LSan), 8/9 (JVM); import question answered at two levels → Task 4 Step 1 (native aligned) and Task 8 Step 5 (Java direct buffer); the four milestone gates → Tasks 3/4, 6, 7/8, 9; the §12 verify-against-headers checklist → done during planning, with the five corrections recorded above; deferred items → not implemented, restated in the README.

**Gap found and closed:** the spec's milestone list ends at "AddModelIT green" with no step that actually delivers the go/no-go answer to a human. Added Task 10.

**Type consistency.** `IreeRuntime::Load`, `Invoke`, `lastImportOutcomes`, `ImportOutcome::{kWrapped,kStaged}`, `InputDesc{data,nbytes,shape,elementType}`, `OutputBuffer{shape,elementType,data}`, and `IreeTensor(ByteBuffer,long[],int)` are used identically in every task that references them. The JNI method names in Task 7 match the `native` declarations in Task 8 (`load`, `invoke`, `close`, `lastImportOutcomes`). `IREE_DJL_ADD_VMFB` is defined for both the test and harness targets. Element-type constant `0x00000120` is used consistently in C++ (`kF32`), the harness, and Java (`IreeDataTypes.FLOAT_32`), and every site that uses it carries the instruction to verify it against `buffer_view.h`.

**Known-risk steps** flagged inline with fallbacks rather than left to fail silently: `iree-compile` flag drift (Task 2 Step 3), `iree_status_to_string` arity (Task 3 Step 1), archive under-linking (Task 1 Step 6), the element-type constant (Task 4 Step 4), and DJL `NDArrayAdapter`/`BaseNDManager` signature drift (Task 9 Step 4).
