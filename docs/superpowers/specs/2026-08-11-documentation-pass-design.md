# Documentation pass: javadoc, native comments, user-forward README, CLAUDE.md

**Date:** 2026-08-11
**Status:** Approved, not yet implemented

## 1. Goal

Bring the project's documentation up to the standard its code already meets. Four
surfaces, one pass:

1. The **javadoc jar published to Maven Central** says something useful about every
   public type, instead of emitting 59 warnings and shipping empty pages.
2. The **native C++ sources** carry ownership and lifetime contracts, not just the
   partial coverage `iree_runtime.h` has today.
3. **`README.md` addresses a user**, not a contributor: what this is, how to run
   something, how to load a model with parameters, how to watch it in production.
   Contributor material moves to a new `CONTRIBUTING.md`.
4. A **`CLAUDE.md`** captures the repo's trip-wires so an agent working here does not
   rediscover them one wasted hour at a time.

Nothing in this pass changes behavior. No production code is modified except to add
comments and javadoc; the only non-comment changes are new `package-info.java` files
and new/moved Markdown.

## 2. Current state

| Surface | State |
| --- | --- |
| `./gradlew javadoc` | Builds, **59 warnings**. Missing `@param`/`@return` and undocumented public methods in `IreeTensor`, `IreeNative`, `LibUtils`, `ManifestException`, `ModelManifest`, `ModelResolver`. |
| Javadoc coverage | Uneven. `IreeEngineStats` (17 blocks/383 lines), `IreeStatsSnapshot` (18/158), `IreeModelStats` (14/134) are good. `ModelManifest` (1/158), `ModelResolver` (1/107), `IreeSymbolBlock` (5/247), `IreeNDArray` (1/49), `IreeLoadOptions` (1/35) are thin. |
| `org.measly.iree.jni` | Public, but internal plumbing. Nothing marks it as unstable. |
| Native comments | ~2,970 real lines across 12 files. `iree_runtime.h` 73/168 comment lines, `iree_runtime.cpp` 141/536, `iree_djl_jni.cpp` 92/480. Sparse: `iree_handles.h` 5/77, `array_size_limits.h` 5/16, `iree_status.h` 9/43. |
| `README.md` | 17.5K. Well written, but contributor-ordered, no quickstart, opens with "not a product", and **stale on platform support** (see §3). |
| `CONTRIBUTING.md` | Does not exist. |
| `CLAUDE.md` | Does not exist. |

## 3. Platform support is documented wrongly today

The README states "Linux (x86_64 and aarch64) only" and its platform table has two
rows. This is **wrong**. `windows-x86_64` is a shipping platform:

- `build.gradle.kts:229` lists it in `nativePlatforms`, giving it a published variant
  and capability like the Linux rows.
- `.github/workflows/native-build-job.yml:86` runs `build-iree-shim-windows` on
  `windows-2022`: builds the shim under MSVC against the image's JDK 8 headers (matching
  the Linux rows' Corretto 8 floor), runs full native QA via `native/build_qa.sh`,
  asserts the static-CRT link with `native/tests/check_windows_crt.sh` **before** upload,
  and publishes `iree-libs-windows-x86_64`.
- `.github/workflows/publish.yml` downloads every `iree-libs-*` artifact into
  `build/native-staging/`, and `nativeJar-windows-x86_64` fails the build if the DLL or
  its license notices are missing — so a release cannot silently omit Windows.

