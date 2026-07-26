# IRPA high-level session spike: findings

Findings from the `spike/irpa-high-level-session` branch. Started in Task 5; completed and
restructured in Task 8. Plan:
`docs/superpowers/plans/2026-07-25-irpa-high-level-session-spike.md`. Scoping context:
`docs/2026-07-22-irpa-and-target-selection-scoping-notes.md`, Parts 1 and 4.

## Verdict: GO

**IRPA works on the existing high-level `iree_runtime_session` facade, the ownership rule
states in one sentence — *IREE retains every level; our code holds none of it* — and a
500-cycle parameter-bound leak-harness run is ASan/LeakSanitizer-clean; build it.**

Measured against the spike's own bar:

| Bar | Result |
|---|---|
| GO if wiring works on the high-level API | Yes — one extra `iree_runtime_session_append_module` call inside `Load`. No `iree_vm_context` migration. |
| GO if the ownership chain is expressible with rules a maintainer can state in a sentence | Yes — and the sentence is shorter than expected: hold nothing. |
| GO if the leak harness is clean across repeated cycles | Yes — 500 load/invoke/close cycles with a FILE-backed archive bound, zero LeakSanitizer reports. |
| NO-GO if it requires the low-level `iree_vm_context` | Not triggered. |
| NO-GO if ownership rules need conditionals | Not triggered. |
| NO-GO if the harness leaks non-trivially | Not triggered. |

### Consequence: the manifest contract is justified

The manifest contract (scoping notes Part 3 — caller points the engine at a JSON manifest
that names a `.vmfb` set and a scope→file map of `.irpa` archives, all resolved relative to
the manifest document) was carrying the cost of IRPA on its own. This spike pays for it:

- The scope→file **map** is real and load-bearing, not speculative generality. Scope is a
  runtime binding chosen by the caller (Q6), IREE's `io_parameters` module takes a provider
  *array* (Q7), and a manifest is the natural place to write that map down.
- The manifest's **existence-only asset check, with `touch` as a documented bypass**, is safe:
  every degenerate archive we could reach through that bypass produces a catchable exception,
  not a crash (Q9).
- Passing archives **by path** is the right marshalling, though not for the reason originally
  recorded (Q8; see the correction note below).

**The alternative was live and is now closed.** Had Q1 or Q4/Q5 gone the other way, the
recommendation would have been to abandon the manifest as too much complexity for `.vmfb`
tiering alone and revert to a plain directory convention (glibc-hwcaps style: tier
subdirectories, loose files), which was the pre-manifest leaning. That recommendation is not
being made. The manifest stands.

Next step per the plan: settle the load-options ABI shape in `docs/panama-research-sketch.md`
against these ownership findings.

---

## Q1/Q2/Q3 — wiring

**Q1: does `append_module(io_parameters)` before `append_bytecode_module_from_memory` resolve
the program's `io_parameters.load`/`gather` imports? Yes.** This was the spike gate and it
passed on the first attempt. `iree_runtime_session_append_module` takes an arbitrary
`iree_vm_module_t*`, which is exactly what `iree_io_parameters_module_create` produces; the
compiled program's `#stream.parameter.named<"model"::"weight">` import resolves against it.
The golden-vector end-to-end forward pass matches the `iree-run-module` oracle byte-for-byte
(`4xf32=1,2,3,4` → `2 4 6 8`).

**Q2: does append order matter? Yes — and it fails eagerly, not lazily.** Moving the
`io_parameters` append to run *after* the bytecode-module append made 3 of the 4
`iree_params_test` cases fail immediately at the bytecode-append call, with:

```
iree_runtime_session_append_bytecode_module_from_memory(...): iree/runtime/src/iree/vm/context.c:205:
NOT_FOUND; required module 'io_parameters' not registered on the context; resolving module 'module' imports
```

Import resolution runs synchronously when a module is registered against whatever is already
in the context — there is no deferred resolution path that would tolerate registering
`io_parameters` afterwards. The existing ordering is required, not stylistic. The failure is
loud, immediate, and names the missing module, which is the good case: a production
implementation cannot get this wrong silently.

