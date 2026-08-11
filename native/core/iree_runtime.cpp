#include "core/iree_runtime.h"

#include <atomic>
#include <cstring>
#include "core/iree_handles.h"
#include "core/iree_status.h"
#include "iree/hal/buffer_view_util.h"
#include "iree/io/file_handle.h"
#include "iree/io/formats/parser_registry.h"
#include "iree/io/parameter_index.h"
#include "iree/io/parameter_index_provider.h"
#include "iree/modules/io/parameters/module.h"
#include "iree/runtime/api.h"

namespace measly::iree {

namespace {
std::atomic<int64_t> g_runtimes_live{0};
}  // namespace

int64_t AliveRuntimeCount() {
  return g_runtimes_live.load(std::memory_order_relaxed);
}

bool StatisticsAvailable() {
#if IREE_STATISTICS_ENABLE
  return true;
#else
  return false;
#endif
}

struct RuntimeState {
  // Owns a copy of the flatbuffer. append_bytecode_module_from_memory with a
  // null allocator does NOT copy, so these bytes must outlive the session.
  // Declared first so it is destroyed last.
  std::vector<std::byte> vmfb;
  std::string entryPoint;
  InstancePtr instance;
  DevicePtr device;
  // No parameter-chain members here. IREE retains every level of the chain
  // internally (file handle <- index <- provider <- io_parameters module <-
  // session/context), so the caller holds none of it -- the file/index/
  // provider/module are scoped locally inside Load's parameter-loading block
  // and released before Load returns. Maintainer rule: IREE retains every
  // level; hold nothing. Verified under ASan by deliberate-drop probing; see
  // the ownership table and the FILE-backed differential in
  // docs/2026-07-25-irpa-spike-findings.md for the full evidence.
  SessionPtr session;
  std::vector<IreeRuntime::ImportOutcome> lastImportOutcomes;

  // Cumulative import outcomes, for the observability snapshot. Incremented in
  // RunCall, which is the single input-preparation path for both Invoke and
  // InvokeViews. Atomic with relaxed ordering: RunCall writes them from the
  // model's thread while Stats() may read them from a monitoring poll, and a
  // bare uint64_t would be a data race. Relaxed is sufficient — each counter is
  // a standalone cumulative count with no ordering constraints against the read
  // side (the reader needs no happens-before edge to a specific call).
  std::atomic<uint64_t> wrappedImports{0};
  std::atomic<uint64_t> stagedImports{0};

  // Staged-input fallback policy. The cached modes retain one grow-only
  // staging buffer PER INPUT SLOT for the runtime's lifetime — see
  // ImportOrCopy. Lock-free by contract: Invoke/InvokeViews are single-flight
  // per model (README: forward() is not thread-safe on the same model).
  IreeRuntime::StagingMode stagingMode = IreeRuntime::StagingMode::kAllocatePerCall;
  std::vector<BufferPtr> cachedStaging;
  std::vector<iree_device_size_t> cachedStagingSizes;

  // Running sum of cachedStagingSizes, maintained alongside it in ImportOrCopy.
  // Stats() reads THIS rather than iterating the vector, which would be a data
  // race: the vector reallocates on resize() and its elements are written on
  // every slot regrowth, both from the model's thread, while a monitoring poll
  // reads from another. Iterating it was undefined behavior, not an approximate
  // read. One relaxed fetch_add on the rare growth path costs less than the
  // loop it replaces, so the hot path is not merely unharmed but shorter, and
  // stagingBytes is now exact rather than exact-while-quiescent.
  std::atomic<uint64_t> stagingBytesTotal{0};

