#!/bin/bash
set -ex # Fail on error, print commands to log

# Runs a native/ script inside the manylinux_2_28 container — the environment the GHA workflow uses
# in CI.  This is the BLESSED way to run the native scripts: the toolchain matches, and a
# shim built here keeps its glibc-2.28 floor (RHEL8). Running these scripts directly on the host
# works but breaks the floor (build.sh) or collides on a container-made cache (bench/qa wipe theirs).
#
# Usage: ./native/local_build_wrapper.sh [script]   (default: native/build.sh)
#   ./native/local_build_wrapper.sh native/bench.sh
#   ITERS=2000 ./native/local_build_wrapper.sh native/build_qa.sh
#   ./native/local_build_wrapper.sh native/build_variants.sh
# build.sh and build_qa.sh both chown their outputs back to you on exit (see
# native/container_env.sh). Other native/ scripts run through this wrapper do not yet, and will
# leave root-owned dirs behind.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Optional first arg: the native/ script to run in the container (default the shim build).
TARGET_SCRIPT="${1:-native/build.sh}"

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

# The image's manylinux_2_28 base holds the glibc >= 2.28 floor, so the shim links the fetched
# runtime at that floor. ITERS/WARMUP forward to the bench/QA scripts when set (harmless for
# build.sh, which ignores them).
docker run --rm \
    -e HOST_UID="$(id -u)" \
    -e HOST_GID="$(id -g)" \
    -e ITERS \
    -e WARMUP \
    -v "${REPO_ROOT}":/workspace \
    -w /workspace \
    "${IMAGE}" \
    /bin/bash "/workspace/${TARGET_SCRIPT}"