**Order relative to the internally-registered HAL module was never a factor.** The session
registers its HAL module during `iree_runtime_session_create_with_device`; we neither touch
nor order against it, and no HAL-related import failure was observed at any point, with or
without the params module present. Ordering matters only relative to the bytecode module.

**Q3: does it fit inside `Load` without restructuring? Yes.** The change is a 4th `Load`
parameter (`std::span<const ParameterScope>`, where `ParameterScope{scope, path}`) and one new
code path guarded by `if (!parameters.empty())`: open file handle → create and parse the
parameter index → wrap in an index provider bound to `param.scope` → collect providers → one
`iree_io_parameters_module_create` for all of them → `append_module`. The pre-existing
3-argument `Load` became a one-line forwarder passing an empty span; `iree_runtime_test`
(11 cases, 36 assertions) and the JNI shim both needed zero edits.

### Link-graph finding — candidate upstream ask for `iree-runtime-dist`

The `iree-runtime-dist::runtime` umbrella target (`libiree_runtime_unified.a`) **does not
carry the io_parameters stack.** `nm` shows it exports only `iree_io_file_handle_open` /
`_open_fd` from the `iree_io_` surface — not `iree_io_parameters_module_create`,
`iree_io_parse_file_index`, `iree_io_parameter_index_create`, or
`iree_io_parameter_index_provider_create`. Eight archive targets had to be linked directly:

```
iree_io_formats_parser_registry
iree_io_formats_irpa_irpa
iree_io_formats_gguf_gguf
iree_io_formats_safetensors_safetensors
iree_io_parameter_index
iree_io_parameter_provider
iree_io_parameter_index_provider
iree_modules_io_parameters_parameters
```

The gguf and safetensors parsers are unused by us but cannot be omitted:
`iree_io_parse_file_index`'s dispatcher has **hard, non-weak** undefined references to all
three format parsers — the registry is compile-time linked, not a runtime plugin registry.

All eight are plain (non-namespaced) targets already defined by `IREETargets-Runtime.cmake`,
loaded transitively via the existing `find_package`, so no new `find_package`/`FetchContent`
was needed. **This is a candidate upstream ask for the `iree-runtime-dist` repo**: the
umbrella target's name ("unified") is misleading for parameter work, and every downstream
consumer that wants IRPA will rediscover this list by undefined-symbol archaeology. Either
fold the io_parameters stack into the umbrella or document the eight targets in the dist.

---

## Q4/Q5 — ownership

Q4/Q5 asked what `RuntimeState` must hold to keep the IRPA parameter chain (file handle →
index → provider → module) alive, and in what destruction order.

**Answer: none of it.** Every level of the chain retains the one below it internally inside
IREE, via its own `_retain()` call at construction time. `RuntimeState` needs only `session`;
everything parameter-related is a local in `IreeRuntime::Load`, scoped to release right after
each handle is handed to the level above.

This is the section the production implementation will actually be read for, so the rule is
worth stating alone: **IREE retains every level of the parameter chain; the caller holds
none of it.** No conditionals, no ordering constraints among the parameter handles, no new
members on `RuntimeState`.

| Handle type | Retained by whom | Must we hold it? | Evidence |
|---|---|---|---|
| File handle (`iree_io_file_handle_t`) — **load-bearing, verified empirically by Task 7** | The parameter index — `iree_io_parameter_index_add` calls `iree_io_file_handle_retain` for every FILE-backed entry (`io/parameter_index.c:185`) | No | See the FILE-backed differential below. |
| Parameter index (`iree_io_parameter_index_t`) | The index provider — `iree_io_parameter_index_provider_create` calls `iree_io_parameter_index_retain` (`io/parameter_index_provider.c:62`) | No | Dropped deliberately; PASS, clean under ASan. |
| Parameter provider (`iree_io_parameter_provider_t`) | The io_parameters module — `iree_io_parameters_module_create` calls `iree_io_parameter_provider_retain` per provider (`modules/io/parameters/module.c:518`) | No | Dropped deliberately; PASS, clean under ASan. |
| io_parameters module (`iree_vm_module_t`) | The session/context — appending a module calls `iree_vm_module_retain` (`vm/context.c:444`/`597`), matching the documented claim at `session.h:143` | No | Dropped deliberately; PASS, clean under ASan. |

