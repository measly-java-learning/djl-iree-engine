# Production observability surface

**Date:** 2026-08-10
**Status:** Approved, ready for planning
**Scope:** Give this engine a production monitoring surface it currently does not have at all.
Metrics only — the native logging bridge is explicitly deferred to its own cycle.

**Companion:** `/home/corey/workspace/djl-executorch-engine/docs/superpowers/specs/2026-08-09-production-observability-design.md`.
This design follows its shape deliberately, so the two engines' numbers are comparable. Where it
departs, the departure is called out and justified. Two sections here — the registry's reference
model and the close-vs-snapshot lock — encode fixes that emerged during *ExecuTorch's
implementation* and never made it back into that spec. Capturing them up front is a large part of
what this document is for.

## Problem

The engine has **no observability of any kind**. Not "logging but no metrics" — nothing:

- No slf4j usage anywhere in `src/main` or `native/`. (`slf4j-api` is `compileOnly` in
  `build.gradle.kts:24`, but no `Logger` is ever constructed.)
- No native log bridge, no PAL equivalent, no USDT/DTrace probes.
- `IreeSymbolBlock.getLastImportOutcomes()` is the only introspection, and it is last-call-only
  state on the runtime — a test affordance, not a production signal.

An operator therefore cannot answer any of the three questions that matter:

1. **Is inference slow, and is throughput what I expect?**
2. **Is the native side growing without bound?**
3. **Is this deployment configured the way I think it is?**

This engine has a fourth question the ExecuTorch engine does not, and it is arguably the most
important one here:

4. **Are my inputs actually zero-copying, or is every call silently staging a copy?**

The skeleton findings established that a native 64-byte-aligned host buffer imports WRAPPED while
a Java direct `ByteBuffer` STAGES a copy, because JVM buffers miss IREE's alignment
preconditions. That is a silent, per-input, per-call performance cliff with no outward symptom.
An operator running this engine in production has no way to know which side of it they are on.

### Why DJL's own metrics do not close this

`ai.djl.metric.Metrics` / `Predictor.setMetrics(...)` records `Preprocess`/`Inference`/
`Postprocess` timings per `predict()`. Verified against DJL 0.36.0 (the version on this project's
`runtimeClasspath`), it is not a production signal:

- **`Metrics.limit` defaults to 0, meaning uncapped.** Every `predict()` appends three retained
  `Metric` objects. In a long-running server that is unbounded retention unless the caller wires
  both `setLimit` and `setOnLimit`.
- **`addMetric` is check-then-act.** `list.size() >= limit` → `onLimit.accept(...)` →
  `list.clear()` → `list.add(...)` with no lock held, over a `Collections.synchronizedList`. The
  natural usage — one `Predictor` per thread sharing one `Metrics` — races at the flush boundary.
- **`Predictor.timestamp` is a plain non-volatile `long`** instance field, read-modify-written
  across the three phase callbacks. Correct under one-`Predictor`-per-thread, silently wrong if
  shared.
- **Aggregation is O(n).** `percentile()` copies and sorts the full list; `mean()` streams it.

`Metrics` is a time-series buffer for benchmarking and tracing, not a counter aggregator for
always-on monitoring. It stays available and unmodified; our documentation presents it as a
profiling tool and names the uncapped default so users do not reach for it as a production signal.

## Design

### Public API

One new public class in `org.measly.iree.engine`, plus immutable value types.

```java
IreeEngineStats.snapshot()        // -> IreeStatsSnapshot; never throws; cold path
IreeEngineStats.registerMBean()   // explicit escape hatch
IreeEngineStats.unregisterMBean()
```

`IreeStatsSnapshot` is immutable and carries three groups.

**Configuration** — process-wide; answers "is this deployment configured the way I think":

| Field | Source |
|---|---|
| `engineVersion` | `IreeEngine.getEngineVersion()` |
| `ireeRuntimeVersion` | the pinned dist tag — see *Version constant* below |
| `platform` | the resolved platform string (`linux-x86_64`, …) from `LibUtils` |
| `nativeLibraryPath` | the path `LibUtils` actually loaded |
| `stagingMode` | the compiled-in staged-fallback policy (`cached-map-write` today) |
| `nativeStatsAvailable` | whether IREE's allocator statistics are compiled in |
| `jmxStatus` | `REGISTERED` / `DISABLED` / `FAILED` |
| `jmxError` | the failure reason when `FAILED`, else empty |

