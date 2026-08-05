# Research Brief: Borrowed Host Buffers as a Shared Native Integration Pattern

**Status:** Proposal for feasibility assessment. This brief originated from
design work on a prospective DJL engine for the TVM-FFI ABI, where the
host-buffer handling pattern appeared to be an improvement over what the IREE
engine does. The purpose of this brief is to determine whether that is actually
true, whether the questions below are already answered in `djl-iree-engine`'s
history, and whether the pattern is worth extracting as shared infrastructure.

**Assume the reader has deep context on `djl-iree-engine` and its history.** Some
of what follows may already be settled, implemented, rejected, or measured. That
determination is the primary output.

---

## 1. Context

### The original claim, and its correction

The initial framing was that DLPack-based tensor views in TVM-FFI would offer
better zero-copy behavior from JVM memory than IREE's buffer-view mapping. On
examination that framing is wrong and should not be carried forward.

DLPack is a POD struct — data pointer, device, ndim, dtype, shape, strides, byte
offset. The zero-copy property comes from it being a **non-owning view over
caller-provided memory**, not from any property of DLPack itself. IREE has
equivalent primitives: `iree_hal_heap_buffer_wrap` for wrapping existing host
memory, and `iree_hal_allocator_import_buffer` with
`IREE_HAL_EXTERNAL_BUFFER_TYPE_HOST_ALLOCATION` for the general case.

So the real question is **borrow versus copy**, not DLPack versus HAL. If
`djl-iree-engine` currently copies host data in and out on each inference call,
that is an implementation choice rather than a limitation of the runtime, and the
improvement — if it is one — is portable.

### What genuinely differs between the two runtimes

TVM-FFI's `TensorView` is a borrow by default and the API surface is trivially
thin. IREE's buffer is a refcounted object carrying memory-type bits, usage bits,
and access flags, and the allocator may legitimately refuse an import —
`iree_hal_allocator_query_buffer_compatibility` can return incompatible depending
on the driver. Under `local-sync` this is expected to be unproblematic. Under a
real device HAL driver, importing host memory typically requires page-aligned and
pinned pages.

IREE's model is therefore *more capable and more conditional*, not worse.
TVM-FFI is thinner because it pushes all device concerns onto the caller.

### The proposed usage-style constraint

The suggested design is: **the engine allocates, and the user writes into what
the engine hands back** — rather than the user supplying a `ByteBuffer` that the
engine wraps.

The motivation is alignment. `ByteBuffer.allocateDirect` guarantees essentially
nothing about alignment; HotSpot allocates via `malloc`, typically yielding 16
bytes on x86-64 glibc, with page alignment only under
`-XX:+PageAlignDirectMemory`. TVM's CPU convention assumes 64-byte alignment for
tensor data, and vectorized kernels from TileLang or IREE's LLVM CPU backend may
issue aligned loads. A 16-byte-aligned pointer handed to such a kernel is a
latent fault or a silent performance cliff that will pass every test on the
developer's machine.

The mechanism would be: allocate aligned native memory in the engine, expose it
to Java as a direct `ByteBuffer` via `NewDirectByteBuffer` (or as a
`MemorySegment` under Panama), and pass the address straight through to the
runtime. This also sidesteps `float[]` entirely, which cannot be borrowed —
`GetPrimitiveArrayCritical` may still copy and is hostile to the GC while held.

**Note on aligned allocation specifically:** the suggestion originally proposed
`posix_memalign`, which is already known to be superseded in this codebase. The
Windows support work moved to C++17 aligned new/delete:

```cpp
// C++17 aligned new/delete (not posix_memalign) so this compiles under MSVC too.
void* aligned = ::operator new(4 * sizeof(float), std::align_val_t{64});
```

This is cited as a representative example of the kind of question this brief is
likely to contain answers to already. Treat similar suggestions with the same
skepticism.

---

## 2. What this brief is trying to answer

Four questions, in order:

1. **Does the engine currently copy, and what does it cost?** If the host path
   is already borrow-based, most of this brief is moot. If it copies, the cost
   needs a number before any API constraint is justified.
2. **Is the alignment hazard real in this codebase, or already handled?** The
   C++17 aligned-new change suggests alignment is at least partly understood;
   whether that extends to buffers crossing the JVM boundary is unknown.
3. **Are the lifetime hazards tractable under the current architecture?**
   Borrowing is strictly less safe than copying. See §4.
