// W2 copy-cost measurement (spike 2026-08-04): memcpy cost vs kernel time as a
// function of tensor size, on the pinned runtime. Answers the brief's Q1 "does
// the engine copy, and at what cost" with numbers.
//
// Takes the four bigscale .vmfb paths on argv in size order (see
// tools/export_bigscale.sh), one row per size:
//   N | memcpy | invoke_staged | invoke_wrapped | outcome_wrapped | output_copy
//     | staged_cached_mw | staged_cached_tr | direct_out
//
// - memcpy:        raw memcpy of N f32s between two AlignedAlloc'd buffers
//                  (the floor any copy-based path pays twice per call).
// - invoke_staged: Invoke with input from malloc'd storage — the user-buffer
//                  path as it actually behaves (outcome recorded, not assumed:
//                  glibc mmaps large mallocs, which import zero-copy).
// - invoke_wrapped:Invoke with input from 64-byte-aligned storage — the
//                  zero-copy path; outcome asserted == kWrapped.
// - output_copy:   memcpy of the output size (bigscale output == input size;
//                  the JNI layer pays this as allocateDirect + memcpy).
// - staged_cached_mw / staged_cached_tr: cached-staging modes (StagingMode
//                  kCachedMapWrite / kCachedTransfer) on the same malloc'd
//                  input as invoke_staged — measures the recoverable share of
//                  the staged delta once the per-call staging allocation is
//                  amortized away. Skipped with a warning when malloc'd
//                  storage wraps (imports zero-copy) — recorded behavior at
//                  16 KB on this host.
// - direct_out:    wrapped input + the view-based output path (InvokeViews +
//                  ReadOutput into scratch + ReleaseOutputs) — the output
//                  path with the intermediate owning vector removed. The
//                  delta vs invoke_wrapped is the vector cost; output_copy is
//                  the JNI memcpy this path eliminates entirely.
//
// Golden check before timing (out == 2*in on every arm) guards against
// a fixture mismatch — same trap as the dist add.vmfb in
// native/test/iree_runtime_test.cpp.
//
// The four sizes (4 KiB, 64 KiB, 1 MiB, 16 MiB of f32) sweep from well inside
// to well outside typical cache levels, so the memcpy floor and the
// staged-vs-wrapped delta are read as a function of size rather than pinned
// to one point that might land favorably (or not) on a given host.
//
// This binary prints raw per-size numbers on every run; it does not judge
// them. For the recorded numbers from a real run, and what they say about
// whether the staged-import cost is worth optimizing away, see
// docs/2026-08-04-borrowed-host-buffers-findings.md -- read that rather than
// trusting any figure repeated in a comment here, which will drift out of
// date the next time the runtime or the host changes.
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <new>
#include <span>
#include <string>
#include <vector>

#include "core/aligned_alloc.h"
#include "core/iree_runtime.h"

using measly::iree::IreeRuntime;

