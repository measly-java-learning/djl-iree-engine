# Scoping notes: IRPA parameter support + vmfb target selection

**Status:** scoping notes, NOT a spec. Captured 2026-07-22 to seed a future
"model artifact layout" design. No decision made to *build* either feature yet.

**Update 2026-07-25 — the user contract is decided** (see Part 3). Everything else here
remains open scoping. The contract is: *point the engine at a manifest JSON document; every
asset it names is resolved relative to that document; archives are explicitly unsupported.*
Part 1's discovery question and Part 3's packaging question are settled by it; the
implementation questions are not.

**Update 2026-07-25 (later) — the IRPA spike (Part 4) has run; verdict GO.** Results in
`docs/2026-07-25-irpa-spike-findings.md`. It confirmed Part 1's "Big rock" finding empirically
and **corrected two claims in Part 1 that were wrong**: the mmap/zero-copy marshalling
rationale, and the ownership assertion that "index/provider/module + the mmap must outlive the
session and every invoke" (IREE retains all of it; we hold none). Both corrections are marked
in place below. The Part 3 manifest contract is unaffected and now justified.

These two questions came up together and are related at the packaging layer, so
they're recorded together. The organizing insight: **they are orthogonal because
they live on different halves of the model** — the `.vmfb` is the (hardware-specific)
program, the parameters are (hardware-agnostic) weights.

---

## Part 1 — IRPA (IREE Parameter Archive) support

IRPA stores model parameters/weights separately from the compiled `.vmfb`, so the
program can be compiled once and (large) weights loaded separately.

### Feasibility: already in the dist (no upstream ask)
The linked `iree-runtime-dist` v3.11.0-3 `default` artifact ships the full stack —
verified in its include tree and archives:
- headers: `iree/io/parameter_index.h`, `parameter_index_provider.h`,
  `parameter_provider.h`, `file_handle.h`, `iree/modules/io/parameters/module.h`,
  `iree/io/formats/irpa/irpa_parser.h`
- symbols compiled in: `iree_io_parameters_module_create`,
  `iree_io_parameter_index*` (36), `iree_io_parse_file_index`, irpa parser.

