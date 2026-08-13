# Shared `engine-build` Image Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace this repo's self-built Linux toolchain images with a pinned digest of the shared `ghcr.io/measly-java-learning/engine-build` image.

**Architecture:** A checked-in `.engine-build-image` file holds the digest; CI and the local wrapper both read it, so they cannot drift. The scripts' existing pinned-image assertions are kept verbatim and only their four image-published variables are renamed to the `MEASLY_DJL_` prefix the shared image publishes. `docker/` and `warm-build-image.yml` are then deleted.

**Tech Stack:** Bash, GitHub Actions, Docker, CMake/Ninja, Gradle 9.6.1.

**Spec:** `docs/superpowers/specs/2026-08-13-shared-engine-build-image-design.md`

## Global Constraints

- **The pin, verbatim:** `ghcr.io/measly-java-learning/engine-build@sha256:725884538caa4f7f8444847e34b3928bb90089da95d5b77ce560aa2e624f905b`
- **Pin the digest, never a tag.** `:main` moves on every publish; the `sha-<short>-amd64` / `-arm64` tags are manifest-list children and are implementation detail.
- **The image is a manifest list** covering `linux/amd64` and `linux/arm64`. Never pass `--platform`, never add an arch matrix over the image.
- **The image is public** — no `docker/login-action`, no token, no secret.
- **Exactly four variables are renamed.** `IREE_DJL_PINNED_IMAGE`, `IREE_DJL_NINJA_VERSION`, `IREE_DJL_TOOLSET_VER`, `IREE_DJL_TOOLSET_NEVRA` → the same names under `MEASLY_DJL_`. **Never run a blanket `s/IREE_DJL_/MEASLY_DJL_/`** — 16 other `IREE_DJL_*` names are this project's own knobs, and `IREE_DJL_UBSAN_MODE` in particular would break the UBSan gate's build/test split silently.
- **Image-published values** (for assertions and expected output): `MEASLY_DJL_TOOLSET_VER=14`, `MEASLY_DJL_TOOLSET_NEVRA=14.2.1-11.el8_10`, `MEASLY_DJL_NINJA_VERSION=1.13.0.git.kitware.jobserver-pipe-1`, `JAVA_HOME=/opt/corretto-jdk`.
- **No emoji** in `README.md`, `CONTRIBUTING.md`, `CLAUDE.md`, or anything under `docs/`.
- **`JAVA_HOME` usually needs setting on this host** before any Gradle or native command — a JDK 17 lives at `/usr/lib/jvm/zulu-17-amd64`. Check what exists first.
- **Contain resource usage.** The container wrapper carries `--memory`/`--cpuset-cpus` itself; a host-side `systemd-run` scope does not contain a container. For host-side Gradle, wrap in a memory scope and pass `--no-daemon`, or Gradle escapes it.
- **`./gradlew test` reporting `UP-TO-DATE` has verified nothing.** Use `--rerun-tasks` and check for `N actionable tasks: N executed`.

---

### Task 1: Point local builds at the pinned image

The pin file, the wrapper, and the four-variable rename land together. They are not separable: the shared image publishes `MEASLY_DJL_*` while the local Dockerfiles publish `IREE_DJL_*`, so renaming without switching images (or switching without renaming) leaves every build silently taking its unpinned host fallback path.

**Files:**
- Create: `.engine-build-image`
- Modify: `native/local_build_wrapper.sh:22-35` (drop `PLATFORM`/`IMAGE` derivation and `docker build`)
- Modify: `native/build.sh:74-82` (rename + remediation text)
- Modify: `native/build_qa.sh:71-89` (rename + remediation text)
- Modify: `native/ubsan_gate.sh:60,110` (rename)

**Interfaces:**
- Produces: `.engine-build-image` — a one-line file containing the full digest reference. Read by Task 2's workflow step and by `local_build_wrapper.sh`. No trailing content, no comments; consumers `cat` it directly.

- [ ] **Step 1: Create the pin file**

```bash
cd /home/corey/workspace/djl-iree-engine
printf 'ghcr.io/measly-java-learning/engine-build@sha256:725884538caa4f7f8444847e34b3928bb90089da95d5b77ce560aa2e624f905b\n' > .engine-build-image
```

- [ ] **Step 2: Verify the pin resolves and publishes what the spec claims**

This is the test that the whole migration rests on. Run it before changing any script.