Note what is **not** here. `driver`, `entryPoint`, and `parameterScopeCount` are per-`Model.load`
arguments (`IreeLoadOptions.device()` defaults to `local-sync`, overridable per load), so they
are per-model fields, not process configuration. `stagingMode` is the only genuinely global one:
`iree_djl_jni.cpp:163` hardcodes `kCachedMapWrite` for every JNI load.

**Process totals:** `modelsLoaded` (cumulative), `modelsLive`, `totalStagingBytes`,
`totalDeviceBytesLive`, and the closed-model rollup — `closedForwardCount`,
`closedForwardTotalNanos`, `closedWrappedImports`, `closedStagedImports`.

**Per-model** — `List<IreeModelStats>`, live models only:

`name`, `driver`, `entryPoint`, `parameterScopeCount`, `loadNanos`, `forwardCount`,
`forwardTotalNanos`, `forwardMaxNanos`, `wrappedImports`, `stagedImports`, `stagingBytes`,
`deviceBytesPeak`, `deviceBytesLive`.

`stagedImports / (stagedImports + wrappedImports)` is the staged-copy rate — the gauge that
answers question 4, and the one to alert on.

### Version constant

No generated Java constant carries the IREE version today: `IreeDataTypeCodegen` emits only
element types. The pinned tag is already available to the build in
`third-party/iree-runtime-metadata.properties` (`ireeRuntimeDistTag=v3.11.0-11`), which Gradle
already consumes. The codegen gains one constant sourced from that file. No new fetch machinery,
and no version literal in Java. Unresolvable → `unknown`.

### Counters

**Java side.** A holder owned by `IreeSymbolBlock` carries `forwardCount`, `forwardTotalNanos`,
and `forwardMaxNanos` as plain `volatile long`. `forwardInternal()` brackets the
`IreeNative.invoke(...)` call with `System.nanoTime()` and updates count, total, and max.

`forward()` is single-writer by the engine's existing threading contract — one `Model`/`Predictor`
per thread, already documented on `IreeSymbolBlock`'s class javadoc and enforced by IREE's own
"a session is not safe for concurrent invocation" rule. So no CAS, no `LongAdder`, no allocation
on the hot path. `volatile` is present solely so the snapshot reader observes the updates and so
64-bit reads cannot tear; the max update is a safe read-compare-write given the single writer.

`loadNanos` is measured in `IreeModel.load` around `IreeNative.load`.

**Native side.** Two `uint64_t` on `RuntimeState`: `wrappedImports`, `stagedImports`. They are
incremented in the input-preparation loop in `RunCall` (`iree_runtime.cpp:338-344`), inside the
branch that already computes the outcome — two adds, no new work.

`RunCall` is the single site that needs to change: both `IreeRuntime::Invoke`
(`iree_runtime.cpp:376`) and `IreeRuntime::InvokeViews` (`:407`) delegate to it. Verified, not
assumed.

**Why this split.** Timing stays in Java to match the ExecuTorch engine's measurement boundary
exactly, which is what makes the two engines' numbers comparable; measuring from inside the
invoke would time a strictly smaller interval and would miss a forward that threw. Import
outcomes stay native because the alternative is calling `IreeNative.lastImportOutcomes(handle)`
every forward — a JNI round-trip plus a `jintArray` allocation on the hot path, to retrieve
something the native side already holds in a local. That inverts the point of the exercise.

### The new JNI method

One cold-path addition:

```java
IreeNative.stats(long handle)
// -> long[6]: [wrapped, staged, stagingBytes, deviceBytesPeak, deviceBytesLive, available]
//    or null if the handle is closed
```

**A `long[]` rather than a new object type, deliberately.** The ExecuTorch spec's first named
footgun was `g_metaCtor`: a cached JNI method ID with a hardcoded signature literal that breaks
at class init whenever the Java constructor changes. This JNI has the same cached-constructor
pattern for `IreeTensor`. Returning a primitive array adds no second instance of that hazard —
the array is unpacked into `IreeModelStats` in Java, where the compiler checks the shape. The
index layout is a documented constant on the Java side, not a magic number at the call site.

**Returning `null` on a closed handle rather than throwing** is a deliberate departure from this
file's prevailing throw-on-closed style (`invoke`, at `iree_djl_jni.cpp:197`). The caller is a
monitoring poll that must never throw; Java skips the entry. The JNI needs a comment saying so,
or it reads as an oversight. Allocation null-checks follow the pattern established by `5cb8c00`.

### Registry and lifecycle

