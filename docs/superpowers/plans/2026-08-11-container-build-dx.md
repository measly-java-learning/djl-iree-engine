# Container Build DX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the floating `manylinux_2_28:latest` build container with a pinned, tagged per-platform image that bakes in the JNI headers, ninja, and the ASan runtime — and stop QA runs leaving root-owned files behind.

**Architecture:** A new `docker/` directory holds one Dockerfile per linux platform, each a thin specialization of a dated `manylinux_2_28` base. `native/local_build_wrapper.sh` builds and runs that image locally; CI builds the same Dockerfile with the GitHub Actions layer cache, warmed on `main` by a new workflow. The `native/*.sh` scripts gain fast paths that no-op when the baked image supplies what they would otherwise install, and keep their existing install paths as fallbacks for host-direct runs.

**Tech Stack:** Bash, Docker/BuildKit, GitHub Actions (`docker/setup-buildx-action`, `docker/build-push-action`), CMake/Ninja, AlmaLinux 8 `dnf`.

**Spec:** `docs/superpowers/specs/2026-08-11-container-build-dx-design.md`

## Global Constraints

These apply to every task. Values are copied verbatim from the spec and were verified by running the base image on both architectures — x86_64 locally, aarch64 natively on `radxa-dragon-q6a.local` (not qemu).

- Base image: `quay.io/pypa/manylinux_2_28_x86_64:2026.06.04-1` and `quay.io/pypa/manylinux_2_28_aarch64:2026.06.04-1`. **Never `:latest`.**
- Image tag: `djl-iree-engine-build:<platform>` where `<platform>` is `linux-x86_64` or `linux-aarch64`.
- ASan runtime NEVRA: `gcc-toolset-14-libasan-devel-14.2.1-11.el8_10` (both arches). Matches the base's own gcc `14.2.1-11` — a libasan from a different toolset revision than the gcc that emitted the instrumentation is the classic source of confusing ASan link errors.
- ninja: `1.13.0`, installed into `/opt/python/cp312-cp312`.
- Corretto 8: version `8.502.07.1`, fetched from the **versioned** resource URL, never the `latest` redirect.
  - x86_64 URL: `https://corretto.aws/downloads/resources/8.502.07.1/java-1.8.0-amazon-corretto-devel-1.8.0_502.b07-1.x86_64.rpm`
  - x86_64 sha256: `8663ad535a10f8418ce6c3b97108e2dbbe49aef7c317eaef9f08f1d25d5a7286`
  - aarch64 URL: `https://corretto.aws/downloads/resources/8.502.07.1/java-1.8.0-amazon-corretto-devel-1.8.0_502.b07-1.aarch64.rpm`
  - aarch64 sha256: `ce812e8ab602fd999d2576ee4ae0eb82116017c7304dfb91601b5e312a6fc48c`
  - These sha256s were computed from the downloaded artifacts. Corretto's `latest_checksum` endpoint serves MD5, so do not expect to find them published upstream.
- `JAVA_HOME` inside the image: `/opt/corretto-jdk` (a symlink resolved at image-build time — never hardcode the extraction path).
- Every Windows code path in `native/build.sh`, `native/build_qa.sh`, and the `build-iree-shim-windows` job in `native-build-job.yml` must remain byte-identical. This work is linux-only.
- Every pin gets a comment stating what it is for and the command to re-resolve it (`dnf list --showduplicates <pkg>`).
- The aarch64 image cannot be built on an x86_64 host. Verify it over SSH on `radxa-dragon-q6a.local`, which has Docker 29.1.3 and needs no password. It already holds a git checkout at `/home/radxa/workspace/djl-iree-engine` (user `radxa`, currently on `main` at `1f5b5c5`) — ship branches there with `git bundle` + `scp` + `git fetch`, never `rsync`, so the remote stays a real checkout instead of a copy that drifts.

---

### Task 1: Shared chown-on-exit helper

The bug: `native/build.sh` chowns its outputs back to the host user on exit, but `native/build_qa.sh` does not — so every wrapper-driven QA run leaves `native/qa/` owned by root. Fix it with one helper both scripts source, rather than a copied trap that will drift.

This task changes no container behaviour, so it is verified against the **current** image and is independently shippable.

**Files:**
- Create: `native/container_env.sh`
- Modify: `native/build.sh:15-23` (replace the inline `cleanup`/`trap`)
- Modify: `native/build_qa.sh` (add sourcing + registration in the Linux branch)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `ir_chown_outputs_on_exit <path>...` — a shell function that installs an `EXIT` trap chowning the given paths (recursively) to `$HOST_UID:$HOST_GID`. No-op when `HOST_UID` is unset. Preserves the script's original exit status. Arguments may contain globs, which are expanded at exit time, not registration time. Task 4 refers to this name in a comment.

- [ ] **Step 1: Prove the bug reproduces**

Run the QA gate through the wrapper on the current tree, then inspect ownership:

```bash
./native/local_build_wrapper.sh native/build_qa.sh
ls -ld native/qa && stat -c '%U:%G' native/qa
```

Expected: `native/qa` is owned by `root:root`. Record the output — this is the failing test.

For contrast, confirm the shim build already does the right thing:

```bash
./native/local_build_wrapper.sh
stat -c '%U:%G' native/build
```

Expected: your own user, not root.

- [ ] **Step 2: Write the helper**

Create `native/container_env.sh`:

