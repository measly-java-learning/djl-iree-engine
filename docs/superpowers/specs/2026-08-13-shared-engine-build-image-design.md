# Migrating onto the shared `engine-build` image

## Problem

This repo builds its own Linux toolchain image. `docker/linux-x86_64.Dockerfile` and
`docker/linux-aarch64.Dockerfile` are near-identical specializations of manylinux_2_28 that add
the Corretto 8 JNI headers, ninja, and the pinned libasan/libubsan NEVRAs. CI builds that image
on every run, and `warm-build-image.yml` exists solely to keep those layers in the GitHub
Actions cache so the build is a cache hit rather than a `dnf` round-trip.

The sibling repo `corey-cole/djl-executorch-engine` maintains a second copy of the same idea.
Both have now been replaced upstream by a single published image,
`ghcr.io/measly-java-learning/engine-build`, whose contract is documented in
`base-docker-images/docs/consuming-engine-build.md`.

Consuming it removes, from this repo: two Dockerfiles, a whole workflow, a `docker build` on
every contributor's first local build, and the GHA layer cache — the last of which retires the
cache-scope-collision failure tracked as `corey-cole/djl-executorch-engine#38`.

## The pin

```
ghcr.io/measly-java-learning/engine-build@sha256:725884538caa4f7f8444847e34b3928bb90089da95d5b77ce560aa2e624f905b
```

A **manifest list** covering `linux/amd64` and `linux/arm64`. Docker selects the architecture,
so no consumer picks a platform-specific reference and no arch matrix over the image is needed.
The package is public and pulls anonymously — no login step, no token, no cross-org secret.

Pin the digest, never the `:main` tag. A moving tag reintroduces exactly the failure the image
exists to prevent: a toolchain rebuilding underneath a green tree.

## What the image provides

Verified against the published digest.

| Variable | Value |
| --- | --- |
| `MEASLY_DJL_PINNED_IMAGE` | `1` |
| `MEASLY_DJL_TOOLSET_VER` | `14` |
| `MEASLY_DJL_TOOLSET_NEVRA` | `14.2.1-11.el8_10` |
| `MEASLY_DJL_NINJA_VERSION` | `1.13.0.git.kitware.jobserver-pipe-1` |
| `JAVA_HOME` | `/opt/corretto-jdk` |

`gcc`/`g++` 14.2.1, `ninja` at the version above, `cmake` 4.3.2, and
`gcc-toolset-14-lib{asan,ubsan}-devel-14.2.1-11.el8_10` at exact NEVRAs. `$JAVA_HOME` resolves
to Corretto 8 with `include/jni.h` and `include/linux/jni_md.h` — headers only, no `libjvm`,
no JDK runtime.

`PATH` is baked into the image config rather than set by a profile script, so
`docker run <image> gcc --version` works without `bash -lc`.

Three consequences matter here:

- **`MEASLY_DJL_NINJA_VERSION` is the string `ninja --version` reports**, not the pip metadata
  version. pip installs `ninja==1.13.0`; the Kitware jobserver-pipe wheel prints the longer
  string. Our assertions compare against reported output, so this is the correct value to
  compare — the same distinction the deleted Dockerfiles already documented.
- **The sanitizer runtimes are held to the base image's own gcc revision.** A libasan from a
  different toolset revision than the gcc that emitted the instrumentation is the classic source
  of confusing ASan link errors. The image build asserts this, so it cannot drift silently.
- **No clang.** `/opt/clang/bin` is on `PATH`, inherited from the manylinux base, but the
  directory does not exist. Nothing here assumes clang.

## Design

### 1. One home for the digest

A new `.engine-build-image` at the repo root holds the digest on a single line.
`native-build-job.yml` reads it into `$GITHUB_ENV`; `local_build_wrapper.sh` reads it directly.

The alternative — a workflow `env:` var, with the wrapper carrying its own copy — was rejected
because two copies drift silently, and the failure mode is CI green while a contributor builds
against a different toolchain. A pin bump must be a one-line diff in one file.

### 2. `.github/workflows/native-build-job.yml`

