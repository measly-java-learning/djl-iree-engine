# Staged Staging Cache + Direct Output Map — Spike Findings (2026-08-04)

Answers to the two items in `docs/additional-zero-copy-exploration.md` (B2F3),
executed per the approved plan `local://staging-output-research-plan.md` on the
current tree (`spike/borrowed-host-buffers`, HEAD 7373f6c), linux-x86_64, JDK 17
(Zulu 17.0.19), glibc 2.39, pinned `iree-runtime-dist` v3.11.0-3 (runtime commit
e4a3b040, compiler `iree-base-compiler==3.11.0`).

## §1 Verdict

Both unexplored alternatives are real wins and are shipped (in the spike tree):

1. **Cached staging recovers ~85% of the staged-vs-wrapped input delta** at
   ≥ 4 MB by amortizing IREE's per-call staging-buffer allocation (a fresh
   `iree_hal_allocator_allocate_buffer` per staged input per call, hidden
   inside `iree_hal_buffer_view_allocate_buffer_copy`). The engine now retains
   one grow-only staging buffer per input slot and rewrites it each call via
   `iree_hal_buffer_map_write` (chosen primitive; `transfer_h2d` is
   indistinguishable). **Applied: the JNI `load` now requests
   `StagingMode::kCachedMapWrite` by default.** No flag, no alignment
   contract, `kStaged` outcome semantics unchanged.
2. **Direct output mapping halves the output path.** The intermediate owning
   `std::vector` (and its 64 MB resize-zeroing at big sizes) is gone: the JNI
   now `map_read`s straight into the JVM-owned direct buffer. Saved up to
   52 ms per call at 64 MB (48% of the wrapped invoke), and eliminates the
   `output_copy` memcpy the JNI used to pay entirely. Applied unconditionally
   (it is strictly fewer instructions per call at the Java boundary).

**Decision-gate update vs the borrow spike** (pre-registered rule in the plan:
≥ 50% recovery at ≥ 2 sizes ≥ 256 KB → flip): the rule fired at 88% (4 MB) and
84% (64 MB), so the JNI default flips to `kCachedMapWrite`. The W4
`iree.engine.alignedBuffers` borrow flag stays **optional**: it remains the
only path to TRUE zero-copy (no copy at all), and the residual
cached-vs-wrapped gap (11.8% / 15.9% of the staged delta) is still material
for memory-bound kernels. The flag's rationale is now narrower — "zero-copy,
not merely allocation-free" — but it is not obsolete.

## §2 Questions answered

**Q1 — How much of `staged − wrapped` does cached staging recover?** 88% at
4 MB (7865 µs of an 8917 µs delta), 84% at 64 MB (58086 of 69104 µs). The
residual is the copy itself (`map_write`/`transfer_h2d` ≈ one memcpy) plus the
view-create — the allocation was the dominant cost, confirming the hypothesis
in B2F3. map_write vs transfer_h2d: 3802.48 vs 4000.70 µs at 4 MB and 119296.88
vs 117331.80 µs at 64 MB — no consistent winner; `kCachedMapWrite` was chosen
as the JNI default for symmetry with the `map_read` output path and the
absence of a timeout argument.

**Q2 — What does direct output mapping save?** At 64 MB the owning-vector
path's materialization cost is 52.4 ms (the `resize` zero-fill of the
64 MB vector, visible as `wrapped − direct_out`), and the JNI `memcpy` it
replaced was another 55.7 ms (`output_copy`). The new path pays one `map_read`
(~55.7 ms at 64 MB, the floor) and nothing else. At 4 MB the saving is 544 µs
(20% of the wrapped invoke); at 256 KB, 25 µs (24%). At 16 KB the view path is
**4.1 µs slower** than the owning path — a fixed per-call overhead (the
`OutputLayout` vector + `pendingOutputs` move + `ReleaseOutputs`), noise
against any real kernel (61.6 ms MobileNet → 0.006%) but measurable at
micro-invocation sizes. Recorded; not a blocker for the spike's decision.

