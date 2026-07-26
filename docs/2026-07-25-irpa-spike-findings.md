# IRPA high-level session spike: findings

Findings from the `spike/irpa-high-level-session` branch. Started in Task 5; finished in
Task 8.

## Ownership chain (Q4/Q5)

Q4/Q5 asked: what does `RuntimeState` actually need to hold to keep the IRPA parameter
chain (file handle → index → provider → module) alive, and in what order?

Answer: **none of it needs to be held in `RuntimeState`.** Every level of the chain
retains the one below it internally inside IREE, via its own `_retain()` call at
construction time. `RuntimeState` only needs `session` (everything param-related is now
a local in `IreeRuntime::Load`, scoped to release right after each handle is handed to
the next level up).

All four levels (module, provider, index, and now file handle) are verified both
empirically — deliberately dropping the `RuntimeState` reference (or, for the file
handle, an explicit local drop) and confirming `iree_params_test` still passed clean
under ASan/LeakSanitizer — and by reading the retain call site in the IREE source. The
file handle needed a FILE-backed fixture to exercise the retain path at all;
Task 5's probe against the suite's splat-only fixtures never took that branch, so the row
was marked provisional until Task 7 added one.

| Handle type | Retained by whom | Must we hold it in `RuntimeState`? | Failure mode when not held |
|---|---|---|---|
| File handle (`iree_io_file_handle_t`) — **verified empirically by Task 7, load-bearing** | The parameter index — `iree_io_parameter_index_add` calls `iree_io_file_handle_retain` for every FILE-backed entry (`io/parameter_index.c:185`) | No | Task 5's drop was inert: both fixtures it binds (`IREE_DJL_SCALE_IRPA`, `IREE_DJL_SCALE2_BIAS_IRPA`) are splat archives with no on-disk storage (see `tools/export_scale.sh`), so `iree_io_parameter_index_add` took the SPLAT branch and the FILE-branch retain at `parameter_index.c:185` never ran. Task 7 added a FILE-backed fixture (`scale_weights_zero.irpa`) and a golden-vector test case that loads it, then re-applied the drop — releasing the local file handle immediately after `iree_io_parse_file_index` returns, forcing its refcount down rather than relying on scope-exit timing. The index stores only `{handle, offset}` at parse time (`irpa_parser.c:125`), so parameter bytes are not read until `io_parameters.load`, later, through the index's own retained handle (`parameter_index_provider.c:149`, `hal/utils/fd_file.c:442` `pread`). Result: **zero faults under ASan, correct golden output (`0,0,0,0`, input × the zeroed archive)** on that subsequent real read, across the full `iree_params_test` suite (9 cases, 36 assertions) and a 500-cycle repeated load/invoke/close run of `iree_leak_harness` with the archive bound and its output values asserted. Since the read genuinely happens after the drop, and through the index's reference rather than ours, this is a positive confirmation that the retain is load-bearing — not just an absence-of-leak result. |
| Parameter index (`iree_io_parameter_index_t`) | The index provider — `iree_io_parameter_index_provider_create` calls `iree_io_parameter_index_retain` (`io/parameter_index_provider.c:64`) | No | None observed. PASS, clean under ASan. |
| Parameter provider (`iree_io_parameter_provider_t`) | The io_parameters module — `iree_io_parameters_module_create` calls `iree_io_parameter_provider_retain` per provider (`modules/io/parameters/module.c:518`) | No | None observed. PASS, clean under ASan. |
| io_parameters module (`iree_vm_module_t`) | The session/context — appending a module calls `iree_vm_module_retain` (`vm/context.c:444`/`597`), matching the documented claim at `session.h:143` | No | None observed. PASS, clean under ASan. |

