#!/usr/bin/env bash
# UBSan gate for the JNI shim, driven by the JVM suite.
#
# This is the ONLY configuration in which native/jni/iree_djl_jni.cpp is instrumented.
# native/build_qa.sh covers iree_djl_core, the Catch2 suites and the leak harness, but
# native/CMakeLists.txt skips the shim under ASan/TSan so QA stays JVM-free. UBSan is the
# exception: it needs no runtime preload, and -static-libubsan folds its runtime into the
# .so, so a stock JVM can dlopen it.
#
# NOTE: a UB hit here presents as a JVM HARD CRASH mid-test, not a Java exception or an
# assertion failure. That is the gate working. Look for the "runtime error:" line and its
# stack trace above the JVM's own crash output.
#
# The instrumented .so is NEVER staged into src/main/resources -- it is reached through
# IREE_LIBRARY_PATH, which LibUtils honours ahead of the classpath copy and which
# build.gradle.kts already declares as a Test task input. So this script leaves the plain
# tree in native/build untouched and does not require a rebuild afterwards.
#
# Linux only: MSVC has no UndefinedBehaviorSanitizer.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

BUILD_DIR="${BUILD_DIR:-native/ubsan}"
JOBS="${JOBS:-$(nproc)}"

# All four test tasks, not just `test`. tasks.test excludes the leak/oom/stress tags, and
# oomTest is a scripted reproduction of issue 16 -- the only task that drives the
# allocation-failure paths in the output marshalling loop. stressTest and oomTest do not
# run in CI, so this local sequence is the only place they meet an instrumented shim.
TEST_TASKS="${TEST_TASKS:-test leakTest oomTest stressTest}"

# --no-daemon is not a preference. A pre-existing Gradle daemon lives in whatever cgroup it
# was first started in, so `./gradlew` would hand the work -- including every forked test
# JVM -- to a process outside any resource scope wrapping this script. oomTest exhausts a
# heap on purpose; letting that escape has taken down unrelated processes on this host.
GRADLE_FLAGS="${GRADLE_FLAGS:---no-daemon}"

# UBSan's default is print-and-continue; -fno-sanitize-recover (native/CMakeLists.txt)
# makes it abort, and these make the abort legible.
export UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1

echo "--- Building the UBSan-instrumented shim ---"
rm -rf "${BUILD_DIR}"
cmake -S native -B "${BUILD_DIR}" -G "Unix Makefiles" \
  -DIREE_DJL_UBSAN=ON -DIREE_DJL_BUILD_TESTS=OFF -DCMAKE_BUILD_TYPE=Debug
cmake --build "${BUILD_DIR}" --target iree_djl -j"${JOBS}"

# A dynamic libubsan dependency means -static-libubsan did not apply, and System.load
# would fail with a confusing linker error. Assert before running so a failure names its
# own cause -- the same courtesy native/build_qa.sh extends for the Windows CRT check.
if ldd "${BUILD_DIR}/libiree_djl.so" | grep -qi ubsan; then
  echo "FAIL: ${BUILD_DIR}/libiree_djl.so has a dynamic libubsan dependency; -static-libubsan did not apply" >&2
  exit 1
fi
echo "--- UBSan runtime is statically linked ---"

echo "--- JVM suite against the instrumented shim (${TEST_TASKS}) ---"
# --rerun-tasks because a cached UP-TO-DATE result would report a pass for a run that
# never loaded this library.
IREE_LIBRARY_PATH="$(pwd)/${BUILD_DIR}/libiree_djl.so" \
  ./gradlew ${GRADLE_FLAGS} ${TEST_TASKS} --rerun-tasks

echo "--- UBSan gate PASS ---"
