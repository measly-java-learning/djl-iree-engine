#include <catch2/catch_test_macros.hpp>
#include <catch2/generators/catch_generators_all.hpp>
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <new>
#include <span>
#include <stdexcept>
#include <vector>
#include "array_size_limits.h"
#include "core/aligned_alloc.h"
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

// Pins the boundary the invoke() output guards enforce (issue #15): an output
// of exactly INT32_MAX bytes is the largest jint that allocateDirect can take;
// one byte more would truncate. The >2 GiB path itself is untested (would need
// a 2 GiB fixture); this case fixes the limit the guard is compiled against.
TEST_CASE("jni array size limit: outputs above INT32_MAX bytes must be rejected",
          "[runtime][jni]") {
  using measly::iree::exceedsJniArrayLimit;
  REQUIRE_FALSE(exceedsJniArrayLimit(static_cast<size_t>(INT32_MAX)));
  REQUIRE(exceedsJniArrayLimit(static_cast<size_t>(INT32_MAX) + 1));
}

#include <cstdint>

namespace {
// IREE_HAL_ELEMENT_TYPE_FLOAT_32 as an int32_t, so the facade header needs no
// IREE include. Asserted equal to the real constant in the test below.
constexpr int32_t kF32 = 0x21000020;
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
  // C++17 aligned new/delete (not posix_memalign) so this compiles under MSVC too.
  void* aligned = ::operator new(4 * sizeof(float), std::align_val_t{64});
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
  ::operator delete(aligned, std::align_val_t{64});
}

// The 64-byte contract is authoritative in the pinned dist:
// IREE_HAL_HEAP_BUFFER_ALIGNMENT = 64 (iree/base/config.h:238-245) —
// "Executables are compiled with alignment expectations and the runtime
// alignment must be greater than or equal to the alignment set in the
// compiler. External buffers wrapped by HAL buffers must meet this alignment
// requirement." Measured 2026-07-19
// (docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md:17-33) and
// re-confirmed on the current tree: an aligned allocation imports zero-copy.
// This assertion pins runtime v3.11.0-11 behavior and MUST be revisited on a
// runtime bump.
TEST_CASE("aligned host allocation imports zero-copy on the pinned runtime",
          "[runtime][import]") {
  auto bytes = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint);

  float* lhs =
      static_cast<float*>(measly::iree::AlignedAlloc(4 * sizeof(float)));
  for (int i = 0; i < 4; ++i) lhs[i] = static_cast<float>(i);
  const float rhs[4] = {0.0f, 0.0f, 0.0f, 0.0f};

  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, 4 * sizeof(float), {4}, kF32},
      {rhs, sizeof(rhs), {4}, kF32},
  };
  auto outputs = runtime->Invoke(inputs);

  auto outcomes = runtime->lastImportOutcomes();
  REQUIRE(outcomes.size() == 2);
  // The aligned input must wrap. The stack input's outcome is not this case's
  // subject (stack alignment is compiler-determined), so only [0] is asserted.
  REQUIRE(outcomes[0] == IreeRuntime::ImportOutcome::kWrapped);

  measly::iree::AlignedFree(lhs);
}

// --- W4-adjacent spike (2026-08-04): cached staging + direct output map -------

