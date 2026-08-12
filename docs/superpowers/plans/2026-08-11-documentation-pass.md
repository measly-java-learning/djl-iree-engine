# Documentation Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring four documentation surfaces up to the standard the code already meets — the published javadoc jar, the native C++ sources, a user-forward `README.md` with contributor material split out, and a new `CLAUDE.md`.

**Architecture:** Nine tasks in four groups, on one branch. Group A (Tasks 1-4) makes `./gradlew javadoc` emit zero warnings with content worth reading. Group B (Tasks 5-7) gives every native source ownership and lifetime contracts. Group C (Task 8) splits the README. Group D (Task 9) writes `CLAUDE.md`. The groups share no files and can be reviewed independently.

**Tech Stack:** Java 17 + standard `javadoc`, Gradle (Kotlin DSL) with a `buildSrc` code generator, C++20, CMake/Ninja, Markdown.

**Spec:** `docs/superpowers/specs/2026-08-11-documentation-pass-design.md`

## Global Constraints

- **No emoji in any Markdown this pass touches or creates.** Applies to `README.md`, `CONTRIBUTING.md`, `docs/observability.md`, `CLAUDE.md`. No emoji in headings, status markers, callouts, tables, or prose. Verified by the grep in Task 8 Step 8 and Task 9 Step 4.
- **No behavior changes.** The only non-comment code this pass adds is new `package-info.java` files and the javadoc-emission change in `buildSrc/src/main/kotlin/IreeDataTypeCodegen.kt`. Native tasks are comments only.
- **No doclint gate.** Do not add `-Xdoclint` or make javadoc warnings build-failing. The spec declined this explicitly.
- **No Doxygen.** Native comments are plain `//` in the style `native/core/iree_runtime.h` already sets. Do not add a Doxyfile, `@brief`, `@param`, or any Doxygen tooling.
- **No `@param name the name` filler.** A parameter whose only honest description restates its own name gets a sentence about its constraints instead — legal values, and what happens for illegal ones.
- **If the pass surfaces a genuine bug or a wrong API, report it — do not fix it here.**
- **Three platforms ship:** `linux-x86_64`, `linux-aarch64`, `windows-x86_64`. Never write "Linux only".

## Baseline: the 59 warnings, by origin

Run `./gradlew javadoc --rerun-tasks 2>&1 | grep -c "warning:"` before starting. The warnings decompose as:

| Origin | Count | Fixed by |
| --- | --- | --- |
| `build/generated/.../IreeDataTypes.java` | 18 | Task 1 — editing the **generator**, not the output |
| `jni/IreeNative.java` | 28 | Task 3 |
| `jni/IreeTensor.java` | 6 | Task 3 |
| `engine/` (7 files, 1 each) | 7 | Task 2 |
| `example/` module | 4 | Task 4 |

The generated file cannot be fixed by editing Java source — it is regenerated on every build. This is the single most likely way to waste an hour on this plan.

---

## Group A: Javadoc

### Task 1: Generated `IreeDataTypes` constants carry javadoc

**Files:**
- Modify: `buildSrc/src/main/kotlin/IreeDataTypeCodegen.kt:253-260`
- Verify (generated, never edited by hand): `build/generated/sources/iree/org/measly/iree/engine/IreeDataTypes.java`

**Interfaces:**
- Consumes: nothing.
- Produces: a generated `IreeDataTypes` whose public constants each carry a javadoc comment. Tasks 2-4 assume this file contributes zero warnings.

- [ ] **Step 1: Confirm the failure**

Run:
```bash
./gradlew javadoc --rerun-tasks 2>&1 | grep -c "IreeDataTypes.java.*warning\|warning: no comment" 
./gradlew javadoc --rerun-tasks 2>&1 | grep "IreeDataTypes.java" | head -3
```
Expected: 18 warnings naming `IreeDataTypes.java`, each `warning: no comment` on a `public static final int`.

- [ ] **Step 2: Read the emission site**

Read `buildSrc/src/main/kotlin/IreeDataTypeCodegen.kt` lines 230-320. The constants loop currently emits a trailing line comment, which javadoc does not see:

```kotlin
        // -- Constants for every mapped IREE element type --
        sb.appendLine("    // IREE element type constants")
        val sortedMappings = mappings.sortedBy { it.iree }
        for (m in sortedMappings) {
            val value = manifest[m.iree]!!.value
            val hex = "0x${value.toUInt().toString(16).uppercase()}"
            sb.appendLine("    public static final int ${m.iree} = $value; // $hex")
        }
```

- [ ] **Step 3: Emit a javadoc comment per constant**

Replace the loop body so the hex value moves from a line comment into javadoc. The DJL type it maps to is already known as `m.djl` — say so, because the bare hex is not information a reader lacks:

```kotlin
        // -- Constants for every mapped IREE element type --
        // Javadoc, not a line comment: these are public API and appear in the
        // published javadoc jar, where a trailing // comment is invisible.
        val sortedMappings = mappings.sortedBy { it.iree }
        for (m in sortedMappings) {
            val value = manifest[m.iree]!!.value
            val hex = "0x${value.toUInt().toString(16).uppercase()}"
            sb.appendLine(
                "    /** IREE {@code iree_hal_element_type_t} $hex, mapped to DJL {@link DataType#${m.djl}}. */"
            )
            sb.appendLine("    public static final int ${m.iree} = $value;")
        }
```

- [ ] **Step 4: Regenerate and read the output**

Run:
```bash
./gradlew generateIreeDataTypes --rerun-tasks
sed -n '15,35p' build/generated/sources/iree/org/measly/iree/engine/IreeDataTypes.java
```
Expected: each constant preceded by a `/** ... */` line naming its hex value and DJL type. Confirm the `{@link DataType#...}` targets are real `ai.djl.ndarray.types.DataType` constants — if any mapping names a `DataType` that does not exist, the javadoc run in Step 5 reports a broken link and that is a real defect to fix, not to suppress.

- [ ] **Step 5: Verify the warnings are gone**

Run:
```bash
./gradlew javadoc --rerun-tasks 2>&1 | grep -c "IreeDataTypes.java"
```
Expected: `0`.

- [ ] **Step 6: Commit**

```bash
git add buildSrc/src/main/kotlin/IreeDataTypeCodegen.kt
git commit -m "docs: emit javadoc for generated IreeDataTypes constants"
```

---

### Task 2: `org.measly.iree.engine` javadoc

**Files:**
- Create: `src/main/java/org/measly/iree/engine/package-info.java`
- Modify: `src/main/java/org/measly/iree/engine/` — `IreeEngine.java`, `IreeLoadOptions.java`, `IreeModel.java`, `IreeNDArray.java`, `IreeNDManager.java`, `IreeSymbolBlock.java`, `LibUtils.java`, `ManifestException.java`, `ModelManifest.java`, `ModelResolver.java`, `ParameterBinding.java`, `ResolvedModel.java`, `IreeEngineProvider.java`

**Interfaces:**
- Consumes: Task 1's warning-free generated source.
- Produces: `package-info.java` for `org.measly.iree.engine`. Task 3 creates the sibling `jni` package-info and cross-links to this one.

**Do not modify:** `IreeEngineStats.java`, `IreeStatsSnapshot.java`, `IreeModelStats.java`, `IreeModelCounters.java`, `IreeEngineStatsMXBean.java`. These are already documented to the target standard and are the reference for what "good" means here. Read `IreeEngineStats.java` before writing anything, to match its voice.

- [ ] **Step 1: Establish the target standard**

Read `src/main/java/org/measly/iree/engine/IreeEngineStats.java` and `IreeStatsSnapshot.java`. Note the house style: a one-sentence summary, then `<p>` paragraphs explaining *why* and what the caller must guarantee. Match it.

- [ ] **Step 2: Write the package-info**

Create `src/main/java/org/measly/iree/engine/package-info.java`:

```java
/**
 * A DJL engine that runs IREE {@code .vmfb} programs.
 *
 * <p>DJL discovers this engine through {@link org.measly.iree.engine.IreeEngineProvider};
 * callers normally touch it only through DJL's own {@code Model}, {@code Predictor}, and
 * {@code NDManager} interfaces. The types here are the ones worth knowing about directly:
 *
 * <ul>
 *   <li>{@link org.measly.iree.engine.ModelResolver} — what {@code Model.load} accepts: a
 *       manifest file, a directory holding one, or a bare {@code .vmfb}.
 *   <li>{@link org.measly.iree.engine.ModelManifest} — the manifest schema, which names the
 *       program and binds {@code .irpa} parameter archives to runtime scopes.
 *   <li>{@link org.measly.iree.engine.IreeLoadOptions} — the load options DJL passes through,
 *       and their precedence against the manifest.
 *   <li>{@link org.measly.iree.engine.IreeEngineStats} — always-on observability: engine
 *       configuration, process totals, and per-model counters, exposed over JMX.
 * </ul>
 *
 * <p>The native contract these types sit on lives in {@link org.measly.iree.jni}, which is
 * internal and not part of the supported API.
 */
package org.measly.iree.engine;
```

- [ ] **Step 3: Document the seven warning-producing declarations**

Each of these is a `warning: no comment` on a public member. Write real javadoc — what it does, what the caller must guarantee, what it throws and when:

- `LibUtils.loadLibrary()` — the extraction-and-load path, `IREE_LIBRARY_PATH`, that it is idempotent and synchronized, and why there is no content-addressed cache (the existing class comment already explains this; reference it rather than repeating).
- `ManifestException(String message)` — what condition produces it.
- `ModelManifest.parse(String json, String sourceLabel)` — the schema rules it enforces, what `sourceLabel` is used for (error messages), and the `ManifestException` cases.
- `ModelResolver.resolve(Path modelPath, String prefix, IreeLoadOptions options)` — the three accepted shapes of `modelPath`, path containment against the manifest's own directory including the symlink-escape check on the resolved real path, and how `allowUnsafePaths` changes it.
- The remaining flagged members in `IreeEngine.java`, `IreeLoadOptions.java`, `IreeSymbolBlock.java` — read the warning text for the exact declaration.

- [ ] **Step 4: Bring the thin classes up to standard**

These compile without warnings but say almost nothing. Add class-level javadoc covering purpose, ownership/lifetime, and thread-safety where it applies:

- `IreeSymbolBlock` (247 lines, 5 blocks) — the forward path, that `forward()` is **not thread-safe on the same model**, and that a model must not be closed with a forward in flight.
- `ModelManifest` (158 lines, 1 block) — schema v1: `schemaVersion` and `program` required, `schemaVersion` must be a JSON integer and is never assumed when absent, `entryPoint` and `parameters` optional, unknown fields ignored so the format can grow.
- `ModelResolver` (107 lines, 1 block) — the resolution table from the README.
- `IreeNDArray` (49), `IreeNDManager` (105), `IreeLoadOptions` (35), `ParameterBinding` (11), `ResolvedModel` (12), `IreeModel` (78), `IreeEngineProvider` (32).

For `IreeNDArray` and `IreeNDManager`, document the alignment story: `NDManager.create` allocates 64-byte-aligned buffers when `-Diree.engine.alignedBuffers=true`, those import zero-copy, and a caller-supplied direct `ByteBuffer` does not reliably do so because the JVM guarantees only 8-byte alignment.

- [ ] **Step 5: Cross-link**

Add `{@link}` references so the generated pages connect: `ModelResolver` to `ModelManifest` and `IreeLoadOptions`; `IreeLoadOptions` to `ModelManifest` for the precedence rule; `IreeSymbolBlock` to `IreeEngineStats` for the staged-import counters; `IreeNDArray` to `IreeNDManager`.

- [ ] **Step 6: Verify**

Run:
```bash
./gradlew javadoc --rerun-tasks 2>&1 | grep "engine/" | grep warning
```
Expected: no output.

- [ ] **Step 7: Read the generated HTML**

Open `build/docs/javadoc/org/measly/iree/engine/package-summary.html` and the `ModelResolver` and `IreeSymbolBlock` pages. Confirm they read as documentation, not as satisfied lint. If a sentence only restates the method name, rewrite it.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/measly/iree/engine/
git commit -m "docs: javadoc the engine package and add package-info"
```

---

### Task 3: `org.measly.iree.jni` javadoc and internal markers

**Files:**
- Create: `src/main/java/org/measly/iree/jni/package-info.java`
- Modify: `src/main/java/org/measly/iree/jni/IreeNative.java` (28 warnings), `src/main/java/org/measly/iree/jni/IreeTensor.java` (6 warnings)

**Interfaces:**
- Consumes: Task 2's `org.measly.iree.engine` package-info, which links here.
- Produces: `package-info.java` for `org.measly.iree.jni` marking the package internal and unstable.

- [ ] **Step 1: Write the package-info**

Create `src/main/java/org/measly/iree/jni/package-info.java`:

```java
/**
 * Internal JNI boundary to the {@code iree_djl} native shim. Not a supported API.
 *
 * <p><strong>This package is internal and unstable.</strong> It is public only because JNI
 * requires it. Its types, signatures, and native contract change in lockstep with the native
 * shim and are not covered by any compatibility guarantee — a patch release may change them.
 * Application code should use {@link org.measly.iree.engine} and DJL's own interfaces instead.
 *
 * <p>Every method in {@link org.measly.iree.jni.IreeNative} is a thin pass-through to native
 * code. The javadoc on each one states the contract the caller must satisfy: nothing here
 * validates its arguments beyond what the shim does, and violating a documented precondition
 * is undefined behavior in native code rather than a Java exception.
 */
package org.measly.iree.jni;
```

- [ ] **Step 2: Mark both classes internal at class level**

A user reading `IreeNative`'s page may never open the package page. Add to each class's javadoc:

```java
 * @apiNote Internal API. Not covered by compatibility guarantees; see
 *          {@link org.measly.iree.jni} for what that means in practice.