4. **Is there a shared abstraction worth extracting?** If a small aligned-native-
   buffer module with a DJL `NDArray` on top would serve both engines, that is
   the piece to factor out — not the DLPack/HAL wrapping code, which is thin and
   engine-specific either way.

---

## 3. Work items

### W1 — Audit the existing host buffer path

Determine what `djl-iree-engine` does today on the input and output paths: copy,
borrow, or mixed. Identify whether `iree_hal_heap_buffer_wrap` or
`iree_hal_allocator_import_buffer` are already in use, and if not, whether they
were considered and rejected.

*Answers:* whether anything below is worth doing. This gates everything.

**Status: COMPLETE** — findings doc §1–§2 (determination table, copy inventory,
import-or-copy fallback history, W1 gate run + recorded alignment-luck
deviation).

### W2 — Establish the cost of the current path

Using the existing benchmark harness, measure copy overhead as a function of
tensor size against kernel execution time. The win from eliminating two copies
per call scales with bytes moved per unit of compute — material for large
activations or high-frequency small kernels, noise for a few-KB tensor against a
multi-millisecond kernel.

*Answers:* whether the API constraint is defensible. If the engine is going to
force direct buffers on users, the number belongs in the README.

**Status: COMPLETE** — both arms measured. Native bench (findings §3: staged
copy 3–35× a bare memcpy; zero-copy worth up to ~90% of the staged call at
256 KB–4 MB); JMH arm re-run successfully 2026-08-04 15:23–15:27 under the §7
controls, exit 0 (findings §3: Java-side create cost 3.2 µs @ 4 KB to
26.2 ms @ 64 MB, aligned arm ≈ plain at ≤256 KB and measurably cheaper at
4 MB+; MobileNet denominator 61.6 ms steady-state, ~0.5% Java overhead — copies
are noise for real models, material only for memory-bound kernels). Two fixes
were needed en route, both recorded in findings §3: the `jmhJar` task wrote
`META-INF/services/ai.djl.engine.EngineProvider` twice (IREE + djl-api RPC)
and zip last-entry-wins shadowed IREE — `example/build.gradle.kts` now sets
`duplicatesStrategy = EXCLUDE`; and the run needs `-w/-r 250ms -gc true
-Xmx1536M` because per-invocation `create(float[], Shape)` allocates no heap
(`FloatBuffer.wrap`), so only explicit GC drains the Cleaner.

⚠️ **CONFIRMED OOM-kill — MUST run under safety controls (§7).** This is the
run that took down the machine on 2026-08-04: the `createCopy` fork (16
M-element / 64 MB tensors × the `aligned` flag) ballooned to 20.7 GB anon-RSS,
exhausted RAM+swap (free swap = 72 kB), and systemd-oomd killed the
snap-Firefox scope (30 processes) then the agent's terminal scope (kernel
journal 12:05:50 / 12:06:08). The mechanism is now pinned (findings §3): the
aligned arm's buffers are JNI-allocated and not counted against
`-XX:MaxDirectMemorySize`, and per-invocation heap churn is ~zero, so GC never
runs, the Cleaner never fires, and RSS grows at memcpy bandwidth (~5 GB/s)
regardless of heap caps. RESOLVED: the re-run above completed under
MemoryMax=4G with bounded iteration time + `-gc true`; the native bench at the
top sizes (64 MB staging allocations per call) stays moderate risk — same
controls.

### W3 — Alignment contract audit

Determine what alignment IREE's compiled artifacts actually assume on the CPU
backend, whether the current path can violate it, and whether the existing
aligned-new work already covers buffers that cross the JVM boundary or only
internal allocations.

*Answers:* whether alignment is a live hazard or a solved one. Note that a
violation here is silent, so absence of reported bugs is not evidence.

**Status: COMPLETE** — findings §4: the 64-byte contract is authoritative
(`IREE_HAL_HEAP_BUFFER_ALIGNMENT`), refusal semantics bound the hazard
(misalignment is a zero-copy miss, not a fault), and the JDK direct-buffer
histogram shows only ~41–44% of small buffers are 64-aligned. Kernel-side
codegen assumption left UNVERIFIED (no compiler sources in the pinned dist).

### W4 — Prototype borrow path behind a flag

Add an engine-allocated, aligned, borrow-based path alongside the existing copy
path, selectable at runtime. Do not remove the copy path.

*Answers:* feasibility, and provides the A/B needed by W2. Keeping both paths is
also the honest fallback if the lifetime hazards prove intractable.

