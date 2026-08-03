# Windows amd64 support for djl-iree-engine

**Date:** 2026-08-03
**Status:** Partially implemented — §3 (fixture portability fix) has shipped;
the remaining sections (Windows amd64 support proper) are approved but not yet
implemented.

## 1. Goal

Ship `windows-x86_64` as a first-class platform alongside `linux-x86_64` and
`linux-aarch64`: the native shim builds under MSVC, native QA runs, the DLL loads
from Java, Gradle publishes a `windows-x86_64` variant, and CI produces the
artifact.

The engine reaches full parity, not a half-state where a DLL builds but nothing
loads it.

## 2. What already exists

Windows support here is roughly half-built. The following need **no change**:

| Location | State |
| --- | --- |
| `native/build.sh:10-13, 37-45, 60-64, 82-83` | Forks on `MINGW*\|MSYS*`, uses `JAVA_HOME` headers, requires `cl`/`ninja` from the caller's dev shell, stages `windows-x86_64/iree_djl.dll` |
| `native/cmake/IreeRuntimePin.cmake:24-26` | Pins `default_windows-x86_64` |
| `build.gradle.kts:161-162` | `nativeLibName()` returns `iree_djl.dll` for `windows-` |
| `build.gradle.kts:197` | `osFamily` derives from the platform string |
| `native/CMakeLists.txt` (JNI exports) | All 6 entry points carry `JNIEXPORT` |
| `native/core`, `native/jni`, `native/test`, `native/harness` | No POSIX headers anywhere |
| `src/test/resources/models/*.vmfb` | Target `embedded-elf-x86_64` / `x86_64-unknown-unknown-eabi-elf` — OS-agnostic |

The pinned upstream runtime is ready. Its `BUILDINFO` reports `crt=MT`,
`BUILD_SHARED_LIBS=OFF`, `msvc_toolset=19.44.35228`, both the embedded-ELF and
system-library loaders on, and it ships all eight `iree_io_*` /
`iree_modules_io_parameters_*` archives that `native/CMakeLists.txt:95-103`
links, behind the same `IreeRuntimeDistConfig.cmake` surface as Linux.

The build host `winbox` (Windows 11 Pro, Ryzen 7 5800XT, 16 threads, 33 GB free)
carries VS 18.8.0 Community. Inside `Launch-VsDevShell -Arch amd64`: `cl` 14.51,
`link`, `lib`, `dumpbin`, `cmake` 4.3.1-msvc1, `ninja` 1.13.2. Git-Bash present.

Two sibling design docs in `djl-executorch-engine` are the reference for the
MSVC-specific hazards: `2026-07-15-windows-builds-design.md` and
`2026-07-18-windows-static-crt-design.md`.

## 3. Fixture portability fix — lands first, on its own