```bash
docker run --rm "$(cat .engine-build-image)" \
  bash -c 'echo "PINNED=$MEASLY_DJL_PINNED_IMAGE VER=$MEASLY_DJL_TOOLSET_VER NEVRA=$MEASLY_DJL_TOOLSET_NEVRA"; \
           echo "NINJA_ENV=$MEASLY_DJL_NINJA_VERSION"; echo "NINJA_REPORTED=$(ninja --version)"; \
           echo "JAVA_HOME=$JAVA_HOME"; test -f "$JAVA_HOME/include/jni.h" && echo "jni.h OK"; \
           rpm -q gcc-toolset-14-libasan-devel-14.2.1-11.el8_10 gcc-toolset-14-libubsan-devel-14.2.1-11.el8_10'
```

Expected — note `NINJA_ENV` and `NINJA_REPORTED` must be **identical**, since `build.sh` compares them with `=`:

```
PINNED=1 VER=14 NEVRA=14.2.1-11.el8_10
NINJA_ENV=1.13.0.git.kitware.jobserver-pipe-1
NINJA_REPORTED=1.13.0.git.kitware.jobserver-pipe-1
JAVA_HOME=/opt/corretto-jdk
jni.h OK
gcc-toolset-14-libasan-devel-14.2.1-11.el8_10
gcc-toolset-14-libubsan-devel-14.2.1-11.el8_10
```

If `NINJA_ENV` and `NINJA_REPORTED` differ, **stop** — `build.sh`'s assertion will fail inside the image and the pin is wrong or the contract doc is stale. Report it rather than loosening the comparison.

- [ ] **Step 3: Rewrite the wrapper's image selection**

In `native/local_build_wrapper.sh`, replace lines 22-35 — the `case "$(uname -m)"` block, the `IMAGE=` line, the `echo "Building ..."` line, and the `docker build` line — with:

```bash
# The pinned shared toolchain image, digest not tag. It is a manifest list covering amd64 and
# arm64, so Docker resolves the architecture -- there is no Dockerfile to pick and no --platform
# to pass. The digest lives in one file so a bump is a one-line change that CI and this wrapper
# pick up together; a second copy here would drift, and the failure mode is CI green while you
# build against a different toolchain.
IMAGE="$(cat "${REPO_ROOT}/.engine-build-image")"
test -n "${IMAGE}" || { echo "empty .engine-build-image" >&2; exit 1; }
```

The header comment at lines 4-7 still says "the manylinux_2_28 container". Update that sentence to name the shared image:

```bash
# Runs a native/ script inside the pinned shared toolchain image (ghcr.io/measly-java-learning/
# engine-build, digest in .engine-build-image) — the environment the GHA workflow uses in CI.
# This is the BLESSED way to run the native scripts: the toolchain matches, and a shim built here
# keeps its glibc-2.28 floor (RHEL8). Running these scripts directly on the host works but breaks
# the floor (build.sh) or collides on a container-made cache (bench/qa wipe theirs).
```

Leave the `docker run` invocation, `IR_MEMORY`, `IR_CPUSET`, `HOST_UID`/`HOST_GID`, and `ITERS`/`WARMUP` exactly as they are.

- [ ] **Step 4: Rename the four variables in `native/build.sh`**

Replace lines 74-82 with — note the remediation text no longer says "rebuild the image", which is impossible now:

```bash
  if [ -n "${MEASLY_DJL_PINNED_IMAGE:-}" ]; then
    # Same guard as native/build_qa.sh: under `set -u` a bare dereference of a companion
    # variable the marker promises would abort with "unbound variable" instead of the
    # BROKEN IMAGE message this branch exists to print.
    : "${MEASLY_DJL_NINJA_VERSION:?MEASLY_DJL_PINNED_IMAGE is set but MEASLY_DJL_NINJA_VERSION is not -- check the pin in .engine-build-image, or unset the marker for a host run}"
    command -v ninja >/dev/null 2>&1 || {
      echo "BROKEN IMAGE: ninja is not on PATH in the pinned image; check the pin in .engine-build-image." >&2; exit 1; }
    [ "$(ninja --version)" = "${MEASLY_DJL_NINJA_VERSION}" ] || {
      echo "BROKEN IMAGE: ninja $(ninja --version), image pins ${MEASLY_DJL_NINJA_VERSION}." >&2; exit 1; }
```

- [ ] **Step 5: Rename the four variables in `native/build_qa.sh`**

Replace lines 71-89 with:

```bash
  # Sanitizer runtimes. In the pinned image these are baked in at an exact NEVRA (the image
  # publishes it as MEASLY_DJL_TOOLSET_NEVRA); a missing one means a BROKEN IMAGE, and
  # installing it here at whatever version dnf offers would silently defeat the pinning the
  # image exists to provide. So: assert inside the image, install only outside it, and never
  # silently.
  if [ -n "${MEASLY_DJL_PINNED_IMAGE:-}" ]; then
    # The marker without its companions means either a hand-set MEASLY_DJL_PINNED_IMAGE or an
    # image predating those variables. Say so: under `set -u` the bare dereference below would
    # abort with bash's "unbound variable", which is the one path through this block that would
    # NOT produce the legible message it exists to give.
    : "${MEASLY_DJL_TOOLSET_VER:?MEASLY_DJL_PINNED_IMAGE is set but MEASLY_DJL_TOOLSET_VER is not -- check the pin in .engine-build-image, or unset the marker for a host run}"
    : "${MEASLY_DJL_TOOLSET_NEVRA:?MEASLY_DJL_PINNED_IMAGE is set but MEASLY_DJL_TOOLSET_NEVRA is not -- check the pin in .engine-build-image, or unset the marker for a host run}"
    for _san in asan ubsan; do
      _pkg="gcc-toolset-${MEASLY_DJL_TOOLSET_VER}-lib${_san}-devel-${MEASLY_DJL_TOOLSET_NEVRA}"
      if ! rpm -q --quiet "${_pkg}"; then
        echo "BROKEN IMAGE: ${_pkg} is not installed at the pinned NEVRA." >&2
        echo "The image is published by measly-java-learning/base-docker-images; check the pin in" >&2
        echo ".engine-build-image rather than installing it here." >&2
        exit 1
      fi
      echo "--- ${_san} runtime present at pinned ${MEASLY_DJL_TOOLSET_NEVRA} ---"
    done
```

Note the `IR_PLATFORM` interpolation in the old message is gone — it named a Dockerfile path that will not exist after Task 3.

- [ ] **Step 6: Rename in `native/ubsan_gate.sh` — the load-bearing one**

Two sites only. Line 60:

```bash
  if [ -n "${MEASLY_DJL_PINNED_IMAGE:-}" ]; then MODE=build; else MODE=all; fi
```

Line 110:

```bash
if [ -n "${MEASLY_DJL_PINNED_IMAGE:-}" ]; then
```

**Do not touch `IREE_DJL_UBSAN_MODE`** on line 58 or in the guidance messages at lines 105 and 115 — it is this project's own knob and keeps its name. If line 60 is missed, the script selects `MODE=all` inside the image and tries to start Gradle under Corretto 8.

- [ ] **Step 7: Verify only the four names moved**

```bash
cd /home/corey/workspace/djl-iree-engine
echo "--- must be empty ---"
grep -rn 'IREE_DJL_PINNED_IMAGE\|IREE_DJL_NINJA_VERSION\|IREE_DJL_TOOLSET_VER\|IREE_DJL_TOOLSET_NEVRA' native/ .github/ CLAUDE.md CONTRIBUTING.md
echo "--- must still be present (project-owned knob) ---"
grep -rc 'IREE_DJL_UBSAN_MODE' native/ubsan_gate.sh
echo "--- must be empty: no project knob got the wrong prefix ---"
grep -rn 'MEASLY_DJL_UBSAN\|MEASLY_DJL_SANITIZE\|MEASLY_DJL_TSAN\|MEASLY_DJL_BUILD_TESTS\|MEASLY_DJL_ADD_VMFB\|MEASLY_DJL_SCALE\|MEASLY_DJL_FIXTURE_DIR\|MEASLY_DJL_PLATFORM\|MEASLY_DJL_TEST_TMP_DIR\|MEASLY_DJL_STATISTICS_ENABLE' .
```

Expected: first and third produce no output; second prints a non-zero count.

- [ ] **Step 8: Negative test — prove the renamed assertion actually fires**

A passing build only shows the assertion did not block. This shows it is wired to the new name:

```bash
docker run --rm -e MEASLY_DJL_NINJA_VERSION=9.9.9-wrong \
  -v "$PWD":/workspace -w /workspace "$(cat .engine-build-image)" \
  /bin/bash /workspace/native/build.sh; echo "exit=$?"
```

Expected: `BROKEN IMAGE: ninja 1.13.0.git.kitware.jobserver-pipe-1, image pins 9.9.9-wrong.` and `exit=1`.

