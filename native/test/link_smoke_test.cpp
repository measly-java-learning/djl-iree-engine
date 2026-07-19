// Proves the IREE runtime links and the local-sync driver is present.
// Deliberately has no dependency on our facade — this is a link test.
#include <cstdio>
#include "iree/runtime/api.h"

int main() {
  iree_runtime_instance_options_t options;
  iree_runtime_instance_options_initialize(&options);
  iree_runtime_instance_options_use_all_available_drivers(&options);

  iree_runtime_instance_t* instance = nullptr;
  iree_status_t status = iree_runtime_instance_create(
      &options, iree_allocator_system(), &instance);
  if (!iree_status_is_ok(status)) {
    iree_status_fprint(stderr, status);
    iree_status_free(status);
    return 1;
  }

  iree_hal_device_t* device = nullptr;
  status = iree_runtime_instance_try_create_default_device(
      instance, iree_make_cstring_view("local-sync"), &device);
  if (!iree_status_is_ok(status)) {
    iree_status_fprint(stderr, status);
    iree_status_free(status);
    iree_runtime_instance_release(instance);
    return 2;
  }

  std::printf("ok: local-sync device created\n");
  iree_hal_device_release(device);
  iree_runtime_instance_release(instance);
  return 0;
}