// Cached staging (StagingMode::kCachedMapWrite / kCachedTransfer): one
// grow-only staging buffer per input slot, reused across calls. The input
// pointers are deliberately MISALIGNED (base+1): glibc's alignment for small
// malloc'd blocks is host-dependent, so a plain malloc can land 64-aligned and
// import zero-copy — a +1 pointer can never satisfy IREE's 64-byte import
// alignment, making the kStaged outcome deterministic on every host. Two
// invokes on the SAME runtime with different values must both produce the
// golden result: a single shared staging buffer would clobber input 0 with
// input 1's copy (both inputs stage), yielding 2*rhs instead of lhs+rhs.
TEST_CASE("cached staging reuses per-slot buffers across invokes", "[runtime][import]") {
  const auto mode = GENERATE(IreeRuntime::StagingMode::kCachedMapWrite,
                             IreeRuntime::StagingMode::kCachedTransfer);
  auto bytes = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint, "local-sync", {}, mode);

  float* base0 = static_cast<float*>(std::malloc(4 * sizeof(float) + 64));
  float* base1 = static_cast<float*>(std::malloc(4 * sizeof(float) + 64));
  float* mis0 = reinterpret_cast<float*>(reinterpret_cast<uintptr_t>(base0) + 1);
  float* mis1 = reinterpret_cast<float*>(reinterpret_cast<uintptr_t>(base1) + 1);

  const float lhs1[4] = {1.0f, 2.0f, 3.0f, 4.0f};
  const float rhs1[4] = {10.0f, 20.0f, 30.0f, 40.0f};
  std::memcpy(mis0, lhs1, sizeof(lhs1));
  std::memcpy(mis1, rhs1, sizeof(rhs1));
  std::vector<measly::iree::InputDesc> first = {
      {mis0, 4 * sizeof(float), {4}, kF32},
      {mis1, 4 * sizeof(float), {4}, kF32},
  };

  auto outputs = runtime->Invoke(first);
  auto outcomes = runtime->lastImportOutcomes();
  REQUIRE(outcomes.size() == 2);
  REQUIRE(outcomes[0] == IreeRuntime::ImportOutcome::kStaged);
  REQUIRE(outcomes[1] == IreeRuntime::ImportOutcome::kStaged);
  const float* r1 = reinterpret_cast<const float*>(outputs[0].data.data());
  REQUIRE(r1[0] == 11.0f);
  REQUIRE(r1[1] == 22.0f);
  REQUIRE(r1[2] == 33.0f);
  REQUIRE(r1[3] == 44.0f);

  const float lhs2[4] = {5.0f, 6.0f, 7.0f, 8.0f};
  const float rhs2[4] = {1.0f, 1.0f, 1.0f, 1.0f};
  std::memcpy(mis0, lhs2, sizeof(lhs2));
  std::memcpy(mis1, rhs2, sizeof(rhs2));
  auto again = runtime->Invoke(first);
  const float* r2 = reinterpret_cast<const float*>(again[0].data.data());
  REQUIRE(r2[0] == 6.0f);
  REQUIRE(r2[1] == 7.0f);
  REQUIRE(r2[2] == 8.0f);
  REQUIRE(r2[3] == 9.0f);

  std::free(base0);
  std::free(base1);
}

// The grow branch (a slot's buffer is too small for the new input) is not
// reachable through a successful call on the compiler-free fixtures — every
// committed model is fixed-shape {4}. It IS reachable through the error path:
// ImportOrCopy stages the 1024-element inputs (growing both slots to 4 KB)
// BEFORE iree_runtime_call_invoke rejects the shape mismatch. That exercises
// the release-then-reallocate ordering and the throw-path release of the views
// holding the grown buffers; the follow-up golden proves the grown slots are
// reusable and nothing dangled (ASan would catch a use-after-free).
TEST_CASE("cached staging grows its buffer through the error path", "[runtime][import]") {
  auto bytes = ReadFile(kAddVmfb);
  auto runtime =
      IreeRuntime::Load(bytes, kEntryPoint, "local-sync", {},
                        IreeRuntime::StagingMode::kCachedMapWrite);

  const float lhs[4] = {1.0f, 2.0f, 3.0f, 4.0f};
  const float rhs[4] = {10.0f, 20.0f, 30.0f, 40.0f};
  std::vector<measly::iree::InputDesc> ok = {
      {lhs, sizeof(lhs), {4}, kF32},
      {rhs, sizeof(rhs), {4}, kF32},
  };
  auto outputs = runtime->Invoke(ok);
  REQUIRE(reinterpret_cast<const float*>(outputs[0].data.data())[3] == 44.0f);

  // 1024 f32s = 4 KB per input: the model rejects the shape, but only after
  // ImportOrCopy has staged both inputs into grown (4 KB) slot buffers.
  float big0[1024], big1[1024];
  std::fill(std::begin(big0), std::end(big0), 1.0f);
  std::fill(std::begin(big1), std::end(big1), 2.0f);
  std::vector<measly::iree::InputDesc> too_big = {
      {big0, sizeof(big0), {1024}, kF32},
      {big1, sizeof(big1), {1024}, kF32},
  };
  REQUIRE_THROWS_AS(runtime->Invoke(too_big), std::runtime_error);

  // The grown buffers are still owned by the cache and reused (not shrunk).
  auto again = runtime->Invoke(ok);
  const float* r = reinterpret_cast<const float*>(again[0].data.data());
  REQUIRE(r[0] == 11.0f);
  REQUIRE(r[3] == 44.0f);
}

