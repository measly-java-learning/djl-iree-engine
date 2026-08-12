// Catch2 suite for the .irpa parameter-archive path: ParameterScope binding
// (native/core/iree_runtime.h) from manifest entries to the VM module's named
// scopes, through Load()'s 4-argument overload. Covers the SPLAT and
// FILE-backed archive kinds, multi-archive/multi-scope binding, and the
// error paths a bad manifest entry (missing file, empty file, truncated
// header, wrong scope name) can hit. Build and run with:
//   ./native/build_qa.sh
//   ./native/qa/iree_params_test
// (or ./native/build.sh -DIREE_DJL_BUILD_TESTS=ON and ./native/build/iree_params_test)
// (a separate binary from iree_runtime_test; same CMake QA target.)
#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_string.hpp>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <filesystem>
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
constexpr const char* kScaleIrpaZero = IREE_DJL_SCALE_IRPA_ZERO;
constexpr const char* kEntryPoint = "module.scale";
constexpr const char* kScale2Vmfb = IREE_DJL_SCALE2_VMFB;
constexpr const char* kScale2BiasIrpa = IREE_DJL_SCALE2_BIAS_IRPA;
constexpr int32_t kF32 = 0x21000020;  // IREE_HAL_ELEMENT_TYPE_FLOAT_32
}  // namespace

// Baseline: the 4-argument Load() (a SPLAT archive bound to the scope the
// program imports from) succeeds. Every later case in this file is a
// variation on this manifest-to-scope path, so this isolates a basic
// wiring break from the more specific archive-kind and error cases below.
TEST_CASE("loads a vmfb with a parameter archive", "[params]") {
  auto bytes = ReadFile(kScaleVmfb);
  const ParameterScope scopes[] = {{"model", kScaleIrpa}};
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint, "local-sync", scopes);
  REQUIRE(runtime != nullptr);
}

// Proves the bound archive's bytes actually reach the compiled program, not
// just that Load() accepted the manifest: a splat-of-2.0 weight must produce
// input*2, checked against the iree-run-module oracle rather than a value
// picked to make the assertion trivially pass.
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

TEST_CASE("golden vector: FILE-backed (zeroed) archive", "[params]") {
  // Unlike kScaleIrpa (a SPLAT archive with no on-disk storage), this fixture
  // is FILE-backed: real bytes on disk, all zero. Loading it is what makes
  // iree_io_parameter_index_add take the FILE branch (parameter_index.c:185)
  // for the first time in this suite -- see Task 7 / the file-handle row of
  // the ownership table in docs/2026-07-25-irpa-spike-findings.md.
  auto bytes = ReadFile(kScaleVmfb);
  const ParameterScope scopes[] = {{"model", kScaleIrpaZero}};
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
  // The archive is zeroed, so the program computes input * 0.
  const std::vector<float> want = {0.0f, 0.0f, 0.0f, 0.0f};
  for (size_t i = 0; i < want.size(); ++i) {
    REQUIRE(std::fabs(got[i] - want[i]) < 1e-6f);
  }
}

// Two ParameterScope entries, two different scope names, one program that
// imports from both: the expected output (input*2 + 10) can only be right if
// each archive resolved under ITS OWN scope rather than, e.g., the second
// entry silently overwriting or shadowing the first in whatever io_parameters
// composes them into.
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
  REQUIRE(outputs[0].shape == std::vector<int64_t>{4});
  REQUIRE(outputs[0].data.size() == 4 * sizeof(float));

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

namespace {
// RAII wrapper around a temp file written under the CMake-provided build-tree
// directory (IREE_DJL_TEST_TMP_DIR = ${CMAKE_CURRENT_BINARY_DIR} for this
// target), not the CWD the test binary happens to be invoked from. The file
// is removed when the guard goes out of scope, which -- since every case
// below expects Load() to throw -- is stack unwinding through a throw, not
// normal fall-through.
class TempFileGuard {
 public:
  TempFileGuard(const char* name, const char* data, size_t len) {
    path_ = std::filesystem::path(IREE_DJL_TEST_TMP_DIR) / name;
    path_str_ = path_.string();
    std::ofstream out(path_, std::ios::binary | std::ios::trunc);
    REQUIRE(out.good());
    if (len > 0) out.write(data, static_cast<std::streamsize>(len));
    out.close();
  }
  ~TempFileGuard() {
    std::error_code ec;
    std::filesystem::remove(path_, ec);  // noexcept overload: unwind must not throw
  }

