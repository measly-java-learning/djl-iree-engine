# IRPA manifest loading — design

Design for the first production chunk of IRPA support: getting parameter archives from a
user-supplied model artifact through `Model.load` and into `IreeRuntime::Load`.

**Predecessors.** The native runtime work is done and validated:
`docs/2026-07-25-irpa-spike-findings.md` (verdict GO). The user contract was settled in
`docs/2026-07-22-irpa-and-target-selection-scoping-notes.md`, Parts 1/3 and *Open questions*.
The ABI ordering argument is in `docs/panama-research-sketch.md`.

## Goal

A caller points DJL at a model artifact that names a `.vmfb` and one or more scope-bound `.irpa`
archives, and the model loads with its weights. Concretely: `Model.load` on a directory
containing a `djl-iree-model.json` succeeds, and a forward pass returns the golden values.

## Non-goals

Each is deferred for a stated reason, not overlooked.

- **hwcaps / CPU tier selection.** Independent of IRPA (params are hardware-agnostic, programs
  are not), and Java-only. The schema below is shaped so it lands additively.
- **The `Invoke` out-param change** removing `lastImportOutcomes` state — GitHub issue #4. Real
  and decided, but it is an ABI-shape fix belonging with `native/capi/`, and it touches `Invoke`
  while this work touches only `Load`.
- **`native/capi/` and the Panama/FFM front-end.** See *ABI decision* below for why this work
  does not wait on it.
- **A friendlier zero-byte archive message** — GitHub issue #3.
- **Archive (zip/tar) support.** Permanently disclaimed; the caller unarchives. Documentation
  obligation only, discharged in *Documentation* below.
- **Checksums or any content validation of assets.** Explicitly rejected by the scoping notes.

## ABI decision: extend the JNI boundary directly

Parameter scopes cross as two parallel `String[]` arrays on the existing `IreeNative.load`,
zipped native-side into the `std::span<const ParameterScope>` that `IreeRuntime::Load` already
accepts. We do **not** build the opaque `iree_djl_load_options_*` C API sketched in
`panama-research-sketch.md` as part of this work.

Rationale:

1. **It is the prescribed order.** That document's own step 3 is "stand up `native/capi/` over
   the current high-level implementation and rebase the JNI shim onto it." JNI-first-then-rebase
   is the plan of record, not a shortcut past it.
2. **The spike collapsed the ABI risk.** Q4/Q5 found there is no ownership graph to express:
   the boundary conveys N `(scope, path)` string pairs into `Load` and nothing else — no handles
   escape, no lifetimes cross, nothing survives the call. The thing an options builder protects
   against does not exist here.
3. **Little future load-option pressure reaches the native boundary.** Tier selection and
   manifest resolution both resolve *in Java* down to a single chosen `.vmfb` path. Positional
   growth is the hazard the sketch warned about, but the next options stop above JNI.
4. **The options object would be one-seventh of an undesigned facade.** Remaining-work items 1-4
   in the sketch (un-throw the boundary, flatten `InputDesc`/`OutputBuffer`, decide the
   result-set protocol, fold in the outcomes) are each larger than the load-options piece.
   Landing a builder while `Invoke` still throws C++ exceptions and returns `std::vector` by
   value produces a boundary that is not a boundary.

**Rework accepted if Panama ever lands:** roughly twenty lines of marshalling in the shim
re-pointed at the C API, plus `IreeNative.load`'s signature. `IreeNative` is internal — its only
callers are `IreeModel` and the test suite, and it ships in the same jar as the `.so` it binds,
so that is a recompile and not a compatibility break.

## Architecture

Four new Java units, each independently testable, plus two records.

### `ModelManifest`

A record holding the parsed document, with `static ModelManifest parse(String json, String
sourceLabel)`.

**Pure — no filesystem access, no path resolution.** It validates schema rules and returns typed
data. `sourceLabel` is carried solely for error messages. This purity is the point: every schema
rule and every parse-time message is testable from string literals with no temp directories.

Fields: `int schemaVersion`, `String program`, `String entryPoint` (nullable),
`Map<String, String> parameters` (empty, never null).

### `ModelResolver`

`ResolvedModel resolve(Path modelPath, String prefix, IreeLoadOptions opts)`.

The filesystem half: the three front doors, relative-path resolution, containment checking, and
existence checks. Depends on `ModelManifest`; only `IreeModel` depends on it.

### `IreeLoadOptions`

`static IreeLoadOptions from(Map<String, ?> options)` → `{String entryPoint (nullable), String
device, boolean allowUnsafePaths}`.

Replaces the inline `options.get(...)` reads currently at `IreeModel.java:44-52`, giving one
place that knows option-key names and defaults.