// --- Phase B: view-based output path -----------------------------------------

// InvokeViews + ReadOutput + ReleaseOutputs: the runtime retains the output
// views between the calls, so the JNI layer can map directly into a JVM-owned
// buffer. The golden must match the owning Invoke's exactly. Input 0 is
// AlignedAlloc'd (not a stack array) so the kWrapped outcome assertion is
// deterministic — stack alignment is compiler-determined (see the aligned
// host allocation case).
TEST_CASE("InvokeViews/ReadOutput materializes the golden output", "[runtime][views]") {
  auto bytes = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint);

  float* lhs = static_cast<float*>(measly::iree::AlignedAlloc(4 * sizeof(float)));
  lhs[0] = 1.0f;
  lhs[1] = 2.0f;
  lhs[2] = 3.0f;
  lhs[3] = 4.0f;
  const float rhs[4] = {10.0f, 20.0f, 30.0f, 40.0f};
  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, 4 * sizeof(float), {4}, kF32},
      {rhs, sizeof(rhs), {4}, kF32},
  };

  auto layouts = runtime->InvokeViews(inputs);
  REQUIRE(layouts.size() == 1);
  REQUIRE(layouts[0].shape == std::vector<int64_t>{4});
  REQUIRE(layouts[0].nbytes == 4 * sizeof(float));
  REQUIRE(layouts[0].elementType == kF32);

  float dst[4] = {};
  runtime->ReadOutput(0, dst);
  REQUIRE(dst[0] == 11.0f);
  REQUIRE(dst[1] == 22.0f);
  REQUIRE(dst[2] == 33.0f);
  REQUIRE(dst[3] == 44.0f);
  runtime->ReleaseOutputs();

  // lastImportOutcomes is filled by the view path too.
  REQUIRE(runtime->lastImportOutcomes()[0] == IreeRuntime::ImportOutcome::kWrapped);
  measly::iree::AlignedFree(lhs);
}

TEST_CASE("ReadOutput out of range throws", "[runtime][views]") {
  auto bytes = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint);

  const float lhs[4] = {1.0f, 2.0f, 3.0f, 4.0f};
  const float rhs[4] = {10.0f, 20.0f, 30.0f, 40.0f};
  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, sizeof(lhs), {4}, kF32},
      {rhs, sizeof(rhs), {4}, kF32},
  };
  runtime->InvokeViews(inputs);
  REQUIRE_THROWS_AS(runtime->ReadOutput(1, nullptr), std::out_of_range);
  runtime->ReleaseOutputs();
}

