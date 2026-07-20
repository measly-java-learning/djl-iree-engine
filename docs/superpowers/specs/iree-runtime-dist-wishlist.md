# `iree-runtime-dist` — What the DJL IREE Engine Wants From It

**Date:** 2026-07-19
**Status:** Input / wishlist for a hypothetical upstream project (not yet started)
**Audience:** whoever builds `iree-runtime-dist` — the upstream that builds and publishes the
IREE runtime for `djl-iree-engine` to consume, analogous to
[`executorch-runtime-dist`](https://github.com/measly-java-learning/executorch-runtime-dist)
for the ExecuTorch engine.

## Purpose

`djl-iree-engine` deliberately does **not** build the IREE runtime; it links a prebuilt one
(the design's escape hatch is `IREE_INSTALL` pointing at a local build tree). This document
lists what a proper dist project should publish so the consumer stops reverse-engineering
things it has no authoritative source for.

**Organizing principle:** the dist owns anything that requires the **IREE source tree**, the
**build configuration**, or the **matching compiler** — precisely the set of things the
skeleton build had to guess at or derive downstream. Every item below is grounded in a concrete
cost paid during the `djl-iree-engine` walking-skeleton build (2026-07); see
`docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md` for that build's findings.

The four items where time was demonstrably lost — **#2, #3, #4, #6** — are the priorities.

---

## Tier 1 — eliminates the biggest fragility

### 1. The CMake pin (FetchContent) — already understood to be on the list
The `EtRuntimePin.cmake` analogue: a hash-pinned, build-attested tarball, with the SHA256 as the
supply-chain review gate. The skeleton ships a stub seam for exactly this:
`native/cmake/IreeRuntimePin.cmake`, which the dist release asset replaces wholesale.
`native/cmake/ResolveIree.cmake` is the temporary hand-rolled stand-in.

### 2. A real install tree shipping `IREERuntimeConfig.cmake`
**The single biggest item after the pin.** The local build was never `ninja install`ed, so there
was no CMake package and `ResolveIree.cmake` had to reconstruct the entire link surface by hand —
and got it wrong on the first attempt. What a `find_package(IREERuntime)` config would eliminate:

- **Archive selection.** `libiree_runtime_impl.a` turned out to be only 3 object files
  (call/instance/session); the real link target was `libiree_runtime_unified.a` (239 objects).
- **Transitive third-party archives.** Had to hand-add `libflatcc_parsing.a` + `libflatcc_runtime.a`
  (the VM bytecode verifier's flatcc dependency), found by `nm`-grepping for undefined symbols.
- **Compile defines.** Had to inject `IREE_ALLOCATOR_SYSTEM_CTL=iree_allocator_libc_ctl` — IREE's
  own build sets this from the `IREE_ALLOCATOR_SYSTEM` cache var; a proper config propagates it,
  a bypass does not (the header won't even declare `iree_allocator_system()` without it).
- **Split include dirs.** Public headers live in the **source** tree (`runtime/src`), generated
  headers (flatbuffer schemas, config) in the **build** tree — an install merges them.
- **Link ordering.** The `--start-group`/`--end-group` dance for the mutually-recursive archives.

All of the above is guesswork a proper install/config makes disappear. This is where the
`IREE_INSTALL`-points-at-a-build-tree escape hatch is at its most fragile.

**The shipped config and install tree must be relocatable** — no absolute build-system paths
baked into published assets. (This is the "relocatability" concern proper: absolute-path leakage,
unrelated to the glibc-floor sense in #8.) ET proves the pattern — `find_package(executorch)`
resolves from a downloaded tarball — so reuse it rather than re-deriving `install(EXPORT)`
mechanics; the IREE-specific leak surfaces to scrub or verify are:

- **CMake config paths.** Generate the package config via `configure_package_config_file` /
  `install(EXPORT)` so paths resolve `${CMAKE_CURRENT_LIST_DIR}`-relative — not the absolute
  build-tree + source-tree include dirs `ResolveIree.cmake` had to hard-wire.
- **Source paths in status messages and debug info.** IREE embeds `__FILE__` in status strings
  (the version-mismatch error surfaced `iree/runtime/src/iree/vm/context.c:275`) and DWARF carries
  `DW_AT_comp_dir`. That dump path already looked relative, so IREE likely handles this — but
  confirm the dist build preserves it (`-ffile-prefix-map=`/`-fdebug-prefix-map=`) rather than
  baking `/home/.../iree`.
- **RPATH/RUNPATH** on any shipped `.so` (`patchelf`/`chrpath` to scrub), and don't ship
  build-tree metadata (`CMakeCache.txt`, `compile_commands.json`) in the tarball.

### 3. A compiler↔runtime compatibility manifest, and the matching `iree-compile`
**The version-alignment saga, prevented at the source.** The pip *stable* `iree-compile` 3.11.0
emitted a `.vmfb` whose `hal.command_buffer.dispatch` VM import signature
(`0rriiiiICiDCiirIID_v`) mismatched the linked runtime built from source commit `a869dc3`
(`3.12.0.dev`, signature `0rrIiiiICiDCiirIID_v`), so the module **failed to load** at VM context
creation. Recovery was to match a pip *nightly* (`iree-base-compiler==3.12.0rc20260717`) by date
and verify against the build tree's own `iree-run-module`.

A dist release should publish, per runtime artifact:
- the exact `runtime_commit` it was built from,
- the **compatible `iree-compile` version/commit** (and ideally pin/ship it), so consumers
  produce loadable `.vmfb`s by construction,
- the HAL/VM module ABI version the runtime expects (`iree-dump-module` already surfaces the
  axis as `Module Dependencies: hal, version >= N`), so a consumer can fail fast with a clear
  message instead of a cryptic signature mismatch.

Also record here (so it's not rediscovered): the pip **`iree-base-runtime` wheel is not
linkable** — no headers, no static libs, at any version. Only a from-source build (or this dist)
yields a linkable runtime.

---

## Tier 2 — generated constants and smoke artifacts

### 4. Element-type (dtype) constants, generated from the source
Concrete bug paid here: the plan hard-coded `FLOAT_32 = 0x00000120`; the real value is
`0x21000020` (from `IREE_HAL_ELEMENT_TYPE_VALUE(numerical_type, bit_count) = (num<<24)|bits`,
`FLOAT_IEEE=0x21`, `SINT=0x11`, so `SINT_32=0x11000020`). It silently "worked" through Tasks 3–8
because IREE ignores the caller-declared **input** element type when the tag isn't a real
encoding — and only surfaced when the DJL layer mapped an **output** type back. Upstream this is
trivial and authoritative: compile a tiny program that emits every `IREE_HAL_ELEMENT_TYPE_*` as
JSON, or codegen `IreeDataTypes.java` directly, and ship it in the tarball. Downstream the only
options are hard-coding (got it wrong) or parsing a C header (fragile).

### 5. Status-code enum, same mechanism
`iree_status_code_t` values (OK, INVALID_ARGUMENT, NOT_FOUND, …). The engine currently throws a
`RuntimeException` carrying only the message string. If typed Java exceptions are ever wanted
(e.g. distinguishing a shape/type rejection from a missing entry point), these enum values are
another generated manifest rather than a hand-transcription.

### 6. A canonical `add.vmfb` smoke artifact, compiled with the matching compiler
ExecuTorch's dist shipped `add.pte` to assert (post-link) that the XNNPACK backend survived. If
the IREE dist ships a guaranteed-compatible `add.vmfb`, a consumer can smoke-test "the runtime
loads and runs a known module" **without needing a compiler at all** — which would have
sidestepped the entire compiler-version-matching detour for the link/load test. Because the dist
has the matching compiler, only the dist can ship an artifact guaranteed to load.

---

## Runtime variants — define the matrix up front

Start here, not later: the variant matrix is far cheaper to design in than to bolt on, because
it dictates how the build is parameterized and what gets published per release. IREE makes this
cleaner than ExecuTorch — nearly everything capability-relevant in the *runtime* is gated behind
two build-time axes, **HAL drivers** (what can execute) and **executable loaders** (what compiled
code can be loaded), plus a **tracing/stats** axis that is the direct `devtools` analogue.

What each optional piece actually unlocks for *this* engine, and how much of the feature the flag
alone delivers:

- **`local-task` HAL driver → CPU multithreading (intra-op parallelism).** The clearest variant
  axis. The skeleton deliberately used `local-sync` (inline, single-threaded) so there are **no
  IREE-internal threads** — that is why the skeleton has no TSan leg at all. `local-task` adds a
  worker pool for throughput on multicore CPUs. Flag alone unlocks it; caveat: it reintroduces
  internal threads, so a `local-task` variant is also where TSan coverage must come back.
- **Tracy tracing → per-dispatch latency / execution timelines.** Build-gated
  (`IREE_ENABLE_RUNTIME_TRACING` + Tracy); the pip wheel already ships a separate `_runtime_tracy`.
  This is what the design's §10 latency reality-check and the deferred benchmark milestone want.
  Overhead ⇒ separate variant. Flag alone unlocks it (just don't strip the Tracy symbols).
- **Allocation statistics → device-allocator counts / peak bytes.** Turns "ASan looked flat" into
  a reported peak + zero-growth assertion across N invocations — the observability half of
  `devtools`. (Confirm the exact IREE CMake flag spelling.)
- **GPU drivers (CUDA / Vulkan / HIP / Metal) → GPU inference.** The biggest capability, and
  impossible without the driver compiled in — but the flag is only the *prerequisite*: it also
  needs the `.vmfb` compiled for that backend (compiler-side, ties to the compatibility manifest,
  #3), the matching loader, and it **breaks the skeleton's CPU-coherent-memory assumption** (the
  copy-out invalidate-range footnote stops being hypothetical; the WRAPPED/STAGED import story
  changes because device memory isn't host-visible). A milestone, not a flag flip — name it as a
  separate axis so nobody assumes "ship the CUDA driver" is sufficient.
- **Executable loaders (`embedded-elf` vs `system-library`) → compatibility, not a feature.** Must
  match how the `.vmfb` was compiled for `llvm-cpu`; shipping both is flexibility, omitting the
  needed one blocks *loading*. `vmvx-module` (reference interpreter) is a niche portability
  fallback.
- **Logging / slf4j PAL bridge → the ExecuTorch `logging` analogue.** Maps to the engine's
  deferred slf4j bridge. IREE's runtime logging is lighter than ExecuTorch's `ET_LOG` PAL (it
  leans on `iree_status` messages), so confirm whether forwarding diagnostics needs a build flag
  or is always available.

### Proposed variant matrix

| Variant | Drivers / loaders | Tracing / stats | Unlocks | Engine caveat |
|---|---|---|---|---|
| `minimal` (bare) | `local-sync` + `embedded-elf` (+ `system-library`) | none | smallest, single-threaded; the skeleton's target | none — TSan-free by construction |
| `default` / `perf` | + `local-task` | none | CPU intra-op parallelism / throughput | reintroduces internal threads ⇒ needs TSan coverage |
| `devtools` | as `default` | Tracy + allocation stats | per-dispatch latency, footprint assertions | tracing overhead ⇒ keep separate from `perf` |
| `gpu` (separate axis, later) | + a GPU driver + matching loader | optional | GPU inference | needs GPU-target compiler + non-CPU marshaling work |

For this CPU engine, **`local-task` (the `perf` variant) and the Tracy/stats `devtools` variant
are the two that deliver real features for little more than the right build flag** — those are the
ones a dist should offer first. GPU is the big capability but a milestone, not a variant flip.

## Tier 3 — distribution hygiene (dist's job, far easier at the source)

### 7. Build-config attestation + trimmed variants
The skeleton verified PIC by reading relocations with `readelf` and confirmed
`local-sync`/`embedded-elf` from `CMakeCache.txt`. Upstream these are just the build config —
attest them in a manifest (PIC on; HAL drivers + executable loaders enabled; Release;
`BUILD_SHARED_LIBS=OFF`; glibc floor) — per variant, since the driver/loader/tracing set is
exactly what the variant matrix selects (see "Runtime variants" above).

### 8. glibc floor via the right container
Build inside a `manylinux_2_28`-equivalent so the shipped `.so` holds a known glibc floor. Prefer
building *against* an old glibc (the manylinux approach `executorch-runtime-dist` uses) over
building new and repairing after — a dist controls its own container, so symbol-version repair
(polyfills, `patchelf`) is a fragile fallback, not the strategy.

Two IREE-specific deltas make this easier than for ExecuTorch:

- **The floor is unconstrained by any torch wheel.** ExecuTorch's 2.28 floor was *forced* by
  `torch==2.12.0`'s wheel. The IREE runtime is a standalone C library with no torch dependency, so
  the shipped `.so`'s glibc floor is purely a choice of build-container glibc — go as old as you like.
- **The C++ runtime floor comes from the JNI shim, not IREE.** IREE's runtime is C; the only
  `libstdc++` dependency in the shipped `.so` is the engine's own C++ shim, so libstdc++ versioning
  is a shim-toolchain choice and IREE adds nothing to it.

The "clang CI container has too-new glibc" wall is a non-issue here: clang is independent of the
container's glibc — install a recent clang *into* an old-glibc base (exactly what manylinux does)
rather than starting from a stock clang image. A build-environment concern, deferred in the
skeleton, squarely dist territory.
The available version of `clang`/`lld` for this container is 21.1.8 as determined by inspection.
Will need to identify additional required packages.  For Python, use CPython 3.12 as that's the
newest non-threaded runtime documented for the available Python packages. (3.10 and 3.11 are both available but why risk it?)

### 9. Third-party license notices
IREE vendors LLVM, flatcc, and more. Collecting `LICENSE`/notice files is trivial from the source
tree and tedious downstream; ExecuTorch's dist bundles `THIRD-PARTY-NOTICES` into the classifier
JAR (`META-INF/licenses/...`). The IREE dist should do the same.

### 10. Per-platform artifacts
`linux-x86_64` now; `windows-x86_64` later (with the MSVC/CRT considerations the skeleton
deferred). Feeds the per-platform classifier-JAR packaging the engine also deferred.

---

## Cross-references

- Consumer stub seam to be replaced by #1: `native/cmake/IreeRuntimePin.cmake`
- Hand-rolled stand-in that #2 obsoletes: `native/cmake/ResolveIree.cmake`
- The findings that motivated #3 and #4: the "Version alignment" and element-type sections of
  `docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md`
- Reference dist project for the overall shape: `executorch-runtime-dist`
