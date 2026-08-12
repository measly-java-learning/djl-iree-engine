# UndefinedBehaviorSanitizer and JNI contract checking

Date: 2026-08-12

## Motivation

Sibling projects under `iree-org` run two QA levers this repo does not: UBSan and
coverage. This design covers the first. Coverage is out of scope.

The prompting observation was that recent JNI fixes were tagged as undefined
behavior. That framing needs correcting before it drives a design, because it
determines what to build:

- **Issue 16** (`5cb8c00`, unchecked allocation results in the `invoke()` output
  marshalling loop) is *JNI-specification* UB: calling a JNI function with an
  exception already pending, and passing a null `jlongArray` to
  `SetLongArrayRegion`. UBSan does not see this. There is no null dereference and
  no overflow — only a well-formed indirect call through the `JNIEnv` function
  table with a null *argument*.
- **Issue 15** (`02514d5`, `invoke()` output size truncating through a 32-bit
  `jint`) is not C++ UB either. Narrowing conversion to a signed type is
  well-defined two's-complement wrapping in C++20.
- **Issue 17** (`e4d9643`) is the same JNI-contract class as issue 16.

All three would have run clean under `-fsanitize=undefined`. The tool that
matches that class is the JVM's own `-Xcheck:jni`, which this repo does not use
anywhere.

UBSan remains worth adopting for a different and real exposure. The largest is
`alignment`: `docs/observability.md` records that IREE imports a host buffer
zero-copy only at 64-byte alignment (`IREE_HAL_HEAP_BUFFER_ALIGNMENT`) while the
JVM guarantees only 8, a hazard currently described in prose and bounded by a
runtime check rather than detected. Behind it sit `null`, `bounds`, `shift`,
`return`, `unreachable`, and the float checks across 570 lines of
`iree_djl_core` and 633 lines of JNI shim.

The work is also a rehearsal. Once validated here it moves to
`djl-executorch-engine`, whose `native/` tree carries the same `core/`, `jni/`,
`harness/`, `test/`, `build_qa.sh` and `CMakeLists.txt` skeleton, and whose issue
\#11 is the sibling of issue 16.

## Goals

- Detect C++ undefined behavior in `iree_djl_core` and in the JNI shim.
- Detect JNI-contract violations of the issue-16 class automatically rather than
  by code review.
- Land as blocking CI gates, but only after a clean local baseline.
- Keep the mechanism portable to `djl-executorch-engine`.

## Non-goals

- Code coverage. Separate lever, separate design.
- Instrumenting the IREE runtime. The pinned dist is an uninstrumented Release
  build of prebuilt archives; only our own translation units get instrumented,
  and dist code inlined into them is ignorelist material, not a fix target.
- Windows UBSan. MSVC has none. The Windows branch of `build_qa.sh` is unchanged,
  documented like the existing LeakSanitizer omission.

## Architecture: two gates, three trees

### Gate A — UBSan on the native QA tree

A new `IREE_DJL_UBSAN` CMake option composing onto the existing ASan build. Unlike
the ASan/TSan pair, which `native/CMakeLists.txt:104-109` declares mutually
exclusive, UBSan combines with ASan freely; it inherits incompatibility with TSan
only through ASan's.

No new tree and no new script. `native/qa/` gains UB checking over
`iree_djl_core`, both Catch2 suites, and the leak harness. `native/build_qa.sh`
passes `-DIREE_DJL_UBSAN=ON` alongside its existing `-DIREE_DJL_SANITIZE=ON` in
the Linux branch only.

The shim is **not** covered by this gate: `native/CMakeLists.txt:222` skips
`iree_djl` under any sanitizer so QA stays JVM-free. That is deliberate and stays.

### Gate B — UBSan on the JNI shim, exercised by the JVM suite

A new `native/ubsan/` tree built by a new `native/ubsan_gate.sh`, and the only
place `iree_djl_jni.cpp` is ever instrumented.

This requires relaxing the `native/CMakeLists.txt:222` guard so the shim *is*
built when UBSan is the only sanitizer active. The relaxation is sound in a way it
would not be for ASan or TSan: UBSan needs no runtime preload, and
`-static-libubsan` folds the diagnostic runtime into the `.so` itself, so a stock
JVM can `dlopen` it with no `LD_PRELOAD` and no "ASan runtime does not come first"
failure. Verified on this host's gcc 13.3.

The gate then runs the JVM suite against that library:

```
IREE_LIBRARY_PATH=native/ubsan/libiree_djl.so ./gradlew test --rerun-tasks
```

`LibUtils` honours `IREE_LIBRARY_PATH` ahead of the classpath copy and bypasses
extraction entirely, and `build.gradle.kts:226-231` already declares it as a
`Test` task input so Gradle's up-to-date check respects the swap. Nothing is
staged into `src/main/resources`, so the instrumented-library trip-wire in
`CLAUDE.md` never fires and the plain tree stays intact. `--rerun-tasks` is
required regardless, per the same trip-wire.

### Gate C — `-Xcheck:jni`

The JVM's built-in JNI checker, added as a `jvmArgs` entry on the `Test` task in
`build.gradle.kts`. It runs against the **plain** shipping library on every
`./gradlew test`: no special build, no new tree, no CI cost.

It is the only one of the three that would have caught issue 16, and the only one
that ports to `djl-executorch-engine` as a single line.

### Resulting coverage