`IreeEngineStats` holds a `ConcurrentHashMap` of live models keyed by native handle. `IreeModel`
registers at load; `IreeSymbolBlock.close()` deregisters, folding the model's totals into a
process-level closed-model bucket rather than discarding them. A restart-on-error loop would
otherwise erase exactly the throughput and staged-import history an operator needs. Per-model
detail is live-only; aggregates cover the process lifetime.

**The registry entry holds the block weakly and its counters strongly.**

```java
private static final ReferenceQueue<IreeSymbolBlock> REAPED = new ReferenceQueue<>();
private static final Map<Long, ModelRef> LIVE = new ConcurrentHashMap<>();

private static final class ModelRef extends WeakReference<IreeSymbolBlock> {
    final long handle;
    final IreeModelCounters counters;
}
```

This map is `static` and lives for the JVM, so it is a GC root. A strong entry would pin
`IreeSymbolBlock` — and through it the model's `IreeNDManager` and every `NDArray` still attached
— for the life of the process. A caller who drops a model without closing it already leaks the
native IREE session (there is no `Cleaner` or finalizer on `IreeSymbolBlock`; `close()` is the
only release path). A strong entry would turn that native-only leak into a permanent heap leak
and make `modelsLive` climb forever. **Observability must not cause the leak it exists to
detect.**

The counters are held strongly because they are the only part worth keeping — a few longs and a
couple of string references, independent of the block's object graph — and holding them lets a
collected model's forwards still reach the rollup, which a bare `WeakReference` would lose.

*Honest limit, carried over from the ExecuTorch implementation's javadoc:* the weak reference
does not make a leaked model collectable. DJL's `BaseNDManager` attaches every base manager to a
static system manager, so the model stays reachable regardless. The weak reference stops *this
class* from being a cause. That retention is DJL's and predates this work.

**Reaping.** `purgeCollected()` drains `REAPED`, folding each collected model into the rollup and
dropping its entry. It is called from both `register()` and `snapshot()`, so the map self-heals
on any activity, and it costs O(models collected) — usually zero — not O(models tracked).

The drain uses the **two-argument** `LIVE.remove(ref.handle, ref)`. IREE handles are pointers, so
after a close the allocator can hand the same address to a new model. Removing by key alone would
evict the live model and double-count the dead one. The compare-and-remove makes both impossible.
This is not hypothetical bookkeeping hygiene; it is a real aliasing hazard for any
pointer-as-handle registry.

### The close-vs-snapshot race

`stats(handle)` on a closed handle is a use-after-free. The ExecuTorch *spec*'s stated fix — read
the handle once into a local and skip if zero — narrows the window but does not close it: a
monitoring thread can read a non-zero handle, `close()` can then free the runtime, and the JNI
call then runs on freed memory. That is tolerable for a debug affordance and unacceptable for a
surface whose entire premise is never breaking production.

**Fix: a per-block `statsLock` monitor, taken only by `close()` and by the stats read.**

- `close()` synchronizes on it, and inside: deregisters first (so no poll can reach a handle it is
  about to free), then calls `IreeNative.close(handle)`, then zeroes the handle.
- The per-model stats read synchronizes on the same monitor around the handle read and the JNI
  call.
- `forwardInternal()` never touches it. Both `close()` and `snapshot()` are cold paths — once per
  model and once per monitoring interval respectively — so the hot-path claim is unaffected.

This mirrors what the ExecuTorch implementation actually shipped.

### JMX

`IreeEngineStatsMXBean` registers as `org.measly.iree:type=IreeEngineStats` on the platform MBean
server at the first model load, opt-out via `ai.djl.iree.jmx_enabled=false`.

It is an **MXBean**, not a Standard MBean, so `List<IreeModelStats>` converts to `CompositeData`/
`TabularData` automatically with no hand-written `OpenType` code. Two consequences the
implementation must respect:

- **The `MXBean` suffix on the interface name is load-bearing.** There is no annotation. Renaming
  the interface silently degrades it to a Standard MBean, at which point the `List` conversion
  stops applying and registration fails with `NotCompliantMBeanException`.
- **`IreeModelStats` must be a conforming bean:** public getters, no setters, no public fields.

The public API methods stay named `registerMBean()`/`unregisterMBean()`, matching both the
ExecuTorch engine and `MBeanServer`'s own method names, which are spelled that way for MXBeans
too.

Registration is attempted exactly once. Failure — name collision, `SecurityManager`, a restricted
container — produces a single logged warning, sets `jmxStatus=FAILED` with `jmxError`, is never
retried per load, and never fails a load.

## Native layer

