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

  ~IreeRuntime();
  IreeRuntime(const IreeRuntime&) = delete;
  IreeRuntime& operator=(const IreeRuntime&) = delete;

  std::vector<OutputBuffer> Invoke(std::span<const InputDesc> inputs);

  // Empirical answer to "did the import zero-copy or silently stage?".
  // Deliberately part of the API, not a log line, so tests can assert it.
  std::span<const ImportOutcome> lastImportOutcomes() const;

  explicit IreeRuntime(std::unique_ptr<RuntimeState> state);

 private:
  std::unique_ptr<RuntimeState> state_;
};

}  // namespace measly::iree
#endif  // MEASLY_IREE_RUNTIME_H
