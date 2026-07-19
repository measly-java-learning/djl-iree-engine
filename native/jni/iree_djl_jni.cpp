// Thin marshalling layer only. All lifetime logic lives in the facade; this
// file translates types and routes errors. The opaque jlong is a pointer to a
// heap-allocated IreeRuntime.
#include <jni.h>

#include <cstring>
#include <memory>
#include <new>
#include <span>
#include <string>
#include <vector>

#include "core/iree_runtime.h"

using measly::iree::InputDesc;
using measly::iree::IreeRuntime;

namespace {

jclass g_runtime_exception = nullptr;

IreeRuntime* AsRuntime(jlong handle) {
  return reinterpret_cast<IreeRuntime*>(static_cast<intptr_t>(handle));
}

// The single native->jthrow translation point, fed by whatever
// IREE_CHECK_OR_THROW raised. Keeps JNI exception logic out of the facade and
// out of the sanitizer harness.
//
// If the cached class is unavailable (NewGlobalRef failed in JNI_OnLoad, or
// JNI_OnLoad never ran), fall back to a fresh, uncached FindClass so a
// facade failure is never silently swallowed.
void ThrowJava(JNIEnv* env, const char* message) {
  if (g_runtime_exception != nullptr) {
    env->ThrowNew(g_runtime_exception, message);
    return;
  }
  jclass fallback = env->FindClass("java/lang/RuntimeException");
  if (fallback != nullptr) {
    env->ThrowNew(fallback, message);
    env->DeleteLocalRef(fallback);
  }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK) {
    return JNI_ERR;
  }
  jclass local = env->FindClass("java/lang/RuntimeException");
  if (local == nullptr) return JNI_ERR;
  g_runtime_exception = static_cast<jclass>(env->NewGlobalRef(local));
  env->DeleteLocalRef(local);
  return JNI_VERSION_1_8;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK) return;
  if (g_runtime_exception != nullptr) {
    env->DeleteGlobalRef(g_runtime_exception);
    g_runtime_exception = nullptr;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_measly_iree_jni_IreeNative_load(JNIEnv* env, jclass,
                                         jbyteArray vmfb, jstring entryPoint) {
  const jsize length = env->GetArrayLength(vmfb);
  std::vector<std::byte> bytes(static_cast<size_t>(length));
  env->GetByteArrayRegion(vmfb, 0, length,
                          reinterpret_cast<jbyte*>(bytes.data()));

  const char* entry = env->GetStringUTFChars(entryPoint, nullptr);
  if (entry == nullptr) {
    ThrowJava(env, "entryPoint was null");
    return 0;
  }
  std::string entry_copy(entry);
  env->ReleaseStringUTFChars(entryPoint, entry);

  try {
    auto runtime = IreeRuntime::Load(bytes, entry_copy);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(runtime.release()));
  } catch (const std::exception& e) {
    ThrowJava(env, e.what());
    return 0;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_org_measly_iree_jni_IreeNative_close(JNIEnv*, jclass, jlong handle) {
  delete AsRuntime(handle);
}

// Inputs and outputs both cross as direct ByteBuffers. Input addresses are
// borrowed for exactly the duration of this call, which is what makes the
// facade's import-or-copy safe: the Java region stays pinned across the
// boundary for precisely that window.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_measly_iree_jni_IreeNative_invoke(JNIEnv* env, jclass, jlong handle,
                                           jobjectArray inputBuffers,
                                           jobjectArray inputShapes,
                                           jintArray inputTypes) {
  IreeRuntime* runtime = AsRuntime(handle);
  if (runtime == nullptr) {
    ThrowJava(env, "invoke on a closed handle");
    return nullptr;
  }

  const jsize count = env->GetArrayLength(inputBuffers);
  std::vector<InputDesc> inputs(static_cast<size_t>(count));
  std::vector<std::vector<int64_t>> shapes(static_cast<size_t>(count));

  jint* types = env->GetIntArrayElements(inputTypes, nullptr);
  if (types == nullptr) {
    ThrowJava(env, "failed to acquire input element types (out of memory?)");
    return nullptr;
  }

  for (jsize i = 0; i < count; ++i) {
    jobject buffer = env->GetObjectArrayElement(inputBuffers, i);
    void* address = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity < 0) {
      env->ReleaseIntArrayElements(inputTypes, types, JNI_ABORT);
      ThrowJava(env, "input must be a direct ByteBuffer");
      return nullptr;
    }

    jlongArray shapeArray =
        static_cast<jlongArray>(env->GetObjectArrayElement(inputShapes, i));
    const jsize rank = env->GetArrayLength(shapeArray);
    shapes[i].resize(static_cast<size_t>(rank));
    env->GetLongArrayRegion(shapeArray, 0, rank,
                            reinterpret_cast<jlong*>(shapes[i].data()));

    inputs[i].data = address;
    inputs[i].nbytes = static_cast<size_t>(capacity);
    inputs[i].shape = shapes[i];
    inputs[i].elementType = static_cast<int32_t>(types[i]);
  }
  env->ReleaseIntArrayElements(inputTypes, types, JNI_ABORT);

  std::vector<measly::iree::OutputBuffer> outputs;
  try {
    outputs = runtime->Invoke(inputs);
  } catch (const std::exception& e) {
    ThrowJava(env, e.what());
    return nullptr;
  }

  jclass tensor_class = env->FindClass("org/measly/iree/jni/IreeTensor");
  if (tensor_class == nullptr) return nullptr;
  jmethodID ctor = env->GetMethodID(tensor_class, "<init>",
                                    "(Ljava/nio/ByteBuffer;[JI)V");
  if (ctor == nullptr) return nullptr;

  // Loop-invariant: java/nio/ByteBuffer's class and its allocateDirect
  // methodID don't change per output, so look them up once here rather than
  // inside the loop (avoids per-iteration local-ref accumulation and
  // redundant lookups).
  jclass bb = env->FindClass("java/nio/ByteBuffer");
  if (bb == nullptr) return nullptr;
  jmethodID allocate = env->GetStaticMethodID(bb, "allocateDirect",
                                              "(I)Ljava/nio/ByteBuffer;");
  if (allocate == nullptr) return nullptr;

  jobjectArray result =
      env->NewObjectArray(static_cast<jsize>(outputs.size()), tensor_class, nullptr);

  for (size_t i = 0; i < outputs.size(); ++i) {
    auto& out = outputs[i];
    // Allocate a direct buffer the JVM owns and copy into it. The facade's
    // OutputBuffer dies with this function; nothing native outlives the call.
    jobject owned = env->CallStaticObjectMethod(
        bb, allocate, static_cast<jint>(out.data.size()));
    if (owned == nullptr || env->ExceptionCheck()) {
      // allocateDirect failed (e.g. OutOfMemoryError). A pending exception
      // will propagate to Java on its own once we return; calling
      // GetDirectBufferAddress/memcpy on a null result with an exception
      // pending is undefined behavior, so bail out cleanly without also
      // throwing over it.
      return nullptr;
    }
    std::memcpy(env->GetDirectBufferAddress(owned), out.data.data(),
                out.data.size());

    jlongArray shape = env->NewLongArray(static_cast<jsize>(out.shape.size()));
    env->SetLongArrayRegion(shape, 0, static_cast<jsize>(out.shape.size()),
                            reinterpret_cast<const jlong*>(out.shape.data()));

    jobject tensor = env->NewObject(tensor_class, ctor, owned, shape,
                                    static_cast<jint>(out.elementType));
    env->SetObjectArrayElement(result, static_cast<jsize>(i), tensor);
    env->DeleteLocalRef(tensor);
    env->DeleteLocalRef(shape);
    env->DeleteLocalRef(owned);
  }
  return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_org_measly_iree_jni_IreeNative_lastImportOutcomes(JNIEnv* env, jclass,
                                                       jlong handle) {
  IreeRuntime* runtime = AsRuntime(handle);
  if (runtime == nullptr) {
    ThrowJava(env, "lastImportOutcomes on a closed handle");
    return nullptr;
  }
  auto outcomes = runtime->lastImportOutcomes();
  jintArray result = env->NewIntArray(static_cast<jsize>(outcomes.size()));
  std::vector<jint> values(outcomes.size());
  for (size_t i = 0; i < outcomes.size(); ++i) {
    values[i] = outcomes[i] == IreeRuntime::ImportOutcome::kWrapped ? 1 : 0;
  }
  env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()),
                         values.data());
  return result;
}
