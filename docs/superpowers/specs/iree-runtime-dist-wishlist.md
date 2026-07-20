# `iree-runtime-dist` — What the DJL IREE Engine Wants From It

**Date:** 2026-07-19
**Status:** Input / wishlist — originally written against a hypothetical upstream project; that
project has since shipped as `iree-runtime-dist` v3.11.0-3 and this engine now consumes it. Items
below are annotated **delivered in v3.11.0-3** where satisfied, and three items (#2, #8, #9) carry
2026-07-20 corrections where the published artifact's actual shape differed from what this
document assumed. See `docs/2026-07-20-djl-iree-engine-handover.md` (from the dist project) and
`docs/2026-07-20-iree-runtime-dist-usability-report.md` (our reply, with filed issues) for the
verified facts.
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

**Delivered in v3.11.0-3.** `IreeRuntimePin.cmake` ships as a release asset and drops straight
into the stub seam. The SHA256 gate is real: a tampered hash fails hard at configure time (we
tested this). One caveat filed as an issue: the pin is coordinates-only (URL + SHA256 variables),
not a drop-in `include()`-and-go file — see filed issue #2 in the usability report.

The `EtRuntimePin.cmake` analogue: a hash-pinned, build-attested tarball, with the SHA256 as the
supply-chain review gate. The skeleton ships a stub seam for exactly this:
`native/cmake/IreeRuntimePin.cmake`, which the dist release asset replaces wholesale.
`native/cmake/ResolveIree.cmake` is the temporary hand-rolled stand-in, now deleted.

### 2. A real install tree shipping `IREERuntimeConfig.cmake`

**Delivered in v3.11.0-3.** `find_package(IreeRuntimeDist REQUIRED)` +
`iree-runtime-dist::runtime` is the entire link surface; the umbrella target resolves with zero
hand-listed archives. One naming caveat filed as an issue: the pin's per-variant/per-platform
variable naming (`IREE_RUNTIME_URL_default_linux-x86_64`) won't scale cleanly as more
variants/platforms ship — see filed issue #3.

**Correction (2026-07-20): RPATH/`patchelf` scrubbing was never the dist's job, and is not listed
here as delivered.** This item originally framed RPATH/RUNPATH hygiene as something the dist
needed to scrub from "the shipped `.so`." **The dist ships 0 `.so` files and 198 `.a` files** —
there is no shared object in the artifact for RPATH to leak from. The only shared object in the
whole system is **our own JNI shim**, so RPATH/RUNPATH hygiene on it is entirely ours to own, not
the dist's. The CMake-config relocatability point below is unaffected by this and was delivered.

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
- Don't ship build-tree metadata (`CMakeCache.txt`, `compile_commands.json`) in the tarball.
  (**Not** RPATH/RUNPATH scrubbing — the dist ships no `.so` at all, so there is nothing of
  that kind to scrub; see the correction above. RPATH hygiene on our own JNI `.so` is ours.)

### 3. A compiler↔runtime compatibility manifest, and the matching `iree-compile`

**Delivered in v3.11.0-3** — as a manifest, not a shipped compiler (that part of the ask was
never going to happen; `IREE_BUILD_COMPILER=OFF` always). `manifest.json` records
`iree_compile_version`, `runtime_commit`, `iree_tag`, and `vm_bytecode_version`, and the pairing
contract failed **fast and legibly** when our stale test fixture didn't match this dist's runtime
(`hal version mismatch; have 6 but require 7`) rather than a cryptic signature error. See
`docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md` for how this dissolves the
version-alignment saga described below.

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

**Delivered in v3.11.0-3.** `element_types.json` (24 entries) is emitted by a program compiled
against the shipped headers, and `IreeDataTypes.java` is now codegen'd from it at build time.
Every generated value — including the corrected `FLOAT_32 = 0x21000020` and
`SINT_32 = 0x11000020` below — matched our independently header-derived values exactly.

Concrete bug paid here: the plan hard-coded `FLOAT_32 = 0x00000120`; the real value is
`0x21000020` (from `IREE_HAL_ELEMENT_TYPE_VALUE(numerical_type, bit_count) = (num<<24)|bits`,
`FLOAT_IEEE=0x21`, `SINT=0x11`, so `SINT_32=0x11000020`). It silently "worked" through Tasks 3–8
because IREE ignores the caller-declared **input** element type when the tag isn't a real
encoding — and only surfaced when the DJL layer mapped an **output** type back. Upstream this is
trivial and authoritative: compile a tiny program that emits every `IREE_HAL_ELEMENT_TYPE_*` as
JSON, or codegen `IreeDataTypes.java` directly, and ship it in the tarball. Downstream the only
options are hard-coding (got it wrong) or parsing a C header (fragile).

### 5. Status-code enum, same mechanism

**Delivered in v3.11.0-3.** `status_codes.json` (19 entries) ships alongside the element types.
Mapping it to typed Java exceptions is a deliberate deferral, not a gap in the dist — see filed
issue #6 for a documentation gap in how the JSON schema is discovered/scoped.

`iree_status_code_t` values (OK, INVALID_ARGUMENT, NOT_FOUND, …). The engine currently throws a
`RuntimeException` carrying only the message string. If typed Java exceptions are ever wanted
(e.g. distinguishing a shape/type rejection from a missing entry point), these enum values are
another generated manifest rather than a hand-transcription.

### 6. A canonical `add.vmfb` smoke artifact, compiled with the matching compiler

**Delivered in v3.11.0-3.** `share/iree-runtime-dist/add.vmfb`, exposed as
`IREE_RUNTIME_DIST_ADD_VMFB`, loads and runs with no compiler installed anywhere in the build.
One correction filed as an issue: the handover's §5 describes it as taking `int32` inputs; it is
`f32` (independently confirmed) — see filed issue #5.

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
  IREE-internal threads** — that is why the skeleton has no TSan leg at all. **[Superseded —
  see "Status as of v3.11.0-3" below and the findings doc: Task 4 measured `local-sync` as
  TSan-clean at runtime even with `local-task` compiled in, so the skeleton's TSan leg exists
  after all.]** `local-task` adds a
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
| `minimal` (bare) | `local-sync` + `embedded-elf` (+ `system-library`) | none | smallest, single-threaded; the skeleton's target | none — TSan-free by construction **[Superseded — see "Status as of v3.11.0-3" below and the findings doc: this is measured true of the shipped `default` variant at runtime too, so a separate `minimal` variant is not actually needed for this.]** |
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

**Correction (2026-07-20): `glibc_build` is not a floor, and this item's framing was wrong.**
`manifest.json` records `"glibc_build": "2.28"`, but that is the glibc of the **container the
archives were compiled against**, not a compatibility floor the dist can hand us. Static
archives carry *unversioned* undefined libc symbols; glibc symbol-version resolution happens at
**our** final link, when the JNI `.so` is produced — not inside a `.a`. Scanning the shipped
archives for `GLIBC_x.y` strings is structurally incapable of yielding a floor. **Our shim's
glibc floor is set by our own link container**, not by the dist's build container. Restated:
build inside a `manylinux_2_28`-equivalent for *our own JNI shim link*, not as something to
request of the dist.

The two observations below survive intact — they describe constraints on our link, not the
dist's:

- **The floor is unconstrained by any torch wheel.** ExecuTorch's 2.28 floor was *forced* by
  `torch==2.12.0`'s wheel. The IREE runtime is a standalone C library with no torch dependency, so
  our shim's glibc floor is purely a choice of our own build-container glibc — go as old as we like.
- **Clang is independent of the container's glibc.** The "clang CI container has too-new glibc"
  wall is a non-issue: install a recent clang *into* an old-glibc base (exactly what manylinux
  does) rather than starting from a stock clang image. This is our build-environment concern, not
  the dist's — deferred in the skeleton, and now squarely our own territory rather than something
  to ask upstream for.

**Correction to the §8 libstdc++ claim:** the conclusion still holds, but the premise it rested on
was wrong. Scanning all 198 shipped archives finds 385 undefined C++ symbols (`std::`, `__cxa_`,
`operator new`) — so "IREE's runtime is C" is not quite true of the artifact as a whole. Those
symbols are confined entirely to `libbenchmark.a` and `libiree_testing_benchmark.a`, and **neither
is in the `iree_runtime_unified` umbrella target's link closure**. Restated: *link the umbrella
target and your libstdc++ story is yours alone* — the conclusion (libstdc++ versioning is a
shim-toolchain choice, IREE adds nothing to our final link) holds, but only because we link the
umbrella target and not because IREE's code is uniformly C.

The available version of `clang`/`lld` for this container is 21.1.8 as determined by inspection.
Will need to identify additional required packages.  For Python, use CPython 3.12 as that's the
newest non-threaded runtime documented for the available Python packages. (3.10 and 3.11 are both available but why risk it?)

### 9. Third-party license notices

**Correction (2026-07-20): "IREE vendors LLVM, flatcc, and more" is wrong for this artifact.**
`THIRD-PARTY-NOTICES/` contains exactly three entries — `flatcc/`, `libbacktrace/`, `printf/` —
and **no LLVM**, because `IREE_BUILD_COMPILER=OFF` means no LLVM code is present in the artifact
at all (measured: zero LLVM/MLIR symbols across all 198 archives). Copy the directory as shipped
into the classifier JAR's `META-INF/licenses/`; do not derive the notice list from IREE's
submodule list, which is a much larger set that includes code never linked into this artifact.

Collecting `LICENSE`/notice files is trivial from the source
tree and tedious downstream; ExecuTorch's dist bundles `THIRD-PARTY-NOTICES` into the classifier
JAR (`META-INF/licenses/...`). The IREE dist does the same, and — unlike ExecuTorch's LLVM-vendoring
case — the notice set here is small because the compiler (and its LLVM dependency) is out of
scope for the runtime artifact entirely.

### 10. Per-platform artifacts
`linux-x86_64` now; `windows-x86_64` later (with the MSVC/CRT considerations the skeleton
deferred). Feeds the per-platform classifier-JAR packaging the engine also deferred. **Still
open** as of v3.11.0-3 — only `linux-x86_64` ships.

---

## Status as of v3.11.0-3

**Delivered:** #1 (pin), #2 (config/manifest, with the RPATH correction above), #3
(compatibility manifest, with `add.vmfb` as #6), #4 (element types), #5 (status codes), #6
(`add.vmfb`).

**Still open:** the `devtools` variant (Tracy tracing + allocation statistics — blocks
latency/footprint observability work), the `gpu` variant, `windows-x86_64` (#10), and a
`minimal` TSan-free-by-construction variant. On that last point: Task 4's empirical result (see
the findings doc) shows a `minimal` variant is not actually needed for the TSan concern that
motivated it — `local-sync`, selected at runtime from the single shipped `default` variant, is
measured thread-free under TSan even though `local-task` is compiled in. See
`docs/2026-07-20-iree-runtime-dist-usability-report.md` for the full verdict and filed-issue
index.

---

## Cross-references

- Consumer stub seam to be replaced by #1: `native/cmake/IreeRuntimePin.cmake`
- Hand-rolled stand-in that #2 obsoletes: `native/cmake/ResolveIree.cmake`
- The findings that motivated #3 and #4: the "Version alignment" and element-type sections of
  `docs/superpowers/specs/2026-07-19-djl-iree-engine-findings.md`
- Reference dist project for the overall shape: `executorch-runtime-dist`
