#!/usr/bin/env bash
# gcov line coverage for native/core and the JNI shim, driven by the Catch2 suites AND the
# JVM suite, reported as Cobertura XML at native/coverage/coverage.xml.
#
# The Catch2 suites only reach native/core. native/jni/iree_djl_jni.cpp runs only under a
# JVM, so a Catch2-only number would report the shim -- where issues 15, 16 and 17 all
# lived -- as dead code. Both drivers write into the SAME .gcda files (iree_djl_core is a
# static library linked into the test executables and the .so alike), so gcov merges their
# counts with no extra step.
#
# TWO PHASES, for the same reason as native/ubsan_gate.sh: the pinned container has the
# toolchain but a Corretto 8 JAVA_HOME, and Gradle 9.6.1 with a JDK 17 toolchain cannot run
# there. IREE_DJL_COVERAGE_MODE selects a phase and defaults to `auto` -- build-only inside
# the image, both phases outside it.
#
#   build  configure + build native/coverage with -DIREE_DJL_COVERAGE=ON, run the Catch2
#          suites. Needs gcc + jni.h.
#   test   run the JVM suite against native/coverage/libiree_djl.so through
#          IREE_LIBRARY_PATH, then turn the .gcda files into the report with gcovr. Needs
#          JDK 17, gcovr, and a gcov of the SAME major version as the compiler.
#
# PATHS ARE ABSOLUTE. gcc bakes the object directory into the binaries, and the .so writes
# its .gcda files there when the JVM exits. So the build must happen at the same absolute
# path the JVM phase runs from. Through the wrapper that means mounting the repo at its host
# path rather than /workspace:
#   IR_WORKDIR="$PWD" ./native/local_build_wrapper.sh native/coverage.sh
#   IREE_DJL_COVERAGE_MODE=test ./native/coverage.sh
# The test phase checks this and says so, rather than reporting zero coverage.
#
# The instrumented .so is NEVER staged into src/main/resources, same rule as the UBSan
# tree. Linux only: MSVC has no gcov.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

BUILD_DIR="${BUILD_DIR:-native/coverage}"
JOBS="${JOBS:-$(nproc)}"

# shellcheck source=native/container_env.sh
. "${REPO_ROOT}/native/container_env.sh"
ir_chown_outputs_on_exit "${BUILD_DIR}"

MODE="${IREE_DJL_COVERAGE_MODE:-auto}"
if [ "${MODE}" = "auto" ]; then
  if [ -n "${MEASLY_DJL_PINNED_IMAGE:-}" ]; then MODE=build; else MODE=all; fi
fi
case "${MODE}" in
  build|test|all) ;;
  *) echo "IREE_DJL_COVERAGE_MODE must be build, test, all or auto (got '${MODE}')" >&2; exit 1 ;;
esac

# `test leakTest`, not ubsan_gate.sh's four tasks: oomTest needs the pip iree-compile for
# its fixture, which CI does not carry, and stressTest adds runtime without adding lines.
TEST_TASKS="${TEST_TASKS:-test leakTest}"

# --no-daemon: see native/ubsan_gate.sh. A daemon started elsewhere escapes any resource
# scope around this script. It also matters for correctness here: the .gcda files are
# written when the forked test JVM exits, and the worker must be a fresh process.
GRADLE_FLAGS="${GRADLE_FLAGS:---no-daemon}"

if [ "${MODE}" = "build" ] || [ "${MODE}" = "all" ]; then
  echo "--- Building the coverage-instrumented shim and Catch2 suites ---"
  # A fresh tree is also what zeroes the counters: stale .gcda files from an earlier run
  # would be merged into this one.
  rm -rf "${BUILD_DIR}"
  cmake -S native -B "${BUILD_DIR}" -G "Unix Makefiles" \
    -DIREE_DJL_COVERAGE=ON -DCMAKE_BUILD_TYPE=Debug
  cmake --build "${BUILD_DIR}" --target iree_djl iree_runtime_test iree_params_test -j"${JOBS}"

  echo "--- Catch2 unit suite (coverage) ---"
  "./${BUILD_DIR}/iree_runtime_test"
  echo "--- Catch2 parameter suite (coverage) ---"
  "./${BUILD_DIR}/iree_params_test"
fi

if [ "${MODE}" = "build" ]; then
  echo "--- Coverage tree built at ${BUILD_DIR}; JVM phase skipped ---"
  echo "--- Run the JVM phase where a JDK 17 lives: IREE_DJL_COVERAGE_MODE=test ./native/coverage.sh ---"
  exit 0
fi

