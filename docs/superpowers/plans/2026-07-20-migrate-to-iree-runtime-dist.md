# Migrate to `iree-runtime-dist` v3.11.0-3 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hand-rolled local-IREE-build seam with the published `iree-runtime-dist` v3.11.0-3 artifact, so the engine builds with no IREE source tree, no IREE build tree, and no compiler installed.

**Architecture:** Delete `native/cmake/ResolveIree.cmake` (198-archive guesswork, `--start-group`, allocator define, split includes) and consume the dist's `find_package(IreeRuntimeDist)` + `iree-runtime-dist::runtime` umbrella target. Flip the model compiler from the 3.12 nightly to stable 3.11.0 to match the dist runtime. Generate `IreeDataTypes.java` from the dist's `element_types.json` instead of hand-transcribing constants.

**Tech Stack:** CMake + Ninja, C++20, gcc 13.3, JDK 17, Gradle 9.6.1, pip `iree-base-compiler==3.11.0`.

**Source of truth:** `docs/2026-07-20-djl-iree-engine-handover.md` (the dist project's handover). Where this plan and the handover disagree, the handover wins — it was verified against the published artifact.

## Secondary goal: this migration is a usability test

The user's stated reason for migrating now: *"if there are usability issues with the runtime dist I'd rather know sooner so I can take down a genuinely not helpful artifact."*

**Treat every point of friction as a finding, not just an obstacle.** In each task, record in the task report: anything that was unclear, undocumented, required guessing, or contradicted the handover. A migration that "worked but was confusing" is a finding.

**File each finding as a GitHub issue at the moment of discovery** — `gh issue create --repo measly-java-learning/iree-runtime-dist` — rather than batching them. Rationale (user's): filing immediately preserves the full context of the finding instead of forcing a lossy reconstruction later. Write a real, well-formed issue: expected vs actual, the exact code/commands involved, impact, and a suggested fix. Note verified-good behaviour too — it is as useful to the maintainer as the complaints. Task 5's report then becomes an *index* of filed issues plus the overall verdict, not the primary vehicle.

Issues filed so far: [#2](https://github.com/measly-java-learning/iree-runtime-dist/issues/2) (pin is coordinates-only vs. docs), [#3](https://github.com/measly-java-learning/iree-runtime-dist/issues/3) (pin variable naming).

## Global Constraints

- **Link surface is exactly two lines.** `find_package(IreeRuntimeDist REQUIRED)` and `target_link_libraries(... iree-runtime-dist::runtime)`. **Never hand-list archives from `lib/`** (198 `.a` files; `libiree_runtime_impl.a` is a 3-object trap; `libbenchmark.a` drags in libstdc++).
- **Never add `-Wl,--start-group`/`--end-group`.** The export set carries correct ordering.
- **Compiler is stable `iree-base-compiler==3.11.0`.** Never a nightly, never a `main` checkout. The dist runtime is `v3.11.0` @ `e4a3b040`; mixing a `main` runtime with a stable compiler is exactly the import-signature mismatch that cost the skeleton a detour.
- **Keep `-Wl,--exclude-libs,ALL`** on the `iree_djl` shim (already present, `native/CMakeLists.txt:51`). It answers the handover's §3.1 open question about symbol visibility — do not remove it.
- **Driver names are exact strings**: `"local-sync"`, `"local-task"`. Never `"local-sync://"`.
- **No local IREE tree.** `IREE_INSTALL` / `IREE_SOURCE` must be gone when this is done. Nothing may reference `/home/corey/workspace/iree` or `/home/corey/workspace/iree-build`.
- **`glibc_build: 2.28` in the manifest is NOT a compatibility floor** — it is the container the archives were compiled against. Our JNI `.so`'s floor is set by *our* link container.
- Platform: linux-x86_64 only. JDK 17 at `/usr/lib/jvm/zulu-17-amd64` (export `JAVA_HOME`; `find_package(JNI)` needs it). C++20 (`CMAKE_CXX_STANDARD 20` + `REQUIRED ON` must stay).
- Do not modify `/home/corey/workspace/iree` or `iree-build` (read-only; and after this migration, irrelevant).

---

### Task 1: Adopt the pin and swap the CMake seam

**Files:**
- Replace: `native/cmake/IreeRuntimePin.cmake` (stub → published asset)
- Delete: `native/cmake/ResolveIree.cmake`
- Modify: `native/CMakeLists.txt`, `native/build.sh`

**Interfaces:**
- Consumes: the published release assets
- Produces: CMake target `iree-runtime-dist::runtime`; variables `IREE_RUNTIME_DIST_ADD_VMFB`, `IREE_RUNTIME_DIST_ELEMENT_TYPES`, `IREE_RUNTIME_DIST_STATUS_CODES`, `IREE_RUNTIME_DIST_MANIFEST`, `IREE_RUNTIME_DIST_VERSION`, `IREE_RUNTIME_DIST_COMPILER_VERSION` — all consumed by Tasks 2–4

- [ ] **Step 1: Fetch and read the published pin (do not guess its interface)**

```bash
curl -fL -o /tmp/IreeRuntimePin.cmake \
  https://github.com/measly-java-learning/iree-runtime-dist/releases/download/v3.11.0-3/IreeRuntimePin.cmake
cat /tmp/IreeRuntimePin.cmake
```

**Read it before writing any CMake.** It determines how the tarball is fetched/extracted and what it sets (a prefix for `find_package`, or the `find_package` itself). Wire `native/CMakeLists.txt` to whatever it actually provides — the steps below assume it fetches+extracts and exposes a prefix, but the file is authoritative.

- [ ] **Step 2: Install the pin, delete the hand-rolled resolver**

```bash
cp /tmp/IreeRuntimePin.cmake native/cmake/IreeRuntimePin.cmake
git rm native/cmake/ResolveIree.cmake
```

- [ ] **Step 3: Rewrite the top of `native/CMakeLists.txt`**

Replace lines 10–11 (`list(APPEND CMAKE_MODULE_PATH ...)` + `include(ResolveIree)`) with the pin + `find_package`. Target shape (adapt to the pin's actual interface from Step 1):

```cmake
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}/cmake")
include(IreeRuntimePin)          # fetches + verifies the hash-pinned tarball
find_package(IreeRuntimeDist REQUIRED)
```

Then replace **every** `iree::runtime` with `iree-runtime-dist::runtime` (2 sites: `link_smoke_test`, `iree_djl_core`).

Delete nothing else — the sanitizer option, Catch2, all targets, and `-Wl,--exclude-libs,ALL` stay.

- [ ] **Step 4: Strip the local-tree knobs from `native/build.sh`**

Remove the `${IREE_INSTALL:+-DIREE_INSTALL=...}` and `${IREE_SOURCE:+-DIREE_SOURCE=...}` pass-throughs — those variables no longer exist. Everything else (build dir, build type, staging the `.so`) stays.

- [ ] **Step 5: Build from a clean tree and verify the link**

```bash
rm -rf native/build
JAVA_HOME=/usr/lib/jvm/zulu-17-amd64 ./native/build.sh
./native/build/link_smoke_test
```

Expected: `ok: local-sync device created`, exit 0.

If `find_package` fails at **configure** time complaining about `Threads`, that is the handover's §7 defect — the dist claims to repair it. **Report that loudly**; it means the repair regressed.

- [ ] **Step 6: Prove no local IREE tree is referenced**

```bash
grep -rn 'IREE_INSTALL\|IREE_SOURCE\|workspace/iree' native/ --include=*.cmake --include=*.txt --include=*.sh
```

Expected: **no matches**. This is the headline outcome — the engine no longer needs an IREE checkout or build tree.

- [ ] **Step 7: Commit**

```bash
git add -A native/
git commit -m "build(native): consume iree-runtime-dist v3.11.0-3

Replaces the hand-rolled ResolveIree.cmake (archive guessing, --start-group,
allocator define, split include dirs) with the published pin plus
find_package(IreeRuntimeDist) and the iree-runtime-dist::runtime umbrella
target. No IREE source or build tree is required to build the engine.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Flip the compiler to stable 3.11.0 and fix the fixtures

**Files:**
- Modify: `tools/export_add.sh`, `src/test/resources/models/add.vmfb`
- Modify: `native/CMakeLists.txt` (add the dist smoke-test define)
- Modify: `native/test/iree_runtime_test.cpp` (add a dist-fixture test case)

**Interfaces:**
- Consumes: `IREE_RUNTIME_DIST_ADD_VMFB` from Task 1
- Produces: an `add.vmfb` that loads against the dist runtime; a compiler-free smoke test over the dist's own fixture

Decision taken (flag if it proves wrong): **keep our f32 fixture** for the golden-vector/DJL tests (recompiled with 3.11.0) **and additionally** use the dist's `add.vmfb` as a compiler-free post-link smoke test. The dist's fixture is int32 per handover §5, so it is not a drop-in replacement for our f32 tests.

- [ ] **Step 1: Install the stable compiler, replacing the nightly**

```bash
uv pip install --python .venv 'iree-base-compiler==3.11.0'
.venv/bin/iree-compile --version
```

Expected: reports `3.11.0` (not a `3.12.0rc*` nightly).

- [ ] **Step 2: Update `tools/export_add.sh`**

Replace every reference to `3.12.0rc20260717` / nightlies / `--find-links` with stable `iree-base-compiler==3.11.0`. Rewrite the header comment: the runtime is now the **dist artifact** (`v3.11.0`, runtime commit `e4a3b040`), and the compiler pairs with it by the manifest's `iree_compile_version`. Delete the guidance about matching a `main` checkout — it is now actively wrong.

- [ ] **Step 3: Recompile our fixture and verify it loads against the dist runtime**

```bash
./tools/export_add.sh
rm -rf native/build && JAVA_HOME=/usr/lib/jvm/zulu-17-amd64 ./native/build.sh
./native/build/iree_runtime_test
```

Expected: all 8 Catch2 cases pass, golden vector still `[11,22,33,44]`.

**If the fixture fails to load**, the compiler/runtime pairing is broken — capture the exact error and report; do not work around it.

- [ ] **Step 4: Add a compiler-free smoke test over the dist's own fixture**

In `native/CMakeLists.txt`, pass the dist fixture path to the test target alongside ours:

```cmake
target_compile_definitions(iree_runtime_test PRIVATE
    IREE_DJL_ADD_VMFB="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/add.vmfb"
    IREE_DIST_ADD_VMFB="${IREE_RUNTIME_DIST_ADD_VMFB}")
```

Add a Catch2 case in `native/test/iree_runtime_test.cpp` that loads the **dist's** fixture and invokes it. Per handover §5 it is int32 and asserts `[11,22,33,44]`.

**First determine its actual signature and element type** — do not assume:

```bash
.venv/bin/iree-dump-module "$(grep -o '/[^"]*add\.vmfb' native/build/CMakeCache.txt | head -1)" | head -40
```

Note whether it uses `INT_32` (`0x10000020`) or `SINT_32` (`0x11000020`) — they are distinct in the dist's `element_types.json`, and our `IreeDataTypes` maps DJL `INT32 → SINT_32`. Write the test against the observed type and record which it is.

- [ ] **Step 5: Run the full native suite**

```bash
./native/build/iree_runtime_test
```

Expected: 9 cases (8 existing + the dist smoke test), all pass.

- [ ] **Step 6: Commit**

```bash
git add tools/export_add.sh src/test/resources/models/add.vmfb native/CMakeLists.txt native/test/iree_runtime_test.cpp
git commit -m "test: pair with stable iree-compile 3.11.0; add dist fixture smoke test

The dist runtime is v3.11.0 @ e4a3b040, so the model compiler flips from the
3.12 nightly to stable iree-base-compiler==3.11.0. Our f32 fixture is
recompiled; the dist's own add.vmfb is additionally exercised as a
compiler-free post-link smoke test.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Generate `IreeDataTypes.java` from the dist's `element_types.json`

**Files:**
- Modify: `build.gradle.kts`
- Delete: `src/main/java/org/measly/iree/engine/IreeDataTypes.java` (becomes generated)
- Create: the Gradle codegen task + its output under `build/generated/`

**Interfaces:**
- Consumes: `IREE_RUNTIME_DIST_ELEMENT_TYPES` (path to `element_types.json`) from Task 1
- Produces: a generated `org.measly.iree.engine.IreeDataTypes` with the same public API — `toIree(DataType)`, `fromIree(int)`, `FLOAT_32`, `SINT_32` — so `IreeSymbolBlock` and the tests compile unchanged

This kills the bug class that cost six tasks: `FLOAT_32` was hand-transcribed as `0x00000120` and stayed invisible until an output type was mapped back.

- [ ] **Step 1: Locate the JSON and confirm its shape**

The engine's Gradle build has no direct link to CMake, so resolve the path pragmatically: the extracted dist prefix lives under the CMake build tree. Find it and read the file:

```bash
find native/build -name element_types.json | head -1 | xargs cat | head -30
```

Record the JSON's actual structure (key names, whether values are decimal or hex). Handover §4 says 24 entries with `FLOAT_32 = 553648160 = 0x21000020`.

- [ ] **Step 2: Write the Gradle codegen task**

Add to `build.gradle.kts` a task that reads the JSON and emits `IreeDataTypes.java` into `build/generated/sources/iree/`, then wire it into `sourceSets.main.java.srcDir` and make `compileJava` depend on it. Emit the same public surface the hand-written class had:

```java
public static final int FLOAT_32 = <from json>;
public static final int SINT_32  = <from json>;
public static int toIree(DataType type)     // FLOAT32 -> FLOAT_32, INT32 -> SINT_32, else UnsupportedOperationException
public static DataType fromIree(int elementType)  // inverse, else UnsupportedOperationException
```

Locate the JSON via a Gradle property with a sensible default (the path found in Step 1), overridable by `-PireeElementTypes=/path/to/element_types.json`, so the build is not hostage to CMake internals.

- [ ] **Step 3: Delete the hand-written class and build**

```bash
git rm src/main/java/org/measly/iree/engine/IreeDataTypes.java
JAVA_HOME=/usr/lib/jvm/zulu-17-amd64 ./gradlew compileJava
```

Expected: compiles, using the generated class.

- [ ] **Step 4: Verify the generated values match the verified constants**

```bash
grep -E 'FLOAT_32|SINT_32' build/generated/sources/iree/org/measly/iree/engine/IreeDataTypes.java
```

Expected: `FLOAT_32 = 553648160` (`0x21000020`) and `SINT_32 = 285212704` (`0x11000020`). **If these differ from the values the skeleton verified, stop and report** — that is a contradiction between the dist's generated JSON and our header-derived values.

- [ ] **Step 5: Run the JVM suite**

```bash
JAVA_HOME=/usr/lib/jvm/zulu-17-amd64 ./gradlew test
```

Expected: 5 tests pass (IreeNativeTest ×4 + AddModelIT ×1).

- [ ] **Step 6: Commit**

```bash
git add -A build.gradle.kts src/main/java
git commit -m "feat(engine): generate IreeDataTypes from the dist element_types.json

Replaces the hand-transcribed element-type constants with codegen from the
dist's generated manifest. This is the structural fix for the bug class that
had FLOAT_32 wrong (0x00000120) and invisible for six tasks.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

> `status_codes.json` is deliberately **not** consumed here. Using it for typed Java exceptions is a behaviour change to the engine's error model, not a migration step — deferred (see Task 5 docs).

---

### Task 4: Re-establish the threading claim under TSan

**Files:**
- Modify: `native/CMakeLists.txt` (add a TSan option)
- Create: `.superpowers/sdd/task-4-tsan-evidence.txt` (scratch; not committed)

**Interfaces:**
- Consumes: the leak harness and facade from Task 1
- Produces: an empirical answer to handover §3.3, feeding the doc updates in Task 5

**Why:** the skeleton's "no TSan leg" rested on a *structural* guarantee — `local-sync` meant no threads existed. The dist ships `IREE_ENABLE_THREADING=ON` with `local-task` compiled in, so that guarantee is now *empirical*: selecting `local-sync` at runtime should still spawn no worker threads, but the handover flags this as untested and our call.

- [ ] **Step 1: Add a TSan build option**

In `native/CMakeLists.txt`, alongside `IREE_DJL_SANITIZE`:

```cmake
option(IREE_DJL_TSAN "Build with ThreadSanitizer" OFF)
if(IREE_DJL_TSAN)
  add_compile_options(-fsanitize=thread -fno-omit-frame-pointer -g)
  add_link_options(-fsanitize=thread)
endif()
```

ASan and TSan are mutually exclusive — never enable both.

- [ ] **Step 2: Build and run the harness under TSan**

```bash
rm -rf native/build
BUILD_TYPE=RelWithDebInfo JAVA_HOME=/usr/lib/jvm/zulu-17-amd64 ./native/build.sh -DIREE_DJL_TSAN=ON
TSAN_OPTIONS=halt_on_error=0 ./native/build/iree_leak_harness "" 100 2>&1 | tee /tmp/tsan-out.txt
tail -20 /tmp/tsan-out.txt
```

Expected: `HARNESS PASS`, and **no** `WARNING: ThreadSanitizer: data race`.

- [ ] **Step 3: Confirm no worker threads are actually created with `local-sync`**

The facade only ever creates a `local-sync` device. Verify empirically that no IREE worker threads spawn:

```bash
grep -ci 'ThreadSanitizer' /tmp/tsan-out.txt || echo "0 TSan reports"
```

Interpretation:
- **Clean** → runtime `local-sync` selection preserves the thread-free property despite `local-task` being linked in. The docs' claim survives, restated as empirical rather than structural.
- **Races reported** → capture them; this is a real finding for both our docs and the dist project.

- [ ] **Step 4: Restore a plain build (TSan artifacts must not ship)**

```bash
rm -rf native/build && JAVA_HOME=/usr/lib/jvm/zulu-17-amd64 ./native/build.sh
JAVA_HOME=/usr/lib/jvm/zulu-17-amd64 ./gradlew test
```

Expected: 5 JVM tests pass with the plain (uninstrumented) `.so` staged. (A sanitizer-instrumented `.so` breaks `gradle test` with "ASan/TSan runtime does not come first".)

- [ ] **Step 5: Commit**

```bash
git add native/CMakeLists.txt
git commit -m "test(native): add a TSan option and verify the local-sync threading claim

The dist ships IREE_ENABLE_THREADING=ON with local-task compiled in, so the
skeleton's structural 'no threads' guarantee becomes empirical. Result: <clean|races>.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Correct the docs and write the usability report

**Files:**
- Modify: `docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md`
- Modify: `docs/superpowers/specs/iree-runtime-dist-wishlist.md`
- Modify: `README.md`
- Create: `docs/2026-07-20-iree-runtime-dist-usability-report.md`

**Interfaces:**
- Consumes: findings and friction from Tasks 1–4
- Produces: accurate docs, and the feedback the dist project asked for

- [ ] **Step 1: Correct `iree-runtime-dist-wishlist.md` (three items are now factually wrong)**

- **#2** — RPATH/`patchelf` scrubbing was framed as the dist's job. The dist ships **0 `.so`, 198 `.a`**; our JNI shim is the only shared object, so RPATH hygiene is **ours**. Keep the CMake-config relocatability point (it was delivered and is gated).
- **#8** — `glibc_build: "2.28"` is **not a floor**; it is the container the archives were built against. Static archives carry unversioned undefined libc symbols, so glibc symbol-version resolution happens at *our* final link. Our shim's floor is set by *our* link container. (The two surviving observations — floor unconstrained by torch, clang independent of container glibc — stay.)
- **#9** — "IREE vendors LLVM, flatcc, and more" is wrong for this artifact: notices are exactly `flatcc/`, `libbacktrace/`, `printf/`, and **no LLVM** (measured: zero LLVM/MLIR symbols across all 198 archives, because `IREE_BUILD_COMPILER=OFF`). Copy the directory as-is; do not derive from IREE's submodule list.
- **§8 libstdc++ premise** — the conclusion holds but the premise was wrong: 385 undefined C++ symbols exist, confined to `libbenchmark.a` and `libiree_testing_benchmark.a`, neither in the umbrella target's closure. Restate as: *link the umbrella target and your libstdc++ story is yours alone*.
- Mark delivered items (#1, #2 config, #3 manifest, #4, #5, #6) as **delivered in v3.11.0-3**, and note what is still open (`devtools`/Tracy, `gpu`, `windows`, `minimal`).

- [ ] **Step 2: Correct the findings doc**

- **Threading/TSan**: replace the structural claim ("no TSan leg because `local-sync` has no internal threads") with the empirical result from Task 4, noting `IREE_ENABLE_THREADING=ON` and `local-task` are now linked in.
- **Version alignment**: the saga is *dissolved, not solved* — the dist anchors on stable `v3.11.0` (runtime `e4a3b040` + compiler `3.11.0`), so the nightly-chasing narrative is superseded. Keep the historical explanation (it is why the pairing contract matters) but mark the resolution.
- **Runtime API choice** section: unchanged, still accurate.

- [ ] **Step 3: Rewrite the README prerequisites — the headline win**

The engine no longer needs an IREE source tree, an IREE build tree, or a compiler. Prerequisites shrink to: JDK 17 (`JAVA_HOME`), cmake/ninja/gcc, and network access for the pinned tarball. `iree-base-compiler==3.11.0` is needed **only** to regenerate the test fixture. Remove all `IREE_INSTALL`/`IREE_SOURCE` instructions. Add the TSan command next to the ASan gate, and keep the plain-rebuild note.

- [ ] **Step 4: Write the usability summary (an index of already-filed issues)**

Individual findings were filed as GitHub issues as they were discovered (see the plan's secondary-goal section). This step writes the *overall* verdict and answers the dist project's open questions — it does **not** re-litigate each finding.

Create `docs/2026-07-20-iree-runtime-dist-usability-report.md`, addressed to `measly-java-learning/iree-runtime-dist`. Structure:

- **Verdict** — did the artifact deliver? Keep it, or is it genuinely not helpful? (This is the question that motivated migrating now.)
- **Index of filed issues** — number, title, one-line severity/impact. Confirm each was filed at discovery time with full context.
- **What worked without friction** (be specific — this is as useful as the complaints).
- **Answers to their two open questions** — file these as issues/comments too if they warrant tracking:
  1. §3.1 symbol visibility — we link the archives into a JNI `.so` with `-Wl,--exclude-libs,ALL`; report whether that suffices and any observed collision.
  2. §3.3 TSan with runtime `local-sync` selection — report Task 4's empirical result.
- **Corrections to the handover**, if any of its claims did not hold.
- **Requests**, prioritized: `devtools` (Tracy/alloc-stats) blocks the latency/footprint work; then `windows-x86_64`; `gpu` and `minimal` lower.

- [ ] **Step 5: Full verification before committing docs**

```bash
rm -rf native/build && JAVA_HOME=/usr/lib/jvm/zulu-17-amd64 ./native/build.sh
./native/build/iree_runtime_test
./native/build/iree_leak_harness "" 50
JAVA_HOME=/usr/lib/jvm/zulu-17-amd64 ./gradlew test
```

Expected: native cases pass, `HARNESS PASS`, 5 JVM tests pass.

- [ ] **Step 6: Commit**

```bash
git add -A docs/ README.md
git commit -m "docs: correct for iree-runtime-dist v3.11.0-3 + usability report

Corrects three now-wrong wishlist items (glibc_build is not a floor, RPATH is
ours since the dist ships no .so, no LLVM in the notices), restates the
threading claim empirically, and rewrites README prerequisites — the engine
no longer needs an IREE source tree, build tree, or compiler.

Adds a usability report answering the dist project's two open questions.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Handover coverage.** §1 pin → Task 1. §2 `find_package` + delete `ResolveIree` → Task 1. §3.1 static-only/RPATH/visibility → Task 1 (keep `--exclude-libs`) + Task 5 report. §3.2 `glibc_build` not a floor → Task 5. §3.3 one variant/threading → Task 4 + Task 5. §3.4 compiler pairing → Task 2. §3.5 notices → Task 5. §4 constants → Task 3. §5 `add.vmfb` → Task 2. §6 libstdc++ correction → Task 5. §7 lessons (exact driver names ✓ already correct; no `--start-group`; `impl.a` trap; Threads repair) → Task 1 constraints. §8 open items → Task 5 requests.

**Deliberately out of scope:** `status_codes.json` → typed Java exceptions (a behaviour change, not a migration step); requesting the `devtools`/`gpu`/`windows` variants (recorded as requests, not built).

**Type consistency.** The generated `IreeDataTypes` preserves `toIree`/`fromIree`/`FLOAT_32`/`SINT_32`, so `IreeSymbolBlock` and existing tests compile unchanged. `iree::runtime` → `iree-runtime-dist::runtime` at both use sites.

**Risk flagged inline, with instructions to report rather than work around:** the pin's actual interface (Task 1 Step 1 reads it first), a `Threads` configure failure (Task 1 Step 5), fixture load failure after the compiler flip (Task 2 Step 3), the dist fixture's element type (Task 2 Step 4), JSON shape (Task 3 Step 1), and constant mismatch (Task 3 Step 4).