A new facade accessor returning a POD:

```cpp
struct RuntimeStats {
  uint64_t wrappedImports, stagedImports;
  uint64_t stagingBytes;                    // ours, exact
  uint64_t deviceBytesPeak, deviceBytesLive;
  bool statisticsAvailable;
};
```

### Staging bytes — no IREE cooperation needed

`stagingBytes` is the sum of `RuntimeState::cachedStagingSizes`, the grow-only per-input-slot
staging buffers the cached staging modes retain for the runtime's lifetime. It is exact and
depends on nothing external.

Unlike the ExecuTorch engine's equivalent gauge — which is 0 for essentially every real model,
because memory-planned inputs never stage — **this one is non-zero on real models**, because the
JVM `ByteBuffer` alignment miss is precisely what forces staging. It is the footprint of the
copy-fallback path, and it is the normal case here.

It *is* 0 under `kAllocatePerCall`, which retains no cached buffers. The JNI always loads with
`kCachedMapWrite` (`iree_djl_jni.cpp:163`), so this only affects the native tests and the leak
harness, which use the other overloads. The Catch2 coverage must select the cached mode
explicitly or it will assert against a structurally-zero gauge.

### Device bytes — IREE allocator statistics

`iree_hal_allocator_query_statistics(iree_hal_device_allocator(device))` yields
`device_bytes_allocated` / `_freed` / `_peak`. We report `deviceBytesPeak` and
`deviceBytesLive = allocated - freed`.

**Attribution is free here**, which is the one place this engine has it materially easier than
ExecuTorch. Each `RuntimeState` owns its own `DevicePtr`, so the allocator's statistics are
already scoped to exactly one model. The XNNPACK-workspace attribution problem that defeated the
ExecuTorch design has no analogue.

The six raw IREE fields collapse to two on purpose. The host/device split is an artifact of
IREE's memory-type bits and largely meaningless for the CPU-only local drivers we ship, and the
raw cumulative `allocated`/`freed` pair is a dashboard nobody reads. `deviceBytesLive` is the
leak signal to alert on; `deviceBytesPeak` is the sizing figure.

### `statisticsAvailable` and the version-bump hazard

`IREE_STATISTICS_ENABLE` is a compile-time macro. It is trustworthy today *because we compile
against the dist's own `include/iree/base/config.h`* — our view and the archives' cannot
disagree. Verified for the pinned `v3.11.0-11` dist: `config.h:183-189` defaults it to `1`
unconditionally (it is **not** `NDEBUG`-gated, so `-DCMAKE_BUILD_TYPE=Release` does not disable
it), `BUILDINFO`'s `cmake_flags` does not override it, and
`iree_hal_heap_allocator_query_statistics` is present in `libiree_hal_hal.a`.

The hazard is a future dist that passes `-DIREE_STATISTICS_ENABLE=0` in `cmake_flags` while our
header default stays 1. The struct layouts would then disagree — a silent ABI mismatch producing
garbage gauges. **Deliverable: a CMake check that reads `cmake_flags` out of the dist's
`BUILDINFO` and propagates the setting rather than trusting the header default.** Cheap, and it
converts a future silent-wrong-numbers bug into a build-time fact.

`statisticsAvailable` is surfaced as the snapshot's `nativeStatsAvailable`; when false, the
device byte gauges report `-1`.

### Build cost

The new JNI method means `linux-aarch64` and `windows-x86_64` need a rebuild and restage before
their JVM tests pass, alongside `linux-x86_64`.

## Error handling

A monitoring surface must never be the thing that breaks production.

- `snapshot()` never throws. A model whose native state cannot be read contributes a degraded
  entry rather than propagating the failure.
- Byte gauges use **`-1` for "unavailable"** and **`0` for "genuinely zero"**. The distinction is
  load-bearing twice: `stagingBytes == 0` means "nothing has staged yet," a real and meaningful
  state; `deviceBytes == -1` means statistics are compiled out. Process totals skip `-1` so
  "unavailable" never sums in as a value.
- Unresolvable configuration fields report `unknown`, never null. This includes fields that throw
  under a restrictive `SecurityManager` or an unsupported `os.arch`.
- JMX registration failure is one logged warning plus `jmxStatus`/`jmxError`, never a retry.

Two failure modes are deliberately *not* covered here, because failing loudly is correct:

- **Native library load failure.** `LibUtils` already throws and the engine never constructs.
  There is no snapshot to read, and that is right.
- **Model load failure.** Throws today, stays throwing. Error taxonomy is out of scope.