namespace {
constexpr int32_t kF32 = 0x21000020;
constexpr const char* kEntryPoint = "module.main";
constexpr size_t kSizes[] = {4096, 65536, 1048576, 16777216};

// Fold target so the copies cannot be optimized away; read at the end.
volatile double g_sink = 0.0;

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

// Steady-clock microseconds per iteration, doubling iterations until >= 50 ms
// of samples (see MemcpyMicros / InvokeMicros below).
double MemcpyMicros(size_t n) {
  float* src = static_cast<float*>(measly::iree::AlignedAlloc(n * sizeof(float)));
  float* dst = static_cast<float*>(measly::iree::AlignedAlloc(n * sizeof(float)));
  for (size_t i = 0; i < n; ++i) src[i] = static_cast<float>(i & 0xff);

  auto start = std::chrono::steady_clock::now();
  size_t iters = 1;
  for (;;) {
    for (size_t i = 0; i < iters; ++i) std::memcpy(dst, src, n * sizeof(float));
    auto elapsed = std::chrono::steady_clock::now() - start;
    if (elapsed >= std::chrono::milliseconds(50)) {
      g_sink += dst[0] + dst[n - 1];
      measly::iree::AlignedFree(src);
      measly::iree::AlignedFree(dst);
      return std::chrono::duration<double, std::micro>(elapsed).count() /
             static_cast<double>(iters);
    }
    iters *= 2;
  }
}

double InvokeMicros(IreeRuntime& runtime, std::span<const measly::iree::InputDesc> inputs) {
  auto start = std::chrono::steady_clock::now();
  size_t iters = 1;
  for (;;) {
    for (size_t i = 0; i < iters; ++i) {
      auto out = runtime.Invoke(inputs);
      // Fold the first output element so the output materialization (and its
      // owning vector) cannot be elided.
      g_sink += static_cast<double>(
          reinterpret_cast<const float*>(out[0].data.data())[0]);
    }
    auto elapsed = std::chrono::steady_clock::now() - start;
    if (elapsed >= std::chrono::milliseconds(50)) {
      return std::chrono::duration<double, std::micro>(elapsed).count() /
             static_cast<double>(iters);
    }
    iters *= 2;
  }
}

// Like InvokeMicros, but through the view-based output path: InvokeViews +
// ReadOutput straight into `scratch` + ReleaseOutputs. The output is folded
// from `scratch` so the map_read cannot be elided.
double InvokeViewsMicros(IreeRuntime& runtime,
                         std::span<const measly::iree::InputDesc> inputs,
                         void* scratch) {
  auto start = std::chrono::steady_clock::now();
  size_t iters = 1;
  for (;;) {
    for (size_t i = 0; i < iters; ++i) {
      runtime.InvokeViews(inputs);
      runtime.ReadOutput(0, scratch);
      runtime.ReleaseOutputs();
      g_sink += static_cast<double>(
          reinterpret_cast<const float*>(scratch)[0]);
    }
    auto elapsed = std::chrono::steady_clock::now() - start;
    if (elapsed >= std::chrono::milliseconds(50)) {
      return std::chrono::duration<double, std::micro>(elapsed).count() /
             static_cast<double>(iters);
    }
    iters *= 2;
  }
}

// One row. Returns nonzero on golden failure.
int MeasureSize(const char* vmfb_path, size_t n) {
  auto vmfb = ReadFile(vmfb_path);
  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint, "local-sync");
  std::vector<int64_t> shape{static_cast<int64_t>(n)};
  const size_t nbytes = n * sizeof(float);

  // --- invoke_staged: malloc'd input, whatever glibc gives us ---
  float* staged_in = static_cast<float*>(std::malloc(nbytes));
  for (size_t i = 0; i < n; ++i) staged_in[i] = 1.0f;
  std::vector<measly::iree::InputDesc> staged_inputs = {
      {staged_in, nbytes, shape, kF32}};
  auto staged_out = runtime->Invoke(staged_inputs);  // warmup + outcome
  const int outcome_staged =
      runtime->lastImportOutcomes()[0] == IreeRuntime::ImportOutcome::kWrapped ? 1 : 0;
  const double staged_us = InvokeMicros(*runtime, staged_inputs);
  std::free(staged_in);

  // --- invoke_wrapped: AlignedAlloc'd input, golden-checked ---
  float* wrapped_in =
      static_cast<float*>(measly::iree::AlignedAlloc(nbytes));
  for (size_t i = 0; i < n; ++i) wrapped_in[i] = static_cast<float>(i % 7);
  std::vector<measly::iree::InputDesc> wrapped_inputs = {
      {wrapped_in, nbytes, shape, kF32}};
  auto wrapped_out = runtime->Invoke(wrapped_inputs);  // warmup + golden
  const int outcome_wrapped =
      runtime->lastImportOutcomes()[0] == IreeRuntime::ImportOutcome::kWrapped ? 1 : 0;
  if (outcome_wrapped != 1) {
    std::fprintf(stderr, "size %zu: aligned input did NOT import zero-copy\n", n);
    return 71;
  }
  const float* r = reinterpret_cast<const float*>(wrapped_out[0].data.data());
  for (size_t i = 0; i < n; ++i) {
    if (r[i] != 2.0f * wrapped_in[i]) {
      std::fprintf(stderr, "size %zu: golden mismatch at element %zu\n", n, i);
      return 70;
    }
  }
  const double wrapped_us = InvokeMicros(*runtime, wrapped_inputs);

  // --- invoke_direct_out: wrapped input + output mapped straight into
  //     scratch (no owning vector, no JNI memcpy) ---
  float* scratch = static_cast<float*>(measly::iree::AlignedAlloc(nbytes));
  auto direct_runtime = IreeRuntime::Load(vmfb, kEntryPoint, "local-sync");
  auto direct_layouts = direct_runtime->InvokeViews(wrapped_inputs);  // warmup
  if (direct_runtime->lastImportOutcomes()[0] !=
      IreeRuntime::ImportOutcome::kWrapped) {
    std::fprintf(stderr, "size %zu: direct-out arm: aligned input did NOT import\n",
                 n);
    return 71;
  }
  direct_runtime->ReadOutput(0, scratch);
  direct_runtime->ReleaseOutputs();
  for (size_t i = 0; i < n; ++i) {
    if (scratch[i] != 2.0f * wrapped_in[i]) {
      std::fprintf(stderr,
                   "size %zu: direct-out golden mismatch at element %zu\n", n,
                   i);
      return 70;
    }
  }
  const double direct_out_us =
      InvokeViewsMicros(*direct_runtime, wrapped_inputs, scratch);
  measly::iree::AlignedFree(scratch);
  measly::iree::AlignedFree(wrapped_in);

