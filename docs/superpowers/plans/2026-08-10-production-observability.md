# Production Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the IREE engine a production monitoring surface — `IreeEngineStats.snapshot()` plus a JMX MXBean — reporting configuration, per-model timing, zero-copy-vs-staged import rates, and native memory, with no measurable hot-path cost.

**Architecture:** Timing counters live in Java on a holder owned by `IreeSymbolBlock` (single-writer by the engine's one-model-per-thread contract). Import counts and memory live natively on `RuntimeState` and are read through one new cold-path JNI method returning a `long[6]`. A static registry holds each model's block **weakly** and its counters **strongly**, so observability can never pin the `NDManager` graph, and a per-block `statsLock` serialises `close()` against the stats read to prevent a use-after-free.

**Tech Stack:** Java 17, DJL 0.36.0, slf4j-api 2.0.17 (transitive via `ai.djl:api`), JMX (`java.lang.management`), JUnit 5, C++20, IREE runtime dist `v3.11.0-11`, Catch2, CMake + Ninja, Gradle Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-08-10-production-observability-design.md`

## Global Constraints

- **Branch:** `feature/observability`. Already created; do not branch again.
- **Hot path is sacred.** `IreeSymbolBlock.forwardInternal()` and `RunCall`'s per-input loop may gain only `System.nanoTime()` brackets and plain integer increments. No allocation, no locks, no JNI round-trips added to either.
- **`snapshot()` never throws.** Any failure degrades to a partial entry or an `unknown`/`-1` field.
- **Byte gauges: `-1` means "unavailable", `0` means "genuinely zero".** Process totals must skip `-1` so it never sums in as a value.
- **Unresolvable string config fields report `"unknown"`, never `null`.**
- **The `MXBean` suffix on `IreeEngineStatsMXBean` is load-bearing.** There is no annotation; renaming the interface silently degrades it to a Standard MBean and registration fails with `NotCompliantMBeanException`.
- **Value types (`IreeStatsSnapshot`, `IreeModelStats`) must be getter-only JavaBeans** — public getters, no setters, no public fields — or MXBean `CompositeData` conversion breaks.
- **JMX object name:** `org.measly.iree:type=IreeEngineStats`. **Opt-out property:** `ai.djl.iree.jmx_enabled=false`.
- **Native counters are `uint64_t`; Java-side counters are `volatile long`.**
- **Java package:** `org.measly.iree.engine` for stats classes, `org.measly.iree.jni` for JNI declarations.
- **Native namespace:** `measly::iree`.
- **Rebuilding native code:** `bash native/build.sh` (CMake + Ninja). The JNI shim is only built when a JDK is present.
- **Do not add USDT/DTrace probes.** Explicitly out of scope.

---

## File Structure

**Native (C++):**
- `native/core/iree_runtime.h` — MODIFY: add `struct RuntimeStats`, `IreeRuntime::Stats()`, `AliveRuntimeCount()`.
- `native/core/iree_runtime.cpp` — MODIFY: counters on `RuntimeState`, increments in `RunCall`, `Stats()` body, runtime census atomic, defined destructor.
- `native/CMakeLists.txt` — MODIFY: propagate `IREE_STATISTICS_ENABLE` from the dist's `BUILDINFO`.
- `native/test/iree_runtime_test.cpp` — MODIFY: Catch2 coverage for counters, `Stats()`, census.
- `native/harness/iree_leak_harness.cpp` — MODIFY: intra-runtime invoke loop with gauge assertions.
- `native/jni/iree_djl_jni.cpp` — MODIFY: `stats()` and `aliveRuntimes()` entry points.

**Java (main):**
- `src/main/java/org/measly/iree/jni/IreeNative.java` — MODIFY: two native declarations plus `long[]` index constants.
- `src/main/java/org/measly/iree/engine/LibUtils.java` — MODIFY: record and expose the loaded library path.
- `src/main/java/org/measly/iree/engine/IreeModelCounters.java` — CREATE: mutable per-model counters, single-writer.
- `src/main/java/org/measly/iree/engine/IreeSymbolBlock.java` — MODIFY: timing brackets, `statsLock`, `toStats()`, deregistration in `close()`.
- `src/main/java/org/measly/iree/engine/IreeModel.java` — MODIFY: measure `loadNanos`, attach counters, register.
- `src/main/java/org/measly/iree/engine/IreeModelStats.java` — CREATE: immutable per-model value type.
- `src/main/java/org/measly/iree/engine/IreeStatsSnapshot.java` — CREATE: immutable snapshot value type.
- `src/main/java/org/measly/iree/engine/IreeEngineStats.java` — CREATE: registry, rollup, `snapshot()`, JMX lifecycle.
- `src/main/java/org/measly/iree/engine/IreeEngineStatsMXBean.java` — CREATE: the MXBean interface.

**Build:**
- `build.gradle.kts` — MODIFY: `generateIreeRuntimeInfo` task emitting `IreeRuntimeInfo.java`; register a `stress` test task.

**Java (test):**
- `src/test/java/org/measly/iree/engine/IreeModelCountersTest.java` — CREATE.
- `src/test/java/org/measly/iree/engine/IreeEngineStatsTest.java` — CREATE.
- `src/test/java/org/measly/iree/engine/IreeEngineStatsJmxIT.java` — CREATE.
- `src/test/java/org/measly/iree/engine/StatsConcurrencyIT.java` — CREATE.
- `src/test/java/org/measly/iree/jni/IreeNativeTest.java` — MODIFY.
- `src/test/java/org/measly/iree/LeakStressTest.java` — MODIFY.

**Docs:**
- `README.md` — MODIFY: observability section.
- `docs/panama-research-sketch.md` — MODIFY: the six corrections from the spec's rider.
- `docs/superpowers/specs/2026-08-10-production-observability-design.md` — MODIFY: record measured overhead.

---

### Task 1: Native import counters, `RuntimeStats`, and `IreeRuntime::Stats()`

**Files:**
- Modify: `native/core/iree_runtime.h`
- Modify: `native/core/iree_runtime.cpp`
- Test: `native/test/iree_runtime_test.cpp`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `measly::iree::RuntimeStats` (POD with fields `wrappedImports`, `stagedImports`, `stagingBytes`, `deviceBytesPeak`, `deviceBytesLive`, `statisticsAvailable`) and `RuntimeStats IreeRuntime::Stats() const;`. Tasks 4, 9 consume these.

**Background for the implementer:**

`RunCall` (`native/core/iree_runtime.cpp:320`) is the single input-preparation path — both `IreeRuntime::Invoke` (`:376`) and `IreeRuntime::InvokeViews` (`:407`) delegate to it, so one increment site covers both. Do not add increments anywhere else.

`ImportOrCopy` writes the per-input outcome through its out-parameter into `state.lastImportOutcomes[i]`. Read that value back after the call to decide which counter to bump — do not duplicate `ImportOrCopy`'s decision logic.

`stagingBytes` is the sum of `RuntimeState::cachedStagingSizes`, which is populated only by the cached staging modes. Under `StagingMode::kAllocatePerCall` (the default for the plain `Load` overloads used by tests and the harness) that vector stays empty and the gauge is structurally `0`. The JNI always loads with `kCachedMapWrite`, so this only affects native tests — which is why the test below loads with the explicit 5-argument overload.

- [ ] **Step 1: Write the failing test**

Append to `native/test/iree_runtime_test.cpp` (the file already provides `ReadFile`, `kAddVmfb`, `kEntryPoint`, `kF32` and includes `core/aligned_alloc.h`):

```cpp
TEST_CASE("Stats counts a wrapped import for an aligned host buffer") {
  auto vmfb = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint, "local-sync");

  // AlignedAlloc gives the 64-byte alignment IREE requires to import zero-copy.
  auto* lhs = static_cast<float*>(measly::iree::AlignedAlloc(4 * sizeof(float)));
  auto* rhs = static_cast<float*>(measly::iree::AlignedAlloc(4 * sizeof(float)));
  for (int i = 0; i < 4; ++i) {
    lhs[i] = static_cast<float>(i + 1);
    rhs[i] = static_cast<float>((i + 1) * 10);
  }
  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, 4 * sizeof(float), {4}, kF32},
      {rhs, 4 * sizeof(float), {4}, kF32},
  };

  auto before = runtime->Stats();
  REQUIRE(before.wrappedImports == 0);
  REQUIRE(before.stagedImports == 0);

  (void)runtime->Invoke(inputs);

  auto after = runtime->Stats();
  REQUIRE(after.wrappedImports == 2);
  REQUIRE(after.stagedImports == 0);

  measly::iree::AlignedFree(lhs);
  measly::iree::AlignedFree(rhs);
}

TEST_CASE("Stats counts a staged import for a misaligned host buffer") {
  auto vmfb = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint, "local-sync");

  // Deliberately offset past the 64-byte boundary so the import precondition fails
  // and ImportOrCopy takes the staging fallback.
  auto* block = static_cast<std::byte*>(measly::iree::AlignedAlloc(256));
  auto* lhs = reinterpret_cast<float*>(block + 4);
  auto* rhs = reinterpret_cast<float*>(block + 128 + 4);
  for (int i = 0; i < 4; ++i) {
    lhs[i] = 1.0f;
    rhs[i] = 2.0f;
  }
  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, 4 * sizeof(float), {4}, kF32},
      {rhs, 4 * sizeof(float), {4}, kF32},
  };

  (void)runtime->Invoke(inputs);

  auto after = runtime->Stats();
  REQUIRE(after.stagedImports == 2);
  REQUIRE(after.wrappedImports == 0);

  measly::iree::AlignedFree(block);
}

TEST_CASE("Stats counters accumulate identically through Invoke and InvokeViews") {
  auto vmfb = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint, "local-sync");

  auto* lhs = static_cast<float*>(measly::iree::AlignedAlloc(4 * sizeof(float)));
  auto* rhs = static_cast<float*>(measly::iree::AlignedAlloc(4 * sizeof(float)));
  for (int i = 0; i < 4; ++i) {
    lhs[i] = 1.0f;
    rhs[i] = 2.0f;
  }
  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, 4 * sizeof(float), {4}, kF32},
      {rhs, 4 * sizeof(float), {4}, kF32},
  };

  (void)runtime->Invoke(inputs);
  REQUIRE(runtime->Stats().wrappedImports == 2);

  (void)runtime->InvokeViews(inputs);
  runtime->ReleaseOutputs();
  REQUIRE(runtime->Stats().wrappedImports == 4);

  measly::iree::AlignedFree(lhs);
  measly::iree::AlignedFree(rhs);
}

TEST_CASE("Stats reports staging bytes that grow then plateau under cached staging") {
  auto vmfb = ReadFile(kAddVmfb);
  // The 5-argument overload is required: kAllocatePerCall retains no cached
  // staging buffers, so stagingBytes would be structurally zero.
  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint, "local-sync",
                                   std::span<const measly::iree::ParameterScope>{},
                                   IreeRuntime::StagingMode::kCachedMapWrite);

  auto* block = static_cast<std::byte*>(measly::iree::AlignedAlloc(256));
  auto* lhs = reinterpret_cast<float*>(block + 4);
  auto* rhs = reinterpret_cast<float*>(block + 128 + 4);
  for (int i = 0; i < 4; ++i) {
    lhs[i] = 1.0f;
    rhs[i] = 2.0f;
  }
  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, 4 * sizeof(float), {4}, kF32},
      {rhs, 4 * sizeof(float), {4}, kF32},
  };

  REQUIRE(runtime->Stats().stagingBytes == 0);
  (void)runtime->Invoke(inputs);
  const uint64_t afterFirst = runtime->Stats().stagingBytes;
  REQUIRE(afterFirst > 0);

  for (int i = 0; i < 20; ++i) {
    (void)runtime->Invoke(inputs);
  }
  // Grow-only per-slot buffers: same input sizes must not grow the footprint.
  REQUIRE(runtime->Stats().stagingBytes == afterFirst);

  measly::iree::AlignedFree(block);
}

TEST_CASE("Stats reports device bytes returning to baseline after ReleaseOutputs") {
  auto vmfb = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint, "local-sync");
  REQUIRE(runtime->Stats().statisticsAvailable);

  auto* lhs = static_cast<float*>(measly::iree::AlignedAlloc(4 * sizeof(float)));
  auto* rhs = static_cast<float*>(measly::iree::AlignedAlloc(4 * sizeof(float)));
  for (int i = 0; i < 4; ++i) {
    lhs[i] = 1.0f;
    rhs[i] = 2.0f;
  }
  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, 4 * sizeof(float), {4}, kF32},
      {rhs, 4 * sizeof(float), {4}, kF32},
  };

  const uint64_t baseline = runtime->Stats().deviceBytesLive;
  (void)runtime->InvokeViews(inputs);
  REQUIRE(runtime->Stats().deviceBytesLive > baseline);
  runtime->ReleaseOutputs();
  REQUIRE(runtime->Stats().deviceBytesLive == baseline);
  REQUIRE(runtime->Stats().deviceBytesPeak > 0);

  measly::iree::AlignedFree(lhs);
  measly::iree::AlignedFree(rhs);
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
bash native/build.sh
```

Expected: **compile failure**, `no member named 'Stats' in 'measly::iree::IreeRuntime'`.

- [ ] **Step 3: Declare `RuntimeStats` and `Stats()` in the header**

In `native/core/iree_runtime.h`, add after the `OutputLayout` struct:

```cpp
// Cold-path observability read. Never called from Invoke/InvokeViews.
//
// stagingBytes is OURS and exact: the sum of the grow-only per-slot staging
// buffers the cached staging modes retain. It is structurally 0 under
// kAllocatePerCall, which retains none.
//
// deviceBytes* come from IREE's HAL allocator statistics. Each runtime owns its
// own device, so these are already scoped to exactly one model. When
// statisticsAvailable is false the two device figures are 0 and meaningless —
// the caller is responsible for reporting them as "unavailable".
struct RuntimeStats {
  uint64_t wrappedImports;
  uint64_t stagedImports;
  uint64_t stagingBytes;
  uint64_t deviceBytesPeak;
  uint64_t deviceBytesLive;
  bool statisticsAvailable;
};
```

and inside `class IreeRuntime`'s public section, after `lastImportOutcomes()`:

```cpp
  // Cumulative, monotonic, per-runtime. Unlike lastImportOutcomes() these have
  // no validity window: they are safe to read at any time between construction
  // and destruction.
  RuntimeStats Stats() const;