**Method.** For each level, the `RuntimeState`-owned handle was replaced with a local RAII
object releasing at the end of the scope in which it is last needed (e.g. the file handle
releases right after `iree_io_parse_file_index` returns), then `native/asan/iree_params_test`
was rebuilt and run. Each successful drop was kept before probing the next level, so the
results compound: the final `RuntimeState` has all four members removed simultaneously, and
both `iree_params_test` (9 cases, 36 assertions) and `iree_runtime_test` (11 cases, 36
assertions) pass with zero LeakSanitizer reports. Every `_retain()` call site above was then
read directly in the IREE source (checkout at `~/workspace/iree`), so the "no leaks" result is
understood mechanistically rather than merely observed.

### The FILE-backed differential — why the file-handle row is a positive result

Task 5's file-handle probe was **inert** and was marked provisional for that reason: both
fixtures it bound (`scale_weights.irpa`, `scale2_bias.irpa`) are *splat* archives with no
on-disk storage (see `tools/export_scale.sh`), so `iree_io_parameter_index_add` took the SPLAT
branch and the FILE-branch retain at `parameter_index.c:185` never executed. A clean ASan run
proved nothing there.

Task 7 closed the gap: it added a FILE-backed fixture
(`src/test/resources/models/scale_weights_zero.irpa`, a zeroed real-storage archive) and a
golden-vector case that loads it, then re-applied the drop — releasing the local file handle
immediately after `iree_io_parse_file_index` returns.

The differential is sensitive because **parameter data is lazy**.
`iree_io_parse_irpa_v0_data_entry` stores only `{handle, offset}` per entry
(`irpa_parser.c:139-151`, inside the function starting at `:124`); no bytes are resident when
parsing returns. They are fetched later, during `io_parameters.load`, when
`iree_io_parameter_index_provider_resolve` dereferences `entry->storage.file.handle`
(`parameter_index_provider.c:147`) and imports it as a HAL file, which for an FD-backed handle
retains it again (`hal/utils/fd_file.c:247`) and `pread`s the span (`fd_file.c:311`). Dropping
our reference right after parse therefore leaves the index
holding the *only* reference across a subsequent real read. Had the index's retain not fired,
that `pread` would be operating on a freed `iree_io_file_handle_t` and a closed fd — a genuine
use-after-free ASan would have caught.

Result: zero faults under ASan across `iree_params_test` (9 cases, 36 assertions) and a
500-cycle load/invoke/close run of `iree_leak_harness` with the archive bound. The golden output
(`0,0,0,0` — input × the zeroed archive) is checked in both, but zero is the weakest possible
golden value: a read that silently returned a zeroed buffer (e.g. from freed/unmapped memory the
allocator happened to zero-fill) is indistinguishable from a correct read of this fixture. The
golden check is therefore not the load-bearing evidence here. What makes this a positive
confirmation that the retain is **load-bearing, not defensive** is the *absence of the
use-after-free ASan would have reported* had the index's retain not fired and the subsequent
`pread` operated on a freed `iree_io_file_handle_t` and closed fd — a clean ASan run under a
scenario constructed specifically to trigger that fault is the evidence, not the output value.

---

## Q6 — scope naming

**Confirmed: a scope is a runtime binding, not a property of the archive.** The `.irpa` file
carries no scope name. At creation time `iree-create-parameters` reports
`Parameter scope <global>` for the fixtures, because nothing bound a scope. The name any tool
displays comes entirely from the `--parameters=<scope>=<path>` binding supplied at load time:
dumping the *same* file as `iree-dump-parameters --parameters=model=<path>` reports
``Parameter scope `model` ``. Same file, same tool, different scope name, differing only by the
invocation flag. At the API level this is the `scope` string handed to
`iree_io_parameter_index_provider_create`, matched against the scope the compiled program
references in `#stream.parameter.named<"model"::"weight">`.

