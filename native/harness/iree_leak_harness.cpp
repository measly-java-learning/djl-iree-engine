// Plain native main(). The JVM is deliberately out of the picture: libjvm's
// allocator, JIT, and signal handlers generate ASan/TSan noise that would bury
// real findings. `local-task` (with its worker pool) is compiled into the dist
// artifact and is exercised by this harness when a driver is passed as argv[3]
// (see native/tsan_gate.sh, which runs `local-task` under TSan). With the default
// `local-sync` driver the process stays single-threaded (TSan clean, zero
// clone/clone3, Threads: 1 per /proc/<pid>/status), which is what keeps the
// ASan/LSan run deterministic. See docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md.
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
constexpr int32_t kF32 = 0x21000020;
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
void HappyPathCycle(const std::vector<std::byte>& vmfb, const char* driver) {
  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint, driver);
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
void ErrorPathCycle(const std::vector<std::byte>& vmfb, const char* driver) {
  std::vector<std::byte> garbage(256, std::byte{0xAB});
  try { IreeRuntime::Load(garbage, kEntryPoint, driver); } catch (const std::runtime_error&) {}
  try { IreeRuntime::Load({}, kEntryPoint, driver); } catch (const std::runtime_error&) {}
  try { IreeRuntime::Load(vmfb, "module.nope", driver); } catch (const std::runtime_error&) {}

  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint, driver);
  const float wide[8] = {1, 2, 3, 4, 5, 6, 7, 8};
  std::vector<InputDesc> bad_shape = {
      {wide, sizeof(wide), {8}, kF32},
      {wide, sizeof(wide), {8}, kF32},
  };
  try { runtime->Invoke(bad_shape); } catch (const std::runtime_error&) {}

  const int32_t ints[4] = {1, 2, 3, 4};
  constexpr int32_t kI32 = 0x11000020;
  std::vector<InputDesc> bad_type = {
      {ints, sizeof(ints), {4}, kI32},
      {ints, sizeof(ints), {4}, kI32},
  };
  try { runtime->Invoke(bad_type); } catch (const std::runtime_error&) {}
}

// The imported input buffer must not outlive Invoke. Here the source buffer is
// freed immediately after the call returns; if anything IREE-side still held a
// pointer into it, a subsequent invoke would be a use-after-free under ASan.
void ImportEscapeCheck(const std::vector<std::byte>& vmfb, const char* driver) {
  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint, driver);

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
  const char* path = (argc > 1 && argv[1][0]) ? argv[1] : IREE_DJL_ADD_VMFB;
  const int iterations = argc > 2 ? std::atoi(argv[2]) : 100;
  const char* driver = (argc > 3 && argv[3][0]) ? argv[3] : "local-sync";

  auto vmfb = ReadFile(path);
  std::printf("driver: %s\n", driver);

  for (int i = 0; i < iterations; ++i) HappyPathCycle(vmfb, driver);
  std::printf("happy path: %d cycles ok\n", iterations);

  for (int i = 0; i < iterations; ++i) ErrorPathCycle(vmfb, driver);
  std::printf("error paths: %d cycles ok\n", iterations);

  ImportEscapeCheck(vmfb, driver);
  std::printf("import escape check ok\n");

  std::printf("HARNESS PASS\n");
  return 0;
}