**Status: COMPLETE** — prototype implemented and verified (native/core/
aligned_alloc.h, JNI `allocateDirectAligned`/`freeDirectAligned`/
`aliveAlignedBuffers`/`bufferAddress`, `iree.engine.alignedBuffers` flag,
Cleaner wiring, e2e + Cleaner + ASan tests); native QA passed including a
constrained replay (2026-08-04); findings §6 filled; Java suite re-run
2026-08-04 under the §7 controls — `./gradlew test leakTest
--no-configuration-cache` green (63 + 2 tests, including all four W4-specific
tests). Both paths stay; the flag is the W2 A/B switch and the honest fallback
if the §5 constraints ever bind.

⚠️ **OOM-kill risk — MUST run under safety controls (§7).** The full QA gate
(`native/build_qa.sh`) rebuilds the ASan-Debug tree at `-j$(nproc)` — 8
concurrent instrumented compilers is the memory spike. The 2026-08-04 rebuild
completed without incident (binaries 11:57); the fatal run that day was W2's
JMH arm, not this gate. The test phase alone is small (passed in ~12 s under a
4 G cap). The Java-side re-run (Gradle daemon + test workers, LeakStressTest
under a constrained direct-buffer budget) goes under the same controls — the
JMH incident proves the profile. RESOLVED: the Java-side re-run completed
under MemoryMax=4G.

### W5 — Lifetime hazard assessment

Assess the three hazards in §4 against the current architecture. Determine
whether the existing `NDArray` / `NDManager` lifecycle already provides the
needed guarantees or whether new machinery is required.

*Answers:* whether borrowing is safe here, which is a precondition for shipping
it regardless of the performance result.

**Status: COMPLETE** — findings §5 filled: all three hazards assessed against
the current architecture. Lifetime-vs-GC: JNI pins the buffer for the call;
the Cleaner is proven by the alive-counter test, with the address-only
registration rule. Completion-vs-return: `iree_runtime_call_invoke(flags=0)`
is synchronous for both drivers (call return == completion), proven
natively by the free-after-invoke ASan case; the borrow contract is safe
exactly while the engine stays synchronous — a recorded constraint, not a
blocker. Aliasing: bounded (inference-only engine, opt-in flag, copy path
defaults). Verdict: borrowing is safe here under the documented constraints.
Its own tests carry no OOM risk (small allocations); the Cleaner test folded
into W4's controlled Java run (done).

### W6 — Shared abstraction assessment

If W1–W5 are favorable, determine whether the aligned-native-buffer allocation
and JVM exposure layer is genuinely common between IREE and a prospective TVM-FFI
engine, or whether the apparent commonality dissolves on contact with the
differing buffer models.

*Answers:* whether to extract a shared module or duplicate a small amount of
code. Duplication is the correct answer if the commonality is superficial.

**Status: COMPLETE** — findings §7: **duplicate, don't extract.** The genuine
overlap is ~60 lines (`aligned_alloc.h` + the address capture); the JNI
exposure half is engine-specific ABI surface, and TVM-FFI's borrow model
differs in kind (caller-provided `TensorView`, no allocator). The seam is
marked in `aligned_alloc.h`; re-measure only if the TVM-FFI engine
materializes. Assessment only; no execution, so no safety-control tag.

---

## 4. Hazards to assess (W5)

Borrowing is strictly less safe than copying. Three specific issues:

**Lifetime versus GC.** Once a raw address is extracted from a direct
`ByteBuffer`, the native side holds no JVM reference. If the only live reference
is the argument to the native call, the buffer can in principle be collected and
its `Cleaner` free the memory mid-call. This requires either a strong reference
held for the call's duration or `Reference.reachabilityFence`. Copying makes this
impossible by construction.

**Completion versus return.** Under `local-sync` the borrow lifetime equals the
call duration, which is why this is safe to prototype there. Under `local-task`
fences or any async execution, the buffer must outlive the **completion signal**,
not the call return. A borrow contract built around call return will produce
corruption that reproduces roughly once a week under load once async execution is
introduced. If async is on the roadmap, the contract must be built for it now.

**Aliasing.** If a kernel writes into a borrowed input, the user's data is
silently mutated. Whether DJL's `NDArray` semantics tolerate this is a design
question, not just an implementation one.

---

## 5. Dependencies and sequencing

```
W1 (audit) ──> W2 (measure) ──┐
           └─> W3 (alignment) ├──> decision gate ──> W6 (shared abstraction)
               W4 (prototype) ─┤
               W5 (hazards) ───┘
```