### `ResolvedModel` and `ParameterBinding`

`ResolvedModel{Path vmfb, List<ParameterBinding> parameters, String entryPoint}` and
`ParameterBinding{String scope, Path path}`. The single currency between resolution and loading.
`ResolvedModel.entryPoint` is the manifest's value and may be null; precedence is applied by
`IreeModel`, not by the resolver.

### `IreeModel.load` after the change

Orchestration only: parse options → resolve → apply entry-point precedence → read vmfb bytes →
`IreeNative.load`.

## Data flow

```
Model.load(modelPath, prefix, options)
        │
        ├─ IreeLoadOptions.from(options)      {entryPoint?, device, allowUnsafePaths}
        │
        ├─ ModelResolver.resolve(...)
        │     ├─ front door: file → parse it as a manifest (any name)
        │     │              dir  → djl-iree-model.json if present
        │     │                     else <prefix>.vmfb as an implicit manifest
        │     ├─ ModelManifest.parse(text)     schemaVersion first, then the rest
        │     ├─ resolve every path against the MANIFEST's directory
        │     ├─ containment check on the real path (unless allowUnsafePaths)
        │     └─ existence check, eager, all assets
        │                                      → ResolvedModel
        │
        ├─ entryPoint precedence: option > manifest > "module.main"
        ├─ Files.readAllBytes(vmfb)
        └─ IreeNative.load(bytes, entryPoint, device, scopes[], paths[])
```

Two properties of that flow are load-bearing:

**Paths resolve against the manifest document's directory, not `modelPath`.** These differ
whenever the caller passes an explicit manifest file path. The scoping notes anchor resolution to
the manifest document, and the implicit bare-`.vmfb` door sets the anchor to `modelDir`, which
makes that case fall out identically rather than needing its own rule.

**The implicit manifest is constructed, not special-cased.** The bare-`.vmfb` door builds a real
in-memory `ModelManifest{schemaVersion: 1, program: prefix + ".vmfb", parameters: {}}` and hands
it to the same resolution code. There is exactly one downstream path, so future fields cannot
quietly land on only one door.

## Entry points

`Model.load` accepts three forms. An explicit file path always wins and **may be named
anything** — `djl-iree-model.json` is a discovery convention for the directory form only, never a
validation rule.

| `modelPath` | Behaviour |
|---|---|
| A regular file | Parse it as a manifest, whatever its name. |
| A directory containing `djl-iree-model.json` | Parse that file. |
| A directory with no `djl-iree-model.json` but a `<prefix>.vmfb` | Implicit single-program, zero-parameter manifest. Preserves today's behaviour. |
| A directory with neither | Error naming the directory and both things sought. |

Several manifests may coexist in one asset directory — staging vs prod, an A/B pair — all
describing the same loose files, with `djl-iree-model.json` acting as the default the directory
form resolves to.

## Manifest schema v1

```json
{
  "schemaVersion": 1,
  "program": "model.vmfb",
  "entryPoint": "module.main",
  "parameters": {
    "model": "weights.irpa",
    "bias":  "bias.irpa"
  }
}
```

`schemaVersion` and `program` are required. `entryPoint` and `parameters` are optional; an absent
`parameters` is equivalent to `{}`.

**`program` is a plain string naming the baseline program, and always will be.** Tiers arrive
later as a separate optional `"variants"` object. This is deliberate: changing an existing
field's type would force the major bump the scoping notes call a last resort, and keeping the
baseline field required structurally enforces the notes' own closing preference that a manifest
carrying tiers must always carry a baseline. A v1 engine reading a future tiered manifest ignores
`variants` (rule 3) and loads the baseline — correct and safe.

**Parsing is a hand-walk over a Gson `JsonObject`, not POJO binding.** Binding would give rule 3
for free, but Gson's type-mismatch messages are unusable and the scoping notes make message
quality a requirement in several places. The document is four fields. Gson arrives transitively
via `djl-api`, so `ai.djl.util.JsonUtils` is used and no new dependency is added.

### The five schema rules

| Rule | v1 implementation |
|---|---|
| 1. Single integer version | `schemaVersion` must be a JSON integer. `"1"` (string) and `1.0` are type errors; neither is coerced. |
| 2. Required, no default | Absent → error. Never assume 1. A missing field is likelier a malformed document or the wrong file entirely than a genuine v1 manifest, and once a default is assumed the two cases can never be distinguished again. |
| 3. Unknown fields ignored silently | The hand-walk reads only keys it knows; everything else is untouched. This is what makes additive evolution free. |
| 4. Unknown values in consumed fields → error | **Nothing to enforce in v1, recorded deliberately.** No consumed field is an enum — `program`, `entryPoint`, and every `parameters` value is a free-form path or name. Rule 4 binds the first enum we add. What v1 enforces is the *type* of each consumed field. |
| 5. `requires` must-understand | v1 understands **zero** keys, so any non-empty `"requires"` object is an error naming the first unknown key. Absent or `{}` passes. |