```

- [ ] **Step 3: Document `IreeTensor` (6 warnings)**

The file is 30 lines and currently documents only the class and constructor. Note the existing constructor comment says it is invoked from native code — preserve that fact, it is load-bearing:

```java
    /**
     * Wraps a tensor crossing the JNI boundary.
     *
     * <p>Invoked from native code — keep the signature in sync with the shim. No argument is
     * copied or validated: the instance holds exactly the references it is handed.
     *
     * @param data a direct {@link ByteBuffer} holding the tensor's elements, whose contents
     *     must outlive this instance; a non-direct buffer cannot cross to native code
     * @param shape the dimension sizes, retained by reference and never copied, so callers
     *     must not mutate it afterward
     * @param elementType an {@code iree_hal_element_type_t} value, as declared in
     *     {@code IreeDataTypes}
     */
```

Then `getData()`, `getShape()`, and `getElementType()`. These are the archetypal "no comment" accessors — do not write "returns the data". Say what the caller gets and what they must not do with it: `getShape()` returns the retained array, not a copy, so mutating it corrupts the tensor.

- [ ] **Step 4: Document `IreeNative` (28 warnings) — the native contract**

This is the highest-value javadoc in the tree, because the contract exists nowhere else in Java. For every native method, document:

- what the JVM side must guarantee before the call (buffer directness, alignment, non-null, handle still open),
- what the native side owns after it returns (and what the caller must release, and how),
- what happens on error — whether it throws, returns a sentinel, or is undefined.

For the statistics methods flagged in the baseline, be precise about the unavailability semantics the README already documents: `statisticsAvailable()` reports whether IREE's allocator statistics were compiled into the linked runtime, and the byte gauges return `-1` for "unavailable" versus `0` for "genuinely zero". `aliveRuntimes()` is a process-wide count used by the leak tests.

Read `native/jni/iree_djl_jni.cpp` alongside this step — the contract you are writing down is implemented there, and any place the two disagree is a finding to report (per the Global Constraints), not something to paper over.

- [ ] **Step 5: Verify**

Run:
```bash
./gradlew javadoc --rerun-tasks 2>&1 | grep "jni/" | grep warning
```
Expected: no output.

- [ ] **Step 6: Verify the root javadoc task is clean**

Run:
```bash
./gradlew :javadoc --rerun-tasks 2>&1 | grep -c "warning:"
```
Expected: `0`. This is the published jar's content; the `example` module is handled in Task 4.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/measly/iree/jni/
git commit -m "docs: document the jni package as internal and write its native contract"
```

---

### Task 4: `example` module javadoc

**Files:**
- Modify: `example/src/main/java/org/measly/example/ModelArtifacts.java` (3 warnings), `example/src/main/java/org/measly/example/MobilenetExample.java` (1 warning)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: a repo-wide clean `./gradlew javadoc`, which Task 8 cites when the README points users at the example.

Scope note: the example module is not published to Maven Central, so this is not required for the jar. It is included so the whole-repo command is clean and a future contributor is not told to ignore four warnings.

- [ ] **Step 1: See the warnings**

Run:
```bash
./gradlew :example:javadoc --rerun-tasks 2>&1 | grep -B1 warning
```

- [ ] **Step 2: Document the flagged declarations**

`ModelArtifacts` resolves the exported MobileNet files; document where it looks (`example/build/models/`) and that `./gradlew :example:exportModels` must have run first, because that is the actual failure a user hits. `MobilenetExample` gets a class-level comment stating what it demonstrates and how to run it.

- [ ] **Step 3: Verify repo-wide**

Run:
```bash
./gradlew javadoc --rerun-tasks 2>&1 | grep -c "warning:"
```
Expected: `0`.

- [ ] **Step 4: Commit**

```bash
git add example/src/main/java/org/measly/example/
git commit -m "docs: javadoc the example module's public entry points"
```

---

## Group B: Native comments

**Applies to every task in this group.** Comments only — a diff touching a single non-comment line in these files is a defect. Plain `//`, no Doxygen. Read `native/core/iree_runtime.h` first; it is the house style, and the goal is to bring the rest to its level rather than invent a new one.

Per file, write: a file-level block saying what this unit is and where it sits in the JVM to JNI to IREE path; ownership and lifetime contracts on every function (who allocates, who releases, what the caller must guarantee, what is valid after it returns); and why-comments on the non-obvious parts.

### Task 5: `native/core/`

**Files:**
- Modify: `native/core/iree_runtime.h` (168 lines), `native/core/iree_runtime.cpp` (536), `native/core/iree_handles.h` (77), `native/core/iree_status.h` (43), `native/core/aligned_alloc.h` (55)

**Interfaces:**
- Consumes: nothing.
- Produces: the documented facade that Task 6's JNI comments refer to by name.

- [ ] **Step 1: Record the baseline**

Run:
```bash
for f in native/core/*.h native/core/*.cpp; do
  echo "$f: $(wc -l < $f) lines, $(grep -cE '^\s*(//|/\*|\*)' $f) comment lines"
done
```
Expected, before changes: `aligned_alloc.h` 22/55, `iree_handles.h` 5/77, `iree_runtime.h` 73/168, `iree_status.h` 9/43, `iree_runtime.cpp` 141/536.