**Status: implemented.** Plan:
`docs/superpowers/plans/2026-08-03-fixture-portability-fix.md`.
PR: [#8](https://github.com/measly-java-learning/djl-iree-engine/pull/8)
(commit `a8f9114`). Task 2 of that plan — wiring the guard into CI — is also
done, landed in commit `7e6e773` on the same branch.

This is an independent bug fix with no Windows content. It ships as the first
commit, before any Windows work, and stands on its own merits.

`tools/export_scale.sh:42,68` passes `--iree-llvmcpu-target-cpu=generic`
unconditionally, adding a target triple only when `IREE_TARGET_TRIPLE` is set.
`tools/export_add.sh:65-69` diverges: absent a triple it falls back to
`--iree-llvmcpu-target-cpu=host`. The comment at `export_add.sh:60-61` states the
reasoning — "the fixture is only ever run on the machine that produced it" —
which is false. The fixture is committed to the repository and run everywhere.

The consequence is measurable today. `src/test/resources/models/add.vmfb` embeds
`cpu = "tigerlake"` with `+avx512f,+avx512bw,+avx512vl,...`, while every other
fixture (`scale.vmfb`, `scale2.vmfb`, and all three under `aarch64/`) embeds
`cpu = "generic"`. Any x86_64 host without AVX-512 executes illegal
instructions. `winbox` is Zen 3 and has no AVX-512, so this blocks the Windows
build — but the bug is not Windows-specific and the fix is not either.

**Change:** align `export_add.sh` with `export_scale.sh`'s policy — always
`--iree-llvmcpu-target-cpu=generic`, triple optional — and correct the misleading
comment. Regenerate the fixture:

```
IREE_TARGET_TRIPLE=x86_64-unknown-unknown-eabi-elf tools/export_add.sh
```

The triple matches what the existing x86_64 fixtures already carry, so only the
CPU-feature set changes. The compiler is the pinned `iree-base-compiler==3.11.0`
in `.venv`, whose `--version` embeds commit `e4a3b0405d...`, matching the dist's
`runtime_commit` exactly.

**Acceptance:** `bash tools/check_fixture_portability.sh` passes (equivalently,
`grep -a 'cpu = ' src/test/resources/models/add.vmfb` reports `generic`) —
`strings` is deliberately avoided here and in the guard script itself, since
Git-Bash, the shell this doc targets, lacks binutils; `native/build_qa.sh`
stays green on Linux x86_64.

No `windows-x86_64` fixture directory is created. Fixture portability is an
architecture property, not an operating-system one.

**Delivered beyond this section as written:** the implementation also added
`tools/check_fixture_portability.sh`, a permanent guard asserting that every
committed `.vmfb` carries `cpu = "generic"` and an OS-agnostic embedded-ELF
triple whose architecture matches its directory. This section originally
specified only the script fix and the regeneration. The guard was added
because the bug is invisible on AVX-512 hardware — which is how it survived
three prior commits to `add.vmfb` — and because, pre-fix, the next contributor
running `export_add.sh` without `IREE_TARGET_TRIPLE` set would have
reintroduced it silently (that hazard is what motivated the guard; the shipped
script no longer has it — omitting `IREE_TARGET_TRIPLE` now yields `cpu =
"generic"` regardless, verified byte-identical to the explicit-triple output on
x86_64). The guard's ongoing value is catching a fixture regenerated through
some other route, or built on a different host architecture: e.g. running
`export_add.sh` on an aarch64 host with no `IREE_TARGET_TRIPLE` set defaults to
the host triple and would silently overwrite the x86_64 fixture with an
aarch64 one — the guard's per-directory architecture check (see the fixture
portability plan, Task 1) catches exactly this.

## 4. CMake platform seam

`native/CMakeLists.txt:20-26` derives the platform from
`CMAKE_SYSTEM_PROCESSOR` and hard-codes a `linux-` prefix. On Windows,
`CMAKE_SYSTEM_PROCESSOR` is `AMD64`, which matches the existing x86_64 regex —
so the current code does not fail loudly on Windows, it silently resolves the
**Linux** pin row. That is the failure mode this section exists to remove.

Replace with an `IREE_DJL_PLATFORM` cache variable whose default derives from
`WIN32` first, then the processor:

```cmake
if(WIN32)
  set(_IREE_DJL_PLATFORM_DEFAULT "windows-x86_64")
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|ARM64)$")
  set(_IREE_DJL_PLATFORM_DEFAULT "linux-aarch64")
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|amd64|AMD64)$")
  set(_IREE_DJL_PLATFORM_DEFAULT "linux-x86_64")
else()
  message(FATAL_ERROR "djl-iree-engine: unsupported processor '${CMAKE_SYSTEM_PROCESSOR}'")
endif()
set(IREE_DJL_PLATFORM "${_IREE_DJL_PLATFORM_DEFAULT}" CACHE STRING
    "Platform identity: linux-x86_64 | linux-aarch64 | windows-x86_64")
```

Ordering matters: `WIN32` is tested before the processor regex, because `AMD64`
would otherwise win.

A cache variable rather than a plain `set()` buys a **host-test seam**. On a
Linux box we can run `cmake -DIREE_DJL_PLATFORM=windows-x86_64` and assert the
pin resolves and the tarball downloads and verifies, with no Windows machine
involved. That is a cheap pre-flight for this section's correctness and runs
before anyone crosses to `winbox`.

Fixture selection at lines 44-49 keys on the platform string equalling
`linux-aarch64`. `windows-x86_64` correctly falls through to the default x86_64
fixture directory. This is deliberate — see §3 — and gets a comment saying so,
because it otherwise reads as an oversight.

## 5. Static CRT

The pinned Windows tarball is `/MT`. We must match it. A mismatch is **not**
reliably caught at link time: measured upstream in the ExecuTorch work, a `/MD`
consumer linked a `/MT` prefix with no `LNK2005` and not even an `LNK4098`. The
hazard is at runtime — two CRTs, two heaps, corruption when an allocation
crosses the boundary.

```cmake
if(WIN32)
  set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded" CACHE STRING
      "Static CRT: ship without a VC++ redist")
endif()
```

