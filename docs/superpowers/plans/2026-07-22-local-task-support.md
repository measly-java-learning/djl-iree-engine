# local-task Support + QA-Gate Reinstatement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose IREE's `local-task` (multithreaded) driver as a selectable alternative to the hardcoded `local-sync`, add a benchmark arm for it, and reinstate the deferred QA gate (Catch2 units + ASan/LSan leak harness + JVM `leakTest` in CI; TSan as a local manual gate).

**Architecture:** A `driver` string threads through the existing load path (C++ facade → JNI → Java SPI → `IreeModel` `device` option), defaulting to `local-sync` so nothing changes for current callers. The caller contract is unchanged — `local-task` only adds intra-op parallelism *below* the JNI boundary, so no lock is added. The QA machinery (Catch2 target, ASan/LSan leak harness, sanitizer CMake options) already exists from the skeleton and is wired into CI; TSan reuses the leak harness driven with `local-task`.

**Tech Stack:** C++20 + IREE runtime C API, JNI, DJL SPI (Java 17), Catch2 v3, JMH (`me.champeau.jmh`), Gradle 9.6.1, GitHub Actions (manylinux_2_28 container).

## Global Constraints

- Default driver everywhere is `local-sync` — the passthrough must be backward-compatible; existing callers that pass no `device` behave exactly as before.
- Caller concurrency contract is **unchanged**: one Model/Predictor per thread, one invoke at a time per model. No shim lock. `local-task` adds intra-op workers below the boundary only.
- Worker-count / topology is **out of scope** (YAGNI). Use `local-task`'s driver-default topology via the existing `iree_runtime_instance_try_create_default_device` helper. Do not drop to explicit `iree_hal` device creation.
- The `device` model-load option key is exactly `"device"` (mirrors the existing `"entryPoint"` option).
- ASan and TSan are mutually exclusive (already enforced in `native/CMakeLists.txt`). Sanitized builds are QA-only and never shipped.
- TSan requires ASLR disabled (`setarch $(uname -m) -R`) and is therefore a **local manual gate only** — never a GitHub CI job.
- Local build/test env: `export JAVA_HOME=/usr/lib/jvm/zulu-17-amd64` for both native CMake configure and Gradle.
- The `add.vmfb` fixture's entry point is `module.add` (not the `module.main` default); its result for lhs `{1,2,3,4}` + rhs `{10,20,30,40}` is `{11,22,33,44}` (f32).

---

### Task 1: Native facade — `driver` parameter + local-task correctness gate

Adds the `driver` argument to the C++ facade and proves `local-task` actually loads, instantiates a worker pool, and produces the identical result to `local-sync`. This is also the design's validation gate: if `try_create_default_device("local-task")` cannot create a default-topology device, this task's test fails at `Load` and that is a finding to surface, not to work around.

**Files:**
- Modify: `native/core/iree_runtime.h:40-41` (add `driver` param to `Load`)
- Modify: `native/core/iree_runtime.cpp:27-44` (thread `driver` to `try_create_default_device`)
- Test: `native/test/iree_runtime_test.cpp` (append two `TEST_CASE`s)

**Interfaces:**
- Consumes: nothing new.
- Produces: `IreeRuntime::Load(std::span<const std::byte> vmfb, std::string_view entryPoint, std::string_view driver = "local-sync")` — the new third parameter, defaulted so existing callers (Catch2 load test, leak harness, JNI) compile unchanged.

- [ ] **Step 1: Write the failing tests**

Append to `native/test/iree_runtime_test.cpp` (the file already defines `ReadFile`, `kAddVmfb`, `kEntryPoint`, and `kF32`):

```cpp
TEST_CASE("local-task driver loads and matches local-sync", "[runtime][driver]") {
  auto bytes = ReadFile(kAddVmfb);
  auto runtime = IreeRuntime::Load(bytes, kEntryPoint, "local-task");
  REQUIRE(runtime != nullptr);

  const float lhs[4] = {1.0f, 2.0f, 3.0f, 4.0f};
  const float rhs[4] = {10.0f, 20.0f, 30.0f, 40.0f};
  std::vector<measly::iree::InputDesc> inputs = {
      {lhs, sizeof(lhs), {4}, kF32},
      {rhs, sizeof(rhs), {4}, kF32},
  };
  auto outputs = runtime->Invoke(inputs);
  REQUIRE(outputs.size() == 1);
  const float* r = reinterpret_cast<const float*>(outputs[0].data.data());
  REQUIRE(r[0] == 11.0f);
  REQUIRE(r[3] == 44.0f);
}

TEST_CASE("unknown driver fails cleanly at load", "[runtime][driver]") {
  auto bytes = ReadFile(kAddVmfb);
  REQUIRE_THROWS_AS(IreeRuntime::Load(bytes, kEntryPoint, "no-such-driver"),
                    std::runtime_error);
}
```

- [ ] **Step 2: Run the tests to verify they fail to compile**

