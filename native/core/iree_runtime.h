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

class IreeRuntime {
 public:
  enum class ImportOutcome { kWrapped, kStaged };

  // Throws std::runtime_error on failure. The vmfb bytes are COPIED: IREE's
  // append-from-memory with a null allocator does not take ownership, so the
  // data must outlive the session and we own that lifetime ourselves.
  static std::unique_ptr<IreeRuntime> Load(std::span<const std::byte> vmfb,
                                           std::string_view entryPoint,
                                           std::string_view driver = "local-sync");

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