**What this means for the manifest.** The manifest's scope→file map is not redundant metadata
that could be recovered from the archives — it is the *only* place the binding can live, short
of a filename convention. It is validated by this finding rather than merely permitted by it.
Two corollaries the manifest spec should inherit:

- One archive can legitimately be bound under different scopes by different manifests. Nothing
  in the file objects.
- A scope typo cannot be caught by inspecting the archive; it surfaces only at load, as the
  Q9 "wrong scope name" error. That error is good (it names the scope), but manifest-side
  validation has nothing to check against, so do not plan for one.

---

## Q7 — multiple scopes

**Composed with zero `native/core/` changes.** Task 3's `Load` already loops over every
`ParameterScope` in the supplied span and builds one `iree_io_parameters_module_create` call
from the resulting provider array, so a second, differently-scoped archive needed no new code
path at all — the provider array in IREE's own API did the work.

Verified end to end with a two-scope fixture (`tools/scale2.mlir`: `util.global`s in scopes
`model::weight` and `bias::offset`), against the oracle first:

```
$ iree-run-module --module=scale2.vmfb \
    --parameters=model=scale_weights.irpa \
    --parameters=bias=scale2_bias.irpa \
    --function=scale2 --input="4xf32=1,2,3,4"
result[0]: hal.buffer_view
4xf32=12 14 16 18
```

and then through `IreeRuntime::Load` in the new `"two archives bound to two scopes"` case.
`input * 2 (model::weight) + 10 (bias::offset)` both ways.

**For the manifest:** the scope→file map is a thin pass-through to the existing
`ParameterScope` span. No merging, ordering, or de-duplication logic is required in
`native/core/`.

---

## Q8 — mmap: transiently mapped to index, `pread` to read

Q8 asked whether IREE genuinely `mmap`s an archive or reads it into host memory. It matters
because "IREE mmaps the file" was the stated justification for the path-based contract.

**Answer: both, at different times, for different purposes — and the original rationale was
wrong.**

- IREE `mmap`s the archive **transiently, only to parse the index**.
  `iree_io_parse_irpa_index` calls `iree_io_file_map_view(..., IREE_HOST_SIZE_MAX, ...)`
  (`irpa_parser.c:334`), which reaches a real `mmap(..., MAP_SHARED, fd, offset)`
  (`io/file_handle.c:708`). That mapping is unmapped as soon as the index is built
  (`irpa_parser.c:342`). This is also why the Q9 zero-byte error says "failed to **map** file
  handle range" — a real `mmap(2)` failing, not a metaphor.
- The index stores only `{file handle, offset}` per entry (`irpa_parser.c:139-151`) — no bytes.
- Parameter bytes are fetched later, span by span, by **`pread(2)`** on the retained fd into
  HAL buffers (`hal/utils/fd_file.c:311`).

So there is **no persistent mapping**, and there **is** a copy into the target buffer on each
read.

**When the read happens, for this fixture: once, during `Load`.** `tools/scale.mlir` binds the
parameter to a `util.global` (`#stream.parameter.named<"model"::"weight">`), so
`io_parameters.load` executes in the module initializer, which runs when the bytecode module is
registered (`iree_runtime_session_append_bytecode_module_from_memory`,
`native/core/iree_runtime.cpp:187`) — inside `Load`, not `Invoke`. This is corroborated by every
Q9 error message below carrying `...while invoking native function io_parameters.load` from a
failing `Load`. **This is a property of this fixture's binding shape, not a universal one:** a
program that loads parameters from inside a function body rather than a `util.global`
initializer would read at invoke time instead. Do not generalize the cost model without
checking the binding.

### `/proc` evidence, and its honest limits

