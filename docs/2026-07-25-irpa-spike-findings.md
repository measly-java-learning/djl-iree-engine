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
| File handle (`iree_io_file_handle_t`) — **verified empirically by Task 7** | The parameter index — `iree_io_parameter_index_add` calls `iree_io_file_handle_retain` for every FILE-backed entry (`io/parameter_index.c:185`) | No | Task 5's drop was inert: both fixtures it binds (`IREE_DJL_SCALE_IRPA`, `IREE_DJL_SCALE2_BIAS_IRPA`) are splat archives with no on-disk storage (see `tools/export_scale.sh`), so `iree_io_parameter_index_add` took the SPLAT branch and the FILE-branch retain at `parameter_index.c:185` never ran. Task 7 added a FILE-backed fixture (`scale_weights_zero.irpa`) and a golden-vector test case that loads it, then re-applied the drop — releasing the local file handle immediately after `iree_io_parse_file_index` returns, forcing its refcount down rather than relying on scope-exit timing. Result: **zero faults under ASan, correct golden output (`0,0,0,0`, input × the zeroed archive)**, across the full `iree_params_test` suite (9 cases, 36 assertions) and a 500-cycle repeated load/invoke/close run of `iree_leak_harness` with the archive bound. This empirically confirms the retain: the index keeps the file handle alive via its own reference regardless of what the caller does with its local one. |
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

**Answer: NOT MAPPED.** `iree_leak_harness` was extended to accept an optional 4th argv,
`scope=path`, threaded through to the 4-argument `IreeRuntime::Load`. Run against
`scale.vmfb` (single import, scope `"model"`) bound to the FILE-backed
`scale_weights_zero.irpa` fixture (the splat fixtures have no on-disk storage and cannot
answer this question at all — they're pure generated values, nothing to map), with
2,000,000 requested iterations so the process would still be running a second later:

```
$ ./native/build/iree_leak_harness src/test/resources/models/scale.vmfb 2000000 local-sync \
    "model=$(pwd)/src/test/resources/models/scale_weights_zero.irpa" &
HARNESS_PID=$!
sleep 1
ps -p $HARNESS_PID -o pid,stat,etimes,cmd   # confirmed alive (STAT=R, ETIMES=1)
grep -c "scale_weights_zero.irpa" /proc/$HARNESS_PID/maps || echo "NOT MAPPED"
```

```
0
NOT MAPPED
```

The full `/proc/<pid>/maps` for the (confirmed-alive) process is 40 lines: the harness
binary itself, libc/libstdc++/libgcc_s/libm/ld-linux, `[heap]`, `[stack]`, `[vdso]`,
`[vvar]`, `[vvar_vclock]`, `[vsyscall]`. No mapping references the archive path or any
anonymous region large enough to be a whole-file mapping of it — the archive is a few KB,
so it would not be lost among the 40 lines if present. `iree_io_file_handle_open` was
called with `IREE_IO_FILE_MODE_RANDOM_ACCESS` (the mode the code comments describe as
"mmap-friendly"), and the harness's parameter cycle (`ParamCycle`, `native/harness/iree_leak_harness.cpp`)
both loads the archive and invokes the model against it repeatedly, so the check is not
catching IREE before it has touched the file.

**Conclusion: for this build (linux-x86_64, `local-sync` driver, RANDOM_ACCESS file
mode), IREE reads the IRPA archive into host memory rather than mapping it into the
process's address space.** "Mmap-friendly" describes the *file handle mode* IREE
supports, not a guarantee that this platform/driver combination actually uses `mmap(2)`
for it. The path-based manifest contract itself is unaffected — passing a path instead of
bytes is still the right shape, and the go/no-go from Task 6 stands — but **its rationale
needs rewording**: it should not claim "so the OS mmaps it and we avoid a copy." It should
instead say the contract avoids requiring the caller to read the file into a byte buffer
before calling — IREE owns the read (or map, where it does map) itself. Whether a
mapping-backed path exists for other drivers/OSes was out of scope for this differential
and was not tested.

## FILE-backed differential (Task 7)

Recorded above in the ownership-chain table's file-handle row: dropping the local file
handle immediately after `iree_io_parse_file_index` returns, against the FILE-backed
zeroed archive, produced **zero faults** under ASan and the correct golden output. This
was the opposite of the naive expectation going in (a UAF, on the theory that a live
mapping needing to outlive the drop would break if nothing retained the handle) — but it
is fully consistent with, and confirms, the source-verified retain at
`parameter_index.c:185`: the index takes its own reference when the FILE-backed entry is
added, so the caller's local handle going out of scope (or being explicitly reset) has no
effect on the archive's continued availability. Combined with the "NOT MAPPED" result
above, the likely mechanism is that the archive's bytes are read directly by whatever
holds the retained handle (the index/the format parser), not lazily faulted in from a
live mapping — which is also why dropping the caller's own reference was always going to
be safe once the retain was confirmed to fire.

A repeated-cycle run (`iree_leak_harness`, 500 load/invoke/close cycles, `local-sync`,
zeroed archive bound) under ASan/LeakSanitizer also came back clean — the strongest single
signal for the parameter chain's lifetime correctness under real, repeated use.