Two placement constraints:

- **Above both `FetchContent_MakeAvailable` calls** (`CMakeLists.txt:34` for the
  runtime, `:109` for Catch2), so it propagates into Catch2's own compilation.
- **A global cache variable**, not a per-target
  `set_property(... MSVC_RUNTIME_LIBRARY ...)`. Catch2 arrives via FetchContent
  and we do not control its targets.

Static linkage also means end users need no VC++ redistributable — relevant
because Windows consumers of this engine are developers on potentially
locked-down workstations.

## 6. Compiler and linker flag forks

Three GCC-specific sites in `native/CMakeLists.txt`:

**`:142` — `-Wl,--exclude-libs,ALL`.** Wrap in `if(NOT WIN32)`. MSVC exports
nothing from a DLL without `__declspec(dllexport)` or a `.def` file, and all six
JNI entry points already carry `JNIEXPORT` (which expands to `dllexport` on
Windows). The Windows default is therefore a *stronger* guarantee than the GNU
flag, achieved with no flag at all.

**`:61-64` — ASan.** MSVC spells it `/fsanitize=address`. Additionally the
Windows branch must set `/INCREMENTAL:NO` on link (MSVC ASan is incompatible
with incremental linking) and must not combine with `/RTC1`. With `/MT` the ASan
runtime links statically, so nothing needs to be on `PATH` at run time.

**`:66-69` — TSan.** MSVC has no ThreadSanitizer. `IREE_DJL_TSAN` becomes a
`FATAL_ERROR` on `WIN32`, rather than emitting flags `cl` rejects with a
confusing diagnostic. The existing mutual-exclusion check at `:57-59` is
unaffected.

## 7. `native/build.sh`

No changes required. The script is already Windows-aware at `:10-13` (host
fork), `:37-45` (JDK headers via `JAVA_HOME`, with a `cygpath` conversion and an
`include/win32/jni_md.h` assertion), `:60-64` (requires the caller to have
activated the MSVC dev shell), and `:82-83` (`windows-x86_64` / `iree_djl.dll`).
The `chown` cleanup at `:16-23` is already gated to the Linux branch.

The plan treats this file as correct and lets the `winbox` run prove it, rather
than pre-emptively editing code that may already work. Any change here is
driven by an observed failure, not by inspection.

## 8. `native/build_qa.sh` Windows fork

Add a host fork mirroring `build.sh:10-13`. The Windows branch:

- Configure `-G Ninja` with `-DIREE_DJL_SANITIZE=ON`.
- Build **`iree_runtime_test` and `iree_params_test` only**. `iree_leak_harness`
  is skipped: MSVC provides ASan but no LeakSanitizer, so the harness is
  structurally Linux-only. This is a real reduction in coverage on Windows and
  is accepted deliberately, not overlooked.
- Run both `.exe` targets.
- Run the CRT gate (§9) against `native/qa`.

Everything Linux-only stays in the Linux branch untouched: the `dnf` ASan
install (`:30-34`), `nproc` (`:36`), the `Unix Makefiles` generator (`:38`), the
`uname -m` fixture fork (`:23-27`), and the three `iree_leak_harness`
invocations (`:50-58`).

## 9. `native/tests/check_windows_crt.sh`

Port `djl-executorch-engine/native/tests/check_windows_crt.sh`, renaming to
`iree_djl.dll` / `native/build` / `native/qa`. It stands in for the ideal test —
loading the DLL on a Windows image that has never had a VC++ redistributable
installed — which we cannot run: no such machine is available, and a dev box
proves nothing because it already has the runtime.

Two traps in the original are preserved verbatim in intent. Both cost the
producer repo real debugging time and are not to be re-derived:

- Flags are passed as `-nologo`, **not** `/nologo`. Under MSYS/Git-Bash a
  leading `/` is path-converted (`/nologo` becomes `C:\Program Files\Git\nologo`)
  and `dumpbin` fails on a garbage filename.
- Assertions are **positive** — a library must *carry* the expected
  `LIBCMT`/`LIBCPMT` marker. An absence-only check ("no `MSVCRT` found") reports
  PASS when `dumpbin` failed to run at all. That exact bug once passed 18
  libraries green while the tool was erroring on every one.

The three-way classification (dumpbin fails → FAIL; no `DEFAULTLIB` directives →
SKIP, counted and printed; has directives → must be static) carries over. The
SKIP bucket exists for import libraries such as the generated `iree_djl.lib`,
which hold import descriptors rather than COFF objects and legitimately state no
CRT opinion. A `/MD` archive does carry `DEFAULTLIB:MSVCRT` and so lands in the
third bucket and fails there.

