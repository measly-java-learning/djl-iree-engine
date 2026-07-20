# `iree-runtime-dist` v3.11.0-3 — Usability Report

**Date:** 2026-07-20
**From:** `djl-iree-engine`
**To:** `measly-java-learning/iree-runtime-dist`
**Re:** end-to-end consumption of the published `v3.11.0-3` release, migrating off a hand-rolled
local IREE build (`native/cmake/ResolveIree.cmake`) onto the dist's CMake package.

This report answers one question directly, then indexes the issues filed while answering it, then
gives the specifics — what worked, what didn't, and what we're asking for next.

---

## Verdict

**Keep it. It delivered.** Every item the engine's wishlist called a Tier-1/Tier-2 priority
(`docs/superpowers/specs/iree-runtime-dist-wishlist.md` #1–#6) is shipped, is real, and closed a
category of guesswork the previous hand-rolled build had no choice but to live with. We migrated a
working, sanitizer-clean engine off a fragile local-build dependency onto this artifact in a
matter of days, and every native/JVM test that passed before the migration passes after it —
`rm -rf native/build && ./native/build.sh`, `iree_runtime_test` (9 cases), `iree_leak_harness`,
and `./gradlew test` (5 tests) all green, plus a new TSan leg that didn't exist before.

Every finding filed below (issues #2–#7) is a **documentation gap**, not a functional defect: a
wrong claim in prose, an underspecified interface, or an explicitly-flagged open question that we
answered by measurement. Nothing we found required a rebuild, a workaround baked into our
`CMakeLists.txt` beyond what good practice already recommends (`--exclude-libs,ALL`), or a
reason to distrust the artifact's correctness. The SHA256 supply-chain gate is real and we tested
it adversarially (see below). The pairing contract fails fast and legibly rather than corrupting
silently. If you were asking "is this worth maintaining or should we take it down" — take the
maintenance burden seriously (it clearly has a real team behind it) but do not take it down; it
is materially better than what it replaced.

---

## Index of filed issues

Issue #1 (`Docker layer cache never carries across releases`) predates our engagement — filed by
the dist project against itself, not by us. Ours are #2–#7, each filed with full reproduction
detail at the moment of discovery:

| # | Title | Impact / severity |
|---|---|---|
| [#2](https://github.com/measly-java-learning/iree-runtime-dist/issues/2) | `IreeRuntimePin.cmake` is coordinates-only, but the handover reads as if `include()` suffices | Minor — cost one stop-and-inspect cycle (had to `tar -xzf` and read the config by hand to discover the real ~8-line `FetchContent` contract); non-blocking, migration succeeded same day. |
| [#3](https://github.com/measly-java-learning/iree-runtime-dist/issues/3) | Pin variable naming (`..._default_linux-x86_64`, mixed `_`/`-` separators) won't scale cleanly to more variants/platforms | Cosmetic today (one variant, one platform ship); becomes a real papercut once `devtools`/`gpu`/`windows-x86_64` land and consumers build these names programmatically. |
| [#4](https://github.com/measly-java-learning/iree-runtime-dist/issues/4) | **Answer** to handover §3.1 (symbol visibility): `-Wl,--exclude-libs,ALL` fully contains IREE symbols in a JNI `.so` — measured | Not a defect — closes one of the handover's two explicitly-open questions. Measured: 1348 IREE symbols linked in, 0 exported; only our 4 JNI entry points + 2 lifecycle hooks + 5 weak C++ template symbols are visible. |
| [#5](https://github.com/measly-java-learning/iree-runtime-dist/issues/5) | Handover §5 misdescribes `add.vmfb` as taking `int32` inputs; it is `f32` | Moderate documentation defect, safe failure mode — `iree-dump-module` and `iree-run-module` both confirm `tensor<4xf32>` / element-type tag `0x21000020` (`FLOAT_32`); an `int32`-typed caller gets a hard `INVALID_ARGUMENT`, not a silent wrong answer, but the wrong prose cost real debugging time before we ran the dump ourselves. |
| [#6](https://github.com/measly-java-learning/iree-runtime-dist/issues/6) | `element_types.json` / `status_codes.json`: no schema doc, no non-CMake discovery path | Low severity — worked around with a hardcoded relative path (`share/iree-runtime-dist/<name>` under the prefix); every non-CMake (Gradle/Python/Rust) consumer will independently hit and re-solve the same "how do I find this file" question, since the only recorded path today is inside `IreeRuntimeDistConfig.cmake`'s CMake variables. Comment on the issue also flags the entry-count coupling: our codegen currently assumes 24/19 entries and would need to notice, not silently ignore, a future schema change. |
| [#7](https://github.com/measly-java-learning/iree-runtime-dist/issues/7) | **Answer** to handover §3.3 (TSan + `local-sync`): empirically thread-free under TSan — measured | Not a defect — closes the second of the handover's two explicitly-open questions. TSan clean over 100 cycles; `strace -f -e trace=clone,clone3` recorded zero thread-creation syscalls across the whole run; `/proc/<pid>/status` sampled mid-invoke read `Threads: 1`. Confirms a shipped `minimal` variant is not needed for this specific concern. |

**Both of the handover's explicitly-open questions (§3.1 symbol visibility, §3.3 TSan under
`local-sync`) are now answered** — see #4 and #7 above.

---

## What worked without friction

This is as informative as the complaints, so it's specific rather than a blanket "it was fine":

- **The umbrella target resolved with zero hand-listed archives.** `find_package(IreeRuntimeDist
  REQUIRED)` + `target_link_libraries(... iree-runtime-dist::runtime)` is genuinely the entire
  link surface. The old `ResolveIree.cmake` had to hand-derive `libiree_runtime_unified.a`,
  `libflatcc_parsing.a`, `libflatcc_runtime.a`, and the `IREE_ALLOCATOR_SYSTEM_CTL` define by
  reading `nm` output; none of that guesswork survived the migration.
- **The SHA256 gate is real, and we tested it adversarially.** Deliberately corrupting the
  recorded hash in a local copy of the pin causes `FetchContent` to fail hard at configure time,
  before any code is compiled — exactly the supply-chain behavior the wishlist asked for.
- **The §7 `find_package(Threads)` defect (from the handover) is verifiably repaired.** The
  shipped `IREERuntimeConfig.cmake` calls `find_package(Threads)` before including its targets
  file, with a comment citing the original bug. A bare `find_package(IreeRuntimeDist)` in a
  minimal consumer `CMakeLists.txt` configures cleanly.
- **The pairing contract fails fast and legibly.** When our own stale test fixture (compiled
  against an older runtime) didn't match this dist's linked runtime, the failure was
  `hal version mismatch; have 6 but require 7` at module-load time — not a cryptic VM import
  signature mismatch, which is exactly the failure mode the old from-source-build era produced
  and exactly what the compatibility manifest (`vm_bytecode_version`) was designed to prevent.
- **Generated constants matched our independently-derived values exactly.** `element_types.json`
  and `status_codes.json` (24 and 19 entries) produced `FLOAT_32 = 0x21000020`,
  `SINT_32 = 0x11000020`, etc. — identical to values we had separately derived by hand from IREE's
  headers during the earlier from-source build. This is strong independent cross-validation, not
  just internal consistency.
- **`add.vmfb` loads and runs with zero compiler anywhere in the build**, exactly as promised —
  once its element type was correctly understood (see #5).

---

## Corrections to the handover

Read against `docs/2026-07-20-djl-iree-engine-handover.md`, everything held except one factual
claim:

- **§5, `add.vmfb` element type.** The handover states it "takes four `int32` inputs." Measured
  (via `iree-dump-module` and `iree-run-module`, both pinned to stable 3.11.0): the function
  signature is `tensor<4xf32>` for all three arguments/results, and the baked-in element-type tag
  is `0x21000020` (`FLOAT_32`), not `0x10000020` (`INT_32`). See issue #5. The golden values
  `[11, 22, 33, 44]` are correct either way — only the type claim was wrong.

Everything else in the handover — the pin's SHA-256 verification, the umbrella-target link
surface, the `Threads` repair, the pairing-contract manifest fields, the "0 `.so`, 198 `.a`"
count, the "no LLVM in the notices" claim, and both of its explicitly-flagged open questions
(§3.1, §3.3) — checked out exactly as written once we measured it independently.

## Net assessment of severity

**All six of our findings are documentation gaps, not defects in the shipped artifact.** Nothing
we found:

- required a workaround more invasive than a link flag we'd want anyway (`--exclude-libs,ALL`),
- produced an incorrect numeric value anywhere (every generated constant checked out),
- silently miscompiled, misran, or misreported a result, or
- required us to fork, patch, or bypass any part of the dist.

The two most consequential items (#2 pin usage, #5 `add.vmfb` element type) both cost real time
during discovery, but both are prose corrections with an unambiguous fix. The two "answer"
issues (#4, #7) exist specifically because the handover was honest about what it hadn't verified
— that transparency is itself a point in the dist's favor, not against it.

---

## Prioritized requests

1. **`devtools` variant (Tracy tracing + allocation statistics)** — highest priority. This
   blocks the latency/footprint observability work called out in the engine's deferred milestone
   list; there is currently no way to get per-dispatch timing or allocator peak/growth stats from
   this artifact at all, since `IREE_ENABLE_RUNTIME_TRACING` is off in the only shipped variant.
2. **`windows-x86_64`** — next priority. `platforms_json` is already the single source of truth
   for the release matrix per the handover, so this is mechanical on the dist side; it unblocks
   the engine's currently Linux-only status.
3. **`gpu` variant** — lower priority. Correctly framed by our own wishlist as a milestone, not a
   flag flip (needs a GPU-target compiler and non-CPU memory marshaling work on our side too), so
   there's no urgency to have it before we're ready to consume it.
4. **A `minimal` (TSan-free-by-construction) variant** — lowest priority, and arguably not needed
   at all. Issue #7 demonstrates that runtime `local-sync` selection is empirically sufficient for
   a TSan-clean leg even with `local-task` compiled in, which was the specific concern `minimal`
   was proposed to solve. If `minimal` is ever built for other reasons (smaller binary, fewer
   linked symbols), that's independently welcome — just not on the TSan critical path.
