#!/usr/bin/env bash
# Host build: the staging platform follows the build host's architecture (linux-x86_64 or
# linux-aarch64). No container: this skeleton has no glibc floor to hold because it ships
# nothing. See the spec's deferred list.
set -euo pipefail

# Host fork. Under Git-Bash on Windows `uname -s` is MINGW64_NT-* or MSYS_NT-*. The caller must have
# already activated the MSVC dev shell (see .github/workflows/native-build-job.yml); this script does
# not activate VS itself. Everything Linux-only below (Corretto RPM, chown, dnf, nproc) is skipped.
case "$(uname -s)" in
  MINGW*|MSYS*) IR_HOST_OS=windows ;;
  *)            IR_HOST_OS=linux ;;
esac

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
build_dir="${here}/build"
build_type="${BUILD_TYPE:-RelWithDebInfo}"

# shellcheck source=native/container_env.sh
. "${here}/container_env.sh"
if [ "${IR_HOST_OS}" = "linux" ]; then
  ir_chown_outputs_on_exit "${build_dir}" 'src/main/resources/native/linux*'
fi


# This script expects:
# 1. To be running inside the pinned toolchain image (docker/<platform>.Dockerfile), which
#    bakes the glibc-2.28 floor via its manylinux_2_28 base and supplies JAVA_HOME + ninja
# 2. Failing that, a manylinux_2_28 base with amazon-corretto-linux-jdk.rpm at /workspace
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
  # Fast path: the pinned toolchain image (docker/linux-*.Dockerfile) bakes the Corretto 8 headers
  # in and exports JAVA_HOME, so there is nothing to extract. The fallback below is for running
  # this script directly on a host, or inside a bare manylinux base — in which case you supply
  # amazon-corretto-linux-jdk.rpm at the repo root yourself.
  if [ -n "${JAVA_HOME:-}" ] && [ -f "${JAVA_HOME}/include/linux/jni_md.h" ]; then
    echo "--- Using the image's baked Corretto JDK headers (headers-only; we never link libjvm) ---"
    export JAVA_HOME
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
  fi
  echo "JAVA_HOME=${JAVA_HOME}"
fi

if [ "${IR_HOST_OS}" = "windows" ]; then
  echo "--- Toolchain Versions (MSVC dev shell must already be activated by the caller) ---"
  command -v cl >/dev/null 2>&1 || { echo "cl.exe not on PATH: activate the VS dev shell first"; exit 1; }
  command -v ninja >/dev/null 2>&1 || { echo "ninja not on PATH: activate the VS dev shell first"; exit 1; }
  cl 2>&1 | head -1; cmake --version; ninja --version
else
  echo "--- Setting up Ninja (the shim configures with -G Ninja) ---"
  # The pinned image bakes ninja in at an exact version; a miss there means a broken image,
  # not something to paper over with an unpinned pip install.
  if [ -n "${IREE_DJL_PINNED_IMAGE:-}" ]; then
    command -v ninja >/dev/null 2>&1 || {
      echo "BROKEN IMAGE: ninja is not on PATH in the pinned image; rebuild it." >&2; exit 1; }
    [ "$(ninja --version)" = "${IREE_DJL_NINJA_VERSION}" ] || {
      echo "BROKEN IMAGE: ninja $(ninja --version), image pins ${IREE_DJL_NINJA_VERSION}." >&2; exit 1; }
  else
    # Same rule as build_qa.sh: this script installs nothing. `pip install ninja` here was
    # unpinned against an image that pins 1.13.0, and on the Ubuntu workstation and runner
    # ninja is already on PATH from the distro anyway (1.11.1, measured) -- so the install
    # only ever fired in environments nobody uses, at whatever version PyPI served that day.
    command -v ninja >/dev/null 2>&1 || {
      echo "ninja is not on PATH. Install it (Debian/Ubuntu: apt install ninja-build) or run" >&2
      echo "through the pinned image: ./native/local_build_wrapper.sh native/build.sh" >&2
      exit 1
    }
    echo "--- WARNING: not the pinned image; ninja $(ninja --version) is unpinned ---"
  fi
  echo "--- Toolchain Versions ---"
  gcc --version; g++ --version; cmake --version; ninja --version
fi




# -DIREE_DJL_BUILD_TESTS=OFF: this is the SHIPPING build, and it stages only the .so plus the
# runtime's licence tree. Leaving the Catch2 suites in meant cloning and compiling Catch2 here as
# well as in native/build_qa.sh — 107 objects and two ~24 MB binaries, discarded, on every arch of
# every CI run. The Catch2 fetch happens at CONFIGURE time, so a --target on the build line alone
# would not have avoided it.
#
# Placed BEFORE "$@" deliberately: the last -D on a cmake command line wins, so
#   ./native/build.sh -DIREE_DJL_BUILD_TESTS=ON
# still gets you native/build/iree_runtime_test for local iteration.
#
# The build line stays untargeted. `--target iree_djl` would be wrong here: under
# -DIREE_DJL_SANITIZE=ON / -DIREE_DJL_TSAN=ON that target does not exist at all (see the JNI shim
# guard in native/CMakeLists.txt), and both are documented local workflows. iree_leak_harness and
# iree_copy_bench stay in `all` — they cost 2 edges each and the sanitizer recipes in README.md
# run them straight out of this tree.
cmake -S "${here}" -B "${build_dir}" -G Ninja \
  -DCMAKE_BUILD_TYPE="${build_type}" \
  -DIREE_DJL_BUILD_TESTS=OFF \
  "$@"

cmake --build "${build_dir}"

if [ "${IR_HOST_OS}" = "windows" ]; then
  OUT_PLATFORM="windows-x86_64"; OUT_LIB="iree_djl.dll"
else
  case "$(uname -m)" in
    x86_64|amd64)  OUT_PLATFORM="linux-x86_64" ;;
    aarch64|arm64) OUT_PLATFORM="linux-aarch64" ;;
    *) echo "unsupported arch: $(uname -m)" >&2; exit 1 ;;
  esac
  OUT_LIB="libiree_djl.so"
fi

# Stage the shim where LibUtils' classpath fallback expects it, once it exists.
lib="${build_dir}/${OUT_LIB}"
if [[ -f "${lib}" ]]; then
  dest="${here}/../src/main/resources/native/${OUT_PLATFORM}"
  mkdir -p "${dest}"
  cp "${lib}" "${dest}/"
  echo "Artifact: ${dest}/${OUT_LIB}"

  # Third-party notices from the resolved runtime tree. Required — never ship a binary without them. 
  IR_RUNTIME_ROOT="${build_dir}/_deps/iree_runtime_dist-src"
  test -f "${IR_RUNTIME_ROOT}/LICENSE" && test -d "${IR_RUNTIME_ROOT}/THIRD-PARTY-NOTICES" \
    || { echo "runtime notices missing under ${IR_RUNTIME_ROOT} (LICENSE + THIRD-PARTY-NOTICES/)"; exit 1; }
  LIC_OUT="${dest}/licenses"
  test -n "${LIC_OUT}" && rm -rf "${LIC_OUT}"
  mkdir -p "${LIC_OUT}"
  cp "${IR_RUNTIME_ROOT}/LICENSE" "${LIC_OUT}/"
  cp -r "${IR_RUNTIME_ROOT}/THIRD-PARTY-NOTICES" "${LIC_OUT}/"
  echo "Notices: ${LIC_OUT} ($(find "${LIC_OUT}" -type f | wc -l) files)"
fi