Contrast with TSan (issue #9): this needs **no** dist change.

### Marshalling: pass the PATH, not the bytes

> **Conclusion CONFIRMED, rationale CORRECTED 2026-07-25** by the spike's Q8 —
> see `docs/2026-07-25-irpa-spike-findings.md`. Passing the path is still right. The
> reason given below was **wrong**, and is struck rather than deleted because the wrong
> reason is the one a reader is likely to arrive with.

~~The vmfb is copied through the JVM because it's a small program blob. IRPA is the
opposite case by design — it exists to hold **large** weights and the format is
built to be **`mmap`'d from disk** (aligned data section, zero-copy).~~

~~- **Right approach:** pass the `.irpa` **file path** (a `String`) across JNI; IREE
  `mmap`s it via `iree_io_file_handle`. Marshalling is trivial (a path).~~
~~- **Wrong default:** marshalling the file *bytes* (`byte[]` → JNI → host buffer)
  copies multi-GB weights into JVM heap → across JNI → host memory, and throws away
  the mmap. Fine only for tiny param sets (`iree_io_file_handle_wrap_host_allocation`);
  wrong for IRPA's actual use case.~~

**What is actually true.** There is **no persistent mmap** of the archive, and there **is**
a copy. IREE `mmap`s the file only *transiently*, to parse the index
(`irpa_parser.c:334`, unmapped again at `:343`); the index then holds only `{file handle,
offset}` per entry. Parameter bytes are read later by **`pread(2)`** on the retained file
descriptor (`hal/utils/fd_file.c:311`) into HAL buffers. So "passing bytes throws away the
mmap" was never the cost — there is no mmap to throw away.

*(All IREE line numbers in this document cite the pinned commit `e4a3b0405d`.)*

- **Right approach, for the corrected reason:** pass the `.irpa` **file path** (a `String`)
  across the boundary. IREE opens and **owns the file descriptor**, and does positional reads
  of only the spans the program actually imports. The caller therefore never has to buffer a
  multi-GB archive into memory just to hand it across. Marshalling is trivial (a path).
- **Still the wrong default:** marshalling the file *bytes* (`byte[]` → JNI → host buffer)
  forces the entire archive into JVM heap and then host memory *before* IREE has read a
  single span — the full cost, up front, unconditionally, to feed a reader that would have
  taken only what it needed. Acceptable only for tiny param sets
  (`iree_io_file_handle_wrap_host_allocation`).

Note the corrected rationale is *stronger*, not weaker: selective `pread` off an IREE-owned
fd beats byte-marshalling regardless of whether any mapping exists, whereas the mmap argument
depended on a claim that turned out to be false.

So the marshalling the question worried about is the **easy** part.

*(If archive read cost ever does matter, `iree_io_file_handle_preload`
(`io/file_handle.h:221`) is the documented lever to the genuine zero-copy
`iree_hal_allocator_import_buffer` path. Not exercised; see the findings doc.)*

### The real work is native runtime wiring
Before appending the bytecode module, `Load` would need to:
1. open the IRPA as a file handle (IREE owns the fd; see the corrected
   marshalling note above — the mapping is transient, index-parse only),
2. parse into `iree_io_parameter_index_t` (`iree_io_parse_file_index`),
3. wrap in a provider with a **scope** (`iree_io_parameter_index_provider_create`),
4. create the `io_parameters` VM module (`iree_io_parameters_module_create`),
5. register that module into the session's VM context so the program's
   `io_parameters.load`/`gather` imports resolve.

~~New RAII handle types with real lifetimes (index/provider/module + the mmap must
outlive the session and every invoke).~~

> **CORRECTED 2026-07-25 — this was backwards.** The spike's Q4/Q5 measured it
> (`docs/2026-07-25-irpa-spike-findings.md`): **IREE retains the entire chain internally, and
> our code holds none of it.** The index retains the file handle
> (`io/parameter_index.c:185`, FILE branch), the provider retains the index
> (`parameter_index_provider.c:64`), the io_parameters module retains each provider
> (`modules/io/parameters/module.c:518`), and the context retains the module
> (`vm/context.c:444`/`597`). `RuntimeState` gained **zero** new members; every parameter
> handle is a local in `Load` that releases as soon as it has been handed one level up.
> Verified by deliberately dropping each reference early and re-running under ASan/LSan, and
> by reading each `_retain()` call site — not inferred.
>
> The retain of the file handle is **load-bearing, not defensive**: parameter data is lazy
> (the index stores only `{handle, offset}`), so the later `pread` runs through the index's
> reference. Dropping ours without that retain would be a use-after-free.
>
> So the five wiring steps above are right, but the ownership sentence that followed them is
> the opposite of the truth. The maintainer rule is one line: **IREE retains every level;
> hold nothing.**

### ~~Big rock: probably forces the low-level VM context API~~ — DISPROVEN 2026-07-25, then CONFIRMED EMPIRICALLY 2026-07-25

> **Status upgrade.** What follows was header-and-symbol verification, and it closed with a
> caveat that a spike still had to confirm HAL-module ordering and scope resolution in
> practice. **That spike has now run and the finding holds with working code** — see
> `docs/2026-07-25-irpa-spike-findings.md` (verdict: **GO**). IRPA loads, resolves its
> imports, and produces oracle-matching output on the high-level `iree_runtime_session`, with
> no `iree_vm_context` migration. The two residual doubts are settled: append order matters
> **only** relative to the bytecode module (the session's internal HAL module was never a
> factor), and scope/provider resolution works — including multiple archives bound to
> multiple scopes, which needed no code beyond the single-archive implementation.
>
> One thing the header check could not have found: the `iree-runtime-dist::runtime` umbrella
> target does **not** contain the io_parameters stack. Eight archive targets must be linked
> directly. See the findings doc; it is a candidate upstream ask for `iree-runtime-dist`.

**The original concern:** the high-level `iree_runtime_session` exposes
`append_bytecode_module_from_memory` but did not *obviously* let us inject a custom native
module (io_parameters) into the context before the bytecode module, which parameter resolution
requires. `iree-run-module` does it via the low-level `iree_vm_context` with an explicit module
list — so IRPA looked like the first concrete reason to migrate the facade off the high-level
API.

**It is not.** Checked against the pinned dist's headers and archives; the high-level session
supports this directly:

- **`iree_runtime_session_append_module(session, iree_vm_module_t* module)`** —
  `include/iree/runtime/session.h:147-148`. Takes an **arbitrary** VM module, not just
  bytecode. Defined in `libiree_runtime_impl.a`.
- **`iree_io_parameters_module_create(instance, provider_count, providers, host_allocator,
  out_module)`** — `include/iree/modules/io/parameters/module.h:21-25`. Produces exactly an
  `iree_vm_module_t*`, which is what `append_module` consumes. Defined in
  `libiree_modules_io_parameters_parameters.a`. Note it accepts a **provider array**, so
  multiple scopes are handled natively — no need to compose several modules.
- **`iree_runtime_instance_vm_instance(instance)`** — `include/iree/runtime/instance.h:105`.
  Supplies the `iree_vm_instance_t*` that `module_create` needs.
- **Ordering is entirely ours.** Both appends happen inside our `Load`, so we simply call
  `append_module(io_parameters)` before `append_bytecode_module_from_memory`.
- **The context is not implicitly frozen.** The only freeze is the explicit
  `iree_vm_context_freeze` (`include/iree/vm/context.h:118`); the runtime session neither
  exposes nor calls it. The repeated "only valid if the context is not yet frozen" notes in
  `session.h` are a caveat about a context *you* froze, not an implicit one-append limit.

**Consequences:**

1. **IRPA can ship on the current high-level facade.** It is a bolt-on to `Load` — the five
   wiring steps above, ~~plus new RAII handle types~~ (**correction 2026-07-25:** plus RAII
   handles that are purely *scope-local* to `Load`; nothing is retained by us — see the
   correction above) — not the facade refactor this note feared.
2. **IRPA is no longer a reason to migrate to the low-level API.** If that migration happens it
   now needs its own justification. This also revises the framing in
   `panama-research-sketch.md`, which treats the low-level migration as forthcoming with IRPA
   as its driver.

~~**Confidence and residual risk.** This is header-and-symbol verification, not a working spike.
It disproves the *API-level* blocker conclusively. Two things a spike still needs to settle:
whether module append order interacts with the HAL module the session registers internally at
create time, and whether provider/scope resolution behaves once wired. Neither is a reason to
plan for the low-level API.~~ — **residual risk retired 2026-07-25**: both questions answered by
the spike (Q2 and Q6/Q7). This is no longer header-and-symbol verification; it is working,
tested code.

### DJL-side design questions
- **Discovery & scopes:** ~~need a convention to find `.irpa`(s) next to the `.vmfb`~~ —
  **settled by the manifest contract (Part 3)**. Programs reference params by *scope* name
  and may use more than one archive; the manifest carries the scope→file map explicitly, so
  there is no filename-guessing convention to design. Still grows load options beyond
  `entryPoint`/`device`.
- **Filesystem vs classpath:** IREE needs a real path it can `open()` and `pread()`
  selectively (see Q8 — no persistent mmap, but no in-memory byte marshalling either). On-disk
  models work directly.
  Jar-bundled models still need extract-to-temp — but **that is now the caller's job, not
  ours** (Part 3): they hand us a manifest path on a real filesystem or they do not get to
  load.
- **Weights stay inside IREE:** they never surface as DJL `NDArray`s — program+params
  run self-contained, consistent with the current plain-Java translator.

---

## Part 2 — vmfb target (CPU) selection

A `.vmfb` from llvm-cpu is compiled for a specific target. `--iree-llvmcpu-target-cpu=host`
bakes the build machine's ISA into the kernels.

### This is a portability problem, not just perf
A host-target vmfb built on a modern (e.g. x86-64-v4) box will **SIGILL / illegal
instruction on an older CPU**. So target selection is first about "does the artifact
run at all on the deployment host," and only second about speed. (And CPU speed is
already not IREE's strength here — see the local-task/PyTorch perf finding — which
lowers the ROI of chasing AVX512, but does NOT lower the portability requirement.)