```bash
#!/usr/bin/env bash
# Ownership handling for scripts run under native/local_build_wrapper.sh.
#
# The wrapper bind-mounts the repo into a container that runs as root, so anything a build or QA
# run creates comes back root-owned on the host. The wrapper passes HOST_UID/HOST_GID so we can
# hand the outputs back on the way out.
#
# Sourced, never executed. Both native/build.sh and native/build_qa.sh use it; keeping it in one
# place is the point, because two copies of this trap will drift.

# Paths registered by ir_chown_outputs_on_exit, chowned by ir_chown_cleanup.
IR_CHOWN_PATHS=()

ir_chown_cleanup() {
  rc=$?
  if [ -n "${HOST_UID:-}" ] && [ "${#IR_CHOWN_PATHS[@]}" -gt 0 ]; then
    # Deliberately unquoted: entries may be globs (src/main/resources/native/linux*) that must
    # expand HERE, at exit, rather than at registration time when the dirs may not exist yet.
    # `|| true` so a chown failure never masks the script's real exit status.
    chown -R "${HOST_UID}:${HOST_GID}" ${IR_CHOWN_PATHS[@]} 2>/dev/null || true
  fi
  exit "$rc"
}

# Usage: ir_chown_outputs_on_exit native/build 'src/main/resources/native/linux*'
# Quote glob arguments at the call site so they survive to exit-time expansion.
ir_chown_outputs_on_exit() {
  IR_CHOWN_PATHS=("$@")
  trap ir_chown_cleanup EXIT
}
```

- [ ] **Step 3: Wire it into `native/build.sh`**

Delete lines 15-23 (the `# Container bind-mount outputs...` comment, the `cleanup()` function, and the `[ "${IR_HOST_OS}" = "linux" ] && trap cleanup EXIT` line).

The `here`/`build_dir` assignments currently sit *below* the trap. Move the registration to just after them so the helper is given a resolved path, not an empty variable. The region becomes:

```bash
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
build_dir="${here}/build"
build_type="${BUILD_TYPE:-RelWithDebInfo}"

# shellcheck source=native/container_env.sh
. "${here}/container_env.sh"
[ "${IR_HOST_OS}" = "linux" ] && \
  ir_chown_outputs_on_exit "${build_dir}" 'src/main/resources/native/linux*'
```

This is not a behaviour change: the old trap read `${build_dir}` at exit time, by which point it was set. Registering after assignment just removes the ordering subtlety.

Note the `&&` idiom is preserved from the original, but the original's placement made it the last command of a `set -e` script section; here it is followed by more code, so a non-linux host leaves the `&&` returning 1. Guard with an explicit `if` instead to avoid tripping `set -e`:

```bash
if [ "${IR_HOST_OS}" = "linux" ]; then
  ir_chown_outputs_on_exit "${build_dir}" 'src/main/resources/native/linux*'
fi
```

Use the `if` form.

- [ ] **Step 4: Wire it into `native/build_qa.sh`**

`build_qa.sh` already computes `REPO_ROOT` and `cd`s to it near the top. Immediately after the `IR_HOST_OS` case block, add:

```bash
# shellcheck source=native/container_env.sh
. "${REPO_ROOT}/native/container_env.sh"
if [ "${IR_HOST_OS}" = "linux" ]; then
  # native/qa is this script's only output tree. Without this, wrapper-driven QA runs leave it
  # root-owned on the host — the exact gap native/build.sh's trap has always covered for builds.
  ir_chown_outputs_on_exit native/qa
fi
```

Placing it after the OS fork keeps Windows untouched, and placing it before the `rm -rf native/qa` means an interrupted run still hands back whatever exists.

- [ ] **Step 5: Verify the fix**

```bash
sudo rm -rf native/qa
./native/local_build_wrapper.sh native/build_qa.sh
stat -c '%U:%G' native/qa
```

Expected: QA prints `--- native QA PASS ---`, and `native/qa` is owned by your user.

Confirm the build path did not regress:

```bash
./native/local_build_wrapper.sh
stat -c '%U:%G' native/build src/main/resources/native/linux-x86_64
```

Expected: both owned by your user.

Confirm the exit status still propagates — the trap must not swallow failures:

```bash
HOST_UID=$(id -u) HOST_GID=$(id -g) bash -c '. native/container_env.sh; ir_chown_outputs_on_exit native/qa; exit 7'; echo "status=$?"
```

Expected: `status=7`.

- [ ] **Step 6: Commit**

```bash
git add native/container_env.sh native/build.sh native/build_qa.sh
git commit -m "fix(native): chown QA outputs back to the host user

build_qa.sh had no exit trap, so every wrapper-driven QA run left native/qa
root-owned. Extract build.sh's trap into native/container_env.sh and source it
from both scripts, so the two cannot drift."
```

---

### Task 2: The pinned toolchain images

Create the `docker/` directory. The filename encodes the platform token so the image tag, its Dockerfile, and the artifact platform stay one name — matching the layout in the sibling `djl-executorch-engine` and `iree-runtime-dist` repos.

Both Dockerfiles land in one task: they differ only by architecture, and splitting them invites the two from drifting on a shared pin.

**Files:**
- Create: `docker/linux-x86_64.Dockerfile`
- Create: `docker/linux-aarch64.Dockerfile`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: images tagged `djl-iree-engine-build:linux-x86_64` and `djl-iree-engine-build:linux-aarch64`, each guaranteeing `JAVA_HOME=/opt/corretto-jdk` with `$JAVA_HOME/include/linux/jni_md.h` present, `ninja` on `PATH`, and `gcc-toolset-14-libasan-devel` installed. Tasks 4, 5, 6, and 7 all depend on these exact tags and paths.

- [ ] **Step 1: Establish the baseline the image must beat**

Confirm what the stock base image lacks, so the assertions in Step 4 are testing something real:

```bash
docker run --rm quay.io/pypa/manylinux_2_28_x86_64:2026.06.04-1 \
  bash -c 'command -v ninja || echo "NO NINJA"; echo "JAVA_HOME=${JAVA_HOME:-unset}"'
```

