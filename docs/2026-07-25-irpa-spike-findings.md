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

Three of the four levels (module, provider, index) are verified both empirically —
deliberately dropping the `RuntimeState` reference and confirming `iree_params_test`
still passed clean under ASan/LeakSanitizer (4 test cases, 18 assertions each run) — and
by reading the retain call site in the IREE source. The fourth (file handle) is verified
by source reading only: `iree_params_test`'s fixtures are splat archives, so the drop
never exercised the code path that would prove it empirically. See the table's first
row and Task 7 for the pending FILE-backed test.

| Handle type | Retained by whom | Must we hold it in `RuntimeState`? | Failure mode when not held |
|---|---|---|---|
| File handle (`iree_io_file_handle_t`) — **provisional, pending Task 7** | The parameter index — `iree_io_parameter_index_add` calls `iree_io_file_handle_retain` for every FILE-backed entry (`io/parameter_index.c:185`) | No, per source reading | Not exercised by `iree_params_test`: both fixtures it binds (`IREE_DJL_SCALE_IRPA`, `IREE_DJL_SCALE2_BIAS_IRPA`) are splat archives with no on-disk storage (see `tools/export_scale.sh`), so `iree_io_parameter_index_add` takes the SPLAT branch and the FILE-branch retain at `parameter_index.c:185` never runs. The drop therefore passed clean under ASan, but for the uninteresting reason that nothing referenced the handle to begin with, not because a retain saved it. The mmap-outliving-lazy-reads prediction was *untested*, not disproven — `IREE_DJL_SCALE_IRPA_ZERO` is the one FILE-backed fixture in the tree and is currently referenced by no test. Task 7 owns the FILE-backed differential (load with the zeroed archive, invoke, then re-apply the Probe-5 drop and confirm it now use-after-frees). |
| Parameter index (`iree_io_parameter_index_t`) | The index provider — `iree_io_parameter_index_provider_create` calls `iree_io_parameter_index_retain` (`io/parameter_index_provider.c:64`) | No | None observed. PASS, clean under ASan. |
| Parameter provider (`iree_io_parameter_provider_t`) | The io_parameters module — `iree_io_parameters_module_create` calls `iree_io_parameter_provider_retain` per provider (`modules/io/parameters/module.c:518`) | No | None observed. PASS, clean under ASan. |
| io_parameters module (`iree_vm_module_t`) | The session/context — appending a module calls `iree_vm_module_retain` (`vm/context.c:444`/`597`), matching the documented claim at `session.h:143` | No | None observed. PASS, clean under ASan. |

Method: for each level, the `RuntimeState`-owned handle was replaced with a local RAII
object that releases at the end of the scope in which it's last needed (e.g. the file
handle releases right after `iree_io_parse_file_index` returns), then
`native/asan/iree_params_test` was rebuilt and run. Each successful drop was kept before
probing the next level down, so the four results compound: the final `RuntimeState` has
all four members removed simultaneously, and both `iree_params_test` (4 cases, 18
assertions) and `iree_runtime_test` (11 cases, 36 assertions) pass with zero
LeakSanitizer reports.

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
