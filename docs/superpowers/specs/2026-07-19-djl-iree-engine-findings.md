# DJL IREE Engine Skeleton — Findings

## Verdict

**GO.** The skeleton runs `add.vmfb` end-to-end through a real DJL `Model.forward()`,
producing the correct output `[11, 22, 33, 44]`. The full JVM suite is green (5 tests:
`IreeNativeTest` ×4 + `AddModelIT` ×1), and the native suite is green (8 Catch2 cases plus
the ASan/LSan leak harness). Sanitizers are clean at two cycle counts with zero leaked
bytes and no dropped `iree_status_t` on any error path. RAII-over-the-C-API was not the
feared source of friction, and the one open technical question this project exists to
answer — whether IREE will zero-copy import a JVM-supplied input buffer — has a definite
answer: no, and the import-or-copy design already handles that correctly. Recommend
proceeding to a MobileNet v2 parity check as the next milestone, per the plan below.

## Import behaviour (the headline question)

- Native, 64-byte-aligned host allocation: **WRAPPED** — `iree_hal_allocator_import_buffer`
  succeeds; the input is imported zero-copy.
- Java direct `ByteBuffer` through JNI: **STAGED** — the same import call fails its
  preconditions and the facade falls back to a copy.
- Interpretation: these two levels disagree, and that disagreement *is* the finding. JVM
  direct `ByteBuffer`s do not meet IREE's zero-copy import alignment preconditions (IREE
  wants 64-byte-aligned host memory; the JDK does not guarantee that for
  `ByteBuffer.allocateDirect`), so under normal DJL use every input silently falls back to
  a staged copy. This was verified genuine, not a test artifact: an independent reviewer
  confirmed the import path is a real try-then-fallback (not a policy that always copies),
  the golden vector is still numerically correct while staged, and the Java-side buffers
  really are direct buffers, not heap buffers masquerading as direct. For a MobileNet-sized
  input, if zero-copy matters, the Java side would need to over-allocate and slice to a
  64-byte boundary to make the buffer import-eligible; otherwise, accept the copy as a cost
  of doing business. Quantify the actual cost before spending effort on the over-alignment
  path — for `add.vmfb`'s tiny tensors the copy cost is immaterial, but it has not yet been
  measured at MobileNet scale.

## Sanitizer results

- ASan: clean.
- LSan: clean — zero leaked bytes across load/invoke/close and every forced error path.
- Cycles run: N=200 and N=400, both clean, with no scaling of any residual (there was none
  to scale) between the two run sizes.
- Any leak traced to a dropped `iree_status_t`: no. Every error path (corrupt `.vmfb`,
  wrong entry-point name, shape mismatch, wrong element type) was walked under the
  sanitizer and none dropped a status.
- Instrumentation was independently verified rather than assumed: 23 `__asan_` symbol
  references were confirmed present in `libiree_djl_core.a`, and LSan's arming was
  confirmed via a deliberate-leak check (a planted leak was caught, ruling out a
  silently-disabled leak detector).
- **TSan (added 2026-07-20, Task 4 — corrected from the original structural claim below):**
  the shipped `iree-runtime-dist` artifact compiles in `IREE_ENABLE_THREADING=ON` with
  `local-task` present, so the "no TSan leg because `local-sync` has no internal threads"
  claim below is no longer true *by construction* — the binary that results from linking
  this dist has threading support linked in either way. The empirical question is instead
  whether *selecting* `local-sync` at runtime keeps the process single-threaded despite
  that, and it does: TSan ran clean over 100 cycles with the facade selecting `local-sync`,
  `strace -f` across the whole run recorded **zero** `clone`/`clone3` syscalls, and
  `/proc/<pid>/status` sampled mid-invoke read `Threads: 1`. This is a measured property of
  the running process, not a guarantee the build gives you for free — state it as such, not
  as "no internal threads" by design. (Historical claim, superseded by the above: no TSan
  leg was run, by design, because the `local-sync` HAL driver executes all work inline on
  the calling thread, so there were no IREE-internal threads for TSan to have a surface
  against — this held for the original from-source build where `local-task` was not even
  compiled in, and no longer describes why the property holds now that it is.)

## Effort assessment

- RAII-over-C-API: low friction in practice. Wrapping every refcounted IREE handle in its
  own `unique_ptr`+deleter, giving the one value-type call (`iree_runtime_call_t`) its own
  scope guard instead of forcing it into the same pointer-ownership shape as everything
  else, and funneling every `iree_status_t` through a single `IREE_CHECK_OR_THROW` macro
  was straightforward. The sanitizer gate mostly *confirmed* the design rather than
  uncovering rework — it did not surface a single leak or double-release that required a
  design change, only the header corrections listed below.
- Surprises vs the bootstrap outline: the outline's from-memory description of the C API
  undershot in a few concrete, checkable ways (see below); none of them were structural —
  all were fixed by reading the actual header rather than by changing the design.