Expected: `NO NINJA` and `JAVA_HOME=unset`. This is the failing test.

- [ ] **Step 2: Write `docker/linux-x86_64.Dockerfile`**

```dockerfile
# Build container for the `linux-x86_64` platform. The filename encodes the platform token so the
# image tag, its Dockerfile, and the artifact platform stay one name. Same layout as
# djl-executorch-engine's docker/ and measly-java-learning/iree-runtime-dist's docker/.
#
# Thin specialization of the manylinux_2_28 build container: the base image is already the thing
# that bakes our glibc-2.28 floor, and this only adds what every build and QA run would otherwise
# install from scratch — the JNI headers, ninja, and the ASan runtime.
#
# Pinned to a dated tag, not `latest`. A floating base leaves no way to tell "the image changed"
# from "our code changed". The sibling djl-executorch-engine repo lost real time to exactly that:
# a 2026-08-05 base rebuild dropped a package and broke builds on a tree that had compiled clean
# hours earlier.
FROM quay.io/pypa/manylinux_2_28_x86_64:2026.06.04-1

# Pinned to an exact NEVRA rather than a bare package name, so a repo update to a newer default
# module stream cannot silently change what this image bakes in. If AlmaLinux retires this build,
# this RUN fails loudly (dnf cannot resolve the NEVRA) instead of drifting.
# Re-resolve with: dnf list --showduplicates gcc-toolset-14-libasan-devel
#
# gcc-toolset-14-libasan-devel  the ASan runtime for native/build_qa.sh. Held to 14.2.1-11.el8_10
#                      to match the base image's own compiler exactly (gcc 14.2.1-11); a libasan
#                      from a different toolset revision than the gcc that emitted the
#                      instrumentation is the classic source of confusing ASan link errors.
#                      build_qa.sh still derives the toolset number from `gcc -dumpversion` and
#                      dnf-installs as a fallback, which is a fast no-op here and keeps host runs
#                      working.
RUN dnf install -y \
      gcc-toolset-14-libasan-devel-14.2.1-11.el8_10 \
    && dnf clean all \
    && rm -rf /var/cache/dnf

# The base ships no ninja, and native/build.sh configures with -G Ninja, so every build paid a
# `pip install ninja` before this. cp312 is the interpreter native/build.sh already puts on PATH.
# Symlinked into /usr/local/bin so `ninja` resolves however the container is entered, not only
# after build.sh's PATH line.
RUN /opt/python/cp312-cp312/bin/pip install --no-cache-dir ninja==1.13.0 \
    && ln -s /opt/python/cp312-cp312/bin/ninja /usr/local/bin/ninja

# JNI headers. We compile against jni.h and never link libjvm, so this is a headers-only need —
# but it used to cost a 113 MB RPM download on every single build, in CI and locally.
#
# The VERSIONED url, never https://corretto.aws/downloads/latest/... — that redirect is exactly
# what makes a layer non-reproducible. sha256 computed from the artifact; Corretto's
# latest_checksum endpoint serves MD5, so do not expect to find this published upstream.
# Corretto 8 (not a newer JDK) for the oldest supported jni.h and the widest runtime
# compatibility, matching what the Windows job binds via JAVA_HOME_8_X64.
ARG CORRETTO_URL=https://corretto.aws/downloads/resources/8.502.07.1/java-1.8.0-amazon-corretto-devel-1.8.0_502.b07-1.x86_64.rpm
ARG CORRETTO_SHA256=8663ad535a10f8418ce6c3b97108e2dbbe49aef7c317eaef9f08f1d25d5a7286

# rpm2archive, not rpm2cpio: this image ships no cpio. The find-then-symlink indirection is
# deliberate — hardcoding the current extraction path
# (/opt/corretto/usr/lib/jvm/java-1.8.0-amazon-corretto) means a Corretto directory rename yields
# an image with a dangling JAVA_HOME and no error until a shim build dies deep in a CMake
# configure. Everything is removed in the same layer so the RPM is not carried in the image.
RUN curl -fL -o /tmp/corretto.rpm "${CORRETTO_URL}" \
    && echo "${CORRETTO_SHA256}  /tmp/corretto.rpm" | sha256sum -c - \
    && rpm2archive /tmp/corretto.rpm \
    && mkdir -p /opt/corretto \
    && tar -C /opt/corretto -xzf /tmp/corretto.rpm.tgz \
    && jni_h="$(find /opt/corretto -path '*/include/jni.h' | head -1)" \
    && test -n "${jni_h}" || { echo "no include/jni.h found in the extracted Corretto RPM"; exit 1; } \
    && ln -s "${jni_h%/include/jni.h}" /opt/corretto-jdk \
    && rm -f /tmp/corretto.rpm /tmp/corretto.rpm.tgz

ENV JAVA_HOME=/opt/corretto-jdk

# Fail at image-build time, not three steps into a shim build, if a pin ever stops delivering what
# it is here for.
RUN test -f "${JAVA_HOME}/include/jni.h" \
      || { echo "JAVA_HOME=${JAVA_HOME} has no include/jni.h"; exit 1; } \
    && test -f "${JAVA_HOME}/include/linux/jni_md.h" \
      || { echo "JAVA_HOME=${JAVA_HOME} has no include/linux/jni_md.h"; exit 1; } \
    && command -v ninja >/dev/null \
      || { echo "ninja is not on PATH"; exit 1; } \
    && ninja --version
```

Careful with the `&&`/`||` chain in the `find` step: `test -n "${jni_h}" || { ...; exit 1; }` inside a `&&` chain works, but the shell precedence makes it easy to get wrong. If the build fails oddly there, split that RUN into a small heredoc script instead of fighting the one-liner.