```bash
export JAVA_HOME=/usr/lib/jvm/zulu-17-amd64
cmake -S native -B native/build -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build native/build --target iree_runtime_test
```
Expected: compile FAIL — `Load` currently takes 2 arguments, so the 3-arg calls do not compile.

- [ ] **Step 3: Add the `driver` parameter to the header**

In `native/core/iree_runtime.h`, replace the `Load` declaration (lines 40-41):

```cpp
  static std::unique_ptr<IreeRuntime> Load(std::span<const std::byte> vmfb,
                                           std::string_view entryPoint,
                                           std::string_view driver = "local-sync");
```

- [ ] **Step 4: Thread `driver` through the facade**

In `native/core/iree_runtime.cpp`, change the `Load` signature (lines 27-28) to accept `driver`, store it, and use it when creating the device. Replace lines 27-31:

```cpp
std::unique_ptr<IreeRuntime> IreeRuntime::Load(std::span<const std::byte> vmfb,
                                               std::string_view entryPoint,
                                               std::string_view driver) {
  auto state = std::make_unique<RuntimeState>();
  state->vmfb.assign(vmfb.begin(), vmfb.end());
  state->entryPoint = std::string(entryPoint);
```

Then replace the hardcoded device creation (lines 42-45):

```cpp
  iree_hal_device_t* raw_device = nullptr;
  std::string driver_name(driver);
  IREE_CHECK_OR_THROW(iree_runtime_instance_try_create_default_device(
      state->instance.get(),
      iree_make_string_view(driver_name.data(), driver_name.size()),
      &raw_device));
  state->device.reset(raw_device);
```

(The `driver_name` local guarantees a contiguous, sized buffer for `iree_make_string_view`, since `std::string_view` is not guaranteed null-terminated. Do not use `iree_make_cstring_view` on `driver.data()`.)

- [ ] **Step 5: Run the tests to verify they pass**

```bash
export JAVA_HOME=/usr/lib/jvm/zulu-17-amd64
cmake --build native/build --target iree_runtime_test
./native/build/iree_runtime_test "[driver]"
```
Expected: PASS — both `[driver]` test cases green. If `local-task` fails at `Load` (device could not be created), STOP and surface it as a finding per the design's validation gate.

- [ ] **Step 6: Confirm the worker pool actually spun up (validation evidence)**

Run the whole suite under strace and confirm `local-task` creates threads that `local-sync` did not:

```bash
strace -f -e trace=clone,clone3 ./native/build/iree_runtime_test "[driver]" 2>&1 | grep -c -E 'clone'
```
Expected: a non-zero count (the `local-task` device instantiated worker threads). Record the number in the commit message. (Contrast: the `local-sync`-only skeleton produced zero.)

- [ ] **Step 7: Commit**

```bash
git add native/core/iree_runtime.h native/core/iree_runtime.cpp native/test/iree_runtime_test.cpp
git commit -m "feat(native): local-task driver selection in the facade"
```

---

### Task 2: JNI + Java `device` option passthrough

Threads the driver from the DJL model-load options through the JNI boundary to the facade, defaulting to `local-sync`.

**Files:**
- Modify: `native/jni/iree_djl_jni.cpp:68-91` (add `jstring device`, marshal, pass to `Load`)
- Modify: `src/main/java/org/measly/iree/jni/IreeNative.java:16` (native `load` gains `String device`)
- Modify: `src/main/java/org/measly/iree/engine/IreeModel.java:20,41-47` (read `device` option)
- Modify: `src/main/java/org/measly/iree/engine/IreeSymbolBlock.java:18-21` (javadoc)
- Modify: `src/test/java/org/measly/iree/jni/IreeNativeTest.java` (update 4 call sites; add 2 tests)
- Modify: `src/test/java/org/measly/iree/AddModelIT.java` (add local-task IT)

**Interfaces:**
- Consumes: `IreeRuntime::Load(vmfb, entryPoint, driver)` from Task 1.
- Produces: `IreeNative.load(byte[] vmfb, String entryPoint, String device)` (native, 3-arg); `IreeModel` honoring `options.get("device")` with `DEFAULT_DEVICE = "local-sync"`.

- [ ] **Step 1: Write the failing tests**

Update the four existing 2-arg `IreeNative.load(...)` calls in `src/test/java/org/measly/iree/jni/IreeNativeTest.java` to pass the driver explicitly (lines 38, 72, 93, 100 → add `, "local-sync"`), e.g.:

```java
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");
```
```java
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-sync");   // reportsImportOutcome...
```
```java
        assertThrows(RuntimeException.class, () -> IreeNative.load(garbage, ENTRY_POINT, "local-sync"));
```
```java
                () -> IreeNative.load(addVmfb(), "module.does_not_exist", "local-sync"));
```

Then append two new tests to the same class:

```java
    @Test
    void loadInvokeWithLocalTaskDriver() throws IOException {
        long handle = IreeNative.load(addVmfb(), ENTRY_POINT, "local-task");
        assertTrue(handle != 0L);
        try {
            IreeTensor[] outputs =
                    IreeNative.invoke(
                            handle,
                            new ByteBuffer[] {
                                directFloats(1f, 2f, 3f, 4f),
                                directFloats(10f, 20f, 30f, 40f)
                            },
                            new long[][] {{4L}, {4L}},
                            new int[] {F32, F32});
            assertEquals(1, outputs.length);
            FloatBuffer result =
                    outputs[0].getData().order(ByteOrder.nativeOrder()).asFloatBuffer();
            assertEquals(11f, result.get(0));
            assertEquals(44f, result.get(3));
        } finally {
            IreeNative.close(handle);
        }
    }

    @Test
    void rejectsUnknownDriver() throws IOException {
        assertThrows(
                RuntimeException.class,
                () -> IreeNative.load(addVmfb(), ENTRY_POINT, "no-such-driver"));
    }
```

Add the local-task IT to `src/test/java/org/measly/iree/AddModelIT.java` (append inside the class):

```java
    @Test
    void runsAddWithLocalTaskDriver() throws Exception {
        Path modelDir = Paths.get("src/test/resources/models");

        try (Model model = Model.newInstance("add", "IREE")) {
            model.load(modelDir, "add", Map.of("entryPoint", "module.add", "device", "local-task"));

            try (NDManager manager = model.getNDManager().newSubManager()) {
                NDArray lhs = manager.create(new float[] {1f, 2f, 3f, 4f}, new Shape(4));
                NDArray rhs = manager.create(new float[] {10f, 20f, 30f, 40f}, new Shape(4));

                NDList outputs = model.getBlock().forward(null, new NDList(lhs, rhs), false);

                assertEquals(1, outputs.size());
                assertArrayEquals(
                        new float[] {11f, 22f, 33f, 44f},
                        outputs.get(0).toFloatArray(),
                        1e-6f);
            }
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME=/usr/lib/jvm/zulu-17-amd64
./gradlew test --tests 'org.measly.iree.jni.IreeNativeTest' --tests 'org.measly.iree.AddModelIT'
```
Expected: compile FAIL — `IreeNative.load` still has arity 2, so the 3-arg calls do not compile.

- [ ] **Step 3: Update the Java SPI declaration**

In `src/main/java/org/measly/iree/jni/IreeNative.java`, change the `load` declaration (line 16):

```java
    /**
     * Returns an opaque handle to the native runtime. Caller must close it.
     * {@code device} selects the IREE driver, e.g. "local-sync" or "local-task".
     */
    public static native long load(byte[] vmfb, String entryPoint, String device);
```

- [ ] **Step 4: Update the JNI implementation**

In `native/jni/iree_djl_jni.cpp`, replace the `load` function (lines 68-91):

```cpp
extern "C" JNIEXPORT jlong JNICALL
Java_org_measly_iree_jni_IreeNative_load(JNIEnv* env, jclass,
                                         jbyteArray vmfb, jstring entryPoint,
                                         jstring device) {
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

  const char* drv = env->GetStringUTFChars(device, nullptr);
  if (drv == nullptr) {
    ThrowJava(env, "device was null");
    return 0;
  }
  std::string driver_copy(drv);
  env->ReleaseStringUTFChars(device, drv);

  try {
    auto runtime = IreeRuntime::Load(bytes, entry_copy, driver_copy);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(runtime.release()));
  } catch (const std::exception& e) {
    ThrowJava(env, e.what());
    return 0;
  }
}
```

- [ ] **Step 5: Update `IreeModel` to read the `device` option**

In `src/main/java/org/measly/iree/engine/IreeModel.java`, add the default constant after line 20:

```java
    private static final String DEFAULT_ENTRY_POINT = "module.main";

    /** IREE driver. "local-sync" (default, single-threaded) or "local-task" (worker pool). */
    private static final String DEFAULT_DEVICE = "local-sync";
```

Then replace the option-resolution + load block (lines 41-47):

```java
        String entryPoint = DEFAULT_ENTRY_POINT;
        if (options != null && options.get("entryPoint") != null) {
            entryPoint = options.get("entryPoint").toString();
        }

        String device = DEFAULT_DEVICE;
        if (options != null && options.get("device") != null) {
            device = options.get("device").toString();
        }

        byte[] bytes = Files.readAllBytes(file);
        long handle = IreeNative.load(bytes, entryPoint, device);
```

- [ ] **Step 6: Update the `IreeSymbolBlock` concurrency javadoc**

In `src/main/java/org/measly/iree/engine/IreeSymbolBlock.java`, replace the javadoc paragraph (lines 18-21):

