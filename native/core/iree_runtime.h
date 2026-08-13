#ifndef MEASLY_IREE_RUNTIME_H
#define MEASLY_IREE_RUNTIME_H

#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <vector>

// The C++ facade over the IREE runtime: this is the middle layer in the
// JVM -> JNI -> IREE path. native/jni/ is the only caller of
// anything declared here; it marshals JNI types to/from the types below and
// otherwise does not touch IREE's C API directly. Everything IREE-specific --
// handle lifetimes, status conversion, driver/session/module setup -- is
// meant to stay behind this header so the JNI layer only ever sees
// std/measly types and std::runtime_error.
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

// Cold-path observability read. Never called from Invoke/InvokeViews.
//
// stagingBytes is the sum of the grow-only per-slot staging buffers the cached
// staging modes retain. It is exact at all times, including during a concurrent
// call: ImportOrCopy maintains it as a single atomic running sum, so a poll
// never observes the per-slot table mid-resize. It is structurally 0 under
// kAllocatePerCall, which retains none.
//
// deviceBytes* come from IREE's HAL allocator statistics. Each runtime owns its
// own device, so these are already scoped to exactly one model. When
// statisticsAvailable is false the two device figures are 0 and meaningless —
// the caller is responsible for reporting them as "unavailable".
struct RuntimeStats {
  uint64_t wrappedImports;
  uint64_t stagedImports;
  uint64_t stagingBytes;
  uint64_t deviceBytesPeak;
  uint64_t deviceBytesLive;
  bool statisticsAvailable;
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
  // ImportOrCopy). kAllocatePerCall takes one fresh HAL buffer per staged
  // input per call. The kCached* modes retain a
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

  // As above, with the staged-input fallback's staging policy. The overloads
  // without this parameter select kAllocatePerCall.
  static std::unique_ptr<IreeRuntime> Load(std::span<const std::byte> vmfb,
                                           std::string_view entryPoint,
                                           std::string_view driver,
                                           std::span<const ParameterScope> parameters,
                                           StagingMode staging);

  // Releases the session, device, and instance (in that order — see the
  // member declaration order in RuntimeState) and the retained vmfb copy.
  // Non-throwing; any IREE teardown status is not surfaced here.
  ~IreeRuntime();
  IreeRuntime(const IreeRuntime&) = delete;
  IreeRuntime& operator=(const IreeRuntime&) = delete;

  // Owning invoke: runs the call and copies every output into freshly
  // allocated OutputBuffers before returning, so nothing IREE-side needs to
  // stay alive past this call. Throws std::runtime_error on any IREE failure
  // (a failed input import/copy, a failed invoke, or a failed output read).
  // inputs are borrowed — the caller retains ownership and must keep them
  // valid only for the duration of this call.
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
  // Copies output `index`'s bytes into dst, which the caller owns and must
  // size to at least the corresponding OutputLayout::nbytes; dst is an
  // arbitrary host destination, not required to be aligned. Valid only
  // between an InvokeViews() call and the matching ReleaseOutputs(); throws
  // std::out_of_range if index is outside the pending output set (including
  // after ReleaseOutputs() or before any InvokeViews() call).
  void ReadOutput(size_t index, void* dst);  // throws std::out_of_range
  // Releases the output views retained since the last InvokeViews(). Safe to
  // call even if nothing is pending (a no-op then). Never throws.
  void ReleaseOutputs();

  // Empirical answer to "did the import zero-copy or silently stage?".
  // Deliberately part of the API, not a log line, so tests can assert it.
  std::span<const ImportOutcome> lastImportOutcomes() const;

  // Cumulative, monotonic, per-runtime. Unlike lastImportOutcomes() these have
  // no validity window: they are safe to read at any time between construction
  // and destruction, including concurrently with a call in flight. Every figure
  // is exact — see RuntimeStats.
  RuntimeStats Stats() const;

  // Takes ownership of state (an already-fully-constructed RuntimeState — see
  // Load()); this constructor does no IREE setup of its own, only the
  // AliveRuntimeCount() bookkeeping. Public rather than private so
  // std::make_unique can call it (Load() is the only caller in practice);
  // never call this directly with a partially-initialized state.
  explicit IreeRuntime(std::unique_ptr<RuntimeState> state);

 private:
  std::unique_ptr<RuntimeState> state_;
};

// Live IreeRuntime instances. A leak probe for the JVM-side stress tests and
// the native harness: unlike LSan, which sees only unreachable memory, this
// counter catches a runtime that is retained forever. Mirrors
// AlignedLiveCount() in core/aligned_alloc.h.
int64_t AliveRuntimeCount();

// Whether IREE's HAL allocator statistics are compiled into this build, i.e.
// whether RuntimeStats::deviceBytesPeak/deviceBytesLive carry meaning. This is
// a build property fixed at compile time (see the IREE_STATISTICS_ENABLE
// agreement check in native/CMakeLists.txt), so it is deliberately answerable
// WITHOUT a runtime handle — a monitoring poll must be able to report it before
// the first model loads and after the last one closes.
bool StatisticsAvailable();

}  // namespace measly::iree
#endif  // MEASLY_IREE_RUNTIME_H