The stale `Status:` header on
`docs/superpowers/specs/2026-08-03-windows-amd64-support-design.md` ("approved but not
yet implemented") reinforces the error and is corrected as part of this pass.

Windows differs from Linux only in QA depth: no ASan/LSan leak harness and no TSan gate
there. That is a real distinction the platform table must show rather than hide.

## 3a. Hard constraint: no emoji in Markdown

**`README.md` and `CONTRIBUTING.md` contain no emoji.** This is a requirement, not a
preference, and it is not negotiable during implementation. No emoji in headings, in
status markers, in callouts, in tables, or in prose. Emphasis is carried by wording,
bold, and structure — the same devices the current README already uses well.

The existing `README.md` and `example/README.md` are already emoji-free, so this
preserves the established convention rather than cleaning up a violation. It extends to
the other Markdown this pass produces: `docs/observability.md` and `CLAUDE.md`.

**Verification** — this must return no matches across every Markdown file the pass
touches or creates:

```bash
grep -nP '[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}\x{2B00}-\x{2BFF}\x{FE0F}]' \
  README.md CONTRIBUTING.md docs/observability.md CLAUDE.md
```

## 4. Deliverable 1 — Javadoc

**Bar: useful, not enforced.** Real content on the public API, no doclint gate. Warnings
are not build-failing; the check is that `./gradlew javadoc` reports zero.

- **`package-info.java` for `org.measly.iree.engine`** — what the engine is, how DJL
  finds it, the entry points a user starts from (`IreeEngine`, `ModelResolver`,
  `IreeEngineStats`), with `{@link}`s.
- **`package-info.java` for `org.measly.iree.jni`** — states plainly that the package is
  **internal and unstable**, not covered by compatibility guarantees, and changes in
  lockstep with the native shim. `@apiNote` internal markers go on `IreeNative` and
  `IreeTensor` too, so the warning is visible at class level and not only on a package
  page a user may never open.
- **Class-level javadoc on every public type.** The thin ones get real treatment — what
  it is for, ownership and lifetime, thread-safety: `IreeModel`, `IreeNDArray`,
  `IreeSymbolBlock`, `ModelManifest`, `ModelResolver`, `IreeLoadOptions`,
  `ParameterBinding`, `ResolvedModel`, `ManifestException`, `LibUtils`.
- **`IreeNative` documents the native contract** per method: what the JVM side must
  guarantee before the call, what the native side owns afterward, and what happens on
  error. This is the most valuable javadoc in the tree, because the contract exists
  nowhere else in Java.
- **Cross-links** between related types, so the generated HTML is navigable rather than
  twenty orphan pages.

No `@param name the name` filler. A parameter whose only honest description is its own
name gets a sentence about its constraints instead — what values are legal, what happens
when they are not.

**Verification:** `./gradlew javadoc` emits zero warnings, and the generated HTML under
`build/docs/javadoc/` is opened and read — landing page, both package pages, and
`IreeNative` — to confirm it reads as documentation rather than as satisfied lint.

## 5. Deliverable 2 — Native C++ comments

**Plain `//` comments in the style `native/core/iree_runtime.h` already sets.** No
Doxygen, no Doxyfile, no new tooling to maintain or CI-verify.

Full pass over all 12 real sources:

| Area | Files |
| --- | --- |
| `native/core/` | `iree_runtime.h`, `iree_runtime.cpp`, `iree_handles.h`, `iree_status.h`, `aligned_alloc.h` |
| `native/jni/` | `iree_djl_jni.cpp`, `array_size_limits.h` |
| `native/test/` | `iree_runtime_test.cpp`, `iree_params_test.cpp`, `link_smoke_test.cpp` |
| `native/harness/` | `iree_leak_harness.cpp` |
| `native/bench/` | `iree_copy_bench.cpp` |

Per file:

- A **file-level block** saying what this unit is and where it sits in the
  JVM → JNI → IREE path.
- **Ownership and lifetime contracts on every function**: who allocates, who releases,
  what the caller must guarantee, what is valid after the call returns. This is the
  highest-value gap — `iree_handles.h` is the whole RAII story in 77 lines with 5 lines
  of comment.
- **Why-comments on the non-obvious parts**: the 64-byte alignment precondition and the
  staging fallback, status conversion in `iree_status.h`, JNI local-reference discipline
  and the array size limits, the aligned allocator's reason for existing.

Test, harness, and bench sources get the same full pass: each case documents **what it
proves and why that is the right proof**, not what it does — the code already says what
it does.

**Verification:** `./native/build.sh` compiles clean and `./gradlew test` passes. This
deliverable is comments only — a diff touching a single non-comment line in these files
is a defect in this pass.

## 6. Deliverable 3 — README, CONTRIBUTING, docs/observability

### 6.1 `README.md` — user-facing

Reframed as a usable early library, honest about its limits: the walking-skeleton
framing survives, but as a clearly-labeled Status section rather than as the first thing
a prospective user reads. Order:

1. **What it is** — one paragraph, plus a one-line status pointer to §Limitations.
2. **Quickstart, tier 1** — inline Java against the committed `add.vmfb`. Zero external
   prerequisites; runs immediately.
3. **Quickstart, tier 2** — a real model: `./gradlew :example:exportModels` and
   `MobilenetExample`, pointing at `example/README.md` for the `uv` prerequisites.
4. **Declaring the dependency** — Gradle capability and Maven classifier forms, kept
   close to verbatim (they are correct and clear), extended to **all three** platforms.
5. **Supported platforms** — corrected table, per §3:

   | Platform | Artifact | HAL driver | QA |
   | --- | --- | --- | --- |
   | `linux-x86_64` | `libiree_djl.so` | `local-sync` (default), `local-task` | Catch2 + ASan/LSan leak harness; TSan |
   | `linux-aarch64` | `libiree_djl.so` | `local-sync` (default), `local-task` | Catch2 + ASan/LSan leak harness |
   | `windows-x86_64` | `iree_djl.dll` | `local-sync` (default), `local-task` | Catch2 + static-CRT assertion |

   Plus the existing note on extraction to `java.io.tmpdir` and `IREE_LIBRARY_PATH`.
6. **Runtime requirements** — JDK 17, supported platforms. Explicitly *not* the build
   prerequisites, which move to `CONTRIBUTING.md`.
7. **Loading models** — manifest schema v1, IRPA parameter archives, where `Model.load`
   looks, load options. Kept at current depth; this is the feature users came for.
8. **Observability** — condensed: what it is for, one snippet, and the staged-import
   gotcha, which is the signal specific to this engine. Links to
   `docs/observability.md`.
9. **Performance and zero-copy inputs** — condensed: the alignment story in brief and
   the flag. Links to the existing findings docs for measurements.
10. **Threading** — unchanged; short and user-facing already.
11. **Status and limitations** — the walking-skeleton framing, the go/no-go verdict link,
    the deferred list.
12. **Third-party licenses** — unchanged.
13. **Contributing** → `CONTRIBUTING.md`; **Docs** index.

### 6.2 `CONTRIBUTING.md` — new

Takes, near-verbatim where the prose is already good:

- Build prerequisites: the `iree-runtime-dist` pin and what it means (no IREE source
  tree, no compiler), `tools/fetch-iree-metadata.sh`, JDK 17, CMake/Ninja/C++20, the
  network hosts touched, and when `iree-compile` is actually needed.
- Build and test: `./native/build.sh`, `./gradlew test`, what the JVM suite covers.
- Editor setup (clangd), including all four caveats.
- Native QA: Catch2, the ASan/LSan gate, the TSan invocation and its `setarch`
  requirement, the mutually-exclusive sanitizer flags, the operational note about
  rebuilding the plain `.so`, and the `local-task` false-positive analysis.
- The container build and the per-platform pinned images.
- Regenerating `add.vmfb`.

### 6.3 `docs/observability.md` — new

Takes the detail condensed out of README §8: the staged-import rationale in full, the
`-1` vs `0` gauge semantics, JMX registration behavior and failure handling, and the
`ai.djl.metric.Metrics` comparison.

### 6.4 Stale-doc correction

`docs/superpowers/specs/2026-08-03-windows-amd64-support-design.md` gets its `Status:`
header corrected to reflect that Windows amd64 shipped, with a pointer to the CI job and
the published variant.

## 7. Deliverable 4 — `CLAUDE.md`

Thin and high-signal. Anything derivable from the code, or already written down in
`CONTRIBUTING.md`, is linked rather than restated.

- **What this is** — two sentences, plus pointers to README and CONTRIBUTING.
- **Repo map** — `src/` engine; `src/main/java/org/measly/iree/jni` internal boundary;
  `native/core` runtime facade; `native/jni` shim; `native/{test,harness,bench}` QA;
  `example/` MobileNet; `docs/`; `tools/`.
- **Commands that actually get run** — `./native/build.sh`, `./gradlew test`,
  `./native/build_qa.sh`, `./gradlew :example:exportModels`. Not an exhaustive task list.
- **Trip-wires**:
  - a sanitizer build stages an instrumented `.so` that breaks `./gradlew test`; rebuild
    plain afterward
  - TSan needs `setarch $(uname -m) -R` or it dies on ASLR
  - `local-task` TSan failures are known false positives, not a new bug
  - never commit `native/build-clangd/` — every entry carries absolute paths
  - `add.vmfb` is committed; `iree-compile` is unnecessary unless regenerating it
  - the pip `iree-base-runtime` wheel is unusable at any version; the pinned dist tarball
    is the only source
  - `native/cmake/IreeRuntimePin.cmake` is the single source of truth for the runtime
    version
  - three platforms ship, including `windows-x86_64` — do not assume Linux-only
- **Before claiming done** — `./native/build.sh` compiles clean and `./gradlew test`
  passes; never commit the clangd database, an instrumented `.so`, or large artifacts.
- **Code style** — Java: javadoc on all public API, `@apiNote` internal markers on the
  `jni` package. C++: plain `//` in `iree_runtime.h`'s style, ownership and lifetime
  contract on every function, `iree_status.h` conversion for error handling. Both:
  why-comments over what-comments.

## 8. Sequencing

One branch, four commits, in this order:

1. **Javadoc** — `package-info.java` files and javadoc across `src/main/java`.
2. **Native comments** — all 12 files under `native/{core,jni,test,harness,bench}`.
3. **README split** — `README.md` rewrite, `CONTRIBUTING.md`, `docs/observability.md`,
   Windows design-doc status fix.
4. **`CLAUDE.md`**.

They share no files, so they cannot conflict, but they are one coherent pass and are
reviewed together — the point is that all four surfaces tell the same story about the
same project, including the corrected platform support.

## 9. Out of scope

- Doclint or any build gate on javadoc warnings (explicitly declined).
- Doxygen tooling or generated native HTML (explicitly declined).
- Any behavior change, refactor, or API change. If the documentation pass surfaces a
  genuine bug or a wrong API, it is reported, not fixed here.
- Publishing documentation anywhere other than the existing javadoc jar.
