# local-task Support + QA-Gate Reinstatement — Design

**Date:** 2026-07-22
**Status:** Approved design, ready for implementation plan

## Goal

Expose IREE's `local-task` driver (multithreaded, intra-op worker pool) as a
selectable alternative to the current hardcoded `local-sync`, add a benchmark
arm that measures it, and reinstate the QA gate that was deferred to ship 1.0 —
now that a multithreaded execution path finally makes a race gate meaningful.

## Motivation

Through 1.0 the engine hardcodes `local-sync` (`iree_runtime.cpp:44`). That
driver was *measured* to run entirely single-threaded (strace: zero
`clone`/`clone3`; `/proc` Threads:1), which is why the skeleton could ship
without a ThreadSanitizer gate — there were no threads to race. `local-task`
is compiled into the `iree-runtime-dist` artifact and registered on the
instance (`use_all_available_drivers`), but never instantiated. Selecting it
is the first time this engine runs concurrent native code, and it is the point
at which the deferred QA machinery becomes worth wiring up.

## Scope

One branch, passthrough-first: land a working `local-task` path and its
benchmark arm, then wire the QA gate as a merge blocker on the same branch.

**In scope**
- A: `local-task` passthrough (DJL option → JNI → facade driver selection)
- B: benchmark arm (`local-sync` vs `local-task`)
- C: reinstated QA gate (Catch2 units + ASan/LSan leak harness + JVM `leakTest`
  in CI; TSan as a documented local manual gate)

**Explicitly out of scope (deferred, YAGNI)**
- Worker-count / topology knob. MVP uses `local-task`'s driver-default topology
  (typically host cores). An explicit `workers=N` option is a fast-follow, to be
  added when a concrete need to pin the pool size arises (e.g. reproducible
  cross-machine benchmark numbers). Deferring it keeps the passthrough to the
  high-level `try_create_default_device` helper.
- Concurrent-caller safety. The caller contract is unchanged (see below); no
  shim lock is added.
- TSan in GitHub CI (see §C).

## Concurrency contract (unchanged)

`IreeSymbolBlock`'s existing javadoc states the contract: *one Model/Predictor
per thread, one invoke at a time per model; an IREE session is not safe for
concurrent invocation.* Under `local-sync` this held "all the way down" because
nothing below the boundary had threads.

`local-task` does **not** change the caller contract. It adds **intra-op**
parallelism: a worker pool that runs *underneath a single invoke*. Callers still
issue one invoke at a time per model. The only javadoc change is to that "all
the way down" clause — `local-task` adds workers below the boundary while the
caller-facing contract is identical. No lock is introduced; making sessions
concurrent-safe is not required by any consumer.

Consequently, TSan's job is narrow and clean: exercise the native worker pool
via a **single caller** doing repeated load/invoke/close cycles under
`local-task`. There is deliberately no multi-Java-thread concurrent-session test
— that would violate the documented contract.

---

## A — local-task passthrough

The driver name threads through the existing load path as a new parameter,
defaulting to `local-sync` (backward-compatible), mirroring exactly how
`entryPoint` already flows.

**Interfaces (current → new):**
- C++ facade: `IreeRuntime::Load(vmfb, entryPoint)` →
  `Load(vmfb, entryPoint, driver)`. The `driver` string replaces the literal
  `"local-sync"` at `iree_runtime.cpp:44`
  (`iree_runtime_instance_try_create_default_device`).
- JNI: `Java_..._IreeNative_load(env, cls, vmfb, entryPoint)` →
  `(…, entryPoint, device)`; the `jstring device` is marshaled like `entryPoint`.
- Java SPI: `IreeNative.load(byte[] vmfb, String entryPoint)` →
  `load(byte[] vmfb, String entryPoint, String device)`.
- `IreeModel.load`: reads `options.get("device")`, default constant
  `DEFAULT_DEVICE = "local-sync"`, passed to `IreeNative.load` — mirroring the
  existing `options.get("entryPoint")` / `DEFAULT_ENTRY_POINT` handling.

**Validation gate (first implementation step):** confirm
`try_create_default_device("local-task")` succeeds and instantiates the worker
pool (verify with strace/`/proc` that threads now appear, contrasting the
`local-sync` baseline). If the high-level helper cannot create a `local-task`
device with default topology, that is a finding to surface before proceeding.

**Correctness test:** load `add.vmfb` with `device=local-task` and assert the
result is identical to `local-sync`. Same for the invalid case (an unknown
driver name must fail cleanly at load, not crash).

## B — Benchmark arm

`MobilenetBenchmark` gains `@Param({"local-sync", "local-task"}) String device`,
threaded into `criteria()` via the `device` model-load option. Both `steadyState`
(AverageTime) and `coldStart` (SingleShotTime) run per arm.

