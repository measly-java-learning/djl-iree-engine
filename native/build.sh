#!/usr/bin/env bash
# Host build, linux-x86_64 only. No container: this skeleton has no glibc
# floor to hold because it ships nothing. See the spec's deferred list.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
build_dir="${here}/build"
build_type="${BUILD_TYPE:-RelWithDebInfo}"

cmake -S "${here}" -B "${build_dir}" -G Ninja \
  -DCMAKE_BUILD_TYPE="${build_type}" \
  ${IREE_INSTALL:+-DIREE_INSTALL="${IREE_INSTALL}"} \
  ${IREE_SOURCE:+-DIREE_SOURCE="${IREE_SOURCE}"} \
  "$@"

cmake --build "${build_dir}"

# Stage the shim where LibUtils' classpath fallback expects it, once it exists.
lib="${build_dir}/libiree_djl.so"
if [[ -f "${lib}" ]]; then
  dest="${here}/../src/main/resources/native/linux-x86_64"
  mkdir -p "${dest}"
  cp "${lib}" "${dest}/"
  echo "staged: ${dest}/libiree_djl.so"
fi