- Corrections the headers forced, vs the plan's from-memory assumptions:
  - `iree_runtime_call_t` is a **value type**, not a handle behind a `unique_ptr`. It needs
    a scope guard that calls `iree_runtime_call_deinitialize`, not `release()`/reset()
    semantics.
  - `append_bytecode_module_from_memory` with `iree_allocator_null()` does **not** copy the
    module bytes — the facade must own the `.vmfb` byte buffer for the session's lifetime.
  - `iree_status_to_string` takes a `const iree_allocator_t*`, not an implicit default,
    requiring a named allocator local at each call site.
  - The element-type constants needed correcting: `FLOAT_32` is `0x21000020` and `SINT_32`
    is `0x11000020`, derived from IREE's `(numerical_type << 24) | bit_count` macro — not
    the placeholder values carried in the plan.
  - `libiree_runtime_impl.a` alone is only 3 objects and is insufficient to link; the build
    also needs `libiree_runtime_unified.a` plus the flatcc archives, and a
    `-DIREE_ALLOCATOR_SYSTEM_CTL=iree_allocator_libc_ctl` define, because there is no
    `IREERuntimeConfig.cmake` to pull those in automatically.
- A finding that was corrected mid-project and should not be repeated: an interim
  observation held that "IREE ignores the caller-declared input element type and silently
  miscomputes" on a type mismatch. That turned out to be an artifact of an invalid test
  constant (`0x00000220`, which is not a real IREE element-type encoding), so
  `hal.buffer_view.assert` had nothing valid to compare against and let it slip through.
  With the corrected constants above, IREE's `hal.buffer_view.assert` **does** validate the
  caller-declared element type against the model's expected signature, and rejects a
  genuine mismatch (e.g. `si32` data presented to an `f32` model) with
  `INVALID_ARGUMENT`. In normal DJL use this validation is a backstop, not a load-bearing
  check: the element-type tag always comes from `IreeDataTypes`, itself derived from each
  `NDArray`'s `DataType`, so it is correct by construction; IREE's own check simply confirms
  that construction is honored.
- **Version alignment (updated 2026-07-20): the saga is dissolved, not solved.** The
  paragraph below records the original problem and why the pairing contract matters, but it
  is no longer the live state of this project. The engine now consumes the published
  `iree-runtime-dist` v3.11.0-3 artifact, which anchors on **stable `v3.11.0`** — runtime
  commit `e4a3b040` paired with compiler `3.11.0` — and never `main`/nightly. The dist's
  `manifest.json` records the pairing explicitly (`runtime_commit`, `iree_compile_version`,
  `iree_tag`, `vm_bytecode_version`), so alignment is asserted at configure/load time rather
  than discovered by trial and error. Consuming the dist also means the engine needs **no
  local IREE source tree and no local IREE build tree** at all — `IREE_INSTALL`/`IREE_SOURCE`
  are gone; see the README's rewritten prerequisites and
  `docs/2026-07-20-iree-runtime-dist-usability-report.md` for the full verdict.

  Historical record, kept because it explains *why* the pairing contract matters: version
  alignment was an environment lesson worth recording, not a design flaw. The `.vmfb`
  compiler and the linked runtime must agree on ABI. The linked runtime at the time was the
  local build at `/home/corey/workspace/iree-build` (source commit `a869dc3`, version
  `3.12.0.dev`). The stable pip release, `iree-base-compiler==3.11.0`, emitted a `.vmfb`
  whose `hal.command_buffer.dispatch` import signature mismatched that runtime and failed
  to load. The fix at the time was to compile with the matching pip nightly,
  `iree-base-compiler==3.12.0rc20260717`, and verify the resulting fixture against the
  runtime's own `iree-run-module` before trusting it in the JVM suite. Separately, the pip
  `iree-base-runtime` wheel is not linkable at any version — no headers, no static libs —
  confirming the design doc's decision (at the time) to link against a local build tree.
  This nightly-chasing narrative is exactly what a stable, tagged pairing contract
  supersedes.

## Runtime API choice (high-level vs low-level) and when to revisit