  TempFileGuard(const TempFileGuard&) = delete;
  TempFileGuard& operator=(const TempFileGuard&) = delete;

  const char* c_str() const { return path_str_.c_str(); }

 private:
  std::filesystem::path path_;
  std::string path_str_;
};
}  // namespace

TEST_CASE("missing archive file throws", "[params][errors]") {
  auto bytes = ReadFile(kScaleVmfb);
  const ParameterScope scopes[] = {{"model", "/nonexistent/nope.irpa"}};
  // Full message: "...iree/runtime/src/iree/io/file_handle.c:350: NOT_FOUND;
  // failed to open file '/nonexistent/nope.irpa'". Assert on the diagnostic
  // phrase (status code + "failed to open file"), not the literal path or
  // line number, which will shift across IREE versions.
  REQUIRE_THROWS_WITH(
      IreeRuntime::Load(bytes, kEntryPoint, "local-sync", scopes),
      Catch::Matchers::ContainsSubstring("NOT_FOUND; failed to open file"));
}

TEST_CASE("zero-byte archive throws rather than crashing", "[params][errors]") {
  // This is the `touch` bypass case from the manifest contract: existence-only
  // checking means an empty file WILL reach IREE. It must fail cleanly.
  TempFileGuard file("empty.irpa", "", 0);
  auto bytes = ReadFile(kScaleVmfb);
  const ParameterScope scopes[] = {{"model", file.c_str()}};
  // Full message: "...INVALID_ARGUMENT; failed to map file handle range
  // 0-18446744073709551615 (...) from file of 0 total bytes". The mapped
  // range is an incidental huge sentinel; "0 total bytes" is the stable,
  // semantic part -- it is literally the diagnosis (empty file).
  //
  // Windows diverges: CreateFileMappingW cannot map a zero-length file, so the
  // failure surfaces earlier at file_handle.c:811 as "UNKNOWN; failed to
  // create file mapping for file handle" -- same throw, different diagnosis.
#ifdef _WIN32
  REQUIRE_THROWS_WITH(
      IreeRuntime::Load(bytes, kEntryPoint, "local-sync", scopes),
      Catch::Matchers::ContainsSubstring("UNKNOWN") &&
          Catch::Matchers::ContainsSubstring("failed to create file mapping"));
#else
  REQUIRE_THROWS_WITH(
      IreeRuntime::Load(bytes, kEntryPoint, "local-sync", scopes),
      Catch::Matchers::ContainsSubstring("INVALID_ARGUMENT") &&
          Catch::Matchers::ContainsSubstring("0 total bytes"));
#endif
}

TEST_CASE("truncated archive throws rather than crashing", "[params][errors]") {
  const char data[] = "IRPA\x00\x00\x00\x00";
  TempFileGuard file("truncated.irpa", data, sizeof(data) - 1);
  auto bytes = ReadFile(kScaleVmfb);
  const ParameterScope scopes[] = {{"model", file.c_str()}};
  // Full message: "...INVALID_ARGUMENT; not enough bytes for a valid IRPA
  // header; file may be empty or truncated" -- an explicit, self-describing
  // diagnosis, so assert on that phrase rather than just the status code.
  REQUIRE_THROWS_WITH(
      IreeRuntime::Load(bytes, kEntryPoint, "local-sync", scopes),
      Catch::Matchers::ContainsSubstring(
          "not enough bytes for a valid IRPA header"));
}

TEST_CASE("wrong scope name throws", "[params][errors]") {
  auto bytes = ReadFile(kScaleVmfb);
  // Archive is fine, but bound under a scope the program does not reference.
  const ParameterScope scopes[] = {{"not_the_model", kScaleIrpa}};
  // Full message: "...NOT_FOUND; no provider registered that handles scopes
  // like 'model'; while invoking native function io_parameters.load; ...".
  // The "no provider registered..." phrase names the actual mismatch (the
  // program's requested scope vs. the ones supplied), so it is the
  // meaningful substring, not just the status code.
  REQUIRE_THROWS_WITH(
      IreeRuntime::Load(bytes, kEntryPoint, "local-sync", scopes),
      Catch::Matchers::ContainsSubstring(
          "no provider registered that handles scopes like"));
}
