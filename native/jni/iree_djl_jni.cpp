// Thin marshalling layer only. All lifetime logic lives in the facade; this
// file translates types and routes errors. The opaque jlong is a pointer to a
// heap-allocated IreeRuntime.
//
// This is the only translation unit under native/ that includes <jni.h> and
// sees JNI types (JNIEnv, jobject, jarray, ...); native/core/ works entirely
// in std/measly types and never touches the JNI API. This file's job is
// marshalling only -- it converts JVM representations to and from the types
// declared in core/iree_runtime.h (see that header for the facade's own
// contracts, which this file relies on rather than restates) and turns a
// facade std::runtime_error into a Java exception. It performs no IREE work
// of its own. Every exported JNIEXPORT symbol's mangled name binds it to a
// specific `native` method declared in org.measly.iree.jni.IreeNative; the
// two files describe one contract from two sides and must be read together --
// a name/signature mismatch between them fails at class-load time
// (UnsatisfiedLinkError), not at compile time.
#include <jni.h>

#include <memory>
#include <new>
#include <span>
#include <string>
#include <vector>

#include "array_size_limits.h"
#include "core/aligned_alloc.h"
#include "core/iree_runtime.h"

using measly::iree::AlignedAlloc;
using measly::iree::AlignedFree;
using measly::iree::AlignedLiveCount;
using measly::iree::InputDesc;
using measly::iree::IreeRuntime;
using measly::iree::ParameterScope;

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

// Marshal a Java String[] to std::vector<std::string>; a null array is empty.
// GetObjectArrayElement returns a FRESH local reference per element and the
// default local-ref table is small, so DeleteLocalRef as we go or a
// many-scope manifest overflows it — the one non-obvious hazard in this file.
std::vector<std::string> ToStringVector(JNIEnv* env, jobjectArray array) {
  std::vector<std::string> out;
  if (array == nullptr) return out;
  const jsize count = env->GetArrayLength(array);
  out.reserve(static_cast<size_t>(count));
  for (jsize i = 0; i < count; ++i) {
    jstring str = static_cast<jstring>(env->GetObjectArrayElement(array, i));
    if (str == nullptr) {
      ThrowJava(env, "parameter scope/path arrays must not contain null");
      return {};
    }
    const char* utf = env->GetStringUTFChars(str, nullptr);
    if (utf == nullptr) return {};  // OOM pending; caller checks ExceptionCheck
    out.emplace_back(utf);
    env->ReleaseStringUTFChars(str, utf);
    env->DeleteLocalRef(str);
  }
  return out;
}

}  // namespace

// Called once when System.loadLibrary loads this library, before any
// IreeNative method can run. Caches the RuntimeException class as a global
// reference so ThrowJava() need not FindClass on every failure: FindClass
// resolves relative to the caller's classloader, which is not reliably
// reachable from deep in an arbitrary JNI callstack, so doing it once here --
// in the classloader context JNI_OnLoad itself runs in -- is the only fully
// reliable place. Returning JNI_ERR aborts the load.
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

// Called at most once, only if the classloader that loaded this library is
// itself ever unloaded (uncommon for the loader that owns a native library in
// practice). Releases the cached global reference; nothing else in this file
// holds process-lifetime native state that needs symmetric teardown here.
extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK) return;
  if (g_runtime_exception != nullptr) {
    env->DeleteGlobalRef(g_runtime_exception);
    g_runtime_exception = nullptr;
  }
}

