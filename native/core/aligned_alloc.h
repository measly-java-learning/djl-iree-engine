#ifndef MEASLY_IREE_ALIGNED_ALLOC_H
#define MEASLY_IREE_ALIGNED_ALLOC_H

// The single source of the host-buffer alignment contract and the future
// W6 extraction seam (see docs/2026-08-04-borrowed-host-buffers-findings.md
// §7). Any allocation that must import zero-copy into IREE goes through
// AlignedAlloc/AlignedFree.
//
// Contract: IREE_HAL_HEAP_BUFFER_ALIGNMENT = 64
// (iree/base/config.h:238-245 in the pinned iree-runtime-dist): "Power of two
// byte alignment required on all host heap buffers. Executables are compiled
// with alignment expectations and the runtime alignment must be greater than
// or equal to the alignment set in the compiler. External buffers wrapped by
// HAL buffers must meet this alignment requirement."
//
// C++17 aligned new/delete (not posix_memalign) so this compiles under MSVC
// too — matching the existing pattern at native/test/iree_runtime_test.cpp
// and native/harness/iree_leak_harness.cpp.

#include <atomic>
#include <cstddef>
#include <new>

namespace measly::iree {

inline constexpr size_t kBufferAlignment = 64;

// Live aligned allocations, for leak probes (JNI-created aligned buffers are
// not counted against -XX:MaxDirectMemorySize, so the counter — not direct
// memory pressure — is the leak signal).
inline std::atomic<int64_t> g_aligned_live{0};

// Ownership/lifetime: returns a pointer the caller owns and must eventually
// pass to AlignedFree — never to free() or plain operator delete, since it
// was obtained from the aligned overload of operator new and a mismatched
// deallocator is undefined behavior. Between AlignedAlloc and AlignedFree the
// caller is free to write into the buffer (that is the whole point: this is
// how the engine hands the caller a buffer that will import zero-copy).
inline void* AlignedAlloc(size_t n) {
  // If operator new throws (std::bad_alloc), the counter is not incremented:
  // the caller sees the exception and there is nothing live to count.
  void* p = ::operator new(n, std::align_val_t{kBufferAlignment});
  ++g_aligned_live;
  return p;
}

// Ownership/lifetime: consumes exactly one pointer previously returned by
// AlignedAlloc; p must not be used again after this call. Must be paired with
// AlignedAlloc, never called on memory from a different allocator.
inline void AlignedFree(void* p) {
  if (p == nullptr) return;
  // Alignment must match the allocation exactly — kBufferAlignment is fixed,
  // so the matching delete is unambiguous (no alignment parameter anywhere).
  ::operator delete(p, std::align_val_t{kBufferAlignment});
  --g_aligned_live;
}

inline int64_t AlignedLiveCount() {
  return g_aligned_live.load(std::memory_order_relaxed);
}

}  // namespace measly::iree

#endif  // MEASLY_IREE_ALIGNED_ALLOC_H
