# UBSan and JNI Contract Checking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three QA gates — UndefinedBehaviorSanitizer over the native QA tree, UBSan over the JNI shim driven by the JVM suite, and `-Xcheck:jni` over every JVM test task — each blocking in CI only after a clean local baseline.

**Architecture:** Gate A composes `-fsanitize=undefined` onto the existing ASan QA tree via a new `IREE_DJL_UBSAN` CMake option, needing no new build tree. Gate B adds a `native/ubsan/` tree whose shim links the UBSan runtime statically (`-static-libubsan`), so a stock JVM can `dlopen` it through the existing `IREE_LIBRARY_PATH` seam with no preload. Gate C attaches `-Xcheck:jni` to the `tasks.withType<Test>()` umbrella so it covers `test`, `leakTest`, `oomTest` and `stressTest`.

**Tech Stack:** CMake 3.22+, GCC 13.3 (`-fsanitize=undefined`, `-static-libubsan`), Gradle Kotlin DSL, JUnit 5 tags, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-08-12-ubsan-and-jni-checking-design.md`

## Global Constraints

- **JDK 17.** `JAVA_HOME=/usr/lib/jvm/zulu-17-amd64` on this host. Export it before any Gradle or native command.
- **Gradle cannot run inside the pinned container.** The images set `JAVA_HOME=/opt/corretto-jdk`, which is Corretto **1.8.0_502** — chosen deliberately for the oldest supported `jni.h`. The wrapper is Gradle **9.6.1** and `build.gradle.kts:17` sets a JDK 17 toolchain, so any Gradle invocation in the container fails before it starts. Native builds run in the container; JVM test runs never do. Gate B straddles that line and is split into two phases for exactly this reason.
- **`./gradlew test` reporting `UP-TO-DATE` has not run anything.** Use `--rerun-tasks` whenever the point is to verify, and check for `N actionable tasks: N executed`.
- **Never stage an instrumented library into `src/main/resources`.** Gate B's library is reached via `IREE_LIBRARY_PATH` only. After any sanitizer work, rebuild plain with `./native/build.sh` before running JVM tests normally.
- **UBSan is Linux-only.** MSVC has no UndefinedBehaviorSanitizer. Every Windows code path stays exactly as it is.
- **UBSan check set:** `-fsanitize=undefined,float-cast-overflow,float-divide-by-zero` with `-fno-sanitize=vptr`.
- **UBSan must abort, not warn:** `-fno-sanitize-recover=undefined` at compile and link; `UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1` at run.
- **GCC has no UBSan ignorelist.** `-fsanitize-ignorelist` and `-fsanitize-blacklist` are unrecognized options, and `UBSAN_OPTIONS=suppressions=` does not suppress these checks. Use `__attribute__((no_sanitize("undefined")))`, a per-TU `set_source_files_properties` override, or a documented `-fno-sanitize=<check>` — each with a written justification at the site.
- **No emoji** in `README.md`, `CONTRIBUTING.md`, `CLAUDE.md`, or anything under `docs/`.
- **`./gradlew javadoc` must stay at zero warnings.** Any new public Java type needs javadoc.
- **Never commit** `native/build-clangd/`, an instrumented `.so`/`.dll`, or the temporary UB probes this plan uses for verification.
- **Every build and test command in this plan runs under the resource-containment wrapper below.** Not optional: this plan runs `oomTest`, which exhausts a heap on purpose.

---

## Resource containment (required)

A runaway test on this project and on `djl-executorch-engine` has more than once triggered a host-wide OOM kill that took down unrelated processes — typically Firefox and the shell hosting the agent. This plan is unusually exposed to that: `oomTest` drives allocation failure deliberately, `stressTest` is a concurrency suite, and Tasks 2 and 3 run parallel native builds. Contain every build and test invocation:

```bash
# Prefix for any build or test command in this plan.
systemd-run --user --scope -p MemoryMax=4G taskset -c 0-3 timeout 900 bash -c '<cmd>'
```

`taskset -c 0-3` also caps build parallelism for free: `nproc` honours CPU affinity, so `-j"$(nproc)"` inside the scope resolves to 4 rather than 8 (measured on this host).

**Gradle escapes this, and must be handled explicitly.** The Gradle daemon is a long-lived process in whatever cgroup it was first started in. If one is already running, `./gradlew` connects to it and the real work — including every forked test JVM — happens in *that* daemon's scope, outside the one you just created. `gradle.properties:5` sets `org.gradle.parallel=true`, so worker processes fork on top of the test JVMs, compounding it. Before any Gradle command in this plan:

```bash
./gradlew --stop     # kill any daemon living outside the scope
```

and run Gradle inside the scope with `--no-daemon`, so the build executes in the CLI process itself and forked test JVMs inherit its cgroup as children:

```bash
systemd-run --user --scope -p MemoryMax=4G taskset -c 0-3 timeout 900 \
  bash -c './gradlew --no-daemon test --rerun-tasks'
```

**Verify containment once, at the start**, rather than assuming it held:

```bash
# In one terminal, inside the scope:
systemd-run --user --scope -p MemoryMax=4G taskset -c 0-3 timeout 900 \
  bash -c './gradlew --no-daemon test --rerun-tasks'

# In another, while it runs -- the test JVM must name a run-*.scope:
pgrep -f 'GradleWorkerMain|GradleDaemon' | while read -r pid; do
  printf '%s: %s\n' "$pid" "$(cat /proc/"$pid"/cgroup)"
done
```

Expected: every listed PID's cgroup path contains `run-<id>.scope`. A PID showing `app.slice` or a bare user slice has escaped — stop, run `./gradlew --stop`, and confirm `--no-daemon` is on the command line.

**Two adjustments to the defaults:**

- **`timeout 900` is too short for a cold native build.** Task 2 and Task 4 configure from scratch, and `FetchContent` pulls the pinned runtime tarball (and Catch2, for the QA tree) before compiling. Use `timeout 1800` for the first `./native/build.sh`, `./native/build_qa.sh` and `./native/ubsan_gate.sh` of a session; 900 is fine once the trees are warm.
- **`MemoryMax=4G` bounds the whole scope, not each JVM.** `ubsan_gate.sh` runs four test tasks in sequence, and `oomTest` deliberately pushes to its `-Xmx128m` ceiling — well inside 4G. If the scope OOMs anyway, that is a finding about the gate, not a reason to raise the cap: report it rather than retrying with `MemoryMax=8G`.

If `systemd-run --user` is unavailable in a given environment (notably inside the CI containers, which have their own limits), say so and fall back to `taskset -c 0-3 timeout 900` alone rather than running unbounded.

### Containers need their own limits — the scope does not reach them

**A `systemd-run --user --scope` wrapped around `docker run` contains nothing that matters.** This host runs the standard root Docker daemon (`docker info` reports no rootless mode), so container processes are children of the daemon's `containerd-shim` in the system slice, not of the invoking shell. The scope bounds only the short-lived `docker run` client. Anything run through `native/local_build_wrapper.sh` — which is the blessed way to run the native scripts, and what Task 5 uses — is therefore **unbounded unless the container is limited directly**.

Constrain the container itself:

```bash
docker run --rm \
    --memory=8g --memory-swap=8g \
    --cpuset-cpus=0-3 \
    ...
```

- **`--memory=8g`, not 4g.** The container runs a parallel C++ build (including two roughly 16 MB Catch2 test binaries) and, for `ubsan_gate.sh`, Gradle plus four test-task JVMs. The host has 31 GiB total, so 8g is affordable, and 4g risks OOM-killing a legitimate build — which would present as a gate failure rather than as a resource limit, and send the implementer debugging the wrong thing.
- **`--memory-swap=8g`** (equal to `--memory`) disables swap for the container. Without it Docker grants an equal amount of swap by default, so the limit does not really bind and the box thrashes instead of failing fast.
- **`--cpuset-cpus=0-3`** mirrors the host `taskset` and caps build parallelism for free: `nproc` inside the container resolves to 4, so `build_qa.sh`'s `JOBS="${JOBS:-$(nproc)}"` and this plan's `-j"$(nproc)"` both follow.

A container OOM kill terminates processes inside the container only. That is the entire point: it converts a host-wide event that takes down Firefox and the agent's shell into a contained, legible build failure.

`docker build` is a separate invocation with its own `--memory` flag, but the Dockerfiles only install a toolchain — they do not compile this project — so it is left alone.

---

### Task 1: Gate C — `-Xcheck:jni` on every Test task

**Files:**
- Modify: `build.gradle.kts:226-231` (the `tasks.withType<Test>().configureEach` block)
- Create: `src/test/java/org/measly/iree/JniCheckFlagTest.java`
- Create: `src/test/java/org/measly/iree/JniCheckFlagTaggedTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks; this is the first.
- Produces: `-Xcheck:jni` present in the JVM arguments of every `Test` task. Tasks 3 and 4 rely on this being active during Gate B's runs — Gate B inherits it for free rather than setting it again.