- [ ] **Step 2: `iree_handles.h` — the largest gap**

77 lines carrying the entire RAII story with 5 lines of comment. The existing header comment states the rule; what is missing is per-type contract. Document: that each `*Ptr` alias is the *only* legal owner of its handle and a raw handle must never escape the facade; that the deleters call IREE's `_release`, which is a refcount decrement rather than an unconditional free, so wrapping a handle transfers one reference; and on `CallGuard`, why `iree_runtime_call_t` gets a scope guard instead of a `unique_ptr` (the existing comment explains the value-type reason — extend it with the `mark_initialized()` contract: the guard tears down only if marked, so a failed initialize must not mark).

- [ ] **Step 3: `iree_status.h`**

Document the conversion from `iree_status_t` to whatever this codebase raises, who owns a status after conversion, and that an ignored `iree_status_t` leaks — IREE statuses are heap-allocated unless they are `iree_ok_status()`.

- [ ] **Step 4: `aligned_alloc.h`**

Already 22/55. Add the *reason* it exists: IREE imports a host buffer zero-copy only at 64-byte alignment (`IREE_HAL_HEAP_BUFFER_ALIGNMENT`), the JVM guarantees only 8, so the engine allocates and the caller writes into what it is handed. Document the free contract — memory from this allocator must go back to its matching free, not to `free()`.

- [ ] **Step 5: `iree_runtime.h`**

Best-covered file, but coverage is uneven across functions. Ensure every declared function states its ownership and lifetime contract and its failure mode, and that the file-level block names its position in the JVM to JNI to IREE path.

- [ ] **Step 6: `iree_runtime.cpp`**

Why-comments on the parts a reader will stumble over: the alignment precondition check and the staging fallback (including that the staging buffer is cached per runtime and reused across calls, rather than allocated per call), device and driver selection between `local-sync` and `local-task`, parameter-provider construction for `.irpa` scopes, and the teardown ordering that keeps handles released in a valid sequence.

- [ ] **Step 7: Verify the build is unaffected**

Run:
```bash
./native/build.sh && ./gradlew test
```
Expected: build succeeds, JVM suite passes.

- [ ] **Step 8: Verify the diff is comments only**

Run:
```bash
git diff -U0 -- native/core/ | grep '^[+-]' | grep -v '^[+-][+-]' | grep -vE '^[+-]\s*(//|/\*|\*)' 
```
Expected: no output. Any line here is a non-comment change and must be reverted.

- [ ] **Step 9: Commit**

```bash
git add native/core/
git commit -m "docs(native): ownership and lifetime contracts across the core facade"
```

---

### Task 6: `native/jni/`

**Files:**
- Modify: `native/jni/iree_djl_jni.cpp` (480 lines, 92 comment lines), `native/jni/array_size_limits.h` (16 lines, 5)

**Interfaces:**
- Consumes: Task 5's documented facade; refer to those functions by name rather than restating their contracts.
- Produces: the native side of the contract Task 3 wrote in `IreeNative`'s javadoc. **These two must agree.** Read `IreeNative.java` while writing this, and report any disagreement rather than silently changing either.

- [ ] **Step 1: File-level block for `iree_djl_jni.cpp`**

State that this is the only translation unit that sees JNI types, that it converts between JVM and facade representations and performs no IREE work itself, and that its exported symbol names are bound to the Java declarations in `org.measly.iree.jni.IreeNative`.

- [ ] **Step 2: Per-function contracts**

For each `JNIEXPORT` function: the preconditions the Java side must satisfy, what is allocated and who releases it, the local-reference discipline (what is created, what is explicitly deleted, and where a reference would otherwise accumulate across a loop), and the error path — how an IREE failure becomes a Java-visible outcome and what state the native side is left in.

- [ ] **Step 3: `array_size_limits.h`**

16 lines with 5 of comment. Document what the limit protects against — a JVM array length is a signed 32-bit value while IREE's sizes are not, so an unchecked conversion can wrap — and what happens when the limit is exceeded.

- [ ] **Step 4: Verify build and diff**

Run:
```bash
./native/build.sh && ./gradlew test
git diff -U0 -- native/jni/ | grep '^[+-]' | grep -v '^[+-][+-]' | grep -vE '^[+-]\s*(//|/\*|\*)'
```
Expected: build succeeds, tests pass, and the diff grep produces no output.

- [ ] **Step 5: Commit**

```bash
git add native/jni/
git commit -m "docs(native): document the JNI boundary contract and reference discipline"
```

---

### Task 7: `native/test/`, `native/harness/`, `native/bench/`