```

Add `#include <cstdint>` if not already present (it is, at the top of the file).

- [ ] **Step 4: Add the counters and increment them in `RunCall`**

In `native/core/iree_runtime.cpp`, add to `struct RuntimeState` (after the `lastImportOutcomes` member at line 33):

```cpp
  // Cumulative import outcomes, for the observability snapshot. Incremented in
  // RunCall, which is the single input-preparation path for both Invoke and
  // InvokeViews. Single-writer by the caller contract (one model per thread).
  uint64_t wrappedImports = 0;
  uint64_t stagedImports = 0;
```

In `RunCall`, replace the input loop body so the outcome is counted after `ImportOrCopy` returns:

```cpp
  for (size_t i = 0; i < inputs.size(); ++i) {
    auto view = ImportOrCopy(state.device.get(), allocator, inputs[i], i, state,
                             &state.lastImportOutcomes[i]);
    // Count the outcome ImportOrCopy just recorded rather than re-deriving it.
    if (state.lastImportOutcomes[i] == IreeRuntime::ImportOutcome::kWrapped) {
      ++state.wrappedImports;
    } else {
      ++state.stagedImports;
    }
    IREE_CHECK_OR_THROW(iree_runtime_call_inputs_push_back_buffer_view(
        call.get(), view.get()));
    input_views.push_back(std::move(view));
  }
```

- [ ] **Step 5: Implement `Stats()`**

In `native/core/iree_runtime.cpp`, add after the `lastImportOutcomes()` definition (line 208-210):

```cpp
RuntimeStats IreeRuntime::Stats() const {
  RuntimeStats out{};
  out.wrappedImports = state_->wrappedImports;
  out.stagedImports = state_->stagedImports;

  uint64_t staging = 0;
  for (iree_device_size_t size : state_->cachedStagingSizes) {
    staging += static_cast<uint64_t>(size);
  }
  out.stagingBytes = staging;

#if IREE_STATISTICS_ENABLE
  out.statisticsAvailable = true;
  iree_hal_allocator_statistics_t stats;
  memset(&stats, 0, sizeof(stats));
  iree_hal_allocator_query_statistics(
      iree_hal_device_allocator(state_->device.get()), &stats);
  out.deviceBytesPeak = static_cast<uint64_t>(stats.device_bytes_peak);
  // freed can never exceed allocated, but clamp rather than underflow a
  // uint64_t if a future allocator implementation disagrees.
  out.deviceBytesLive =
      stats.device_bytes_allocated >= stats.device_bytes_freed
          ? static_cast<uint64_t>(stats.device_bytes_allocated -
                                  stats.device_bytes_freed)
          : 0;
#else
  out.statisticsAvailable = false;
  out.deviceBytesPeak = 0;
  out.deviceBytesLive = 0;
#endif

  return out;
}
```

Add `#include <cstring>` to the .cpp includes if absent (needed for `memset`).

- [ ] **Step 6: Run the tests to verify they pass**

```bash
bash native/build.sh
./native/build/iree_runtime_test
```

Expected: all assertions PASS, including the five new `TEST_CASE`s.

- [ ] **Step 7: Commit**

```bash
git add native/core/iree_runtime.h native/core/iree_runtime.cpp native/test/iree_runtime_test.cpp
git commit -m "feat(native): cumulative import counters and RuntimeStats accessor"
```

---

### Task 2: Native runtime census (`AliveRuntimeCount`)

**Files:**
- Modify: `native/core/iree_runtime.h`
- Modify: `native/core/iree_runtime.cpp`
- Test: `native/test/iree_runtime_test.cpp`

**Interfaces:**
- Consumes: nothing.
- Produces: `int64_t measly::iree::AliveRuntimeCount();` — a free function in the `measly::iree` namespace. Tasks 4 and 9 consume it.

**Background for the implementer:**

This mirrors the existing leak-probe pattern at `native/core/aligned_alloc.h:31-50` (`g_aligned_live` / `AlignedLiveCount()`), including the `std::memory_order_relaxed` load. Follow that style.

`IreeRuntime`'s destructor is currently `~IreeRuntime() = default;` in the .cpp (line 51). It must become a defined destructor that decrements. The constructor at line 49-50 increments.

- [ ] **Step 1: Write the failing test**

Append to `native/test/iree_runtime_test.cpp`:

```cpp
TEST_CASE("AliveRuntimeCount tracks runtime construction and destruction") {
  const int64_t baseline = measly::iree::AliveRuntimeCount();
  auto vmfb = ReadFile(kAddVmfb);
  {
    auto runtime = IreeRuntime::Load(vmfb, kEntryPoint, "local-sync");
    REQUIRE(measly::iree::AliveRuntimeCount() == baseline + 1);
    {
      auto second = IreeRuntime::Load(vmfb, kEntryPoint, "local-sync");
      REQUIRE(measly::iree::AliveRuntimeCount() == baseline + 2);
    }
    REQUIRE(measly::iree::AliveRuntimeCount() == baseline + 1);
  }
  REQUIRE(measly::iree::AliveRuntimeCount() == baseline);
}

TEST_CASE("AliveRuntimeCount does not count a failed load") {
  const int64_t baseline = measly::iree::AliveRuntimeCount();
  auto vmfb = ReadFile(kAddVmfb);
  REQUIRE_THROWS_AS(IreeRuntime::Load(vmfb, kEntryPoint, "no-such-driver"),
                    std::runtime_error);
  REQUIRE(measly::iree::AliveRuntimeCount() == baseline);
}
```

Add `#include <stdexcept>` to the test file's includes if absent.

- [ ] **Step 2: Run the test to verify it fails**

```bash
bash native/build.sh
```

Expected: **compile failure**, `no member named 'AliveRuntimeCount' in namespace 'measly::iree'`.

- [ ] **Step 3: Declare the accessor in the header**

In `native/core/iree_runtime.h`, add after the closing brace of `class IreeRuntime` and before `}  // namespace measly::iree`:

```cpp
// Live IreeRuntime instances. A leak probe for the JVM-side stress tests and
// the native harness: unlike LSan, which sees only unreachable memory, this
// counter catches a runtime that is retained forever. Mirrors
// AlignedLiveCount() in core/aligned_alloc.h.
int64_t AliveRuntimeCount();
```

- [ ] **Step 4: Implement the census**

In `native/core/iree_runtime.cpp`, add near the top of `namespace measly::iree` (just after the opening at line 14, before `struct RuntimeState`):

```cpp
namespace {
std::atomic<int64_t> g_runtimes_live{0};
}  // namespace

int64_t AliveRuntimeCount() {
  return g_runtimes_live.load(std::memory_order_relaxed);
}
```

Add `#include <atomic>` to the .cpp includes.

Then replace the constructor and destructor definitions (currently lines 49-51):

```cpp
IreeRuntime::IreeRuntime(std::unique_ptr<RuntimeState> state)
    : state_(std::move(state)) {
  // Incremented here rather than in Load() so a construction that never
  // happens — a throwing Load — is never counted.
  ++g_runtimes_live;
}

IreeRuntime::~IreeRuntime() { --g_runtimes_live; }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
bash native/build.sh
./native/build/iree_runtime_test
```

Expected: PASS, including both new `TEST_CASE`s.

- [ ] **Step 6: Verify the leak harness still runs clean**

```bash
./native/build/iree_leak_harness src/test/resources/models/add.vmfb
echo "exit=$?"
```

Expected: `exit=0`. The defined destructor must not have disturbed destruction order.

- [ ] **Step 7: Commit**

```bash
git add native/core/iree_runtime.h native/core/iree_runtime.cpp native/test/iree_runtime_test.cpp
git commit -m "feat(native): AliveRuntimeCount census for leak probes"
```

---

### Task 3: Propagate `IREE_STATISTICS_ENABLE` from the dist's `BUILDINFO`

**Files:**
- Modify: `native/CMakeLists.txt`

**Interfaces:**
- Consumes: `RuntimeStats::statisticsAvailable` from Task 1 (the `#if IREE_STATISTICS_ENABLE` guard it feeds).
- Produces: nothing consumed by later Java tasks; this is a build-correctness guard.

**Background for the implementer:**

`RuntimeStats::statisticsAvailable` is compiled from `IREE_STATISTICS_ENABLE`, which `iree/base/config.h:183-189` defaults to `1`. That default is trustworthy only while the dist's own archives were compiled the same way. The dist records its build flags at `${iree_runtime_dist_SOURCE_DIR}/BUILDINFO` in a `cmake_flags=...` line. Today it contains no `IREE_STATISTICS_ENABLE` override, so the default applies on both sides.

If a future dist bumps and passes `-DIREE_STATISTICS_ENABLE=0`, our translation units would still see `1` from the header default while the archives saw `0`. `iree_hal_allocator_statistics_t` is `#if`-guarded field-by-field, so the two sides would disagree on the struct's layout — a silent ABI mismatch producing garbage gauges. This check converts that into a build-time fact.

`iree_runtime_dist_SOURCE_DIR` is set by `FetchContent_MakeAvailable(iree_runtime_dist)` at `native/CMakeLists.txt:54`.

- [ ] **Step 1: Add the propagation block**

In `native/CMakeLists.txt`, insert immediately after `find_package(IreeRuntimeDist REQUIRED)` (line 57):

```cmake
# --- IREE_STATISTICS_ENABLE agreement with the dist ---
#
# RuntimeStats::statisticsAvailable and the device byte gauges compile against
# iree_hal_allocator_statistics_t, whose fields are #if IREE_STATISTICS_ENABLE.
# The header defaults it to 1; if the dist's archives were built with 0, our
# struct layout and theirs disagree — a silent ABI mismatch, not a missing
# feature. Read the dist's recorded flags and match them explicitly.
set(_iree_buildinfo "${iree_runtime_dist_SOURCE_DIR}/BUILDINFO")
if(EXISTS "${_iree_buildinfo}")
  file(READ "${_iree_buildinfo}" _iree_buildinfo_text)
  if(_iree_buildinfo_text MATCHES "-DIREE_STATISTICS_ENABLE=([01])")
    set(IREE_DJL_STATISTICS_ENABLE "${CMAKE_MATCH_1}")
    message(STATUS
      "iree-runtime-dist BUILDINFO sets IREE_STATISTICS_ENABLE=${IREE_DJL_STATISTICS_ENABLE}; matching it.")
  else()
    set(IREE_DJL_STATISTICS_ENABLE "1")
    message(STATUS
      "iree-runtime-dist BUILDINFO does not override IREE_STATISTICS_ENABLE; using the header default (1).")
  endif()
else()
  set(IREE_DJL_STATISTICS_ENABLE "1")
  message(WARNING
    "iree-runtime-dist BUILDINFO not found at ${_iree_buildinfo}; "
    "assuming IREE_STATISTICS_ENABLE=1. If the dist was built with statistics "
    "disabled, the device byte gauges will be wrong rather than absent.")
endif()
```

Then add the definition to `iree_djl_core`, immediately after `target_link_libraries(iree_djl_core PUBLIC iree-runtime-dist::runtime)` (line 110):

```cmake
target_compile_definitions(iree_djl_core
  PUBLIC IREE_STATISTICS_ENABLE=${IREE_DJL_STATISTICS_ENABLE})
```

`PUBLIC` so the Catch2 units, the harness, and the JNI shim — all of which link `iree_djl_core` — compile with the same value.

- [ ] **Step 2: Configure and verify the status message**

```bash
rm -rf native/build
bash native/build.sh 2>&1 | grep -i 'IREE_STATISTICS_ENABLE'
```

Expected: `-- iree-runtime-dist BUILDINFO does not override IREE_STATISTICS_ENABLE; using the header default (1).`

- [ ] **Step 3: Verify the tests still pass with the explicit definition**

```bash
./native/build/iree_runtime_test
```

Expected: PASS. In particular `REQUIRE(runtime->Stats().statisticsAvailable)` from Task 1 still holds.

- [ ] **Step 4: Verify the guard actually bites**

```bash
cmake -S native -B /tmp/iree-stats-off -G Ninja -DIREE_DJL_STATISTICS_ENABLE=0 2>&1 | tail -3
```

Expected: configures without error. This confirms the variable is settable; the real check is that the `BUILDINFO` match path sets it automatically. Remove the scratch build dir afterward:

```bash
rm -rf /tmp/iree-stats-off
```

- [ ] **Step 5: Commit**

```bash
git add native/CMakeLists.txt
git commit -m "build(native): match IREE_STATISTICS_ENABLE to the dist's BUILDINFO"
```

