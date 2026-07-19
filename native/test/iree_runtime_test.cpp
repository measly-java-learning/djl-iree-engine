#include <catch2/catch_test_macros.hpp>
#include <cstddef>
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