The class javadoc's "single arm" caveat is rewritten as a **two-arm intra-op
parallelism** note: same model, same f32 weights, same caller contract; the arms
differ only in whether IREE parallelizes a single invoke across a worker pool.
Cross-engine (ExecuTorch/PyTorch) comparison remains manual and out-of-band, as
before. The `local-task` arm's absolute numbers are host-core-dependent until the
worker-count knob lands — the arm is for the `local-sync`-vs-`local-task` delta on
one machine, not a portable figure.

## C — QA gate

The native QA machinery already exists from the skeleton and is simply never
executed in CI (current `native-build-job.yml` builds and uploads only):
- `iree_runtime_test` — Catch2 unit suite (`native/test/iree_runtime_test.cpp`)
- `iree_leak_harness` — ASan/LSan leak gate (`native/harness/iree_leak_harness.cpp`),
  deliberately `local-sync` to stay deterministic
- CMake options `IREE_DJL_SANITIZE` (ASan/LSan) and `IREE_DJL_TSAN`

### C.1 — CI-enforced gate (merge blocker)

Port ExecuTorch's proven pattern (`native/build_qa.sh` + `./gradlew leakTest`,
both run in CI). ASan/LSan and Catch2 need no ASLR changes, so they run fine
inside the manylinux container.

- **`native/build_qa.sh`** (new): build + run `iree_runtime_test` (Catch2) and
  `iree_leak_harness` (ASan/LSan, `-DIREE_DJL_SANITIZE=ON`, iterated) against the
  resolved dist runtime. Mirrors ET's `build_qa.sh` structure.
- **Wire into `native-build-job.yml`**: run `build_qa.sh` in the manylinux
  container as a merge-blocking step, before the shim upload.
- **JVM `leakTest`** (new, net-new Java side): a `register<Test>("leakTest")`
  Gradle task with constrained resources
  (`-Xmx256m -XX:MaxDirectMemorySize=64m -XX:+HeapDumpOnOutOfMemoryError`),
  `useJUnitPlatform { includeTags("leak") }`; `tasks.test` gains
  `excludeTags("leak")` so the stress test stays out of the normal suite. Ported
  from ET's `build.gradle.kts`. It covers what the native harness structurally
  cannot: the JNI boundary and the direct-`ByteBuffer` STAGED import path (the
  native harness is a plain `main()` with no JVM). Wire `./gradlew leakTest` into
  the gradle CI step.
  - Cache-correctness: mirror ET's `EXECUTORCH_LIBRARY_PATH` `inputs.property`
    guard using IREE's `LibUtils` override env var (verify the exact variable
    name during planning) so pointing the tests at a different `.so` invalidates
    the Gradle cache instead of replaying a stale pass.

### C.2 — TSan: local manual gate (not GitHub CI)

TSan needs ASLR disabled (`setarch $(uname -m) -R`, an ASLR/TSan shadow-mapping
conflict). ExecuTorch's CI runs no TSan for exactly this reason, corroborating
the constraint. So TSan is a **documented, required pre-merge manual gate**, not
an automated GitHub CI job.

- **Harness change:** the leak harness (or a sibling harness) gains a
  `local-task` happy-path mode so the worker pool actually spins up — TSan needs
  threads to find races, the opposite of the ASan/LSan harness's `local-sync`
  determinism.
- **`native/tsan_gate.sh`** (new): build the `local-task`-exercising harness with
  `-DIREE_DJL_TSAN=ON` and run it under `setarch $(uname -m) -R`.
- **Docs:** README / contributing notes mark `tsan_gate.sh` as a required manual
  step before merging changes to the native path, and record why it is not in
  GitHub CI (ASLR requirement, no self-hosted runner). A commented CI stub may be
  left for a future self-hosted runner, but is not wired.

## Testing summary

| Gate | Tool | Driver | Where enforced |
|------|------|--------|----------------|
| Unit correctness | Catch2 `iree_runtime_test` | local-sync | GitHub CI |
| Native leaks | `iree_leak_harness` (ASan/LSan) | local-sync | GitHub CI |
| JNI / direct-buffer leaks | JVM `leakTest` (constrained heap) | local-sync | GitHub CI |
| Data races | TSan harness | **local-task** | Local manual (`tsan_gate.sh`) |
| local-task correctness | `add.vmfb` result equality | local-task | GitHub CI (unit) |
| local-task latency | JMH `MobilenetBenchmark` arm | local-task | Manual (benchmark) |

## Risks / open items

- `try_create_default_device("local-task")` may not expose a usable default
  topology; the validation gate in A catches this early. Fallback (dropping to
  explicit `iree_hal` device creation) would pull the deferred worker-count work
  forward — surface as a finding rather than silently absorbing it.
- The `local-task` arm's benchmark numbers are host-core-dependent until the
  worker-count knob lands; the javadoc must state this so the arm is not misread
  as portable.
- TSan enforcement relies on contributor discipline (manual gate). Acceptable
  given the ASLR/CI constraint and the single-caller contract that bounds the
  race surface.