`iree_leak_harness` was extended to accept an optional 4th argv (`scope=path`) threaded into
the 4-argument `Load`, and run against `scale.vmfb` bound to the FILE-backed
`scale_weights_zero.irpa` (the splat fixtures have no on-disk storage and cannot answer this at
all), with 2,000,000 requested iterations so the process would still be actively looping
`Load`/`Invoke`/close a second later:

```
$ ./native/build/iree_leak_harness src/test/resources/models/scale.vmfb 2000000 local-sync \
    "model=$(pwd)/src/test/resources/models/scale_weights_zero.irpa" &
HARNESS_PID=$!
sleep 1
ps -p $HARNESS_PID -o pid,stat,etimes,cmd
grep -c "scale_weights_zero.irpa" /proc/$HARNESS_PID/maps || echo "NOT MAPPED (grep found 0 matches)"
wc -l /proc/$HARNESS_PID/maps
```

```
    PID STAT ELAPSED CMD
3095117 R          1 ./native/build/iree_leak_harness src/test/resources/models/scale.vmfb 2000000 local-sync model=/home/corey/workspace/djl-iree-engine/src/test/resources/models/scale_weights_zero.irpa

0
NOT MAPPED (grep found 0 matches)

40 /proc/3095117/maps
```

The full 40-line map for the confirmed-alive process (`STAT=R`, `ETIMES=1`) is: the harness
binary (5 segments), libc/libstdc++/libgcc_s/libm/ld-linux (5 segments each), `[heap]`,
`[stack]`, `[vdso]`, `[vvar]`, `[vvar_vclock]`, `[vsyscall]`. No mapping references the archive
path.

**Method limit, stated plainly:** this rules out a *persistent* mapping and nothing more. The
parse-window `mmap` is a microsecond-scale slice of each `Load` cycle (map, index, unmap, all
before `Load` returns), so a 1-second sample landing between cycles was the expected outcome
whichever way the mechanism worked. The source reading above, not the `/proc` sample, is what
establishes the transient mapping.

### Correction to the manifest rationale

The path-based contract **stands**, but its recorded rationale was wrong and has been corrected
in the scoping notes.

- **Wrong (as previously written):** *"IRPA is built to be mmap'd from disk (aligned data
  section, zero-copy); passing bytes throws away the mmap."* There is no persistent mapping and
  there is a copy.
- **Right:** *IREE owns the file descriptor and does positional reads (`pread`) of just the
  spans a program actually imports, so the caller never has to buffer a multi-GB archive into
  memory to hand it across the boundary.* Mapping is used only internally and briefly, to build
  the index.

The conclusion is unchanged and if anything better supported: the reason to pass a path is that
IREE reads *selectively from the fd it owns*, which is a stronger argument against
byte-marshalling than a mmap claim that turns out not to hold.

### The lever, if archive read cost ever matters

`iree_io_file_handle_preload` (`io/file_handle.h:217`) yields a `HOST_ALLOCATION`-backed handle
that routes through `iree_hal_memory_file_wrap` and unlocks the genuine zero-copy
`iree_hal_allocator_import_buffer` path (`parameter_index_provider.c:739-762`). Not exercised
here. Noted for whoever picks this up if the `pread` cost at model-load time becomes a concern
— a one-off per session `Load` for `util.global`-bound parameters like this fixture's, per the
timing note above.

---

## Q9 — error behaviour

Q9 asked whether a zero-byte `.irpa` — guaranteed reachable by the manifest's existence-only
check plus its documented `touch` bypass — fails as a diagnosable error or crashes.

**Confirmed: none of the four failure modes probed crash.** All four throw a catchable
`std::runtime_error` from `IreeRuntime::Load`, in both a plain host build and under
ASan/LeakSanitizer, with zero leaked `iree_status_t` objects.