if [ -n "${MEASLY_DJL_PINNED_IMAGE:-}" ]; then
  echo "REFUSING the JVM phase inside the pinned image: JAVA_HOME is Corretto 8, and Gradle" >&2
  echo "9.6.1 with a JDK 17 toolchain cannot run there. Build here, test on the host:" >&2
  echo "  IR_WORKDIR=\"\$PWD\" ./native/local_build_wrapper.sh native/coverage.sh" >&2
  echo "  IREE_DJL_COVERAGE_MODE=test ./native/coverage.sh" >&2
  exit 1
fi

if [ ! -f "${BUILD_DIR}/libiree_djl.so" ]; then
  echo "no instrumented shim at ${BUILD_DIR}/libiree_djl.so -- run the build phase first" >&2
  exit 1
fi

# The build-path check described in the header. CMAKE_HOME_DIRECTORY is the source dir the
# tree was configured from; if it is not this checkout's native/, the .so will write its
# .gcda files somewhere this run never looks (or fail to create them at all).
_built_from="$(sed -n 's/^CMAKE_HOME_DIRECTORY:INTERNAL=//p' "${BUILD_DIR}/CMakeCache.txt")"
if [ "${_built_from}" != "${REPO_ROOT}/native" ]; then
  echo "${BUILD_DIR} was configured from ${_built_from}, not ${REPO_ROOT}/native." >&2
  echo "gcov paths are absolute, so build at the host path:" >&2
  echo "  IR_WORKDIR=\"\$PWD\" ./native/local_build_wrapper.sh native/coverage.sh" >&2
  exit 1
fi

# gcov must match the compiler EXACTLY. The .gcda/.gcno version stamp encodes the release,
# not just the major: the pinned image's gcc 14.2.1 writes 'B42 ', Ubuntu's gcov-14 (14.2.0)
# expects 'B42*', warns on every file and exits 3, and gcovr throws the result away (measured
# in CI). So a host gcov is used only when its version string equals the compiler's -- the
# host-native build case. Otherwise the gcov shipped in the pinned image runs, through
# native/container_gcov.sh. The compiler version comes from the tree itself.
_cc_ver="$(sed -n 's/^set(CMAKE_CXX_COMPILER_VERSION "\(.*\)")$/\1/p' \
  "${BUILD_DIR}"/CMakeFiles/*/CMakeCXXCompiler.cmake | head -1)"
_cc_major="${_cc_ver%%.*}"
_gcov_ver() { "$1" --version 2>/dev/null | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1; }
if [ -z "${GCOV:-}" ]; then
  for _cand in "gcov-${_cc_major}" gcov; do
    if command -v "${_cand}" >/dev/null 2>&1 && [ "$(_gcov_ver "${_cand}")" = "${_cc_ver}" ]; then
      GCOV="${_cand}"; break
    fi
  done
fi
if [ -z "${GCOV:-}" ] && command -v docker >/dev/null 2>&1; then
  GCOV="${REPO_ROOT}/native/container_gcov.sh"
fi
if [ -z "${GCOV:-}" ] || [ "$(_gcov_ver "${GCOV}")" != "${_cc_ver}" ]; then
  echo "need a gcov of exactly ${_cc_ver} to read ${BUILD_DIR} (got: ${GCOV:-none})." >&2
  echo "A container build reads through native/container_gcov.sh, which needs docker." >&2
  exit 1
fi
command -v gcovr >/dev/null 2>&1 || { echo "gcovr not on PATH (pip install gcovr)" >&2; exit 1; }

_java_major="$("${JAVA_HOME:-/usr}/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
if [ "${_java_major:-0}" -lt 17 ]; then
  echo "JAVA_HOME points at Java ${_java_major}; Gradle 9.6.1 and this project need 17+." >&2
  exit 1
fi

echo "--- JVM suite against the instrumented shim (${TEST_TASKS}) ---"
# --rerun-tasks because a cached UP-TO-DATE result would write no .gcda at all.
IREE_LIBRARY_PATH="$(pwd)/${BUILD_DIR}/libiree_djl.so" \
  ./gradlew ${GRADLE_FLAGS} ${TEST_TASKS} --rerun-tasks

# A JVM that never loaded the shim, or never exited normally, leaves the shim's .gcda
# missing -- and gcovr would then report the shim as 0% rather than fail.
if ! find "${BUILD_DIR}" -name 'iree_djl_jni.cpp.gcda' | grep -q .; then
  echo "no .gcda for the JNI shim: the JVM suite did not load ${BUILD_DIR}/libiree_djl.so" >&2
  exit 1
fi

echo "--- gcovr report (gcov: ${GCOV}) ---"
gcovr --root "${REPO_ROOT}" \
  --filter 'native/core/' --filter 'native/jni/' \
  --gcov-executable "${GCOV}" \
  --cobertura "${BUILD_DIR}/coverage.xml" \
  --print-summary \
  "${BUILD_DIR}"

echo "--- Coverage report at ${BUILD_DIR}/coverage.xml ---"
