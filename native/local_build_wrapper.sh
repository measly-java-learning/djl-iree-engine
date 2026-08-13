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
#   IR_MEMORY=12g ./native/local_build_wrapper.sh native/ubsan_gate.sh   # raise the cap
# build.sh, build_qa.sh and ubsan_gate.sh all chown their outputs back to you on exit (see
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