  // --- invoke_staged_cached_*: cached staging modes on malloc'd input ---
  // (separate runtime per mode; a malloc'd input that happens to wrap is
  // skipped with a warning — recorded behavior at 16 KB on this host)
  float* cached_in = static_cast<float*>(std::malloc(nbytes));
  for (size_t i = 0; i < n; ++i) cached_in[i] = 1.0f;
  std::vector<measly::iree::InputDesc> cached_inputs = {
      {cached_in, nbytes, shape, kF32}};
  double staged_cached_us[2] = {0.0, 0.0};
  bool cached_ok[2] = {false, false};
  const IreeRuntime::StagingMode kCachedModes[2] = {
      IreeRuntime::StagingMode::kCachedMapWrite,
      IreeRuntime::StagingMode::kCachedTransfer};
  for (int m = 0; m < 2; ++m) {
    auto cached_runtime = IreeRuntime::Load(vmfb, kEntryPoint, "local-sync", {},
                                            kCachedModes[m]);
    auto cached_out = cached_runtime->Invoke(cached_inputs);  // warmup + outcome
    if (cached_runtime->lastImportOutcomes()[0] !=
        IreeRuntime::ImportOutcome::kStaged) {
      std::fprintf(stderr,
                   "size %zu: cached-staging arm %d: malloc'd input WRAPPED; "
                   "arm skipped (not staged)\n",
                   n, m);
      continue;
    }
    const float* cr = reinterpret_cast<const float*>(cached_out[0].data.data());
    for (size_t i = 0; i < n; ++i) {
      if (cr[i] != 2.0f * cached_in[i]) {
        std::fprintf(stderr,
                     "size %zu: cached-staging golden mismatch at element %zu\n",
                     n, i);
        return 70;
      }
    }
    staged_cached_us[m] = InvokeMicros(*cached_runtime, cached_inputs);
    cached_ok[m] = true;
  }
  std::free(cached_in);

  const double memcpy_us = MemcpyMicros(n);
  const double output_copy_us = MemcpyMicros(n);  // output size == input size

  std::printf(
      "%9zu | %10.2f | %15.2f | %16.2f | %-15d | %12.2f | %15.2f | %14.2f | "
      "%11.2f\n",
      n, memcpy_us, staged_us, wrapped_us, outcome_wrapped, output_copy_us,
      staged_cached_us[0], staged_cached_us[1], direct_out_us);
  std::printf("  staged outcome: %d (%s) | cached_mw:%s | cached_tr:%s\n",
              outcome_staged, outcome_staged ? "WRAPPED" : "STAGED",
              cached_ok[0] ? "staged" : "skipped",
              cached_ok[1] ? "staged" : "skipped");
  std::printf(
      "  deltas: staged-staged_cached_mw=%s | staged-staged_cached_tr=%s | "
      "wrapped-direct_out=%s\n",
      cached_ok[0] ? std::to_string(staged_us - staged_cached_us[0]).c_str()
                   : "n/a",
      cached_ok[1] ? std::to_string(staged_us - staged_cached_us[1]).c_str()
                   : "n/a",
      std::to_string(wrapped_us - direct_out_us).c_str());
  return 0;
}
}  // namespace

int main(int argc, char** argv) {
  if (argc < 5) {
    std::fprintf(stderr,
                 "usage: %s bigscale_4096.vmfb bigscale_65536.vmfb "
                 "bigscale_1048576.vmfb bigscale_16777216.vmfb\n",
                 argv[0]);
    return 64;
  }
  std::printf("          N |   memcpy |  invoke_staged |   invoke_wrapped | "
              "outcome_wrapped |   output_copy | staged_cached_mw | "
              "staged_cached_tr |  direct_out\n");
  for (int s = 0; s < 4; ++s) {
    const int rc = MeasureSize(argv[1 + s], kSizes[s]);
    if (rc != 0) return rc;
  }
  std::printf("sink=%f\n", g_sink);
  return 0;
}
