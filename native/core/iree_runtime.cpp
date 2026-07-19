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
                                               std::string_view entryPoint) {
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
  IREE_CHECK_OR_THROW(iree_runtime_instance_try_create_default_device(
      state->instance.get(), iree_make_cstring_view("local-sync"), &raw_device));
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

}  // namespace measly::iree