The shim ends up covered twice by two different nets — C++ UB via Gate B, JNI
contract via Gate C — and `iree_djl_core` gains UB checking on top of the ASan and
TSan coverage it already has.

## Check set

Start from GCC's `-fsanitize=undefined` group and adjust at both ends.

**Add**, because GCC deliberately excludes them from the umbrella:

- `float-cast-overflow`
- `float-divide-by-zero`

**Drop**:

- `vptr`. It requires every translation unit holding a polymorphic object to be
  instrumented, and is the check most likely to misfire on objects crossing into
  the uninstrumented dist. Nothing needs it: the RAII handles in
  `native/core/iree_handles.h` are non-polymorphic.

**Keep prominent**: `alignment`. This is the check that earns the gate, firing on
`reinterpret_cast` of a JVM-supplied host buffer against IREE's 64-byte
precondition.

### Known gap

The check that would have caught issue 15's truncation is
`-fsanitize=implicit-signed-integer-truncation`, which is **clang-only**. GCC 13.3
has no equivalent, so on the current toolchain that class stays uncovered by
tooling. Adding a clang variant of Gate A later is a clean follow-on, not a
prerequisite. This is recorded so the gate is not mistaken for total.

## Failure behavior

UBSan defaults to print-and-continue, which produces a gate that stays green while
diagnostics scroll past. Therefore:

- `-fno-sanitize-recover=undefined` at compile and link, so the first diagnostic
  aborts with a nonzero status.
- `UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1` exported by both scripts.

Under Gate B this means a UB hit presents as a **JVM hard crash** mid-test, not a
Java exception or an assertion failure. That is correct for a gate but confusing
to read cold, so `ubsan_gate.sh` states it up front, as `tsan_gate.sh` does for
`setarch -R`.

## Suppressions

Diagnostics can fire on IREE dist header code inlined into our translation units,
which cannot be fixed upstream. Handle this with a compile-time
`-fsanitize-ignorelist=native/ubsan-ignorelist.txt`, committed empty, with every
entry carrying a written justification. Compile-time ignorelisting is reliable in
a way `UBSAN_OPTIONS=suppressions=` is not — the latter covers only a subset of
checks.

## Toolchain

`native/build_qa.sh:73-79` installs `gcc-toolset-${VER}-libasan-devel` as a
fallback for host runs and bare bases. It needs a `-libubsan-devel` sibling, and
the pinned toolchain images from `431f649` should bake it in the same way.

## Rollout

Baseline locally to green before any CI wiring, cheapest-first:

1. **Gate C.** A one-line change against the existing plain library, and the most
   likely of the three to find something — it audits every JNI call in a shim
   that has already yielded three bugs of exactly this class. Fix what it reports
   before moving on.
2. **Gate A.** Two flags plus the CMake option. Findings are either real UB in
   `iree_djl_core` or inlined dist-header noise destined for the ignorelist.
3. **Gate B.** Last, since it is the only one needing a new tree and script, and
   it benefits from Gate A's ignorelist already existing.

### CI wiring, once green

- **Gate A** needs no new job. It rides inside `build_qa.sh`, which
  `.github/workflows/native-build-job.yml:64-70` already runs on both Linux matrix
  rows. The Windows branch is untouched.
- **Gate C** needs no new job. It rides inside the `./gradlew test` at
  `.github/workflows/native-build.yml:67`. Being a JVM flag it works on Windows
  for free.
- **Gate B** is the only new step, scoped to **linux-x86_64 only**. A second full
  native build plus a `--rerun-tasks` JVM suite is real CI time, and per the
  primary-platform decision aarch64 gets documented gaps rather than duplicated
  gates.

## Documentation

- `CONTRIBUTING.md`: both gates in the QA section, with invocations.
- `CLAUDE.md`, three trip-wires:
  - The `native/ubsan/` tree must never be staged into `src/main/resources` —
    same hazard as the ASan tree, different flag.
  - A UB hit under Gate B presents as a JVM crash, not a test failure.
  - Every `ubsan-ignorelist.txt` entry needs a written justification, or it
    silently becomes permanent.

Per repo style, no emoji in any of it.

## Porting to djl-executorch-engine

`~/workspace/djl-executorch-engine/native/` carries the same skeleton, so:

- **Gate C** is a one-line copy.
- **Gate A** is the CMake option plus two flags, near-verbatim.
- **Gate B** has one unverified dependency: it needs that repo to have an
  `IREE_LIBRARY_PATH` equivalent — a `LibUtils` environment override that
  bypasses resource extraction. **Confirm this before promising the full
  three-gate port.** If it is absent, Gate B there needs either that seam added
  first or a staging-and-restore approach instead.

For this reason Gate B's requirement is stated abstractly — "a documented
environment override selecting which library is loaded, declared as a test input"
— rather than as a specific variable name.

## Testing

Each gate is itself test infrastructure, so verification is that it fails when it
should:

- **Gate A / B:** confirm detection with a deliberate, temporary UB expression in
  a QA-only translation unit (for example a misaligned load in the leak harness),
  observe the abort and nonzero exit, then revert. Do not commit the probe.
- **Gate C:** confirm by temporarily reverting one null check from `5cb8c00` under
  a forced-allocation-failure path, or by asserting that `-Xcheck:jni` appears in
  the resolved `Test` task JVM arguments if the failure path proves impractical to
  trigger.
- **Regression safety:** `./native/build.sh` followed by `./gradlew test
  --rerun-tasks` must still pass against the plain library, confirming no
  instrumented artifact leaked into the shipping path.