`schemaVersion` is validated and range-checked **before any other key is read**, so a future
manifest reports a clean version error rather than a confusing type or unknown-field complaint
from further down the document.

### Two smaller calls

- **An empty scope name `""` is legal** and binds the archive's global scope, which
  `native/core/iree_runtime.h:42` already documents as `ParameterScope`'s behaviour.
- **Duplicate JSON keys are not detected.** Gson takes last-wins. Building a duplicate-detecting
  reader is not worth it here.

## Resolution and containment

Per asset, in this order — the order is part of the design:

1. **Resolve** the relative path against the manifest document's directory.
2. **Existence check.** Missing → the missing-asset error. This precedes step 3 so a dangling
   path produces the "forgot to unarchive" message rather than a confusing realpath failure.
3. **`toRealPath()`**, then `startsWith(manifestDir.toRealPath())`. Containment is checked on the
   resolved real path, never the string: a symlink escape is invisible in the manifest text, so
   string-level `..` checking would miss it.
4. Step 3 is skipped entirely when `allowUnsafePaths` is set.

Containment applies to **the `.vmfb` as well as the archives** — every asset the manifest names.
The flag is **all-or-nothing per manifest, not per asset**, and it lives on the caller's load
options and never in the manifest: a manifest that could self-authorize escape defeats the
control, since the manifest is precisely the artifact that may be untrusted.

**All listed assets are checked eagerly**, not merely the ones a given load ends up opening.
Eager is cheap and predictable and gives one clear error for the whole artifact.

**No size or content check anywhere.** A zero-byte asset passes step 2 and reaches IREE
untouched. That is the documented `touch` bypass — a user placeholdering an asset they know will
never be loaded — and Q9 confirmed every resulting failure is a catchable exception rather than a
crash. *Do not "improve" this with a non-zero-size check; it would break the bypass.*

## Load option precedence

| Option | Source | Default |
|---|---|---|
| `entryPoint` | load option > manifest > default | `"module.main"` |
| `device` | load option only | `"local-sync"` |
| `allowUnsafePaths` | load option only | `false` |

`entryPoint` is in the manifest because it is a *fact about the compiled artifact* —
`iree-dump-module` tells you what it is — and a describing manifest is the right home for it.
The caller keeps an override because a `.vmfb` may export several functions.

`device` and `allowUnsafePaths` are policy, and a model artifact must not choose its own
execution driver or authorize its own path escapes. Same reasoning that keeps the future tier
option out of the manifest.

## Error catalogue

Every message names the fix.

| Condition | Message content |
|---|---|
| Directory form, no `djl-iree-model.json` and no `<prefix>.vmfb` | The directory, and **both** things sought. |
| Manifest names a missing asset | The asset path and the manifest, worded for the forgot-to-unarchive case, since that is the predictable consequence of the archive disclaimer. |
| Asset path escapes the manifest directory | The asset, its resolved real path, and **the `allowUnsafePaths` option by name**, so the legitimate case is a one-line fix. |
| `schemaVersion` greater than supported | Both numbers and the engine version: *"model manifest requires schema version 2; this engine (0.1.0-SNAPSHOT) supports up to 1 — upgrade the engine."* |
| `schemaVersion` missing or not an integer | States required-and-no-default explicitly, so it does not read as a parser quirk. |
| Unsatisfied `requires` entry | The specific unknown key. |
| Malformed JSON, or a consumed field of the wrong type | The manifest path and the offending key. |

The engine version comes from `IreeEngine.getVersion()`. Note that this is currently a hardcoded
`"0.1.0-SNAPSHOT"` at `IreeEngine.java:39-41` while the Gradle build derives the real version
from the `releaseVersion` property — a pre-existing inconsistency. This design consumes the
accessor and does not fix it; worth a separate issue.

**Exception types.** `ModelManifest.parse` throws `ManifestException extends IOException` for
every schema and JSON failure, so the pure unit stays free of filesystem exception types while
still fitting `Model.load`'s `throws IOException`. `ModelResolver` throws `FileNotFoundException`
for missing assets and missing front doors — matching the existing behaviour at
`IreeModel.java:41` — and `ManifestException` for containment refusals. Nothing in this design
throws an unchecked exception for a condition the caller can cause.

`sourceLabel` is the manifest's path for the two real front doors, and the literal
`"<implicit manifest>"` for the bare-`.vmfb` door, so a message can never point at a file that
does not exist.