// Preconditions (see IreeNative.load's javadoc): vmfb, entryPoint, and device
// non-null; paramScopes/paramPaths non-null, equal length, with no null
// elements. Every one of these is checked here rather than assumed: this is
// the entry point from Java, so a caller bug must surface as a Java
// exception, never a native crash.
//
// Allocation/ownership: vmfb's bytes are copied into a local std::vector so
// GetByteArrayRegion has somewhere to write, then handed to
// IreeRuntime::Load() as a std::span (Load() copies again internally -- see
// its header comment -- so this local copy exists only to bridge the JNI
// array API, not to hand off ownership). On success, Load() heap-allocates a
// RuntimeState; that allocation's ownership leaves this function as the
// returned jlong and returns on close(). On any failure path -- here or
// inside Load() -- nothing is leaked: every local (vectors, the two
// std::strings, `parameters`) is stack-owned and unwinds normally, and Load()
// itself throws before transferring ownership of anything to the caller.
//
// Local-reference discipline: entryPoint's and device's UTF chars are
// released via ReleaseStringUTFChars immediately after being copied into a
// std::string, before any later precondition check that could return early --
// so no JNI resource here is left depending on reaching the function's end.
// ToStringVector() explicitly deletes each element's local reference inside
// its own loop (see its comment) rather than accumulating
// paramScopes.length + paramPaths.length locals in the default-size local
// reference table.
//
// Error path: an unknown/unavailable driver or a malformed vmfb surfaces as
// IreeRuntime::Load() throwing std::runtime_error, caught here and turned
// into a Java RuntimeException via ThrowJava(); 0 is returned alongside it.
// No partial handle ever escapes a failed load -- runtime.release() only runs
// after Load() has already succeeded.
extern "C" JNIEXPORT jlong JNICALL
Java_org_measly_iree_jni_IreeNative_load(JNIEnv* env, jclass,
                                         jbyteArray vmfb, jstring entryPoint,
                                         jstring device, jobjectArray paramScopes,
                                         jobjectArray paramPaths) {
  if (vmfb == nullptr) {
    ThrowJava(env, "vmfb was null");
    return 0;
  }
  const jsize length = env->GetArrayLength(vmfb);
  std::vector<std::byte> bytes(static_cast<size_t>(length));
  env->GetByteArrayRegion(vmfb, 0, length,
                          reinterpret_cast<jbyte*>(bytes.data()));

  if (entryPoint == nullptr) {
    ThrowJava(env, "entryPoint was null");
    return 0;
  }
  const char* entry = env->GetStringUTFChars(entryPoint, nullptr);
  if (entry == nullptr) {
    ThrowJava(env, "entryPoint was null");
    return 0;
  }
  std::string entry_copy(entry);
  env->ReleaseStringUTFChars(entryPoint, entry);

  if (device == nullptr) {
    ThrowJava(env, "device was null");
    return 0;
  }
  const char* drv = env->GetStringUTFChars(device, nullptr);
  if (drv == nullptr) {
    ThrowJava(env, "device was null");
    return 0;
  }
  std::string driver_copy(drv);
  env->ReleaseStringUTFChars(device, drv);

  if (paramScopes == nullptr || paramPaths == nullptr) {
    ThrowJava(env, "paramScopes and paramPaths must not be null");
    return 0;
  }
  if (env->GetArrayLength(paramScopes) != env->GetArrayLength(paramPaths)) {
    ThrowJava(env, "paramScopes and paramPaths must have the same length");
    return 0;
  }
  std::vector<std::string> scope_strings = ToStringVector(env, paramScopes);
  if (env->ExceptionCheck()) return 0;
  std::vector<std::string> path_strings = ToStringVector(env, paramPaths);
  if (env->ExceptionCheck()) return 0;

  std::vector<ParameterScope> parameters;
  parameters.reserve(scope_strings.size());
  for (size_t i = 0; i < scope_strings.size(); ++i) {
    parameters.push_back(ParameterScope{scope_strings[i], path_strings[i]});
  }

  try {
    // Staging default: kCachedMapWrite — the fastest cached-staging mode
    // measured in docs/2026-08-04-staging-and-output-findings.md. The staged
    // fallback reuses a grow-only per-slot staging buffer instead of
    // allocating a fresh HAL buffer per call, recovering ~85% of the
    // staged-vs-wrapped delta at >= 4 MB. ImportOutcome means the same thing
    // in every mode (kStaged means "copied"). The other Load overloads select
    // kAllocatePerCall for the native tests/harness.
    auto runtime = IreeRuntime::Load(bytes, entry_copy, driver_copy, parameters,
                                     IreeRuntime::StagingMode::kCachedMapWrite);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(runtime.release()));
  } catch (const std::exception& e) {
    ThrowJava(env, e.what());
    return 0;
  }
}