- [ ] **Step 3: Write `docker/linux-aarch64.Dockerfile`**

Identical to the x86_64 file with three substitutions, plus its own header comment. Do not simply say "same as x86_64" in the file — write it out.

- `FROM quay.io/pypa/manylinux_2_28_aarch64:2026.06.04-1`
- `ARG CORRETTO_URL=https://corretto.aws/downloads/resources/8.502.07.1/java-1.8.0-amazon-corretto-devel-1.8.0_502.b07-1.aarch64.rpm`
- `ARG CORRETTO_SHA256=ce812e8ab602fd999d2576ee4ae0eb82116017c7304dfb91601b5e312a6fc48c`

The `gcc-toolset-14-libasan-devel-14.2.1-11.el8_10` and `ninja==1.13.0` pins are unchanged — both were confirmed to resolve on native aarch64 hardware. Add this to the header comment:

```dockerfile
# NEVRAs verified 2026-08-11 by running this exact base tag on a native aarch64 host (not qemu):
# gcc is 14.2.1-11, and libasan resolves to the same version as the x86_64 image. That agreement
# is a convenience, not a rule — re-resolve per arch with `dnf list --showduplicates <pkg>` on the
# next base bump rather than copying the x86_64 values.
```

Also note in the header that this image is what the live `linux-aarch64` matrix row builds inside, running on `ubuntu-24.04-arm`, so the shim and its QA are compiled and sanitizer-tested on native aarch64 hardware.

- [ ] **Step 4: Build and verify the x86_64 image**

```bash
docker build -t djl-iree-engine-build:linux-x86_64 -f docker/linux-x86_64.Dockerfile docker/
docker run --rm djl-iree-engine-build:linux-x86_64 bash -c '
  set -e
  echo "JAVA_HOME=${JAVA_HOME}"
  test -f "${JAVA_HOME}/include/linux/jni_md.h" && echo "jni_md.h OK"
  ninja --version
  rpm -q gcc-toolset-14-libasan-devel
  test ! -f /tmp/corretto.rpm && echo "rpm not left in image"
'
```

Expected: `JAVA_HOME=/opt/corretto-jdk`, `jni_md.h OK`, `1.13.0`, `gcc-toolset-14-libasan-devel-14.2.1-11.el8_10.x86_64`, `rpm not left in image`.

- [ ] **Step 5: Build and verify the aarch64 image on native hardware**

The aarch64 image cannot be built on an x86_64 host. `radxa-dragon-q6a.local` already has a
checkout at `/home/radxa/workspace/djl-iree-engine`, so ship the branch with `git bundle` — an
incremental bundle carries only the commits the remote lacks, and keeps the remote a real git
checkout rather than an rsync'd copy that drifts.

```bash
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
git bundle create /tmp/dx.bundle "main..${BRANCH}"
scp /tmp/dx.bundle radxa-dragon-q6a.local:/tmp/dx.bundle
ssh radxa-dragon-q6a.local "
  cd /home/radxa/workspace/djl-iree-engine &&
  git fetch /tmp/dx.bundle '${BRANCH}:${BRANCH}' &&
  git checkout '${BRANCH}'"
```

If `git fetch` rejects the bundle for a missing prerequisite, the remote's `main` is behind — push
your `main` and `git pull` there first, then re-bundle.

Then build and check:

```bash
ssh radxa-dragon-q6a.local '
  cd /home/radxa/workspace/djl-iree-engine &&
  docker build -t djl-iree-engine-build:linux-aarch64 -f docker/linux-aarch64.Dockerfile docker/ &&
  docker run --rm djl-iree-engine-build:linux-aarch64 bash -c "
    set -e
    echo JAVA_HOME=\$JAVA_HOME
    test -f \$JAVA_HOME/include/linux/jni_md.h && echo jni_md.h OK
    ninja --version
    rpm -q gcc-toolset-14-libasan-devel
  "'
```

Expected: the same five lines, with `.aarch64` on the libasan NEVRA.

- [ ] **Step 6: Confirm the second build is fully cached**

```bash
docker build -t djl-iree-engine-build:linux-x86_64 -f docker/linux-x86_64.Dockerfile docker/ 2>&1 | tail -20
```

Expected: every step reports `CACHED`. This is what makes the wrapper change in Task 5 cheap.

- [ ] **Step 7: Commit**

```bash
git add docker/linux-x86_64.Dockerfile docker/linux-aarch64.Dockerfile
git commit -m "build(docker): pinned per-platform toolchain images

Dated manylinux_2_28 base plus the three things every build previously
installed from scratch: Corretto 8 JNI headers, ninja, and the gcc-toolset-14
ASan runtime. All pins verified on both arches against the real base image."
```

---

### Task 3: Script fast paths for the baked image

Make `build.sh` and `build_qa.sh` skip the work the image already did, while keeping their existing install paths as fallbacks so host-direct runs and stale images still work.

**Files:**
- Modify: `native/build.sh` — the Linux (`else`) branch of the JDK block, and the Linux branch of the toolchain-versions block. Locate by content, not line number: Task 1 shifted this file.
- Modify: `native/build_qa.sh` — the `# QA is the only ASan consumer` block in the Linux branch.

**Interfaces:**
- Consumes: `djl-iree-engine-build:<platform>` from Task 2, specifically `JAVA_HOME=/opt/corretto-jdk` and `ninja` on `PATH`.
- Produces: `native/build.sh` and `native/build_qa.sh` that run correctly both inside the pinned image (no downloads) and on a bare base image (falling back to the current behaviour). Task 5 relies on this.

- [ ] **Step 1: Prove the scripts still do redundant work in the new image**

