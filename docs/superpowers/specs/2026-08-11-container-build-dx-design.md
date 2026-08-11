# Container Build DX: Pinned Toolchain Image

**Date:** 2026-08-11
**Status:** Approved, ready for implementation planning

## Problem

The native build runs inside `quay.io/pypa/manylinux_2_28_{arch}:latest` — a floating tag, with
every per-run cost paid on every run:

| Cost | Where | Paid on |
| --- | --- | --- |
| 113 MB Corretto 8 RPM download + `rpm2archive` extraction | `native/build.sh`, `native/local_build_wrapper.sh`, `native-build-job.yml` | every build |
| `pip install ninja` (base image ships no ninja) | `native/build.sh` | every build |
| `dnf install gcc-toolset-N-libasan-devel` | `native/build_qa.sh` | every QA run |

Two further defects:

- **Floating base tag.** A base rebuild changes the toolchain underneath a green tree, leaving no
  way to distinguish "the image changed" from "our code changed". The sibling
  `djl-executorch-engine` repo hit exactly this: a 2026-08-05 base rebuild dropped
  `systemtap-sdt-devel` and broke builds that had passed hours earlier on identical source.
- **`build_qa.sh` has no chown trap.** `native/build.sh` chowns its outputs back to the host user
  on exit; `build_qa.sh` does not, so every wrapper-driven QA run leaves `native/qa/` root-owned.

## Goals

1. A pinned, tagged toolchain image per linux platform that bakes in the three amortizable costs.
2. GitHub Actions layer-cache warming on `main`, so CI image builds are cache hits.
3. JNI headers baked into the image, removing the 113 MB download from CI and local runs.
4. `native/qa/` output owned by the invoking user, not root.

Non-goals: registry publishing (GHCR), Windows changes, macOS.

## Verified pins

All values below were confirmed by running the base image — x86_64 locally, aarch64 natively on
`radxa-dragon-q6a.local` (not qemu). Both arches agree; that agreement is a convenience, not a
rule. Re-resolve per arch on the next base bump with `dnf list --showduplicates <pkg>`.

| Pin | Value | How verified |
| --- | --- | --- |
| Base image | `quay.io/pypa/manylinux_2_28_{arch}:2026.06.04-1` | dated tag, both arches pulled |
| Base gcc | `14.2.1-11` | `gcc --version` in both images |
| ASan runtime | `gcc-toolset-14-libasan-devel-14.2.1-11.el8_10` | `dnf list --showduplicates`, both arches |
| ninja | `1.13.0` | `pip index versions ninja` under `/opt/python/cp312-cp312` |
| Corretto 8 | `8.502.07.1` | resolved from the `latest` redirect |
| Corretto x86_64 sha256 | `8663ad535a10f8418ce6c3b97108e2dbbe49aef7c317eaef9f08f1d25d5a7286` | computed by download |
| Corretto aarch64 sha256 | `ce812e8ab602fd999d2576ee4ae0eb82116017c7304dfb91601b5e312a6fc48c` | computed by download |

The ASan pin is held to the base image's own compiler revision deliberately: a libasan from a
different toolset revision than the gcc that emitted the instrumentation is the classic source of
confusing ASan link errors.

Corretto's `latest_checksum` endpoint serves MD5, not SHA-256, so the checksums above were computed
from the downloaded artifacts rather than published by upstream.

Also confirmed present in both base images: `rpm2archive` (used for header extraction — the image
has no `cpio`) and `/opt/python/cp312-cp312`, so `build.sh`'s existing PATH line needs no change.

## Design

### 1. `docker/` directory

Two files, `docker/linux-x86_64.Dockerfile` and `docker/linux-aarch64.Dockerfile`. The filename
encodes the platform token so the image tag, its Dockerfile, and the artifact platform stay one
name — matching the layout in `djl-executorch-engine` and `iree-runtime-dist`.

Image tag: `djl-iree-engine-build:<platform>`.

Each Dockerfile is a thin specialization of the pinned base:

1. `FROM quay.io/pypa/manylinux_2_28_{arch}:2026.06.04-1`
2. `RUN dnf install -y gcc-toolset-14-libasan-devel-14.2.1-11.el8_10 && dnf clean all && rm -rf /var/cache/dnf`
3. `RUN /opt/python/cp312-cp312/bin/pip install --no-cache-dir ninja==1.13.0`, then symlink the
   resulting binary into `/usr/local/bin` so `ninja` is on `PATH` regardless of how the container
   is entered.
4. Corretto headers, via build args `CORRETTO_URL` / `CORRETTO_SHA256` defaulted to the pinned
   values above: `curl -fL` the **versioned** URL (never the `latest` redirect — that redirect is
   what makes a layer non-reproducible), verify with `sha256sum -c`, `rpm2archive`, `tar -C
   /opt/corretto -xzf`, then `find /opt/corretto -path '*/include/jni.h'` and symlink that root to
   `/opt/corretto-jdk`. `ENV JAVA_HOME=/opt/corretto-jdk`. Delete the RPM and tarball in the same
   layer.

   The `find`-then-symlink indirection is deliberate. The extraction currently lands at
   `/opt/corretto/usr/lib/jvm/java-1.8.0-amazon-corretto/`, but hardcoding that means a Corretto
   directory rename yields an image with a dangling `JAVA_HOME` and no error until a shim build
   fails deep in a CMake configure.

5. Image-build-time assertions, so a pin that stops delivering what it is here for fails loudly
   at image build rather than three steps into a shim build:
   `test -f "$JAVA_HOME/include/linux/jni_md.h"` and `command -v ninja`.

Each pin carries a comment stating what it is for and why it is held where it is.

### 2. Shared chown helper