| Failure mode | Exact message | Operator-diagnosable from the string alone? |
|---|---|---|
| Missing file | `...NOT_FOUND; failed to open file '/nonexistent/nope.irpa'` | **Yes** — names the missing path directly. |
| Zero-byte archive (the `touch` bypass case) | `...INVALID_ARGUMENT; failed to map file handle range 0-18446744073709551615 (18446744073709551615 bytes) from file of 0 total bytes` | **Yes, with effort** — "file of 0 total bytes" is the load-bearing phrase, but it is preceded by IREE's internal `SIZE_MAX` "whole file" sentinel leaking into the message, which reads as corruption rather than emptiness to an unfamiliar operator. The worst of the four. |
| Truncated archive (8-byte garbage header) | `...INVALID_ARGUMENT; not enough bytes for a valid IRPA header; file may be empty or truncated` | **Yes, cleanly** — names the format, the defect, and the likely causes. The best of the four. |
| Wrong scope name (valid archive bound under an unreferenced scope) | `...NOT_FOUND; no provider registered that handles scopes like 'model'; while invoking native function io_parameters.load; ...` | **Yes** — names the specific missing scope. |
| Unsupported file extension (path does not end in `.irpa`/`.gguf`/`.safetensors`) — not probed by this spike's fixtures, but a real API contract: `iree_io_parse_file_index` (`io/formats/parser_registry.c`) dispatches purely on the path's extension | `...UNIMPLEMENTED; unsupported file format '<ext>'; ensure the extension matches one of the supported formats: [.irpa, .gguf, .safetensors]` | **Yes** — names the bad extension and the supported set. Relevant to the manifest: a caller-supplied relative path with any other extension (e.g. `weights.bin`) is a fifth reachable failure mode. |

**Bottom line for the manifest:** the existence-only check is safe from a crash standpoint. The
worst a caller can do by `touch`-bypassing it is trade a manifest-level error for an equally
catchable exception one call deeper. The zero-byte message is the single candidate for a
friendlier wrapper if this ships — it is the one an operator is most likely to hit (it is the
documented bypass, after all) and the one most likely to be misread as corruption. Not a
blocker; see *Deferred minors*.

---

## Cost estimate for production IRPA

The shape is now known, so this is estimable rather than speculative. The spike itself is
~400 lines of native code and fixtures across Tasks 1-7; the production delta is mostly
*above* `native/core/`.

**Already done by the spike, reusable as-is:**

- `IreeRuntime::Load`'s 4-argument overload and the `ParameterScope{scope, path}` type. This is
  the production shape, not a spike hack — Q7 showed multi-scope needs nothing further.
- The eight CMake link targets.
- Fixtures: `scale.vmfb` + `scale_weights.irpa` (single scope, splat), `scale2.vmfb` +
  `scale2_bias.irpa` (two scopes), `scale_weights_zero.irpa` (FILE-backed, real storage),
  generated by `tools/export_scale.sh` from `tools/scale.mlir` / `tools/scale2.mlir`.
- Coverage: `native/test/iree_params_test.cpp` (9 cases) and `iree_leak_harness`'s
  parameter-bound cycle mode.

**Remaining, roughly in dependency order:**

1. **JNI marshalling** — a `String[]` scope / `String[]` path pair (or a parallel array of
   pairs) across the boundary into the `ParameterScope` span. Small: paths are strings, and
   Q8 confirmed no byte marshalling is ever needed. Low risk.
2. **Java load options** — `Model.load` / criteria plumbing to carry a scope→path map beyond
   today's `entryPoint`/`device`. Mechanical, but it is public API and should land alongside
   the manifest rather than ahead of it.
3. **Manifest parsing and resolution** — schema version, relative-path resolution with the
   `allowUnsafePaths` containment rule, existence-only checking, the `djl-iree-model.json`
   directory convention. **This is the largest remaining chunk and it is entirely Java-side**;
   the scoping notes' open questions already specify it in detail.
4. **A friendlier wrapper for the zero-byte message** (optional, see *Deferred minors*).
5. **Release-track native rebuild** — the spike built host-glibc binaries; anything shipped
   must go through `./native/local_build_wrapper.sh`.

**Nothing in this list is native-runtime risk.** The native work is done and the residual
uncertainty has moved to Java-side manifest semantics, which is exactly where the spike was
supposed to move it.