**Why two test classes:** `tasks.test` uses `excludeTags("leak", "oom", "stress")` while `leakTest`/`oomTest`/`stressTest` each use `includeTags(...)`. No single class can run under all four: an untagged class is skipped by the three tag-filtered tasks, and a tagged class is excluded from `test`. So an untagged base class covers `test`, and a tagged subclass inherits its `@Test` method to cover the other three.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/measly/iree/JniCheckFlagTest.java`:

```java
package org.measly.iree;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Asserts the JVM under test runs with {@code -Xcheck:jni}, the JNI-contract
 * checker (issue #16's defect class: JNI calls made with a pending exception,
 * null array arguments). The flag is attached to the {@code Test} task umbrella
 * in {@code build.gradle.kts}, so this assertion must hold for every test task,
 * not just {@code test} — see {@link JniCheckFlagTaggedTest}, which inherits
 * this check into the tag-filtered tasks.
 *
 * <p>This asserts the checker is <em>active</em> rather than that it fires:
 * {@code IreeNativeOomTest} documents that the null-check branches are not
 * deterministically reachable, so a fire-on-demand probe cannot be a gate.
 */
class JniCheckFlagTest {

    @Test
    void jvmRunsWithXcheckJni() {
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        assertTrue(
                args.contains("-Xcheck:jni"),
                "test JVM must run with -Xcheck:jni; actual JVM arguments: " + args);
    }
}
```

Create `src/test/java/org/measly/iree/JniCheckFlagTaggedTest.java`:

```java
package org.measly.iree;

import org.junit.jupiter.api.Tag;

/**
 * Carries {@link JniCheckFlagTest}'s inherited assertion into the tag-filtered
 * test tasks. {@code tasks.test} excludes these three tags and the three tasks
 * each include exactly one, so no single class can run under all four; this
 * subclass is how the umbrella attachment gets proven where it matters most,
 * including {@code oomTest}.
 */
