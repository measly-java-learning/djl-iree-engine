#ifndef MEASLY_IREE_STATUS_H
#define MEASLY_IREE_STATUS_H

#include <stdexcept>
#include <string>
#include "iree/base/api.h"

// The facade's sole IREE-status-to-C++-exception boundary. Everything in
// native/core/ that calls into IREE gets an iree_status_t back; this header
// is where that C-style, heap-allocated-on-failure result type is converted
// into the C++ exceptions the rest of the JNI-to-IREE path (native/jni/) is
// written to expect. No other file in the facade should call
// iree_status_free/iree_status_ignore directly on a status obtained this way.
namespace measly::iree {

// Consumes an iree_status_t exactly once. A non-OK status is a HEAP OBJECT:
// dropping it without free leaks it along with its message payload. Error
// paths are the least hand-tested code, which is why every status in this
// codebase funnels through here and nowhere else.
//
// Ownership: the caller passes status BY VALUE and gives up ownership on the
// call -- after this function returns, the caller must not touch status
// again, whichever path it took. On success the OK status is ignored (OK
// statuses carry no heap payload, so iree_status_ignore is a no-op, not a
// leak). On failure this function converts the IREE status into the
// codebase's own error representation -- a thrown std::runtime_error -- and
// the iree_status_t itself is freed before the throw, so nothing IREE-owned
// survives past this call. An iree_status_t that is neither ignored nor freed
// (e.g. an error checked with iree_status_is_ok and then simply discarded)
// leaks its heap payload; that is why every status in this codebase is
// required to flow through here rather than being inspected ad hoc.
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

// Wraps expr (any call returning iree_status_t) and immediately hands the
// result to ConsumeStatusOrThrow, so the status can never be accidentally
// left unconsumed between the call and the check. #expr captures the source
// text for the exception message, not just the failure site.
#define IREE_CHECK_OR_THROW(expr) \
  ::measly::iree::ConsumeStatusOrThrow((expr), #expr)

#endif  // MEASLY_IREE_STATUS_H