Delete the `docker/setup-buildx-action@v4.2.0` step, the `docker/build-push-action@v7.3.0` step,
and the `cache-from: type=gha,scope=${{ matrix.platform }}` line with them. Add a step reading
the digest into the environment, then point all **three** `docker run` invocations at it —
lines 61 (shim build), 69 (QA gate), and 84 (UBSan instrumented shim). The `docker run` bodies
are otherwise unchanged: same `--rm`, same `-v`/`-w`, same `-e`.

The `build-iree-shim-windows` job is untouched. It has no container path.

### 3. Deletions

`docker/` and `.github/workflows/warm-build-image.yml`, in full. With no image build there is
nothing to warm; with no GHA cache there is no cache scope, so the scope-collision bug class
cannot recur.

### 4. `native/local_build_wrapper.sh`

Drop the `docker build` line and the `PLATFORM`/`IMAGE` derivation above it. The `uname -m`
case statement existed only to pick a Dockerfile name and an image tag; the manifest list
resolves the architecture, and `native/build.sh` performs its own arch check when staging
output, so nothing else consumes that token here.

Everything else stays: `IR_MEMORY`/`IR_CPUSET` (a host-side systemd scope does not contain a
container — dockerd is a root daemon, so container processes are children of containerd-shim in
the system slice), `HOST_UID`/`HOST_GID` (the image still runs as root, and `container_env.sh`
hands outputs back on exit), and `ITERS`/`WARMUP`.

### 5. The variable rename

The scripts already assert against image-published pins rather than installing tools — that
work landed in `e27edb2` and merged as PR #36. This migration does not rewrite that logic. It
renames the variables to the prefix the shared image actually publishes, and nothing else:
the `set -u` companion guards, the BROKEN-IMAGE semantics, and the host-side link probe all
stay exactly as they are.

**This is a scalpel, not a `sed`.** There are 20 distinct `IREE_DJL_*` names in the tree.
Exactly four are image-published:

| Rename | To |
| --- | --- |
| `IREE_DJL_PINNED_IMAGE` | `MEASLY_DJL_PINNED_IMAGE` |
| `IREE_DJL_NINJA_VERSION` | `MEASLY_DJL_NINJA_VERSION` |
| `IREE_DJL_TOOLSET_VER` | `MEASLY_DJL_TOOLSET_VER` |
| `IREE_DJL_TOOLSET_NEVRA` | `MEASLY_DJL_TOOLSET_NEVRA` |

Every other `IREE_DJL_*` name is this project's own CMake option or test knob and must not be
touched: `IREE_DJL_SANITIZE`, `IREE_DJL_TSAN`, `IREE_DJL_UBSAN`, `IREE_DJL_UBSAN_MODE`,
`IREE_DJL_UBSAN_CHECKS`, `IREE_DJL_BUILD_TESTS`, `IREE_DJL_ADD_VMFB`, `IREE_DJL_SCALE*`,
`IREE_DJL_FIXTURE_DIR`, `IREE_DJL_PLATFORM*`, `IREE_DJL_TEST_TMP_DIR`,
`IREE_DJL_STATISTICS_ENABLE`.

A blanket `s/IREE_DJL_/MEASLY_DJL_/` would rename `IREE_DJL_UBSAN_MODE` — which sits adjacent to
`IREE_DJL_UBSAN` in the same files — and break the gate silently.

Call sites, three scripts:

- `native/build.sh:74, 78, 81, 82` — the marker branch, its `:?` companion guard, and the ninja
  version assertion.
- `native/build_qa.sh:75, 76, 80, 81, 83, 89` — the marker branch, two `:?` guards, and the
  `for _san in asan ubsan` NEVRA loop.
- `native/ubsan_gate.sh:60, 110` — **load-bearing.** Line 60 selects `MODE=build` inside the
  image versus `MODE=all` outside it. Miss it and `ubsan_gate.sh` inside the image picks
  `MODE=all` and tries to start Gradle under Corretto 8, which is precisely the failure the
  `CLAUDE.md` trip-wire describes.

### 6. Remediation messages that are now impossible

Five messages tell the reader to *"rebuild the image from `docker/*.Dockerfile`"*. That advice
cannot be followed once the image is shared across two organisations — a consumer cannot add
layers to it, and there is no Dockerfile in this repo to rebuild.

