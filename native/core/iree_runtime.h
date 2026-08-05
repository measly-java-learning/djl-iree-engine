#ifndef MEASLY_IREE_RUNTIME_H
#define MEASLY_IREE_RUNTIME_H

#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace measly::iree {

// Borrowed input: a host pointer the caller keeps valid across Invoke().
// May be imported zero-copy or staged — see lastImportOutcomes().
struct InputDesc {
  const void* data;
  size_t nbytes;
  std::vector<int64_t> shape;
  int32_t elementType;  // iree_hal_element_type_t
};

// OWNING output. Unlike ExecuTorch's borrow-view OutputView, this holds its
// own bytes: every IREE handle is released before Invoke() returns.
struct OutputBuffer {
  std::vector<int64_t> shape;
  int32_t elementType;
  std::vector<std::byte> data;
};

// Output METADATA for the view-based invoke (InvokeViews). The owning
// OutputBuffer exists to be handed out of the facade; OutputLayout exists to
// let the JNI marshal outputs directly into JVM-owned buffers without a
// std::vector detour — see ReadOutput()/ReleaseOutputs().
struct OutputLayout {
  std::vector<int64_t> shape;
  int32_t elementType;
  uint64_t nbytes;
};

struct RuntimeState;  // pimpl

// One parameter archive bound to a scope name. `scope` is the name the compiled
// program references (e.g. "model" for #stream.parameter.named<"model"::"weight">);
// an empty scope binds the archive's global scope. `path` is a filesystem path --
// IREE opens and owns the file descriptor and does positional reads (pread) of
// only the spans the program imports, so the caller never has to buffer the
// archive.
//
// NOTE: the scope is a RUNTIME BINDING, not a property of the archive. The same
// .irpa can be bound under any scope name.
struct ParameterScope {
  std::string scope;
  std::string path;
};

class IreeRuntime {
 public:
  enum class ImportOutcome { kWrapped, kStaged };

  // How the staged input fallback obtains its staging buffer (see
  // ImportOrCopy). kAllocatePerCall is the historical behavior — one fresh
  // HAL buffer per staged input per call. The kCached* modes retain a
  // grow-only buffer PER INPUT SLOT on the runtime and reuse it across calls,
  // amortizing the allocation; the two cached modes differ only in the copy
  // primitive (host map_write vs device transfer_h2d). ImportOutcome stays
  // kStaged in every mode — a copy still happened.
  enum class StagingMode { kAllocatePerCall = 0, kCachedMapWrite, kCachedTransfer };

  // Throws std::runtime_error on failure. The vmfb bytes are COPIED: IREE's
  // append-from-memory with a null allocator does not take ownership, so the
  // data must outlive the session and we own that lifetime ourselves.
  // `driver` selects the IREE HAL driver (default "local-sync"; e.g. "local-task"
  // for the worker-pool driver); an unknown/unavailable driver throws
  // std::runtime_error at load.
  static std::unique_ptr<IreeRuntime> Load(std::span<const std::byte> vmfb,
                                           std::string_view entryPoint,
                                           std::string_view driver = "local-sync");

  // As above, but also registers parameter archives. Each archive is opened,
  // parsed, and wrapped in a provider; all providers are composed into a single
  // io_parameters VM module which is appended to the session BEFORE the bytecode
  // module so the program's parameter imports resolve.
  static std::unique_ptr<IreeRuntime> Load(std::span<const std::byte> vmfb,
                                           std::string_view entryPoint,
                                           std::string_view driver,
                                           std::span<const ParameterScope> parameters);

  // As above, with the staged-input fallback's staging policy. Defaults to
  // the historical per-call allocation, so no existing callsite changes.
  static std::unique_ptr<IreeRuntime> Load(std::span<const std::byte> vmfb,
                                           std::string_view entryPoint,
                                           std::string_view driver,
                                           std::span<const ParameterScope> parameters,
                                           StagingMode staging);

  ~IreeRuntime();
  IreeRuntime(const IreeRuntime&) = delete;
  IreeRuntime& operator=(const IreeRuntime&) = delete;

  std::vector<OutputBuffer> Invoke(std::span<const InputDesc> inputs);

  // View-based invoke: runs the call and returns only output METADATA; the
  // output views themselves are retained by the runtime until ReleaseOutputs().
  // ReadOutput() then materializes one output into an arbitrary host
  // destination (a copy — no alignment requirement). Safe because execution
  // is synchronous for both shipped drivers (call return == completion,
  // findings 2026-08-04 §5): nothing IREE-side can outlive the caller's
  // ReleaseOutputs(). Invoke() and InvokeViews() are interchangeable for
  // inputs; each clears the other's pending outputs first, so forgetting
  // ReleaseOutputs() leaks nothing (stale-batch guard).
  std::vector<OutputLayout> InvokeViews(std::span<const InputDesc> inputs);
  void ReadOutput(size_t index, void* dst);  // throws std::out_of_range
  void ReleaseOutputs();

  // Empirical answer to "did the import zero-copy or silently stage?".
  // Deliberately part of the API, not a log line, so tests can assert it.
  std::span<const ImportOutcome> lastImportOutcomes() const;

  explicit IreeRuntime(std::unique_ptr<RuntimeState> state);

 private:
  std::unique_ptr<RuntimeState> state_;
};

}  // namespace measly::iree
#endif  // MEASLY_IREE_RUNTIME_H