// Precondition (see IreeNative.close's javadoc): handle is either 0 (a
// no-op -- AsRuntime(0) is nullptr, and delete on nullptr is defined and does
// nothing) or a value load() returned that has not already been passed here.
// There is no live-handle registry to check the latter, so a double close, or
// any other call on a handle after it has been closed, is a native
// double-free/use-after-free -- undefined behavior, not a checked Java error.
// Releases everything the runtime owns: session, device, instance (in that
// order) and the retained vmfb copy -- see ~IreeRuntime(). Never throws; any
// IREE teardown status is not surfaced.
extern "C" JNIEXPORT void JNICALL
Java_org_measly_iree_jni_IreeNative_close(JNIEnv*, jclass, jlong handle) {
  delete AsRuntime(handle);
}

// Returns the address of a direct ByteBuffer's backing memory, or 0 for a
// non-direct buffer (JNI spec). Used by the alignment probe and by the
// aligned-buffer Cleaner wiring; the JVM guarantees nothing stronger than
// long (8-byte) alignment for its own direct buffers.
//
// Precondition (see IreeNative.bufferAddress's javadoc): buffer is non-null.
// Unlike invoke() below, which explicitly null-guards the same underlying
// call before using it (see the `buffer == nullptr` check ahead of
// GetDirectBufferAddress there), this function passes buffer straight to
// GetDirectBufferAddress with no null check of its own. Calling this with
// null is undefined behavior in native code, not a checked Java error. No
// allocation, no local references beyond the borrowed `buffer` argument, and
// no error path: this call cannot itself throw.
extern "C" JNIEXPORT jlong JNICALL
Java_org_measly_iree_jni_IreeNative_bufferAddress(JNIEnv* env, jclass,
                                                  jobject buffer) {
  return reinterpret_cast<jlong>(env->GetDirectBufferAddress(buffer));
}

