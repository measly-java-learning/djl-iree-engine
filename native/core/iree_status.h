#ifndef MEASLY_IREE_STATUS_H
#define MEASLY_IREE_STATUS_H

#include <stdexcept>
#include <string>
#include "iree/base/api.h"

namespace measly::iree {

// Consumes an iree_status_t exactly once. A non-OK status is a HEAP OBJECT:
// dropping it without free leaks it along with its message payload. Error
// paths are the least hand-tested code, which is why every status in this
// codebase funnels through here and nowhere else.
inline void ConsumeStatusOrThrow(iree_status_t status, const char* expr) {
  if (iree_status_is_ok(status)) {
    iree_status_ignore(status);
    return;
  }
  // Render the message BEFORE freeing — the buffer is owned by the status.
  // iree_status_to_string takes the allocator by pointer (not value, unlike
  // iree_allocator_free), so it must be materialized into a named local:
  // `&iree_allocator_system()` does not compile (can't take the address of
  // an rvalue).
  std::string message;
  iree_host_size_t length = 0;
  char* buffer = nullptr;
  iree_allocator_t allocator = iree_allocator_system();
  if (iree_status_to_string(status, &allocator, &buffer, &length)) {
    message.assign(buffer, length);
    iree_allocator_free(allocator, buffer);
  } else {
    message = "unknown IREE error";
  }
  iree_status_free(status);
  throw std::runtime_error(std::string(expr) + ": " + message);
}

}  // namespace measly::iree

#define IREE_CHECK_OR_THROW(expr) \
  ::measly::iree::ConsumeStatusOrThrow((expr), #expr)

#endif  // MEASLY_IREE_STATUS_H