### glibc-hwcaps-style approach (the direction previously landed on)
Ship a baseline `.vmfb` (generic / low tier, runs everywhere) plus optional per-tier
variants (`x86-64-v2` / `v3` / `v4`); at load time pick the **highest tier the host
CPU supports, else baseline** — exactly glibc's `glibc-hwcaps` ordering.
- Microarch levels: v2 = SSE4.2/POPCNT; v3 = AVX2/BMI/FMA; v4 = AVX-512.
- **Pure JVM-side selection** — no native change. Detect via `/proc/cpuinfo` flags
  (Linux-only, but the engine is Linux-x86_64 only) or a tiny JNI cpuid; pass ONE
  chosen path to the facade.
- Per-file/loader selection (separate vmfbs per tier) sidesteps any uncertainty about
  IREE's in-vmfb multi-variant dispatch — the loader picks the file, like glibc picks
  the `.so`.

### "Let users manage it themselves" is viable IF documented
> **Settled 2026-07-25** — see the tier-selection decision in *Open questions*. Shape: caller
> may name a tier and owns the SIGILL if they are wrong; otherwise detect from `/proc/cpuinfo`
> and fall back to generic; detection is a Linux affordance, not a cross-platform contract; and
> none of it engages unless the manifest stratifies by tier.

Acceptable minimum: document the target-portability footgun and recommend compiling
for a **baseline target** (e.g. `x86-64-v2` or generic) so it runs everywhere. hwcaps
auto-selection is the nicer-UX optimization layer on top of a baseline that works.
Silence is the only bad option (users hit SIGILL in production).

---

## Part 3 — Relationship, and the packaging decision

### Orthogonal, but they meet at packaging
- Program (vmfb) = hardware-specific → needs per-target variants.
- Params (irpa) = hardware-agnostic → **one archive shared across all vmfb tiers**.
- Implementation lands in different layers: hwcaps = JVM-only selection; IRPA =
  native-heavy. **Either can ship first.**

### Groundwork worth doing once
IRPA already forces a multi-file model-artifact convention (`Model.load` must discover
vmfb + associated files). Shape that so hwcaps drops in later:
- make **vmfb resolution a small pluggable selection step**
  (today "find `<name>.vmfb`" → later "find highest-supported-tier, else baseline"), and
- keep **params resolving independently of which vmfb tier won** (shared across tiers).
That independence is the invariant to bake in.

### DECIDED (2026-07-25): the user contract is a manifest JSON document

**The contract:** the caller points the engine at a **manifest JSON document**. Every asset
the manifest names — `.vmfb`(s), `.irpa`(s) — is located at a path **relative to the
manifest document itself**. The manifest *describes*; it never *contains*.

**API constraint the manifest inherits:** `iree_io_parse_file_index` dispatches on the
asset's path **extension alone** (`io/formats/parser_registry.c`) and throws
`UNIMPLEMENTED; unsupported file format ...` for anything other than `.irpa`/`.gguf`/
`.safetensors`. Since the manifest lets a caller name arbitrary relative paths, a
mis-suffixed parameter asset (e.g. `weights.bin`) is a real, reachable failure mode, not a
hypothetical — see the Q9 error table in the findings doc.