// Inputs and outputs both cross as direct ByteBuffers. Input addresses are
// borrowed for exactly the duration of this call, which is what makes the
// facade's import-or-copy safe: the Java region stays pinned across the
// boundary for precisely that window.
//
// Preconditions (see IreeNative.invoke's javadoc): handle is a still-open
// handle from load(); inputBuffers/inputShapes/inputTypes are non-null and
// the same length; every element of inputBuffers is itself a non-null direct
// ByteBuffer. All of these are checked below rather than assumed, including
// the per-element buffer check inside the marshalling loop.
//
// Allocation/ownership: InvokeViews() leaves its output views live on the
// runtime rather than handing back owned memory (see its header comment);
// this function is responsible for turning each view into a JVM-owned direct
// ByteBuffer (via ByteBuffer.allocateDirect + ReadOutput(), a copy) and then
// releasing the views with ReleaseOutputs(). Once InvokeViews() has
// succeeded, every path below reaches ReleaseOutputs() exactly once --
// success, the 2 GiB-limit throw, and the generic catch block all call it --
// with one deliberate, documented exception: see the FindClass/GetMethodID
// lookups a few lines down.
//
// Local-reference discipline: inputBuffers[i]'s and inputShapes[i]'s locals
// from GetObjectArrayElement are never explicitly deleted inside the input
// loop -- unlike ToStringVector()'s string loop -- because `count` is bounded
// by the model's own input arity (small, fixed per model) rather than by
// caller-supplied data of unbounded size, so the default local-ref table is
// not at risk here. tensor_class, ctor, bb, and allocate are looked up once
// before the output loop specifically because they are loop-invariant (see
// the comment ahead of the `bb`/`allocate` lookups); per-output locals
// (owned, shape, tensor) ARE explicitly deleted at the end of each output
// iteration, since the output count is caller/model-controlled and looping
// without deleting them would accumulate one triple of locals per output.
//
// Error path: a failure inside InvokeViews() (a failed input import, a failed
// invoke, or an internal facade error) is caught and turned into a Java
// RuntimeException via ThrowJava(); no output views exist yet in that case,
// so there is nothing to release. A failure discovered while building the
// result array (an output at/above the 2 GiB jsize limit, an allocation
// failure, or any other std::exception from the facade) both releases the
// views and either throws explicitly or lets a pending Java exception
// (OutOfMemoryError) propagate on return, per the comments at each site
// below.
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

  if (inputBuffers == nullptr || inputShapes == nullptr || inputTypes == nullptr) {
    ThrowJava(env, "inputs, shapes, and elementTypes must not be null");
    return nullptr;
  }
  const jsize count = env->GetArrayLength(inputBuffers);
  // The marshalling below indexes shapes[i]/types[i] for i in [0, count).
  // A caller that passes shorter arrays would otherwise be out-of-bounds
  // reads of the JNI copies (and, on some JVMs/layouts, a SIGSEGV in the
  // interpreter's own bookkeeping — observed 2026-08-04 across three JDKs).
  // Validate up front instead of trusting the caller.
  if (env->GetArrayLength(inputShapes) != count ||
      env->GetArrayLength(inputTypes) != count) {
    ThrowJava(env, "inputs, shapes, and elementTypes must have the same length");
    return nullptr;
  }
  std::vector<InputDesc> inputs(static_cast<size_t>(count));
  std::vector<std::vector<int64_t>> shapes(static_cast<size_t>(count));

  jint* types = env->GetIntArrayElements(inputTypes, nullptr);
  if (types == nullptr) {
    ThrowJava(env, "failed to acquire input element types (out of memory?)");
    return nullptr;
  }

  for (jsize i = 0; i < count; ++i) {
    jobject buffer = env->GetObjectArrayElement(inputBuffers, i);
    // GetDirectBufferAddress(nullptr) is undefined; fold the null element
    // into the existing non-direct-buffer rejection.
    void* address = buffer == nullptr ? nullptr : env->GetDirectBufferAddress(buffer);
    const jlong capacity = buffer == nullptr ? 0 : env->GetDirectBufferCapacity(buffer);
    if (buffer == nullptr || address == nullptr || capacity < 0) {
      env->ReleaseIntArrayElements(inputTypes, types, JNI_ABORT);
      ThrowJava(env, "input must be a direct ByteBuffer");
      return nullptr;
    }

    jlongArray shapeArray =
        static_cast<jlongArray>(env->GetObjectArrayElement(inputShapes, i));
    if (shapeArray == nullptr) {
      env->ReleaseIntArrayElements(inputTypes, types, JNI_ABORT);
      ThrowJava(env, "input shape is null");
      return nullptr;
    }
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

  std::vector<measly::iree::OutputLayout> layouts;
  try {
    layouts = runtime->InvokeViews(inputs);
  } catch (const std::exception& e) {
    ThrowJava(env, e.what());
    return nullptr;
  }

  // NOTE (documented, not fixed -- see the file's task notes): InvokeViews()
  // above has already succeeded, so the runtime is now holding output views
  // that only ReleaseOutputs() will release. Each of these four lookups can
  // fail (a pending exception, e.g. OutOfMemoryError or NoSuchMethodError)
  // and returns nullptr WITHOUT calling ReleaseOutputs() first, unlike every
  // other early return past this point. These are core-JDK class/methodID
  // lookups with hardcoded signatures; their failure is effectively
  // JVM-fatal, which is why this is not treated as a caller-visible leak in
  // practice -- but it is a real gap in the release-on-every-path property
  // the rest of this function maintains.
  jclass tensor_class = env->FindClass("org/measly/iree/jni/IreeTensor");
  if (tensor_class == nullptr) return nullptr;
  jmethodID ctor = env->GetMethodID(tensor_class, "<init>",
                                    "(Ljava/nio/ByteBuffer;[JI)V");
  if (ctor == nullptr) return nullptr;

  // Loop-invariant: java/nio/ByteBuffer's class and its allocateDirect
  // methodID don't change per output, so look them up once here rather than
  // inside the loop (avoids per-iteration local-ref accumulation and
  // redundant lookups). Same caveat as tensor_class/ctor above: a failure
  // here also returns without releasing the pending output views.
  jclass bb = env->FindClass("java/nio/ByteBuffer");
  if (bb == nullptr) return nullptr;
  jmethodID allocate = env->GetStaticMethodID(bb, "allocateDirect",
                                              "(I)Ljava/nio/ByteBuffer;");
  if (allocate == nullptr) return nullptr;

  try {
    jobjectArray result =
        env->NewObjectArray(static_cast<jsize>(layouts.size()), tensor_class, nullptr);
    if (result == nullptr) {
      // OOM pending; it propagates on its own once we return. Release the
      // output views so the runtime is not left holding mapped buffers.
      runtime->ReleaseOutputs();
      return nullptr;
    }

    for (size_t i = 0; i < layouts.size(); ++i) {
      const auto& layout = layouts[i];
      // allocateDirect takes an int: an output at or above 2 GiB would be
      // silently truncated (jint) and then map_read into a short buffer —
      // a heap write overflow. Reject it instead.
      if (measly::iree::exceedsJniArrayLimit(layout.nbytes)) {
        runtime->ReleaseOutputs();
        ThrowJava(env, "IREE output exceeds the 2GB JNI direct-buffer limit");
        return nullptr;
      }
      // Direct buffer the JVM owns; the facade map_reads straight into it, so
      // this is the output path's ONLY copy — no intermediate owning vector,
      // no second JNI memcpy. Nothing IREE-side outlives
      // ReleaseOutputs() below; execution is synchronous, so the map_read
      // completes before the views are released.
      jobject owned = env->CallStaticObjectMethod(
          bb, allocate, static_cast<jint>(layout.nbytes));
      if (owned == nullptr || env->ExceptionCheck()) {
        // allocateDirect failed (e.g. OutOfMemoryError). A pending exception
        // will propagate to Java on its own once we return; calling
        // GetDirectBufferAddress/map_read on a null result with an exception
        // pending is undefined behavior, so bail out cleanly without also
        // throwing over it.
        runtime->ReleaseOutputs();
        return nullptr;
      }
      void* dst = env->GetDirectBufferAddress(owned);
      if (dst == nullptr) {
        runtime->ReleaseOutputs();
        ThrowJava(env, "output direct buffer has no address");
        return nullptr;
      }
      runtime->ReadOutput(i, dst);

      // NewLongArray also takes an int; a shape longer than 2^31 dims is
      // theoretical, but it is the loop's only other silent truncation.
      if (measly::iree::exceedsJniArrayLimit(layout.shape.size())) {
        runtime->ReleaseOutputs();
        ThrowJava(env, "IREE output shape exceeds the JNI array length limit");
        return nullptr;
      }
      jlongArray shape = env->NewLongArray(static_cast<jsize>(layout.shape.size()));
      if (shape == nullptr) {
        // OOM pending; propagate on return without throwing over it.
        runtime->ReleaseOutputs();
        return nullptr;
      }
      // SetLongArrayRegion cannot fail once the array exists at the exact
      // size (it only copies bytes), so no ExceptionCheck is needed here.
      env->SetLongArrayRegion(shape, 0, static_cast<jsize>(layout.shape.size()),
                              reinterpret_cast<const jlong*>(layout.shape.data()));

      jobject tensor = env->NewObject(tensor_class, ctor, owned, shape,
                                      static_cast<jint>(layout.elementType));
      if (tensor == nullptr) {
        // OOM pending; propagate on return without throwing over it.
        runtime->ReleaseOutputs();
        return nullptr;
      }
      // SetObjectArrayElement cannot fail either: result is allocated with
      // exactly layouts.size() slots and i < layouts.size().
      env->SetObjectArrayElement(result, static_cast<jsize>(i), tensor);
      env->DeleteLocalRef(tensor);
      env->DeleteLocalRef(shape);
      env->DeleteLocalRef(owned);
    }
    runtime->ReleaseOutputs();
    return result;
  } catch (const std::exception& e) {
    runtime->ReleaseOutputs();  // idempotent; views released on any throw path
    ThrowJava(env, e.what());
    return nullptr;
  }
}