## §3 Measured table

Native bench `native/build/iree_copy_bench`, extended with
`staged_cached_mw | staged_cached_tr | direct_out` columns (see §6 for the
arms). µs/op, 50 ms-min sampling, golden-checked (`out == 2*in`) on every arm.

| N (f32) | memcpy | invoke_staged | invoke_wrapped | output_copy | staged_cached_mw | staged_cached_tr | direct_out |
|--------:|-------:|--------------:|---------------:|------------:|-----------------:|-----------------:|-----------:|
| 4,096 (16 KB) | 0.43 | 15.12 | 14.17 | 0.42 | *skipped* | *skipped* | 18.29 |
| 65,536 (256 KB) | 19.33 | 535.60 | 105.60 | 19.58 | *skipped* | *skipped* | 80.16 |
| 1,048,576 (4 MB) | 749.73 | 11667.48 | 2750.09 | 877.75 | 3802.48 | 4000.70 | 2205.87 |
| 16,777,216 (64 MB) | 31205.70 | 177382.55 | 108278.44 | 55714.58 | 119296.88 | 117331.80 | 55850.90 |

Derived:

| N | staged − wrapped | recoverable (staged − cached_mw) | recovery % | cached − wrapped residual | wrapped − direct_out |
|--:|--:|--:|--:|--:|--:|
| 4 MB | 8917.39 | 7865.00 | 88.2% | 1052.39 | 544.23 |
| 64 MB | 69104.11 | 58085.67 | 84.1% | 11018.44 | 52427.54 |

Baseline cross-check vs the W2 run (prior findings §3): same arms agree within
run-to-run variance (256 KB staged 616.52 → 535.60 µs; wrapped 70.86 → 105.60).
The `invoke_staged` at 16 KB and 256 KB rows both WRAP on this host's glibc
(mmap luck), so the cached arms there are n/a — the two sizes that matter
(4 MB, 64 MB) are fully measured. Note the 256 KB row shows the *staged* arm
STAGED while the *cached* arm WRAPPED: two separate mallocs, two different
addresses, per-allocation luck — the arms report their own outcomes honestly
(skip + warning) rather than assuming. The mechanism — glibc's `chunk+16`
pointers vs the `buffer_heap.c:183` alignment gate — is resolved in §7.1.

Run metadata: 2026-08-04, ~16:20 local, exit 0, under the brief §7 controls
(`systemd-run --user --scope -p MemoryMax=4G taskset -c 0-3 timeout 900`).
Fixture set: `build/bench-models/bigscale_{4096,65536,1048576,16777216}.vmfb`
(regenerated 11:51 today by `tools/export_bigscale.sh`).

## §4 Design notes and deviations from the plan

1. **Per-slot cache, not a single buffer** (deviation, load-bearing): a single
   shared staging buffer clobbers input 0's view the moment input 1 is staged
   into the same call — the plan's own two-input golden would fail. The cache
   is `std::vector<BufferPtr>` indexed by input position: slot *i* is written
   only after slots `[0, i)` are already pushed into the call. Grow-only per
   slot; released by `~RuntimeState`. Lock-free under the documented
   single-flight contract (README: `forward()` not thread-safe per model).
   Methodological corollary: the bench's bigscale fixture is single-input, so
   a clobbering design would have measured as a clean ~85% win and shipped —
   multi-input staging correctness on this engine is only checkable by
   multi-input tests (the add-model goldens), not by the benchmark.
2. **Growth test via the error path** (deviation): every committed vmfb is
   fixed-shape `{4}`, so no compiler-free fixture can grow a successful
   input. The grow branch is exercised by staging 1024-element inputs (which
   grows both slots to 4 KB) into the add model, letting
   `iree_runtime_call_invoke` reject the shape mismatch, then re-invoking the
   standard golden. This also covers the throw-path release of views holding
   grown buffers.