```bash
docker run --rm -v "$PWD":/workspace -w /workspace \
  -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
  djl-iree-engine-build:linux-x86_64 \
  /bin/bash /workspace/native/build.sh 2>&1 | grep -E 'Extracting Corretto|Collecting ninja|Requirement already'
```

Expected: `--- Extracting Corretto JDK headers ---` still appears, and pip still runs (even if it reports the requirement is already satisfied). That redundancy is what this task removes.

- [ ] **Step 2: Add the JDK fast path to `native/build.sh`**

Replace the `else` branch of the JDK block (the Linux branch, currently starting `echo "--- Extracting Corretto JDK headers..."`) with:

```bash
else
  # Fast path: the pinned toolchain image (docker/linux-*.Dockerfile) bakes the Corretto 8 headers
  # in and exports JAVA_HOME, so there is nothing to extract. The fallback below is for running
  # this script directly on a host, or inside a bare manylinux base — in which case you supply
  # amazon-corretto-linux-jdk.rpm at the repo root yourself.
  if [ -n "${JAVA_HOME:-}" ] && [ -f "${JAVA_HOME}/include/linux/jni_md.h" ]; then
    echo "--- Using the image's baked Corretto JDK headers (headers-only; we never link libjvm) ---"
    export JAVA_HOME
  else
    echo "--- Extracting Corretto JDK headers (headers-only; we never link libjvm) ---"
    JDK_EXTRACT=/opt/corretto
    mkdir -p "${JDK_EXTRACT}"
    cp /workspace/amazon-corretto-linux-jdk.rpm /tmp/corretto.rpm
    rpm2archive /tmp/corretto.rpm            # -> /tmp/corretto.rpm.tgz (no cpio in this image)
    tar -C "${JDK_EXTRACT}" -xzf /tmp/corretto.rpm.tgz
    JNI_H="$(find "${JDK_EXTRACT}" -path '*/include/jni.h' | head -1)"
    export JAVA_HOME="${JNI_H%/include/jni.h}"
    test -f "${JAVA_HOME}/include/linux/jni_md.h" \
      || { echo "JDK headers not found under JAVA_HOME=${JAVA_HOME}"; exit 1; }
  fi
  echo "JAVA_HOME=${JAVA_HOME}"
fi
```

Also update the "This script expects:" comment block above it — item 2 currently reads "The Corretto RPM downloaded to /workspace", which is no longer true for the blessed path. Replace with:

```bash
# This script expects:
# 1. To be running inside the pinned toolchain image (docker/linux-<platform>.Dockerfile), which
#    bakes the glibc-2.28 floor via its manylinux_2_28 base and supplies JAVA_HOME + ninja
# 2. Failing that, a manylinux_2_28 base with amazon-corretto-linux-jdk.rpm at /workspace
```

- [ ] **Step 3: Add the ninja fast path to `native/build.sh`**

In the Linux branch of the toolchain block, replace `pip install ninja` with:

```bash
  # The pinned image bakes ninja in; only install when running on a bare base or a host.
  command -v ninja >/dev/null 2>&1 || pip install ninja
```

Leave the `export PATH="/opt/python/cp312-cp312/bin:${PATH}"` line above it alone — the image installs ninja into that same interpreter, and the PATH line is still what the fallback needs.

- [ ] **Step 4: Add the ASan fast path to `native/build_qa.sh`**

Replace the ASan install block with:

```bash
  # QA is the only ASan consumer. The pinned toolchain image bakes the runtime in at the base
  # image's own compiler revision; this dnf call is the fallback for host runs and bare bases.
  TOOLSET_VER="$(gcc -dumpversion | cut -d. -f1)"
  if rpm -q --quiet "gcc-toolset-${TOOLSET_VER}-libasan-devel"; then
    echo "--- ASan runtime already present (gcc-toolset-${TOOLSET_VER}-libasan-devel) ---"
  elif command -v dnf >/dev/null 2>&1; then
    echo "--- Installing ASan runtime (dnf), may appear to hang ---"
    dnf install -y -q "gcc-toolset-${TOOLSET_VER}-libasan-devel" || true
  fi
```

`rpm -q --quiet` is safe here: every path this branch runs on is RPM-based (manylinux_2_28 is AlmaLinux 8). If `rpm` is absent the command fails, the `elif` runs, and behaviour is exactly as before.

- [ ] **Step 5: Verify no redundant work in the pinned image**

```bash
docker run --rm -v "$PWD":/workspace -w /workspace \
  -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
  djl-iree-engine-build:linux-x86_64 \
  /bin/bash /workspace/native/build.sh 2>&1 | tee /tmp/build-fast.log | tail -5
grep -c 'Extracting Corretto' /tmp/build-fast.log
grep -c 'baked Corretto' /tmp/build-fast.log
```

Expected: build succeeds and prints an `Artifact:` line; `Extracting Corretto` count is `0`; `baked Corretto` count is `1`.

- [ ] **Step 6: Verify the fallback still works on a bare base**

This is the regression that matters — do not skip it. The repo root must still hold `amazon-corretto-linux-jdk.rpm` for this (it is gitignored; re-download it if absent).

```bash
docker run --rm -v "$PWD":/workspace -w /workspace \
  -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
  quay.io/pypa/manylinux_2_28_x86_64:2026.06.04-1 \
  /bin/bash /workspace/native/build.sh 2>&1 | grep -E 'Extracting Corretto|JAVA_HOME=|Artifact:'
```

Expected: `--- Extracting Corretto JDK headers ---` appears, `JAVA_HOME=/opt/corretto/usr/lib/jvm/...`, and an `Artifact:` line. The fallback path is intact.

- [ ] **Step 7: Verify QA in both images**