// Precondition: handle is a still-open handle from load() (see
// IreeNative.lastImportOutcomes's javadoc); a zero/closed handle throws
// rather than returning an empty or null result, unlike stats() below, which
// intentionally never throws.
//
// Allocation: reads runtime->lastImportOutcomes(), a view into state the
// facade retains internally (no facade-side allocation here), and copies it
// into a freshly allocated jintArray sized to match. No output views are
// involved, so there is nothing to release on any path.
//
// Error path: only the result array's own allocation can fail here (OOM);
// that failure is left to propagate as a pending Java exception on return,
// as noted at the call site.
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
  if (result == nullptr) {
    // OOM pending; propagates on its own. No views are held here (reads
    // cached copies), so no ReleaseOutputs. SetIntArrayRegion below cannot
    // fail once the array exists at the exact size.
    return nullptr;
  }
  std::vector<jint> values(outcomes.size());
  for (size_t i = 0; i < outcomes.size(); ++i) {
    values[i] = outcomes[i] == IreeRuntime::ImportOutcome::kWrapped ? 1 : 0;
  }
  env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()),
                         values.data());
  return result;
}

// --- Engine-allocated, 64-byte-aligned direct buffers ------------------------
//
// Borrow contract (see IreeNDManager): the buffer is engine-allocated native
// memory exposed to Java via NewDirectByteBuffer, and freed by the registered
// java.lang.ref.Cleaner (NOT by the JVM — NewDirectByteBuffer memory is not
// GC-managed). Alignment is fixed at kBufferAlignment (64), so the matching
// AlignedFree is unambiguous. A 64-aligned pointer imports zero-copy into
// IREE (kWrapped) deterministically, unlike a JVM-allocated direct buffer
// (see docs/2026-08-04-borrowed-host-buffers-findings.md §4).
//
// Precondition: capacity is non-negative (see IreeNative.allocateDirectAligned's
// javadoc); this is not checked here -- a negative capacity reaches AlignedAlloc
// via an unsigned cast in the size_t conversion below. Error path: a failed
// aligned allocation throws before any JNI object exists, so there is nothing to
// clean up; a failed NewDirectByteBuffer (JVM-side OOM) frees the just-allocated
// aligned block via AlignedFree before returning, so the native allocation never
// outlives the failed wrapper -- see the comment at that call below.