```java
 * <p><b>Not thread-safe on the same model.</b> One Model/Predictor per thread,
 * and never close a model with a forward in flight. An IREE session is not safe
 * for concurrent invocation. This caller contract is identical for every driver:
 * {@code local-task} adds an intra-op worker pool <i>below</i> this boundary
 * (parallelizing a single invoke), but does not make concurrent invocation of
 * one session safe. {@code local-sync} holds the contract all the way down by
 * running everything on the calling thread.
```

- [ ] **Step 7: Rebuild the shim and run the tests**

```bash
export JAVA_HOME=/usr/lib/jvm/zulu-17-amd64
./native/build.sh                       # rebuilds + stages libiree_djl.so with the new signature
export IREE_LIBRARY_PATH="$PWD/src/main/resources/native/linux-x86_64/libiree_djl.so"
./gradlew test --tests 'org.measly.iree.jni.IreeNativeTest' --tests 'org.measly.iree.AddModelIT'
```
Expected: PASS — all JNI tests (including `loadInvokeWithLocalTaskDriver`, `rejectsUnknownDriver`) and both `AddModelIT` tests green.

- [ ] **Step 8: Commit**

```bash
git add native/jni/iree_djl_jni.cpp src/main/java/org/measly/iree/jni/IreeNative.java \
        src/main/java/org/measly/iree/engine/IreeModel.java \
        src/main/java/org/measly/iree/engine/IreeSymbolBlock.java \
        src/test/java/org/measly/iree/jni/IreeNativeTest.java \
        src/test/java/org/measly/iree/AddModelIT.java
git commit -m "feat(engine): device option selects the IREE driver (default local-sync)"
```

---

### Task 3: Benchmark arm — `local-sync` vs `local-task`

Adds a JMH `@Param` axis so the existing MobileNetV2 benchmark runs both drivers, and rewrites the class javadoc from a single-arm caveat into a two-arm intra-op-parallelism note.

**Files:**
- Modify: `example/src/jmh/java/org/measly/example/MobilenetBenchmark.java`

**Interfaces:**
- Consumes: the `device` model-load option from Task 2.
- Produces: nothing downstream.

- [ ] **Step 1: Add the `@Param` and thread it into `criteria`**

In `example/src/jmh/java/org/measly/example/MobilenetBenchmark.java`, add the parameter field to the `Config` `@State` class (after `List<String> synset;`, around line 53):

```java
        @Param({"local-sync", "local-task"})
        String device;
```

Change the `criteria(...)` method (lines 68-78) to set the option:

```java
    static Criteria<Image, Classifications> criteria(
            Config cfg, CloseableImageTranslator translator) {
        return Criteria.builder()
                .setTypes(Image.class, Classifications.class)
                .optEngine(ENGINE)
                .optModelPath(cfg.modelsDir)
                .optModelName(MODEL_NAME)
                // Default entry point is "module.main"; the exported .vmfb uses it.
                .optOption("device", cfg.device)
                .optTranslator(translator)
                .build();
    }
```

(`Criteria.Builder.optOption(String, String)` places the entry into the model-load options map that `IreeModel.load` reads.)

- [ ] **Step 2: Rewrite the class javadoc**

Replace the two caveat paragraphs in the class javadoc (lines 26-40, "Single arm…" and "Comparing against other engines…") with:

```java
 * <p><b>Two arms: {@code local-sync} vs {@code local-task}</b> (JMH {@code @Param} on {@code
 * device}). Both arms run the same model, same f32 weights, and the same caller contract (one
 * invoke at a time). They differ only in whether IREE parallelizes a single invoke across an
 * intra-op worker pool ({@code local-task}) or runs it inline on the calling thread
 * ({@code local-sync}). The {@code local-task} arm's absolute latency is host-core-dependent
 * (topology = driver default) — read it as the on-machine delta against {@code local-sync}, not
 * as a portable figure.
 *
 * <p><b>Comparing against other engines is manual, by design.</b> This project is not linked to the
 * ExecuTorch/PyTorch benchmarks. To keep an eyeball comparison honest, the timed region here matches
 * that project's {@code ET_NATIVE} arm exactly: {@code predictor.predict(image)} on a pre-decoded
 * {@link Image}, with the same JMH config (warmup/iterations/fork, {@code AverageTime} steady-state +
 * {@code SingleShotTime} cold-start). Directional, not authoritative.
```

- [ ] **Step 3: Verify the benchmark compiles and enumerates both arms**

```bash
export JAVA_HOME=/usr/lib/jvm/zulu-17-amd64
export IREE_LIBRARY_PATH="$PWD/src/main/resources/native/linux-x86_64/libiree_djl.so"
./gradlew :example:jmhCompileGeneratedClasses
./gradlew :example:jmh -Pjmh.args='-l' 2>&1 | tail -20
```
Expected: compiles; the benchmark list shows `steadyState` and `coldStart`. (A full run is heavy; a smoke run of one arm/one iteration is optional: append `-Pjmh.args='-f1 -wi1 -i1 -p device=local-task'`.)

- [ ] **Step 4: Commit**

