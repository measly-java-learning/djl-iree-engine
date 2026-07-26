# Panama research sketch: dual JNI + FFM front-ends

**Status**: research notes, not a spec. No code written, no decision made.
**Date**: 2026-07-25 (revised same day — see *How forthcoming IREE work interacts*)
**Question**: could `native/` support JNI (older JVMs) *and* Panama/FFM (Java 25+) from one
codebase? What blockers exist in the C++ as it stands today, and how does forthcoming IREE work
(IRPA parameter support) interact?

> **Revision note.** This document was originally written on the assumption that a migration to
> IREE's low-level API was forthcoming, driven by IRPA. That assumption was disproven the same
> day — the high-level session can compose the io_parameters module directly. Every
> recommendation below survived the correction unchanged, because they follow from *where the
> churn lands* rather than from *which IREE API causes it*. The affected framing has been
> revised in place and the dead premise is called out where it appeared.
>
> **Second revision, 2026-07-25 (later): the IRPA spike ran, and this time a *recommendation*
> did not survive.** See `docs/2026-07-25-irpa-spike-findings.md`. The section *"IRPA is what
> makes direct binding indefensible"* is **withdrawn** — IREE retains the whole parameter
> chain, so there is no ownership graph for a caller to model, and that section's central
> claim was the ownership graph. A narrower argument replaces it. The two-layer facade
> recommendation itself still holds, but now rests on the `-Wl,--exclude-libs,ALL` packaging
> blocker and the status-consumption discipline rather than on IRPA lifetimes. Read the
> "every recommendation survived" note above as scoped to the *first* revision.

Java-side packaging concerns (multi-release JARs, module descriptors,
`--enable-native-access`, how DJL selects a front-end) are explicitly **out of scope**.

**Companion document**: the same question was worked through for the ExecuTorch engine first —
`/home/corey/workspace/djl-executorch-engine/docs/panama-research-sketch.md`. Both files are
uncommitted scratch, hence the absolute path. Several conclusions here are stated as deltas
against that analysis, because the two engines differ in ways that matter.

## Verdict

Feasible, and a materially **easier** target than ExecuTorch. Nothing in
`native/core/iree_runtime.cpp` blocks it.

Recommended design: a **two-layer facade** — an `extern "C"` boundary with a C++ implementation,
with JNI and Panama as peers on that boundary. Best done **before IRPA lands**, so the load
surface is designed once rather than retrofitted.

## Why IREE is the easier target

Three of ExecuTorch's blockers do not exist here.

**Outputs are owning.** `OutputBuffer` holds its own `std::vector<std::byte>` and every IREE
handle is released before `Invoke()` returns (`core/iree_runtime.h:23-29`, and the closing
comment at `core/iree_runtime.cpp:205-206`). ExecuTorch's `ForwardResult` hands back borrowed
views into the runtime's arena, so its lifetime had to be exported to Java. Here, nothing but
bytes crosses. That was the single real design change over there, and it is already solved here.

**No upcalls are needed at all.** There is no PAL/logging bridge in `native/jni/`. The whole
`AttachCurrentThreadAsDaemon` / foreign-thread-upcall question — the biggest unknown in the
ExecuTorch analysis, and the thing recommended to spike first — simply does not arise. Worth
noting because `local-task` *does* spawn worker threads; we just never call back into Java from
them.

**The error model is already status-based.** `IREE_CHECK_OR_THROW` converts IREE's C status into
a C++ exception (`core/iree_status.h`). For an FFM boundary you want to *un-add* that and return
codes plus a message — closer to IREE's native model than the current core is. Over in
ExecuTorch, exceptions were baked into the core's contract and had to be wrapped. Here the
exception layer is ours and can simply stop at the boundary.

## Recommended design: two layers

```
IREE C API
  └─ native/capi/    extern "C" LINKAGE, C++ IMPLEMENTATION   ← the contract
       ├─ Panama
       └─ native/jni/   (a peer, not a consumer of a C++ wrapper)
```

### C linkage, C++ implementation — the load-bearing distinction

The facade must be `extern "C"` functions whose **bodies are C++**, still using
`core/iree_handles.h` internally.