extern "C" JNIEXPORT jobject JNICALL
Java_org_measly_iree_jni_IreeNative_allocateDirectAligned(JNIEnv* env, jclass,
                                                          jint capacity) {
  void* p = nullptr;
  try {
    p = AlignedAlloc(static_cast<size_t>(capacity));
  } catch (const std::bad_alloc&) {
    ThrowJava(env, "aligned allocation failed (out of memory)");
    return nullptr;
  }
  jobject buffer = env->NewDirectByteBuffer(p, capacity);
  if (buffer == nullptr) {
    // OOM pending on the Java side (NewDirectByteBuffer returned null); the
    // exception propagates on its own — don't throw over it, but do not leak
    // the aligned block either.
    AlignedFree(p);
    return nullptr;
  }
  return buffer;
}

// Called exactly once per buffer, from the Cleaner. 0 is a no-op (bufferAddress
// of a non-direct buffer); a genuine double-free is undefined behavior, not
// safe to rely on — see IreeNative.freeDirectAligned's javadoc.
extern "C" JNIEXPORT void JNICALL
Java_org_measly_iree_jni_IreeNative_freeDirectAligned(JNIEnv*, jclass,
                                                      jlong address) {
  if (address == 0) return;
  AlignedFree(reinterpret_cast<void*>(static_cast<intptr_t>(address)));
}

