#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_string.hpp>
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
constexpr const char* kScale2Vmfb = IREE_DJL_SCALE2_VMFB;
constexpr const char* kScale2BiasIrpa = IREE_DJL_SCALE2_BIAS_IRPA;
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

TEST_CASE("two archives bound to two scopes", "[params]") {
  auto bytes = ReadFile(kScale2Vmfb);
  const ParameterScope scopes[] = {
      {"model", kScaleIrpa},
      {"bias", kScale2BiasIrpa},
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

TEST_CASE("loading without the required parameters fails", "[params]") {
  auto bytes = ReadFile(kScaleVmfb);
  // No archives supplied: the program's parameter import cannot resolve.
  // Asserting on a message substring rather than a bare throw is the point --
  // the full captured message is recorded in task-3-report.md. IREE's import
  // resolution fails at the missing-module level (io_parameters was never
  // appended), not at the missing-parameter level, so that is the stable
  // substring to assert on.
  REQUIRE_THROWS_WITH(
      IreeRuntime::Load(bytes, kEntryPoint, "local-sync",
                        std::span<const ParameterScope>{}),
      Catch::Matchers::ContainsSubstring("io_parameters"));
}
