#include "core/iree_runtime.h"

#include <cstring>
#include "core/iree_handles.h"
#include "core/iree_status.h"
#include "iree/hal/buffer_view_util.h"
#include "iree/runtime/api.h"

namespace measly::iree {

struct RuntimeState {
  // Owns a copy of the flatbuffer. append_bytecode_module_from_memory with a
  // null allocator does NOT copy, so these bytes must outlive the session.
  // Declared first so it is destroyed last.
  std::vector<std::byte> vmfb;
  std::string entryPoint;
  InstancePtr instance;
  DevicePtr device;
  SessionPtr session;
  std::vector<IreeRuntime::ImportOutcome> lastImportOutcomes;
};

IreeRuntime::IreeRuntime(std::unique_ptr<RuntimeState> state)
    : state_(std::move(state)) {}
IreeRuntime::~IreeRuntime() = default;

std::unique_ptr<IreeRuntime> IreeRuntime::Load(std::span<const std::byte> vmfb,
                                               std::string_view entryPoint,
                                               std::string_view driver) {
  auto state = std::make_unique<RuntimeState>();
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

namespace {

// Attempts a zero-copy import of host memory; falls back to a staged copy when
// the allocator's preconditions (memory type / usage / alignment) are unmet.
// Returns the buffer view and reports which path was taken.
BufferViewPtr ImportOrCopy(iree_hal_device_t* device,
                           iree_hal_allocator_t* allocator,
                           const InputDesc& input,
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
  IREE_CHECK_OR_THROW(iree_hal_buffer_view_allocate_buffer_copy(
      device, allocator, shape.size(), shape.data(),
      static_cast<iree_hal_element_type_t>(input.elementType),
      IREE_HAL_ENCODING_TYPE_DENSE_ROW_MAJOR, params,
      iree_make_const_byte_span(input.data, input.nbytes), &view));
  *out_outcome = IreeRuntime::ImportOutcome::kStaged;
  return BufferViewPtr(view);
}

}  // namespace

std::vector<OutputBuffer> IreeRuntime::Invoke(std::span<const InputDesc> inputs) {
  CallGuard call;
  IREE_CHECK_OR_THROW(iree_runtime_call_initialize_by_name(
      state_->session.get(),
      iree_make_string_view(state_->entryPoint.data(), state_->entryPoint.size()),
      call.get()));
  call.mark_initialized();

  iree_hal_allocator_t* allocator =
      iree_runtime_session_device_allocator(state_->session.get());

  // Every input view is owned here and released when this vector goes out of
  // scope — including on the throw paths below.
  std::vector<BufferViewPtr> input_views;
  input_views.reserve(inputs.size());
  state_->lastImportOutcomes.assign(inputs.size(), ImportOutcome::kStaged);

  for (size_t i = 0; i < inputs.size(); ++i) {
    auto view = ImportOrCopy(state_->device.get(), allocator, inputs[i],
                             &state_->lastImportOutcomes[i]);
    IREE_CHECK_OR_THROW(iree_runtime_call_inputs_push_back_buffer_view(
        call.get(), view.get()));
    input_views.push_back(std::move(view));
  }

  IREE_CHECK_OR_THROW(iree_runtime_call_invoke(call.get(), /*flags=*/0));

  // Copy out. Nothing IREE-side may outlive this function, so each output is
  // materialised into an owning vector and its view released immediately.
  std::vector<OutputBuffer> outputs;
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
    BufferViewPtr view(raw_view);

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
  // input_views destructs here: every imported/staged buffer released before
  // return, so no imported input can outlive the caller's pinned region.
}

}  // namespace measly::iree