```bash
git add example/src/jmh/java/org/measly/example/MobilenetBenchmark.java
git commit -m "test(example): benchmark arm for local-sync vs local-task"
```

---

### Task 4: TSan local manual gate — harness driver mode + `tsan_gate.sh` + docs + CMake QA guard

Makes the leak harness able to drive `local-task` (so TSan has threads to inspect), adds a one-command TSan gate script, guards the JNI shim so sanitized builds need no JDK, and documents TSan as a required pre-merge manual step.

**Files:**
- Modify: `native/CMakeLists.txt:76-82` (skip the JNI shim under any sanitizer)
- Modify: `native/harness/iree_leak_harness.cpp` (driver argv; thread into cycles; update header comment)
- Create: `native/tsan_gate.sh`
- Modify: `README.md` (QA gates section)

**Interfaces:**
- Consumes: `IreeRuntime::Load(vmfb, entryPoint, driver)` from Task 1.
- Produces: `native/tsan_gate.sh` (used only by developers); the `IREE_DJL_SANITIZE`/`IREE_DJL_TSAN`-guarded JNI skip relied on by Task 6's `build_qa.sh`.

- [ ] **Step 1: Guard the JNI shim behind "no sanitizer"**

In `native/CMakeLists.txt`, wrap the JNI shim block (lines 76-82) so sanitized (QA-only) configures skip it and therefore need no `find_package(JNI)`:

```cmake
# JNI shim (Task 7): thin marshalling layer over iree_djl_core. Skipped under any
# sanitizer: sanitized builds are QA-only, never shipped, and JVM-free — so they must
# not require a JDK. The Catch2 units and the leak harness link iree_djl_core directly.
if(NOT IREE_DJL_SANITIZE AND NOT IREE_DJL_TSAN)
  find_package(JNI REQUIRED)
  add_library(iree_djl SHARED jni/iree_djl_jni.cpp)
  target_include_directories(iree_djl PRIVATE ${JNI_INCLUDE_DIRS})
  target_link_libraries(iree_djl PRIVATE iree_djl_core)
  # Hide IREE's symbols so they cannot collide with anything else in the JVM.
  target_link_options(iree_djl PRIVATE -Wl,--exclude-libs,ALL)
endif()
```

- [ ] **Step 2: Thread `driver` through the leak harness**

In `native/harness/iree_leak_harness.cpp`, change the three cycle functions to accept a driver and pass it to `Load`, and read the driver from `argv[3]`.

Update the header comment (lines 3-7) to reflect the new reality:

```cpp
// artifact and is exercised by this harness when a driver is passed as argv[3]
// (see native/tsan_gate.sh, which runs `local-task` under TSan). With the default
// `local-sync` driver the process stays single-threaded (TSan clean, zero
// clone/clone3, Threads: 1 per /proc/<pid>/status), which is what keeps the
// ASan/LSan run deterministic. See docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md.
```

Change `HappyPathCycle`, `ErrorPathCycle`, and `ImportEscapeCheck` to take `const char* driver` and forward it to every `IreeRuntime::Load(...)` call inside them, e.g.:

```cpp
void HappyPathCycle(const std::vector<std::byte>& vmfb, const char* driver) {
  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint, driver);
  ...
}
```
```cpp
void ErrorPathCycle(const std::vector<std::byte>& vmfb, const char* driver) {
  std::vector<std::byte> garbage(256, std::byte{0xAB});
  try { IreeRuntime::Load(garbage, kEntryPoint, driver); } catch (const std::runtime_error&) {}
  try { IreeRuntime::Load({}, kEntryPoint, driver); } catch (const std::runtime_error&) {}
  try { IreeRuntime::Load(vmfb, "module.nope", driver); } catch (const std::runtime_error&) {}

  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint, driver);
  ...
}
```
```cpp
void ImportEscapeCheck(const std::vector<std::byte>& vmfb, const char* driver) {
  auto runtime = IreeRuntime::Load(vmfb, kEntryPoint, driver);
  ...
}
```

Update `main` to read the driver and pass it through:

```cpp
int main(int argc, char** argv) {
  const char* path = (argc > 1 && argv[1][0]) ? argv[1] : IREE_DJL_ADD_VMFB;
  const int iterations = argc > 2 ? std::atoi(argv[2]) : 100;
  const char* driver = (argc > 3 && argv[3][0]) ? argv[3] : "local-sync";

  auto vmfb = ReadFile(path);
  std::printf("driver: %s\n", driver);

  for (int i = 0; i < iterations; ++i) HappyPathCycle(vmfb, driver);
  std::printf("happy path: %d cycles ok\n", iterations);

  for (int i = 0; i < iterations; ++i) ErrorPathCycle(vmfb, driver);
  std::printf("error paths: %d cycles ok\n", iterations);

  ImportEscapeCheck(vmfb, driver);
  std::printf("import escape check ok\n");

  std::printf("HARNESS PASS\n");
  return 0;
}
```