Writing the bottom layer in actual C would move `RuntimeState`'s destruction ordering, the
`unique_ptr` deleters, `CallGuard`, and the consume-exactly-once status discipline into a
language with no destructors — onto the error paths that `core/iree_status.h:10-13` explicitly
identifies as the least hand-tested code in the build. That is a real safety regression bought
for nothing. C ABI at the boundary; RAII unchanged behind it.

### Why there is no third (C++-over-C) layer

A C++ convenience layer between the C facade and the JNI shim has no job. The shim's own header
comment already states it is a "thin marshalling layer only. All lifetime logic lives in the
facade." Its work is `jbyteArray` → pointer+length and error code → `ThrowJava`, which is
identical whether it calls a C symbol or a C++ method. Inserting a C++ wrapper means flattening
to C PODs and re-inflating them into `std::vector` for the wrapper's benefit — a copy that
exists only to make the middle layer look C++-shaped.

### No escape hatch

An "escape hatch" letting the C++/JNI side bypass the C facade and call IREE directly was
considered and **rejected**. The divergences it anticipates do not materialise:

| Presumed need | Reality |
|---|---|
| Zero-copy output | JNI uses `GetDirectBufferAddress` on a Java-allocated direct buffer; Panama allocates an `Arena` segment. Both are "caller supplies a destination pointer" — one C signature serves both. |
| Zero-copy input | Same. Both hand down a raw host pointer, which is what `InputDesc.data` already is. |
| Error handling | JNI throws, Panama returns. Both are front-end-side translation of the same error code. No C-API difference. |

A hatch that exists gets used, and then the JNI path can do things the Panama path cannot —
rebuilding the behaviour fork the facade exists to prevent. Keep it as a **rule, not a
mechanism**: *wanting to bypass the C API is evidence the C API is wrong*. Fix it there, where
both front-ends benefit.

## The rejected alternative: binding IREE's C API directly from Panama

Because IREE is already a C API, `jextract`-ing it and reimplementing the orchestration
(instance/device/session creation, import-or-copy, call setup, output pop loop) in Java looks
cheap. It is not, for two reasons.

### 1. Everything valuable in the core is unenforced lifetime knowledge

- The vmfb bytes must outlive the session because `append_bytecode_module_from_memory` with a
  null allocator does not copy (`core/iree_runtime.cpp:11-15`) — the field ordering in
  `RuntimeState` is load-bearing for destruction order.
- `iree_status_t` is a heap object that must be consumed **exactly once**; dropping one leaks it
  and its message payload (`core/iree_status.h:10-13`). Note the status that must be freed on the
  *success-adjacent* import path (`core/iree_runtime.cpp:126-128`) — exactly the kind of thing a
  reimplementation gets wrong silently.
- `iree_runtime_call_t` is a value type with no release, needing a scope guard rather than a
  handle (`core/iree_handles.h:29-31`).

IREE's headers enforce none of this. Duplicating it into a language with no destructors, against
a JNI path doing the same thing in C++, is a guaranteed defect. **IRPA makes this decisive — see
below.**

### 2. A hard packaging blocker

`native/CMakeLists.txt:85`:

```cmake
target_link_options(iree_djl PRIVATE -Wl,--exclude-libs,ALL)
```

IREE is statically linked into `libiree_djl.so` and its symbols are **deliberately hidden** so
they cannot collide with anything else in the JVM. Panama cannot bind symbols that are not
exported. Direct binding would require either dropping that flag — reintroducing the exact
collision risk it was added to prevent — or shipping IREE as a separate shared library, which is
a change to the pinned `iree-runtime-dist` tarball, not a native-code change.

## How forthcoming IREE work interacts — REVISED 2026-07-25