---

## Deferred minors

Collected from the SDD ledger across all tasks so they are triageable rather than lost.
**None block production IRPA; a few have since been fixed in the fix-wave pass over this
document — noted per row.** Ordered by rough severity.

| # | Task | Item | Note |
|---|---|---|---|
| 1 | 6 | `TempFileGuard`'s destructor uses the throwing `std::filesystem::remove` overload during unwind → `std::terminate` risk if removal fails. Use the `error_code` overload. | Fixed: switched to the `error_code` overload. |
| 3 | 7 | `ParamCycle` in the leak harness hardcodes the zeroed-fixture expectation, so the parameter path is single-fixture. | Also relevant: the harness hardcodes `kEntryPoint` assuming `module.add`. |
| 4 | 4 | The two-scope test omits the shape / byte-length `REQUIRE`s its sibling golden-vector test has. | Fixed: added the same `shape`/byte-length `REQUIRE`s. |
| 5 | 1 | `tools/export_scale.sh` lacks the PATH fallback for tools that `export_add.sh` has. | Inherited from the brief. Deliberately left. |
| 6 | 1 | `tools/export_scale.sh` does not embed the oracle/dump verification the way `export_add.sh` embeds `iree-dump-module`. | Inherited from the brief. Deliberately left. |
| 7 | 3 | `IREE_DJL_SCALE_IRPA_ZERO` was defined but unused at the time; the zeroed archive is the ideal anti-vacuity control for the golden vector. | Substantially addressed by Task 7, which put the zeroed fixture to work. Verify and close. |
| 8 | 5 | The `RuntimeState` comment is long for a struct that no longer holds the parameter members. | Fixed: trimmed to the mechanism plus a one-line maintainer rule. |
| 9 | 7 | IREE line numbers cited throughout this document were read from a slightly drifted checkout. | Not "a few lines": most citations are exact or off by 1-2, but `fd_file.c` citations are off by 98-131 lines. See the per-citation deltas noted where line numbers are used in this document; re-anchor to the pinned `e4a3b0405d` if this document is used as a precise source reference. |
| 10 | 9 (Q9) | The zero-byte archive's error message leaks IREE's internal `SIZE_MAX` sentinel before the load-bearing "0 total bytes". | Candidate for a friendlier wrapper on the documented `touch`-bypass path. |

---

## Documents corrected by this spike

Recorded here so the corrections are discoverable from the evidence rather than only from the
corrected documents:

- **`docs/2026-07-22-irpa-and-target-selection-scoping-notes.md`**
  - Part 1 "Big rock": header-and-symbol *disproof* upgraded to empirical confirmation.
  - Part 1 "Marshalling: pass the PATH, not the bytes": the mmap/zero-copy rationale was
    **wrong** (Q8). Conclusion affirmed, rationale replaced.
  - Part 1 "The real work is native runtime wiring": "index/provider/module + the mmap must
    outlive the session and every invoke" was **backwards** (Q4/Q5). IREE retains all of it.
  - Part 4: spike marked complete.
- **`docs/panama-research-sketch.md`**
  - "IRPA is what makes direct binding indefensible": the argument as originally stated
    **does not survive** Q4/Q5 — there is no multi-level ownership graph for a caller to model.
    A thinner argument replaces it.
  - "Remaining work" item 8 (IRPA fixture for the leak harness): **done**.

## Build environment note

The build environment varied by task and is worth recording. Task 3 built with host
`cmake`/`ninja` (`-DCMAKE_BUILD_TYPE=RelWithDebInfo`, host JDK for headers only), because
`native/build.sh` assumes a manylinux container that was not present. Tasks 4 and later used
`./native/local_build_wrapper.sh`; because CMake is configured with cwd `/workspace` inside the
container, the compiled-in fixture path constants are absolute `/workspace/...` paths and the
test binaries must be run through the same bind-mount. Both are fine for a native-only spike
that ships no `.so`; neither is a substitute for the container wrapper on release-track work.
