# Borrowed Host Buffers — Spike Findings (2026-08-04)

Answers to `docs/borrowed-host-buffers-brief.md`'s four questions, on the current
tree (`spike/borrowed-host-buffers`, HEAD 7373f6c), linux-x86_64, JDK 17
(Zulu 17.0.19), glibc 2.39, pinned `iree-runtime-dist` v3.11.0-3 (runtime commit
e4a3b040, compiler `iree-base-compiler==3.11.0`).

## §1 Determination table

| # | Question | Status | Pointer |
|---|----------|--------|---------|
| 1 | Does the engine copy host data per inference call, and at what cost? | **Answered** (copies; cost measured below) | §2 inventory; prior measurement `docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md:17-33`; W2 table §3 |
| 2 | Is the 64-byte-alignment hazard real here or already handled? | **Answered** (contract real; misalignment is a zero-copy miss, not a fault; kernel-side codegen assumption UNVERIFIED) | W3 probes, §4 |
| 3 | Are borrow-lifetime hazards tractable under this architecture? | **Answered** (tractable; evidence: leak harness `ImportEscapeCheck` + new free-after-invoke ASan test + Cleaner alive-counter test) | §5 |
| 4 | Is a shared aligned-native-buffer abstraction worth extracting? | **Answered** (duplicate, don't extract) | §7 |

The genuinely open question at skeleton time — whether IREE would zero-copy
import a JVM-supplied input buffer — is re-confirmed on the current tree: **no
(deterministically)**. The 2026-07-19 answer still holds, with one nuance
recorded in the gate deviation below.

### W1 gate run (2026-08-04)

Native QA (`bash native/build_qa.sh`, ASan/LSan Debug tree): PASS —
11 Catch2 cases + 9 parameter cases, leak harness clean at 1000 iterations
× (local-sync, local-task, parameter-bound). The Catch2 case and the harness
both print, for a 64-byte-aligned host allocation, `import outcome = WRAPPED`.

Java suite (`./gradlew test`, staged release .so):
`JAVA DIRECT BYTEBUFFER IMPORT OUTCOME: WRAPPED (zero-copy)` on the first
full-suite run, then `STAGED (copied)` on 5/5 repeat runs of the same test.

**Gate deviation (recorded per plan Step 1.6):** the first run's WRAPPED is
not a runtime change. A standalone alignment probe (`DirectBuffer.address()`
via reflection on `java.nio.Buffer.address`) shows JDK direct-buffer
addresses are not deterministically 64-byte-aligned: across 1000 allocations
per size, 16–256 KB sizes landed in 4 distinct `addr % 64` buckets with only
~25–37% at 0 mod 64 (1 MB allocations are mmap-backed and always page-aligned
→ 0 mod 64). So a small fraction of JDK direct buffers *do* meet IREE's import
precondition, and the import outcome for a small unaligned-capable buffer is
per-allocation luck, not a stable property. The deterministic statement is
unchanged: **user-supplied Java direct buffers cannot be relied on for
zero-copy; 64-byte-aligned engine allocations import zero-copy reliably.**
The §3 bench uses engine-controlled aligned storage so the measurement is not
subject to this luck; the Step 2.4 histogram (JNI `bufferAddress`) records the
distribution precisely in §4.

## §2 Current-path copy inventory

### Input path

1. `IreeNDManager.create(...)` copies user data (from a heap `float[]` /
   `Buffer`) into an engine-owned direct `ByteBuffer`:
   `IreeNDManager.create` → `allocateDirect(capacity)` +
   `BaseNDManager.copyBuffer` (see §6 for the flag-gated replacement of
   `allocateDirect`).
2. JNI `invoke` borrows the buffer's address for the call duration:
   `GetDirectBufferAddress` / `GetDirectBufferCapacity`
   (`native/jni/iree_djl_jni.cpp:175-181`). The Java region stays pinned
   across the boundary for exactly that window — a JNI argument is a GC root
   for the duration of the native call, so mid-call collection is impossible
   (see §5.1).
3. `ImportOrCopy` (`native/core/iree_runtime.cpp:215-246`) tries a zero-copy
   import first: `iree_hal_allocator_import_buffer` with
   `IREE_HAL_EXTERNAL_BUFFER_TYPE_HOST_ALLOCATION` (→ `kWrapped`); when the
   allocator's preconditions are unmet it consumes the refused status and
   stages a copy via `iree_hal_buffer_view_allocate_buffer_copy` (→ `kStaged`).

Measured on this tree: a 64-byte-aligned native allocation imports zero-copy
(kWrapped, deterministic); a JDK direct `ByteBuffer` imports zero-copy only
when its malloc'd address happens to be 64-byte-aligned (~25–37% of small
allocations; 0%–100% depending on size class — see §4), else stages.