**Files:**
- Modify: `native/test/iree_runtime_test.cpp` (707 lines), `native/test/iree_params_test.cpp` (252), `native/test/link_smoke_test.cpp` (34), `native/harness/iree_leak_harness.cpp` (314), `native/bench/iree_copy_bench.cpp` (286)

**Interfaces:**
- Consumes: Tasks 5 and 6.
- Produces: nothing later tasks depend on. Task 8 links to the QA commands from `CONTRIBUTING.md` but does not depend on these comments.

Standard for this task: each case documents **what it proves and why that is the right proof**. The code already says what it does; a comment that restates the assertions adds nothing.

- [ ] **Step 1: `iree_runtime_test.cpp`**

File-level block naming what the Catch2 suite covers and how to build and run it (`./native/build_qa.sh`, then `./native/build/iree_runtime_test`). Per case: the property under test and why the chosen inputs establish it.

- [ ] **Step 2: `iree_params_test.cpp`**

Same treatment, focused on `.irpa` parameter-archive loading and scope binding — what a passing case proves about the manifest-to-scope path.

- [ ] **Step 3: `link_smoke_test.cpp`**

34 lines. A file-level block is most of the job: it proves the shim links and its symbols resolve, which is a distinct failure mode from any behavioral test and is why it exists separately.

- [ ] **Step 4: `iree_leak_harness.cpp`**

Document its argv contract (the cycle count and the driver selector, default `local-sync`), that it is the ASan/LSan go/no-go gate, and the two operational facts a reader needs: TSan requires `setarch $(uname -m) -R` because TSan's shadow mapping conflicts with ASLR, and `local-task` TSan reports are known false positives because the linked runtime is an uninstrumented Release build with no `__tsan` symbols.

- [ ] **Step 5: `iree_copy_bench.cpp`**

Document what it measures (the staged-versus-wrapped import delta), the input sizes it sweeps and why those sizes, and how to read the output — pointing at `docs/2026-08-04-borrowed-host-buffers-findings.md` for the recorded numbers rather than repeating figures that will drift.

- [ ] **Step 6: Verify QA still builds and passes**

Run:
```bash
./native/build_qa.sh
./native/build/iree_runtime_test
```
Expected: builds succeed; all Catch2 cases pass.

- [ ] **Step 7: Restore the plain shipping library**

A QA or sanitizer build can leave an instrumented library staged in the JVM resources, which breaks `./gradlew test`. Run:
```bash
./native/build.sh && ./gradlew test
```
Expected: build succeeds, JVM suite passes.

- [ ] **Step 8: Verify the diff is comments only**

Run:
```bash
git diff -U0 -- native/test/ native/harness/ native/bench/ | grep '^[+-]' | grep -v '^[+-][+-]' | grep -vE '^[+-]\s*(//|/\*|\*)'
```
Expected: no output.

- [ ] **Step 9: Commit**

```bash
git add native/test/ native/harness/ native/bench/
git commit -m "docs(native): document what each QA case proves"
```

---

## Group C: README split

### Task 8: README, CONTRIBUTING, observability, and the stale platform claim

**Files:**
- Modify: `README.md` (17.5K, currently 15 top-level sections)
- Create: `CONTRIBUTING.md`
- Create: `docs/observability.md`
- Modify: `docs/superpowers/specs/2026-08-03-windows-amd64-support-design.md:3-6` (stale `Status:` header)

**Interfaces:**
- Consumes: Task 4's clean javadoc (the README may state that the published jar carries full API docs).
- Produces: `CONTRIBUTING.md` and `docs/observability.md`, both linked from Task 9's `CLAUDE.md`.

**Preserve the existing prose.** Most of this README is well written; the work is reordering, splitting, condensing two sections, and fixing the platform error. Moved text should arrive near-verbatim. Do not rewrite a passage merely because you are moving it.

- [ ] **Step 1: Correct the platform facts first**

`README.md` currently says "Linux (x86_64 and aarch64) only" and its platform table has two rows. This is wrong — `windows-x86_64` ships. Verify for yourself before writing, so the table is grounded:

```bash
grep -n "nativePlatforms" build.gradle.kts
grep -n "build-iree-shim-windows\|runs-on: windows\|build_qa.sh\|check_windows_crt.sh" .github/workflows/native-build-job.yml
```
Expected: `nativePlatforms` lists all three; the Windows job runs on `windows-2022`, runs `./native/build_qa.sh`, and runs the CRT check before uploading.

- [ ] **Step 2: Rewrite `README.md` in the user-facing order**

Sections, in order:

1. **What it is** — one paragraph, plus a one-line status pointer to the Limitations section.
2. **Quickstart, tier 1** — inline Java loading the committed `add.vmfb` through DJL's `Model.load` and `Predictor`, with the expected output. Zero external prerequisites. State that the fixture is committed and needs no `iree-compile`.
3. **Quickstart, tier 2** — `./gradlew :example:exportModels` then `MobilenetExample`, linking `example/README.md` for the `uv` prerequisites.
4. **Declaring the dependency** — the existing Gradle-capability and Maven-classifier blocks, extended to all three platforms.
5. **Supported platforms** — the corrected table:

   | Platform | Artifact | HAL driver | QA |
   | --- | --- | --- | --- |
   | `linux-x86_64` | `libiree_djl.so` | `local-sync` (default), `local-task` | Catch2 + ASan/LSan leak harness; TSan |
   | `linux-aarch64` | `libiree_djl.so` | `local-sync` (default), `local-task` | Catch2 + ASan/LSan leak harness |
   | `windows-x86_64` | `iree_djl.dll` | `local-sync` (default), `local-task` | Catch2 + static-CRT assertion |

   Keep the existing note about extraction to `java.io.tmpdir` and `IREE_LIBRARY_PATH`.
6. **Runtime requirements** — JDK 17 and a supported platform. Explicitly not the build prerequisites.
7. **Loading models** — manifest schema v1, IRPA parameter archives, the `Model.load` resolution table, load options. Keep at current depth; this is the feature users came for. Keep both obligations: unarchive before loading, and compile for a baseline CPU target or risk SIGILL on older hardware.
8. **Observability** — condensed to what it is for, one code snippet, and the staged-import rate as the signal specific to this engine. Link `docs/observability.md`.
9. **Performance and zero-copy inputs** — condensed: the alignment story in brief and the `-Diree.engine.alignedBuffers=true` flag. Link the two findings docs for measurements.
10. **Threading** — unchanged.
11. **Status and limitations** — the walking-skeleton framing and the go/no-go verdict link, moved here from the top.
12. **Third-party licenses** — unchanged.
13. **Contributing** — link `CONTRIBUTING.md`; **Docs** — the existing index.

- [ ] **Step 3: Create `CONTRIBUTING.md`**

Move, near-verbatim: build prerequisites (the `iree-runtime-dist` pin and what it buys — no IREE source tree, no compiler; `tools/fetch-iree-metadata.sh`; JDK 17; CMake/Ninja/C++20; the network hosts touched; when `iree-compile` is actually needed); build and test; clangd editor setup with all four caveats; native QA including the ASan/LSan gate, the TSan `setarch` requirement, the mutually-exclusive sanitizer flags, the rebuild-the-plain-`.so` operational note, and the `local-task` false-positive analysis; the container build and pinned per-platform images; regenerating `add.vmfb`.

- [ ] **Step 4: Create `docs/observability.md`**

Take the detail condensed out of README section 8: the staged-import rationale in full, the `-1` versus `0` gauge semantics, JMX registration behavior and its failure handling (`getJmxStatus()`, one warning, never fails a model load), and the `ai.djl.metric.Metrics` comparison with the `Metrics.limit` default-of-0 explanation.

- [ ] **Step 5: Fix the stale Windows design-doc status**

In `docs/superpowers/specs/2026-08-03-windows-amd64-support-design.md`, replace the `Status:` header stating the remaining sections are "approved but not yet implemented" with one recording that Windows amd64 shipped, pointing at `build-iree-shim-windows` in `.github/workflows/native-build-job.yml` and the published `windows-x86_64` variant.

- [ ] **Step 6: Verify every internal link resolves**

Run:
```bash
grep -oE '\]\([^)#][^)]*\)' README.md CONTRIBUTING.md docs/observability.md \
  | sed -E 's/.*\]\(([^)]*)\)/\1/' | grep -v '^http' | sort -u \
  | while read -r p; do [ -e "$p" ] || echo "BROKEN: $p"; done
```
Expected: no `BROKEN:` lines.

- [ ] **Step 7: Verify no content was lost in the split**

Run:
```bash
git show HEAD:README.md | grep -oE '^#+ .*' 
grep -oE '^#+ .*' README.md CONTRIBUTING.md docs/observability.md
```
Compare the two lists by hand. Every heading from the old README must appear in one of the three files, or be a deliberate condensation whose detail moved into `docs/observability.md`. Nothing should have simply vanished.

- [ ] **Step 8: Verify the emoji constraint**

Run:
```bash
grep -nP '[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}\x{2B00}-\x{2BFF}\x{FE0F}]' \
  README.md CONTRIBUTING.md docs/observability.md
```
Expected: no matches, exit status 1.

- [ ] **Step 9: Verify the quickstart actually runs**

Do not ship an untested quickstart. Compile and run the tier-1 snippet exactly as written in the README, from a scratch directory, and confirm the output matches what the README claims. Fix the README to match reality, never the reverse.

- [ ] **Step 10: Commit**

