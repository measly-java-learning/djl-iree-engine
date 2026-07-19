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
- No TSan leg was run, by design: the `local-sync` HAL driver executes all work inline on
  the calling thread, so there are no IREE-internal threads for TSan to have a surface
  against.

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
- Version alignment is an environment lesson worth recording, not a design flaw: the
  `.vmfb` compiler and the linked runtime must agree on ABI. The linked runtime here is the
  local build at `/home/corey/workspace/iree-build` (source commit `a869dc3`, version
  `3.12.0.dev`). The stable pip release, `iree-base-compiler==3.11.0`, emits a `.vmfb`
  whose `hal.command_buffer.dispatch` import signature mismatches that runtime and fails
  to load. The fix was to compile with the matching pip nightly,
  `iree-base-compiler==3.12.0rc20260717`, and verify the resulting fixture against the
  runtime's own `iree-run-module` before trusting it in the JVM suite. Separately, the pip
  `iree-base-runtime` wheel is not linkable at any version — no headers, no static libs —
  confirming the design doc's decision to link against the local build tree instead. This
  leaves a deferred item: for an eventual shipping build, align on a single stable release
  (compiler wheel + from-source runtime built at the same tag) rather than depending on an
  ephemeral nightly.

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
- Swapping the stub `IreeRuntimePin.cmake` for a real `iree-runtime-dist` pin — align on a
  stable release (compiler + from-source runtime) at that point
- Content-addressed native-library extraction cache in `LibUtils`
- Timing/benchmark harness and the JMH example module
- Correcting `IreeNDArray` close/use-after-close hygiene toward the ExecuTorch reference