**Archives are explicitly unsupported, and we say so.** If a user wants to ship the vmfb,
the manifest, and the IRPA files as a zip/tar/whatever, they are required to unarchive it —
in Java or anything else — **before** calling into the DJL IREE engine. We do not accept an
archive, and we do not extract one on the caller's behalf.

Why disclaim rather than support:

- A zip **conflicts with IRPA's need for a real file descriptor**. ~~The `.irpa` would have to
  be extracted to a temp file before mmap, reintroducing exactly the multi-GB copy IRPA exists
  to avoid.~~ (**Rationale corrected 2026-07-25**, same Q8 finding as Part 1's marshalling
  section: there is no persistent mmap. The accurate statement is that IREE needs a path it
  can `open()` and `pread()` selectively, so a zipped `.irpa` must be materialised in full
  first — reintroducing exactly the whole-archive I/O that path-passing avoids.) Supporting
  archives would mean shipping a slow path that silently defeats the feature's whole purpose.
- Extract-to-temp has policy questions we do not want to own: where does the temp go, who
  cleans it up, what happens on a disk-full or read-only filesystem, how does it interact with
  concurrent loads of the same model. All of that is the caller's environment to reason about.
- The caller almost always already knows the answer — they chose the archive format and know
  where they can write. Pushing it up is the correct layering, not a cop-out.

This subsumes the earlier "prefer a directory convention" leaning: a directory layout is still
what the files sit in (loose and openable by real path, glibc-hwcaps precedent), but **the manifest is the
entry point**, not directory scanning. That removes the filename-guessing conventions Part 1
was worried about — there is nothing to guess when the map is written down.

**Documentation obligation.** The same logic as the SIGILL footgun in Part 2 applies: silence
is the only bad option. "Unarchive before loading" must be stated in the user-facing docs, not
merely implied by a load failure.

**Consequence — the manifest becomes public API.** It is now a versioned compatibility surface
we own across releases, which the loose-files-in-a-directory approach would not have been. It
needs a schema version field from day one; see open questions.

### Layering principle (the reasoning behind several decisions here)

Stated once, because it has now driven more than one call and should be inherited rather than
re-derived each time:

> **Anyone operating ML software at the DJL level is a sophisticated user.** This engine stays
> sharp, predictable, and narrow. Convenience, recovery, and friendliness belong in a Java
> layer *on top of* it — written by whoever needs them.

Decisions this principle produced:

- **No archive support** (above) — the caller unarchives; we do not own temp-file policy.
- **Existence-only asset checking, with `touch` as a deliberate bypass** (see open questions) —
  we do not validate content, and we do not second-guess a user who tells us a file is
  irrelevant.

The corollary matters as much as the rule: when someone wants friendlier behaviour, the answer
is *"yes — build it above us"*, not *"no"*. The engine's job is to be a clean substrate for
that layer, which means predictable failures and no hidden accommodation.

### Recommended sequencing
- Don't fuse into one feature.
- If **portability** is the near-term pain: baseline-target recommendation + documented
  model-dir layout — cheap, standalone, independent of IRPA.
- If **IRPA** is the priority: build it first, but make its artifact-layout/discovery
  decision hwcaps-aware (pluggable vmfb-resolution seam) and steer packaging to a
  directory convention so IREE can `open()` the archive directly, rather than one that
  needs an extract-to-temp step first (see Q8 — no persistent mmap, but IREE still needs a
  real fd).
- The thing worth doing **once**: a single "model artifact layout" spec covering both —
  tiered vmfbs selected by CPU + scope-keyed hardware-agnostic params (shared), loose
  files, **manifest-pointed** (contract decided; see Part 3). That spec also settles the
  IRPA discovery/scope questions.

---

## Part 4 — Spike plan: IRPA on the high-level session — ✅ COMPLETE 2026-07-25