New `native/container_env.sh`, sourced by `native/build.sh` and `native/build_qa.sh`. It exposes
one function that installs an EXIT trap chowning the given paths back to `$HOST_UID:$HOST_GID`,
and is a no-op when `HOST_UID` is unset (i.e. host-direct runs, and Windows).

Paths passed by each caller:

- `build.sh` — `native/build` and `src/main/resources/native/linux*` (its current behaviour, moved
  verbatim into the helper; no change in effect)
- `build_qa.sh` — `native/qa` (**the fix**)

A shared helper rather than a copied trap: two copies of this logic will drift, and any future
script run under the wrapper then gets ownership handling with one line.

The trap must preserve the current exit-status semantics — capture `$?` first, chown with
`2>/dev/null || true` so a chown failure never masks the real exit code, then `exit "$rc"`.

### 3. `native/build.sh`

- **JDK block (Linux branch):** add a fast path — if `JAVA_HOME` is set and
  `"$JAVA_HOME/include/linux/jni_md.h"` exists, use it as-is and skip extraction entirely.
  Otherwise fall back to the existing `/workspace/amazon-corretto-linux-jdk.rpm` extraction. This
  keeps host-direct runs and stale-image runs working, and mirrors how `build_qa.sh` already keeps
  its `dnf` line as a fallback.
- **Ninja:** `command -v ninja >/dev/null || pip install ninja`.
- **Trap:** replaced by sourcing `container_env.sh`.
- Windows branch untouched.

### 4. `native/build_qa.sh`

- **ASan install:** skip the `dnf` call when the toolset's libasan is already present. The existing
  `gcc -dumpversion`-derived, `|| true`-guarded `dnf` line stays as the fallback for host runs.
- **Trap:** source `container_env.sh` and register `native/qa`.
- Windows branch untouched.

### 5. `native/local_build_wrapper.sh`

- Resolve the platform token (`linux-x86_64` / `linux-aarch64`) from `uname -m`, replacing the
  existing `ML_IMAGE_ARCH` / `CORRETTO_ARCH` pair.
- `docker build -t djl-iree-engine-build:<platform> -f docker/<platform>.Dockerfile docker/` before
  the `docker run`. Docker's layer cache makes this a near-instant no-op after the first run.
- `docker run` the built tag instead of `quay.io/pypa/manylinux_2_28_*:latest`.
- Delete the Corretto RPM download block — the image carries the headers now. Nothing in the repo
  downloads that RPM after this change; `build.sh`'s fallback path (§3) is for a developer running
  `native/build.sh` directly on the host, who supplies the RPM themselves. If it is absent, the
  fallback fails with the existing "JDK headers not found" message.
- Update the comment block: the note that "only build.sh chowns its outputs back to you" is now
  wrong for QA and must say so.

### 6. `.github/workflows/warm-build-image.yml` (new)

Ported from `djl-executorch-engine`. Populates the GHA layer cache on the default branch, which is
the one cache scope every run can read.

- Triggers: `push` to `main` filtered to `docker/**` and the workflow file itself, plus
  `workflow_dispatch`.
- `PLATFORMS` env holds the single source of truth for the platform/container/runner triples:
  `linux-x86_64` on `ubuntu-latest`, `linux-aarch64` on `ubuntu-24.04-arm`.
- A `setup` job publishes that JSON as an output; a matrixed `warm` job builds each Dockerfile with
  `docker/build-push-action` using `cache-from: type=gha` and `cache-to: type=gha,mode=max`.
- No `load:` — this job only populates the cache and never runs the image.
- Each row runs on a runner of its own architecture, so both images build natively with no
  qemu/binfmt setup.
- `permissions: contents: read`.

### 7. `.github/workflows/native-build-job.yml`

In the two linux matrix rows:

- `image:` becomes `djl-iree-engine-build:${{ matrix.platform }}`.
- Delete the `corretto-jdk-url` matrix keys and the "Download Corretto JDK 8 RPM" step.
- Add `docker/setup-buildx-action` and a `docker/build-push-action` step that builds
  `docker/${{ matrix.platform }}.Dockerfile` with `load: true` (so the `docker run` steps below can
  use it) and `cache-from: type=gha`.

Unchanged: the IREE runtime provenance gate, both `docker run` steps, artifact upload, and the
entire `build-iree-shim-windows` job.

## Verification

1. `./native/local_build_wrapper.sh` — shim builds; `native/build` and
   `src/main/resources/native/linux-x86_64` are owned by `$USER`.
2. `./native/local_build_wrapper.sh native/build_qa.sh` — QA passes **and `native/qa` is owned by
   `$USER`**. This is the regression check for the trap fix; confirm it fails on the pre-change
   tree.
3. Re-run both — no curl / pip / dnf network traffic in the output, and the image build reports
   cached layers only.
4. `docker run --rm djl-iree-engine-build:linux-x86_64 bash -c 'test -f $JAVA_HOME/include/linux/jni_md.h && ninja --version'`
5. Repeat 1–4 on `radxa-dragon-q6a.local` against `docker/linux-aarch64.Dockerfile`, giving the
   aarch64 image a native end-to-end run rather than deferring it to CI.
6. Confirm the shim still reports a glibc-2.28 floor — the pinned image must not raise it.

## Risks

- **Pin staleness.** Pinned NEVRAs fail loudly when AlmaLinux retires a build; that is the intended
  behaviour, but it means the Dockerfiles need occasional maintenance. Each pin's comment records
  the re-resolution command.
- **Corretto URL rot.** Amazon could remove the `8.502.07.1` resources path. The image build then
  fails at the `curl`, with `build.sh`'s RPM fallback still available as an escape hatch.
- **First local build is slow.** The initial `docker build` on a developer machine pays all three
  costs once, and is thereafter cached. This is strictly better than today's every-run cost.
