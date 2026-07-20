#include <catch2/catch_test_macros.hpp>
#include <cmath>
#include <cstddef>
#include <cstdlib>
#include <cstring>
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