```bash
git add README.md CONTRIBUTING.md docs/observability.md \
        docs/superpowers/specs/2026-08-03-windows-amd64-support-design.md
git commit -m "docs: make the README user-forward and split out CONTRIBUTING

Corrects the platform support claim: windows-x86_64 ships and is built,
QA'd, and published by CI."
```

---

## Group D: CLAUDE.md

### Task 9: `CLAUDE.md`

**Files:**
- Create: `CLAUDE.md`

**Interfaces:**
- Consumes: Task 8's `CONTRIBUTING.md`, which this file links rather than restates.
- Produces: nothing downstream.

Keep it thin. Anything derivable from the code, or already in `CONTRIBUTING.md`, is linked rather than repeated. The value is concentrated in the trip-wires section.

- [ ] **Step 1: Write the file**

Sections:

- **What this is** — two sentences, plus pointers to `README.md` and `CONTRIBUTING.md`.
- **Repo map** — `src/` engine; `src/main/java/org/measly/iree/jni` internal JNI boundary; `native/core` runtime facade; `native/jni` shim; `native/{test,harness,bench}` QA; `example/` MobileNet example and JMH benchmarks; `docs/`; `tools/`; `buildSrc/` the `IreeDataTypes` generator.
- **Commands that actually get run** — `./native/build.sh`, `./gradlew test`, `./native/build_qa.sh`, `./gradlew :example:exportModels`. Not an exhaustive task list.
- **Trip-wires** — each with its consequence, because a rule without a reason gets ignored:
  - a sanitizer or QA build stages an instrumented library that breaks `./gradlew test`; rebuild plain with `./native/build.sh` afterward
  - TSan needs `setarch $(uname -m) -R` or it dies immediately on ASLR
  - `local-task` TSan reports are known false positives against an uninstrumented Release runtime, not a new bug
  - never commit `native/build-clangd/` — every entry carries absolute paths
  - `add.vmfb` is committed; `iree-compile` is unnecessary unless regenerating fixtures
  - the pip `iree-base-runtime` wheel is unusable at any version — it ships no headers and no linkable library; the pinned dist tarball is the only source
  - `native/cmake/IreeRuntimePin.cmake` is the single source of truth for the runtime version
  - three platforms ship, including `windows-x86_64` — do not write "Linux only"
  - `IreeDataTypes.java` is generated by `buildSrc/src/main/kotlin/IreeDataTypeCodegen.kt`; editing the output does nothing
- **Before claiming done** — `./native/build.sh` compiles clean and `./gradlew test` passes; never commit the clangd database, an instrumented library, or large artifacts.
- **Code style** — Java: javadoc on all public API, `@apiNote` internal markers on the `jni` package. C++: plain `//` in `native/core/iree_runtime.h`'s style, ownership and lifetime contract on every function, `iree_status.h` conversion for error handling. Both: why-comments over what-comments.

- [ ] **Step 2: Verify every path and command named in the file exists**

Run:
```bash
for p in src native/core native/jni native/test native/harness native/bench \
         example docs tools buildSrc README.md CONTRIBUTING.md \
         native/build.sh native/build_qa.sh native/cmake/IreeRuntimePin.cmake \
         buildSrc/src/main/kotlin/IreeDataTypeCodegen.kt; do
  [ -e "$p" ] || echo "MISSING: $p"
done
```
Expected: no `MISSING:` lines.

- [ ] **Step 3: Verify the length**

Run: `wc -l CLAUDE.md`
Expected: under roughly 120 lines. If it is longer, content belongs in `CONTRIBUTING.md` instead — move it and link.

- [ ] **Step 4: Verify the emoji constraint**

Run:
```bash
grep -nP '[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}\x{2B00}-\x{2BFF}\x{FE0F}]' CLAUDE.md
```
Expected: no matches, exit status 1.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: add CLAUDE.md with the repo map and its trip-wires"
```

---

## Final verification

Run all of these before declaring the pass complete. Every one must pass.

```bash
# Javadoc: zero warnings repo-wide
./gradlew javadoc --rerun-tasks 2>&1 | grep -c "warning:"    # expect 0

# Native: builds clean and the JVM suite passes against the plain library
./native/build.sh && ./gradlew test

# Native QA still passes
./native/build_qa.sh && ./native/build/iree_runtime_test

# Restore the plain library after QA, so the tree is left usable
./native/build.sh && ./gradlew test

# Emoji constraint across every Markdown file this pass touched
grep -nP '[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}\x{2B00}-\x{2BFF}\x{FE0F}]' \
  README.md CONTRIBUTING.md docs/observability.md CLAUDE.md    # expect no matches

# No stray "Linux only" claim survives anywhere
grep -rn "Linux (x86_64 and aarch64) only\|Linux only" --include="*.md" .    # expect no matches

# Nothing that must never be committed is staged
git status --porcelain | grep -E "native/build-clangd|\.so$|\.dll$"    # expect no matches
```