> **This section previously assumed a migration to IREE's low-level API was coming, with IRPA
> as its driver. That premise is dead.** Verified against the pinned dist's headers and
> archives: `iree_runtime_session_append_module` (`include/iree/runtime/session.h:147-148`)
> accepts an **arbitrary** `iree_vm_module_t*`, which is exactly what
> `iree_io_parameters_module_create` (`include/iree/modules/io/parameters/module.h:21-25`)
> produces. The high-level session can compose the io_parameters module itself, so **IRPA is a
> bolt-on to `Load`, not a facade refactor**. Details and the confirming spike plan:
> `docs/2026-07-22-irpa-and-target-selection-scoping-notes.md`, Parts 1 and 4.
>
> **What survived the correction is the important part: every recommendation below is
> unchanged.** They were derived from *where the churn lands*, not from *which IREE API
> causes it* — and IRPA moves the load surface either way. The premise was wrong; the
> conclusions were not. That is worth stating plainly, because the natural instinct on
> learning the migration is off would be to assume this document needs unwinding. It does not.

**There is currently no scheduled low-level migration.** If one happens it now needs its own
justification — IRPA is no longer it, and async control was never it (see below). Plan the
facade against the high-level API as it stands.

### Withdrawn recommendation (recorded so it is not re-proposed)

An earlier draft recommended shaping the C boundary around an opaque *invocation* handle plus
an explicit wait/complete step, to leave headroom for IREE's asynchronous fence/semaphore model
without an ABI break.

**Withdrawn, and the 2026-07-25 finding strengthens the withdrawal.** It was premised on async
control motivating a low-level migration. Async was never the motivation — and now there is no
migration on the roadmap at all. The indirection would buy headroom for a feature nothing is
asking for.

### The churn still lands on load — which is still the good case

The argument here never depended on the low-level API. **IRPA itself** grows the load surface,
whoever implements it:

- **Invoke does not change.** Buffer views in, views popped out, `ImportOrCopy`'s
  import-or-stage fallback intact. The hot, per-inference, ABI-sensitive path Panama cares most
  about can be frozen **now**, with high confidence.
- **Load is called once per model.** Zero perf sensitivity, free to be coarse and opaque — and
  IRPA is precisely a load-time feature (parameter scopes, archive paths).

Uncertainty is concentrated in the one call where ABI flexibility is cheapest. That remains a
strong argument for building the facade **before** IRPA lands, rather than retrofitting it
afterwards.

Also downgraded, and now doubly so: an earlier concern that the low-level API's heavier use of
by-value aggregates (`iree_string_view_t`, `iree_const_byte_span_t`,
`iree_hal_buffer_params_t`) would aggravate FFM struct-classification friction. With no
migration scheduled and the facade absorbing load-time types regardless, those never reach the
boundary.

### The one real ABI design decision: an opaque load-options object

Load grows from `(vmfb, entryPoint, driver)` to also carrying N parameter archives, each with a
**scope name** and a **path** — and the scope→file discovery convention is still an open question
in the scoping notes. So do not grow positional parameters:

```c
iree_djl_load_options_create(&opts);
iree_djl_load_options_set_entry_point(opts, "main");
iree_djl_load_options_add_parameter_scope(opts, "model", "/path/to/weights.irpa");
iree_djl_load(opts, &out_runtime);
```

Every future load option is then an additive new symbol — never a layout or signature break.
This matters far more for Panama than for JNI: the JNI shim ships in lockstep with the `.so` and
physically cannot mismatch, whereas hand-written Panama bindings drift.

Supporting details, both already settled by the scoping notes:

- **Params cross as paths**, not bytes (scoping notes, Part 1 "Marshalling") — ~~mmap is the
  entire point~~ **corrected 2026-07-25**: there is no persistent mmap. IREE owns the fd and
  `pread`s only the spans the program imports, so passing a path keeps multi-GB weights off
  every boundary. Conclusion unchanged, rationale corrected; trivial marshalling for both
  front-ends either way.
- **The vmfb should cross as pointer+length.** Whether the facade copies it (it does today, and
  must, per the null-allocator rule) stays an implementation detail behind the ABI rather than
  part of the contract.
- **Extract-to-temp for jar-bundled models** (scoping notes line 149) is resolved above the
  boundary in Java, identically for both front-ends. No C-API pressure.

### ~~IRPA is what makes direct binding indefensible~~ — WITHDRAWN 2026-07-25; a weaker argument replaces it