If this exits 0, the rename did not take and `build.sh` fell through to its host branch.

- [ ] **Step 9: Build the shim through the wrapper**

```bash
./native/local_build_wrapper.sh
```

Expected: no `docker build` runs (a pull may run once); `--- Using the image's baked Corretto JDK headers ---`; `JAVA_HOME=/opt/corretto-jdk`; and a final `Artifact: .../src/main/resources/native/linux-x86_64/libiree_djl.so` plus a `Notices:` line.

- [ ] **Step 10: Run the QA gate — exercises both renamed NEVRA assertions**

```bash
./native/local_build_wrapper.sh native/build_qa.sh
```

Expected: `--- asan runtime present at pinned 14.2.1-11.el8_10 ---` and the same line for `ubsan`, then the Catch2 and leak-harness output, ending green.

- [ ] **Step 11: Run the UBSan gate — the sharpest test of the rename**

```bash
./native/local_build_wrapper.sh native/ubsan_gate.sh
```

Expected: the build phase runs and stops with `--- UBSan shim built at ... ; JVM phase skipped ---`. That message proves line 60 selected `MODE=build`, which only happens when `MEASLY_DJL_PINNED_IMAGE` is read under its new name. If Gradle starts and fails under Corretto 8, Step 6 was applied incompletely.

- [ ] **Step 12: Commit**

```bash
git add .engine-build-image native/local_build_wrapper.sh native/build.sh native/build_qa.sh native/ubsan_gate.sh
git commit -m "build: run local builds against the shared engine-build image

The digest lives in .engine-build-image so the wrapper and CI read one
value; a second copy drifts, and the failure mode is CI green while a
contributor builds against a different toolchain.

The pinned-image assertions are unchanged in substance -- only the four
variables the image publishes are renamed to the MEASLY_DJL_ prefix.
The remediation text changes with them: 'rebuild the image from
docker/*.Dockerfile' is no longer possible for a shared image, so the
messages point at the pin instead.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Point CI at the pinned image

**Files:**
- Modify: `.github/workflows/native-build-job.yml:39-85`

**Interfaces:**
- Consumes: `.engine-build-image` from Task 1.
- Produces: `$ENGINE_BUILD_IMAGE` in the job environment, used by all three `docker run` steps.

- [ ] **Step 1: Replace the image-build steps with a digest read**

In `.github/workflows/native-build-job.yml`, delete lines 39-52 — the comment block, `- uses: docker/setup-buildx-action@v4.2.0`, the whole `- name: Build the pinned toolchain image` step including `cache-from: type=gha,scope=${{ matrix.platform }}` — and put this in their place:

```yaml
      # The shared toolchain image, pinned by digest. It is a manifest list covering amd64 and
      # arm64, so each runner resolves its own architecture and no --platform is passed. The
      # package is public: the pull is implicit, with no login step and no secret. Reading the
      # digest from a checked-in file keeps CI and native/local_build_wrapper.sh on one value.
      - name: Resolve the pinned toolchain image
        run: echo "ENGINE_BUILD_IMAGE=$(cat .engine-build-image)" >> "$GITHUB_ENV"
```

- [ ] **Step 2: Point all three `docker run` steps at it**

Three occurrences of the image tag remain, at what were lines 61, 69, and 84. Replace each:

```
            djl-iree-engine-build:${{ matrix.platform }} \
```

with:

```
            "${ENGINE_BUILD_IMAGE}" \
```

Change nothing else in those steps — same `--rm`, same `-v`/`-w`, same `if:` guard on the UBSan step. Leave the `build-iree-shim-windows` job untouched; it has no container path.

- [ ] **Step 3: Verify no image reference survives**

```bash
cd /home/corey/workspace/djl-iree-engine
echo "--- must be empty ---"
grep -n 'djl-iree-engine-build\|setup-buildx\|build-push-action\|cache-from\|cache-to' .github/workflows/native-build-job.yml
echo "--- must be 3 ---"
grep -c 'ENGINE_BUILD_IMAGE' .github/workflows/native-build-job.yml
```

Expected: no output from the first; the second prints `4` (one `echo` writing it, three `docker run` uses).

- [ ] **Step 4: Verify the workflow still parses**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/native-build-job.yml')); print('YAML OK')"
```

Expected: `YAML OK`.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/native-build-job.yml
git commit -m "ci: build against the pinned shared engine-build image