## Native and JNI changes

`IreeRuntime::Load`'s four-argument overload is **unchanged** — it already takes
`std::span<const ParameterScope>`, which the findings identify as the production shape rather
than a spike artifact.

`IreeNative.load` gains two parameters:

```java
static native long load(byte[] vmfb, String entryPoint, String device,
                        String[] paramScopes, String[] paramPaths);
```

with a **plain-Java** three-argument overload delegating with empty arrays — not a second native
method. This leaves `IreeNativeTest`'s five existing call sites untouched and keeps one entry
point in the shim.

In `native/jni/iree_djl_jni.cpp:68`, one helper plus a zip:

- `ToStringVector(env, jobjectArray)` → `std::vector<std::string>`, treating a null array as
  empty. **It must `DeleteLocalRef` each element as it goes:** `GetObjectArrayElement` returns a
  fresh local ref per call, and the default local-reference table is small enough that a
  many-scope manifest could overflow it. This is the one non-obvious hazard in the file.
- Zip the two vectors into `std::vector<ParameterScope>` and call the four-argument `Load` inside
  the existing `try`. Mismatched array lengths throw via `ThrowJava` — the Java layer guarantees
  they match, but the shim is the trust boundary and the check is two lines.

**The checked-in `src/main/resources/native/linux-x86_64/libiree_djl.so` must be regenerated
through `./native/local_build_wrapper.sh`.** The JNI signature changes, so a stale `.so` is an
`UnsatisfiedLinkError`. The container wrapper is the only release-track build path; the spike's
host-glibc builds are not a substitute.

## Testing

| Test | Kind | Covers |
|---|---|---|
| `ModelManifestTest` | pure, string literals | Every schema rule and every parse-time message. No filesystem. |
| `ModelResolverTest` | `@TempDir` | All three front doors; the manifest-directory anchor (including an explicit manifest whose directory differs from `modelPath`); eager existence checking; containment via `..` **and** via symlink; `allowUnsafePaths`; a zero-byte asset passing. |
| `IreeNativeTest` | JNI | `scale.vmfb` + `scale_weights.irpa` → `2 4 6 8`; two-scope `scale2.vmfb` + `scale_weights.irpa` + `scale2_bias.irpa` → `12 14 16 18`; the three-argument overload still works. |
| `ScaleModelIT` | end-to-end | `Criteria`/`Model.load` against a real manifest directory, golden `2 4 6 8`. The actual proof that the chunk works. |

**Fixtures already exist** in `src/test/resources/models/` from the spike — `scale.vmfb`,
`scale2.vmfb`, `scale_weights.irpa`, `scale2_bias.irpa`, `scale_weights_zero.irpa`. No new
binaries are needed.

**Tests copy fixtures into a `@TempDir` and write the manifest there** rather than checking a
manifest into `models/`. Two reasons: dropping a `djl-iree-model.json` into that flat directory
would make the directory front door find it and hijack `AddModelIT`, which loads `add.vmfb` from
the same folder by prefix; and copying lets the containment tests construct symlinks and escapes
without polluting the repository.

**`scale_weights_zero.irpa` is not used as a Java-level golden vector.** Its output `0,0,0,0` is
the weakest possible golden value — a read that silently returned a zeroed buffer is
indistinguishable from a correct read of it. Its real job was the ASan use-after-free
differential, which `native/harness/iree_leak_harness.cpp` already owns.

## Documentation

Two obligations from the scoping notes, both discharged in `README.md`, on the principle that
silence is the only bad option:

1. **Unarchive before loading.** We do not accept a zip/tar and do not extract on the caller's
   behalf; a zipped `.irpa` must be materialised in full first, which reintroduces exactly the
   whole-archive I/O that path-passing avoids. This must be stated, not merely implied by a load
   failure.
2. **The baseline-target SIGILL footgun.** A `.vmfb` built with `--iree-llvmcpu-target-cpu=host`
   on a modern machine will fault with an illegal instruction on an older CPU. Recommend
   compiling for a baseline target until tier selection exists.

Plus the manifest format itself: the schema, the three entry-point forms, the option table, and
the `allowUnsafePaths` semantics.

## Risks

- **The manifest becomes public API** — a versioned compatibility surface owned across releases.
  This is inherent to the decided contract, and the five schema rules exist to make "never bump
  major" achievable rather than to handle bumps gracefully.
- **`toRealPath()` behaviour on exotic filesystems** (bind mounts, overlayfs, network mounts)
  could in principle reject a legitimate layout. Mitigated by `allowUnsafePaths` being a
  documented, named escape hatch, and by the refusal message naming it.
- **The regenerated `.so`** must land in the same commit as the Java signature change or the
  build is broken between commits.