It runs against **both** trees:

- `native/build` — produces the DLL, whose import table is checked for absence
  of `VCRUNTIME`/`MSVCP`.
- `native/qa` — produces no DLL, but is the tree containing the FetchContent'd
  Catch2, the single most likely place for the `/MT` setting to stop
  propagating. A `/MD` Catch2 inside a `/MT` test executable links with no
  diagnostic at all.

In CI it runs before the artifact upload, so a dynamically-linked DLL is never
published.

## 10. Java: `LibUtils`

Three changes to `src/main/java/org/measly/iree/engine/LibUtils.java`:

1. **`platform()`** gains a `windows-x86_64` branch; the exception message at
   `:41` updates to list it.
2. **`libName(String platform)`** replaces the `LIB_NAME` constant at `:19`,
   returning `iree_djl.dll` for platforms starting with `windows-`. Kept in sync
   with `nativeLibName` in `build.gradle.kts:161`.
3. **Content-addressed cache** replaces the `createTempDirectory` +
   `deleteOnExit` path at `:68-73`, ported from the ExecuTorch engine. Windows
   refuses to delete a loaded DLL, so `deleteOnExit` silently fails and the
   current approach leaks a full copy of the library per JVM run.

Cache design: SHA-256 of the classpath resource is the key, computed in a
hash-only first pass because the key must be known before the path exists. Root
is `%LOCALAPPDATA%\iree-djl` on Windows, else `$XDG_CACHE_HOME` if set, else
`~/.cache/iree-djl`. A concurrent JVM that already mapped the file causes the
replace to fail with `AccessDeniedException`/`FileSystemException`; that is a
handled cache **hit**, not an error.

The class doc at `:13-15` explicitly says the cache was skipped *because* the
skeleton is Linux-only. This spec reverses that premise, so it pays it off
rather than leaving the comment stale.

`platform()`, `libName()`, and `cacheRoot()` are the unit-tested seams. The
extraction and load path needs the real library and real JVM state, and stays
covered by the `winbox` gate (§13).

## 11. Gradle

`build.gradle.kts:158` becomes
`listOf("linux-x86_64", "linux-aarch64", "windows-x86_64")`, and the
`machineArch` mapping at `:199` gains the entry.

Nothing else changes. `nativeLibName` (`:161-162`) and the `osFamily` derivation
(`:197`) already handle the `windows-` prefix, and `nativeJarTasks` (`:166`) and
`nativeVariants` (`:196`) are generic over the platform list. The existing
`doFirst` guards at `:182-187` then automatically fail a release that is missing
the Windows DLL or its third-party notices.

## 12. CI

Replace the placeholder comment at the end of `.github/workflows/native-build-job.yml`
with a sibling job `build-iree-shim-windows` on `windows-2022`.

A **sibling job rather than a matrix row**: the Linux rows are container-based
(manylinux bakes the glibc floor into the image) and Windows has no container.
It uploads under `iree-libs-windows-x86_64`, matching the `iree-libs-*` pattern
that the reusable workflow already declares as its output — so `native-build.yml`
and `publish.yml` consume it **unchanged**. A future macOS platform follows this
same shape.

Steps, mirroring the ExecuTorch job:

1. **Checkout.**
2. **Provenance gate.** Grep the default Windows URL out of
   `IreeRuntimePin.cmake`, download, and `gh attestation verify --repo
   measly-java-learning/iree-runtime-dist`. `URL_HASH` in CMake covers
   integrity; this covers provenance.

   *Simpler than ExecuTorch's:* its pin file carries two Windows rows (`/MD` and
   `/MT`), forcing a `-static` suffix in the grep so it cannot attest a tarball
   the build never links. `IreeRuntimePin.cmake:24-26` has exactly **one**
   Windows row, so the pattern is plain `default-windows-x86_64\.tar\.gz` with no
   disambiguation.
3. **Discover Visual Studio**, once, publishing `VS_PATH` to `$GITHUB_ENV`.
   Discovery is edition-agnostic (`vswhere -latest -products *`) so a runner-image
   edition change cannot silently break the build. Written with **`Add-Content`,
   not `Out-File -Encoding utf8`**: the latter writes a UTF-8 BOM under Windows
   PowerShell 5.1, and a BOM in `GITHUB_ENV` makes the runner parse the name as
   `<BOM>VS_PATH`, so `$env:VS_PATH` is empty in every later step.

   Only *discovery* is hoisted, not *activation*: `Launch-VsDevShell` mutates the
   current process's environment, and each step runs in a fresh process, so every
   step needing `cl` must activate for itself.