- **W1 gates everything.** If the path is already borrow-based, close the brief
  and record why.
- **W2 depends on W4** for the comparison arm, but the copy-path baseline can be
  measured immediately.
- **W3 is independent** of W2 and W4 and can proceed in parallel.
- **W5 should complete before W4 is merged**, not before it is prototyped. A
  prototype that turns out to be unsafe is still useful for measurement.
- **Decision gate** after W2/W3/W5: proceed, proceed-with-constraints, or reject
  with the measurement recorded so the question stays closed.
- **W6 is last** and is contingent on the prospective TVM-FFI engine actually
  reaching a state where the commonality can be evaluated against something real
  rather than hypothetical.

---

## 6. Expected output

- A determination of which of §2's four questions were already answered, with
  pointers to where.
- A measured number for copy overhead across tensor sizes, retained regardless of
  the decision.
- A go / go-with-constraints / no-go on borrow-based host buffers in
  `djl-iree-engine`, with the reasoning recorded.
- If go: a statement of the user-facing API constraint and the rationale, in
  terms defensible in the README.
- A recommendation on W6 that explicitly permits "duplicate the code" as an
  outcome.

---

## 7. Execution safety controls (host OOM-kill protection)

Work items tagged ⚠️ in §3 involve runs with OOM-kill potential on this host.
The risk is CONFIRMED, not hypothetical: on 2026-08-04 the W2 JMH arm ran
uncontained, a `CopyCostBenchmark.createCopy` fork grew to 20.7 GB anon-RSS
(64 MB tensors × the `aligned` flag; JNI-allocated buffers are not counted
against `-XX:MaxDirectMemorySize`), and with RAM+swap exhausted
(free swap = 72 kB) `systemd-oomd` killed the snap-Firefox scope (30
processes) at 12:05:50 and then the agent's terminal scope
(`app-ghostty-surface-transient-*.scope`, kernel journal 12:06:08) — taking
the java fork, the shell, and the agent together. The two desktop freezes the
user reported before the crash were the PSI memory-pressure episodes
(journald flushed caches every few seconds from 12:05:24). systemd-oomd kills
WHOLE UNITS, so an uncontained run launched from a terminal inherits that
terminal's scope: the kill boundary includes the shell. The hard boundary
below is therefore required, not optional. All methods below were verified on
2026-08-04 on this host (Ubuntu 24.04, systemd 255, 31 G RAM, linux-x86_64).

### Primary control — user-scope cgroup (no elevation)

`systemd-run --user --scope` runs the command in a transient scope inside the
user manager's delegated cgroup (`user@.service` delegates `cpu memory pids`).
A `MemoryMax=` cap confines any OOM kill to the scope; the invoking shell is
untouched. It also moves the run OUT of the terminal's own scope, so an oomd
kill cannot take the shell with it — the exact 2026-08-04 failure mode. The
system-manager form (`systemd-run --scope` without `--user`) prompts polkit on
every run and is not a per-run option.

```bash
# recipe: memory cap + CPU containment + runtime bound
systemd-run --user --scope -p MemoryMax=4G taskset -c 0-3 timeout 900 bash <cmd>
```

Needs `XDG_RUNTIME_DIR` (present in a desktop session). Size `MemoryMax` at
~2× the run's expected peak.

### Always pair

- `timeout <sec> <cmd>` — runtime bound (SIGTERM at expiry); a memory cap does
  not stop a hang.
- `taskset -c <cpus>` — CPU containment only: pins placement and lowers
  `nproc`-derived parallel job counts (fewer concurrent ASan compilers → lower
  peak RSS). It is NOT a safety mechanism by itself.

### Fallbacks (if the systemd user manager is unavailable)

- Manual cgroup v2: `mkdir` a child under
  `/sys/fs/cgroup/user.slice/user-$(id -u).slice/user@$(id -u).service/`, write
  `memory.max`, move the process tree in — same effect without `systemd-run`.
- VmRSS watchdog: poll `/proc/<pid>/status` across the process tree and SIGKILL
  above a threshold.

### What does NOT protect the shell

- `ulimit -v` / `-d` (RLIMIT_AS/DATA): incompatible with ASan — its shadow
  memory reserves ~16 TB of virtual address space, so any sane cap breaks
  sanitizer startup. RLIMIT_RSS is unenforced on Linux.
- `numactl`: NUMA placement only; no OOM protection.
- Containers (`bwrap`/`unshare`): namespace isolation without a memory cap
  gives no OOM protection.