Drops the per-run image build, the buildx setup, and the GHA layer cache.
With no cache there is no cache scope, which retires the scope-collision
failure class tracked in corey-cole/djl-executorch-engine#38.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Delete the per-repo image

**Files:**
- Delete: `docker/linux-x86_64.Dockerfile`, `docker/linux-aarch64.Dockerfile`
- Delete: `.github/workflows/warm-build-image.yml`

- [ ] **Step 1: Confirm nothing still references them**

Run this *before* deleting, so a live reference is found while the files still explain themselves:

```bash
cd /home/corey/workspace/djl-iree-engine
grep -rn 'warm-build-image\|djl-iree-engine-build' --exclude-dir=.git --exclude-dir=build \
  --exclude-dir=native/build --exclude-dir=docs . | grep -v '^./docker/'
```

Expected: no output. Hits under `docs/superpowers/` are excluded deliberately — dated specs and plans are historical records of decisions and are not rewritten.

- [ ] **Step 2: Delete**

```bash
git rm -r docker/ .github/workflows/warm-build-image.yml
```

- [ ] **Step 3: Verify the tree still builds from the deleted state**

The wrapper must not need `docker/` any more. This is the step that proves Task 1 removed the dependency rather than merely stopping using it:

```bash
./native/local_build_wrapper.sh
```

Expected: green, with no reference to a missing Dockerfile.

- [ ] **Step 4: Commit**

```bash
git commit -m "build: delete the per-repo toolchain image

The shared engine-build image replaces both Dockerfiles. warm-build-image.yml
existed only to keep their layers in the GHA cache; with no image build there
is nothing to warm.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Update the prose

Every remaining reference describes a `docker/` directory that no longer exists.

**Files:**
- Modify: `CONTRIBUTING.md:67-71`, `CONTRIBUTING.md:99`
- Modify: `CLAUDE.md` (the Gradle-in-container trip-wire)
- Modify: `native/build.sh:27`, `native/build.sh:43`
- Modify: `.clangd:7`
- Modify: `native/gen_clangd_db.sh:40`

- [ ] **Step 1: `CONTRIBUTING.md` — the "Container build" section**

Replace lines 67-71 (from `The wrapper picks the image from` through `(.github/workflows/warm-build-image.yml).`) with:

```markdown
The wrapper runs the shared `engine-build` image, pinned by digest in `.engine-build-image` and
published by `measly-java-learning/base-docker-images`. It is a manifest list, so the same digest
works on x86_64 and aarch64 — including an ARM laptop — and there is nothing to build: a first
run pays a pull. The image carries the Corretto 8 JNI headers, so the shipped library is always
compiled against the JDK 8 floor whatever the host has. The same digest backs the CI matrix
(`.github/workflows/native-build-job.yml`).
```

Keep the following sentence about `build.sh`, `build_qa.sh` and `ubsan_gate.sh` chowning their outputs — it is still true.

- [ ] **Step 2: `CONTRIBUTING.md` — the clangd bullet**

On line 99, replace `(`docker/<platform>.Dockerfile`)` with `` (`.engine-build-image`) ``, so the sentence reads:

```markdown
  `.so` is always built against the Corretto 8 headers baked into the pinned build image
  (`.engine-build-image`), whatever your host has.
```

- [ ] **Step 3: Add a pin-bump note to `CONTRIBUTING.md`**

Directly after the "Container build" paragraph edited in Step 1, add:

```markdown
To move to a newer toolchain, replace the digest in `.engine-build-image` with the one printed
by the `Publish Engine Images` run in `measly-java-learning/base-docker-images`, and let CI prove
the toolchain still builds. Digests are per-run, not per-commit: re-running the publish workflow
on the same commit yields a different digest, so take it from the run you intend to consume
rather than expecting to re-derive it later.
```

- [ ] **Step 4: `CLAUDE.md` — the Gradle-in-container trip-wire**

In the bullet beginning `**Gradle cannot run in the pinned container.**`, change `` `IREE_DJL_PINNED_IMAGE` `` to `` `MEASLY_DJL_PINNED_IMAGE` ``. Leave `IREE_DJL_UBSAN_MODE=auto` in that same sentence unchanged.

- [ ] **Step 5: `CLAUDE.md` — add the pin trip-wire**

The repo map and trip-wires still imply a local `docker/`. Add this trip-wire after the `iree-runtime-dist` one:

```markdown
- **The Linux toolchain image is external and pinned by digest** in `.engine-build-image`
  (`ghcr.io/measly-java-learning/engine-build`, published by `base-docker-images`). There is no
  `docker/` here to rebuild. It publishes `MEASLY_DJL_PINNED_IMAGE`, `MEASLY_DJL_TOOLSET_VER`,
  `MEASLY_DJL_TOOLSET_NEVRA` and `MEASLY_DJL_NINJA_VERSION`, which `build.sh`, `build_qa.sh` and
  `ubsan_gate.sh` assert against. Every other `IREE_DJL_*` name is this project's own knob —
  a blanket prefix rewrite would rename `IREE_DJL_UBSAN_MODE` and break the UBSan gate silently.