Method: for each level, the `RuntimeState`-owned handle was replaced with a local RAII
object that releases at the end of the scope in which it's last needed (e.g. the file
handle releases right after `iree_io_parse_file_index` returns), then
`native/asan/iree_params_test` was rebuilt and run. Each successful drop was kept before
probing the next level down, so the results compound: the final `RuntimeState` has all
four members removed simultaneously, and both `iree_params_test` (9 cases, 36 assertions,
after Task 7's FILE-backed addition) and `iree_runtime_test` (11 cases, 36 assertions)
pass with zero LeakSanitizer reports.

This was cross-checked against the IREE source (checkout at `~/workspace/iree`), not
just inferred from the ASan result — each `_retain()` call site above was read directly,
so the "no leaks" result is understood mechanistically, not just observed.

## Append ordering (Q2)

Q2 asked whether `iree_runtime_session_append_module` (registering the `io_parameters`
module) must run before `iree_runtime_session_append_bytecode_module_from_memory`
(registering the model's bytecode module, which imports from `io_parameters`). This had
previously only been inferred from the "no archives supplied → fails" test case, never
proven by actually reordering the two calls.

**Confirmed empirically: order matters, and the failure is eager, not lazy.** Moving the
`io_parameters` append to run *after* the bytecode-module append made 3 of the 4
`iree_params_test` cases fail immediately at the bytecode-append call, with:

```
iree_runtime_session_append_bytecode_module_from_memory(...): iree/runtime/src/iree/vm/context.c:205:
NOT_FOUND; required module 'io_parameters' not registered on the context; resolving module 'module' imports
```

Import resolution runs synchronously when a module is registered against whatever is
already in the context — there is no deferred/lazy resolution path that would tolerate
registering `io_parameters` afterward. The existing code (append parameters module before
the bytecode module) is therefore not just a stylistic ordering choice; it's required.

## Error behaviour (Q9)

Q9 asked whether a zero-byte `.irpa` — guaranteed reachable by the planned model-manifest
format, which checks only "does this file exist," and documents a `touch` bypass for
archives a caller knows will never load — fails as a diagnosable error or crashes.

**Confirmed: none of the four failure modes probed (missing file, zero-byte archive,
truncated archive, wrong scope name) crash.** All four throw a catchable
`std::runtime_error` from `IreeRuntime::Load`, in both a plain host build and under
ASan/LeakSanitizer, with zero leaked `iree_status_t` objects. This directly validates the
manifest design's existence-only check: the worst a caller can do by `touch`-bypassing the
check is trade a manifest-level error for an equally catchable exception one call deeper.

| Failure mode | Exact message | Diagnosable from the string alone? |
|---|---|---|
| Missing file | `...NOT_FOUND; failed to open file '/nonexistent/nope.irpa'` | Yes — names the missing path directly. |
| Zero-byte archive (the `touch` bypass case) | `...INVALID_ARGUMENT; failed to map file handle range 0-18446744073709551615 (18446744073709551615 bytes) from file of 0 total bytes` | Yes, with effort — "file of 0 total bytes" is the load-bearing phrase but is preceded by IREE's internal `SIZE_MAX` "whole file" sentinel leaking into the message, which could read as corruption rather than emptiness to an unfamiliar operator. |
| Truncated archive (8-byte garbage header) | `...INVALID_ARGUMENT; not enough bytes for a valid IRPA header; file may be empty or truncated` | Yes, cleanly — names the format, the defect, and the likely causes. |
| Wrong scope name (archive valid, bound under an unreferenced scope) | `...NOT_FOUND; no provider registered that handles scopes like 'model'; while invoking native function io_parameters.load; ...` | Yes — names the specific missing scope. |

Full messages, ASan results, and build-environment notes are in
`.superpowers/sdd/2026-07-25-irpa-high-level-session-spike/task-6-report.md`. Bottom line
for the manifest go/no-go: the existence-only check is safe from a crash standpoint; the
zero-byte message is the one candidate for a friendlier wrapper if this ships, but is not
a blocker on its own.

## mmap (Q8)

Q8 asked whether IREE genuinely `mmap`s an IRPA parameter archive, or reads it into host
memory. This matters because the planned model-manifest format passes archives **by
path, not by bytes**, and "IREE mmaps the file" is the stated justification for that
choice.

**Answer: both, at different times, for different purposes.** IREE `mmap`s the archive
**transiently**, only to parse the index: `iree_io_parse_irpa_index` calls
`iree_io_file_map_view(..., IREE_HOST_SIZE_MAX, ...)` (`irpa_parser.c:330`), which goes
through `iree_io_platform_map_file_view` to a real `mmap(..., MAP_SHARED, fd, offset)`
(`io/file_handle.c:731`) — and this is exactly why Task 6's zero-byte-archive error
message says "failed to **map** file handle range 0-18446744073709551615": the failure
is a real `mmap(2)` failing, not a metaphor. That mapping is unmapped again as soon as
the index is built (`irpa_parser.c:342`). The index itself stores only `{file handle,
offset}` per entry (`irpa_parser.c:125`) — no parameter bytes. Those bytes are fetched
later, at `io_parameters.load` time, span-by-span, via `pread(2)` on the retained fd into
HAL buffers (`hal/utils/fd_file.c:442`). In this fixture, `io_parameters.load` runs
**once, during `Load`**, not per inference: `tools/scale.mlir` binds the parameter to a
`util.global` (`#stream.parameter.named<"model"::"weight">`), so the load executes in the
module initializer, which runs when the bytecode module is registered on the context
(`iree_runtime_session_append_bytecode_module_from_memory`,
`native/core/iree_runtime.cpp:187`) — inside `Load`, not `Invoke`. This is also why every
one of this document's `Load`-time error messages (§ Error behaviour above) already
includes `...while invoking native function io_parameters.load`, and why
`iree_params_test.cpp`'s "wrong scope name" case asserts that message from a failing
`Load` call, not a failing `Invoke`. A program that instead loaded parameters from inside
a function body rather than a `util.global` initializer would read at invoke time — this
finding is about this fixture's binding shape, not a universal property of
`io_parameters.load`.

So there is **no persistent mapping** of the archive, and there **is** a copy into the
target buffer on every read. `iree_leak_harness` was extended to accept an optional 4th
argv, `scope=path`, threaded through to the 4-argument `IreeRuntime::Load`, and run
against `scale.vmfb` (single import, scope `"model"`) bound to the FILE-backed
`scale_weights_zero.irpa` fixture (the splat fixtures have no on-disk storage and cannot
answer this question at all), with 2,000,000 requested iterations so the process would
still be mid-run — actively looping `Load`/`Invoke`/close, i.e. actively re-parsing and
re-reading the archive — a second later:

```
$ ./native/build/iree_leak_harness src/test/resources/models/scale.vmfb 2000000 local-sync \
    "model=$(pwd)/src/test/resources/models/scale_weights_zero.irpa" &
HARNESS_PID=$!
sleep 1
ps -p $HARNESS_PID -o pid,stat,etimes,cmd
grep -c "scale_weights_zero.irpa" /proc/$HARNESS_PID/maps || echo "NOT MAPPED (grep found 0 matches)"
wc -l /proc/$HARNESS_PID/maps
```

Actual output:

```
    PID STAT ELAPSED CMD
3095117 R          1 ./native/build/iree_leak_harness src/test/resources/models/scale.vmfb 2000000 local-sync model=/home/corey/workspace/djl-iree-engine/src/test/resources/models/scale_weights_zero.irpa

0
NOT MAPPED (grep found 0 matches)

40 /proc/3095117/maps
```

The full 40-line `/proc/<pid>/maps` for the confirmed-alive process (`STAT=R`,
`ETIMES=1`) is: the harness binary itself (5 segments), libc/libstdc++/libgcc_s/libm/
ld-linux (5 segments each), `[heap]`, `[stack]`, `[vdso]`, `[vvar]`, `[vvar_vclock]`,
`[vsyscall]`. No mapping references the archive path, and there is no anonymous region
large enough to be a whole-file mapping of a several-KB archive that would be lost among
the other 40 lines. This is a real, useful result, but it can only rule out a
**persistent** mapping — the parse-window `mmap` above is a microsecond-scale slice of
each `Load` cycle (map, build index, unmap, all before `Load` returns), so a 1-second
sample landing between cycles was always the expected outcome whichever way the
underlying mechanism actually worked, and does not by itself distinguish "never mapped"
from "mapped and already unmapped again."

**Conclusion for the manifest contract:** the contract still stands — passing a path
avoids requiring the caller to buffer the archive into memory before calling — but its
stated rationale needs rewording. Not *"the OS mmaps it so we avoid a copy"* (there is no
persistent mapping, and there is a copy, at read time). Instead: *IREE owns the file
descriptor and does positional reads (`pread`) of just the spans a program actually
imports, so the caller never has to read the whole archive into a byte buffer up front —
mapping is used only internally and briefly, to build the index of what's in the file.*

One caveat worth recording for later: the negative `/proc/maps` result is method-limited
by design (see above), and separately, `iree_io_file_handle_preload`
(`io/file_handle.h:216`) is a documented lever if copy cost ever matters in production —
it yields a `HOST_ALLOCATION`-backed handle that routes through
`iree_hal_memory_file_wrap` and unlocks the genuine zero-copy
`iree_hal_allocator_import_buffer` path (`parameter_index_provider.c:741-763`). Not
exercised here; noted for whoever picks this up if the `pread` cost at model-load time
(a one-off per session `Load`, per the initializer-timing note above — not a per-inference
cost, at least for `util.global`-bound parameters like this fixture's) becomes a concern.

## FILE-backed differential (Task 7)

Recorded above in the ownership-chain table's file-handle row: dropping the local file
handle immediately after `iree_io_parse_file_index` returns, against the FILE-backed
zeroed archive, produced **zero faults** under ASan and the correct golden output.

The mechanism, traced through the source: the retain at `parameter_index.c:185` is
**load-bearing, not defensive**. `iree_io_parse_irpa_v0_data_entry` stores only
`{handle, offset}` per entry in the index (`irpa_parser.c:125`) — no parameter bytes are
resident when `iree_io_parse_file_index` returns. Those bytes are fetched later, during
`io_parameters.load`: `iree_io_parameter_index_provider_resolve` dereferences
`entry->storage.file.handle` (`parameter_index_provider.c:149`) and imports it as a HAL
file, which for an FD-backed handle retains it again (`hal/utils/fd_file.c:345`) and then
`pread`s the requested span (`fd_file.c:442`). Dropping the local reference immediately
after parse therefore leaves the index holding the *only* reference across a subsequent,
real, later read of the file. Had the index's retain not actually fired at runtime, that
later `pread` would be operating on a freed `iree_io_file_handle_t` and (absent some other
live reference) a closed fd — a genuine use-after-free that ASan/LeakSanitizer would have
caught. It did not fire an error; it returned the correct `0,0,0,0` golden output. Zero
faults plus correct output is therefore a real positive result for the retain being both
present and load-bearing — not an artifact of the data already being resident, and not
inevitable regardless of whether the retain fired.

A repeated-cycle run (`iree_leak_harness`, 500 load/invoke/close cycles, `local-sync`,
zeroed archive bound) under ASan/LeakSanitizer also came back clean — the strongest single
signal for the parameter chain's lifetime correctness under real, repeated use.