> **The spike ran and the verdict is GO.** Results:
> **`docs/2026-07-25-irpa-spike-findings.md`**. Branch `spike/irpa-high-level-session`;
> ledger at `.superpowers/sdd/2026-07-25-irpa-high-level-session-spike/progress.md`.
>
> All nine questions below were answered. Summary:
> **Q1/Q2/Q3** IRPA wires into `Load` on the high-level session; append order matters and
> fails eagerly and loudly; the HAL module was never a factor.
> **Q4/Q5** IREE retains the whole chain — `RuntimeState` holds nothing (this **contradicts**
> Part 1's original claim; corrected there).
> **Q6** scope is a runtime binding, not an archive property — the manifest's scope→file map
> is the only place it can live.
> **Q7** multiple scopes compose with zero `native/core/` changes.
> **Q8** transient mmap to index, `pread` to read — Part 1's mmap rationale was **wrong**;
> corrected there, conclusion unchanged.
> **Q9** all four failure modes throw catchable exceptions; none crash, validating the
> existence-only check and the `touch` bypass.
>
> **Consequence for this document: the Part 3 manifest contract is justified and stands.**
> The NO-GO branch — revert to a plain directory convention because the manifest is too much
> complexity for `.vmfb` tiering alone — was a live alternative and is now closed.
>
> Everything below is preserved as written, as the plan of record for what was executed.

**Purpose.** Part 1's header-and-symbol check disproved the API-level blocker, but that is not
the same as working code. This spike converts that into evidence, and — more valuably — pins
down the **ownership chain**, which is what the RAII design depends on and what no header
states plainly.

**Scope: native only.** Catch2 + the ASan/LSan leak harness, linking `iree_djl_core` directly,
no JDK. Explicit non-goals: manifest parsing, DJL integration, hwcaps selection, the Panama
facade. Those all sit above this and none of them are blocked by it.

### Prerequisite: the test fixture (probably the biggest chunk of work)

The repo has no model with externalized parameters. Producing one is tractable — the local
`.venv` ships the full toolchain, and `tools/export_add.sh` / `tools/scripts/export_mobilenet.py`
are the precedent to follow:

- **`iree-compile --iree-parameter-export=<scope>=<path>.irpa`** externalizes weights at compile
  time, with `--iree-parameter-export-minimum-size` controlling what gets pulled out. This is
  the fixture generator.
- **`iree-dump-parameters`** inspects the resulting archive — use it to assert the fixture is
  what we think before blaming the runtime.
- **`iree-run-module --parameters=<scope>=<path>.irpa`** is a **known-good oracle**. Any
  behaviour our facade gets wrong can be diffed against a reference implementation that is
  known to work, which converts most "is it us or IREE?" questions into a five-second check.

Prefer a **small** fixture (add-with-a-weight scale) over anything real: the spike is about
wiring, not throughput. Keep MobileNet-sized artifacts out of the repo.

### Questions the spike must answer

**Wiring (does the Part 1 finding hold?)**

1. Does `append_module(io_parameters)` before `append_bytecode_module_from_memory` actually
   resolve the program's `io_parameters.load`/`gather` imports? Import resolution happens when
   the bytecode module is registered against modules already in the context, so this is the
   single load-bearing assertion.
2. Does append **order** matter relative to the HAL module the session registers internally at
   `session_create_with_device`? If the naive order fails, try alternatives before concluding
   anything — a failure here is an ordering bug, not a refutation.
3. Can all of this live inside the existing `IreeRuntime::Load` without restructuring it?

**Ownership — the real payload**

4. What does the retain chain actually guarantee? Specifically: does `append_module` retaining
   the module (per `session.h:143`) mean we can drop our reference immediately? Does the module
   retain the providers, the provider retain the index, the index retain the file handle/mmap?
5. **Therefore: what must `RuntimeState` actually hold, and in what destruction order?** Part 1
   asserts "index/provider/module + the mmap must outlive the session and every invoke" — that
   was inferred, not verified. If the retain chain covers some of it, `RuntimeState` needs fewer
   new members than feared. Method: drop references early on purpose and see what breaks under
   ASan, rather than reasoning from the headers.

**Contract validation**

6. **Scope naming round-trip.** How does a scope string in the manifest map to what
   `iree_io_parameter_index_provider_create` takes, and to what the compiled program references?
   This directly specifies the manifest's scope field, which is decided-in-principle but
   unspecified.
7. **Multiple archives / multiple scopes.** `iree_io_parameters_module_create` takes a provider
   *array*, so this should be native — confirm it, because the manifest promises a scope→file
   *map*, not a single file.
8. **Is the mmap real?** Verify via RSS or `/proc/self/maps` that the archive is mapped rather
   than read into host memory. mmap is the entire justification for the path-based contract; if
   IREE copies anyway, the contract still stands but the rationale needs rewording.

**Error behaviour**

9. What do these look like, and are they diagnosable? (a) scope name mismatch, (b) archive
   missing a parameter the program wants, (c) **truncated or zero-byte archive**. (c) matters
   specifically because the existence-only check plus the `touch` bypass means a zero-byte file
   *will* reach IREE — we decided to defer to IREE's error there, so this confirms that error is
   tolerable rather than a segfault.

### Exit criteria

Q1 + Q4/Q5 answered is the bar. Q1 confirms or refutes "IRPA is a bolt-on to `Load`"; Q4/Q5
produce the handle design. Everything else is valuable but not gating.

**If Q1 refutes the Part 1 finding**, the low-level migration returns to the table and this
document's Part 1 needs reverting to its original conclusion.

### Follow-on action, required either way

**Update `panama-research-sketch.md` regardless of spike outcome.** That document is framed
around the low-level migration being forthcoming with IRPA as its driver — which shapes its
sequencing recommendation and its "the churn lands on load" argument. Part 1 already undercuts
that framing; the spike either confirms the undercut or restores the original. The facade
argument itself does **not** depend on the migration and survives either way, but the framing
around it is now wrong as written and must not be left to rot.

---

## Open questions for the future spec

Resolved by the 2026-07-25 contract decision (Part 3), kept for the record:
- ~~vmfb-resolution seam: directory tiers vs JSON manifest for tier→file?~~ → **manifest.**
  The pluggable-selection seam survives, but its input is the manifest's variant list rather
  than a directory scan.
- ~~IRPA scope convention: single `<name>.irpa`, or scope→file map / manifest?~~ →
  **manifest, explicit scope→file map.** No filename convention to design.
- ~~jar-bundled models: extract-to-temp policy for mmap?~~ → **not ours.** Caller unarchives
  and hands us a real filesystem path.

Still open:
- **Manifest schema version — DECIDED.**

  **The constraint that drives everything else:** models are long-lived artifacts, engines get
  upgraded. The dominant skew is therefore *new engine reading an old manifest* — a manifest
  written today must still load years from now. So the design goal is not "handle version
  mismatch gracefully", it is **"never need to bump major in the first place"**, and the rules
  below exist to make that achievable.

  1. **`"schemaVersion": 1` — a single integer, not semver.** Version strings invite `"1"` vs
     `"1.0"` vs `"1.0.0"` skew plus comparison logic, for no benefit here. No minor component
     either: under rule 3, additive changes bump nothing at all, so a minor would never move.
  2. **Required, with no default.** A missing `schemaVersion` is an error — *not* "assume 1".
     A missing field is more likely a malformed document or the wrong file entirely than a
     genuine v1 manifest, and once a default is assumed those two cases can never be told
     apart again. v1 is the only release where requiring it is free.
  3. **Unknown *fields* are ignored silently.** This is what makes additive evolution free:
     checksums, new tier metadata, provenance, whatever — added without a version bump.
  4. **Unknown *values* in fields we act on are an error.** The subtle counterpart to rule 3,
     and the one most likely to be got wrong. Ignoring an unknown *key* is safe. Ignoring an
     unknown *enum value in a key we consume* is not: a future
     `"compression": "zstd"` on an asset, silently ignored, does not fail — it produces wrong
     results. Unknown key → skip; unrecognised value in a consumed field → refuse.
  5. **A `requires` must-understand block.** For additive fields that genuinely *cannot* be
     safely ignored, everything under `"requires": { ... }` must be understood or the load
     fails. This inverts rule 3's default for one designated subtree, and it is the mechanism
     that makes "never bump major" achievable: an old engine fails loudly only on the
     manifests that actually use the new capability, while every pre-existing manifest keeps
     loading untouched. Precedent: HTTP `must-understand`, COSE critical headers, TLS
     extensions.

  **Rejection behaviour.** `schemaVersion` greater than the engine supports → refuse, naming
  both numbers and the engine version: *"model manifest requires schema version 2; this engine
  (x.y.z) supports up to 1 — upgrade the engine."* Same for an unsatisfied `requires` entry,
  naming the specific unknown key. The failure must point at the fix.

  **Implementation note:** validate `schemaVersion` *before* parsing anything else in the
  document, so a future manifest reports a clean version error rather than a confusing
  unknown-field or type error from halfway down the file.

  **Bump policy.** Major bumps only for changing the meaning of an existing field or removing
  one. Anything additive uses a plain optional field (rule 3) or `requires` (rule 5). Treat a
  major bump as a last resort, because it strands every manifest already in the wild.

  **Scope:** `schemaVersion` versions *the JSON document only*. The `.vmfb` carries its own
  IREE bytecode version and the `.irpa` its own format version; do not conflate them. Whether
  the manifest should also declare a minimum engine or IREE version is deliberately **out of
  scope for v1** — that is exactly what `requires` is for if it ever becomes necessary.
- **Relative-path resolution — decided in shape, open in detail.** Paths resolve against the
  manifest's own directory. Escaping that directory (absolute paths, `..` traversal, symlinks
  pointing outward) is **not rejected outright — it is classed as *unsafe* and requires the
  caller to opt in via an explicit flag.** Default is contained; the escape hatch exists but
  has to be asked for by name.

  Rationale: outright rejection would break legitimate layouts — a shared read-only weights
  directory referenced by several models, or an ops-managed absolute mount — and those are
  reasonable things to want. But they should be a deliberate, visible choice at the call site
  rather than something a downloaded manifest can arrange silently.

  Details still to settle:
  - **The flag must live on the caller's load options, never in the manifest.** A manifest that
    could self-authorize escape defeats the entire control, since the manifest is precisely the
    artifact that may be untrusted.
  - **Name it so it reads as unsafe at the call site** (`allowUnsafePaths` /
    `allowPathsOutsideModelDirectory` or similar) — the word is the warning.
  - **Containment must be checked on the resolved real path, not the string.** A symlink escape
    is invisible in the manifest text; string-level `..` checking would miss it.
  - **The refusal message must name the flag**, so the legitimate case is a one-line fix rather
    than a mystery.

  **Decided: the flag is all-or-nothing for the manifest, not per-asset.** Per-asset opt-in is
  too granular for the value it adds. If someone articulates a concrete use case later, they
  can bring the implementation PR — widening the scope later is compatible; narrowing it is not.
