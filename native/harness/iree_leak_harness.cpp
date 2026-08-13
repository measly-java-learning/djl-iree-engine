// Plain native main(). The JVM is deliberately out of the picture: libjvm's
// allocator, JIT, and signal handlers generate ASan/TSan noise that would bury
// real findings. `local-task` (with its worker pool) is compiled into the dist
// artifact and is exercised by this harness when a driver is passed as argv[3]
// (see native/tsan_gate.sh, which runs `local-task` under TSan). With the default
// `local-sync` driver the process stays single-threaded (TSan clean, zero
// clone/clone3, Threads: 1 per /proc/<pid>/status), which is what keeps the
// ASan/LSan run deterministic. See docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md.
//
// argv contract: argv[1] is the vmfb path (default IREE_DJL_ADD_VMFB), argv[2]
// is the cycle count (default 100), argv[3] is the driver selector (default
// "local-sync"), argv[4] is an optional "scope=path" parameter-archive spec
// (see the param cycle branch in main() below).
//
// This harness, run via native/build_qa.sh under ASan+LSan with local-sync,
// IS the ASan/LSan go/no-go gate: a leak or a use-after-free anywhere in the
// facade's retain/release paths fails the build. It is deliberately
// long-running (hundreds of cycles) rather than single-shot, because a leak
// that is invisible in one call becomes visible RSS growth or a poisoned
// region over many.
//
// Two operational facts for anyone running this under a different sanitizer:
// - TSan (native/tsan_gate.sh, local-task only) requires
//   `setarch $(uname -m) -R` to disable ASLR for the process. TSan's
//   shadow-memory mapping conflicts with ASLR (the default on this class of
//   host, /proc/sys/kernel/randomize_va_space = 2); without the setarch
//   wrapper it dies immediately with "FATAL: ThreadSanitizer: unexpected
//   memory mapping" before any test code runs.
// - `local-task` TSan reports are KNOWN FALSE POSITIVES, not a signal to
//   chase: the pinned dist runtime is an uninstrumented Release build
//   (variant=default, zero __tsan symbols), and TSan needs whole-program
//   instrumentation to observe a library's synchronization. It cannot see
//   IREE's atomics or the task-executor's semaphores, so it flags ordinary
//   main<->worker submit/execute and refcounted-free handoffs as races even
//   though the harness completes correctly every run. `local-sync`, by
//   contrast, ran TSan-clean over 100 cycles with zero clone/clone3 syscalls
//   observed -- that is the configuration TSan can actually vouch for.
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <new>
#include <span>
#include <stdexcept>
#include <string>
#include <vector>

#include "core/aligned_alloc.h"
#include "core/iree_runtime.h"

using measly::iree::InputDesc;
using measly::iree::IreeRuntime;
using measly::iree::ParameterScope;

namespace {
constexpr int32_t kF32 = 0x21000020;
constexpr const char* kEntryPoint = "module.add";
// Used only when a parameter archive (argv[4]) is supplied: the add model
// takes two same-shape inputs and has no parameter imports, so a
// parameter-bound run instead loads the scale model, whose single import
// (scope "model") is exactly what the archive argument is for.
constexpr const char* kScaleEntryPoint = "module.scale";

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
  // DELIBERATELY MISALIGNED. Stack arrays would leave the staging assertions at
  // the mercy of where the frame happens to land: a 64-byte-aligned frame makes
  // both inputs import zero-copy, stagingBytes stays 0, and
  // `stagingAfterFirst == stats.stagingBytes` becomes 0 == 0 — a green
  // assertion that exercised nothing. Offsetting past the boundary forces the
  // staging fallback every run, on every host.
  auto* backing = static_cast<std::byte*>(measly::iree::AlignedAlloc(256));
  auto* lhs = reinterpret_cast<float*>(backing + 4);
  auto* rhs = reinterpret_cast<float*>(backing + 128 + 4);
  for (int i = 0; i < 4; ++i) {
    lhs[i] = static_cast<float>(i + 1);
    rhs[i] = static_cast<float>((i + 1) * 10);
  }
  std::vector<InputDesc> inputs = {
      {lhs, 4 * sizeof(float), {4}, kF32},
      {rhs, 4 * sizeof(float), {4}, kF32},
  };

  (void)runtime->Invoke(inputs);
  if (runtime->Stats().stagedImports != 2) {
    std::fprintf(stderr,
                 "expected both misaligned inputs to stage, got %llu staged\n",
                 static_cast<unsigned long long>(runtime->Stats().stagedImports));
    std::exit(70);
  }
  // Baselines AFTER the first invoke: the first call allocates the grow-only
  // cached staging buffers (one DEVICE_LOCAL buffer per input slot, retained
  // for the runtime's lifetime by design), so a pre-invoke deviceBytesLive
  // baseline would count exactly that intentional footprint as a "leak" and
  // fail structurally. What these baselines detect is GROWTH across invokes.
  const uint64_t deviceBaseline = runtime->Stats().deviceBytesLive;
  const uint64_t stagingAfterFirst = runtime->Stats().stagingBytes;
  if (stagingAfterFirst == 0) {
    std::fprintf(stderr, "staging baseline is 0; the growth check would be vacuous\n");
    std::exit(70);
  }

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

  measly::iree::AlignedFree(backing);
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

  // C++17 aligned new/delete (not posix_memalign) so this compiles under MSVC too.
  void* aligned = ::operator new(4 * sizeof(float), std::align_val_t{64});
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