---

### Task 4: JNI `stats()` and `aliveRuntimes()`

**Files:**
- Modify: `native/jni/iree_djl_jni.cpp`
- Modify: `src/main/java/org/measly/iree/jni/IreeNative.java`
- Test: `src/test/java/org/measly/iree/jni/IreeNativeTest.java`

**Interfaces:**
- Consumes: `IreeRuntime::Stats()` (Task 1), `AliveRuntimeCount()` (Task 2).
- Produces:
  - `public static native long[] IreeNative.stats(long handle)` — returns a 6-element array, or `null` if the handle is 0/closed.
  - `public static native long IreeNative.aliveRuntimes()`
  - Public index constants on `IreeNative`: `STAT_WRAPPED_IMPORTS=0`, `STAT_STAGED_IMPORTS=1`, `STAT_STAGING_BYTES=2`, `STAT_DEVICE_BYTES_PEAK=3`, `STAT_DEVICE_BYTES_LIVE=4`, `STAT_STATISTICS_AVAILABLE=5`, `STAT_LENGTH=6`.

  Tasks 5 and 7 consume all of these.

**Background for the implementer:**

Return a `long[]` rather than constructing a Java object in JNI. The ExecuTorch engine's equivalent work was bitten by `g_metaCtor`, a cached JNI method ID with a hardcoded signature literal that fails at class init whenever the Java constructor changes. This file has the same cached-constructor pattern for `IreeTensor`; a primitive array adds no second instance of it.

`stats()` returns `null` on a closed handle instead of throwing — a deliberate departure from this file's prevailing style (`invoke` throws at `iree_djl_jni.cpp:197`). The caller is a monitoring poll that must never throw. Comment it, or it reads as an oversight.

`NewLongArray` can return `nullptr` on allocation failure; null-check it, following the pattern established by commit `5cb8c00`.

`AsRuntime(handle)` is the existing helper that converts a `jlong` to an `IreeRuntime*` and returns `nullptr` for 0.

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/org/measly/iree/jni/IreeNativeTest.java`, inside the existing test class. Reuse the helpers already in that file — `addVmfb()`, `invokeAdd(long)`, and the `F32` / `ENTRY_POINT` constants — rather than introducing new fixture loading:

```java
    @Test
    void statsReturnsNullForClosedHandle() {
        IreeNative.ensureLoaded();
        assertNull(IreeNative.stats(0L));
    }

    @Test
    void statsReportsImportOutcomesAndLength() throws Exception {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        try {
            long[] before = IreeNative.stats(handle);
            assertNotNull(before);
            assertEquals(IreeNative.STAT_LENGTH, before.length);
            assertEquals(0L, before[IreeNative.STAT_WRAPPED_IMPORTS]);
            assertEquals(0L, before[IreeNative.STAT_STAGED_IMPORTS]);
            assertEquals(1L, before[IreeNative.STAT_STATISTICS_AVAILABLE]);

            assertArrayEquals(ADD_SUM, invokeAdd(handle), 1e-6f);

            long[] after = IreeNative.stats(handle);
            assertNotNull(after);
            // Two inputs crossed; each is either wrapped or staged, never both.
            assertEquals(
                    2L,
                    after[IreeNative.STAT_WRAPPED_IMPORTS]
                            + after[IreeNative.STAT_STAGED_IMPORTS]);
            // A JVM direct ByteBuffer misses IREE's 64-byte alignment precondition,
            // so directFloats() input stages rather than wrapping. This is the
            // engine's defining performance cliff and the reason the gauge exists.
            assertEquals(2L, after[IreeNative.STAT_STAGED_IMPORTS]);
            assertTrue(after[IreeNative.STAT_STAGING_BYTES] > 0L);
        } finally {
            IreeNative.close(handle);
        }
    }

    @Test
    void aliveRuntimesTracksLoadAndClose() throws Exception {
        long baseline = IreeNative.aliveRuntimes();
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
        assertEquals(baseline + 1, IreeNative.aliveRuntimes());
        IreeNative.close(handle);
        assertEquals(baseline, IreeNative.aliveRuntimes());
    }
```

Add the static imports `assertNull` and `assertNotNull`; `assertEquals`, `assertTrue`, and `assertArrayEquals` are already imported.

**If `assertEquals(2L, after[STAT_STAGED_IMPORTS])` fails** because the JVM happened to hand back a 64-byte-aligned buffer, do not delete the assertion — relax it to `assertTrue(after[STAT_STAGED_IMPORTS] + after[STAT_WRAPPED_IMPORTS] == 2L)` and note the observed behaviour in the commit message. The staged outcome for JVM direct buffers is an established finding of this project, so a wrapped result is a genuine surprise worth recording.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests 'org.measly.iree.jni.IreeNativeTest'
```

Expected: FAIL — compile error, `cannot find symbol: method stats(long)`.

- [ ] **Step 3: Declare the Java side**

In `src/main/java/org/measly/iree/jni/IreeNative.java`, add after `lastImportOutcomes`:

```java
    /** Index of the cumulative zero-copy import count in {@link #stats(long)}. */
    public static final int STAT_WRAPPED_IMPORTS = 0;
    /** Index of the cumulative staged-copy import count in {@link #stats(long)}. */
    public static final int STAT_STAGED_IMPORTS = 1;
    /** Index of the cached staging footprint, in bytes, in {@link #stats(long)}. */
    public static final int STAT_STAGING_BYTES = 2;
    /** Index of the HAL allocator's peak device bytes in {@link #stats(long)}. */
    public static final int STAT_DEVICE_BYTES_PEAK = 3;
    /** Index of the HAL allocator's live device bytes in {@link #stats(long)}. */
    public static final int STAT_DEVICE_BYTES_LIVE = 4;
    /** Index of the statistics-compiled-in flag (1 or 0) in {@link #stats(long)}. */
    public static final int STAT_STATISTICS_AVAILABLE = 5;
    /** Length of the array {@link #stats(long)} returns. */
    public static final int STAT_LENGTH = 6;

    /**
     * Cold-path observability read for one runtime, as a fixed-layout array
     * indexed by the {@code STAT_*} constants above.
     *
     * <p>Returns a primitive array rather than an object deliberately: building
     * a Java object in JNI needs a cached constructor ID with a hardcoded
     * signature literal, which breaks at class init whenever the Java
     * constructor changes. The array is unpacked in Java, where the compiler
     * checks it.
     *
     * <p><b>Returns {@code null} for a closed or zero handle rather than
     * throwing</b>, because the caller is a monitoring poll that must never
     * throw. Callers skip a null entry.
     *
     * <p>When {@code STAT_STATISTICS_AVAILABLE} is 0, the two device-byte
     * entries are meaningless and callers must report them as unavailable.
     */
    public static native long[] stats(long handle);

    /**
     * Live native runtimes. A leak probe for tests: unlike LSan, which sees
     * only unreachable memory, this counts a runtime that is retained forever.
     */
    public static native long aliveRuntimes();
```

- [ ] **Step 4: Implement the JNI entry points**

In `native/jni/iree_djl_jni.cpp`, add at the end of the file (after the existing `aliveAlignedBuffers` entry point at line 433-436):

```cpp
// Cold-path observability read. Deliberately returns null — not an exception —
// for a closed handle: the caller is a monitoring poll whose contract is that
// it never throws. Every other entry point in this file throws on a closed
// handle; this one must not.
extern "C" JNIEXPORT jlongArray JNICALL
Java_org_measly_iree_jni_IreeNative_stats(JNIEnv* env, jclass, jlong handle) {
  IreeRuntime* runtime = AsRuntime(handle);
  if (runtime == nullptr) {
    return nullptr;
  }

  measly::iree::RuntimeStats stats = runtime->Stats();

  jlongArray out = env->NewLongArray(6);
  if (out == nullptr) {
    return nullptr;  // OOM pending; the JVM throws on return.
  }
  jlong values[6] = {
      static_cast<jlong>(stats.wrappedImports),
      static_cast<jlong>(stats.stagedImports),
      static_cast<jlong>(stats.stagingBytes),
      static_cast<jlong>(stats.deviceBytesPeak),
      static_cast<jlong>(stats.deviceBytesLive),
      static_cast<jlong>(stats.statisticsAvailable ? 1 : 0),
  };
  env->SetLongArrayRegion(out, 0, 6, values);
  return out;
}

// Native-side leak probe, companion to aliveAlignedBuffers: a retained-forever
// runtime is reachable and therefore invisible to LSan, but visible here.
extern "C" JNIEXPORT jlong JNICALL
Java_org_measly_iree_jni_IreeNative_aliveRuntimes(JNIEnv*, jclass) {
  return static_cast<jlong>(measly::iree::AliveRuntimeCount());
}
```

- [ ] **Step 5: Rebuild native and run the tests**

```bash
bash native/build.sh
./gradlew test --tests 'org.measly.iree.jni.IreeNativeTest'
```

Expected: PASS, all three new tests.

- [ ] **Step 6: Commit**

```bash
git add native/jni/iree_djl_jni.cpp src/main/java/org/measly/iree/jni/IreeNative.java src/test/java/org/measly/iree/jni/IreeNativeTest.java
git commit -m "feat(jni): stats(handle) and aliveRuntimes() cold-path probes"
```

---

### Task 5: `IreeModelCounters` and the `IreeSymbolBlock` hot-path brackets

**Files:**
- Create: `src/main/java/org/measly/iree/engine/IreeModelCounters.java`
- Modify: `src/main/java/org/measly/iree/engine/IreeSymbolBlock.java`
- Test: `src/test/java/org/measly/iree/engine/IreeModelCountersTest.java`

**Interfaces:**
- Consumes: `IreeNative.stats(long)` and the `STAT_*` constants (Task 4).
- Produces:
  - `IreeModelCounters` (package-private) with constructor `IreeModelCounters(String name, String driver, String entryPoint, int parameterScopeCount, long loadNanos)`, method `void recordForward(long nanos)`, and accessors `name()`, `driver()`, `entryPoint()`, `parameterScopeCount()`, `loadNanos()`, `forwardCount()`, `forwardTotalNanos()`, `forwardMaxNanos()`.
  - On `IreeSymbolBlock`: `void attachCounters(IreeModelCounters)`, `IreeModelStats toStats()` (added in Task 7 — this task adds only `attachCounters` and the timing), and the `statsLock` monitor.

  Tasks 6 and 7 consume these.

**Background for the implementer:**

**The write order in `recordForward` is load-bearing: count, then total, then max.** A reader on another thread can interleave anywhere between the three volatile writes. This order guarantees the invariant `forwardMaxNanos <= forwardTotalNanos` — the max is published only after a total that already includes the same sample. The JMM forbids reordering volatile writes with each other, so the guarantee is real, but it is a property of this sequence, not of the field declarations. Writing max first lets a reader observe a max with no total behind it, which fails an assertion in `StatsConcurrencyIT` (Task 10) as a rare flake.

Do **not** use `LongAdder`. There is no write contention to relieve — `forward()` is single-writer by the engine contract documented on `IreeSymbolBlock`'s class javadoc — and it would allocate cells and turn the read into a summation.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/measly/iree/engine/IreeModelCountersTest.java`:

```java
package org.measly.iree.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IreeModelCountersTest {

    private static IreeModelCounters newCounters() {
        return new IreeModelCounters("add", "local-sync", "module.add", 0, 1_000L);
    }

    @Test
    void startsAtZero() {
        IreeModelCounters counters = newCounters();
        assertEquals(0L, counters.forwardCount());
        assertEquals(0L, counters.forwardTotalNanos());
        assertEquals(0L, counters.forwardMaxNanos());
        assertEquals("add", counters.name());
        assertEquals("local-sync", counters.driver());
        assertEquals("module.add", counters.entryPoint());
        assertEquals(0, counters.parameterScopeCount());
        assertEquals(1_000L, counters.loadNanos());
    }

    @Test
    void accumulatesCountAndTotal() {
        IreeModelCounters counters = newCounters();
        counters.recordForward(10L);
        counters.recordForward(30L);
        counters.recordForward(20L);
        assertEquals(3L, counters.forwardCount());
        assertEquals(60L, counters.forwardTotalNanos());
    }

    @Test
    void tracksMaximum() {
        IreeModelCounters counters = newCounters();
        counters.recordForward(10L);
        counters.recordForward(30L);
        counters.recordForward(20L);
        assertEquals(30L, counters.forwardMaxNanos());
    }

    @Test
    void maxNeverExceedsTotal() {
        IreeModelCounters counters = newCounters();
        for (int i = 1; i <= 100; i++) {
            counters.recordForward(i);
            assertTrue(
                    counters.forwardMaxNanos() <= counters.forwardTotalNanos(),
                    "max must never be published ahead of the total containing it");
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests 'org.measly.iree.engine.IreeModelCountersTest'
```

Expected: FAIL — compile error, `cannot find symbol: class IreeModelCounters`.

- [ ] **Step 3: Create `IreeModelCounters`**

Create `src/main/java/org/measly/iree/engine/IreeModelCounters.java`:

```java
package org.measly.iree.engine;

/**
 * Mutable per-model counters, updated on the forward path and read by the observability snapshot.
 *
 * <p><b>Single-writer by design.</b> {@code IreeSymbolBlock.forward()} is not safe for concurrent
 * calls on the same model — the engine's contract is one {@code Model}/{@code Predictor} per
 * thread, and an IREE session is not safe for concurrent invocation — so exactly one thread ever
 * calls {@link #recordForward(long)} for a given instance. That is what lets the accumulators be
 * plain read-modify-writes with no CAS and no lock.
 *
 * <p>The fields are {@code volatile} for the reader's sake, not the writer's: a snapshot taken on
 * another thread must observe the updates and must never see a torn 64-bit value. A {@code
 * LongAdder} would be strictly worse — it allocates cells and makes the read a summation, and
 * there is no write contention for it to relieve.
 *
 * <p>This object holds no reference to its {@code IreeSymbolBlock}. That is deliberate: {@code
 * IreeEngineStats} retains it strongly from a static map, so a back-reference would pin the
 * block's whole object graph.
 */
final class IreeModelCounters {

    private final String name;
    private final String driver;
    private final String entryPoint;
    private final int parameterScopeCount;
    private final long loadNanos;

    private volatile long forwardCount;
    private volatile long forwardTotalNanos;
    private volatile long forwardMaxNanos;

    IreeModelCounters(
            String name,
            String driver,
            String entryPoint,
            int parameterScopeCount,
            long loadNanos) {
        this.name = name;
        this.driver = driver;
        this.entryPoint = entryPoint;
        this.parameterScopeCount = parameterScopeCount;
        this.loadNanos = loadNanos;
    }

    /**
     * Records one completed forward. Called only from the model's owning thread.
     *
     * <p><b>The write order is load-bearing: count, then total, then max.</b> A reader on another
     * thread can interleave anywhere between these three volatile writes, and this order is what
     * guarantees the invariant {@code forwardMaxNanos <= forwardTotalNanos} — the max is published
     * only after the total that already includes the same sample. The JMM forbids reordering
     * volatile writes with each other, so the guarantee is real, but it is a property of this
     * sequence and not of the field declarations. Writing max first would let a reader observe a
     * max with no total behind it, breaking an assertion in {@code StatsConcurrencyIT} in a way
     * that only shows up as a rare flake under load.
     *
     * @param nanos the measured wall duration of the native invoke call
     */
    void recordForward(long nanos) {
        forwardCount = forwardCount + 1;
        forwardTotalNanos = forwardTotalNanos + nanos;
        if (nanos > forwardMaxNanos) {
            forwardMaxNanos = nanos;
        }
    }

    String name() {
        return name;
    }

    String driver() {
        return driver;
    }

    String entryPoint() {
        return entryPoint;
    }

    int parameterScopeCount() {
        return parameterScopeCount;
    }

    long loadNanos() {
        return loadNanos;
    }

    long forwardCount() {
        return forwardCount;
    }

    long forwardTotalNanos() {
        return forwardTotalNanos;
    }

    long forwardMaxNanos() {
        return forwardMaxNanos;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew test --tests 'org.measly.iree.engine.IreeModelCountersTest'
```

Expected: PASS, all four tests.

- [ ] **Step 5: Add the timing brackets and `statsLock` to `IreeSymbolBlock`**

In `src/main/java/org/measly/iree/engine/IreeSymbolBlock.java`, add these fields after `private volatile long handle;`:

```java
    // Serializes the stats cold path against close(): the stats read takes the native
    // handle and calls into JNI, close() destroys the handle, and a destroy between the
    // read and the JNI call would be a use-after-free. Taken only by close() and
    // toStats(), never by forwardInternal — the hot path stays lock-free.
    private final Object statsLock = new Object();

    // Attached by IreeModel.load right after construction. Null only in the narrow
    // window before that, and in tests that build a block directly.
    private volatile IreeModelCounters counters;
```

Add the attach method:

```java
    /** Attaches the counters this block updates. Called once, from {@link IreeModel#load}. */
    void attachCounters(IreeModelCounters counters) {
        this.counters = counters;
    }
```

Replace the `IreeNative.invoke(...)` line in `forwardInternal` with the timed version:

```java
        final long start = System.nanoTime();
        IreeTensor[] outputs = IreeNative.invoke(handle, buffers, shapes, types);
        final long elapsed = System.nanoTime() - start;
        IreeModelCounters c = counters;
        if (c != null) {
            c.recordForward(elapsed);
        }
```

Replace `close()` with the lock-guarded version:

```java
    @Override
    public void close() {
        // Mutual exclusion with toStats(): a concurrent snapshot poll must never observe
        // the handle between IreeNative.close() freeing it and the handle read.
        synchronized (statsLock) {
            if (handle != 0L) {
                IreeNative.close(handle);
                handle = 0L;
            }
        }
    }
```

Deregistration is added to this method in Task 7, once the registry exists.

- [ ] **Step 6: Verify the existing suite still passes**

```bash
./gradlew test
```

Expected: PASS. The timing brackets must not change any existing behaviour.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/measly/iree/engine/IreeModelCounters.java src/main/java/org/measly/iree/engine/IreeSymbolBlock.java src/test/java/org/measly/iree/engine/IreeModelCountersTest.java
git commit -m "feat(engine): per-model forward counters and stats lock"
```

---

### Task 6: Configuration plumbing — loaded library path and IREE version constant

**Files:**
- Modify: `src/main/java/org/measly/iree/engine/LibUtils.java`
- Modify: `build.gradle.kts`
- Test: `src/test/java/org/measly/iree/engine/LibUtilsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `static String LibUtils.loadedPath()` — the absolute path actually passed to `System.load`, or `null` before any load.
  - Generated class `org.measly.iree.engine.IreeRuntimeInfo` with `public static final String DIST_TAG` (e.g. `"v3.11.0-11"`).

  Task 7 consumes both.

**Background for the implementer:**

`LibUtils.loadLibrary()` has two `System.load` call sites — the `IREE_LIBRARY_PATH` override at line 37 and the extracted-cache path at line 52. Both must record the path. There is no accessor today; `loaded` is only a boolean.

For the version: no generated Java constant carries the IREE version. `IreeDataTypeCodegen` emits element types only, and extending it would mix responsibilities. The pinned tag is already available to the build in `third-party/iree-runtime-metadata.properties` as `ireeRuntimeDistTag=v3.11.0-11`, and `build.gradle.kts` already loads that file into the `ireeMetadata` properties object. Emit a tiny generated class from it into the existing `generatedIreeSourcesDir`, which is already wired into the main source set.

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/org/measly/iree/engine/LibUtilsTest.java`:

```java
    @Test
    void loadedPathIsRecordedAfterLoad() {
        LibUtils.loadLibrary();
        String path = LibUtils.loadedPath();
        assertNotNull(path, "loadedPath must be recorded once the library is loaded");
        assertFalse(path.isEmpty());
        assertTrue(
                path.endsWith("libiree_djl.so") || path.endsWith("iree_djl.dll"),
                "expected a native library filename, got: " + path);
    }

    @Test
    void distTagIsGenerated() {
        assertNotNull(IreeRuntimeInfo.DIST_TAG);
        assertTrue(
                IreeRuntimeInfo.DIST_TAG.startsWith("v"),
                "expected a release tag like v3.11.0-11, got: " + IreeRuntimeInfo.DIST_TAG);
    }
```

Add the static imports `assertNotNull`, `assertFalse`, `assertTrue` if absent.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests 'org.measly.iree.engine.LibUtilsTest'
```

Expected: FAIL — compile error, `cannot find symbol: method loadedPath()`.

- [ ] **Step 3: Record the loaded path in `LibUtils`**

In `src/main/java/org/measly/iree/engine/LibUtils.java`, add the field next to `loaded`:

```java
    private static String loadedPath;
```

In `loadLibrary()`, record it at both load sites. The override branch:

```java
        if (override != null && !override.isEmpty()) {
            String path = Path.of(override).toAbsolutePath().toString();
            System.load(path);
            loadedPath = path;
            loaded = true;
            return;
        }
```

The extraction branch:

```java
            String path = target.toAbsolutePath().toString();
            System.load(path);
            loadedPath = path;
            loaded = true;
```

Add the accessor after `loadLibrary()`:

```java
    /**
     * The absolute path last passed to {@code System.load}, or {@code null} before any load.
     * Reported by the observability snapshot so an operator can tell which library a process
     * actually loaded — the {@code IREE_LIBRARY_PATH} override makes that non-obvious.
     */
    static String loadedPath() {
        return loadedPath;
    }
```

- [ ] **Step 4: Add the version codegen task**

In `build.gradle.kts`, add immediately after the `generateIreeDataTypes` task registration (after line ~150, before the `sourceSets` block):

```kotlin
// The pinned dist tag as a Java constant. Sourced from the same properties file the
// native build's pin generates, so there is no version literal in Java and no second
// source of truth. Emitted into generatedIreeSourcesDir, which is already on the main
// source set.
val ireeDistTag = ireeMetadata.getProperty("ireeRuntimeDistTag", "unknown")

val generateIreeRuntimeInfo = tasks.register("generateIreeRuntimeInfo") {
    val outDir = generatedIreeSourcesDir
    val tag = ireeDistTag
    inputs.property("ireeDistTag", tag)
    outputs.dir(outDir)
    doLast {
        val pkgDir = outDir.get().asFile.resolve("org/measly/iree/engine")
        pkgDir.mkdirs()
        pkgDir.resolve("IreeRuntimeInfo.java").writeText(
            """
            package org.measly.iree.engine;

            /** Generated from third-party/iree-runtime-metadata.properties. Do not edit. */
            public final class IreeRuntimeInfo {

                /** The pinned iree-runtime-dist release tag, e.g. "v3.11.0-11". */
                public static final String DIST_TAG = "$tag";

                private IreeRuntimeInfo() {}
            }
            """.trimIndent() + "\n"
        )
    }
}
```

Then add the dependency alongside the existing one:

```kotlin
tasks.named("compileJava") {
    dependsOn(generateIreeDataTypes, generateIreeRuntimeInfo)
}

tasks.matching { it.name == "sourcesJar" }.configureEach {
    dependsOn(generateIreeDataTypes, generateIreeRuntimeInfo)
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew test --tests 'org.measly.iree.engine.LibUtilsTest'
```

Expected: PASS, both new tests. Confirm the generated file exists:

```bash
cat build/generated/sources/iree/org/measly/iree/engine/IreeRuntimeInfo.java
```

Expected: a class with `DIST_TAG = "v3.11.0-11"`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/measly/iree/engine/LibUtils.java build.gradle.kts src/test/java/org/measly/iree/engine/LibUtilsTest.java
git commit -m "feat(engine): record loaded library path and generate IREE dist tag"
```

---

### Task 7: Value types, registry, and `snapshot()`

**Files:**
- Create: `src/main/java/org/measly/iree/engine/IreeModelStats.java`
- Create: `src/main/java/org/measly/iree/engine/IreeStatsSnapshot.java`
- Create: `src/main/java/org/measly/iree/engine/IreeEngineStats.java`
- Modify: `src/main/java/org/measly/iree/engine/IreeSymbolBlock.java`
- Modify: `src/main/java/org/measly/iree/engine/IreeModel.java`
- Test: `src/test/java/org/measly/iree/engine/IreeEngineStatsTest.java`

**Interfaces:**
- Consumes: `IreeModelCounters` and `IreeSymbolBlock.attachCounters` (Task 5); `IreeNative.stats(long)` + `STAT_*` (Task 4); `LibUtils.loadedPath()`, `LibUtils.platform()`, `IreeRuntimeInfo.DIST_TAG` (Task 6).
- Produces:
  - `public static IreeStatsSnapshot IreeEngineStats.snapshot()`
  - `public static final String IreeEngineStats.OBJECT_NAME`
  - package-private `IreeEngineStats.register(long handle, IreeSymbolBlock block, IreeModelCounters counters)` and `IreeEngineStats.deregister(long handle)`
  - `IreeSymbolBlock.toStats()` returning `IreeModelStats` or `null`
  - `IreeModelStats` and `IreeStatsSnapshot` getter-only beans

  Task 8 consumes `snapshot()` and `OBJECT_NAME`; Tasks 9 and 10 consume `snapshot()`.

**Background for the implementer:**

The registry is `static` and lives for the JVM, so it is a GC root. `ModelRef` extends `WeakReference<IreeSymbolBlock>` — **weak on the block, strong on the counters.** A strong entry would pin the block and, through it, the model's `IreeNDManager` and every attached `NDArray` for the life of the process, turning a native-only leak into a permanent heap one. The counters are held strongly because they are a few longs and some strings, independent of the block's object graph, and holding them lets a collected model's forwards still reach the rollup.

*Honest limit to preserve in the javadoc:* the weak reference does not make a leaked model collectable — DJL's `BaseNDManager` attaches every base manager to a static system manager, so it stays reachable regardless. The weak reference stops *this class* from being a cause.

`purgeCollected()` must use the **two-argument** `LIVE.remove(ref.handle, ref)`. IREE handles are pointers, so after a close the allocator can hand the same address to a new model; removing by key alone would evict the live model and double-count the dead one.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/measly/iree/engine/IreeEngineStatsTest.java`:

```java
package org.measly.iree.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IreeEngineStatsTest {

    private static final Path MODEL_DIR = Paths.get("src/test/resources/models");
    private static final Map<String, String> ADD_OPTIONS = Map.of("entryPoint", "module.add");

    private static void forwardOnce(Model model) {
        try (NDManager manager = model.getNDManager().newSubManager()) {
            NDArray lhs = manager.create(new float[] {1f, 2f, 3f, 4f}, new Shape(4));
            NDArray rhs = manager.create(new float[] {10f, 20f, 30f, 40f}, new Shape(4));
            model.getBlock().forward(null, new NDList(lhs, rhs), false);
        }
    }

    @Test
    void snapshotReportsConfiguration() {
        IreeStatsSnapshot snapshot = IreeEngineStats.snapshot();
        assertNotNull(snapshot);
        assertEquals(IreeEngine.getEngineVersion(), snapshot.getEngineVersion());
        assertEquals(IreeRuntimeInfo.DIST_TAG, snapshot.getIreeRuntimeVersion());
        assertFalse(snapshot.getPlatform().isEmpty());
        assertEquals("cached-map-write", snapshot.getStagingMode());
        assertNotNull(snapshot.getJmxStatus());
    }

    @Test
    void snapshotTracksLiveModelAndCounters() throws Exception {
        long loadedBefore = IreeEngineStats.snapshot().getModelsLoaded();
        try (Model model = Model.newInstance("add", "IREE")) {
            model.load(MODEL_DIR, "add", ADD_OPTIONS);
            forwardOnce(model);
            forwardOnce(model);

            IreeStatsSnapshot snapshot = IreeEngineStats.snapshot();
            assertEquals(loadedBefore + 1, snapshot.getModelsLoaded());
            assertEquals(1, snapshot.getModelsLive());

            IreeModelStats stats = snapshot.getModels().get(0);
            assertEquals("add", stats.getName());
            assertEquals("local-sync", stats.getDriver());
            assertEquals("module.add", stats.getEntryPoint());
            assertEquals(0, stats.getParameterScopeCount());
            assertEquals(2L, stats.getForwardCount());
            assertTrue(stats.getForwardTotalNanos() > 0);
            assertTrue(stats.getForwardMaxNanos() > 0);
            assertTrue(stats.getForwardMaxNanos() <= stats.getForwardTotalNanos());
            assertTrue(stats.getLoadNanos() > 0);
            // Two inputs per forward, two forwards; each input wrapped or staged.
            assertEquals(4L, stats.getWrappedImports() + stats.getStagedImports());
        }
    }

    @Test
    void closingFoldsCountersIntoTheRollup() throws Exception {
        long closedBefore = IreeEngineStats.snapshot().getClosedForwardCount();
        try (Model model = Model.newInstance("add", "IREE")) {
            model.load(MODEL_DIR, "add", ADD_OPTIONS);
            forwardOnce(model);
            forwardOnce(model);
            forwardOnce(model);
        }
        IreeStatsSnapshot after = IreeEngineStats.snapshot();
        assertEquals(0, after.getModelsLive());
        assertEquals(closedBefore + 3, after.getClosedForwardCount());
        assertTrue(after.getClosedForwardTotalNanos() > 0);
    }

    @Test
    void collectedModelIsFoldedIntoTheRollupWithoutClose() throws Exception {
        long closedBefore = IreeEngineStats.snapshot().getClosedForwardCount();

        // Deliberately no try-with-resources: drop the model unclosed and let GC reap it.
        Model model = Model.newInstance("add", "IREE");
        model.load(MODEL_DIR, "add", ADD_OPTIONS);
        forwardOnce(model);
        model = null;

        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            System.gc();
            Thread.sleep(50L);
            if (IreeEngineStats.snapshot().getClosedForwardCount() >= closedBefore + 1) {
                break;
            }
        }

        IreeStatsSnapshot after = IreeEngineStats.snapshot();
        assertEquals(
                closedBefore + 1,
                after.getClosedForwardCount(),
                "a collected block must reach the rollup, not be stranded in LIVE");
    }

    @Test
    void byteGaugesNeverSumUnavailableIntoTotals() throws Exception {
        try (Model model = Model.newInstance("add", "IREE")) {
            model.load(MODEL_DIR, "add", ADD_OPTIONS);
            forwardOnce(model);
            IreeStatsSnapshot snapshot = IreeEngineStats.snapshot();
            assertTrue(
                    snapshot.getTotalStagingBytes() >= 0,
                    "-1 must be skipped rather than summed");
            assertTrue(snapshot.getTotalDeviceBytesLive() >= 0);
        }
    }
}
```

**Note on the GC test:** it is not tagged and runs in the normal suite. If it proves flaky on a CI runner, tag it rather than weakening the assertion — the behaviour it covers is the whole point of the weak reference.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests 'org.measly.iree.engine.IreeEngineStatsTest'
```

Expected: FAIL — compile error, `cannot find symbol: class IreeStatsSnapshot`.

- [ ] **Step 3: Create `IreeModelStats`**

Create `src/main/java/org/measly/iree/engine/IreeModelStats.java`:

```java
package org.measly.iree.engine;

/**
 * Immutable per-model statistics, as of one {@link IreeEngineStats#snapshot()}.
 *
 * <p><b>Getter-only by contract.</b> This is returned through an MXBean, and the JMX runtime
 * converts it to {@code CompositeData} by reflecting over its getters. Adding a setter or a
 * public field breaks that conversion.
 *
 * <p><b>Byte gauges use {@code -1} for "unavailable" and {@code 0} for "genuinely zero".</b> The
 * distinction matters twice here: {@code stagingBytes == 0} means no input has staged a copy yet,
 * a real and meaningful state; {@code deviceBytesPeak == -1} means IREE's allocator statistics
 * were compiled out of the runtime and the figure is unknowable, not zero.
 */
public final class IreeModelStats {

    private final String name;
    private final String driver;
    private final String entryPoint;
    private final int parameterScopeCount;
    private final long loadNanos;
    private final long forwardCount;
    private final long forwardTotalNanos;
    private final long forwardMaxNanos;
    private final long wrappedImports;
    private final long stagedImports;
    private final long stagingBytes;
    private final long deviceBytesPeak;
    private final long deviceBytesLive;

    IreeModelStats(
            String name,
            String driver,
            String entryPoint,
            int parameterScopeCount,
            long loadNanos,
            long forwardCount,
            long forwardTotalNanos,
            long forwardMaxNanos,
            long wrappedImports,
            long stagedImports,
            long stagingBytes,
            long deviceBytesPeak,
            long deviceBytesLive) {
        this.name = name;
        this.driver = driver;
        this.entryPoint = entryPoint;
        this.parameterScopeCount = parameterScopeCount;
        this.loadNanos = loadNanos;
        this.forwardCount = forwardCount;
        this.forwardTotalNanos = forwardTotalNanos;
        this.forwardMaxNanos = forwardMaxNanos;
        this.wrappedImports = wrappedImports;
        this.stagedImports = stagedImports;
        this.stagingBytes = stagingBytes;
        this.deviceBytesPeak = deviceBytesPeak;
        this.deviceBytesLive = deviceBytesLive;
    }

    /** @return the DJL model name */
    public String getName() {
        return name;
    }

    /** @return the IREE HAL driver this model loaded with, e.g. {@code local-sync} */
    public String getDriver() {
        return driver;
    }

    /** @return the compiled entry point, e.g. {@code module.main} */
    public String getEntryPoint() {
        return entryPoint;
    }

    /** @return how many {@code .irpa} parameter scopes were bound at load */
    public int getParameterScopeCount() {
        return parameterScopeCount;
    }

    /** @return wall time spent in the native load call */
    public long getLoadNanos() {
        return loadNanos;
    }

    /** @return completed forwards on this model */
    public long getForwardCount() {
        return forwardCount;
    }

    /** @return summed wall time of every completed forward */
    public long getForwardTotalNanos() {
        return forwardTotalNanos;
    }

    /** @return the slowest single forward */
    public long getForwardMaxNanos() {
        return forwardMaxNanos;
    }

    /**
     * @return cumulative inputs imported zero-copy. Compare against {@link #getStagedImports()}:
     *     a high staged share means the engine is copying every input, which is the single most
     *     common silent performance cliff in this engine.
     */
    public long getWrappedImports() {
        return wrappedImports;
    }

    /** @return cumulative inputs that fell back to a staged copy */
    public long getStagedImports() {
        return stagedImports;
    }

    /**
     * @return bytes held by the grow-only per-slot staging buffers, or {@code -1} if unreadable.
     *     {@code 0} means nothing has staged yet, which is a real state, not an error.
     */
    public long getStagingBytes() {
        return stagingBytes;
    }

    /** @return peak HAL device-allocator bytes, or {@code -1} if statistics are compiled out */
    public long getDeviceBytesPeak() {
        return deviceBytesPeak;
    }

    /**
     * @return currently-live HAL device-allocator bytes (allocated minus freed), or {@code -1} if
     *     statistics are compiled out. This is the gauge to alert on for an unbounded-growth leak.
     */
    public long getDeviceBytesLive() {
        return deviceBytesLive;
    }
}
```

- [ ] **Step 4: Create `IreeStatsSnapshot`**

Create `src/main/java/org/measly/iree/engine/IreeStatsSnapshot.java`:

```java
package org.measly.iree.engine;

import java.util.List;

/**
 * An immutable point-in-time view of engine configuration, process totals, and live models.
 *
 * <p><b>Getter-only by contract</b>, for the same MXBean {@code CompositeData} reason as
 * {@link IreeModelStats}.
 *
 * <p>Unresolvable string fields report {@code "unknown"} rather than {@code null}, so a
 * monitoring consumer never has to null-check.
 */
public final class IreeStatsSnapshot {

    private final String engineVersion;
    private final String ireeRuntimeVersion;
    private final String platform;
    private final String nativeLibraryPath;
    private final String stagingMode;
    private final boolean nativeStatsAvailable;
    private final String jmxStatus;
    private final String jmxError;
    private final long modelsLoaded;
    private final int modelsLive;
    private final long totalStagingBytes;
    private final long totalDeviceBytesLive;
    private final long closedForwardCount;
    private final long closedForwardTotalNanos;
    private final long closedWrappedImports;
    private final long closedStagedImports;
    private final List<IreeModelStats> models;

    IreeStatsSnapshot(
            String engineVersion,
            String ireeRuntimeVersion,
            String platform,
            String nativeLibraryPath,
            String stagingMode,
            boolean nativeStatsAvailable,
            String jmxStatus,
            String jmxError,
            long modelsLoaded,
            int modelsLive,
            long totalStagingBytes,
            long totalDeviceBytesLive,
            long closedForwardCount,
            long closedForwardTotalNanos,
            long closedWrappedImports,
            long closedStagedImports,
            List<IreeModelStats> models) {
        this.engineVersion = engineVersion;
        this.ireeRuntimeVersion = ireeRuntimeVersion;
        this.platform = platform;
        this.nativeLibraryPath = nativeLibraryPath;
        this.stagingMode = stagingMode;
        this.nativeStatsAvailable = nativeStatsAvailable;
        this.jmxStatus = jmxStatus;
        this.jmxError = jmxError;
        this.modelsLoaded = modelsLoaded;
        this.modelsLive = modelsLive;
        this.totalStagingBytes = totalStagingBytes;
        this.totalDeviceBytesLive = totalDeviceBytesLive;
        this.closedForwardCount = closedForwardCount;
        this.closedForwardTotalNanos = closedForwardTotalNanos;
        this.closedWrappedImports = closedWrappedImports;
        this.closedStagedImports = closedStagedImports;
        this.models = models;
    }

    /** @return the DJL engine version */
    public String getEngineVersion() {
        return engineVersion;
    }

    /** @return the pinned iree-runtime-dist release tag */
    public String getIreeRuntimeVersion() {
        return ireeRuntimeVersion;
    }

    /** @return the resolved platform directory, e.g. {@code linux-x86_64} */
    public String getPlatform() {
        return platform;
    }

    /** @return the native library path actually loaded, or {@code "unknown"} */
    public String getNativeLibraryPath() {
        return nativeLibraryPath;
    }

    /** @return the compiled-in staged-fallback policy, e.g. {@code cached-map-write} */
    public String getStagingMode() {
        return stagingMode;
    }

    /**
     * @return whether IREE's HAL allocator statistics are compiled into the runtime. When false,
     *     every {@code deviceBytes*} figure is {@code -1}.
     */
    public boolean isNativeStatsAvailable() {
        return nativeStatsAvailable;
    }

    /** @return {@code REGISTERED}, {@code DISABLED}, or {@code FAILED} */
    public String getJmxStatus() {
        return jmxStatus;
    }

    /** @return why JMX registration failed, or an empty string */
    public String getJmxError() {
        return jmxError;
    }

    /** @return models loaded since JVM start, cumulative */
    public long getModelsLoaded() {
        return modelsLoaded;
    }

    /** @return models currently live */
    public int getModelsLive() {
        return modelsLive;
    }

    /** @return summed staging bytes across live models; unavailable ({@code -1}) entries skipped */
    public long getTotalStagingBytes() {
        return totalStagingBytes;
    }

    /** @return summed live device bytes across live models; unavailable entries skipped */
    public long getTotalDeviceBytesLive() {
        return totalDeviceBytesLive;
    }

    /** @return forwards completed by models that are now closed or collected */
    public long getClosedForwardCount() {
        return closedForwardCount;
    }

    /** @return summed forward time of models that are now closed or collected */
    public long getClosedForwardTotalNanos() {
        return closedForwardTotalNanos;
    }

    /** @return zero-copy imports by models that are now closed or collected */
    public long getClosedWrappedImports() {
        return closedWrappedImports;
    }

    /** @return staged imports by models that are now closed or collected */
    public long getClosedStagedImports() {
        return closedStagedImports;
    }