4. **Build**, 5. **QA**, 6. **CRT assert** — each activates the dev shell, then
   invokes `native/build.sh` / `native/build_qa.sh` / `native/tests/check_windows_crt.sh`
   through Git-Bash **by explicit path** (`${env:ProgramFiles}\Git\bin\bash.exe`)
   so `PATH` order cannot select WSL's `System32\bash.exe` and run the build in a
   Linux environment with no MSVC toolchain — and **non-login** (`-c`, not `-lc`),
   because a login shell re-sources the profile, resets `PATH`, and drops the VS
   environment. Each checks `$LASTEXITCODE` explicitly.
7. **Upload** `iree-libs-windows-x86_64` with the `**/*.dll` and
   `**/licenses/**` paths.

`JAVA_HOME` binds explicitly to `JAVA_HOME_8_X64` with a fail-fast if unset. The
image's default `JAVA_HOME` is a property GitHub can change, and we want the
oldest supported `jni.h` for the widest runtime compatibility — matching the
Linux rows' Corretto 8 RPM. We compile against `jni.h` only and never link
`libjvm`.

**Accepted risk.** `windows-2022` is on GitHub's retirement track. It is
demonstrably live — the ExecuTorch Windows job ran green on 2026-07-24 — and
migrating to `windows-2025` is a one-line change. This is a deferral, not a debt.

**Accepted divergence.** `winbox` runs VS 18.8.0 / MSVC 14.51; `windows-2022`
runs VS 2022 / MSVC 14.4x; the pinned runtime tarball was built with MSVC
19.44.35228. Local success does not prove CI success. The local gate exists to
catch build-system errors cheaply, not to certify the shipped binary.

## 13. Validation order

The requirement is a successful Windows build before any push to GitHub. This is
satisfiable as stated because `native-build.yml` triggers only on push-to-`main`
and `pull_request` — **pushing a feature branch fires no CI**, so the
clone-on-winbox flow costs nothing and reaches GitHub only as an inert branch.

1. ~~**Fixture fix (§3), own commit.** Regenerate `add.vmfb` as `generic`; confirm
   `native/build.sh` and `native/build_qa.sh` still green on Linux x86_64.~~
   **Done** — PR [#8](https://github.com/measly-java-learning/djl-iree-engine/pull/8),
   commit `a8f9114`. Native QA and `./gradlew test` both green in the
   manylinux_2_28 container.
2. **Host-test seam.** `cmake -DIREE_DJL_PLATFORM=windows-x86_64` on Linux;
   assert the Windows pin row resolves and the tarball downloads and verifies.
   Catches §4 errors without leaving Linux.
3. **Provision `winbox`.** Install JDK 8 (Temurin or Corretto); confirm
   `%JAVA_HOME%\include\win32\jni_md.h` exists. `winbox` currently has Zulu 17
   only; JDK 8 is installed so the local build compiles against the same `jni.h`
   as CI, removing a divergence in the exact artifact under validation.
4. **Push the feature branch.** No CI fires.
5. **Clone on `winbox`; `native/build.sh`** → `iree_djl.dll`.
6. **`native/build_qa.sh`** → both Catch2 suites green, CRT gate green on both
   trees.
7. **`gradlew test` on `winbox`** against the freshly built DLL → proves
   `LibUtils` resolution, the content-addressed cache, and the JNI surface
   end-to-end.
8. **Open the PR.** CI runs for the first time here.

Steps 5-7 are the acceptance gate. A failure at any of them is iterated on
`winbox`; GitHub is not involved.

## 14. Explicitly out of scope

- **A Windows Java job in CI.** The acceptance gate at step 7 is manual and runs
  on `winbox`. ExecuTorch's CI never loads its DLL from Java either. Adding a
  Windows `build-java-package` job would make the guarantee continuous, at the
  cost of Windows runner minutes on every build; deferred.
- **A Windows leak signal.** MSVC has ASan but no LSan. A CRT-debug-heap or UMDH
  port is a different detection model and meaningfully more work.
- **`windows-aarch64`.** No pin row exists upstream.
- **macOS.** Would follow the §12 sibling-job shape when a pin row exists.
- **Migrating to `windows-2025`.** See §12.