// Native-side leak probe: the aligned buffers are JNI-allocated and not
// counted against -XX:MaxDirectMemorySize, so the live count — not direct
// memory pressure — is the leak signal for the Cleaner lifetime test.
extern "C" JNIEXPORT jlong JNICALL
Java_org_measly_iree_jni_IreeNative_aliveAlignedBuffers(JNIEnv*, jclass) {
  return static_cast<jlong>(AlignedLiveCount());
}

// Cold-path observability read. Deliberately returns null — not an exception —
// for a closed handle: the caller is a monitoring poll whose contract is that
// it never throws. Every other entry point in this file throws on a closed
// handle; this one must not.
//
// This function only copies RuntimeStats' fields into the fixed-layout array
// verbatim -- it applies no sentinel of its own. When statisticsAvailable is
// false, deviceBytesPeak/deviceBytesLive are the literal 0 the facade reports
// (see the #else branch in iree_runtime.cpp's Stats()), not a marker value;
// STAT_STATISTICS_AVAILABLE (index 5 below) is what tells the caller those two
// entries are meaningless. The "-1 = unavailable" convention some Java callers
// use is applied one layer above this, in IreeSymbolBlock -- this function and
// the facade beneath it never produce a negative sentinel.
extern "C" JNIEXPORT jlongArray JNICALL
Java_org_measly_iree_jni_IreeNative_stats(JNIEnv* env, jclass, jlong handle) {
  IreeRuntime* runtime = AsRuntime(handle);
  if (runtime == nullptr) {
    return nullptr;
  }

  measly::iree::RuntimeStats stats = runtime->Stats();

  jlongArray out = env->NewLongArray(6);
  if (out == nullptr) {
    return nullptr;  // OOM pending; the JVM throws on return.
  }
  jlong values[6] = {
      static_cast<jlong>(stats.wrappedImports),
      static_cast<jlong>(stats.stagedImports),
      static_cast<jlong>(stats.stagingBytes),
      static_cast<jlong>(stats.deviceBytesPeak),
      static_cast<jlong>(stats.deviceBytesLive),
      static_cast<jlong>(stats.statisticsAvailable ? 1 : 0),
  };
  env->SetLongArrayRegion(out, 0, 6, values);
  return out;
}

// Native-side leak probe, companion to aliveAlignedBuffers: a retained-forever
// runtime is reachable and therefore invisible to LSan, but visible here.
extern "C" JNIEXPORT jlong JNICALL
Java_org_measly_iree_jni_IreeNative_aliveRuntimes(JNIEnv*, jclass) {
  return static_cast<jlong>(measly::iree::AliveRuntimeCount());
}

// Handle-free companion to STAT_STATISTICS_AVAILABLE. Deliberately takes no
// handle: this is a build property, so the snapshot must be able to report it
// with no model loaded.
extern "C" JNIEXPORT jboolean JNICALL
Java_org_measly_iree_jni_IreeNative_statisticsAvailable(JNIEnv*, jclass) {
  return measly::iree::StatisticsAvailable() ? JNI_TRUE : JNI_FALSE;
}
