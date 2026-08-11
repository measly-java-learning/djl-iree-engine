#pragma once
#include <cstddef>
#include <cstdint>

// The jsize (jint) boundary, in one place. An IREE output larger than INT32_MAX bytes
// cannot be marshalled through ByteBuffer.allocateDirect (which takes an int), and a
// shape longer than INT32_MAX dims cannot be a jlongArray. Split out of the shim so the
// Catch2 units can pin the boundary without a JNIEnv -- free of <jni.h> for that reason,
// which is also why the limit is spelled INT32_MAX rather than the jsize type itself.
namespace measly::iree {

inline constexpr size_t kJniArrayMaxBytes = static_cast<size_t>(INT32_MAX);

inline bool exceedsJniArrayLimit(size_t n) { return n > kJniArrayMaxBytes; }

}  // namespace measly::iree