    /** @return per-model detail for live models only; never {@code null} */
    public List<IreeModelStats> getModels() {
        return models;
    }
}
```

- [ ] **Step 5: Create `IreeEngineStats`**

Create `src/main/java/org/measly/iree/engine/IreeEngineStats.java`:

```java
package org.measly.iree.engine;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production monitoring surface for the IREE engine.
 *
 * <p>Answers the questions an operator actually asks: is inference slow, is the native side
 * growing without bound, is this deployment configured as expected, and — specific to this engine
 * — are inputs importing zero-copy or silently staging a copy on every call.
 *
 * <p><b>Not to be confused with {@code ai.djl.metric.Metrics}.</b> That is a time-series buffer
 * suited to benchmarking: its {@code limit} defaults to 0 (uncapped), so every {@code predict()}
 * retains three {@code Metric} objects indefinitely unless the caller wires both {@code setLimit}
 * and {@code setOnLimit}. Use it for profiling; use this class for production monitoring.
 */
public final class IreeEngineStats {

    /** The JMX object name this engine registers under. */
    public static final String OBJECT_NAME = "org.measly.iree:type=IreeEngineStats";

    private static final String UNKNOWN = "unknown";

    /** The staged-fallback policy the JNI compiles in; see iree_djl_jni.cpp. */
    private static final String STAGING_MODE = "cached-map-write";

    private static final ReferenceQueue<IreeSymbolBlock> REAPED = new ReferenceQueue<>();
    private static final Map<Long, ModelRef> LIVE = new ConcurrentHashMap<>();
    private static final AtomicLong MODELS_LOADED = new AtomicLong();
    private static final AtomicLong CLOSED_FORWARD_COUNT = new AtomicLong();
    private static final AtomicLong CLOSED_FORWARD_TOTAL_NANOS = new AtomicLong();
    private static final AtomicLong CLOSED_WRAPPED_IMPORTS = new AtomicLong();
    private static final AtomicLong CLOSED_STAGED_IMPORTS = new AtomicLong();

    private IreeEngineStats() {}

    /**
     * A registry entry: the block <b>weakly</b>, its counters <b>strongly</b>.
     *
     * <p>Weak on the block because this map is static and lives for the JVM. A caller who drops a
     * model without closing it already leaks the native IREE session — there is no {@code
     * Cleaner} or finalizer on {@link IreeSymbolBlock}, only {@code close()}. A strong entry here
     * would additionally pin the block, and through it the model's {@link IreeNDManager} and every
     * attached {@code NDArray}, for the life of the process: a native-only leak turned into a
     * permanent heap one, with {@code modelsLive} climbing forever. Observability must not cause
     * the leak it exists to detect.
     *
     * <p>(It does not make a leaked model free: DJL's {@code BaseNDManager} attaches every base
     * manager to a static system manager, so the model stays reachable regardless. That retention
     * is DJL's and predates this class. The weak reference stops <i>this</i> class from being a
     * cause.)
     *
     * <p>Strong on the counters because they are the only thing worth keeping — a few longs and
     * some string references, independent of the block's object graph. Holding them lets a
     * collected model's forwards still reach the rollup, which a bare {@code WeakReference} would
     * lose: exactly the history the rollup exists to preserve.
     */
    private static final class ModelRef extends WeakReference<IreeSymbolBlock> {
        final long handle;
        final IreeModelCounters counters;

        ModelRef(IreeSymbolBlock block, long handle, IreeModelCounters counters) {
            super(block, REAPED);
            this.handle = handle;
            this.counters = counters;
        }
    }

    /** Records a newly loaded model. Called from {@link IreeModel#load}. */
    static void register(long handle, IreeSymbolBlock block, IreeModelCounters counters) {
        purgeCollected(); // bounded by what the GC has reaped since the last call, usually nothing
        LIVE.put(handle, new ModelRef(block, handle, counters));
        MODELS_LOADED.incrementAndGet();
    }

    /**
     * Removes a model and folds its totals into the rollup. Called from
     * {@link IreeSymbolBlock#close()}. Idempotent: a second close finds nothing to remove.
     */
    static void deregister(long handle) {
        ModelRef ref = LIVE.remove(handle);
        if (ref != null) {
            foldIntoRollup(ref.counters);
        }
    }

    /**
     * Folds every model the GC has reclaimed since the last call into the rollup and drops its
     * entry. Called from {@link #snapshot()} and {@link #register}, so the map self-heals on any
     * activity; draining costs O(models collected), not O(models tracked).
     */
    private static void purgeCollected() {
        for (Reference<? extends IreeSymbolBlock> reaped = REAPED.poll();
                reaped != null;
                reaped = REAPED.poll()) {
            ModelRef ref = (ModelRef) reaped;
            // Two-argument remove, deliberately. IREE handles are pointers: if this handle was
            // already deregistered by close() and the allocator has since handed the same address
            // to a new model, the current mapping is a different ModelRef — removing by key alone
            // would evict the live model and double-count this one. Compare-and-remove makes both
            // impossible.
            if (LIVE.remove(ref.handle, ref)) {
                foldIntoRollup(ref.counters);
            }
        }
    }

    private static void foldIntoRollup(IreeModelCounters counters) {
        CLOSED_FORWARD_COUNT.addAndGet(counters.forwardCount());
        CLOSED_FORWARD_TOTAL_NANOS.addAndGet(counters.forwardTotalNanos());
        // Import totals live natively and die with the runtime, so they are captured at
        // deregistration time from the block's last successful stats read. A model collected
        // without a close contributes 0 here — its native counters went with the leaked runtime.
        CLOSED_WRAPPED_IMPORTS.addAndGet(counters.lastWrappedImports());
        CLOSED_STAGED_IMPORTS.addAndGet(counters.lastStagedImports());
    }

    /**
     * Captures the engine's current state.
     *
     * @return an immutable snapshot; never {@code null}, never throws
     */
    public static IreeStatsSnapshot snapshot() {
        purgeCollected();
        List<IreeModelStats> models = new ArrayList<>(LIVE.size());
        long staging = 0;
        long deviceLive = 0;
        boolean statsAvailable = true;

        for (ModelRef ref : LIVE.values()) {
            IreeSymbolBlock block = ref.get();
            if (block == null) {
                continue; // collected between the purge above and here; the next poll folds it in
            }
            IreeModelStats stats = block.toStats();
            if (stats == null) {
                // Defensive only, and not reachable through IreeModel.load: attachCounters()
                // always precedes register(). Kept so a block registered by some future path
                // without counters degrades to "absent from the list" rather than an NPE out of
                // a monitoring poll.
                continue;
            }
            models.add(stats);
            if (stats.getStagingBytes() > 0) {
                staging += stats.getStagingBytes(); // skips -1 so "unavailable" never sums in
            }
            if (stats.getDeviceBytesLive() > 0) {
                deviceLive += stats.getDeviceBytesLive();
            }
            if (stats.getDeviceBytesPeak() < 0) {
                statsAvailable = false;
            }
        }

        return new IreeStatsSnapshot(
                IreeEngine.getEngineVersion(),
                safeString(IreeRuntimeInfo.DIST_TAG),
                safePlatform(),
                safeString(LibUtils.loadedPath()),
                STAGING_MODE,
                statsAvailable,
                IreeJmx.status(),
                IreeJmx.error(),
                MODELS_LOADED.get(),
                models.size(),
                staging,
                deviceLive,
                CLOSED_FORWARD_COUNT.get(),
                CLOSED_FORWARD_TOTAL_NANOS.get(),
                CLOSED_WRAPPED_IMPORTS.get(),
                CLOSED_STAGED_IMPORTS.get(),
                Collections.unmodifiableList(models));
    }

    private static String safeString(String value) {
        return (value == null || value.isEmpty()) ? UNKNOWN : value;
    }

    private static String safePlatform() {
        try {
            return LibUtils.platform();
        } catch (RuntimeException e) {
            return UNKNOWN; // unsupported os.arch: reportable, not fatal to a monitoring read
        }
    }
}
```

**`IreeJmx` does not exist yet** — Task 8 creates it. To keep this task independently compilable, add a minimal placeholder now as a nested holder inside `IreeEngineStats`, and Task 8 replaces the bodies:

```java
    /** JMX registration state. Bodies are filled in when JMX lands; see Task 8. */
    static final class IreeJmx {
        private IreeJmx() {}

        static String status() {
            return "DISABLED";
        }

        static String error() {
            return "";
        }
    }
```

and change the two call sites to `IreeJmx.status()` / `IreeJmx.error()` accordingly (they already read that way).

- [ ] **Step 6: Add `lastWrappedImports`/`lastStagedImports` to `IreeModelCounters`**

The rollup needs the native import totals captured before the runtime dies. Add to `IreeModelCounters`:

```java
    // Last observed native import totals. Native counters die with the runtime, so the
    // block records them here on every stats read; deregistration folds the last value
    // into the process rollup. Written from the stats cold path under the block's
    // statsLock, read from the same place plus deregistration.
    private volatile long lastWrappedImports;
    private volatile long lastStagedImports;

    void recordNativeImports(long wrapped, long staged) {
        lastWrappedImports = wrapped;
        lastStagedImports = staged;
    }

    long lastWrappedImports() {
        return lastWrappedImports;
    }

    long lastStagedImports() {
        return lastStagedImports;
    }
```

- [ ] **Step 7: Add `toStats()` and deregistration to `IreeSymbolBlock`**

Add to `src/main/java/org/measly/iree/engine/IreeSymbolBlock.java`:

```java
    /**
     * Reads this model's statistics. Returns {@code null} if no counters are attached.
     *
     * <p>Holds {@code statsLock} across the handle read and the JNI call so a concurrent
     * {@code close()} cannot free the runtime between them — that interleaving is a
     * use-after-free, and it is exactly what a monitoring poll racing a model shutdown does.
     */
    IreeModelStats toStats() {
        IreeModelCounters c = counters;
        if (c == null) {
            return null;
        }
        long wrapped = -1;
        long staged = -1;
        long stagingBytes = -1;
        long devicePeak = -1;
        long deviceLive = -1;
        synchronized (statsLock) {
            if (handle != 0L) {
                long[] raw = IreeNative.stats(handle);
                if (raw != null && raw.length == IreeNative.STAT_LENGTH) {
                    wrapped = raw[IreeNative.STAT_WRAPPED_IMPORTS];
                    staged = raw[IreeNative.STAT_STAGED_IMPORTS];
                    stagingBytes = raw[IreeNative.STAT_STAGING_BYTES];
                    if (raw[IreeNative.STAT_STATISTICS_AVAILABLE] == 1L) {
                        devicePeak = raw[IreeNative.STAT_DEVICE_BYTES_PEAK];
                        deviceLive = raw[IreeNative.STAT_DEVICE_BYTES_LIVE];
                    }
                    c.recordNativeImports(wrapped, staged);
                }
            }
        }
        return new IreeModelStats(
                c.name(),
                c.driver(),
                c.entryPoint(),
                c.parameterScopeCount(),
                c.loadNanos(),
                c.forwardCount(),
                c.forwardTotalNanos(),
                c.forwardMaxNanos(),
                wrapped,
                staged,
                stagingBytes,
                devicePeak,
                deviceLive);
    }
```

Update `close()` to deregister before freeing:

```java
    @Override
    public void close() {
        synchronized (statsLock) {
            if (handle != 0L) {
                // Deregister first so no poll can reach a handle this method is about to free.
                // Capture the native import totals into the counters on the way out, since the
                // native side dies with the runtime.
                IreeModelCounters c = counters;
                if (c != null) {
                    long[] raw = IreeNative.stats(handle);
                    if (raw != null && raw.length == IreeNative.STAT_LENGTH) {
                        c.recordNativeImports(
                                raw[IreeNative.STAT_WRAPPED_IMPORTS],
                                raw[IreeNative.STAT_STAGED_IMPORTS]);
                    }
                }
                IreeEngineStats.deregister(handle);
                IreeNative.close(handle);
                handle = 0L;
            }
        }
    }
```

- [ ] **Step 8: Wire registration into `IreeModel.load`**

In `src/main/java/org/measly/iree/engine/IreeModel.java`, replace the load and block construction at the end of `load(...)`:

```java
        final long loadStart = System.nanoTime();
        long handle = IreeNative.load(bytes, entryPoint, opts.device(), scopes, paths);
        final long loadNanos = System.nanoTime() - loadStart;

        IreeSymbolBlock symbolBlock = new IreeSymbolBlock((IreeNDManager) manager, handle);
        IreeModelCounters counters =
                new IreeModelCounters(modelName, opts.device(), entryPoint, count, loadNanos);
        symbolBlock.attachCounters(counters);
        IreeEngineStats.register(handle, symbolBlock, counters);
        block = symbolBlock;
```

- [ ] **Step 9: Run the tests to verify they pass**

```bash
./gradlew test --tests 'org.measly.iree.engine.IreeEngineStatsTest'
```

Expected: PASS, all five tests.

- [ ] **Step 10: Run the full suite**

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/org/measly/iree/engine/IreeModelStats.java \
        src/main/java/org/measly/iree/engine/IreeStatsSnapshot.java \
        src/main/java/org/measly/iree/engine/IreeEngineStats.java \
        src/main/java/org/measly/iree/engine/IreeModelCounters.java \
        src/main/java/org/measly/iree/engine/IreeSymbolBlock.java \
        src/main/java/org/measly/iree/engine/IreeModel.java \
        src/test/java/org/measly/iree/engine/IreeEngineStatsTest.java
git commit -m "feat(engine): IreeEngineStats registry, snapshot, and value types"
```