3. **16 KB / 256 KB cached columns are n/a** on this host (malloc'd storage
   wrapped at those sizes — see §3). The conclusion rests on the 4 MB and
   64 MB rows, which is where the 3–35× effect lives anyway.
4. **`native/build.sh` is host-blocked** at the `/opt/corretto` extraction
   step (root-owned dir; the container-only Corretto RPM flow). The JNI shim
   was rebuilt directly (`cmake --build native/build --target iree_djl`) with
   the host JDK's headers (tree already configured) and staged per the
   script's own logic (src/main/resources + licenses).

## §5 Verification evidence

- **Native suite (Release, 10 consecutive runs)**: 86 assertions / 18 test
  cases, all green, including the five new cases (cached reuse across invokes
  in both modes; cached growth-through-error; InvokeViews golden; ReadOutput
  out-of-range throw; Invoke-after-InvokeViews stale-batch guard). A
  pre-existing flake surfaced and was fixed: the new InvokeViews test asserted
  `kWrapped` on a **stack** array, whose alignment is compiler-determined and
  varies per process (ASLR) — same trap the existing import tests document.
  Input switched to `AlignedAlloc`; 10/10 clean after.
- **ASan/LSan gate** (`bash native/build_qa.sh`, `JOBS=4`, scoped): PASS —
  unit suite, parameter suite, and the leak harness × 3 (1000 iterations each:
  local-sync, local-task, parameter-bound). The cached buffers and pending
  output views show no leak, UAF, or double-release under the sanitizer.
- **Java suite** (staged `.so` `5d686fd6`, rebuilt with the view-path invoke +
  `kCachedMapWrite` default): `./gradlew test leakTest
  --no-configuration-cache` → BUILD SUCCESSFUL; 63 tests + 2 leak tests, 0
  failures. Covers the new JNI output path end-to-end (`AddModelIT` incl. the
  `alignedBuffers`-flag interplay, `IreeNativeTest` import-outcome recording,
  `LeakStressTest` Cleaner wiring).

## §6 Artifacts

Runtime (facade): `native/core/iree_runtime.h` (`StagingMode`,
`OutputLayout`, `InvokeViews`/`ReadOutput`/`ReleaseOutputs`, 5-arg `Load`),
`native/core/iree_runtime.cpp` (per-slot cached staging branch in
`ImportOrCopy`; `RunCall` extraction; view-based members), `native/core/iree_handles.h`
(`BufferPtr`). JNI: `native/jni/iree_djl_jni.cpp` (view-path invoke;
`kCachedMapWrite` default). Bench: `native/bench/iree_copy_bench.cpp`
(3 new arms + derived deltas). Tests: `native/test/iree_runtime_test.cpp`
(5 new cases). Docs: README zero-copy section (cached-fallback sentence).

## §7 Unresolved observations worth keeping

