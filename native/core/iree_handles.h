#ifndef MEASLY_IREE_HANDLES_H
#define MEASLY_IREE_HANDLES_H

#include <memory>
#include "iree/io/file_handle.h"
#include "iree/io/parameter_index.h"
#include "iree/io/parameter_provider.h"
#include "iree/runtime/api.h"

namespace measly::iree {

// One owning wrapper per refcounted IREE handle type. Construction acquires,
// destruction releases, exactly once. No raw handle escapes the facade.
struct InstanceDeleter {
  void operator()(iree_runtime_instance_t* p) const { iree_runtime_instance_release(p); }
};
struct DeviceDeleter {
  void operator()(iree_hal_device_t* p) const { iree_hal_device_release(p); }
};
struct SessionDeleter {
  void operator()(iree_runtime_session_t* p) const { iree_runtime_session_release(p); }
};
struct BufferViewDeleter {
  void operator()(iree_hal_buffer_view_t* p) const { iree_hal_buffer_view_release(p); }
};
struct BufferDeleter {
  void operator()(iree_hal_buffer_t* p) const { iree_hal_buffer_release(p); }
};
struct FileHandleDeleter {
  void operator()(iree_io_file_handle_t* p) const { iree_io_file_handle_release(p); }
};
struct ParameterIndexDeleter {
  void operator()(iree_io_parameter_index_t* p) const { iree_io_parameter_index_release(p); }
};
struct ParameterProviderDeleter {
  void operator()(iree_io_parameter_provider_t* p) const {
    iree_io_parameter_provider_release(p);
  }
};
struct VmModuleDeleter {
  void operator()(iree_vm_module_t* p) const { iree_vm_module_release(p); }
};

using InstancePtr = std::unique_ptr<iree_runtime_instance_t, InstanceDeleter>;
using DevicePtr = std::unique_ptr<iree_hal_device_t, DeviceDeleter>;
using SessionPtr = std::unique_ptr<iree_runtime_session_t, SessionDeleter>;
using BufferViewPtr = std::unique_ptr<iree_hal_buffer_view_t, BufferViewDeleter>;
using BufferPtr = std::unique_ptr<iree_hal_buffer_t, BufferDeleter>;
using FileHandlePtr = std::unique_ptr<iree_io_file_handle_t, FileHandleDeleter>;
using ParameterIndexPtr = std::unique_ptr<iree_io_parameter_index_t, ParameterIndexDeleter>;
using ParameterProviderPtr =
    std::unique_ptr<iree_io_parameter_provider_t, ParameterProviderDeleter>;
using VmModulePtr = std::unique_ptr<iree_vm_module_t, VmModuleDeleter>;

// iree_runtime_call_t is a VALUE type, not a refcounted handle: it is
// initialized in place and torn down with deinitialize (there is no
// iree_runtime_call_release). So it gets a scope guard, not a unique_ptr.
class CallGuard {
 public:
  CallGuard() = default;
  ~CallGuard() {
    if (initialized_) iree_runtime_call_deinitialize(&call_);
  }
  CallGuard(const CallGuard&) = delete;
  CallGuard& operator=(const CallGuard&) = delete;

  iree_runtime_call_t* get() { return &call_; }
  void mark_initialized() { initialized_ = true; }
  bool initialized() const { return initialized_; }

 private:
  iree_runtime_call_t call_{};
  bool initialized_ = false;
};

}  // namespace measly::iree
#endif  // MEASLY_IREE_HANDLES_H