### Output path

Always two copies, by design:

1. `iree_hal_buffer_map_read` into an owning `std::vector<std::byte>`
   (`native/core/iree_runtime.cpp:299-301`) — nothing IREE-side may outlive
   `Invoke`, so every output is materialized into an owning buffer and its
   HAL view released immediately.
2. JNI `ByteBuffer.allocateDirect` + `memcpy`
   (`native/jni/iree_djl_jni.cpp:226-240`) — the JVM-owned direct buffer is
   the return vehicle.
3. Java `wrap()`s the returned buffer without copying
   (`IreeNDManager.wrap`).

### "Was import considered and rejected?"

Yes — deliberately. The import-or-copy fallback was the skeleton plan's
explicit choice: `docs/superpowers/plans/2026-07-19-djl-iree-engine-skeleton.md:847-887`.
`iree_hal_heap_buffer_wrap` appears nowhere in repo history except the brief
itself (grep of the whole tree: only `docs/borrowed-host-buffers-brief.md` and
the dist's own headers match). It was never considered, but it is not a missing
option: per `iree/hal/buffer.h:1099-1100` in the pinned dist, wrap refuses
buffers below `IREE_HAL_HEAP_BUFFER_ALIGNMENT` with `IREE_STATUS_OUT_OF_RANGE` —
the same 64-byte precondition as the import path. Any pointer that wraps is a
pointer that imports.

### Marshalling hazard found during the audit (fixed)

The JNI `invoke` marshalling trusted the caller: it indexed `shapes[i]` and
`types[i]` for `i < count` without checking the Java arrays were that long.
The repo's own tests passed a 1-element `shapes` array (and, in one case, a
1-element `types` array) for the add model's TWO inputs. The resulting
out-of-bounds `GetObjectArrayElement`/`GetLongArrayRegion`/`types[1]` reads
were UB that happened to survive on past JVMs — and on 2026-08-04
deterministically SIGSEGV'd three different JDK generations (Zulu 17, Corretto
21, Zulu 25) at three different crash sites (G1 write barrier, JNI handle
resolution, `jni_GetArrayLength`), taking the whole test JVM down. Not a JVM
bug, not a regression from this spike: a latent caller-trust bug in the
boundary layer, exposed by the environment. Fixed on both sides: the tests now
pass correctly sized arrays, and `Java_org_measly_iree_jni_IreeNative_invoke`
validates `len(shapes) == len(types) == count` up front, throwing a
`RuntimeException` (`ThrowJava`'s cached `java/lang/RuntimeException` — not
`IllegalArgumentException`) instead of reading out of bounds. Tracked as
[issue #13](https://github.com/measly-java-learning/djl-iree-engine/issues/13)
for backport to `main` (the validation hunk is self-contained; the rest of this
spike is independent of it).

## §3 Copy cost vs kernel time (W2)

`tools/export_bigscale.sh` + `native/bench/iree_copy_bench` (release build,
RelWithDebInfo, linux-x86_64, local-sync, `--iree-llvmcpu-target-cpu=generic`
— no SIMD, so these are worst-case-ish kernel times; a real model's kernel is
compute-heavier, see the MobileNet denominator below). Elementwise `y = 2*x`,
one f32 input/output of N elements; 50 ms minimum samples per cell, µs per
invoke.

| N (f32) | memcpy | invoke_staged | invoke_wrapped | outcome (wrapped/staged rows) | output_copy |
|--------:|-------:|--------------:|---------------:|:-----------------------------:|------------:|
| 4,096 (16 KB) | 0.26 | 13.08 | 11.31 | WRAPPED / WRAPPED* | 0.26 |
| 65,536 (256 KB) | 15.32 | 616.52 | 70.86 | WRAPPED / STAGED | 15.49 |
| 1,048,576 (4 MB) | 575.21 | 14,243.74 | 2,348.07 | WRAPPED / STAGED | 597.47 |
| 16,777,216 (64 MB) | 16,968.76 | 117,081.55 | 71,231.99 | WRAPPED / STAGED | 17,450.41 |

\* The 16 KB malloc'd row wrapped: glibc `malloc(16 KB)` returned a
64-aligned chunk — the same per-allocation luck measured for JDK direct
buffers in §4. Below the mmap threshold, "user malloc" is not
deterministically STAGED either.

Derived (µs):

| N | input-copy delta (staged − wrapped) | implied kernel (wrapped − output_copy) | copy share, memcpy-floor: (in+out)/invoke_wrapped | copy share of staged call: (delta+out)/invoke_staged |
|---:|--:|--:|--:|--:|
| 4,096 | 1.77 (both zero-copy; noise) | 11.05 | 4.6% | — |
| 65,536 | 545.66 | 55.37 | 43.5% | 91.0% |
| 1,048,576 | 11,895.67 | 1,750.60 | 49.9% | 87.7% |
| 16,777,216 | 45,849.56 | 53,781.58 | 48.3% | 54.1% |

Readings:

- **The staged input copy is 3–35× a bare memcpy** (545.66 µs vs 15.32 µs at
  256 KB): IREE's `iree_hal_buffer_view_allocate_buffer_copy` path allocates
  its own staging buffer and copies through its own machinery — the real
  "cost of the current path" is far above the memcpy floor.
- **Zero-copy is worth up to ~90% of the staged call** for a memory-bound
  kernel at 256 KB–4 MB. Copies are NOT noise for this workload shape.
- **Implied kernel** (wrapped − output materialization): 11 µs @ 16 KB to
  53.8 ms @ 64 MB — elementwise, scalar (generic CPU target), so
  memory-bandwidth-bound; the ~54 ms for 128 MB of traffic is ~2.4 GB/s.
- The MobileNet denominator (real model, compute-heavy kernel) and the
  Java-side copy cost are measured in the JMH arm below; the decision gate
  (§7) uses the bigscale table plus the JMH arm per the pre-registered
  criteria.

### JMH arm (Java-side copy cost + MobileNet denominator)

`example/src/jmh/java/org/measly/example/CopyCostBenchmark.java` +
`MobilenetBenchmark.java`, run 2026-08-04 15:23–15:27 under the §7 safety
controls, exit 0 (no OOM-kill). **Run deviation, recorded per plan Step
1.6:** the OOM analysis (§2 of the brief) pinned the mechanism — per
invocation `create(float[], Shape)` allocates one native direct buffer and
copies through a `FloatBuffer.wrap` view (zero heap allocation, verified in
`ai.djl.api` 0.36.0 sources: `NDManager.create(float[], Shape)` →
`FloatBuffer.wrap` → `IreeNDManager.create(Buffer, Shape, DataType)` →
`allocateDirect` + `copyBuffer`), so nothing pressures the heap, GC never
runs, the Cleaner never fires, and the aligned arm accumulates native memory
at memcpy bandwidth (~5 GB/s) regardless of tensor size. The prior run's
20.7 GB was ~323 iterations of this. The safe configuration is therefore
bounded iteration time + explicit GC, not a heap cap alone: run with
`-w 250ms -r 250ms -gc true -Xmx1536M` (JMH GCs between iterations, so the
Cleaner drains each iteration's accumulation; peak ≈ 1.3 GB/iteration). Also
fixed en route: the plugin's `jmhJar` wrote `META-INF/services/
ai.djl.engine.EngineProvider` twice (IREE + djl-api's RPC); zip
last-entry-wins made RPC shadow IREE and a fork on the fat jar alone died
with "Deep learning engine not found: IREE". `example/build.gradle.kts` now
sets `jmhJar { duplicatesStrategy = EXCLUDE }` (first entry = IREE); with it,
a direct `java -jar` fork registers IREE and the measurement completes.
[INFERENCE] The 11:57 run still reached warmup (the OOM narrative is
kernel-journal-backed), which is consistent with the plugin's `jmh` task
classpath resolving the service file from a single-entry source (the main
jar) rather than the fat jar's duplicate — but that run's exact classpath is
not recoverable from this checkout.

`CopyCostBenchmark.createCopy` — `manager.create(float[], Shape)` then
close, µs/op, `aligned` = engine-allocated 64-byte-aligned buffers
(`iree.engine.alignedBuffers=true`):

| N (f32) | plain | aligned | delta (aligned − plain) |
|--------:|------:|--------:|------------------------:|
| 4,096 (16 KB) | 3.19 ± 0.75 | 2.56 ± 0.15 | −0.63 |
| 65,536 (256 KB) | 54.70 ± 4.97 | 57.10 ± 4.97 | +2.40 |
| 1,048,576 (4 MB) | 1,431.87 ± 453.07 | 1,271.70 ± 534.69 | −160.17 |
| 16,777,216 (64 MB) | 26,177.48 ± 4,921.28 | 22,553.89 ± 6,422.21 | −3,623.59 |

Readings: the two arms are the same code path and both memcpy the same bytes;
the delta is allocator overhead, not import outcome (the import outcome is
asserted by the JNI/native tests, not here — this benchmark never invokes).
At 4 MB+ the aligned arm is measurably cheaper (−160 µs / −3.6 ms): the JNI
`AlignedAlloc` + Cleaner registration is cheaper than the JVM's
`DirectByteBuffer` allocation machinery (reserve + bits accounting + its own
Cleaner). At ≤256 KB the difference is noise. The Java-side create cost is
**~1.5–2.5× the native memcpy floor** at mid sizes (1.43 ms vs 0.58 ms at
4 MB; 26.2 ms vs 17.5 ms at 64 MB) — the fixed per-call JNI/wrap/manager
overhead is ~2.5–3 µs and dominates at 4 KB (12× the memcpy floor).

`MobilenetBenchmark` — real-model kernel time (the bigscale table's
"implied kernel" is elementwise and memory-bound; MobileNet is
compute-heavy), ms/op, both drivers:

| arm | local-sync | local-task |
|-----|-----------:|-----------:|
| steadyState (warm predictor) | 61.61 ± 7.91 | 64.33 ± 15.29 |
| coldStart (load + first predict) | 84.28 ± 11.25 | 86.85 ± 17.10 |

Putting the two arms together for the ~600 KB MobileNet input (3×224×224
f32): Java create ≈ 0.25–0.4 ms (interpolating the table) against a
61.6 ms kernel — **~0.5% overhead. Copies are noise for this workload
shape**, exactly the brief's prediction; they are material only for
memory-bound kernels (the bigscale table, up to ~90% of the staged call at
256 KB–4 MB) or high-frequency small calls (4 KB: 3.2 µs Java overhead vs
11 µs kernel). The decision gate (§7) weighs both shapes.

## §4 Alignment audit (W3)

**The contract.** `IREE_HAL_HEAP_BUFFER_ALIGNMENT = 64`
(`iree/base/config.h:238-245` in the pinned dist): "Executables are compiled
with alignment expectations and the runtime alignment must be greater than or
equal to the alignment set in the compiler. External buffers wrapped by HAL
buffers must meet this alignment requirement."

**Refusal semantics bound the hazard.** A host pointer below that alignment is
refused at import time (`IREE_STATUS_OUT_OF_RANGE`, documented on
`iree_hal_heap_buffer_wrap` at `iree/hal/buffer.h:1099-1100`, the same
precondition the import path enforces) and the facade stages a copy. So a
misaligned buffer is a **zero-copy miss, not a fault**: it never reaches a
kernel, which bounds the brief's "latent fault" scenario. What remains
UNVERIFIED is the kernel side: whether LLVM-CPU codegen actually emits
aligned vector loads for *wrapped* (imported) buffers. The dist ships no
compiler sources, so this cannot be confirmed from this checkout; the
compiler-side alignment expectation lives in the IREE monorepo's
`compiler/src/iree/compiler/Codegen/LLVMCPU/` lowering (not present here).
Marked UNVERIFIED; the refusal mechanism means the hazard is bounded either
way — a wrapped buffer is 64-aligned by construction.

**Measured on the JVM boundary.** `IreeNative.bufferAddress` over 1000
`ByteBuffer.allocateDirect(16)` buffers (two runs, `IreeNativeTest.recordsDirectBufferAddressAlignment`):

| addr % 64 | run A | run B |
|-----------|-------|-------|
| 0  (64-aligned → imports zero-copy) | 437 | 413 |
| 16 | 66 | 87 |
| 32 | 430 | 412 |
| 48 | 67 | 88 |

~41–44% of small JDK direct buffers happen to be 64-aligned; the rest stage.
The 8-byte JVM floor holds in every sample. Larger allocations shift the
distribution: a reflection probe (`java.nio.Buffer.address`) showed 1 MB
direct buffers are mmap-backed and always page-aligned (0 mod 64); 16 B–256 KB
buffers land in the 4-bucket pattern above. Consequences:

- The import outcome for a user-supplied small buffer is **per-allocation
  luck** — the W1 gate caught exactly this (one WRAPPED in seven runs).
- The internal aligned-new work (test/harness allocations) covers only native
  allocations, not JVM-boundary buffers. The W4 prototype closes that gap:
  engine-allocated `NewDirectByteBuffer`s are 64-aligned by construction, so
  they import zero-copy deterministically.

## §5 Lifetime hazard assessment (W5)

The three brief §4 hazards, assessed against the current architecture. The
borrow contract the W4 path relies on: **the engine allocates; the user
writes into what the engine hands back; the buffer lives until its
`ByteBuffer` is unreachable and the Cleaner runs; a call can only ever
observe a live buffer.**

**Lifetime versus GC.** The Java region is pinned across the native call by
JNI itself: a JNI argument is a GC root for the duration of the call, so the
buffer whose address `GetDirectBufferAddress` reads cannot be collected
mid-call — already true of the copy path (§2.2), inherited by the aligned
path. The Cleaner side is proven by
`LeakStressTest.alignedBuffersAreFreedByCleaner`: 10,000 engine-allocated
buffers through `IreeNDManager.allocateDirect`, all references dropped, GC
pressure until `aliveAlignedBuffers()` reaches 0. The native counter is the
leak signal precisely because the buffers are JNI-allocated and NOT counted
against `-XX:MaxDirectMemorySize` (the W2 OOM mechanism — §3). Two design
rules make the Cleaner reliable: the registered action captures ONLY the
address primitive (capturing the `ByteBuffer` keeps it strongly reachable
and the Cleaner never fires — commented in `IreeNDManager.allocateDirect`),
and `freeDirectAligned` treats 0 as a no-op, so a mis-registration can never
double-free.

**Completion versus return.** `IreeRuntime::Invoke` is synchronous for both
drivers: `iree_runtime_call_invoke(call.get(), /*flags=*/0)`
(`native/core/iree_runtime.cpp:275`) blocks until the invocation completes —
including under local-task, where the fence is waited before return. So call
return == completion signal, today. The native-side guarantee underneath is
proven by the `aligned buffer freed after invoke leaves no dangling import`
ASan case (`native/test/iree_runtime_test.cpp`): the imported input is freed
immediately after `Invoke` returns and a second invoke runs on fresh input —
any retained IREE-side pointer would be a use-after-free under ASan. This
records a hard constraint: **the borrow path is safe exactly while the
engine stays synchronous.** If async execution (invoke returns before the
fence) is ever added, the contract breaks for the aligned path and the
existing copy path alike (same import mechanics); the fix would be
refcounting or a completion callback, not a flag. Proceed-with-constraints
input, not a blocker.

**Aliasing.** A kernel that writes into a borrowed input silently mutates
the user's buffer. Assessed: (a) the engine is inference-only by design
(`IreeEngine.hasCapability` is false; no DJL training surface exists), so
models in scope read inputs; (b) the import is flagged
`IREE_HAL_EXTERNAL_BUFFER_FLAG_NONE` and neither the add fixture nor
MobileNet writes inputs; (c) the copy path stays the default, so the hazard
is opt-in. Nothing in IREE's runtime enforces read-only inputs for an
arbitrary `.vmfb`, so the contract is documented as "kernels must not mutate
borrowed inputs" — the same design question the brief flagged, answered
"tolerable behind a flag, revisit if the engine grows training or in-place
ops".

## §6 Prototype summary (W4)

**Files** (new unless noted):

- `native/core/aligned_alloc.h` — the single source of the alignment
  contract: `kBufferAlignment = 64` (`IREE_HAL_HEAP_BUFFER_ALIGNMENT`),
  C++17 aligned new/delete (MSVC-compatible, per the brief's own
  correction), `g_aligned_live` atomic counter for leak probes. Commented as
  the W6 extraction seam.
- `native/jni/iree_djl_jni.cpp` — four new functions: `bufferAddress`
  (alignment probe; 0 for non-direct), `allocateDirectAligned`
  (`AlignedAlloc` → `NewDirectByteBuffer`; frees the block if
  `NewDirectByteBuffer` fails so an OOM never leaks), `freeDirectAligned`
  (idempotent; 0 = no-op), `aliveAlignedBuffers` (native leak probe). Plus
  the invoke marshalling validation (shapes/types length check — issue #13,
  self-contained, tracked for `main` backport).
- `src/main/java/org/measly/iree/jni/IreeNative.java` — JNI declarations,
  alignment contract in javadoc.
- `src/main/java/org/measly/iree/engine/IreeNDManager.java` —
  `allocateDirect` is flag-gated (below).
- Tests: `AddModelIT.runsAddWithAlignedBuffersFlag` (e2e: import outcomes
  {1,1} through the full DJL stack, golden result unchanged),
  `IreeNativeTest.recordsDirectBufferAddressAlignment` (§4 histogram) and
  `reportsImportOutcomeForJavaDirectBuffers`,
  `LeakStressTest.alignedBuffersAreFreedByCleaner` (§5), native
  `aligned host allocation imports zero-copy` and
  `aligned buffer freed after invoke leaves no dangling import` (ASan).

**Flag and behavior.** `-Diree.engine.alignedBuffers=true` (read per
allocation, so it toggles around a measurement):
`IreeNDManager.allocateDirect` returns an engine-allocated 64-byte-aligned
direct `ByteBuffer` instead of `ByteBuffer.allocateDirect`. Its address goes
straight through the existing import path, which wraps it zero-copy
(kWrapped) deterministically — vs the JDK buffer's per-allocation luck
(~41–44% of small allocations, §4). The buffer is freed by a
`java.lang.ref.Cleaner` when unreachable, and is not counted against
`-XX:MaxDirectMemorySize`. Default `false` = the unchanged copy path.
Nothing else in the engine is touched: the import-or-copy fallback, the
output double-copy, and the DJL surface are identical under both settings.

**Verification.** Native QA (`native/build_qa.sh`, ASan/LSan Debug) passed
on this tree, including a constrained replay (2026-08-04). Java suite
re-run 2026-08-04 under the §7 controls (systemd-run scope, MemoryMax=4G,
taskset 0-3): `./gradlew test leakTest --no-configuration-cache` — 63 + 2
tests green, including the four W4-specific tests above.

**Status: prototype complete; not merged.** Both paths stay; the flag is the
A/B switch for W2 (JMH results in §3) and the honest fallback if the §5
constraints ever bind.

## §7 Recommendation (W6)

### Decision gate (W2 + W3 + W5)

**Decision: proceed-with-constraints.** Borrow-based host buffers are
feasible, measured, and safe under the current architecture; they ship
behind the prototype flag, not as the default.

The pre-registered criteria, applied:

- **W2 (cost):** material only for memory-bound kernels. Zero-copy is worth
  up to ~90% of the staged call at 256 KB–4 MB on the elementwise shape; the
  staged input copy is 3–35× a bare memcpy. For the real-model denominator
  (MobileNet, 61.6 ms kernel vs ~0.3 ms Java create) the copy is ~0.5%
  noise. So the borrow path is a real win for the memory-bound shape and a
  no-op for compute-heavy models — worth shipping as an option, not worth
  forcing on users.
- **W3 (alignment):** the hazard is bounded, not live: misalignment is a
  zero-copy miss (refused at import, staged), never a fault; the prototype's
  engine-allocated buffers are 64-aligned by construction. The one UNVERIFIED
  item (whether LLVM-CPU codegen emits aligned loads for wrapped buffers) is
  moot for the aligned path — a wrapped buffer is 64-aligned by construction.
- **W5 (lifetimes):** tractable. Synchronous invoke means call return ==
  completion for both drivers today; JNI pins the buffer for the call; the
  Cleaner lifetime is proven; aliasing is bounded by inference-only + opt-in.
  The constraint this imposes on the roadmap is real: async execution must
  not ship while the borrow path exists without redesigning the contract.

### User-facing API constraint (README-terms)

If go-with-constraints ships, the README gets the following (drafted here):

> **Zero-copy inputs.** `IREE` copies caller data into engine-owned buffers by
> default. Set `-Diree.engine.alignedBuffers=true` to have the engine allocate
> 64-byte-aligned buffers instead; these import into the runtime zero-copy.
> JDK `ByteBuffer.allocateDirect` buffers are *not* reliably importable —
> the JVM guarantees only 8-byte alignment and IREE requires 64, so a
> user-supplied direct buffer imports zero-copy only when its malloc'd
> address happens to be aligned (~40% of small allocations) and otherwise
> stages a per-call copy. The engine allocates; the user writes into what
> the engine hands back.

Constraints recorded alongside: (1) the flag defaults to off — the copy path
remains the safe default; (2) the borrow contract is synchronous-only —
revisit before any async invoke ships; (3) borrowed inputs must not be
mutated by kernels (inference-only scope).

### W6: extract or duplicate?

**Duplicate — do not extract.** The apparent commonality is
`AlignedAlloc`/`AlignedFree` (a ~30-line header with a counter) plus the
JNI exposure (`NewDirectByteBuffer` + `Cleaner` + per-engine JNI symbols).
The JNI half is engine-specific by nature — function names, cleaner wiring,
and leak probes are this engine's own ABI surface — and a prospective
TVM-FFI engine's borrow model differs in kind (caller-provided `TensorView`,
no allocator, no ownership). A shared module would carry build, versioning,
and ownership costs for ~60 lines of genuine overlap. The seam is already
marked (`aligned_alloc.h` header comment); extract only if the TVM-FFI
engine materializes and the overlap is re-measured against something real —
per the brief, W6 is contingent on that engine existing.
