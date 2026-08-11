# CI build-time analysis and the shared-image question

**Date:** 2026-08-11
**Status:** Interim note. Measurements are settled; the GHCR proposal is unvalidated and
blocked on research into current GHCR behaviour for public packages.

## Why this note exists

Two changes landed close together — the pinned toolchain image (#27) and the Catch2 gate
(#28) — and the combined CI saving is about two minutes. This records *which* change earned
that, because the answer is lopsided and it changes how the container work should be
described going forward.

## Measurements

GitHub Actions, `IREE Engine Build`, step-level timings. Baseline is run `31525772111`
(sha `1f5b5c5`, before both changes); "after" is run `31542865571` (PR #28 branch, which has
both).

### linux-x86_64

| Step | Before | After | Δ |
| --- | --- | --- | --- |
| Download Corretto JDK 8 RPM | 1s | *removed* | −1s |
| Verify IREE runtime provenance | 8s | 6s | −2s (noise) |
| `docker/setup-buildx-action` | — | 7s | **+7s** |
| Build the pinned toolchain image | — | 39s | **+39s** |
| Build the libiree_djl shim | 94s | 4s | **−90s** |
| Run native QA gate | 101s | 69s | **−32s** |
| *Total real work* | *204s* | *125s* | *−79s* |

### linux-aarch64

| Step | Before | After | Δ |
| --- | --- | --- | --- |
| Download Corretto JDK 8 RPM | 1s | *removed* | −1s |
| Verify IREE runtime provenance | 6s | 4s | −2s (noise) |
| `docker/setup-buildx-action` | — | 6s | **+6s** |
| Build the pinned toolchain image | — | 36s | **+36s** |
| Build the libiree_djl shim | 76s | 4s | **−72s** |
| Run native QA gate | 108s | 82s | **−26s** |
| *Total real work* | *191s* | *132s* | *−59s* |

Combined: **−138s**, matching the observed ~2 minutes.

Job totals are a poor instrument here. The untouched Windows job moved 243s → 203s → 154s
across the same three runs with no changes to it at all, so run-to-run variance on job
totals is tens of seconds. Step-level timings are what make the attribution defensible.

## Attribution

- **Catch2 gate (#28): −162s.** The shim build going 94s→4s and 76s→4s is essentially the
  whole saving. Larger than the ~30s predicted from local measurement, because on a cold
  4-vCPU runner the shipping build compiled all 107 Catch2 objects from scratch.
- **QA gate: −58s**, shared between #28's hashed tarball (no 46 MB clone) and #27's baked
  libasan (no `dnf` round-trip).
- **Pinned image infrastructure (#27): +88s.** `setup-buildx` plus reconstructing a 1.76 GB
  image from the GHA cache and `load:`ing it into the daemon.

Crediting #27 the `dnf` time it removes from QA (~20s/arch), the pinned image is still
roughly **45s net slower** in wall-clock.

## Two corrections to earlier reasoning

1. **The 113 MB Corretto download cost 1 second in CI.** Runner network is fast enough that
   it was never a meaningful per-run cost. It mattered for local dev, not CI. The
   "amortize the download" motivation in the #27 spec was wrong on the CI side.
2. **"Amortize `dnf` install times" did not pay off in wall-clock.** Materializing the image
   costs more than the `dnf` it replaces.

**#27 still earns its place** — but for reproducibility, not speed. It is what stops a
floating base tag from rebuilding the toolchain underneath a green tree, which is the
incident that motivated it in `djl-executorch-engine` (a 2026-08-05 base rebuild dropped
`systemtap-sdt-devel` and broke builds on unchanged source). That is the benefit to claim.
Describing it as a speed optimization is not supported by the data.

## The shared-image proposal

Motivation is **consolidation, not speed**: one image consumed by both engine repos, deleting
per-repo container CI from both.

### Current landscape

Three repos specialize `quay.io/pypa/manylinux_2_28_*:2026.06.04-1`:

| Repo | Adds | Purpose |
| --- | --- | --- |
| `measly-java-learning/iree-runtime-dist` | clang 21.1.8, lld, ninja-build 1.8.2 | compiler toolchain |
| `measly-java-learning/djl-iree-engine` | libasan 14.2.1-11, ninja 1.13.0 (pip), Corretto 8 headers | gcc shim build |
| `corey-cole/djl-executorch-engine` | libasan 14.2.1-11, systemtap-sdt-devel | gcc shim build |

The two engine images are near-identical. Their union is the iree image plus
`systemtap-sdt-devel`; executorch would additionally gain the ninja and Corretto layers it
does not currently bake. One image for both is preferable to two — "both engines build in a
byte-identical environment" removes the drift risk between two hand-maintained Dockerfile
pairs.

`iree-runtime-dist` should stay separate. It is a clang/lld compiler toolchain, different in
kind; folding it in ships clang to consumers that build with gcc.

### What leaves both repos

The `docker/` directory, all of `warm-build-image.yml`, `setup-buildx-action`, and
`build-push-action` — replaced by a bare `docker run ghcr.io/…@sha256:…`, since the pull is
implicit. **The GHA-cache scope-collision bug class goes with it** (see
`corey-cole/djl-executorch-engine#38`): no GHA cache means no scope to collide.

The local DX win is likely larger than the CI win. `local_build_wrapper.sh` in both repos
drops its `docker build` entirely, so a new contributor's first run stops paying an image
build.

### Two decisions that make or break it

1. **The package must be public.** `corey-cole/djl-executorch-engine` is in a *different org*
   from `measly-java-learning`. A public GHCR package pulls anonymously — no PAT, no
   `docker/login-action`, no cross-org secret. A private package needs a PAT with
   `read:packages` in the other org's repo secrets, which is exactly the complexity being
   removed.
2. **Consumers must pin by digest, not tag.** A GHCR tag is as movable as `:latest`; pinning
   by tag would silently undo the reproducibility property that motivated #27 in the first
   place. Use `ghcr.io/<org>/<image>:<platform>@sha256:…`, treating the tag as a human label.

### Costs

- Toolchain bumps become a cross-repo sequence: publish → capture digest → bump two pins.
  Slower to iterate, more auditable.
- One more repo to own, with a publish workflow. Attestation would match the
  `gh attestation verify` discipline already used for the runtime tarballs.
- Unknown whether a GHCR pull beats the current 39s of cache reconstruction plus `load:`.
  Likely somewhat faster, but this is not the case for doing it.

## Open questions

Blocking, pending research into current GHCR behaviour:

- Anonymous pull limits and rate limiting for public GHCR packages, and whether GitHub-hosted
  runners are subject to them.
- Whether a public package in one org can be pulled by Actions in another org with no token
  at all, and whether `GITHUB_TOKEN` alone suffices if not.
- Package visibility and admin mechanics: how a package published by a workflow is made
  public, and whether that is a one-time or per-publish action.
- Retention/cleanup policy for untagged digests, given that consumers pin by digest — a
  cleanup rule that prunes untagged manifests would break pinned consumers.
- Whether image attestation (`gh attestation verify` against a GHCR image) is available and
  how it composes with digest pinning.

## Related

- Spec: `docs/superpowers/specs/2026-08-11-container-build-dx-design.md`
- Plan: `docs/superpowers/plans/2026-08-11-container-build-dx.md`
- PR #27 (pinned images), PR #28 (Catch2 gate)
- `corey-cole/djl-executorch-engine#38` (GHA cache scope collision)