> **This section's argument does not survive the IRPA spike. Retracted, not softened.**
> Evidence: `docs/2026-07-25-irpa-spike-findings.md`, Q4/Q5.

**What this section claimed:** that IRPA introduces a file handle (mmap), a parameter index, a
provider, and the io_parameters module — *"four new handle types that must outlive the session
and every invoke"* — layered on `RuntimeState`'s destruction-order rule, and that modelling
that ordered ownership graph in Java across `Arena` scopes without destructors would be a
defect waiting to happen. The section already carried a hedge conceding the *count* was
inferred from headers rather than verified, while insisting the *shape* was certain: "there is
a new ordered ownership graph either way."

**What the spike measured:** the count is **zero**, and the shape claim was the part that was
wrong. IREE retains the entire chain internally — index retains file handle
(`io/parameter_index.c:185`), provider retains index (`parameter_index_provider.c:64`), module
retains providers (`modules/io/parameters/module.c:518`), context retains module
(`vm/context.c:444`/`597`). `RuntimeState` gained no new members; every parameter handle is a
local in `Load` that releases as soon as it is handed one level up. Verified by dropping each
reference early on purpose and re-running under ASan/LSan, and by reading every `_retain()`
call site.

**So there is no multi-level ownership graph for a caller to model — in Java or in C++.** The
hedge protected the count but not the conclusion, and the conclusion was the load-bearing part.
An argument of the form "this ownership graph is too intricate to duplicate in a language
without destructors" cannot stand when the graph turns out not to be the caller's to hold. IRPA
is not what makes direct binding indefensible.

**The thinner argument that does survive.** Direct binding is still not attractive, on narrower
grounds:

- A direct-binding implementation must still get the **C-API call sequence** right — open,
  parse, wrap-with-scope, module-create, append — reimplemented in Java against headers that
  document none of the sequencing.
- It must get the **append ordering** right: `io_parameters` before the bytecode module. The
  spike confirmed this is required and that violating it fails eagerly with
  `context.c:205 NOT_FOUND`. Loud and immediate, which makes it a nuisance rather than a
  silent-corruption risk — a genuinely weaker point than the ownership argument it replaces.
- It must maintain the **consume-exactly-once status discipline** (`core/iree_status.h:10-13`),
  which is unchanged by this spike and remains the strongest item in the section above.
- **The packaging blocker is untouched.** `-Wl,--exclude-libs,ALL` (`native/CMakeLists.txt:85`)
  hides IREE's symbols; Panama cannot bind what is not exported. The spike did not go near
  this, and it stands unaffected. **This, not IRPA, is the load-bearing objection to direct
  binding.**

None of that is "indefensible." It is "unattractive, for reasons that were already listed
above this section." The honest position is that IRPA neither strengthens nor weakens the
direct-binding case much either way — and the recommended two-layer facade continues to rest on
the packaging blocker and the status discipline, both of which the spike leaves intact.

**Knock-on:** the recommendation to build the facade *before* IRPA lands is also weaker than it
reads. IRPA turned out to be a small, self-contained addition to `Load` — a 4th parameter and
one guarded code path, with no new state. The sequencing argument in *The churn still lands on
load* above still holds directionally (load-time uncertainty is cheap; invoke can be frozen
now), but "retrofitting IRPA afterwards" is a smaller cost than this document assumed.

## Remaining work under the recommended design

1. **Un-throw the boundary.** A C++ exception unwinding through an FFM downcall stub is undefined
   behaviour, not a Java exception. Every `extern "C"` entry needs a total catch-all → error code
   + message out-param. More natural here than in ExecuTorch, since `ConsumeStatusOrThrow`
   already renders the message into a `std::string` before freeing the status.
2. **Flatten the boundary structs.** `InputDesc` and `OutputBuffer` embed `std::vector`
   (`core/iree_runtime.h:16-29`); FFM needs POD pointer+length pairs.
3. **Decide the result-set protocol.** `Invoke` returns `std::vector<OutputBuffer>` by value.
   Through C that is either a heap handle plus explicit free, *or* a two-call protocol (query
   shapes/sizes, then copy into caller-provided destinations). The second is more attractive here
   than it was for ExecuTorch precisely because the data is already an owned copy — Panama hands
   down an `Arena` segment and the intermediate disappears. Strictly better than what JNI does
   today at `jni/iree_djl_jni.cpp:187-198` (`allocateDirect` + `memcpy`).
