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
# Note: only build.sh chowns its outputs back to you; bench/qa/variants leave root-owned dirs
# (see the "Container file ownership" note in README.md).
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Optional first arg: the native/ script to run in the container (default the shim build).
TARGET_SCRIPT="${1:-native/build.sh}"

if [ ! -f "${REPO_ROOT}/amazon-corretto-linux-jdk.rpm" ]; then
  echo "Downloading Amazon Corretto JDK RPM to ${REPO_ROOT}/amazon-corretto-linux-jdk.rpm"
  curl -L -o "${REPO_ROOT}/amazon-corretto-linux-jdk.rpm" \
    https://corretto.aws/downloads/latest/amazon-corretto-8-x64-linux-jdk.rpm
fi

# Must use manylinux_2_28 (glibc >= 2.28) so the shim links the fetched runtime at the 2.28 floor.
# Override the runtime variant with ET_RUNTIME_VARIANT (default logging). ITERS/WARMUP forward to
# the bench/QA scripts when set (harmless for build.sh, which ignores them).
docker run --rm \
    -e HOST_UID="$(id -u)" \
    -e HOST_GID="$(id -g)" \
    -e ITERS \
    -e WARMUP \
    -v "${REPO_ROOT}":/workspace \
    -w /workspace \
    quay.io/pypa/manylinux_2_28_x86_64:latest \
    /bin/bash "/workspace/${TARGET_SCRIPT}"