1. **Why ≥256 KB mallocs stage — resolved, and it is NOT an engine
   size-dependence.** The heap allocator's import gate is
   `iree_hal_heap_buffer_wrap` (`buffer_heap.c:183` at the pinned commit
   e4a3b0405d): it refuses with `IREE_STATUS_OUT_OF_RANGE` when the access
   does not allow `IREE_HAL_MEMORY_ACCESS_UNALIGNED` and the pointer is not
   64-aligned (`IREE_HAL_HEAP_BUFFER_ALIGNMENT`, `iree/base/config.h:244`).
   Our params use `IREE_HAL_MEMORY_ACCESS_ALL = READ|WRITE|DISCARD`
   (`iree/hal/buffer.h:162`) — deliberately without the `UNALIGNED` bit, so
   the gate is live. The apparent size-dependence is glibc-side: `malloc`
   returns `chunk+16` pointers. mmap'd allocations (≥ the ~128 KB mmap
   threshold) are always `mmap_base+16` → `addr % 64 == 16` → *never*
   importable, which is why 256 KB–64 MB mallocs stage deterministically.
   Arena chunks occasionally reuse a freed 64-aligned block (e.g. a freed
   `AlignedAlloc` region), which is the recorded 16 KB WRAPPED rows and the
   one 256 KB WRAPPED cached arm — confirmed by a host probe (glibc 2.39):
   fresh `malloc(16 KB)` → `%64=32`; `malloc(4 MB)` after 256 KB churn →
   `%64=16`; a freed `posix_memalign`'d 256 KB block re-served at `%64=0`.
   `posix_memalign(64, …)` (our `AlignedAlloc`) is always 64-aligned → always
   imports, which is why the wrapped arm is deterministic.

   What this means for the runtime bump: the only engine-side surface is the
   `buffer_heap.c:183` gate (the alignment constant, `UNALIGNED` handling, or
   a change to `ACCESS_ALL` semantics). It is fully covered by tripwires
   already in the tree — the alignment-pin test (AlignedAlloc must wrap), the
   deliberately-misaligned `base+1` tests (must stage), and the bench's
   per-arm outcome columns (which print the outcome rather than assuming).
   Re-run all three on the next iree-runtime-dist bump; a flip in any of them
   invalidates the staged-path measurements, the cached-staging win, and the
   JNI default choice. glibc-side behavior is host luck, out of the engine's
   control, and made visible by the same probes.


 The gate (source-verified at the pinned commit e4a3b0405d): the heap allocator's import path is iree_hal_heap_buffer_wrap (runtime/src/iree/hal/allocator_heap.c → buffer_heap.c:183), and its only refusal is:

 ```c
if (!iree_any_bit_set(allowed_access, IREE_HAL_MEMORY_ACCESS_UNALIGNED) &&
    !iree_host_size_has_alignment((uintptr_t)data.data, IREE_HAL_HEAP_BUFFER_ALIGNMENT))
  return IREE_STATUS_OUT_OF_RANGE;  // "imported heap buffer data must be aligned to 64"
```

2. **Cached staging buffers are invisible to JVM memory accounting.** They are
   IREE-allocated (`DEVICE_LOCAL`), so they count against neither `-Xmx` nor
   `-XX:MaxDirectMemorySize`, and not against `AlignedLiveCount` (which tracks
   only the W4 aligned buffers). They are grow-only: a runtime's RSS floor
   becomes the largest input size ever staged per slot, held until `close()`.
   Bounded and benign here (freed at close; amortizes allocation churn — the
   opposite of the W4 OOM mechanism), but the accounting blind spot is the
   same lesson as the W4 analysis in reverse: heap caps cannot see these
   bytes, which is exactly why the leak-test budget (`-Xmx256m
   -XX:MaxDirectMemorySize=64m`) still passes with the cache live.

3. **The default path absorbed most of the input-copy win.** ~60% of JVM
   direct buffers miss 64-byte alignment (README histogram), so on the default
   path — no flag — those inputs now stage into the cached buffer instead of a
   fresh per-call allocation. The `iree.engine.alignedBuffers` flag's
   remaining marginal value is true zero-copy for memory-bound kernels; the
   input-copy half of its benefit is now largely subsumed by the default path.
   This is the quantified justification for keeping the flag optional rather
   than removing it: its residual edge is measured (the 11.8%/15.9% residual
   in §3), not assumed.

4. **Cross-run variance is real on this host** (thermal/background load):
   256 KB wrapped row 70.86 µs (W2) vs 105.60 µs (this run) — ±40%. Within-run
   deltas are the reliable comparisons (all arms share the kernel), which is
   why every conclusion above uses within-run deltas only.

Follow-ups worth noting, out of scope for this spike: the ~4 µs fixed
view-path overhead at micro sizes (a small-output fast path could trim it);
the borrow flag's residual justification (true zero-copy for memory-bound
kernels). The ≥256 KB import behavior is resolved, not a follow-up (§7.1).