- **Manifest path vs directory at the DJL entry point — DECIDED.** Accept both: a direct path
  to the manifest, *or* a directory containing the well-known filename
  **`djl-iree-model.json`**. The directory form is sugar for the manifest form, matching DJL's
  conventionally directory-oriented `Model.load`.

  **The name is deliberately namespaced to this engine.** Not `iree-model.json`: the `.vmfb`
  and `.irpa` assets are runtime-agnostic and someone may well drive them from a different
  IREE host — plain `iree-run-module`, a Python harness, another binding. What is specific to
  us is *this layout and this manifest*, so that is what the filename claims. A sibling
  manifest for another runtime can describe the same asset files without collision, which is
  only possible because the manifest **describes rather than contains** (Part 3).

  Secondary benefit, and the reason a well-known name beats a free-for-all: support personnel
  can locate every affected model on a box with a plain
  `find /path -type f -name djl-iree-model.json`. A convention only pays off if it is
  greppable.

  **An explicit file path always wins, and it may name any file.** `djl-iree-model.json` is a
  *discovery convention for the directory form only* — it is **not** a validation rule. If the
  caller hands us `/models/resnet/prod-v3.json`, we load that; we must not refuse a manifest
  for being named something else. A naive implementation could easily get this backwards by
  enforcing the well-known name everywhere, so it is worth stating outright.

  This falls out usefully: several manifests can coexist in one asset directory — staging vs
  prod, different tier subsets, an A/B pair — all describing the same loose `.vmfb`/`.irpa`
  files, with `djl-iree-model.json` (if present) acting as the default that the directory form
  resolves to.

  If the directory form is used and `djl-iree-model.json` is absent, that is a distinct,
  well-worded error — same family as the "forgot to unarchive" case below.