- [ ] **Step 3: Verify the default (local-sync) harness still builds and passes under ASan/LSan**

```bash
export JAVA_HOME=/usr/lib/jvm/zulu-17-amd64
cmake -S native -B native/qa -DIREE_DJL_SANITIZE=ON -DCMAKE_BUILD_TYPE=Debug \
  -DCMAKE_CXX_FLAGS="-fsanitize=address -fno-omit-frame-pointer -g" \
  -DCMAKE_EXE_LINKER_FLAGS="-fsanitize=address"
cmake --build native/qa --target iree_leak_harness
./native/qa/iree_leak_harness src/test/resources/models/add.vmfb 200
```
Expected: prints `driver: local-sync`, `HARNESS PASS`, ASan/LSan clean (no leak report). This also proves the CMake QA guard works — configure did not require a JDK.

- [ ] **Step 4: Create the TSan gate script**

Create `native/tsan_gate.sh`:

```bash
#!/usr/bin/env bash
# TSan gate for the local-task worker pool. NOT a GitHub CI job: TSan needs ASLR
# disabled (setarch -R), which is unavailable on GitHub-hosted container runners.
# Run this locally before merging any change to the native path. See README "QA gates".
#
# Drives the leak harness with the local-task driver so IREE's worker pool actually
# runs — that is the only configuration in which TSan can find a data race here. The
# ASan/LSan gate (native/build_qa.sh) deliberately stays local-sync/deterministic.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

ITERS="${ITERS:-500}"
BUILD_DIR="${BUILD_DIR:-native/tsan}"

cmake -S native -B "${BUILD_DIR}" -DIREE_DJL_TSAN=ON -DCMAKE_BUILD_TYPE=Debug \
  -DCMAKE_CXX_FLAGS="-fsanitize=thread -fno-omit-frame-pointer -g" \
  -DCMAKE_EXE_LINKER_FLAGS="-fsanitize=thread"
cmake --build "${BUILD_DIR}" --target iree_leak_harness

echo "--- TSan leak harness (local-task, ${ITERS} iterations) ---"
# setarch -R disables ASLR for this process; without it TSan's shadow mapping fails.
setarch "$(uname -m)" -R \
  ./"${BUILD_DIR}"/iree_leak_harness src/test/resources/models/add.vmfb "${ITERS}" local-task
echo "--- TSan gate PASS ---"
```

- [ ] **Step 5: Run the TSan gate**

```bash
export JAVA_HOME=/usr/lib/jvm/zulu-17-amd64
chmod +x native/tsan_gate.sh
./native/tsan_gate.sh
```
Expected: prints `driver: local-task`, `HARNESS PASS`, then `TSan gate PASS`, with **no** `WARNING: ThreadSanitizer: data race` in the output. If a race is reported, STOP — that is a real finding in the shim's interaction with the worker pool.

- [ ] **Step 6: Document the gate in the README**

In `README.md`, add a "QA gates" section (place it near the build instructions):

```markdown
## QA gates

| Gate | Command | Driver | Enforced |
|------|---------|--------|----------|
| Catch2 units + ASan/LSan leak harness | `native/build_qa.sh` | local-sync | GitHub CI |
| JNI / direct-buffer leaks | `./gradlew leakTest` | local-sync | GitHub CI |
| Data races | `native/tsan_gate.sh` | **local-task** | **Local manual** |

`tsan_gate.sh` is not in GitHub CI: ThreadSanitizer needs ASLR disabled
(`setarch -R`), which is unavailable on GitHub-hosted container runners. Run it
locally before merging any change to the native path (`native/`), since
`local-task` is the only configuration in which a data race can surface.
```

- [ ] **Step 7: Commit**

```bash
git add native/CMakeLists.txt native/harness/iree_leak_harness.cpp native/tsan_gate.sh README.md
git commit -m "test(native): local-task TSan gate (local manual) + sanitized-build JNI skip"
```

---

### Task 5: JVM `leakTest` gate

Adds a Gradle `leakTest` task and a `@Tag("leak")` stress test that loops model load/invoke/close under a constrained heap and direct-memory budget — covering the JNI boundary and the direct-`ByteBuffer` STAGED import path that the native harness (JVM-free) cannot reach. Excludes the `leak` tag from the normal `test` task.

**Files:**
- Modify: `build.gradle.kts:31-35` (exclude the `leak` tag from `test`) and add a `leakTest` task
- Create: `src/test/java/org/measly/iree/LeakStressTest.java`

**Interfaces:**
- Consumes: the `device` option (Task 2); the staged `libiree_djl.so`.
- Produces: `./gradlew leakTest` (used by CI in Task 6).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/measly/iree/LeakStressTest.java`:

```java
package org.measly.iree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Memory-leak stress test under a constrained heap/direct-memory budget (set by the leakTest
 * Gradle task). Loops load/invoke/close so an unbalanced native retain/release, or a leaked
 * direct ByteBuffer at the JNI boundary, exhausts the budget and fails with OOM instead of
 * passing silently. Tagged "leak" so it runs only via `./gradlew leakTest`, never in the
 * normal suite.
 */