@Tag("leak")
@Tag("oom")
@Tag("stress")
class JniCheckFlagTaggedTest extends JniCheckFlagTest {}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/zulu-17-amd64
./gradlew test --rerun-tasks --tests 'org.measly.iree.JniCheckFlagTest'
```

Expected: FAIL — `test JVM must run with -Xcheck:jni; actual JVM arguments: [-XX:+HeapDumpOnOutOfMemoryError, ...]`

- [ ] **Step 3: Attach the flag to the Test umbrella**

In `build.gradle.kts`, replace the existing block at lines 226-231:

```kotlin
// LibUtils resolves the native library from IREE_LIBRARY_PATH before falling
// back to the classpath copy, so this variable changes WHICH .so is under test.
// Undeclared, it is invisible to the up-to-date check: point it elsewhere and
// Gradle would replay a cached pass for a run that loaded something else.
tasks.withType<Test>().configureEach {
    inputs.property(
        "ireeLibraryPath",
        providers.environmentVariable("IREE_LIBRARY_PATH").orElse("")
    )
    // -Xcheck:jni is the only lever that catches issue 16's defect class: JNI
    // calls made with a pending exception, and null array arguments. It is on the
    // umbrella rather than on tasks.test deliberately -- tasks.test excludes the
    // leak/oom/stress tags, and oomTest is the one task that drives the
    // allocation-failure paths those bugs lived on. Costs nothing: it runs
    // against the plain shipping library.
    jvmArgs("-Xcheck:jni")
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew test --rerun-tasks --tests 'org.measly.iree.JniCheckFlagTest'
```

Expected: PASS. Confirm the output shows `N actionable tasks: N executed`, not `up-to-date`.

- [ ] **Step 5: Baseline the full suite under the checker, `oomTest` first**

Per the spec's rollout order, `oomTest` is the task most likely to need attention: `-Xcheck:jni` adds bookkeeping inside a deliberate 128 MiB heap, so it may shift where the OOM lands, and it aborts the VM on a violation where the test expects a clean `OutOfMemoryError`.

This is the single most dangerous step in the plan for the host — `oomTest` exhausts a heap on purpose. Run it contained, and note the `--no-daemon`:

```bash
./gradlew --stop
systemd-run --user --scope -p MemoryMax=4G taskset -c 0-3 timeout 900 \
  bash -c './gradlew --no-daemon oomTest --rerun-tasks'
systemd-run --user --scope -p MemoryMax=4G taskset -c 0-3 timeout 900 \
  bash -c './gradlew --no-daemon test leakTest stressTest --rerun-tasks'
```

Expected: all pass. `oomTest` requires the pinned pip `iree-compile` on PATH for its `exportOomFixture` dependency.

If `-Xcheck:jni` reports a warning or aborts the VM, that is a real finding: **stop and fix the shim**, do not weaken the gate. Record what it found — it is the first genuine catch of this defect class by tooling rather than review.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src/test/java/org/measly/iree/JniCheckFlagTest.java \
        src/test/java/org/measly/iree/JniCheckFlagTaggedTest.java
git commit -m "test: run every JVM test task under -Xcheck:jni

Attaches the JNI-contract checker to the Test umbrella rather than
tasks.test: tasks.test excludes the leak/oom/stress tags, and oomTest is
the only task driving the allocation-failure paths issue 16 lived on.

Asserts the flag is active from inside the test JVM, in both an untagged
and a tagged class, since no single class can run under all four tasks."
```

---

### Task 2: Gate A — UBSan on the native QA tree

**Files:**
- Modify: `native/CMakeLists.txt:101-128` (sanitizer option block)
- Modify: `native/build_qa.sh:73-90` (Linux branch: toolchain package and cmake invocation)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: CMake option `IREE_DJL_UBSAN` (BOOL, default `OFF`) and cache variable `IREE_DJL_UBSAN_CHECKS` (STRING, default `undefined,float-cast-overflow,float-divide-by-zero`). Task 4 turns the same option on for a different tree and adds `-static-libubsan` on top.

> **Superseded in part by Task 3.** Step 6 of this task extended `build_qa.sh`'s `dnf install` fallback to UBSan. That was wrong: libubsan is not baked into the pinned images, so inside the container the `rpm -q` guard fails and an **unpinned** `gcc-toolset-14-libubsan-devel` gets installed on every run — defeating the NEVRA pinning `431f649` established. Task 3 bakes the runtime into the images and converts the scripts from installing to asserting. Do not replicate Step 6's pattern anywhere else.

- [ ] **Step 1: Add the CMake option**

In `native/CMakeLists.txt`, immediately after the existing `option(IREE_DJL_TSAN ...)` at line 102, add:

```cmake
option(IREE_DJL_UBSAN "Build with UndefinedBehaviorSanitizer" OFF)

# The check set, as a cache variable so a one-off run can narrow it without
# editing this file. Two additions over GCC's `undefined` umbrella, which
# deliberately excludes both: float-cast-overflow and float-divide-by-zero, each
# reachable in element-count and scale arithmetic. vptr comes off below: it needs
# every TU holding a polymorphic object instrumented, and the RAII handles in
# core/iree_handles.h are non-polymorphic, so it buys nothing here and is the
# check most likely to misfire against the uninstrumented dist.
set(IREE_DJL_UBSAN_CHECKS "undefined,float-cast-overflow,float-divide-by-zero"
    CACHE STRING "UBSan check set passed to -fsanitize=")
```

- [ ] **Step 2: Add the flag block**

In `native/CMakeLists.txt`, after the existing `if(IREE_DJL_TSAN)` block (which ends at line 128), add:

```cmake
# UBSan composes with ASan -- unlike the ASan/TSan pair above, which is mutually
# exclusive. It is also per-TU and local: each check is an inline test emitted at
# the operation, with no cross-module state, so linking the uninstrumented dist
# produces no false positives and hides nothing in our own code.
#
# -fno-sanitize-recover is what makes this a gate rather than a log: UBSan's
# default is print-and-continue, under which CI stays green while diagnostics
# scroll past.
#
# NOTE: GCC has no ignorelist -- -fsanitize-ignorelist and -fsanitize-blacklist
# are both unrecognized, and UBSAN_OPTIONS=suppressions= does not suppress these
# checks (measured, gcc 13.3). To silence a diagnostic, use
# __attribute__((no_sanitize("undefined"))) on the function, a per-TU
# set_source_files_properties COMPILE_OPTIONS override, or -fno-sanitize=<check>
# here -- each with a comment naming what was given up and why.
if(IREE_DJL_UBSAN)
  if(WIN32)
    message(FATAL_ERROR "IREE_DJL_UBSAN is unsupported on Windows: MSVC has no UndefinedBehaviorSanitizer")
  endif()
  add_compile_options(
      -fsanitize=${IREE_DJL_UBSAN_CHECKS}
      -fno-sanitize=vptr
      -fno-sanitize-recover=undefined
      -fno-omit-frame-pointer -g)
  add_link_options(-fsanitize=${IREE_DJL_UBSAN_CHECKS})
endif()
```

- [ ] **Step 3: Verify the option configures and builds**

```bash
rm -rf native/qa-ubsan-probe
cmake -B native/qa-ubsan-probe -S native -G "Unix Makefiles" \
  -DIREE_DJL_SANITIZE=ON -DIREE_DJL_UBSAN=ON -DCMAKE_BUILD_TYPE=Debug
cmake --build native/qa-ubsan-probe --target iree_leak_harness -j"$(nproc)"
```

Expected: configures and builds clean. ASan and UBSan together must not error — that combination is the whole point of Gate A.

- [ ] **Step 4: Verify UBSan actually fires and aborts**

Temporarily add a deliberate UB expression at the top of `main` in `native/harness/iree_leak_harness.cpp`:

```cpp
  // TEMPORARY UBSan probe -- revert before committing.
  {
    int* probe = nullptr;
    if (argc > 99) { return *probe; }
    volatile int shift_probe = 1;
    (void)(shift_probe << 99);
  }
```

Then:

```bash
cmake --build native/qa-ubsan-probe --target iree_leak_harness -j"$(nproc)"
UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1 \
  ./native/qa-ubsan-probe/iree_leak_harness src/test/resources/models/add.vmfb 1
echo "exit=$?"
```

Expected: a `runtime error: shift exponent 99 is too large` diagnostic with a stack trace, and a **nonzero** exit. If the exit is 0, `-fno-sanitize-recover` is not taking effect — fix that before proceeding.

- [ ] **Step 5: Revert the probe**

```bash
git checkout native/harness/iree_leak_harness.cpp
git diff --stat native/harness/iree_leak_harness.cpp   # must be empty
rm -rf native/qa-ubsan-probe
```

The probe must never be committed.

- [ ] **Step 6: Wire it into build_qa.sh**

In `native/build_qa.sh`, in the Linux branch only, extend the toolchain fallback at lines 73-79 to install the UBSan runtime alongside ASan's:

```bash
  # QA is the only ASan/UBSan consumer. The pinned toolchain image bakes the runtimes in at
  # the base image's own compiler revision; this dnf call is the fallback for host runs and
  # bare bases.
  TOOLSET_VER="$(gcc -dumpversion | cut -d. -f1)"
  for _san in asan ubsan; do
    if rpm -q --quiet "gcc-toolset-${TOOLSET_VER}-lib${_san}-devel"; then
      echo "--- ${_san} runtime already present (gcc-toolset-${TOOLSET_VER}-lib${_san}-devel) ---"
    elif command -v dnf >/dev/null 2>&1; then
      echo "--- Installing ${_san} runtime (dnf), may appear to hang ---"
      dnf install -y -q "gcc-toolset-${TOOLSET_VER}-lib${_san}-devel" || true
    fi
  done
```

Then add `-DIREE_DJL_UBSAN=ON` to the Linux cmake invocation at line 83 and the UBSan flags to the explicit flag strings:

```bash
  cmake -B native/qa -S native -G "Unix Makefiles" -DIREE_DJL_SANITIZE=ON -DIREE_DJL_UBSAN=ON \
    -DCMAKE_BUILD_TYPE=Debug \
    -DCMAKE_CXX_FLAGS="-fsanitize=address -fno-omit-frame-pointer -g" \
    -DCMAKE_EXE_LINKER_FLAGS="-fsanitize=address"
```

The UBSan flags themselves come from the CMake block, not from `CMAKE_CXX_FLAGS` — leave those strings carrying only ASan, matching how `IREE_DJL_SANITIZE` already works.

Finally, export the runtime options once near the top of the Linux branch, just before the `cmake -B native/qa` line:

```bash
  # UBSan's default is print-and-continue; -fno-sanitize-recover (set in
  # native/CMakeLists.txt) makes it abort, and these make the abort legible.
  export UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1
```

- [ ] **Step 7: Run the full QA gate**

```bash
./native/build_qa.sh
```

Expected: `--- native QA PASS ---`. Any UBSan diagnostic here is a real finding — triage it against the suppression rules in Global Constraints before suppressing anything.

- [ ] **Step 8: Confirm the Windows branch is untouched**

```bash
git diff native/build_qa.sh | grep -n '^[+-]' | grep -i 'windows\|MSVC\|cl.exe\|/fsanitize'
```

Expected: no output. The Windows branch must be byte-identical.

- [ ] **Step 9: Commit**

```bash
git add native/CMakeLists.txt native/build_qa.sh
git commit -m "build(native): add UBSan to the QA gate

IREE_DJL_UBSAN composes onto the existing ASan tree -- UBSan is per-TU and
local, so it needs neither its own build nor an instrumented runtime.
-fno-sanitize-recover makes it a gate rather than a log.

vptr is dropped (nothing here is polymorphic); float-cast-overflow and
float-divide-by-zero are added, since GCC excludes both from the umbrella.
Linux only: MSVC has no UBSan."
```

---

### Task 3: Pin the UBSan runtime in the images; stop the scripts installing tools

**Files:**
- Modify: `docker/linux-x86_64.Dockerfile` (bake libubsan; export pin markers)
- Modify: `docker/linux-aarch64.Dockerfile` (same)
- Modify: `native/build_qa.sh` (the sanitizer-runtime block added by Task 2 Step 6)
- Modify: `native/build.sh:74` (the `pip install ninja` fallback)

**Interfaces:**
- Consumes: the `IREE_DJL_UBSAN` wiring from Task 2.
- Produces: image environment variables `IREE_DJL_PINNED_IMAGE=1`, `IREE_DJL_TOOLSET_VER`, `IREE_DJL_TOOLSET_NEVRA`, `IREE_DJL_NINJA_VERSION`. Tasks 4 and 5 rely on the pinned images carrying a UBSan runtime; without this task, the CI job's UBSan build has nothing to link against.

**The defect.** The images go to real lengths to pin — a dated base tag, the exact NEVRA `gcc-toolset-14-libasan-devel-14.2.1-11.el8_10`, `ninja==1.13.0`, a versioned Corretto URL with a SHA-256, and image-build-time assertions. The scripts then undo it at run time:

1. `build_qa.sh`'s guard is `rpm -q --quiet "gcc-toolset-${VER}-lib${_san}-devel"` — a **bare package name**, so any version satisfies it. The NEVRA the image pins is never verified.
2. libubsan is not baked into either image, so that guard **fails** inside the container and `dnf install -y -q` pulls an unpinned libubsan on every run, with `|| true` swallowing any failure. This is live now: Task 2 Step 6 introduced it.
3. `build.sh:74` runs `command -v ninja || pip install ninja` — unpinned, against an image that pins `ninja==1.13.0`.
4. Nothing distinguishes "running in the pinned image" from "running on a bare host", so a fallback meant for host runs silently becomes drift inside the container.

5. The host branch is **dead code**. Both the workstation and the GitHub runner are Ubuntu, where `rpm` is command-not-found and `command -v dnf` is false — so under `set -euo pipefail` the loop falls through silently, installing nothing and printing nothing. The "fallback for host runs" has never run on the only host that exists.

**The fix**: bake the runtime in with the same NEVRA discipline, have the image announce its own pins, and make the scripts *assert* rather than install — the pinned NEVRA inside the image, and a distro-agnostic link probe outside it. **No script installs a tool on any path.** Inside the image an install defeats the pinning; outside it, an install is unpinned by construction and silently changes what the run measures.

- [ ] **Step 1: Resolve the real NEVRA — do not guess it**

The version must match the base image's compiler exactly; a libubsan from a different toolset revision than the gcc that emitted the instrumentation is the same class of confusing link error the Dockerfile comment already warns about for ASan.

```bash
docker run --rm quay.io/pypa/manylinux_2_28_x86_64:2026.06.04-1 \
  bash -c 'dnf list --showduplicates gcc-toolset-14-libubsan-devel 2>/dev/null | tail -5; gcc -dumpversion'
```

Expect `14.2.1-11.el8_10` to be available, matching the pinned libasan. **If it is not, stop and report** — do not pin a different revision than libasan. Repeat for `manylinux_2_28_aarch64` before editing that Dockerfile.

- [ ] **Step 2: Bake libubsan into both Dockerfiles**

In `docker/linux-x86_64.Dockerfile`, extend the existing `RUN dnf install` and its comment:

```dockerfile
# gcc-toolset-14-libubsan-devel  the UBSan runtime for native/build_qa.sh and
#                      native/ubsan_gate.sh. Same NEVRA as libasan above and for the same
#                      reason: it must match the gcc that emitted the instrumentation.
RUN dnf install -y \
      gcc-toolset-14-libasan-devel-14.2.1-11.el8_10 \
      gcc-toolset-14-libubsan-devel-14.2.1-11.el8_10 \
    && dnf clean all \
    && rm -rf /var/cache/dnf
```

Apply the identical change to `docker/linux-aarch64.Dockerfile`, using the NEVRA resolved for that base in Step 1.

- [ ] **Step 3: Have the image announce its own pins**

The scripts cannot assert against values they do not know, and duplicating the NEVRA into a shell script creates a second source of truth that will drift. Instead the image publishes what it baked. Add to both Dockerfiles, immediately after the `dnf install` layer:

```dockerfile
# The scripts assert against these rather than installing anything: presence of
# IREE_DJL_PINNED_IMAGE means "you are in the pinned image, a missing tool is a broken image,
# not something to fix at run time". Keep the NEVRA here identical to the dnf line above --
# this is the single source of truth, and native/build_qa.sh reads it from the environment.
ENV IREE_DJL_PINNED_IMAGE=1
ENV IREE_DJL_TOOLSET_VER=14
ENV IREE_DJL_TOOLSET_NEVRA=14.2.1-11.el8_10
ENV IREE_DJL_NINJA_VERSION=1.13.0
```

- [ ] **Step 4: Assert at image-build time**

Extend the final assertion block in both Dockerfiles, so a pin that stops delivering fails at image build rather than three steps into a QA run:

```dockerfile
    && rpm -q "gcc-toolset-${IREE_DJL_TOOLSET_VER}-libasan-devel-${IREE_DJL_TOOLSET_NEVRA}" \
      || { echo "libasan NEVRA not installed as pinned"; exit 1; } \
    && rpm -q "gcc-toolset-${IREE_DJL_TOOLSET_VER}-libubsan-devel-${IREE_DJL_TOOLSET_NEVRA}" \
      || { echo "libubsan NEVRA not installed as pinned"; exit 1; } \
    && test "$(ninja --version)" = "${IREE_DJL_NINJA_VERSION}" \
      || { echo "ninja is $(ninja --version), expected ${IREE_DJL_NINJA_VERSION}"; exit 1; }
```

- [ ] **Step 5: Replace the install fallback in `build_qa.sh` with an assertion**

Replace the whole `for _san in asan ubsan; do ... done` block that Task 2 Step 6 added:

```bash
  # Sanitizer runtimes. In the pinned image these are baked in at an exact NEVRA (see
  # docker/*.Dockerfile); a missing one means a BROKEN IMAGE, and installing it here at
  # whatever version dnf offers would silently defeat the pinning the image exists to
  # provide. So: assert inside the image, install only outside it, and never silently.
  if [ -n "${IREE_DJL_PINNED_IMAGE:-}" ]; then
    for _san in asan ubsan; do
      _pkg="gcc-toolset-${IREE_DJL_TOOLSET_VER}-lib${_san}-devel-${IREE_DJL_TOOLSET_NEVRA}"
      if ! rpm -q --quiet "${_pkg}"; then
        echo "BROKEN IMAGE: ${_pkg} is not installed at the pinned NEVRA." >&2
        echo "Rebuild the image (docker/${IR_PLATFORM:-linux-$(uname -m)}.Dockerfile); do not install it here." >&2
        exit 1
      fi
      echo "--- ${_san} runtime present at pinned ${IREE_DJL_TOOLSET_NEVRA} ---"
    done
  else
    # Not the pinned image. Probe for what actually matters -- can this toolchain LINK a
    # sanitized binary -- rather than asking a package manager about a package name. The
    # probe is distro-agnostic (the previous rpm/dnf version was dead code on Ubuntu, which
    # is what both the workstation and the GitHub runner run: rpm is command-not-found and
    # `command -v dnf` is false, so the whole block was a silent no-op that had never run).
    # This script installs nothing: an install here would be unpinned by construction.
    echo "--- WARNING: not the pinned image; toolchain versions are unpinned and results are not comparable ---"
    _probe="$(mktemp -d)"
    printf 'int main(){return 0;}\n' > "${_probe}/probe.cpp"
    for _san in address undefined; do
      if ! "${CXX:-g++}" -fsanitize="${_san}" "${_probe}/probe.cpp" -o "${_probe}/probe" 2>/dev/null; then
        echo "cannot link -fsanitize=${_san} with ${CXX:-g++}; install your toolchain's sanitizer runtime" >&2
        echo "  Debian/Ubuntu: it ships with gcc (libasan/libubsan); try reinstalling g++" >&2
        echo "  RHEL family:   gcc-toolset-<N>-lib{asan,ubsan}-devel" >&2
        rm -rf "${_probe}"
        exit 1
      fi
    done
    rm -rf "${_probe}"
    echo "--- asan and ubsan runtimes link (unpinned host toolchain) ---"
  fi
```

Two deliberate departures from the block this replaces:

- **No package manager, on either path.** The old `rpm`/`dnf` branch could not run on Ubuntu, so it was untested code guarding a case it never handled. A link probe tests the property the build depends on and works anywhere.
- **The script installs nothing, ever.** Inside the image an install defeats the pinning; outside it, an install is unpinned by construction and silently changes what the run measures. Both are failures, so both fail. The dropped `|| true` follows from the same rule.

- [ ] **Step 6: Give `build.sh`'s ninja fallback the same treatment**

Replace `native/build.sh:74`:

```bash
  # The pinned image bakes ninja in at an exact version; a miss there means a broken image,
  # not something to paper over with an unpinned pip install.
  if [ -n "${IREE_DJL_PINNED_IMAGE:-}" ]; then
    command -v ninja >/dev/null 2>&1 || {
      echo "BROKEN IMAGE: ninja is not on PATH in the pinned image; rebuild it." >&2; exit 1; }
    [ "$(ninja --version)" = "${IREE_DJL_NINJA_VERSION}" ] || {
      echo "BROKEN IMAGE: ninja $(ninja --version), image pins ${IREE_DJL_NINJA_VERSION}." >&2; exit 1; }
  else
    # Same rule as build_qa.sh: this script installs nothing. `pip install ninja` here was
    # unpinned against an image that pins 1.13.0, and on the Ubuntu workstation and runner
    # ninja is already on PATH from the distro anyway (1.11.1, measured) -- so the install
    # only ever fired in environments nobody uses, at whatever version PyPI served that day.
    command -v ninja >/dev/null 2>&1 || {
      echo "ninja is not on PATH. Install it (Debian/Ubuntu: apt install ninja-build) or run" >&2
      echo "through the pinned image: ./native/local_build_wrapper.sh native/build.sh" >&2
      exit 1
    }
    echo "--- WARNING: not the pinned image; ninja $(ninja --version) is unpinned ---"
  fi
```

Also delete `native/build.sh:72` in the same edit:

```bash
  export PATH="/opt/python/cp312-cp312/bin:${PATH}"
```

That path is the manylinux image's interpreter and does not exist on a host — it sits in the *host* branch purely so the adjacent `pip install ninja` resolved to the container's pip. With the install gone it is vestigial, and a nonexistent PATH entry in a host branch is exactly the kind of line that reads as meaningful later.

- [ ] **Step 7: Verify the image is self-consistent**

```bash
docker build -t djl-iree-engine-build:linux-x86_64 -f docker/linux-x86_64.Dockerfile docker
docker run --rm djl-iree-engine-build:linux-x86_64 bash -c \
  'rpm -q gcc-toolset-14-libubsan-devel; echo "pinned=${IREE_DJL_PINNED_IMAGE} nevra=${IREE_DJL_TOOLSET_NEVRA}"'
```

Expected: the package prints at `14.2.1-11.el8_10`, and `pinned=1`. The image-build assertions from Step 4 must have passed for the build to succeed at all.

- [ ] **Step 8: Verify the scripts assert rather than install**

```bash
./native/local_build_wrapper.sh native/build_qa.sh 2>&1 | tee /tmp/qa.log
grep -c "Installing .* runtime" /tmp/qa.log
grep "runtime present at pinned" /tmp/qa.log
```

Expected: the install line appears **zero** times, and both `asan` and `ubsan` report `runtime present at pinned 14.2.1-11.el8_10`. A nonzero install count means the container is still drifting.

- [ ] **Step 9: Verify the broken-image path fails loudly**

Prove the assertion actually bites, rather than trusting a passing run:

```bash
docker run --rm -e IREE_DJL_TOOLSET_NEVRA=99.9.9-9.el8_10 \
  -v "$(pwd)":/workspace -w /workspace \
  --memory=8g --memory-swap=8g --cpuset-cpus=0-3 \
  djl-iree-engine-build:linux-x86_64 /bin/bash /workspace/native/build_qa.sh; echo "exit=$?"
```

Expected: `BROKEN IMAGE: gcc-toolset-14-libubsan-devel-99.9.9-9.el8_10 is not installed at the pinned NEVRA.` and a **nonzero** exit, with no `dnf install` attempted.

- [ ] **Step 10: Verify the host path, which is now a real path**

The workstation is Ubuntu, so this branch actually runs here — unlike the `rpm`/`dnf` code it replaces. Run `build_qa.sh` directly on the host, outside the container:

```bash
systemd-run --user --scope -p MemoryMax=4G taskset -c 0-3 timeout 1800 \
  bash -c './native/build_qa.sh' 2>&1 | tee /tmp/qa-host.log
grep -E "WARNING: not the pinned image|runtimes link" /tmp/qa-host.log
```

Expected: the unpinned-toolchain warning and `--- asan and ubsan runtimes link (unpinned host toolchain) ---`, then a normal QA run. Measured on this host, `g++ -fsanitize=address` and `-fsanitize=undefined` both link out of the box (`libasan8`, `libubsan1` arrive with gcc), so the probe should pass without anything being installed.

Then confirm the failure path reads well, without breaking your toolchain:

```bash
CXX=/bin/false ./native/build_qa.sh; echo "exit=$?"
```

Expected: `cannot link -fsanitize=address with /bin/false`, the two package hints, and a nonzero exit.

- [ ] **Step 11: Commit**

```bash
git add docker/linux-x86_64.Dockerfile docker/linux-aarch64.Dockerfile \
        native/build_qa.sh native/build.sh
git commit -m "build: bake the UBSan runtime into the images; stop scripts installing tools

The scripts were undoing the image pinning at run time. build_qa.sh guarded on
a bare package name, so the pinned NEVRA was never verified, and libubsan was
not baked in at all -- so inside the container the guard failed and dnf pulled
an unpinned libubsan on every run, with || true hiding failures. build.sh did
the same with an unpinned pip install ninja against an image pinning 1.13.0.

The host branch was dead code besides: both the workstation and the runner are
Ubuntu, where rpm is command-not-found and dnf is absent, so the loop fell
through silently and had never actually run.

Both images now bake libubsan at the same NEVRA as libasan and publish their
pins as environment variables. The scripts assert against those inside the
image -- a miss is a broken image, exit nonzero -- and outside it probe that
the toolchain can link -fsanitize=address/undefined, which is distro-agnostic
and tests the property the build depends on. Neither script installs anything
on any path: inside the image that defeats the pinning, outside it it is
unpinned by construction."
```

---

### Task 4: Gate B — UBSan on the JNI shim, driven by the JVM suite

**Files:**
- Modify: `native/CMakeLists.txt:222` (the shim's sanitizer guard)
- Create: `native/ubsan_gate.sh`
- Modify: `native/.gitignore` (ignore the new tree)

**Interfaces:**
- Consumes: `IREE_DJL_UBSAN` and `IREE_DJL_UBSAN_CHECKS` from Task 2; the NEVRA-pinned UBSan runtime from Task 3; `-Xcheck:jni` from Task 1 (inherited automatically — this task must not set it again).
- Produces: `native/ubsan/libiree_djl.so`, a shim with the UBSan runtime statically linked, loadable by a stock JVM via `IREE_LIBRARY_PATH`.

- [ ] **Step 1: Relax the shim guard**

In `native/CMakeLists.txt`, replace line 222's condition and its comment:

```cmake
# JNI shim (Task 7): thin marshalling layer over iree_djl_core. Skipped under ASan and
# TSan: those builds are QA-only, never shipped, and JVM-free -- so they must not require
# a JDK. The Catch2 units and the leak harness link iree_djl_core directly.
#
# UBSan is the exception, and deliberately so. It needs no runtime preload, and
# -static-libubsan folds its runtime into the .so, so a stock JVM can dlopen the result
# with no LD_PRELOAD and no "ASan runtime does not come first". That makes the shim --
# where issues 15, 16 and 17 all lived -- reachable by a sanitizer for the first time.
# native/ubsan_gate.sh builds exactly this configuration and drives it with the JVM suite.
if(NOT IREE_DJL_SANITIZE AND NOT IREE_DJL_TSAN)
  find_package(JNI REQUIRED)
  add_library(iree_djl SHARED jni/iree_djl_jni.cpp)
  target_include_directories(iree_djl PRIVATE ${JNI_INCLUDE_DIRS})
  target_link_libraries(iree_djl PRIVATE iree_djl_core)
  # Hide IREE's symbols so they cannot collide with anything else in the JVM.
  if(NOT WIN32)
    target_link_options(iree_djl PRIVATE -Wl,--exclude-libs,ALL)
  endif()
  # Statically link the UBSan runtime so the JVM needs no preload. Without this the .so
  # carries an undefined dependency on libubsan.so and System.load fails.
  if(IREE_DJL_UBSAN)
    target_link_options(iree_djl PRIVATE -static-libubsan)
  endif()
endif()
```

Note the guard itself is unchanged — it already permits UBSan-only builds, since UBSan is neither `IREE_DJL_SANITIZE` nor `IREE_DJL_TSAN`. The additions are the `-static-libubsan` link option and the comment recording why UBSan is allowed through.

- [ ] **Step 2: Verify the shim builds and links statically**

```bash
export JAVA_HOME=/usr/lib/jvm/zulu-17-amd64
rm -rf native/ubsan
cmake -B native/ubsan -S native -G "Unix Makefiles" \
  -DIREE_DJL_UBSAN=ON -DIREE_DJL_BUILD_TESTS=OFF -DCMAKE_BUILD_TYPE=Debug
cmake --build native/ubsan --target iree_djl -j"$(nproc)"
ldd native/ubsan/libiree_djl.so | grep -i ubsan && echo "FAIL: dynamic libubsan dependency" || echo "OK: ubsan runtime is static"
nm -D --defined-only native/ubsan/libiree_djl.so | grep -c ubsan
```

Expected: `OK: ubsan runtime is static`, and a nonzero count of defined `ubsan` symbols. A dynamic dependency here means `-static-libubsan` did not apply and `System.load` will fail in the JVM.

- [ ] **Step 3: Write the gate script**

Create `native/ubsan_gate.sh`:

```bash
#!/usr/bin/env bash
# UBSan gate for the JNI shim, driven by the JVM suite.
#
# This is the ONLY configuration in which native/jni/iree_djl_jni.cpp is instrumented.
# native/build_qa.sh covers iree_djl_core, the Catch2 suites and the leak harness, but
# native/CMakeLists.txt skips the shim under ASan/TSan so QA stays JVM-free. UBSan is the
# exception: it needs no runtime preload, and -static-libubsan folds its runtime into the
# .so, so a stock JVM can dlopen it.
#
# NOTE: a UB hit here presents as a JVM HARD CRASH mid-test, not a Java exception or an
# assertion failure. That is the gate working. Look for the "runtime error:" line and its
# stack trace above the JVM's own crash output.
#
# TWO PHASES, because they need different environments. The pinned container has the right
# toolchain but the wrong JDK (Corretto 8, for the oldest supported jni.h), and Gradle 9.6.1
# with this project's JDK 17 toolchain cannot run there at all. So: build in the container,
# test on the host. IREE_DJL_UBSAN_MODE selects a phase; it defaults to `auto`, which is
# build-only inside the image and both phases outside it.
#
# The instrumented .so is NEVER staged into src/main/resources -- it is reached through
# IREE_LIBRARY_PATH, which LibUtils honours ahead of the classpath copy and which
# build.gradle.kts already declares as a Test task input. So this script leaves the plain
# tree in native/build untouched and does not require a rebuild afterwards.
#
# Linux only: MSVC has no UndefinedBehaviorSanitizer.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

BUILD_DIR="${BUILD_DIR:-native/ubsan}"
JOBS="${JOBS:-$(nproc)}"

# Two phases, because they need different environments and cannot share one.
#
#   build  needs gcc + jni.h. Runs happily in the pinned container, which is where the
#          instrumentation SHOULD be produced: pinned toolchain, pinned libubsan NEVRA.
#   test   needs Gradle 9.6.1 and a JDK 17 toolchain. The container CANNOT provide that --
#          its JAVA_HOME is Corretto 1.8.0_502, chosen deliberately for the oldest supported
#          jni.h -- so the JVM phase runs on the host, against the .so the build phase left
#          behind.
#
# Default is `auto`: build-only inside the pinned image, both phases outside it. The script
# knows where it is, so no caller has to remember.
MODE="${IREE_DJL_UBSAN_MODE:-auto}"
if [ "${MODE}" = "auto" ]; then
  if [ -n "${IREE_DJL_PINNED_IMAGE:-}" ]; then MODE=build; else MODE=all; fi
fi
case "${MODE}" in
  build|test|all) ;;
  *) echo "IREE_DJL_UBSAN_MODE must be build, test, all or auto (got '${MODE}')" >&2; exit 1 ;;
esac

# All four test tasks, not just `test`. tasks.test excludes the leak/oom/stress tags, and
# oomTest is a scripted reproduction of issue 16 -- the only task that drives the
# allocation-failure paths in the output marshalling loop. stressTest and oomTest do not
# run in CI, so this local sequence is the only place they meet an instrumented shim.
TEST_TASKS="${TEST_TASKS:-test leakTest oomTest stressTest}"

# --no-daemon is not a preference. A pre-existing Gradle daemon lives in whatever cgroup it
# was first started in, so `./gradlew` would hand the work -- including every forked test
# JVM -- to a process outside any resource scope wrapping this script. oomTest exhausts a
# heap on purpose; letting that escape has taken down unrelated processes on this host.
GRADLE_FLAGS="${GRADLE_FLAGS:---no-daemon}"

# UBSan's default is print-and-continue; -fno-sanitize-recover (native/CMakeLists.txt)
# makes it abort, and these make the abort legible.
export UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1

if [ "${MODE}" = "build" ] || [ "${MODE}" = "all" ]; then
  echo "--- Building the UBSan-instrumented shim ---"
  rm -rf "${BUILD_DIR}"
  cmake -S native -B "${BUILD_DIR}" -G "Unix Makefiles" \
    -DIREE_DJL_UBSAN=ON -DIREE_DJL_BUILD_TESTS=OFF -DCMAKE_BUILD_TYPE=Debug
  cmake --build "${BUILD_DIR}" --target iree_djl -j"${JOBS}"

  # A dynamic libubsan dependency means -static-libubsan did not apply, and System.load
  # would fail with a confusing linker error. Assert here so a failure names its own cause
  # -- the same courtesy native/build_qa.sh extends for the Windows CRT check. This matters
  # more across the phase split: the build may happen in a container and the load on a host
  # hours later, in a different job.
  if ldd "${BUILD_DIR}/libiree_djl.so" | grep -qi ubsan; then
    echo "FAIL: ${BUILD_DIR}/libiree_djl.so has a dynamic libubsan dependency; -static-libubsan did not apply" >&2
    exit 1
  fi
  echo "--- UBSan runtime is statically linked ---"
fi

if [ "${MODE}" = "build" ]; then
  echo "--- UBSan shim built at ${BUILD_DIR}/libiree_djl.so; JVM phase skipped ---"
  echo "--- Run the JVM phase where a JDK 17 lives: IREE_DJL_UBSAN_MODE=test ./native/ubsan_gate.sh ---"
  exit 0
fi

# Refuse the JVM phase rather than letting Gradle fail obscurely. The container's JAVA_HOME
# is Corretto 8; Gradle 9.6.1 and the project's JDK 17 toolchain both need 17+.
if [ -n "${IREE_DJL_PINNED_IMAGE:-}" ]; then
  echo "REFUSING the JVM phase inside the pinned image: JAVA_HOME is Corretto 8, and Gradle" >&2
  echo "9.6.1 with a JDK 17 toolchain cannot run there. Build here, test on the host:" >&2
  echo "  ./native/local_build_wrapper.sh native/ubsan_gate.sh   # build phase, in-container" >&2
  echo "  IREE_DJL_UBSAN_MODE=test ./native/ubsan_gate.sh        # JVM phase, on the host" >&2
  exit 1
fi

_java_major="$("${JAVA_HOME:-/usr}/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
if [ "${_java_major:-0}" -lt 17 ]; then
  echo "JAVA_HOME points at Java ${_java_major}; Gradle 9.6.1 and this project need 17+." >&2
  exit 1
fi

if [ ! -f "${BUILD_DIR}/libiree_djl.so" ]; then
  echo "no instrumented shim at ${BUILD_DIR}/libiree_djl.so -- run the build phase first" >&2
  exit 1
fi

echo "--- JVM suite against the instrumented shim (${TEST_TASKS}) ---"
# --rerun-tasks because a cached UP-TO-DATE result would report a pass for a run that
# never loaded this library.
IREE_LIBRARY_PATH="$(pwd)/${BUILD_DIR}/libiree_djl.so" \
  ./gradlew ${GRADLE_FLAGS} ${TEST_TASKS} --rerun-tasks

echo "--- UBSan gate PASS ---"
```

Make it executable:

```bash
chmod +x native/ubsan_gate.sh
```

- [ ] **Step 4: Ignore the new tree**

Add to `native/.gitignore`:

```
ubsan/
```

- [ ] **Step 5: Verify the gate detects UB in the shim**

Temporarily add a deliberate UB expression at the top of `Java_org_measly_iree_jni_IreeNative_invoke` in `native/jni/iree_djl_jni.cpp`:

```cpp
  // TEMPORARY UBSan probe -- revert before committing.
  {
    volatile int shift_probe = 1;
    (void)(shift_probe << 99);
  }
```

Then:

```bash
./gradlew --stop
systemd-run --user --scope -p MemoryMax=4G taskset -c 0-3 timeout 1800 \
  bash -c './native/ubsan_gate.sh'; echo "exit=$?"
```

Expected: a `runtime error: shift exponent 99 is too large` diagnostic naming `iree_djl_jni.cpp`, a JVM crash, and a **nonzero** exit from the script. This is the proof that the shim is genuinely instrumented and that a hit fails the gate — the single most important verification in this plan, since Gate B exists only to cover this file.

- [ ] **Step 6: Revert the probe and run the gate clean**

```bash
git checkout native/jni/iree_djl_jni.cpp
git diff --stat native/jni/iree_djl_jni.cpp   # must be empty
./native/ubsan_gate.sh
```

Expected: `--- UBSan gate PASS ---`. Any real diagnostic is a finding to fix, not to suppress.

- [ ] **Step 7: Verify the phase split behaves in both environments**

The split is the part most likely to be subtly wrong, so exercise both refusals rather than only the happy path.

```bash
# In-container: must build and stop, never reaching Gradle.
./native/local_build_wrapper.sh native/ubsan_gate.sh 2>&1 | tail -3

# In-container, forced into the JVM phase: must refuse with a legible reason.
docker run --rm -e IREE_DJL_UBSAN_MODE=test \
  -v "$(pwd)":/workspace -w /workspace \
  --memory=8g --memory-swap=8g --cpuset-cpus=0-3 \
  djl-iree-engine-build:linux-x86_64 /bin/bash /workspace/native/ubsan_gate.sh; echo "exit=$?"

# Host, JVM phase only, against the .so the container just built.
IREE_DJL_UBSAN_MODE=test ./native/ubsan_gate.sh
```

Expected, in order: `JVM phase skipped` with the host follow-up printed; then `REFUSING the JVM phase inside the pinned image` and a nonzero exit; then `--- UBSan gate PASS ---`. The third run is also the more valuable configuration in general — instrumentation produced by the pinned toolchain, exercised by a JDK 17 the container cannot host.

- [ ] **Step 8: Confirm the plain tree still works**

The gate must not have disturbed the shipping library.

```bash
./native/build.sh
./gradlew test --rerun-tasks
```

Expected: both succeed, with `N actionable tasks: N executed`. This confirms nothing instrumented leaked into `src/main/resources`.

- [ ] **Step 9: Commit**

```bash
git add native/CMakeLists.txt native/ubsan_gate.sh native/.gitignore
git commit -m "build(native): UBSan gate over the JNI shim via the JVM suite

The shim is where issues 15, 16 and 17 lived and is the one file no
sanitizer could reach: ASan and TSan builds skip it to stay JVM-free.
UBSan can reach it -- no preload needed, and -static-libubsan folds the
runtime into the .so so a stock JVM can dlopen it through the existing
IREE_LIBRARY_PATH seam, staging nothing into resources.

Split into build and test phases: the pinned container has the right
toolchain but JAVA_HOME is Corretto 8, and Gradle 9.6.1 with this
project's JDK 17 toolchain cannot start there. Build in the container,
test on the host; the mode defaults to auto so callers need not care.

Runs all four test tasks: tasks.test excludes the tags oomTest needs,
and oomTest is the reproduction of issue 16."
```

---

### Task 5: CI wiring

**Files:**
- Modify: `.github/workflows/native-build-job.yml` (Gate B build phase + artifact upload, Linux x86_64 only)
- Modify: `.github/workflows/native-build.yml` (Gate B JVM phase in `build-java-package`)
- Modify: `native/local_build_wrapper.sh:9-12,39` (container memory/CPU limits)

**Interfaces:**
- Consumes: `native/ubsan_gate.sh` and its `IREE_DJL_UBSAN_MODE` phase selector from Task 4; the `IREE_DJL_UBSAN=ON` wiring in `build_qa.sh` from Task 2 (already in CI, no change needed); the baked-in UBSan runtime and `IREE_DJL_PINNED_IMAGE` marker from Task 3, without which the build phase has no runtime to link and no way to know it must skip Gradle.
- Produces: nothing later tasks depend on.

**Scope:** Gates A and C need no CI change at all — Gate A rides inside `build_qa.sh`, which `.github/workflows/native-build-job.yml:64-70` already runs on both Linux matrix rows, and Gate C rides inside the `./gradlew test` and `./gradlew leakTest` at `.github/workflows/native-build.yml:66-68`. Gate B is the only new work, on **linux-x86_64 only**, and it spans both workflows.

**Why two jobs.** The container has the pinned toolchain and the pinned libubsan NEVRA, but `JAVA_HOME` is Corretto 1.8.0_502; Gradle 9.6.1 with this project's JDK 17 toolchain cannot start there. The repo already separates these concerns — `build-iree-shim` builds natively in containers and uploads artifacts, `build-java-package` runs on `ubuntu-latest` with `setup-java` 17 and consumes them — so Gate B follows that existing seam rather than fighting it. The alternative, adding a JDK 17 to the images, would undo the deliberate Corretto 8 choice (the oldest supported `jni.h`, matching what the Windows job binds via `JAVA_HOME_8_X64`), bloat a pinned image, and create a `JAVA_HOME` ambiguity in the shipping build. Not worth it to save an artifact round-trip.

- [ ] **Step 1: Confirm Gates A and C are already covered**

```bash
grep -n "build_qa.sh" .github/workflows/native-build-job.yml
grep -n "gradlew" .github/workflows/native-build.yml
```

Expected: `build_qa.sh` invoked in the Linux job (line ~70) and the Windows job (line ~151); `gradlew build`/`test`/`leakTest` in `native-build.yml`. No edits to either — Gate A and Gate C are inside those invocations already.

- [ ] **Step 2: Add the Gate B build phase to the container job**

Gate B cannot be one CI step: the container has the pinned toolchain but Corretto 8, and Gradle 9.6.1 needs 17+. It straddles the two jobs the repo already has — `build-iree-shim` (container matrix, uploads artifacts) and `build-java-package` (`ubuntu-latest`, `setup-java` 17, downloads them). Build in the first, test in the second.

In `.github/workflows/native-build-job.yml`, after the existing "Run native QA gate" step (which ends at line 70), add:

```yaml
      # Gate B, phase 1: build the instrumented shim with the pinned toolchain and libubsan
      # NEVRA. The JVM phase runs in build-java-package, which has a JDK 17 -- this
      # container's JAVA_HOME is Corretto 8, so Gradle cannot start here at all.
      #
      # Scoped to linux-x86_64: a second native build plus a --rerun-tasks JVM suite is real
      # CI time, and aarch64 gets documented gaps rather than duplicated gates.
      - name: Build the UBSan-instrumented shim (linux-x86_64 only)
        if: matrix.platform == 'linux-x86_64'
        run: |
          docker run --rm \
            -v ${{ github.workspace }}:/workspace \
            -w /workspace \
            djl-iree-engine-build:${{ matrix.platform }} \
            /bin/bash /workspace/native/ubsan_gate.sh

      # A DISTINCT artifact name, deliberately outside the `iree-libs-*` pattern that
      # build-java-package merges into src/main/resources/native/. An instrumented library
      # landing there would be staged into the shipping resources -- the exact trip-wire
      # this design exists to avoid -- and would silently become what `./gradlew test` loads.
      - name: Store the UBSan shim
        if: matrix.platform == 'linux-x86_64'
        uses: actions/upload-artifact@v5
        with:
          name: iree-ubsan-shim-${{ matrix.platform }}
          path: native/ubsan/libiree_djl.so
```

The `docker run` flags match the two steps above it verbatim; the only addition is the `if:` guard. No `-e IREE_DJL_UBSAN_MODE` is needed — the script's `auto` mode sees `IREE_DJL_PINNED_IMAGE` and builds without attempting Gradle. Match `upload-artifact`'s major version to the "Store libiree_djl shim" step already in this file rather than the `v5` written here.

- [ ] **Step 3: Add the Gate B JVM phase to the Java job**

In `.github/workflows/native-build.yml`, in `build-java-package`, after the existing "Build and test the Java package" step:

```yaml
        # Gate B, phase 2: the JVM suite against the UBSan-instrumented shim built by
        # build-iree-shim. This job is where a JDK 17 exists; the container that produced the
        # library cannot run Gradle at all.
        #
        # Downloaded OUTSIDE src/main/resources/native/ and reached via IREE_LIBRARY_PATH,
        # which LibUtils honours ahead of the classpath copy. The instrumented library is
        # never staged into resources.
        #
        # TEST_TASKS omits oomTest and stressTest: oomTest needs the pinned pip iree-compile
        # for its exportOomFixture dependency, which this job does not carry, so the issue 16
        # reproduction stays a local gate by decision, not by oversight. See
        # docs/superpowers/specs/2026-08-12-ubsan-and-jni-checking-design.md.
        - name: Download the UBSan-instrumented shim
          uses: actions/download-artifact@v8
          with:
            name: iree-ubsan-shim-linux-x86_64
            path: native/ubsan/

        - name: UBSan gate over the JNI shim
          env:
            IREE_DJL_UBSAN_MODE: test
            TEST_TASKS: "test leakTest"
          run: ./native/ubsan_gate.sh
```

- [ ] **Step 4: Verify both workflows parse**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/native-build-job.yml')); print('job YAML OK')"
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/native-build.yml')); print('build YAML OK')"
```

Expected: both print OK.

- [ ] **Step 5: Confirm the instrumented artifact cannot reach the shipping resources**

The download in `build-java-package` uses `pattern: iree-libs-*` with `merge-multiple: true` into `src/main/resources/native/`. Prove the new artifact name is outside that pattern:

```bash
grep -n "iree-libs-\|iree-ubsan-shim-\|merge-multiple\|path: src/main/resources" .github/workflows/native-build.yml
```

Expected: the `iree-libs-*` pattern and the `iree-ubsan-shim-linux-x86_64` name are clearly distinct, and only the former targets `src/main/resources/native/`. If a future rename brings them under one pattern, an instrumented `.so` becomes what every `./gradlew test` loads — silently.

- [ ] **Step 6: Give the local container wrapper its own memory limit**

A host `systemd-run` scope does not contain a container (see Resource containment above), so `local_build_wrapper.sh` must limit the container itself. Without this, the next step runs a UBSan build plus four test-task JVMs unbounded — the exact shape that has OOM-killed unrelated processes on this host.

In `native/local_build_wrapper.sh`, add above the `docker run` at line 39:

```bash
# Resource limits for the container. A host-side systemd scope does NOT contain this:
# dockerd is a root daemon, so container processes are children of containerd-shim in the
# system slice, not of this shell. A runaway test here has taken down unrelated host
# processes, so the limit goes on the container or nowhere.
#   --memory-swap equal to --memory disables swap; without it Docker grants an equal
#     amount by default and the box thrashes instead of failing fast.
#   --cpuset-cpus caps parallelism for free: nproc inside resolves to the set's size, so
#     JOBS="${JOBS:-$(nproc)}" in build_qa.sh follows automatically.
IR_MEMORY="${IR_MEMORY:-8g}"
IR_CPUSET="${IR_CPUSET:-0-3}"
```

and add these three flags to the `docker run` invocation, immediately after `--rm`:

```bash
    --memory="${IR_MEMORY}" \
    --memory-swap="${IR_MEMORY}" \
    --cpuset-cpus="${IR_CPUSET}" \
```

Also extend the usage comment at lines 9-12 with:

```bash
#   IR_MEMORY=12g ./native/local_build_wrapper.sh native/ubsan_gate.sh   # raise the cap
```

- [ ] **Step 7: Verify the limits apply**

```bash
./native/local_build_wrapper.sh native/build.sh &
sleep 20
docker stats --no-stream --format '{{.Name}}: mem={{.MemUsage}} cpu={{.CPUPerc}}'
wait
```

Expected: the `MemUsage` column shows a limit of `8GiB`, not the host's total. If it shows the host total, the flags did not apply — fix before continuing, since the next step is the one that runs four JVMs.

- [ ] **Step 8: Rehearse the CI split locally**

CI's two phases run in different jobs on different machines, so rehearse them as two separate invocations rather than one local run that hides the seam:

```bash
# Phase 1, as build-iree-shim does it: container, auto mode, build only.
./native/local_build_wrapper.sh native/ubsan_gate.sh
test -f native/ubsan/libiree_djl.so && echo "artifact present at the path CI uploads"

# Phase 2, as build-java-package does it: host JDK 17, test mode, CI's task subset.
IREE_DJL_UBSAN_MODE=test TEST_TASKS="test leakTest" ./native/ubsan_gate.sh
```

Expected: phase 1 ends with `JVM phase skipped` and leaves `native/ubsan/libiree_djl.so` — the exact path the upload step names; phase 2 ends with `--- UBSan gate PASS ---`. The `find_package(JNI REQUIRED)` in phase 1 is satisfied by the image's Corretto 8 headers, which is all the shim build ever needed.

If the container is OOM-killed at 8g (exit 137), report that rather than raising `IR_MEMORY` and moving on: a UBSan build of this tree needing more than 8 GiB is itself a finding.

- [ ] **Step 9: Commit**

```bash
git add .github/workflows/native-build-job.yml .github/workflows/native-build.yml \
        native/local_build_wrapper.sh
git commit -m "ci: run the UBSan shim gate on linux-x86_64

Gates A and C need no CI change -- they ride inside build_qa.sh and the
existing gradlew test/leakTest invocations. Gate B is the only new step.

TEST_TASKS omits oomTest and stressTest: oomTest needs pip iree-compile
for its fixture, which this job does not carry, so the issue 16
reproduction stays a local gate by decision.

Gate B spans two jobs because it must: the container has the pinned
toolchain but Corretto 8, and Gradle 9.6.1 needs 17+. It builds in
build-iree-shim and runs the JVM phase in build-java-package, which
already has setup-java 17. The instrumented library is uploaded under a
name outside the iree-libs-* pattern so it can never be merged into
src/main/resources/native/.

Also caps the local container wrapper at 8 GiB with swap disabled and a
4-CPU cpuset. A host systemd scope cannot contain a container -- dockerd
is a root daemon, so its children live in the system slice -- which left
local container runs as the one unbounded path."
```

---

### Task 6: Documentation

**Files:**
- Modify: `CONTRIBUTING.md:113-172` (Native QA section) and `CONTRIBUTING.md:35-49` (Build and test)
- Modify: `CLAUDE.md` (Commands and Trip-wires sections)

**Interfaces:**
- Consumes: the invocations established in Tasks 1-4.
- Produces: nothing later tasks depend on. This is the last task.

- [ ] **Step 1: Add the gates to CONTRIBUTING.md's Native QA section**

In `CONTRIBUTING.md`, inside the code block in the `## Native QA` section, after the ASan/LSan lines and before the TSan lines, add:

```bash
# UBSan gate over the native QA tree (composes with ASan; both run in ./native/build_qa.sh):
rm -rf native/build && ./native/build.sh -DIREE_DJL_SANITIZE=ON -DIREE_DJL_UBSAN=ON
UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1 ./native/build/iree_leak_harness "" 200

# UBSan gate over the JNI shim, driven by the JVM suite. The ONLY configuration that
# instruments native/jni/iree_djl_jni.cpp. Runs all four test tasks:
./native/ubsan_gate.sh
```

Then add this prose after the existing `IREE_DJL_SANITIZE`/`IREE_DJL_TSAN` mutual-exclusion paragraph:

```markdown
`IREE_DJL_UBSAN` is not mutually exclusive with either: UBSan is per-translation-unit and
local, so it composes with ASan and needs no instrumented runtime. It is Linux-only (MSVC
has no UndefinedBehaviorSanitizer) and fails fast at configure time on Windows.

`./native/ubsan_gate.sh` runs in two phases, because they need different environments. The
build phase needs the pinned toolchain and is happiest in the container; the JVM phase needs
Gradle 9.6.1 and a JDK 17 toolchain, which the container **cannot** provide — its
`JAVA_HOME` is Corretto 8, chosen deliberately for the oldest supported `jni.h`.
`IREE_DJL_UBSAN_MODE` selects a phase and defaults to `auto`: build-only inside the pinned
image, both phases outside it. So a plain host run needs no flags, and the container run
stops after the build and tells you the follow-up command:

```bash
./native/local_build_wrapper.sh native/ubsan_gate.sh   # build, pinned toolchain
IREE_DJL_UBSAN_MODE=test ./native/ubsan_gate.sh        # JVM phase, host JDK 17
```

**`./native/ubsan_gate.sh` is the only gate that instruments the JNI shim.** The ASan and
TSan trees skip `native/jni/iree_djl_jni.cpp` to stay JVM-free, which left the file where
issues 15, 16 and 17 all lived uncovered by any sanitizer. UBSan can reach it because it
needs no runtime preload: `-static-libubsan` folds its runtime into the `.so`, and the gate
points the JVM at it through `IREE_LIBRARY_PATH` rather than staging it into resources — so
unlike the ASan and TSan gates, **it does not require rebuilding the plain `.so`
afterwards**. A UB hit presents as a JVM hard crash mid-test, not a test failure; look for
the `runtime error:` line above the JVM's crash output.

The gate runs `test leakTest oomTest stressTest`, not just `test`: `tasks.test` excludes the
`leak`, `oom` and `stress` tags, and `oomTest` is a scripted reproduction of issue 16 — the
only task that drives the output-marshalling allocation-failure paths. `oomTest` needs the
pinned pip `iree-compile` on PATH for its `exportOomFixture` dependency, which is why it and
`stressTest` run locally only; CI covers `test` and `leakTest`. **Run the full local
sequence before claiming the JNI boundary is verified** — CI does not check those paths.

Every JVM test task also runs under `-Xcheck:jni`, the JVM's own JNI-contract checker,
attached to the `Test` task umbrella in `build.gradle.kts`. It catches the class UBSan
cannot see: JNI calls made with a pending exception, and null array arguments — issue 16
exactly. It runs against the plain shipping library, so it costs nothing and needs no
special build.
```

- [ ] **Step 2: Note the checker in CONTRIBUTING.md's Build and test section**

After the code block in `## Build and test`, add:

```markdown
Every test task runs with `-Xcheck:jni`. If the JVM aborts with a JNI warning rather than a
test failure, that is the checker catching a contract violation in the shim — see Native QA
below.
```

- [ ] **Step 3: Add the commands to CLAUDE.md**

In `CLAUDE.md`'s "Commands that actually get run" block, add after the `./native/build_qa.sh` line:

```bash
./native/ubsan_gate.sh             # UBSan over the JNI shim, driven by the JVM suite
```

- [ ] **Step 4: Add the trip-wires to CLAUDE.md**

Add to the `## Trip-wires` section:

```markdown
- **`native/ubsan/` must never be staged into `src/main/resources`.** Same hazard as the
  ASan tree, different flag. `./native/ubsan_gate.sh` reaches its instrumented `.so`
  through `IREE_LIBRARY_PATH` instead, so — unlike the ASan and TSan gates — it leaves the
  plain tree alone and needs no rebuild afterwards.
- **A UB hit under `ubsan_gate.sh` is a JVM hard crash, not a test failure.** `-Xmx`-style
  JVM crash output will dominate; the actual finding is the `runtime error:` line and its
  stack trace above it. Do not read the crash as a flaky test.
- **Gradle cannot run in the pinned container.** `JAVA_HOME` there is Corretto 1.8.0_502
  (deliberate: the oldest supported `jni.h`), while the wrapper is Gradle 9.6.1 and
  `build.gradle.kts` sets a JDK 17 toolchain. Native builds go in the container, JVM runs
  never do. `ubsan_gate.sh` splits along that line by itself — `IREE_DJL_UBSAN_MODE=auto`
  builds only when it sees `IREE_DJL_PINNED_IMAGE` — and refuses the JVM phase there rather
  than letting Gradle fail obscurely.
- **GCC has no UBSan ignorelist.** `-fsanitize-ignorelist` and `-fsanitize-blacklist` are
  unrecognized options, and `UBSAN_OPTIONS=suppressions=` does not suppress these checks
  (measured, gcc 13.3). Silencing a diagnostic means
  `__attribute__((no_sanitize("undefined")))` on the function, a per-TU
  `set_source_files_properties` override, or `-fno-sanitize=<check>` — each needs a comment
  naming what was given up, or a check silently leaves the gate.
- **`oomTest` and `stressTest` do not run in CI.** `oomTest` needs pip `iree-compile` for
  its fixture. It is the only reproduction of issue 16's allocation-failure paths, so the
  JNI failure contract is verified only when someone runs the full local sequence.
```

- [ ] **Step 5: Verify no emoji and no stale claims**

```bash
grep -nP '[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}]' CONTRIBUTING.md CLAUDE.md docs/superpowers/specs/2026-08-12-ubsan-and-jni-checking-design.md docs/superpowers/plans/2026-08-12-ubsan-and-jni-checking.md || echo "no emoji"
grep -n "Linux only\|Linux-only" README.md || echo "README unchanged, no platform claim introduced"
```

Expected: `no emoji`. The second check guards the standing "three platforms ship" rule — UBSan being Linux-only must not leak into a claim that the *engine* is.

- [ ] **Step 6: Verify the documented commands actually work**

Run each command as written in the docs, from a clean state:

```bash
export JAVA_HOME=/usr/lib/jvm/zulu-17-amd64
./native/build.sh
./gradlew test --rerun-tasks
./native/build_qa.sh
./native/ubsan_gate.sh
./gradlew javadoc
```

Expected: all pass, and `javadoc` reports zero warnings — Task 1 added two public-ish test classes, and while test sources are not in the javadoc jar, run it anyway since regressions there are silent by design.

- [ ] **Step 7: Commit**

```bash
git add CONTRIBUTING.md CLAUDE.md
git commit -m "docs: document the UBSan and -Xcheck:jni gates

Records the three things a reader would otherwise get wrong: the shim gate
does not need a plain rebuild afterwards (it never stages), a UB hit is a
JVM crash rather than a test failure, and GCC has no ignorelist so
suppression is per-function or per-TU.

Also states plainly that oomTest and stressTest are local-only, so the
issue 16 reproduction is enforced by whoever runs the full sequence."
```

---

## Verification checklist

Run before claiming the work is done:

- [ ] `./native/build.sh` compiles clean, and `./gradlew test --rerun-tasks` passes against the plain library, showing `N actionable tasks: N executed`.
- [ ] `./native/build_qa.sh` reports `--- native QA PASS ---` with UBSan active.
- [ ] `./native/ubsan_gate.sh` reports `--- UBSan gate PASS ---` on the host, and the container build phase followed by a host `IREE_DJL_UBSAN_MODE=test` run passes as a pair.
- [ ] The container refuses `IREE_DJL_UBSAN_MODE=test` with a legible message and a nonzero exit, rather than letting Gradle fail against Corretto 8.
- [ ] The CI artifact name for the instrumented shim is outside the `iree-libs-*` pattern that `build-java-package` merges into `src/main/resources/native/`.
- [ ] `./gradlew oomTest --rerun-tasks` and `./gradlew stressTest --rerun-tasks` pass under `-Xcheck:jni`.
- [ ] `./gradlew javadoc` reports zero warnings.
- [ ] `git status` shows no `native/ubsan/`, no `native/qa-ubsan-probe/`, no `native/build-clangd/`, no instrumented `.so`, and no leftover UB probes in `iree_leak_harness.cpp` or `iree_djl_jni.cpp`.
- [ ] Every build and test invocation ran inside a `systemd-run --user --scope` with `--no-daemon` on the Gradle commands, and containment was confirmed at least once via the `/proc/<pid>/cgroup` check. No host-wide OOM kill occurred.
- [ ] Every container run went through `native/local_build_wrapper.sh` with its `--memory`/`--memory-swap`/`--cpuset-cpus` limits in place, confirmed once via `docker stats`. A host scope does not contain a container, so this is a separate check, not a duplicate of the one above.
- [ ] The Windows branch of `native/build_qa.sh` is byte-identical to before this work.
- [ ] No script installs a tool on any path. `./native/local_build_wrapper.sh native/build_qa.sh` logs zero `Installing ... runtime` lines and reports the pinned NEVRA; `native/build.sh` logs no `pip install ninja`; and no `rpm`/`dnf`/`apt` invocation remains in either script.
- [ ] The host path was exercised on Ubuntu (Task 3 Step 10), not just the container path.
- [ ] The broken-image path was proven to fail loudly (Task 3 Step 9), not just assumed.

## Deferred, by decision

Recorded so a later reader does not mistake these for oversights:

- **`oomTest` and `stressTest` in CI.** `oomTest` needs pip `iree-compile` for `exportOomFixture`, a dependency the Linux native job does not carry and that `CLAUDE.md` is explicit about not requiring in order to build or test the engine. Revisit only if that job gains `iree-compile` for another reason.
- **A clang UBSan variant.** It would add `implicit-signed-integer-truncation` — the check that would have caught issue 15 — and a real ignorelist. Neither is available on GCC 13.3. A clean follow-on, not a prerequisite.
- **Coverage thresholds.** `jacoco` is already wired (`build.gradle.kts:6`, `:8`, `:78`, `:125`); what is missing is a threshold and coverage of the tag-filtered tasks. Separate design.
- **A UBSan runtime dist variant.** Unnecessary: UBSan is per-TU and local, so an uninstrumented dist produces no false positives and hides nothing in our own code. This is the opposite of the TSan situation, which genuinely needs an instrumented runtime — see issue #35.
- **The port to `djl-executorch-engine`.** The motivating follow-on, and out of scope for this plan, which touches one repo. Once these gates are green here, Gate C is a one-line copy and Gate A is the CMake option plus flags near-verbatim. Gate B has one dependency to **verify first**: that repo needs an `IREE_LIBRARY_PATH` equivalent — a `LibUtils` environment override that bypasses resource extraction and is declared as a `Test` task input. If it is absent, Gate B there needs that seam added first, or a staging-and-restore approach instead. Do not promise the full three-gate port before checking.