4. **Fold `lastImportOutcomes` into the invoke result.** It is call-scoped mutable state on the
   runtime today (`core/iree_runtime.cpp:157`, `core/iree_runtime.h:53-55`). Fine while JNI
   serialises access, but as a separate query on a shared C boundary the two front-ends will
   eventually disagree about when it is valid.
5. **Keep every struct opaque** — handles plus accessor functions, never exported layouts. Once
   Java is compiled against a `MemoryLayout`, that layout is ABI owned forever, and it is the
   thing most likely to shift as IRPA and tier selection grow the load surface. Accessor call
   overhead is irrelevant at this granularity.
6. **Ship an `iree_djl_abi_version()`.** The JNI shim cannot mismatch; Panama bindings can.
7. **Symbol visibility policy.** Export exactly the `iree_djl_*` symbols while keeping IREE's
   hidden — a version script or explicit `__attribute__((visibility("default")))`. Do **not**
   simply drop `--exclude-libs,ALL` (`native/CMakeLists.txt:85`).
8. ~~**An IRPA fixture for the leak harness.**~~ — **DONE 2026-07-25** by the scoping notes'
   spike (Part 4), exactly as this item anticipated (build it once, shared). Delivered:
   - Fixtures generated by `tools/export_scale.sh` from `tools/scale.mlir` / `tools/scale2.mlir`:
     `src/test/resources/models/scale.vmfb` + `scale_weights.irpa` (single scope, splat),
     `scale2.vmfb` + `scale2_bias.irpa` (two scopes), and `scale_weights_zero.irpa`
     (**FILE-backed, real on-disk storage** — the splat archives cannot exercise the file-handle
     path at all, which was a real trap: an early ownership probe against them was silently
     inert).
   - Catch2 coverage in `native/test/iree_params_test.cpp` (9 cases), including error paths.
   - `native/harness/iree_leak_harness.cpp` extended with an optional 4th argv (`scope=path`)
     threaded into the 4-argument `Load`; **500 load/invoke/close cycles with the archive bound
     and outputs asserted come back ASan/LSan-clean.**

   Note the bug class named here — "the mmap + index + provider lifetimes" — turned out not to
   be ours to hold (see the withdrawn section above), so the gate this item wanted is
   correspondingly less load-bearing than intended. It is still the right gate to keep.

## A benefit worth naming

The Catch2 units and the leak harness currently link `iree_djl_core` directly
(`native/CMakeLists.txt`), validating C++ RAII paths. Pointing them at the C facade instead makes
the existing ASan/LSan go/no-go gate cover the **explicit-free protocol Panama actually depends
on** — the layer with no destructors, and therefore where the leaks will be. The right surface
gets tested for free.

## If this is picked up

Revised after the 2026-07-25 correction — the ordering changes, the destination does not.

1. ~~**Run the scoping notes' IRPA spike first** (Part 4).~~ — **DONE 2026-07-25, verdict GO**
   (`docs/2026-07-25-irpa-spike-findings.md`). Its Q4/Q5 answer for the load-options ABI is
   the simplest possible one: **there is no ownership graph to express.** The C boundary needs
   to convey N `(scope, path)` string pairs into `Load` and nothing else — no handles escape,
   no lifetimes cross, nothing survives the call. That is the whole ABI implication.
2. **Settle the load-options ABI shape** against what the spike found. Now materially lower
   risk than this document expected: with no lifetimes crossing, the opaque
   `iree_djl_load_options_*` builder sketched above is close to sufficient as written.
3. **Stand up `native/capi/` over the current high-level implementation** and rebase the JNI shim
   onto it. Validates the boundary with no IREE-side churn in flight — and since there is no
   low-level migration pending, "the current implementation" is simply the implementation.
4. **Land IRPA below the facade**, with both front-ends unaffected — which is the whole point of
   the exercise, and remains true now that IRPA is a `Load` bolt-on rather than a refactor.