- **Missing asset behaviour — DECIDED: existence check only, and an empty file is a valid
  bypass.**

  A manifest naming a file that is not there is an **error** — almost certainly the "user
  forgot to unarchive" case, so it gets a distinctly-worded message, since it is the
  predictable consequence of the archive disclaimer.

  But the check goes no further than `does this path exist?`. Specifically:

  - **No checksums.** An earlier note floated optional manifest checksums to catch a
    *partially* unarchived tree. **Dropped** — it moves us into content validation, which is
    not a job this layer is taking on.
  - **No size or content check, and this is load-bearing.** A zero-byte file **passes**. That
    is deliberate: the user can `touch` a placeholder for an asset they know will never be
    loaded — e.g. the generic-CPU `.vmfb` tier on a host known to be hwcap v4. The asset is
    never opened, so its emptiness never matters.

    **Do not "improve" this by adding a non-zero-size check.** It would break the documented
    bypass. If a file *is* genuinely truncated and *is* genuinely loaded, IREE's own file-open
    or IRPA parse failure is the error — deferring to it is the intended behaviour, not a gap.
  - **All listed assets are checked eagerly**, not just the tier that selection ends up
    choosing. Eager is cheap and predictable, gives one clear error for the whole artifact at
    load time, and the `touch` bypass covers the "I know I'll never use that tier" case
    without needing selection-aware validation logic.

  **Rationale — the operating assumption.** Anyone running ML software at the DJL level is a
  sophisticated user. The engine stays sharp and predictable rather than accommodating; see
  the *Layering principle* in Part 3.
- ~~Facade: confirm high-level session can't inject io_parameters → low-level context
  migration required? Scope that refactor.~~ → **RESOLVED 2026-07-25: it can.**
  `iree_runtime_session_append_module` takes an arbitrary `iree_vm_module_t*`, which is
  precisely what `iree_io_parameters_module_create` produces. No low-level migration required;
  see Part 1. ~~A spike should still confirm HAL-module ordering and scope resolution in
  practice.~~ → **It did, 2026-07-25: both confirmed.** See
  `docs/2026-07-25-irpa-spike-findings.md`.
