#!/usr/bin/env bash
# Host build, linux-x86_64 only. No container: this skeleton has no glibc
# floor to hold because it ships nothing. See the spec's deferred list.
set -euo pipefail

# Host fork. Under Git-Bash on Windows `uname -s` is MINGW64_NT-* or MSYS_NT-*. The caller must have
# already activated the MSVC dev shell (see .github/workflows/native-build-job.yml); this script does
# not activate VS itself. Everything Linux-only below (Corretto RPM, chown, dnf, nproc) is skipped.
case "$(uname -s)" in
  MINGW*|MSYS*) IR_HOST_OS=windows ;;
  *)            IR_HOST_OS=linux ;;
esac

# Container bind-mount outputs are root-owned on the host; chown them back on exit (any status).
cleanup() {
  rc=$?
  if [ -n "${HOST_UID:-}" ]; then
    chown -R "${HOST_UID}:${HOST_GID}" "${build_dir}" src/main/resources/native/linux* 2>/dev/null || true
  fi
  exit "$rc"
}
[ "${IR_HOST_OS}" = "linux" ] && trap cleanup EXIT


here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
build_dir="${here}/build"
build_type="${BUILD_TYPE:-RelWithDebInfo}"


# This script expects:
# 1. To be running inside quay.io/pypa/manylinux_2_28_x86_64 (glibc-2.28 floor for the shipped .so)
# 2. The Corretto RPM downloaded to /workspace
# The runtime tarball is fetched by CMake during the shim configure (also inside the container,
# so the fetched runtime is linked on glibc 2.28).

if [ "${IR_HOST_OS}" = "windows" ]; then
  echo "--- Using the runner's JDK headers (headers-only; we never link libjvm) ---"
  test -n "${JAVA_HOME:-}" || { echo "JAVA_HOME must be set on Windows (see setup-java)"; exit 1; }
  # Git-Bash gives JAVA_HOME as a Windows path; cmake accepts it, but the test below needs a POSIX path.
  JAVA_HOME="$(cygpath -u "${JAVA_HOME}" 2>/dev/null || echo "${JAVA_HOME}")"
  export JAVA_HOME
  test -f "${JAVA_HOME}/include/win32/jni_md.h" \
    || { echo "JDK headers not found under JAVA_HOME=${JAVA_HOME} (expected include/win32/jni_md.h)"; exit 1; }
  echo "JAVA_HOME=${JAVA_HOME}"
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
  echo "JAVA_HOME=${JAVA_HOME}"
fi

if [ "${IR_HOST_OS}" = "windows" ]; then
  echo "--- Toolchain Versions (MSVC dev shell must already be activated by the caller) ---"
  command -v cl >/dev/null 2>&1 || { echo "cl.exe not on PATH: activate the VS dev shell first"; exit 1; }
  command -v ninja >/dev/null 2>&1 || { echo "ninja not on PATH: activate the VS dev shell first"; exit 1; }
  cl 2>&1 | head -1; cmake --version; ninja --version
else
  echo "--- Setting up Ninja (the shim configures with -G Ninja) ---"
  export PATH="/opt/python/cp312-cp312/bin:${PATH}"
  pip install ninja
  echo "--- Toolchain Versions ---"
  gcc --version; g++ --version; cmake --version; ninja --version
fi




cmake -S "${here}" -B "${build_dir}" -G Ninja \
  -DCMAKE_BUILD_TYPE="${build_type}" \
  "$@"

cmake --build "${build_dir}"

if [ "${IR_HOST_OS}" = "windows" ]; then
  OUT_PLATFORM="windows-x86_64"; OUT_LIB="iree_djl.dll"
else
  OUT_PLATFORM="linux-x86_64";   OUT_LIB="libiree_djl.so"
fi

# Stage the shim where LibUtils' classpath fallback expects it, once it exists.
lib="${build_dir}/${OUT_LIB}"
if [[ -f "${lib}" ]]; then
  dest="${here}/../src/main/resources/native/${OUT_PLATFORM}"
  mkdir -p "${dest}"
  cp "${lib}" "${dest}/"
  # Also stage element_types.json so the Java build can find it without the CMake build tree
  IR_RUNTIME_ROOT="${build_dir}/_deps/iree_runtime_dist-src"
  test -f "${IR_RUNTIME_ROOT}/share/iree-runtime-dist/element_types.json" \
    || { echo "element_types.json file missing under ${IR_RUNTIME_ROOT}"; exit 1; }
  cp "${IR_RUNTIME_ROOT}/share/iree-runtime-dist/element_types.json" "${dest}/"
  echo "Artifact: ${dest}/${OUT_LIB}"

  # Third-party notices from the resolved runtime tree. Required — never ship a binary without them. 
  test -f "${IR_RUNTIME_ROOT}/LICENSE" && test -d "${IR_RUNTIME_ROOT}/THIRD-PARTY-NOTICES" \
    || { echo "runtime notices missing under ${IR_RUNTIME_ROOT} (LICENSE + THIRD-PARTY-NOTICES/)"; exit 1; }
  LIC_OUT="${dest}/licenses"
  test -n "${LIC_OUT}" && rm -rf "${LIC_OUT}"
  mkdir -p "${LIC_OUT}"
  cp "${IR_RUNTIME_ROOT}/LICENSE" "${LIC_OUT}/"
  cp -r "${IR_RUNTIME_ROOT}/THIRD-PARTY-NOTICES" "${LIC_OUT}/"
  echo "Notices: ${LIC_OUT} ($(find "${LIC_OUT}" -type f | wc -l) files)"
fi
