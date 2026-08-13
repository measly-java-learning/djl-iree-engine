#!/bin/bash
set -ex # Fail on error, print commands to log

# Runs a native/ script inside the pinned shared toolchain image (ghcr.io/measly-java-learning/
# engine-build, digest in .engine-build-image) — the environment the GHA workflow uses in CI.
# This is the BLESSED way to run the native scripts: the toolchain matches, and a shim built here
# keeps its glibc-2.28 floor (RHEL8). Running these scripts directly on the host works but breaks
# the floor (build.sh) or collides on a container-made cache (bench/qa wipe theirs).
#
# Usage: ./native/local_build_wrapper.sh [script]   (default: native/build.sh)
#   ./native/local_build_wrapper.sh native/bench.sh
#   ITERS=2000 ./native/local_build_wrapper.sh native/build_qa.sh
#   ./native/local_build_wrapper.sh native/build_variants.sh
#   IR_MEMORY=12g ./native/local_build_wrapper.sh native/ubsan_gate.sh   # raise the cap
# build.sh, build_qa.sh and ubsan_gate.sh all chown their outputs back to you on exit (see
# native/container_env.sh). Other native/ scripts run through this wrapper do not yet, and will
# leave root-owned dirs behind.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Optional first arg: the native/ script to run in the container (default the shim build).
TARGET_SCRIPT="${1:-native/build.sh}"

# The pinned shared toolchain image, digest not tag. It is a manifest list covering amd64 and
# arm64, so Docker resolves the architecture -- there is no Dockerfile to pick and no --platform
# to pass. The digest lives in one file so a bump is a one-line change that CI and this wrapper
# pick up together; a second copy here would drift, and the failure mode is CI green while you
# build against a different toolchain.
IMAGE="$(cat "${REPO_ROOT}/.engine-build-image")"
test -n "${IMAGE}" || { echo "empty .engine-build-image" >&2; exit 1; }

# The image's manylinux_2_28 base holds the glibc >= 2.28 floor, so the shim links the fetched
# runtime at that floor. ITERS/WARMUP forward to the bench/QA scripts when set (harmless for
# build.sh, which ignores them).
#
# Resource limits for the container. A host-side systemd scope does NOT contain this:
# dockerd is a root daemon, so container processes are children of containerd-shim in the
# system slice, not of this shell. A runaway test here has taken down unrelated host
# processes, so the limit goes on the container or nowhere.
#   --memory-swap equal to --memory disables swap; without it Docker grants an equal
#     amount by default and the box thrashes instead of failing fast.
#   --cpuset-cpus caps parallelism for free: nproc inside resolves to the set's size, so
#     JOBS="${JOBS:-$(nproc)}" in build_qa.sh follows automatically.
IR_MEMORY="${IR_MEMORY:-8g}"
IR_CPUSET="${IR_CPUSET:-0-3}"

docker run --rm \
    --memory="${IR_MEMORY}" \
    --memory-swap="${IR_MEMORY}" \
    --cpuset-cpus="${IR_CPUSET}" \
    -e HOST_UID="$(id -u)" \
    -e HOST_GID="$(id -g)" \
    -e ITERS \
    -e WARMUP \
    -v "${REPO_ROOT}":/workspace \
    -w /workspace \
    "${IMAGE}" \
    /bin/bash "/workspace/${TARGET_SCRIPT}"