- **Facade: `lastImportOutcomes` — DECIDED: replace the separate accessor with an optional
  caller-supplied output buffer on `Invoke`.**

  **What it is and why it must survive.** `ImportOrCopy` attempts a zero-copy
  `iree_hal_allocator_import_buffer` and **silently** falls back to
  `iree_hal_buffer_view_allocate_buffer_copy` when the allocator's memory-type / usage /
  alignment preconditions are unmet. Without this API the caller cannot tell that a full-size
  copy is happening on every invoke. `core/iree_runtime.h:53-55` is explicit that it is
  deliberately API and not a log line, so tests can assert it — and it is useful beyond tests,
  since staging is a real per-invoke performance cliff. **Any fix must keep it observable and
  assertable.**

  **The problem: it is `errno`.** The value describes one specific `Invoke`, but lives on the
  runtime (`RuntimeState::lastImportOutcomes`, `core/iree_runtime.cpp:20`) and is fetched by a
  separate later call. Five consequences:

  1. **The returned span can dangle.** `lastImportOutcomes()` returns a `std::span` over the
     vector; the next `Invoke` does `.assign(inputs.size(), ...)`
     (`core/iree_runtime.cpp:157`). A larger input count reallocates → previously returned
     spans dangle. A smaller one silently overwrites the contents instead. Either way a
     retained span is use-after-free or quietly stale. Hazardous *today*; never hit only
     because `jni/iree_djl_jni.cpp:214-231` copies to a `jintArray` immediately. The safety is
     a caller convention, not a property of the API.
  2. **Two-call sequence at the Java layer.** `invoke(...)` then `lastImportOutcomes(handle)`
     are separate transitions. A shared `Predictor`, retry wrapper, or DJL interceptor
     touching the handle in between corrupts the answer — *silently*, yielding plausible
     outcomes from the wrong call rather than an error.
  3. **Concurrent `Invoke` on one runtime tears it.** Two threads both write
     `state_->lastImportOutcomes`. Survivable only under a one-runtime-per-thread convention
     that — unlike the ExecuTorch engine — is not currently written down in this repo's
     headers.
  4. **Undefined failure semantics.** If `Invoke` throws partway through the input loop, the
     vector holds an all-`kStaged` assignment partially overwritten with real values: a
     half-truth that looks like data.
  5. **Ambiguous empty case.** Before any `Invoke` the vector is empty, so Java receives an
     empty `int[]` — indistinguishable from "invoked with zero inputs".

  **Why the facade work forces it now.** With one front-end that happens to consume the value
  immediately, none of the above bites. Put JNI and Panama on a shared C boundary (see
  `panama-research-sketch.md`) and the ordering rule becomes something **both** front-ends must
  document and **neither** can enforce. A per-call fact reachable only via a second,
  unsynchronised call is the wrong shape to freeze into an ABI — cheap to fix now, expensive
  once Java is compiled against it.

  **The decision.** `Invoke` takes an **optional, caller-supplied output buffer**:

  ```
  Invoke(inputs, /* nullable */ ImportOutcome* out_outcomes)
  ```

  - The element count is always `inputs.size()`, which the caller already knows — sizing is
    trivial and needs no query call.
  - **Free when unwanted:** pass null and nothing is written or allocated.
  - **Per-call correct by construction:** the outcomes cannot outlive, or be confused with,
    the call that produced them. Issues 1-5 all dissolve.
  - **Natural at a C ABI, and symmetric across front-ends:** JNI allocates an `int[]` only if
    the Java layer asked for one; Panama passes an `Arena` segment. Tests pass a stack buffer.
  - **Remove `lastImportOutcomes()` and `RuntimeState::lastImportOutcomes` entirely.** Leaving
    the accessor in place as a deprecated alias would preserve every problem above; the whole
    point is that the state stops existing.

  **Rejected alternatives.**
  - *Return the outcomes from `Invoke` as part of the result.* Also correct — kills all five
    issues — but forces every caller to carry the data whether or not they want it, and grows
    the C-boundary result struct. A reasonable second choice if the diagnostic should always
    be present rather than opt-in.
  - *Keep the separate accessor but return `std::vector` by value.* Rejected: fixes only issue
    1, the least dangerous of the five, while preserving the shape that causes the other four.

  **Follow-on:** while touching this, write down the runtime's threading contract in
  `core/iree_runtime.h` (issue 3). It is currently assumed and unstated.
- **Baseline / hwcap tier selection — DECIDED.**

  **Precondition: this is all academic unless the manifest stratifies by tier.** A manifest
  naming a single `.vmfb` takes a trivial path — no detection, no selection, nothing below
  engages. Tier machinery exists only when the manifest offers tiers.

  **1. The caller may name a tier explicitly, and owns the consequences.** Consistent with the
  *Layering principle*: if they name `v4` and the host is v2, it SIGILLs and that is on them.
  Two refinements that cost almost nothing:

  - **Validate the named tier against the *manifest*, not the CPU.** Naming a tier the manifest
    does not offer is *our* clean error; naming a tier the *CPU* does not support is their
    SIGILL. Cheap to distinguish, and it catches the common typo without pretending to catch
    the hardware mismatch.
  - **The tier option lives on the caller's load options, never in the manifest** — same
    reasoning as the unsafe-paths flag. A model artifact should not be able to select its own
    execution tier.

  **2. Log the decision immediately before loading — and flush.** This is the "minimally
  friendly" concession, and the flush is the whole point:

  - **SIGILL is not catchable in any useful sense; it kills the JVM.** There is no exception, no
    `finally`, no post-mortem from inside the process. A buffered log line that has not reached
    the appender when the illegal instruction executes tells the operator *nothing*.
  - **Do not install a signal handler to "recover".** HotSpot already uses signals for its own
    purposes (implicit null checks, safepoint polling); competing for them to prettify a
    user-caused crash is a bad trade.
  - So the log line must be emitted and flushed *before* the load attempt, and must carry:
    **which tier was chosen, how it was chosen** (explicit / detected / fallback), **and the
    resolved file path**. That triple is exactly what a post-mortem needs.

  **3. No explicit tier → auto-detect, else generic.** The default path is where being friendly
  is worth it. On Linux, parse `/proc/cpuinfo` flags and pick the **highest tier the host
  supports that the manifest actually offers** (glibc-hwcaps ordering; v2 = SSE4.2/POPCNT,
  v3 = AVX2/BMI/FMA, v4 = AVX-512). If detection fails or is unavailable, fall back to
  **generic**. Log which of the two occurred — the outcome is the same, the diagnosis is not.

  **4. Automatic detection is explicitly NOT a cross-platform guarantee.** It is a Linux
  affordance. When the library gains Windows support, a small effort to replicate is worthwhile,
  but we **disclaim automatic level determination as a portable capability**. On any platform
  without detection, the behaviour is well-defined and boring: generic, unless the caller names
  a tier. Detection is an optimization over a default that already works — never a dependency.

  Small detail left for the spec: if a manifest offers tiers but *no* generic/baseline entry and
  detection is unavailable, we have nothing safe to fall back to. Preference is to error and
  name the tier option rather than guess — guessing risks SIGILL on the *default* path, which
  is the one place that must stay safe. Arguably the manifest should be required to carry a
  baseline whenever it carries tiers.