// Stale-batch guard: Invoke after InvokeViews (without ReleaseOutputs) must
// clear the pending views — no leak, no double-release, ASan-clean.
TEST_CASE("Invoke after InvokeViews releases pending views", "[runtime][views]") {
  auto bytes = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint);

  const float lhs[4] = {1.0f, 2.0f, 3.0f, 4.0f};
  const float rhs[4] = {10.0f, 20.0f, 30.0f, 40.0f};
  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, sizeof(lhs), {4}, kF32},
      {rhs, sizeof(rhs), {4}, kF32},
  };
  auto layouts = runtime->InvokeViews(inputs);
  REQUIRE(layouts.size() == 1);

  // Deliberately no ReleaseOutputs before the owning Invoke.
  auto outputs = runtime->Invoke(inputs);
  REQUIRE(outputs.size() == 1);
  REQUIRE(reinterpret_cast<const float*>(outputs[0].data.data())[0] == 11.0f);
}
// Borrow-lifetime proof, same shape as the leak harness's ImportEscapeCheck:
// the imported input buffer is freed immediately after Invoke returns, then
// another invoke runs with a fresh input. If anything IREE-side still held a
// pointer into the freed block, the second invoke would be a use-after-free
// under ASan — the guarantee the Cleaner-based Java path (§5) relies on: a
// buffer is only freed when no live call can be using it.
TEST_CASE("aligned buffer freed after invoke leaves no dangling import",
          "[runtime][import]") {
  auto bytes = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint);

  float* lhs =
      static_cast<float*>(measly::iree::AlignedAlloc(4 * sizeof(float)));
  for (int i = 0; i < 4; ++i) lhs[i] = static_cast<float>(i);
  const float rhs[4] = {1.0f, 1.0f, 1.0f, 1.0f};

  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, 4 * sizeof(float), {4}, kF32},
      {rhs, sizeof(rhs), {4}, kF32},
  };
  auto outputs = runtime->Invoke(inputs);
  REQUIRE(runtime->lastImportOutcomes()[0] == IreeRuntime::ImportOutcome::kWrapped);

  measly::iree::AlignedFree(lhs);  // poisoned by ASan from here on

  const float fresh[4] = {2.0f, 2.0f, 2.0f, 2.0f};
  std::vector<measly::iree::InputDesc> again = {
      {fresh, sizeof(fresh), {4}, kF32},
      {rhs, sizeof(rhs), {4}, kF32},
  };
  auto outputs2 = runtime->Invoke(again);
  REQUIRE(outputs2.size() == 1);
}

// Compiler-free post-link smoke test (Task 2, brief §Step 4): the dist
// artifact ships its own add.vmfb, compiled by the dist project itself with
// no compiler present anywhere in *our* build. Handover §5 describes it in
// prose as "four int32 inputs" -- that prose does NOT match reality. Per
// Step 4's instruction to determine the signature empirically rather than
// hand-guess a type constant: `iree-dump-module` on the dist's add.vmfb
// shows `sync func @add(%input0: tensor<4xf32>, %input1: tensor<4xf32>) ->
// (%output0: tensor<4xf32>)`, and the element-type constant baked into its
// bytecode's hal.buffer_view.assert/create calls is 0x21000020 -- that is
// FLOAT_32, not INT_32 (0x10000020) or SINT_32 (0x11000020). Confirmed by
// running it: f32 inputs succeed and produce [11,22,33,44]; i32 inputs are
// rejected with INVALID_ARGUMENT ("expected f32 (21000020) but have i32
// (10000020)"). Filed as a doc bug against the dist:
// https://github.com/measly-java-learning/iree-runtime-dist/issues/5
TEST_CASE("dist fixture: compiler-free smoke test", "[runtime][dist]") {
  constexpr const char* kDistAddVmfb = IREE_DIST_ADD_VMFB;
  auto bytes = ReadFile(kDistAddVmfb);
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
// EMPIRICAL DEVIATION FROM THE BRIEF: this case does not throw. module.add's
// entry function has a fixed compiled signature (tensor<4xf32>). IREE
// validates the caller-declared element type on an input buffer view against
// that signature via hal.buffer_view.assert (see
// iree/runtime/src/iree/modules/hal/utils/buffer_diagnostics.c): a real,
// recognized-but-mismatched type tag (si32 in place of the expected f32) is
// rejected with INVALID_ARGUMENT, surfaced here as a thrown
// std::runtime_error. (An earlier version of this test used a bogus,
// non-IREE-encoded placeholder tag that hal.buffer_view.assert could not
// recognize as any type, so it silently slipped through to a miscomputed
// result instead of being rejected -- that was an artifact of the bad
// constant, not of IREE ignoring element type. With the real si32 encoding,
// IREE's own validation catches the mismatch.)
TEST_CASE("rejects a valid-but-mismatched element type", "[runtime][errors]") {
  auto bytes = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint);

  const int32_t lhs[4] = {1, 2, 3, 4};
  const int32_t rhs[4] = {1, 2, 3, 4};
  constexpr int32_t kI32 = 0x11000020;  // real si32 type tag; model expects f32
  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, sizeof(lhs), {4}, kI32},
      {rhs, sizeof(rhs), {4}, kI32},
  };

  REQUIRE_THROWS_AS(runtime->Invoke(inputs), std::runtime_error);
}

