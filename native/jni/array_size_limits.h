#pragma once
#include <cstddef>
#include <cstdint>

// The jsize (jint) boundary, in one place. An IREE output larger than INT32_MAX bytes
// cannot be marshalled through ByteBuffer.allocateDirect (which takes an int), and a
// shape longer than INT32_MAX dims cannot be a jlongArray. Split out of the shim so the
// Catch2 units can pin the boundary without a JNIEnv -- free of <jni.h> for that reason,
// which is also why the limit is spelled INT32_MAX rather than the jsize type itself.
//
// What this guards against: IREE's own sizes (nbytes, shape dims) are not bounded to
// fit a signed 32-bit value, but every JNI array length is a jint -- a signed 32-bit
// value. static_cast<jsize>(n) on a size_t/uint64_t larger than INT32_MAX silently
// wraps rather than failing, which for an output size becomes a too-small
// allocateDirect() call followed by a map_read that overflows it (see the call site in
// iree_djl_jni.cpp, issue #15) -- a heap write overflow, not a bounds-checked error. The
// two callers of exceedsJniArrayLimit() check the real (unwrapped) size before any such
// cast and throw a Java RuntimeException instead of letting the cast happen.
namespace measly::iree {

inline constexpr size_t kJniArrayMaxBytes = static_cast<size_t>(INT32_MAX);

// True once n can no longer be cast to jsize (jint) without wrapping/overflowing.
inline bool exceedsJniArrayLimit(size_t n) { return n > kJniArrayMaxBytes; }

}  // namespace measly::iree