---

### Task 8: JMX MXBean and auto-registration

**Files:**
- Create: `src/main/java/org/measly/iree/engine/IreeEngineStatsMXBean.java`
- Modify: `src/main/java/org/measly/iree/engine/IreeEngineStats.java`
- Modify: `src/main/java/org/measly/iree/engine/IreeModel.java`
- Test: `src/test/java/org/measly/iree/engine/IreeEngineStatsJmxIT.java`

**Interfaces:**
- Consumes: `IreeEngineStats.snapshot()`, `IreeEngineStats.OBJECT_NAME` (Task 7).
- Produces: `IreeEngineStatsMXBean` with `IreeStatsSnapshot getSnapshot()`; `IreeEngineStats.registerMBean()`, `IreeEngineStats.unregisterMBean()`, and package-private `registerMBeanOnce()`.

**Background for the implementer:**

**The `MXBean` suffix on the interface name is the only thing that makes this an MXBean.** There is no annotation. Rename it and the JMX runtime treats it as a Standard MBean, `List<IreeModelStats>` stops converting to `TabularData`, and registration throws `NotCompliantMBeanException`.

Registration is attempted exactly once per JVM. A failure — name collision, `SecurityManager`, restricted container — logs one warning and is never retried, because a per-load retry would log on every load and re-run a failure already reported. It must never fail a model load.