  // Output views retained by InvokeViews() until ReleaseOutputs(). Invoke()
  // and InvokeViews() both clear this first (stale-batch guard), so a caller
  // that forgets ReleaseOutputs leaks nothing and double-release is impossible.
  std::vector<BufferViewPtr> pendingOutputs;
};

IreeRuntime::IreeRuntime(std::unique_ptr<RuntimeState> state)
    : state_(std::move(state)) {
  // Incremented here rather than in Load() so a construction that never
  // happens — a throwing Load — is never counted.
  ++g_runtimes_live;
}

IreeRuntime::~IreeRuntime() { --g_runtimes_live; }

std::unique_ptr<IreeRuntime> IreeRuntime::Load(std::span<const std::byte> vmfb,
                                               std::string_view entryPoint,
                                               std::string_view driver) {
  return Load(vmfb, entryPoint, driver, std::span<const ParameterScope>{});
}

std::unique_ptr<IreeRuntime> IreeRuntime::Load(
    std::span<const std::byte> vmfb, std::string_view entryPoint,
    std::string_view driver, std::span<const ParameterScope> parameters) {
  return Load(vmfb, entryPoint, driver, parameters, StagingMode::kAllocatePerCall);
}

std::unique_ptr<IreeRuntime> IreeRuntime::Load(
    std::span<const std::byte> vmfb, std::string_view entryPoint,
    std::string_view driver, std::span<const ParameterScope> parameters,
    IreeRuntime::StagingMode staging) {
  auto state = std::make_unique<RuntimeState>();
  state->stagingMode = staging;
  state->vmfb.assign(vmfb.begin(), vmfb.end());
  state->entryPoint = std::string(entryPoint);

  iree_runtime_instance_options_t options;
  iree_runtime_instance_options_initialize(&options);
  iree_runtime_instance_options_use_all_available_drivers(&options);

  iree_runtime_instance_t* raw_instance = nullptr;
  IREE_CHECK_OR_THROW(iree_runtime_instance_create(
      &options, iree_allocator_system(), &raw_instance));
  state->instance.reset(raw_instance);

  iree_hal_device_t* raw_device = nullptr;
  // Copy into a std::string: std::string_view is not guaranteed null-terminated,
  // so we need a contiguous, sized buffer to hand IREE (same reasoning as
  // entryPoint above) — do not collapse this to iree_make_cstring_view(driver.data()).
  std::string driver_name(driver);
  IREE_CHECK_OR_THROW(iree_runtime_instance_try_create_default_device(
      state->instance.get(),
      iree_make_string_view(driver_name.data(), driver_name.size()),
      &raw_device));
  state->device.reset(raw_device);

  iree_runtime_session_options_t session_options;
  iree_runtime_session_options_initialize(&session_options);
  iree_runtime_session_t* raw_session = nullptr;
  IREE_CHECK_OR_THROW(iree_runtime_session_create_with_device(
      state->instance.get(), &session_options, state->device.get(),
      iree_allocator_system(), &raw_session));
  state->session.reset(raw_session);

  // Parameter archives, if any. This MUST happen before the bytecode module is
  // appended: import resolution runs when the bytecode module is registered
  // against the modules already in the context.
  if (!parameters.empty()) {
    std::vector<iree_io_parameter_provider_t*> raw_providers;
    raw_providers.reserve(parameters.size());
    // Locally scoped: Task 5 proved under ASan that the io_parameters module
    // retains each provider, so nothing here needs to outlive this block.
    std::vector<ParameterProviderPtr> scoped_providers;
    scoped_providers.reserve(parameters.size());

    for (const auto& param : parameters) {
      // 1. Open the archive. RANDOM_ACCESS is the mode used for the pread(2)s
      //    that later fetch parameter spans (hal/utils/fd_file.c:311) -- IREE
      //    only mmaps this file transiently, inside iree_io_parse_file_index
      //    below, to parse the index (irpa_parser.c:334), then unmaps it.
      //    Locally scoped: the parameter index retains the file handle for
      //    every FILE-backed entry it is given (verified by source reading,
      //    io/parameter_index.c:185, FILE branch). This retain is
      //    LOAD-BEARING, not defensive: the index stores only {handle,
      //    offset} per entry (irpa_parser.c:140-152), so no parameter bytes
      //    are resident yet -- they are pread() later, at io_parameters.load
      //    time, through this same retained handle (parameter_index_provider.c:147,
      //    fd_file.c:311). Task 7's FILE-backed differential dropped this
      //    local handle immediately after parse, against the FILE-backed
      //    scale_weights_zero.irpa fixture, and ASan reported no
      //    use-after-free on the SUBSEQUENT real read through the index's
      //    retained reference. That absent fault is the evidence: had the
      //    retain not fired, the later pread would have hit a freed handle
      //    and closed fd. (The golden output is the weaker half -- the
      //    fixture is zeroed, so a silently-zeroed buffer would look
      //    identical.) See iree_params_test.cpp's "golden vector: FILE-backed
      //    (zeroed) archive" case and docs/2026-07-25-irpa-spike-findings.md.
      //    Line numbers cite IREE at the pinned commit e4a3b0405d.
      iree_io_file_handle_t* raw_file = nullptr;
      IREE_CHECK_OR_THROW(iree_io_file_handle_open(
          IREE_IO_FILE_MODE_READ | IREE_IO_FILE_MODE_RANDOM_ACCESS,
          iree_make_string_view(param.path.data(), param.path.size()),
          iree_allocator_system(), &raw_file));
      FileHandlePtr scoped_file(raw_file);

      // 2. Parse it into an index. The path is passed too so the registry can
      //    pick a parser by extension. Locally scoped: the index provider
      //    retains the index (verified under ASan).
      iree_io_parameter_index_t* raw_index = nullptr;
      IREE_CHECK_OR_THROW(
          iree_io_parameter_index_create(iree_allocator_system(), &raw_index));
      ParameterIndexPtr scoped_index(raw_index);
      IREE_CHECK_OR_THROW(iree_io_parse_file_index(
          iree_make_string_view(param.path.data(), param.path.size()),
          scoped_file.get(), scoped_index.get(),
          iree_allocator_system()));
      // Explicit release right here, not left to scope-exit ordering (which
      // would actually run at the end of this loop iteration, after step 3,
      // since scoped_file is declared before scoped_index and destroys in
      // reverse order). This is the exact timing Task 7's FILE-backed
      // differential proved safe: the index has already taken its own
      // reference to the handle inside iree_io_parse_file_index (retain at
      // parameter_index.c:185), so nothing here needs to outlive this line.
      scoped_file.reset();

      // 3. Wrap in a provider bound to the caller's scope name.
      iree_io_parameter_provider_t* raw_provider = nullptr;
      IREE_CHECK_OR_THROW(iree_io_parameter_index_provider_create(
          iree_make_string_view(param.scope.data(), param.scope.size()),
          scoped_index.get(),
          IREE_IO_PARAMETER_INDEX_PROVIDER_DEFAULT_MAX_CONCURRENT_OPERATIONS,
          iree_allocator_system(), &raw_provider));
      scoped_providers.emplace_back(raw_provider);
      raw_providers.push_back(scoped_providers.back().get());
    }

    // 4. One module for all providers -- module_create takes an array, so
    //    multiple scopes need one module, not several. Locally scoped: the
    //    session/context retains the module (session.h:143, verified under
    //    ASan), so state does not need a paramsModule field either.
    iree_vm_module_t* raw_module = nullptr;
    IREE_CHECK_OR_THROW(iree_io_parameters_module_create(
        iree_runtime_instance_vm_instance(state->instance.get()),
        raw_providers.size(), raw_providers.data(), iree_allocator_system(),
        &raw_module));
    VmModulePtr scoped_module(raw_module);

    // 5. Append BEFORE the bytecode module.
    IREE_CHECK_OR_THROW(iree_runtime_session_append_module(
        state->session.get(), scoped_module.get()));
    // scoped_module releases here, before Load returns.
  }

  IREE_CHECK_OR_THROW(iree_runtime_session_append_bytecode_module_from_memory(
      state->session.get(),
      iree_make_const_byte_span(state->vmfb.data(), state->vmfb.size()),
      iree_allocator_null()));

  // Fail fast at load time if the entry point does not exist, rather than at
  // first Invoke. Cheap, and it makes the wrong-entry-point error path testable
  // without a successful load first.
  iree_vm_function_t function;
  IREE_CHECK_OR_THROW(iree_runtime_session_lookup_function(
      state->session.get(),
      iree_make_string_view(state->entryPoint.data(), state->entryPoint.size()),
      &function));

  return std::make_unique<IreeRuntime>(std::move(state));
}

std::span<const IreeRuntime::ImportOutcome> IreeRuntime::lastImportOutcomes() const {
  return state_->lastImportOutcomes;
}

RuntimeStats IreeRuntime::Stats() const {
  RuntimeStats out{};
  out.wrappedImports = state_->wrappedImports.load(std::memory_order_relaxed);
  out.stagedImports = state_->stagedImports.load(std::memory_order_relaxed);

  // Lock-free and exact: one relaxed load of the running sum ImportOrCopy
  // maintains. Reading state_->cachedStagingSizes directly would race the
  // model's thread — see RuntimeState::stagingBytesTotal.
  out.stagingBytes = state_->stagingBytesTotal.load(std::memory_order_relaxed);

  out.statisticsAvailable = StatisticsAvailable();
#if IREE_STATISTICS_ENABLE
  iree_hal_allocator_statistics_t stats;
  memset(&stats, 0, sizeof(stats));
  iree_hal_allocator_query_statistics(
      iree_hal_device_allocator(state_->device.get()), &stats);
  out.deviceBytesPeak = static_cast<uint64_t>(stats.device_bytes_peak);
  // freed can never exceed allocated, but clamp rather than underflow a
  // uint64_t if a future allocator implementation disagrees.
  out.deviceBytesLive =
      stats.device_bytes_allocated >= stats.device_bytes_freed
          ? static_cast<uint64_t>(stats.device_bytes_allocated -
                                  stats.device_bytes_freed)
          : 0;
#else
  out.deviceBytesPeak = 0;
  out.deviceBytesLive = 0;
#endif

  return out;
}

namespace {

// Attempts a zero-copy import of host memory; falls back to a staged copy when
// the allocator's preconditions (memory type / usage / alignment) are unmet.
// Returns the buffer view and reports which path was taken. `input_index` is
// the input's position in the call: the cached staging modes key their
// grow-only buffers by it (see the kCached* branch).
BufferViewPtr ImportOrCopy(iree_hal_device_t* device,
                           iree_hal_allocator_t* allocator,
                           const InputDesc& input, size_t input_index,
                           RuntimeState& state,
                           IreeRuntime::ImportOutcome* out_outcome) {
  std::vector<iree_hal_dim_t> shape(input.shape.begin(), input.shape.end());

  iree_hal_buffer_params_t params = {};
  params.type = IREE_HAL_MEMORY_TYPE_DEVICE_LOCAL | IREE_HAL_MEMORY_TYPE_HOST_VISIBLE;
  params.usage = IREE_HAL_BUFFER_USAGE_DEFAULT | IREE_HAL_BUFFER_USAGE_MAPPING;
  params.access = IREE_HAL_MEMORY_ACCESS_ALL;

  // 1. Try the import. const_cast is safe: params.access is read-only for our
  //    use and the buffer never escapes Invoke.
  iree_hal_external_buffer_t external = {};
  external.type = IREE_HAL_EXTERNAL_BUFFER_TYPE_HOST_ALLOCATION;
  external.flags = IREE_HAL_EXTERNAL_BUFFER_FLAG_NONE;
  external.size = static_cast<iree_device_size_t>(input.nbytes);
  external.handle.host_allocation.ptr = const_cast<void*>(input.data);

  iree_hal_buffer_t* imported = nullptr;
  iree_status_t import_status = iree_hal_allocator_import_buffer(
      allocator, params, &external,
      iree_hal_buffer_release_callback_null(), &imported);

  if (iree_status_is_ok(import_status)) {
    iree_status_ignore(import_status);
    iree_hal_buffer_view_t* view = nullptr;
    iree_status_t view_status = iree_hal_buffer_view_create(
        imported, shape.size(), shape.data(),
        static_cast<iree_hal_element_type_t>(input.elementType),
        IREE_HAL_ENCODING_TYPE_DENSE_ROW_MAJOR, iree_allocator_system(), &view);
    // The view retains the buffer; drop our own reference either way.
    iree_hal_buffer_release(imported);
    IREE_CHECK_OR_THROW(view_status);
    *out_outcome = IreeRuntime::ImportOutcome::kWrapped;
    return BufferViewPtr(view);
  }

  // 2. Import refused. Consume the status — this is exactly the path where a
  //    dropped iree_status_t would leak — and stage a copy instead.
  iree_status_free(import_status);

  iree_hal_buffer_view_t* view = nullptr;
  if (state.stagingMode == IreeRuntime::StagingMode::kAllocatePerCall) {
    // Historical behavior: one fresh HAL buffer per staged input per call
    // (allocate + transfer + view create — the "3-35x a bare memcpy" cost).
    IREE_CHECK_OR_THROW(iree_hal_buffer_view_allocate_buffer_copy(
        device, allocator, shape.size(), shape.data(),
        static_cast<iree_hal_element_type_t>(input.elementType),
        IREE_HAL_ENCODING_TYPE_DENSE_ROW_MAJOR, params,
        iree_make_const_byte_span(input.data, input.nbytes), &view));
  } else {
    // Cached staging: one grow-only buffer per input SLOT, reused across
    // calls, so the allocation is amortized away. Per-slot (not a single
    // shared buffer) is load-bearing: a call with several staged inputs must
    // not clobber an earlier input's view — slot i is only written after the
    // views for slots [0, i) are already pushed into the call. Between calls
    // the previous call's views are gone (synchronous invoke; released when
    // RunCall's input_views destructs), so rewriting a slot is safe.
    // Allocated with the same params as allocate_buffer_copy uses internally
    // (DEVICE_LOCAL | HOST_VISIBLE, DEFAULT | MAPPING), so kernel visibility
    // is unchanged — only the allocation is amortized.
    if (state.cachedStaging.size() <= input_index) {
      state.cachedStaging.resize(input_index + 1);
      state.cachedStagingSizes.resize(input_index + 1, 0);
    }
    auto& cached = state.cachedStaging[input_index];
    auto& cachedSize = state.cachedStagingSizes[input_index];
    if (cached == nullptr || cachedSize < input.nbytes) {
      // Retire the old buffer and its contribution to the running sum together,
      // BEFORE attempting the new allocation. IREE_CHECK_OR_THROW below can
      // leave this slot empty, and dropping the contribution first means the
      // sum then reports what is actually allocated (nothing for this slot)
      // rather than a buffer that has already been freed. It also keeps the
      // subtraction from ever underflowing: whenever cachedSize is non-zero the
      // sum includes it. Relaxed on both, like the import counters — a
      // standalone cumulative figure with no ordering constraint against the
      // reader.
      cached.reset();
      state.stagingBytesTotal.fetch_sub(static_cast<uint64_t>(cachedSize),
                                        std::memory_order_relaxed);
      cachedSize = 0;
      iree_hal_buffer_t* raw_buffer = nullptr;
      IREE_CHECK_OR_THROW(iree_hal_allocator_allocate_buffer(
          allocator, params, static_cast<iree_device_size_t>(input.nbytes),
          &raw_buffer));
      cached.reset(raw_buffer);
      cachedSize = static_cast<iree_device_size_t>(input.nbytes);
      state.stagingBytesTotal.fetch_add(static_cast<uint64_t>(cachedSize),
                                        std::memory_order_relaxed);
    }
    if (state.stagingMode == IreeRuntime::StagingMode::kCachedMapWrite) {
      IREE_CHECK_OR_THROW(iree_hal_buffer_map_write(
          cached.get(), 0, input.data,
          static_cast<iree_device_size_t>(input.nbytes)));
    } else {
      IREE_CHECK_OR_THROW(iree_hal_device_transfer_h2d(
          device, input.data, cached.get(), 0,
          static_cast<iree_device_size_t>(input.nbytes),
          IREE_HAL_TRANSFER_BUFFER_FLAG_DEFAULT, iree_infinite_timeout()));
    }
    IREE_CHECK_OR_THROW(iree_hal_buffer_view_create(
        cached.get(), shape.size(), shape.data(),
        static_cast<iree_hal_element_type_t>(input.elementType),
        IREE_HAL_ENCODING_TYPE_DENSE_ROW_MAJOR, iree_allocator_system(),
        &view));
  }
  *out_outcome = IreeRuntime::ImportOutcome::kStaged;
  return BufferViewPtr(view);
}

// Runs the full call: pushes each input through ImportOrCopy, invokes, pops
// every output view. The views retain the HAL buffers independently of the
// call, so the caller may materialize them after the call is torn down.
std::vector<BufferViewPtr> RunCall(RuntimeState& state,
                                   std::span<const InputDesc> inputs) {
  CallGuard call;
  IREE_CHECK_OR_THROW(iree_runtime_call_initialize_by_name(
      state.session.get(),
      iree_make_string_view(state.entryPoint.data(), state.entryPoint.size()),
      call.get()));
  call.mark_initialized();

  iree_hal_allocator_t* allocator =
      iree_runtime_session_device_allocator(state.session.get());

  // Every input view is owned here and released when this vector goes out of
  // scope — including on the throw paths below.
  std::vector<BufferViewPtr> input_views;
  input_views.reserve(inputs.size());
  state.lastImportOutcomes.assign(inputs.size(), IreeRuntime::ImportOutcome::kStaged);

  for (size_t i = 0; i < inputs.size(); ++i) {
    auto view = ImportOrCopy(state.device.get(), allocator, inputs[i], i, state,
                             &state.lastImportOutcomes[i]);
    // Count the outcome ImportOrCopy just recorded rather than re-deriving it.
    if (state.lastImportOutcomes[i] == IreeRuntime::ImportOutcome::kWrapped) {
      state.wrappedImports.fetch_add(1, std::memory_order_relaxed);
    } else {
      state.stagedImports.fetch_add(1, std::memory_order_relaxed);
    }
    IREE_CHECK_OR_THROW(iree_runtime_call_inputs_push_back_buffer_view(
        call.get(), view.get()));
    input_views.push_back(std::move(view));
  }

  IREE_CHECK_OR_THROW(iree_runtime_call_invoke(call.get(), /*flags=*/0));

  // Pop every output view. Nothing IREE-side may outlive the caller's
  // materialization/release (see InvokeViews); the views retain the buffers,
  // so popping before reading is safe.
  std::vector<BufferViewPtr> outputs;
  for (;;) {
    iree_hal_buffer_view_t* raw_view = nullptr;
    iree_status_t status = iree_runtime_call_outputs_pop_front_buffer_view(
        call.get(), &raw_view);
    if (!iree_status_is_ok(status)) {
      // Exhausted the output list: consume the status and stop.
      iree_status_free(status);
      break;
    }
    iree_status_ignore(status);
    outputs.emplace_back(raw_view);
  }

  return outputs;
  // input_views destructs here: every imported/staged buffer released before
  // return, so no imported input can outlive the caller's pinned region.
}

}  // namespace

std::vector<OutputBuffer> IreeRuntime::Invoke(std::span<const InputDesc> inputs) {
  // Stale-batch guard: a prior InvokeViews without ReleaseOutputs cannot leak
  // or double-release — the next call clears the pending views first.
  state_->pendingOutputs.clear();
  auto views = RunCall(*state_, inputs);

  // Copy out. The owning Invoke materializes every output before returning —
  // nothing IREE-side outlives the caller's owning buffers.
  std::vector<OutputBuffer> outputs;
  for (auto& view : views) {
    OutputBuffer out;
    out.elementType =
        static_cast<int32_t>(iree_hal_buffer_view_element_type(view.get()));
    const iree_host_size_t rank = iree_hal_buffer_view_shape_rank(view.get());
    out.shape.reserve(rank);
    for (iree_host_size_t d = 0; d < rank; ++d) {
      out.shape.push_back(
          static_cast<int64_t>(iree_hal_buffer_view_shape_dim(view.get(), d)));
    }

    const iree_device_size_t nbytes = iree_hal_buffer_view_byte_length(view.get());
    out.data.resize(static_cast<size_t>(nbytes));
    // CPU host memory is coherent, so this is a straight copy. A non-CPU
    // backend would need an invalidate-range before reading.
    IREE_CHECK_OR_THROW(iree_hal_buffer_map_read(
        iree_hal_buffer_view_buffer(view.get()), 0, out.data.data(), nbytes));

    outputs.push_back(std::move(out));
  }

  return outputs;
}

std::vector<OutputLayout> IreeRuntime::InvokeViews(std::span<const InputDesc> inputs) {
  state_->pendingOutputs.clear();  // stale-batch guard, same as Invoke
  state_->pendingOutputs = RunCall(*state_, inputs);

  std::vector<OutputLayout> layouts;
  layouts.reserve(state_->pendingOutputs.size());
  for (const auto& view : state_->pendingOutputs) {
    OutputLayout layout;
    layout.elementType =
        static_cast<int32_t>(iree_hal_buffer_view_element_type(view.get()));
    const iree_host_size_t rank = iree_hal_buffer_view_shape_rank(view.get());
    layout.shape.reserve(rank);
    for (iree_host_size_t d = 0; d < rank; ++d) {
      layout.shape.push_back(
          static_cast<int64_t>(iree_hal_buffer_view_shape_dim(view.get(), d)));
    }
    layout.nbytes =
        static_cast<uint64_t>(iree_hal_buffer_view_byte_length(view.get()));
    layouts.push_back(std::move(layout));
  }
  return layouts;
}

void IreeRuntime::ReadOutput(size_t index, void* dst) {
  if (index >= state_->pendingOutputs.size()) {
    throw std::out_of_range("output index out of range");
  }
  auto& view = state_->pendingOutputs[index];
  const iree_device_size_t nbytes = iree_hal_buffer_view_byte_length(view.get());
  IREE_CHECK_OR_THROW(iree_hal_buffer_map_read(
      iree_hal_buffer_view_buffer(view.get()), 0, dst, nbytes));
}

void IreeRuntime::ReleaseOutputs() {
  state_->pendingOutputs.clear();
}

}  // namespace measly::iree