@Tag("leak")
class LeakStressTest {

    private static final int ITERATIONS = 2000;

    @Test
    void loadInvokeCloseDoesNotLeak() throws Exception {
        Path modelDir = Paths.get("src/test/resources/models");
        float[] expected = {11f, 22f, 33f, 44f};

        for (int i = 0; i < ITERATIONS; i++) {
            try (Model model = Model.newInstance("add", "IREE")) {
                model.load(modelDir, "add", Map.of("entryPoint", "module.add"));
                try (NDManager manager = model.getNDManager().newSubManager()) {
                    NDArray lhs = manager.create(new float[] {1f, 2f, 3f, 4f}, new Shape(4));
                    NDArray rhs = manager.create(new float[] {10f, 20f, 30f, 40f}, new Shape(4));
                    NDList outputs = model.getBlock().forward(null, new NDList(lhs, rhs), false);
                    assertArrayEquals(expected, outputs.get(0).toFloatArray(), 1e-6f);
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify it does NOT run under the normal `test` task yet**

```bash
export JAVA_HOME=/usr/lib/jvm/zulu-17-amd64
export IREE_LIBRARY_PATH="$PWD/src/main/resources/native/linux-x86_64/libiree_djl.so"
./gradlew test --tests 'org.measly.iree.LeakStressTest' 2>&1 | tail -5
```
Expected: `No tests found` / the class is picked up but there is no `leakTest` task yet — this confirms the starting state. (After Step 3 the tag is excluded from `test` and a dedicated task runs it.)

- [ ] **Step 3: Add the tag exclusion and the `leakTest` task**

In `build.gradle.kts`, change the `test` task (lines 31-35) to exclude the tag:

```kotlin
tasks.test {
    useJUnitPlatform { excludeTags("leak") }
    jvmArgs("-XX:+HeapDumpOnOutOfMemoryError")
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("leakTest") {
    description = "Memory-leak stress tests under constrained heap/direct memory."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("leak") }
    jvmArgs("-Xmx256m", "-XX:MaxDirectMemorySize=64m", "-XX:+HeapDumpOnOutOfMemoryError")
}
```

(The existing `tasks.withType<Test>().configureEach { inputs.property("ireeLibraryPath", ...) }` block at lines 98-103 already declares `IREE_LIBRARY_PATH` as an input for **every** `Test` task, so `leakTest`'s cache key correctly tracks which `.so` is loaded. No change needed there.)

- [ ] **Step 4: Run `leakTest` and confirm it passes within the budget**

```bash
export JAVA_HOME=/usr/lib/jvm/zulu-17-amd64
export IREE_LIBRARY_PATH="$PWD/src/main/resources/native/linux-x86_64/libiree_djl.so"
./gradlew leakTest
```
Expected: BUILD SUCCESSFUL — 2000 iterations complete under `-Xmx256m -XX:MaxDirectMemorySize=64m` without `OutOfMemoryError`. Confirm the normal suite still excludes it: `./gradlew test` runs `AddModelIT` + `IreeNativeTest` but not `LeakStressTest`.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts src/test/java/org/measly/iree/LeakStressTest.java
git commit -m "test(engine): JVM leakTest gate for the JNI/direct-buffer path"
```

---

### Task 6: Wire the QA gate into CI

Ports ExecuTorch's `build_qa.sh` pattern (Catch2 units + ASan/LSan leak harness) and runs it plus `./gradlew leakTest` in GitHub CI as merge-blocking steps.

**Files:**
- Create: `native/build_qa.sh`
- Modify: `.github/workflows/native-build-job.yml` (add a QA step)
- Modify: `.github/workflows/native-build.yml` (add `./gradlew leakTest`)

**Interfaces:**
- Consumes: the sanitized-build JNI guard (Task 4); the `leakTest` task (Task 5).
- Produces: nothing downstream.

- [ ] **Step 1: Create the native QA script**

Create `native/build_qa.sh`:

```bash
#!/usr/bin/env bash
# Build + run the native QA targets (Catch2 units + ASan/LSan leak harness) against the
# resolved iree-runtime-dist runtime. NOT part of the shipping build: the QA targets are
# built with AddressSanitizer/LeakSanitizer into a distinct tree (native/qa), separate from
# the Release .so (native/build via native/build.sh).
#
# JVM-free: under a sanitizer, native/CMakeLists.txt skips the JNI shim, so NO JDK/JAVA_HOME
# is needed. In GitHub Actions, run this in the SAME manylinux_2_28 container as native/build.sh.
# TSan is intentionally absent — it needs ASLR disabled and runs as a local gate only
# (native/tsan_gate.sh).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

ITERS="${ITERS:-1000}"

# QA is the only ASan consumer; install the toolset's ASan runtime here.
if command -v dnf >/dev/null 2>&1; then
  echo "--- Installing ASan runtime (dnf), may appear to hang ---"
  TOOLSET_VER="$(gcc -dumpversion | cut -d. -f1)"
  dnf install -y -q "gcc-toolset-${TOOLSET_VER}-libasan-devel" || true
fi

JOBS="${JOBS:-$(nproc)}"
rm -rf native/qa
cmake -B native/qa -S native -G "Unix Makefiles" -DIREE_DJL_SANITIZE=ON \
  -DCMAKE_BUILD_TYPE=Debug \
  -DCMAKE_CXX_FLAGS="-fsanitize=address -fno-omit-frame-pointer -g" \
  -DCMAKE_EXE_LINKER_FLAGS="-fsanitize=address"
cmake --build native/qa --target iree_runtime_test iree_leak_harness -j"${JOBS}"

echo "--- Catch2 unit suite ---"
./native/qa/iree_runtime_test

echo "--- ASan/LSan leak harness (${ITERS} iterations, local-sync) ---"
./native/qa/iree_leak_harness src/test/resources/models/add.vmfb "${ITERS}"

echo "--- native QA PASS ---"
```

- [ ] **Step 2: Smoke-test the script locally**

```bash
export JAVA_HOME=/usr/lib/jvm/zulu-17-amd64   # not required by the script, harmless if set
chmod +x native/build_qa.sh
./native/build_qa.sh
```
Expected: Catch2 suite passes (including the Task 1 `[driver]` cases), leak harness prints `HARNESS PASS`, script ends `native QA PASS`, ASan/LSan clean. (Locally without `dnf`, the ASan runtime install is skipped — the host toolchain provides it.)

- [ ] **Step 3: Add the QA step to the reusable native build job**

In `.github/workflows/native-build-job.yml`, add a step after "Build the libiree_djl shim" (after line 57, before "Store libiree_djl shim"):

```yaml
      - name: Run native QA gate (Catch2 units + ASan/LSan leak harness)
        run: |
          docker run --rm \
            -v ${{ github.workspace }}:/workspace \
            -w /workspace \
            ${{ matrix.image }} \
            /bin/bash /workspace/native/build_qa.sh
```

- [ ] **Step 4: Add `leakTest` to the Java CI job**

In `.github/workflows/native-build.yml`, extend the "Build and test the Java package" step (the `run:` block currently running `./gradlew build` and `./gradlew test`):

```yaml
        - name: Build and test the Java package
          run: |
              ./gradlew build
              ./gradlew test
              ./gradlew leakTest
```

(`leakTest` loads the shim from the classpath copy staged by the download-artifact step, exactly as `test` does — no `IREE_LIBRARY_PATH` needed in CI.)

- [ ] **Step 5: Validate the workflow YAML**

```bash
python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in ['.github/workflows/native-build-job.yml','.github/workflows/native-build.yml']]; print('YAML OK')"
```
Expected: `YAML OK`. (CI itself runs on push/PR; the merge is what exercises the gate end-to-end.)

- [ ] **Step 6: Commit**

```bash
git add native/build_qa.sh .github/workflows/native-build-job.yml .github/workflows/native-build.yml
git commit -m "ci: reinstate QA gate (native Catch2/leak + JVM leakTest)"
```

---

## Self-Review

**Spec coverage:**
- Design A (passthrough): Tasks 1 (facade) + 2 (JNI/Java option, javadoc, no lock). ✓
- Design B (benchmark arm): Task 3. ✓
- Design C.1 (CI-enforced: Catch2 + ASan/LSan leak + JVM leakTest): Task 5 (leakTest) + Task 6 (native QA + CI wiring). ✓
- Design C.2 (TSan local manual gate + harness local-task mode + docs): Task 4. ✓
- Validation gate (`try_create_default_device("local-task")`): Task 1 Steps 5-6. ✓
- Worker-count deferred / driver-default: honored (Global Constraints; no explicit topology code). ✓
- CMake JNI-skip so sanitized builds need no JDK: Task 4 Step 1, relied on by Task 6. ✓
- `IREE_LIBRARY_PATH` cache-correctness for `leakTest`: covered by the existing `withType<Test>().configureEach` block (Task 5 Step 3 note). ✓

**Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N" — every code step shows full code, every command has expected output. ✓

**Type consistency:** `Load(vmfb, entryPoint, driver)` (Task 1) is consumed with matching arity by the JNI (Task 2), harness (Task 4), and Catch2/leak targets; `IreeNative.load(byte[], String, String)` (Task 2) matches all four updated call sites plus `IreeModel`; the `"device"` option key is identical in `IreeModel` (Task 2), `AddModelIT` (Task 2), `MobilenetBenchmark` (Task 3); `DEFAULT_DEVICE="local-sync"` and the harness/script driver defaults all agree. ✓