  ::operator delete(aligned, std::align_val_t{64});  // poisoned by ASan from here on

  // If an imported buffer escaped Invoke, this second call touches freed memory.
  const float safe_lhs[4] = {1.0f, 2.0f, 3.0f, 4.0f};
  std::vector<InputDesc> again = {
      {safe_lhs, sizeof(safe_lhs), {4}, kF32},
      {rhs, sizeof(rhs), {4}, kF32},
  };
  runtime->Invoke(again);
}

// A full load/invoke/close cycle with a parameter archive bound, via the
// 4-argument Load. This is what exercises the parameter chain's ownership
// (file handle -> index -> provider -> module) repeatedly under ASan/LSan --
// see docs/2026-07-25-irpa-spike-findings.md. A long-running invocation of this
// harness also uses it to keep an archive bound and repeatedly read, which is
// what the /proc/<pid>/maps check observes.
//
// The golden check below assumes the caller passes the zeroed fixture
// (scale_weights_zero.irpa, per the documented invocation): input * 0 = all
// zeros. Note zero is a weak golden value -- it cannot distinguish a correct
// read from a read that landed on already-zeroed (e.g. freed/unmapped) memory,
// so this check alone does not prove the retain is load-bearing. What this
// cycle actually witnesses, run under ASan/LSan, is the *absence* of a
// use-after-free: a retain that fails to keep the handle/fd alive leaves the
// later pread() touching freed memory and a closed fd, which ASan reports.
// See the FILE-backed differential in
// docs/2026-07-25-irpa-spike-findings.md for the full argument.
void ParamCycle(const std::vector<std::byte>& vmfb, const char* driver,
                 std::span<const ParameterScope> params) {
  auto runtime = IreeRuntime::Load(vmfb, kScaleEntryPoint, driver, params);
  const float input[4] = {1.0f, 2.0f, 3.0f, 4.0f};
  std::vector<InputDesc> inputs = {{input, sizeof(input), {4}, kF32}};
  auto outputs = runtime->Invoke(inputs);
  if (outputs.size() != 1) {
    std::fprintf(stderr, "expected 1 output, got %zu\n", outputs.size());
    std::exit(71);
  }
  if (outputs[0].data.size() != 4 * sizeof(float)) {
    std::fprintf(stderr, "expected 16 bytes, got %zu\n", outputs[0].data.size());
    std::exit(74);
  }
  float got[4];
  std::memcpy(got, outputs[0].data.data(), sizeof(got));
  for (int i = 0; i < 4; ++i) {
    if (got[i] != 0.0f) {
      std::fprintf(stderr,
                    "golden mismatch at %d: got %f, want 0.0 (zeroed archive)\n",
                    i, got[i]);
      std::exit(75);
    }
  }
}
// Every exit path must run this, including the parameter-bound early return —
// that is the path most likely to strand a runtime, since it holds io_parameters
// resources the others never touch.
bool RuntimeCensusIsClean() {
  const int64_t alive = measly::iree::AliveRuntimeCount();
  if (alive != 0) {
    std::fprintf(stderr, "runtimes still alive at exit: %lld\n",
                 static_cast<long long>(alive));
    return false;
  }
  return true;
}

}  // namespace

int main(int argc, char** argv) {
  const char* path = (argc > 1 && argv[1][0]) ? argv[1] : IREE_DJL_ADD_VMFB;
  const int iterations = argc > 2 ? std::atoi(argv[2]) : 100;
  const char* driver = (argc > 3 && argv[3][0]) ? argv[3] : "local-sync";

  // argv[4], if present, is "scope=path" for a parameter archive.
  std::vector<ParameterScope> params;
  if (argc > 4) {
    std::string spec(argv[4]);
    auto eq = spec.find('=');
    if (eq == std::string::npos) {
      std::fprintf(stderr, "expected scope=path, got %s\n", argv[4]);
      std::exit(64);
    }
    params.push_back({spec.substr(0, eq), spec.substr(eq + 1)});
  }

  auto vmfb = ReadFile(path);
  std::printf("driver: %s\n", driver);

  if (!params.empty()) {
    // Parameter-archive path: the caller is expected to pass a model whose
    // entry point matches kScaleEntryPoint (e.g. scale.vmfb) rather than the
    // default add model, whose two-input signature and lack of parameter
    // imports don't fit this cycle. Runs a dedicated cycle instead of the
    // add-specific Happy/Error/ImportEscape paths above.
    for (int i = 0; i < iterations; ++i) ParamCycle(vmfb, driver, params);
    std::printf("param cycles: %d cycles ok\n", iterations);
    if (!RuntimeCensusIsClean()) return 74;
    std::printf("HARNESS PASS\n");
    return 0;
  }

  for (int i = 0; i < iterations; ++i) HappyPathCycle(vmfb, driver);
  std::printf("happy path: %d cycles ok\n", iterations);

  IntraRuntimeInvokeCycle(vmfb, driver);
  std::printf("intra-runtime invoke cycle ok\n");

  for (int i = 0; i < iterations; ++i) ErrorPathCycle(vmfb, driver);
  std::printf("error paths: %d cycles ok\n", iterations);

  ImportEscapeCheck(vmfb, driver);
  std::printf("import escape check ok\n");

  if (!RuntimeCensusIsClean()) return 74;

  std::printf("HARNESS PASS\n");
  return 0;
}
