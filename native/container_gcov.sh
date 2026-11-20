#!/usr/bin/env bash
# gcov from the pinned toolchain image, for gcovr's --gcov-executable.
#
# native/coverage.sh builds its tree in the pinned image (gcc-toolset, gcc 14.2.1) and reads
# the counters on the host. A host gcov of the same MAJOR version is not enough: the .gcda
# version stamp encodes the exact release, so Ubuntu's gcov-14 (14.2.0, stamp 'B42*') warns
# on every file built by 14.2.1 ('B42 ') and exits 3, and gcovr discards the result. The
# only gcov guaranteed to match is the one shipped beside the compiler, so this runs it.
#
# gcovr invokes gcov with a working directory under the repo and writes gcov's output there,
# so the repo is mounted at its own path (as the build was, see native/coverage.sh), the
# container starts in the caller's directory, and it runs as the caller so the output files
# are theirs. Sources the build recorded from inside the image -- jni.h, libstdc++ -- exist
# here too, which a host gcov cannot offer.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="$(cat "${REPO_ROOT}/.engine-build-image")"
test -n "${IMAGE}" || { echo "empty .engine-build-image" >&2; exit 1; }

exec docker run --rm -i \
  -u "$(id -u):$(id -g)" \
  -v "${REPO_ROOT}:${REPO_ROOT}" \
  -w "${PWD}" \
  "${IMAGE}" \
  gcov "$@"