They become: verify the pin in `.engine-build-image`, or unset the marker for a host run.

`native/build_qa.sh:86` additionally interpolates a Dockerfile path
(`docker/${IR_PLATFORM:-linux-$(uname -m)}.Dockerfile`) into its error text, naming a file that
will not exist.

This is the part most easily missed: the assertions keep working after a naive rename, but they
send whoever trips them somewhere that no longer exists.

### 7. Documentation

- `CONTRIBUTING.md` "Container build" — the wrapper no longer picks a Dockerfile from
  `uname -m` or builds anything; a first build pays a pull. The sentence pointing at
  `warm-build-image.yml` goes with the workflow.
- `CLAUDE.md` — the Gradle-in-container trip-wire names `IREE_DJL_PINNED_IMAGE`.
- `native/build.sh:27, 43` — comments describing where the image comes from.
- `.clangd:7` and `native/gen_clangd_db.sh:40` — both explain the separate clangd tree by
  reference to "the manylinux container". The reasoning is unchanged (a container build writes
  `/workspace`-absolute paths that host clangd cannot resolve); only the name of the image
  changes.

## What does not change

`native/build.sh`'s JAVA_HOME fast path, its host fallbacks, `build_qa.sh`'s host-side
`-fsanitize` link probe, the `ubsan_gate.sh` two-phase split, `container_env.sh`, the Windows
job, and every CMake file. The image satisfies the same contract the deleted Dockerfiles did.

## Verification

1. `./native/local_build_wrapper.sh` — shim builds, stages into `src/main/resources`.
2. `./native/local_build_wrapper.sh native/build_qa.sh` — exercises the renamed
   `MEASLY_DJL_TOOLSET_VER`/`NEVRA` assertions across both `asan` and `ubsan`.
3. `./native/local_build_wrapper.sh native/ubsan_gate.sh` — the sharpest test of the rename: it
   exercises both the NEVRA loop and the `MODE=build` split at line 60. A wrong result here is
   Gradle failing under Corretto 8, not a silent pass.
4. `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/native-build-job.yml'))"`
5. `./gradlew test --rerun-tasks` against the plain library. Per the repo trip-wire, a run
   reporting `UP-TO-DATE` has verified nothing — check for `N actionable tasks: N executed`.
6. `grep -rn 'IREE_DJL_PINNED_IMAGE\|IREE_DJL_TOOLSET\|IREE_DJL_NINJA' .` returns nothing, while
   `IREE_DJL_UBSAN_MODE` and the other project-owned names are untouched — the rename hit the
   four image-published variables and stopped there.

A sanitizer or QA build leaves an instrumented library staged, so `./native/build.sh` must run
plain again before step 5.

## Risks

**The digest is not re-derivable.** Re-running the publish workflow on the same commit produces
a different index digest — layer timestamps and merge ordering are not bit-reproducible. The
digest must be taken from the run being consumed; it cannot be recovered later by rebuilding the
same sha. This is why it lives in a checked-in file rather than being reconstructed.

**Provenance moves from local to remote.** The repo previously built its toolchain from a
Dockerfile it could read. It now trusts a published artifact. That trust is checkable:

```bash
gh attestation verify \
  oci://ghcr.io/measly-java-learning/engine-build@sha256:725884538caa4f7f8444847e34b3928bb90089da95d5b77ce560aa2e624f905b \
  --owner measly-java-learning
```

Success is silent; the exit code is the verdict. `buildSignerURI` should be
`build-multi-arch-image.yml@refs/heads/main` in `measly-java-learning/base-docker-images`.
Only the index digest is attested — verifying a manifest-list child returns HTTP 404. This is
not wired into CI as a gate; the runtime tarball provenance check already in the job covers the
artifact we link against, and adding a second network-dependent gate for the toolchain is a
separate decision.

**A pin bump is now a cross-repo event.** Previously a toolchain change was a local Dockerfile
edit reviewed here. Now it lands in `base-docker-images`, and this repo consumes it by updating
one line. The upside is that both consumer repos move together; the cost is that the change
itself is reviewed elsewhere.