`slf4j-api` is already on the runtime classpath transitively via `ai.djl:api`, and declared `compileOnly` in `build.gradle.kts:24`. No dependency change is needed.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/measly/iree/engine/IreeEngineStatsJmxIT.java`:

```java
package org.measly.iree.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IreeEngineStatsJmxIT {

    @AfterEach
    void unregister() {
        IreeEngineStats.unregisterMBean();
    }

    @Test
    void registersAndReadsBackThroughThePlatformServer() throws Exception {
        IreeEngineStats.registerMBean();

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(IreeEngineStats.OBJECT_NAME);
        assertTrue(server.isRegistered(name));

        // Reading through the server exercises the MXBean CompositeData conversion, which is
        // what would break if the interface lost its MXBean suffix or a value type gained a
        // setter.
        Object snapshot = server.getAttribute(name, "Snapshot");
        assertNotNull(snapshot);
        assertTrue(
                snapshot instanceof javax.management.openmbean.CompositeData,
                "an MXBean must convert the snapshot to CompositeData, got: "
                        + snapshot.getClass());

        javax.management.openmbean.CompositeData data =
                (javax.management.openmbean.CompositeData) snapshot;
        assertEquals(IreeEngine.getEngineVersion(), data.get("engineVersion"));
        assertNotNull(data.get("models"));
    }

    @Test
    void repeatedRegistrationDoesNotThrow() {
        IreeEngineStats.registerMBean();
        IreeEngineStats.registerMBean();
        assertEquals("REGISTERED", IreeEngineStats.snapshot().getJmxStatus());
    }

    @Test
    void statusIsReportedInTheSnapshot() {
        IreeEngineStats.registerMBean();
        IreeStatsSnapshot snapshot = IreeEngineStats.snapshot();
        assertEquals("REGISTERED", snapshot.getJmxStatus());
        assertEquals("", snapshot.getJmxError());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests 'org.measly.iree.engine.IreeEngineStatsJmxIT'
```

Expected: FAIL — compile error, `cannot find symbol: method registerMBean()`.

- [ ] **Step 3: Create the MXBean interface**

Create `src/main/java/org/measly/iree/engine/IreeEngineStatsMXBean.java`:

```java
package org.measly.iree.engine;

/**
 * JMX view of {@link IreeEngineStats}, registered as {@value IreeEngineStats#OBJECT_NAME}.
 *
 * <p>An <b>MX</b>Bean rather than a plain MBean: the JMX runtime converts {@link
 * IreeStatsSnapshot} and its nested {@code List<IreeModelStats>} to {@code CompositeData}/{@code
 * TabularData} automatically, so no hand-written {@code OpenType} mapping is needed.
 *
 * <p><b>The {@code MXBean} suffix on this interface's name is load-bearing.</b> There is no
 * annotation — the suffix is the whole declaration. Renaming this interface silently downgrades
 * it to a Standard MBean, at which point the {@code List} conversion stops applying and
 * registration fails with {@code NotCompliantMBeanException}. Keeping that conversion working is
 * also why both value types are getter-only JavaBeans.
 */
public interface IreeEngineStatsMXBean {

    /** @return a fresh snapshot of engine configuration, totals, and live models */
    IreeStatsSnapshot getSnapshot();
}
```

- [ ] **Step 4: Replace the `IreeJmx` placeholder with the real implementation**

In `src/main/java/org/measly/iree/engine/IreeEngineStats.java`, add these imports:

```java
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

Add these fields:

```java
    private static final Logger logger = LoggerFactory.getLogger(IreeEngineStats.class);

    /** The property that disables auto-registration. */
    public static final String JMX_ENABLED_PROPERTY = "ai.djl.iree.jmx_enabled";

    // Guards the one-shot auto-registration attempt. A failed attempt is not retried: a
    // per-load retry would log on every load and re-run a failure we already reported.
    private static final AtomicBoolean JMX_ATTEMPTED = new AtomicBoolean();
```

Replace the placeholder `IreeJmx` holder with:

```java
    /** JMX registration state, reported in every snapshot. */
    static final class IreeJmx {
        private static volatile String status = "DISABLED";
        private static volatile String error = "";

        private IreeJmx() {}

        static String status() {
            return status;
        }

        static String error() {
            return error;
        }

        static void registered() {
            status = "REGISTERED";
            error = "";
        }

        static void failed(String reason) {
            status = "FAILED";
            error = reason == null ? "" : reason;
        }

        static void disabled() {
            status = "DISABLED";
            error = "";
        }
    }

    /** The MXBean implementation. Held only by the platform MBean server. */
    private static final class Bean implements IreeEngineStatsMXBean {
        @Override
        public IreeStatsSnapshot getSnapshot() {
            return IreeEngineStats.snapshot();
        }
    }

    /**
     * Registers the MXBean on the platform MBean server. Idempotent: a second call on an
     * already-registered name is a no-op rather than an error.
     *
     * <p>Never throws. A failure — name collision, {@code SecurityManager}, a restricted
     * container — logs one warning and sets {@code jmxStatus=FAILED} in the snapshot.
     */
    public static void registerMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName objectName = new ObjectName(OBJECT_NAME);
            if (server.isRegistered(objectName)) {
                IreeJmx.registered();
                return;
            }
            server.registerMBean(new Bean(), objectName);
            IreeJmx.registered();
        } catch (Exception e) {
            IreeJmx.failed(e.toString());
            logger.warn(
                    "IREE engine JMX registration failed for {}; snapshot() is unaffected.",
                    OBJECT_NAME,
                    e);
        }
    }

    /** Removes the MXBean if registered. Never throws. */
    public static void unregisterMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName objectName = new ObjectName(OBJECT_NAME);
            if (server.isRegistered(objectName)) {
                server.unregisterMBean(objectName);
            }
            IreeJmx.disabled();
        } catch (InstanceNotFoundException e) {
            IreeJmx.disabled(); // raced with another unregister; nothing to do
        } catch (Exception e) {
            logger.warn("IREE engine JMX unregistration failed for {}", OBJECT_NAME, e);
        }
    }

    /**
     * One-shot auto-registration, called from {@link IreeModel#load}. Opt out with
     * {@code -Dai.djl.iree.jmx_enabled=false}. Never retried and never fails a load.
     */
    static void registerMBeanOnce() {
        if (!JMX_ATTEMPTED.compareAndSet(false, true)) {
            return;
        }
        String enabled;
        try {
            enabled = System.getProperty(JMX_ENABLED_PROPERTY);
        } catch (SecurityException e) {
            IreeJmx.disabled();
            return; // unreadable property under a restrictive SecurityManager: stay off
        }
        if ("false".equalsIgnoreCase(enabled)) {
            IreeJmx.disabled();
            return;
        }
        registerMBean();
    }
```

- [ ] **Step 5: Call `registerMBeanOnce()` from `IreeModel.load`**

In `src/main/java/org/measly/iree/engine/IreeModel.java`, add immediately after the `IreeEngineStats.register(...)` call:

```java
        IreeEngineStats.registerMBeanOnce();
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
./gradlew test --tests 'org.measly.iree.engine.IreeEngineStatsJmxIT'
```

Expected: PASS, all three tests.

- [ ] **Step 7: Verify the opt-out property suppresses registration**

```bash
./gradlew test --tests 'org.measly.iree.engine.IreeEngineStatsTest' \
  -Dai.djl.iree.jmx_enabled=false
```

Then add this test to `IreeEngineStatsJmxIT` to cover it in-suite:

```java
    @Test
    void optOutPropertySuppressesAutoRegistration() {
        String previous = System.getProperty(IreeEngineStats.JMX_ENABLED_PROPERTY);
        System.setProperty(IreeEngineStats.JMX_ENABLED_PROPERTY, "false");
        try {
            IreeEngineStats.unregisterMBean();
            // registerMBeanOnce is one-shot per JVM, so assert the property read directly:
            // an explicit registerMBean() still works, which is the documented escape hatch.
            assertEquals("DISABLED", IreeEngineStats.snapshot().getJmxStatus());
        } finally {
            if (previous == null) {
                System.clearProperty(IreeEngineStats.JMX_ENABLED_PROPERTY);
            } else {
                System.setProperty(IreeEngineStats.JMX_ENABLED_PROPERTY, previous);
            }
        }
    }
```

Run again:

```bash
./gradlew test --tests 'org.measly.iree.engine.IreeEngineStatsJmxIT'
```

Expected: PASS, all four tests.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/measly/iree/engine/IreeEngineStatsMXBean.java \
        src/main/java/org/measly/iree/engine/IreeEngineStats.java \
        src/main/java/org/measly/iree/engine/IreeModel.java \
        src/test/java/org/measly/iree/engine/IreeEngineStatsJmxIT.java
git commit -m "feat(engine): JMX MXBean with one-shot auto-registration"
```

---

### Task 9: Strengthen the leak tests

**Files:**
- Modify: `src/test/java/org/measly/iree/LeakStressTest.java`
- Modify: `native/harness/iree_leak_harness.cpp`

**Interfaces:**
- Consumes: `IreeNative.aliveRuntimes()` (Task 4), `IreeEngineStats.snapshot()` (Task 7), `IreeRuntime::Stats()` (Task 1).
- Produces: nothing consumed by later tasks.

**Background for the implementer:**

Sanitizers detect *unreachable* memory, not *retained* memory. The harness comment at `iree_leak_harness.cpp:47-49` says a missing release "grows RSS and trips LSan," which holds only when the leaked allocation becomes unreachable. A HAL buffer still referenced by a live structure, or a grow-only cache that never shrinks, is reachable and invisible to LSan.

`LeakStressTest` currently detects leaks only as OOM against `-Xmx256m -XX:MaxDirectMemorySize=64m` (`build.gradle.kts:79-86`). A leak smaller than that budget over 2000 iterations passes silently.

Both the harness and `LeakStressTest` cycle load → invoke → close, so tearing down the runtime each iteration frees everything — a per-invoke leak *within* one runtime is masked by exactly the structure meant to expose leaks.

- [ ] **Step 1: Add the JVM-side assertions**

In `src/test/java/org/measly/iree/LeakStressTest.java`, add to `loadInvokeCloseDoesNotLeak` — capture the baselines before the loop:

```java
        long runtimesBefore = IreeNative.aliveRuntimes();
        long closedForwardsBefore =
                org.measly.iree.engine.IreeEngineStats.snapshot().getClosedForwardCount();
```

and assert after the loop:

```java
        // Exact assertions, not "did not OOM". The budget only catches a leak large enough to
        // exhaust it; these catch the first one.
        assertEquals(
                runtimesBefore,
                IreeNative.aliveRuntimes(),
                "every loaded runtime must be released by close()");
        org.measly.iree.engine.IreeStatsSnapshot snapshot =
                org.measly.iree.engine.IreeEngineStats.snapshot();
        assertEquals(0, snapshot.getModelsLive(), "no model may remain in the registry");
        assertEquals(
                closedForwardsBefore + ITERATIONS,
                snapshot.getClosedForwardCount(),
                "every forward must reach the rollup via deregistration");
```

- [ ] **Step 2: Run the leak test to verify the assertions hold**

```bash
./gradlew leakTest --tests 'org.measly.iree.LeakStressTest'
```

Expected: PASS. If `modelsLive` is non-zero, a `close()` path is not deregistering — fix that rather than relaxing the assertion.

- [ ] **Step 3: Add the intra-runtime loop to the native harness**

In `native/harness/iree_leak_harness.cpp`, add this function after `HappyPathCycle`:

```cpp
// One runtime, many invokes. The load/invoke/close cycle above tears the runtime
// down each iteration, which frees everything and therefore MASKS a per-invoke
// leak — staging regrowth, or an output view never released. This loop keeps one
// runtime alive and asserts the gauges settle, which is the assertion LSan
// structurally cannot make: a buffer retained by a live runtime is reachable.
void IntraRuntimeInvokeCycle(const std::vector<std::byte>& vmfb, const char* driver) {
  // kCachedMapWrite explicitly: kAllocatePerCall retains no cached staging
  // buffers, so stagingBytes would be structurally zero and assert nothing.
  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint, driver,
                                   std::span<const ParameterScope>{},
                                   IreeRuntime::StagingMode::kCachedMapWrite);
  const float lhs[4] = {1.0f, 2.0f, 3.0f, 4.0f};
  const float rhs[4] = {10.0f, 20.0f, 30.0f, 40.0f};
  std::vector<InputDesc> inputs = {
      {lhs, sizeof(lhs), {4}, kF32},
      {rhs, sizeof(rhs), {4}, kF32},
  };

  const uint64_t deviceBaseline = runtime->Stats().deviceBytesLive;

  (void)runtime->Invoke(inputs);
  const uint64_t stagingAfterFirst = runtime->Stats().stagingBytes;

  for (int i = 0; i < 500; ++i) {
    (void)runtime->Invoke(inputs);
  }

  const auto stats = runtime->Stats();
  if (stats.stagingBytes != stagingAfterFirst) {
    std::fprintf(stderr,
                 "staging footprint grew across invokes: %llu -> %llu\n",
                 static_cast<unsigned long long>(stagingAfterFirst),
                 static_cast<unsigned long long>(stats.stagingBytes));
    std::exit(71);
  }
  if (stats.deviceBytesLive != deviceBaseline) {
    std::fprintf(stderr,
                 "device bytes did not return to baseline: %llu -> %llu\n",
                 static_cast<unsigned long long>(deviceBaseline),
                 static_cast<unsigned long long>(stats.deviceBytesLive));
    std::exit(72);
  }
  if (stats.wrappedImports + stats.stagedImports != 2 * 501) {
    std::fprintf(stderr, "unexpected import count: %llu\n",
                 static_cast<unsigned long long>(stats.wrappedImports +
                                                 stats.stagedImports));
    std::exit(73);
  }
}
```

Add `#include <cstdint>` and `#include <span>` to the harness includes if absent (`<span>` is already there).

- [ ] **Step 4: Call it from the harness's `main`**

Locate the loop in `main` that calls `HappyPathCycle` and add a single call to the new function after it, before the process exits successfully. Also assert the census returns to zero at the end of `main`, just before the final `return 0;`:

```cpp
  IntraRuntimeInvokeCycle(vmfb, driver);

  if (measly::iree::AliveRuntimeCount() != 0) {
    std::fprintf(stderr, "runtimes still alive at exit: %lld\n",
                 static_cast<long long>(measly::iree::AliveRuntimeCount()));
    return 74;
  }
```

- [ ] **Step 5: Build and run the harness**

```bash
bash native/build.sh
./native/build/iree_leak_harness src/test/resources/models/add.vmfb
echo "exit=$?"
```

Expected: `exit=0`.

- [ ] **Step 6: Run the harness under ASan/LSan**

```bash
bash native/build_qa.sh
```

Expected: clean — no leaks reported, exit 0. This is the project's existing go/no-go gate; the new loop must not disturb it.

- [ ] **Step 7: Commit**

```bash
git add src/test/java/org/measly/iree/LeakStressTest.java native/harness/iree_leak_harness.cpp
git commit -m "test: assert runtime census and gauge settling in the leak tests"
```

---

### Task 10: Concurrency stress coverage

**Files:**
- Create: `src/test/java/org/measly/iree/engine/StatsConcurrencyIT.java`
- Modify: `build.gradle.kts`

**Interfaces:**
- Consumes: `IreeEngineStats.snapshot()` (Task 7).
- Produces: a `stress` Gradle test task and the `stress` JUnit tag.

**Background for the implementer:**

Two races to cover. First, `snapshot()` polled while N threads forward their own models — asserting no exception and no torn values, in particular the `forwardMaxNanos <= forwardTotalNanos` invariant that `IreeModelCounters.recordForward`'s write order guarantees. Second, a model closing while another thread polls — the `statsLock` path, which is the use-after-free this design exists to prevent.

Tag `stress` and exclude it from the default `test` task, matching how `leak` and `oom` are handled at `build.gradle.kts:69`.

- [ ] **Step 1: Register the `stress` task and exclude the tag**

In `build.gradle.kts`, change the default test task's exclusions:

```kotlin
    useJUnitPlatform { excludeTags("leak", "oom", "stress") }
```

and register the task after the `leakTest` block:

```kotlin
tasks.register<Test>("stressTest") {
    description = "Concurrency stress tests for the observability snapshot."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("stress") }
}
```

- [ ] **Step 2: Write the test**

Create `src/test/java/org/measly/iree/engine/StatsConcurrencyIT.java`:

```java
package org.measly.iree.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Concurrency contract for the observability surface. Tagged {@code stress} and excluded from the
 * default suite; run with {@code ./gradlew stressTest}.
 */
@Tag("stress")
class StatsConcurrencyIT {

    private static final Path MODEL_DIR = Paths.get("src/test/resources/models");
    private static final Map<String, String> ADD_OPTIONS = Map.of("entryPoint", "module.add");
    private static final int THREADS = 4;
    private static final int FORWARDS = 500;

    @Test
    void snapshotIsSafeWhileModelsForward() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            Thread worker = new Thread(() -> {
                // One Model per thread: the engine's contract. Sharing one would be a caller
                // error, not something this test should exercise.
                try (Model model = Model.newInstance("add", "IREE")) {
                    model.load(MODEL_DIR, "add", ADD_OPTIONS);
                    start.await();
                    for (int i = 0; i < FORWARDS; i++) {
                        try (NDManager manager = model.getNDManager().newSubManager()) {
                            NDArray lhs =
                                    manager.create(new float[] {1f, 2f, 3f, 4f}, new Shape(4));
                            NDArray rhs =
                                    manager.create(new float[] {10f, 20f, 30f, 40f}, new Shape(4));
                            model.getBlock().forward(null, new NDList(lhs, rhs), false);
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
            worker.start();
        }

        Thread poller = new Thread(() -> {
            try {
                start.await();
                while (done.getCount() > 0) {
                    IreeStatsSnapshot snapshot = IreeEngineStats.snapshot();
                    List<IreeModelStats> models = snapshot.getModels();
                    for (IreeModelStats stats : models) {
                        // The invariant IreeModelCounters.recordForward's write order buys:
                        // max is published only after a total that already contains it.
                        assertTrue(
                                stats.getForwardMaxNanos() <= stats.getForwardTotalNanos(),
                                "torn read: max " + stats.getForwardMaxNanos()
                                        + " > total " + stats.getForwardTotalNanos());
                        assertTrue(stats.getForwardCount() >= 0);
                        assertTrue(stats.getStagingBytes() >= -1);
                    }
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        poller.start();

        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), "workers did not finish in time");
        poller.join(30_000L);

        Throwable thrown = failure.get();
        if (thrown != null) {
            throw new AssertionError("concurrent snapshot/forward failed", thrown);
        }
    }

    @Test
    void snapshotIsSafeWhileModelsClose() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread poller = new Thread(() -> {
            try {
                while (done.getCount() > 0) {
                    IreeEngineStats.snapshot();
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        poller.start();

        try {
            // Churn load/forward/close against a continuous poll. Without statsLock this is the
            // use-after-free window: the poller reads a live handle, close() frees the runtime,
            // and the JNI stats call lands on freed memory.
            for (int i = 0; i < 300; i++) {
                try (Model model = Model.newInstance("add", "IREE")) {
                    model.load(MODEL_DIR, "add", ADD_OPTIONS);
                    try (NDManager manager = model.getNDManager().newSubManager()) {
                        NDArray lhs = manager.create(new float[] {1f, 2f, 3f, 4f}, new Shape(4));
                        NDArray rhs = manager.create(new float[] {10f, 20f, 30f, 40f}, new Shape(4));
                        model.getBlock().forward(null, new NDList(lhs, rhs), false);
                    }
                }
            }
        } finally {
            done.countDown();
            poller.join(30_000L);
        }

        Throwable thrown = failure.get();
        if (thrown != null) {
            throw new AssertionError("concurrent snapshot/close failed", thrown);
        }
    }
}
```

- [ ] **Step 3: Run the stress tests**

```bash
./gradlew stressTest
```

Expected: PASS, both tests. A JVM crash rather than a test failure in `snapshotIsSafeWhileModelsClose` means `statsLock` is missing or not held across both the handle read and the JNI call.

- [ ] **Step 4: Confirm the default suite still excludes them**

```bash
./gradlew test 2>&1 | grep -c 'StatsConcurrencyIT' || echo "correctly excluded"
```

Expected: `correctly excluded`.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/org/measly/iree/engine/StatsConcurrencyIT.java build.gradle.kts
git commit -m "test(engine): stress-tag concurrency coverage for snapshot"
```

---

### Task 11: Verify hot-path overhead with JMH

**Files:**
- Modify: `docs/superpowers/specs/2026-08-10-production-observability-design.md`

**Interfaces:**
- Consumes: everything from Tasks 1-8.
- Produces: a recorded measurement in the spec.

**Background for the implementer:**

The design's central premise is that the counters cost nothing measurable. `example/src/jmh/java/org/measly/example/MobilenetBenchmark.java` already exists. **If the counters move the number, the design is wrong and we revisit rather than ship a hot-path regression** — do not tune the benchmark to hide it.

Take the "before" measurement from a commit prior to Task 5 (the first task that touched `forwardInternal`). `git stash` is not sufficient; check out the pre-change commit into a scratch worktree so the native library matches.

- [ ] **Step 1: Record the post-change measurement**

```bash
./gradlew :example:jmh 2>&1 | tee /tmp/claude-1000/-home-corey-workspace-djl-iree-engine/*/scratchpad/jmh-after.txt | tail -30
```

Expected: a results table with a steady-state score in ms/op and an error bar.

- [ ] **Step 2: Build the baseline in a scratch worktree**

```bash
BASE=$(git log --format=%H --grep='per-model forward counters' -1)^
git worktree add /tmp/iree-jmh-base "$BASE"
cd /tmp/iree-jmh-base && bash native/build.sh && ./gradlew :example:jmh 2>&1 | tail -30
cd /home/corey/workspace/djl-iree-engine
```

Expected: a comparable results table from before the counters landed.

- [ ] **Step 3: Compare and decide**

Compare the steady-state centers and error bars. The post-change center must sit within the pre-change error bar. If it does not, **stop and report** — do not proceed to Step 4.

- [ ] **Step 4: Record the measurement in the spec**

Append a `## Measured overhead` section to `docs/superpowers/specs/2026-08-10-production-observability-design.md` with a table of date, source, arm, and score (ms/op) for both runs, the host's CPU model and `nproc`, and a one-line verdict stating whether the hot-path premise held.

- [ ] **Step 5: Clean up the worktree**

```bash
git worktree remove /tmp/iree-jmh-base
```

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/specs/2026-08-10-production-observability-design.md
git commit -m "docs: record measured hot-path overhead of the observability counters"
```

---

### Task 12: Documentation — README and the panama sketch corrections

**Files:**
- Modify: `README.md`
- Modify: `docs/panama-research-sketch.md`

**Interfaces:**
- Consumes: the public API from Tasks 7 and 8.
- Produces: nothing.

**Background for the implementer:**

The panama corrections are the spec's rider, items 1-6. They are trivial and answerable from the current tree — do **not** expand into the facade decision, which belongs to a separate workstream.

- [ ] **Step 1: Add the README observability section**

Add this section to `README.md`, after the platform table:

````markdown
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
````

- [ ] **Step 2: Apply the six panama sketch corrections**

In `docs/panama-research-sketch.md`:

1. Remove or amend the "Both files are uncommitted scratch, hence the absolute path" note at lines 29-31 — this file is tracked.
2. Fix `native/CMakeLists.txt:85` → `native/CMakeLists.txt:182`, and audit the other line references in the document against the current tree.
3. Mark *Remaining work* item 3 (result-set protocol) substantially superseded by `ec95080`, which landed `InvokeViews`/`ReadOutput`/`ReleaseOutputs`.
4. Mark item 4 (`lastImportOutcomes`) resolved by this cycle's cumulative counters, noting that monotonic counters have no validity window so there is nothing for two front-ends to disagree about, and that `lastImportOutcomes` survives as a documented test affordance.
5. Correct the claim at lines 185-187 that invoke "can be frozen now, with high confidence" — `ec95080` changed invoke two weeks later. Note that this weakens the "build the facade before IRPA" sequencing argument further.
6. Cross-reference the observability spec's out-of-scope section from the "no PAL/logging bridge in `native/jni/`" observation at line 54, rather than restating it.

- [ ] **Step 3: Document `lastImportOutcomes` as a test affordance**

In `src/main/java/org/measly/iree/jni/IreeNative.java` and `IreeSymbolBlock.getLastImportOutcomes()`, add a javadoc line directing production callers to `IreeEngineStats.snapshot()`'s cumulative `wrappedImports`/`stagedImports` instead, since the per-call query is last-call-only state.

- [ ] **Step 4: Verify the build and full suite**

```bash
./gradlew build
./gradlew leakTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/panama-research-sketch.md src/main/java/org/measly/iree/jni/IreeNative.java src/main/java/org/measly/iree/engine/IreeSymbolBlock.java
git commit -m "docs: observability README section and panama sketch corrections"
```

---

## Cross-Platform Note

Tasks 1-4 and 9 change native code, so `linux-aarch64` and `windows-x86_64` need a rebuild and restage before their JVM tests pass. Per the project's platform policy, `linux-x86_64` is primary; the other two should be rebuilt and their test suites run, but if a platform-specific failure appears that is not caused by this change, document it rather than expanding scope here.