TEST_CASE("local-task driver loads and matches local-sync", "[runtime][driver]") {
  auto bytes = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint, "local-task");
  REQUIRE(runtime != nullptr);

  const float lhs[4] = {1.0f, 2.0f, 3.0f, 4.0f};
  const float rhs[4] = {10.0f, 20.0f, 30.0f, 40.0f};
  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, sizeof(lhs), {4}, kF32},
      {rhs, sizeof(rhs), {4}, kF32},
  };
  auto outputs = runtime->Invoke(inputs);
  REQUIRE(outputs.size() == 1);
  const float* r = reinterpret_cast<const float*>(outputs[0].data.data());
  REQUIRE(r[0] == 11.0f);
  REQUIRE(r[3] == 44.0f);
}

TEST_CASE("unknown driver fails cleanly at load", "[runtime][driver]") {
  auto bytes = ReadFile(kAddVmfb);
  REQUIRE_THROWS_AS(IreeRuntime::Load(bytes, kEntryPoint, "no-such-driver"),
                    std::runtime_error);
}

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

TEST_CASE("Stats replaces rather than accumulates a regrown staging slot") {
  auto vmfb = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint, "local-sync",
                                   std::span<const measly::iree::ParameterScope>{},
                                   IreeRuntime::StagingMode::kCachedMapWrite);

  // Misaligned so both inputs take the staging fallback.
  auto* block = static_cast<std::byte*>(measly::iree::AlignedAlloc(1024));
  auto* lhs = reinterpret_cast<float*>(block + 4);
  auto* rhs = reinterpret_cast<float*>(block + 512 + 4);
  for (int i = 0; i < 8; ++i) {
    lhs[i] = 1.0f;
    rhs[i] = 2.0f;
  }

  std::vector<measly::iree::InputDesc> small = {
      {lhs, 4 * sizeof(float), {4}, kF32},
      {rhs, 4 * sizeof(float), {4}, kF32},
  };
  (void)runtime->Invoke(small);
  const uint64_t afterSmall = runtime->Stats().stagingBytes;
  REQUIRE(afterSmall == 2 * 4 * sizeof(float));

  // Twice the bytes into the same two slots. ImportOrCopy stages BEFORE the
  // call runs, so the slots regrow even though the module's static 4xf32
  // signature then rejects the invoke — which is exactly the accounting path
  // worth pinning: a regrown slot must REPLACE its old contribution to the
  // running sum, not add to it, and a slot must not be double-counted when the
  // call it was staged for throws.
  std::vector<measly::iree::InputDesc> large = {
      {lhs, 8 * sizeof(float), {8}, kF32},
      {rhs, 8 * sizeof(float), {8}, kF32},
  };
  try {
    (void)runtime->Invoke(large);
  } catch (const std::runtime_error&) {
    // Shape mismatch is expected and irrelevant here; the staging already happened.
  }

  // 64, not 32 + 64: the old contribution is retired before the new one lands.
  REQUIRE(runtime->Stats().stagingBytes == 2 * 8 * sizeof(float));

  // And shrinking back does not shrink the footprint — the buffers are grow-only.
  try {
    (void)runtime->Invoke(small);
  } catch (const std::runtime_error&) {
  }
  REQUIRE(runtime->Stats().stagingBytes == 2 * 8 * sizeof(float));

  measly::iree::AlignedFree(block);
}

TEST_CASE("StatisticsAvailable answers without a runtime and agrees with Stats") {
  // Handle-free by design: the snapshot must be able to report this build
  // property with no model loaded. Agreement with the per-runtime field is what
  // makes the two interchangeable.
  const bool standalone = measly::iree::StatisticsAvailable();
  auto vmfb = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint, "local-sync");
  REQUIRE(runtime->Stats().statisticsAvailable == standalone);
}