## Testing

- **Catch2 (native):** `stagedImports` increments for an unaligned host buffer and
  `wrappedImports` for a 64-byte-aligned one — the split the skeleton findings established.
  `stagingBytes` grows then plateaus across repeated invokes (the grow-only contract), using a
  `kCachedMapWrite` load explicitly. `deviceBytesLive` returns to baseline after
  `ReleaseOutputs()`. Counters accumulate identically through `Invoke` and `InvokeViews`, since
  both route through `RunCall`.
- **JVM unit:** snapshot contents across load → forward → close, including the closed-model
  rollup; counter accumulation; `forwardMaxNanos` tracks the maximum; and a **GC test** — drop a
  block without closing it, force collection, and assert the entry is folded into the rollup
  rather than stranded in `LIVE`.
- **JVM integration:** the MXBean registers and its attributes read back through the platform
  MBean server; `ai.djl.iree.jmx_enabled=false` suppresses registration; repeated engine
  initialisation does not double-register.
- **Concurrency (tagged `stress`, excluded from CI):** `snapshot()` polled repeatedly while N
  threads forward on their own models; asserts no exception and no torn values. Plus a dedicated
  close-vs-snapshot loop that exercises `statsLock` — close a model on one thread while another
  polls, repeatedly, under ASan.
- **Overhead:** a JMH run comparing steady-state MobileNet before and after, via the existing
  `example/src/jmh/java/org/measly/example/MobilenetBenchmark.java`. The counters must not move
  the number. If they do, the design is wrong and we revisit rather than ship a hot-path
  regression.

## Out of scope

- **The native logging bridge.** This engine has no slf4j usage and no PAL equivalent. Forwarding
  IREE's diagnostics into slf4j is a self-contained lift with a working reference implementation
  in the ExecuTorch repo (`native/jni/et_logging.cpp`), and it answers a different question than
  metrics do. Its own cycle. Note that `slf4j-api:2.0.17` is already on the runtime classpath
  transitively via `ai.djl:api:0.36.0`, so the Java-side warnings this design calls for need no
  new dependency.
- **Error/failure taxonomy.** Exceptions stay as they are.
- **Micrometer, OpenTelemetry, and Prometheus bridges.** The snapshot is the integration point. A
  bridge is roughly thirty lines of user code; we document the shape rather than shipping and
  supporting three of them, and we avoid forcing a metrics-library dependency on every consumer.
- **Exposing `stagingMode` or the `local-task` worker count as tunables.** Reporting the effective
  value is in scope; making it configurable is not.

## Rider: `docs/panama-research-sketch.md` corrections

The sketch came up during this brainstorm because item 4 of its *Remaining work* concerns
`lastImportOutcomes`. A survey found its backlog is not eight open items but **five items that
are the work breakdown of a single unmade decision** (build `native/capi/` and a Panama
front-end?), plus one done, one superseded, and one that this design resolves. The facade
decision stays untouched and belongs to a separate workstream. Only these trivial corrections,
answerable from current context, are in scope for this cycle:

1. **The "both files are uncommitted scratch, hence the absolute path" note (lines 29-31) is
   stale.** This file is tracked.
2. **Line-reference drift:** `native/CMakeLists.txt:85` is now `:182`. Audit the other references
   while there.
3. **Item 3 (result-set protocol) is substantially superseded** by `ec95080`, which landed
   `InvokeViews`/`ReadOutput`/`ReleaseOutputs` — the two-call query-then-copy protocol item 3
   recommended, at the C++ level.
4. **Item 4 (fold `lastImportOutcomes` into the invoke result) is resolved by this design's
   counters**, not by folding. Item 4's stated worry is that call-scoped mutable state queried
   separately leaves two front-ends disagreeing about when it is valid. Monotonic cumulative
   counters have no validity window, so there is nothing to disagree about.
   `lastImportOutcomes` survives with one consumer — tests asserting that a specific call
   zero-copied — and should be documented as the test affordance it already is in practice.
5. **The claim that invoke "can be frozen now, with high confidence" (lines 185-187) did not
   hold.** `ec95080` changed invoke two weeks later: cached staging, direct output map,
   `InvokeViews`. Worth recording, because that claim was load-bearing for the sketch's
   "build the facade before IRPA lands" sequencing argument — an argument the document had
   already weakened twice on other grounds.
6. **The observation that there is no PAL/logging bridge in `native/jni/` (line 54) is still
   true**, and this design's out-of-scope section is the current statement of intent on it.
   Cross-reference rather than restate.