```

- [ ] **Step 6: `native/build.sh` — the two header comments**

Line 27, in the `This script expects:` block:

```bash
# 1. To be running inside the pinned shared toolchain image (see .engine-build-image), which
```

Line 43, the JAVA_HOME fast-path comment:

```bash
  # Fast path: the pinned shared toolchain image (see .engine-build-image) bakes the Corretto 8
```

- [ ] **Step 7: `.clangd` and `native/gen_clangd_db.sh`**

Both explain the separate clangd tree by reference to "the manylinux container". The reasoning is unchanged — a container build writes `/workspace`-absolute paths host clangd cannot resolve — so only the name changes. In `.clangd:7`:

```
# (native/local_build_wrapper.sh) runs native/build.sh in the pinned toolchain container, where
```

In `native/gen_clangd_db.sh:40`:

```bash
# The shipping tree (native/build) is configured by native/build.sh inside the pinned toolchain
```

- [ ] **Step 8: Verify no stale reference survives outside historical records**

```bash
cd /home/corey/workspace/djl-iree-engine
grep -rn 'docker/linux\|docker/<platform>\|docker/\*\.Dockerfile\|warm-build-image\|manylinux container' \
  --exclude-dir=.git --exclude-dir=build --exclude-dir=native/build --exclude-dir=docs .
```

Expected: no output.

- [ ] **Step 9: Check the docs build clean**

```bash
export JAVA_HOME=/usr/lib/jvm/zulu-17-amd64
systemd-run --user --scope -p MemoryMax=8G -- ./gradlew javadoc --no-daemon
```

Expected: `BUILD SUCCESSFUL` with zero warnings. Javadoc warnings do not fail the build by design, so read the output rather than trusting the exit code.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "docs: describe the shared toolchain image

Every reference described a docker/ directory this repo no longer has.
Adds the pin-bump procedure and a trip-wire naming the four image-published
variables, since a blanket IREE_DJL_ prefix rewrite would break the UBSan
gate's mode split.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Full verification

Nothing new is written here. This is the end-to-end gate before opening a PR.

- [ ] **Step 1: Rebuild plain, so no instrumented library is staged**

Tasks 1 and 3 ran the QA and UBSan gates. `build_qa.sh` stages an instrumented library into JVM resources, which breaks `./gradlew test` with "ASan runtime does not come first" — the JVM does not preload sanitizer runtimes.

```bash
./native/local_build_wrapper.sh
```

Expected: green, ending with an `Artifact:` line.

- [ ] **Step 2: Run the JVM suite against the plain library**

```bash
export JAVA_HOME=/usr/lib/jvm/zulu-17-amd64
systemd-run --user --scope -p MemoryMax=8G -- ./gradlew test --rerun-tasks --no-daemon
```

Expected: `BUILD SUCCESSFUL` **and** a line reading `N actionable tasks: N executed`. If it says `up-to-date`, Gradle replayed a cached result and this step has verified nothing — rerun it.

- [ ] **Step 3: Confirm the working tree is clean of build artifacts**

```bash
git status --short
```

Expected: no `.so`, no `native/build-clangd/`, no `native/qa/` or `native/ubsan/` entries staged. These are gitignored; if any appear, do not commit them.

- [ ] **Step 4: Review the complete diff against main**

```bash
git diff main... --stat
```

Expected shape: `.engine-build-image` added; `docker/` and `warm-build-image.yml` deleted; `native-build-job.yml`, three `native/*.sh`, `CONTRIBUTING.md`, `CLAUDE.md`, `.clangd`, `native/gen_clangd_db.sh` modified.

- [ ] **Step 5: Push and open a PR**

```bash
git push -u origin feature/shared-engine-build-image
```

The PR body should note that CI is the first real test of the aarch64 row — every local verification in this plan ran on x86_64, and the manifest list resolving correctly on `ubuntu-24.04-arm` is asserted by the spec but not proven until that job runs.