```bash
docker run --rm -v "$PWD":/workspace -w /workspace \
  -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
  djl-iree-engine-build:linux-x86_64 \
  /bin/bash /workspace/native/build_qa.sh 2>&1 | grep -E 'ASan runtime|native QA PASS'
```

Expected: `--- ASan runtime already present (gcc-toolset-14-libasan-devel) ---` and `--- native QA PASS ---`.

- [ ] **Step 8: Commit**

```bash
git add native/build.sh native/build_qa.sh
git commit -m "build(native): skip installs the pinned image already baked

build.sh uses a baked JAVA_HOME when the image supplies one and skips the pip
install when ninja is on PATH; build_qa.sh skips dnf when libasan is already
present. Every install path is kept as a fallback for host-direct runs and bare
manylinux bases, both of which are verified."
```

---

### Task 4: Point the local wrapper at the pinned image

`local_build_wrapper.sh` calls itself "the BLESSED way to run the native scripts" because the toolchain matches CI. Right now it runs `manylinux_2_28:latest`, which CI will no longer use — so this change is what keeps that claim true.

**Files:**
- Modify: `native/local_build_wrapper.sh:20-49`
- Modify: `README.md:225-229` (the clangd section's Corretto sentence)

**Interfaces:**
- Consumes: `djl-iree-engine-build:<platform>` from Task 2; the fast paths from Task 3; `ir_chown_outputs_on_exit` behaviour from Task 1.
- Produces: a wrapper that builds the image before running it. No later task depends on it.

- [ ] **Step 1: Confirm the wrapper still runs the floating base**

```bash
grep -n 'manylinux_2_28\|corretto' native/local_build_wrapper.sh
```

Expected: a `:latest` image reference and the Corretto download block are both present. This is the failing test.

- [ ] **Step 2: Replace the arch resolution and download block**

Replace the arch `case` block and the entire `if [ ! -f "${REPO_ROOT}/amazon-corretto-linux-jdk.rpm" ]` block with:

```bash
# One platform token drives the Dockerfile name, the image tag, and the artifact platform.
case "$(uname -m)" in
  x86_64|amd64)  PLATFORM="linux-x86_64" ;;
  aarch64|arm64) PLATFORM="linux-aarch64" ;;
  *) echo "unsupported arch: $(uname -m)" >&2; exit 1 ;;
esac
IMAGE="djl-iree-engine-build:${PLATFORM}"

# Build the pinned toolchain image the CI matrix also builds (see docker/ and
# .github/workflows/warm-build-image.yml). Docker's layer cache makes this a near-instant no-op
# after the first run; the first run pays the JDK/ninja/libasan cost once instead of every build.
# No Corretto download here any more — the image carries the JNI headers.
echo "Building ${IMAGE} (cached after the first run)"
docker build -t "${IMAGE}" -f "${REPO_ROOT}/docker/${PLATFORM}.Dockerfile" "${REPO_ROOT}/docker"
```

- [ ] **Step 3: Point `docker run` at the image**

Replace the final image argument `"quay.io/pypa/manylinux_2_28_${ML_IMAGE_ARCH}:latest"` with `"${IMAGE}"`, and replace the comment above the `docker run` with:

```bash
# The image's manylinux_2_28 base holds the glibc >= 2.28 floor, so the shim links the fetched
# runtime at that floor. ITERS/WARMUP forward to the bench/QA scripts when set (harmless for
# build.sh, which ignores them).
```

The stray `ET_RUNTIME_VARIANT` sentence in the old comment is a copy-paste from the ExecuTorch repo and applies to nothing here — drop it rather than carrying it forward.

- [ ] **Step 4: Fix the now-false ownership note in the header comment**

The header currently reads:

```
# Note: only build.sh chowns its outputs back to you; bench/qa/variants leave root-owned dirs
# (see the "Container file ownership" note in README.md).
```

Replace with:

```
# build.sh and build_qa.sh both chown their outputs back to you on exit (see
# native/container_env.sh). Other native/ scripts run through this wrapper do not yet, and will
# leave root-owned dirs behind.
```

- [ ] **Step 5: Update the README's Corretto sentence**

`README.md` line ~228 reads "the shipped `.so` is always built against the Corretto 8 headers `native/build.sh` extracts in the container, whatever your host has." Replace the clause with: "the shipped `.so` is always built against the Corretto 8 headers baked into the pinned build image (`docker/linux-<platform>.Dockerfile`), whatever your host has."

- [ ] **Step 6: Verify end to end**

```bash
sudo rm -rf native/build native/qa src/main/resources/native/linux-x86_64
./native/local_build_wrapper.sh
stat -c '%U:%G' native/build src/main/resources/native/linux-x86_64
./native/local_build_wrapper.sh native/build_qa.sh
stat -c '%U:%G' native/qa
```

Expected: both runs succeed, and all three paths are owned by your user.

- [ ] **Step 7: Verify the second run is cheap**

```bash
time ./native/local_build_wrapper.sh 2>&1 | grep -E 'CACHED|Extracting Corretto|Collecting ninja' | head
```

Expected: `CACHED` lines from the image build, and no `Extracting Corretto` or `Collecting ninja`.

- [ ] **Step 8: Verify the whole wrapper flow on aarch64**

Ship the branch the same way as Task 2 Step 5 (`git bundle` into the existing checkout at
`/home/radxa/workspace/djl-iree-engine`):

```bash
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
git bundle create /tmp/dx.bundle "main..${BRANCH}"
scp /tmp/dx.bundle radxa-dragon-q6a.local:/tmp/dx.bundle
ssh radxa-dragon-q6a.local "
  cd /home/radxa/workspace/djl-iree-engine &&
  git fetch /tmp/dx.bundle '+${BRANCH}:${BRANCH}' &&
  git checkout '${BRANCH}' && git reset --hard '${BRANCH}'"
```

Note the `+` on the refspec and the `git reset --hard`: by this point the remote already has the
Task 2 version of this branch, so the fetch is a non-fast-forward update of a ref that is checked
out. Without both, the fetch is rejected and you silently test stale commits.

Then run the wrapper end to end:

```bash
ssh radxa-dragon-q6a.local 'cd /home/radxa/workspace/djl-iree-engine && ./native/local_build_wrapper.sh && ./native/local_build_wrapper.sh native/build_qa.sh && stat -c "%U:%G" native/build native/qa'
```

Expected: both succeed and both paths are owned by the remote user — the aarch64 image gets a native end-to-end run rather than waiting on CI.

- [ ] **Step 9: Commit**

```bash
git add native/local_build_wrapper.sh README.md
git commit -m "build(native): run the wrapper in the pinned toolchain image

The wrapper builds docker/<platform>.Dockerfile and runs that instead of
manylinux_2_28:latest, so local runs match CI exactly. Drops the 113 MB Corretto
download; the image carries the headers. Verified end to end on x86_64 and on
native aarch64 hardware."
```

---

### Task 5: Cache-warming workflow

GitHub Actions scopes caches per ref and lets a run read only its own ref plus the default branch, so a PR run can never read a cache another PR wrote. Warming on `main` is the one scope every run can read — which turns the image build in `native-build-job.yml` into a cache hit instead of a dnf round-trip.

**Files:**
- Create: `.github/workflows/warm-build-image.yml`

**Interfaces:**
- Consumes: `docker/linux-x86_64.Dockerfile` and `docker/linux-aarch64.Dockerfile` from Task 2.
- Produces: a populated `type=gha` layer cache on `main` for both image tags. Task 6's `cache-from: type=gha` reads it.

- [ ] **Step 1: Confirm no warming workflow exists**

```bash
ls .github/workflows/
```

Expected: no `warm-build-image.yml`. This is the failing test.

- [ ] **Step 2: Write the workflow**

```yaml
name: warm-build-image

# Populate the GitHub Actions layer cache for the per-platform toolchain image on the DEFAULT
# branch. GHA scopes caches per ref and lets a run read only its own ref plus the default branch,
# so a PR run can never read a cache another PR wrote. Warming here on `main` is the one scope
# every run CAN read, which turns the image build in native-build-job.yml into a cache hit instead
# of a dnf round-trip on every build.
on:
  push:
    branches: [main]
    # Only re-warm when the image or how it is named/selected actually changes. A warm on
    # unrelated commits would just re-push identical layers.
    paths:
      - 'docker/**'
      - '.github/workflows/warm-build-image.yml'
  workflow_dispatch:

permissions:
  contents: read

env:
  # Single source of truth for the linux build platforms. Both rows are live in
  # native-build-job.yml; warming their images here is what keeps those jobs' image builds a cache
  # hit instead of a dnf round-trip.
  PLATFORMS: >-
    [
        {"platform":"linux-x86_64","container":"djl-iree-engine-build:linux-x86_64","runner":"ubuntu-latest"},
        {"platform":"linux-aarch64","container":"djl-iree-engine-build:linux-aarch64","runner":"ubuntu-24.04-arm"}
    ]

jobs:
  setup:
    permissions:
      contents: read
    runs-on: ubuntu-latest
    outputs:
      platforms: ${{ steps.platforms.outputs.platforms }}
    steps:
        # `tee -a` used in place of `>>` to simplify debugging
      - id: platforms
        run: |
          {
            echo "platforms<<EOF"
            echo "$PLATFORMS"
            echo "EOF"
          } | tee -a "$GITHUB_OUTPUT"

  warm:
    needs: setup
    runs-on: ${{ matrix.combo.runner }}
    strategy:
      matrix:
        combo: ${{ fromJson(needs.setup.outputs.platforms) }}
    steps:
      - uses: actions/checkout@v7
      # Each row runs on a runner of its own architecture (ubuntu-24.04-arm for aarch64), so the
      # image builds natively and no qemu/binfmt setup is needed.
      - uses: docker/setup-buildx-action@v4.2.0
      - name: Warm the pinned toolchain image cache
        uses: docker/build-push-action@v7.3.0
        # No `load:` -- this job only populates the cache; it never runs the image. cache-to writes
        # the layers the build runs read via cache-from.
        with:
          context: docker
          file: "docker/${{ matrix.combo.platform }}.Dockerfile"
          tags: ${{ matrix.combo.container }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

- [ ] **Step 3: Validate the YAML parses and the matrix JSON is well-formed**

```bash
python3 -c "
import yaml, json, sys
d = yaml.safe_load(open('.github/workflows/warm-build-image.yml'))
p = json.loads(d['env']['PLATFORMS'])
print(json.dumps(p, indent=2))
assert {c['platform'] for c in p} == {'linux-x86_64','linux-aarch64'}
for c in p:
    import os; assert os.path.exists(f\"docker/{c['platform']}.Dockerfile\"), c['platform']
print('OK')
"
```

Expected: the two entries print and `OK`. The `assert os.path.exists` is the part that matters — it catches a platform token that does not match a Dockerfile filename, which is the whole reason the filenames encode the token.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/warm-build-image.yml
git commit -m "ci: warm the toolchain image layer cache on main

GHA scopes caches per ref; main is the one scope every run can read. Warming
both platform images here makes the image build in native-build-job.yml a cache
hit rather than a dnf round-trip on every run."
```

---

### Task 6: Rewire the native build job onto the pinned image

**Files:**
- Modify: `.github/workflows/native-build-job.yml:11-24` (the matrix) and `:44-79` (build + QA steps)

**Interfaces:**
- Consumes: the Dockerfiles from Task 2, the script fast paths from Task 3, the warmed cache from Task 5.
- Produces: CI green on both linux platforms with no Corretto download. Terminal task.

- [ ] **Step 1: Confirm the job still downloads the RPM and uses the raw base**

```bash
grep -n 'corretto-jdk-url\|manylinux_2_28\|Download Corretto' .github/workflows/native-build-job.yml
```

Expected: two `corretto-jdk-url` keys, two `manylinux_2_28` image values, one `Download Corretto JDK 8 RPM` step. This is the failing test.

- [ ] **Step 2: Rewrite the matrix**

Replace the `include:` block with:

```yaml
        include:
          - platform: linux-x86_64
            runner: ubuntu-latest
          - platform: linux-aarch64
            runner: ubuntu-24.04-arm
```

The `image:` and `corretto-jdk-url:` keys both go: the image name is now derivable from the platform token, and the JNI headers are baked in.

- [ ] **Step 3: Delete the RPM download step**

Remove the whole step:

```yaml
      # Use a generic name for the RPM file
      - name: Download Corretto JDK 8 RPM
        run: |
            curl -L -o amazon-corretto-linux-jdk.rpm ${{ matrix.corretto-jdk-url }}
```

- [ ] **Step 4: Add the image build step**

Insert immediately before the "Build the libiree_djl shim" step (after the provenance gate, so a bad runtime pin still fails fast before any image work):

```yaml
      # The toolchain image: manylinux_2_28 at a dated tag plus the JNI headers, ninja, and ASan
      # runtime the base does not ship, all at pinned versions. Built here rather than pulled from
      # a registry -- warm-build-image.yml keeps the layers in the GHA cache, so this is a cache
      # hit rather than a dnf round-trip. `load: true` puts the image in the local daemon for the
      # docker run steps below.
      - uses: docker/setup-buildx-action@v4.2.0
      - name: Build the pinned toolchain image
        uses: docker/build-push-action@v7.3.0
        with:
          context: docker
          file: "docker/${{ matrix.platform }}.Dockerfile"
          tags: djl-iree-engine-build:${{ matrix.platform }}
          load: true
          cache-from: type=gha
```

No `cache-to` here — only `warm-build-image.yml` writes the cache, and a PR run writing to its own ref scope would just burn cache quota nothing else can read.

- [ ] **Step 5: Point both `docker run` steps at the new tag**

In the "Build the libiree_djl shim" and "Run native QA gate" steps, replace `${{ matrix.image }}` with `djl-iree-engine-build:${{ matrix.platform }}`. Everything else about those two steps — the bind mount, `-w /workspace`, the script paths — stays exactly as-is.

- [ ] **Step 6: Confirm the Windows job is untouched**

```bash
git diff .github/workflows/native-build-job.yml | grep -c 'JAVA_HOME_8_X64\|Launch-VsDevShell\|check_windows_crt'
```

Expected: `0`. Nothing in the Windows job should appear in the diff.

- [ ] **Step 7: Validate the workflow YAML**

```bash
python3 -c "
import yaml
d = yaml.safe_load(open('.github/workflows/native-build-job.yml'))
j = d['jobs']['build-iree-shim']
rows = j['strategy']['matrix']['include']
assert [r['platform'] for r in rows] == ['linux-x86_64','linux-aarch64'], rows
assert all('corretto-jdk-url' not in r and 'image' not in r for r in rows), rows
names = [s.get('name','') for s in j['steps']]
assert 'Download Corretto JDK 8 RPM' not in names, names
assert 'Build the pinned toolchain image' in names, names
print('OK')
"
```

Expected: `OK`.

- [ ] **Step 8: Commit**

```bash
git add .github/workflows/native-build-job.yml
git commit -m "ci: build the linux shim in the pinned toolchain image

Both linux matrix rows now build docker/<platform>.Dockerfile with the warmed
GHA layer cache and run in that image, replacing manylinux_2_28:latest. Drops
the per-run 113 MB Corretto download; the image bakes the headers. The Windows
job is unchanged."
```

- [ ] **Step 9: Push and confirm CI is green**

```bash
git push -u origin HEAD
gh pr create --fill
gh pr checks --watch
```

Expected: both linux rows and the Windows job pass. Compare the linux rows' wall-clock time against a recent `main` run — the JDK download and dnf steps should be gone.

Note the first PR run cannot read a warm cache, because `warm-build-image.yml` only fires on pushes to `main`. Expect the image build to be a full ~2-3 minute build on that first run, and a cache hit on every run after the branch merges.

---

## Verification Summary

Mapped to the spec's Verification section:

| Spec check | Task / step |
| --- | --- |
| 1. Wrapper builds shim, outputs user-owned | Task 4 Step 6 |
| 2. QA passes and `native/qa` user-owned (fails pre-change) | Task 1 Steps 1 and 5 |
| 3. Re-runs show no curl/pip/dnf, cached layers only | Task 3 Step 5, Task 4 Step 7 |
| 4. `jni_md.h` + `ninja --version` in the image | Task 2 Step 4 |
| 5. Full repeat on native aarch64 | Task 2 Step 5, Task 4 Step 8 |
| 6. glibc-2.28 floor unchanged | Below — run once after Task 4 |

**glibc floor check** (run after Task 4, before opening the PR):

```bash
objdump -T src/main/resources/native/linux-x86_64/libiree_djl.so \
  | grep -oE 'GLIBC_[0-9]+\.[0-9]+' | sort -uV | tail -3
```

Expected: nothing above `GLIBC_2.28`. The pinned image must not raise the floor — its base is the same manylinux_2_28 family, just at a fixed tag, so a higher symbol here means the base tag is wrong.