IREE exposes two C runtime APIs. The public docs
([c-api/#runtime-api](https://iree.dev/reference/bindings/c-api/#runtime-api)) recommend the
low-level API for "custom bindings or when integrating into larger projects." This skeleton
uses a **hybrid, backed by the high-level API**, and does so deliberately:

- **High-level (`iree/runtime/api.h`)** for lifecycle and invocation —
  `iree_runtime_instance` → `iree_hal_device` → `iree_runtime_session` → `iree_runtime_call`
  (load, `iree_runtime_session_lookup_function`, `iree_runtime_call_invoke`).
- **Low-level HAL (`iree_hal_*`)** directly for buffer marshaling —
  `iree_hal_allocator_import_buffer`, `iree_hal_buffer_view_create`, `iree_hal_buffer_map_read`,
  `iree_hal_buffer_view_allocate_buffer_copy`. This is exactly where control matters (the
  WRAPPED/STAGED import decision), and it is already at the low level.

**Why this is sound here, not a compromise:** the API choice is fully encapsulated behind
`IreeRuntime::Load`/`Invoke`. Neither the JNI shim nor the DJL layer knows which runtime API
sits underneath, so migrating to the low-level VM API later is a facade-internal refactor, not
an interface break. The high-level API is officially supported and stable, is the surface the
header-verification checklist targeted, and kept the facade small — which is what made the
go/no-go gate reachable quickly.

**What the "prefer low-level" advice points at.** The high-level `session` bundles three things
the low-level API lets you separate: the VM **instance**, the HAL **device**, and the VM
**context**. Reasons to reach past the convenience layer:

1. **Sharing the instance/device across many models — the concrete one for this codebase.**
   `RuntimeState` currently creates a full `instance + device + session` per `IreeRuntime`
   (i.e. per loaded model). Fine for one model; wasteful for a server hosting many models or
   per-thread engines, where one instance + device could back many lightweight per-model
   contexts. This is the first place the low-level API (or a shared high-level instance) would
   earn its keep.
2. **Explicit driver registration.** The high-level path calls
   `iree_runtime_instance_options_use_all_available_drivers`, registering every compiled-in
   driver. Harmless with a runtime trimmed to `local-sync`; a larger project usually wants
   explicit registration.
3. **Custom native VM modules (custom ops), streaming/async invocation, custom module
   resolution order** — all out of scope here, all reasons to own the VM context directly.

**Verdict:** not a show-stopper, and the right call for a go/no-go skeleton. Revisit the
per-handle `instance + device + session` construction if this grows into a multi-model or
high-concurrency host — a bounded, facade-internal change when that time comes.

## Consideration: operator coverage / custom ops (unvalidated — likely YAGNI until a concrete consumer)

Captured as context, not planned work. Revisit only when a real consumer model demands it.

Because IREE is a **compiler**, not a runtime with a fixed kernel library, "custom op" means
something different than it did for the ExecuTorch engine. There, a model needing `nn.LSTM`
went poorly: the decomposed graph wasn't well-served by the kernel library/delegate, forcing a
hand-written first-party op (`etnp::lstm`) to be whole-archived into the shim. IREE instead
code-generates kernels from the tensor algebra, so the **decomposition that hurt ExecuTorch is
what IREE wants** — a standard LSTM should compile fine with no custom op and nothing to link.

The risk doesn't disappear, it **moves from runtime-kernel coverage to frontend import
coverage**: does the PyTorch→StableHLO path (torch-mlir / iree-turbine) cleanly lower the op and
its awkward variants (bidirectional, packed/padded sequences, dynamic lengths)? That is an
export-time problem, generally more tractable than writing and linking a kernel.

Genuine IREE custom ops (a custom native VM module / `custom_call`, or a hand-written ukernel
overriding codegen) are **rare for standard inference** and only needed when an op can't be
expressed in the input dialect, or to hand-tune a hot path. If they ever are needed, registering
a custom VM module means owning the VM **context** — i.e. it is the **second reason (after
multi-model instance sharing) to drop the facade to the low-level API** (see the API-choice
section above).

None of this is exercised by the skeleton (it runs a trivial `add`). The cheap way to de-risk it
for a specific model is a standalone `torch.export` → iree-turbine/torch-mlir → `iree-compile`
spike — no engine work required — which directly probes the frontend-import variable.

## Recommended next milestone

**MobileNet v2 parity.** It exercises real tensor sizes, which is what makes the STAGED
copy cost (see Import behaviour above) actually measurable rather than theoretical, and it
exercises the torch→StableHLO→`iree-compile` export chain — both explicitly deferred from
this skeleton. Both are prerequisites for deciding whether the import-or-copy design needs
the over-alignment optimization or can simply accept the copy.

## Deferred items

Carried forward from the design doc's deferred list, plus items surfaced during this work:

- MobileNet v2 parity check and the torch→StableHLO→`iree-compile` export chain
- `manylinux_2_28` container wrapper and the glibc floor
- Windows/MSVC support (the `/MT` static-CRT pin, `check_windows_crt.sh`)
- Per-platform classifier JARs and the Maven publishing block
- Third-party license bundling
- The `et_logging` → slf4j PAL bridge
- The `Translator` support library (`translate/`)
- ~~Swapping the stub `IreeRuntimePin.cmake` for a real `iree-runtime-dist` pin — align on a
  stable release (compiler + from-source runtime) at that point~~ — **done, 2026-07-20**: the
  engine now consumes `iree-runtime-dist` v3.11.0-3 and needs no local IREE tree; see the
  version-alignment update above and `docs/2026-07-20-iree-runtime-dist-usability-report.md`.
- Content-addressed native-library extraction cache in `LibUtils`
- Timing/benchmark harness and the JMH example module
- Correcting `IreeNDArray` close/use-after-close hygiene toward the ExecuTorch reference
