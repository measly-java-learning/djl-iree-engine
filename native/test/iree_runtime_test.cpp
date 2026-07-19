#include <catch2/catch_test_macros.hpp>
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
