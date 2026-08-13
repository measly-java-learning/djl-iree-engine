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
# TWO PHASES, because they need different environments. The pinned container has the right
# toolchain but the wrong JDK (Corretto 8, for the oldest supported jni.h), and Gradle 9.6.1
# with this project's JDK 17 toolchain cannot run there at all. So: build in the container,
# test on the host. IREE_DJL_UBSAN_MODE selects a phase; it defaults to `auto`, which is
# build-only inside the image and both phases outside it.
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

# The build phase runs as root under native/local_build_wrapper.sh, so without this the
# tree comes back root-owned and the NEXT run's `rm -rf "${BUILD_DIR}"` below dies with a
# bare "Permission denied" that names neither the container nor the cause. That is not
# hypothetical: it is exactly what happened between the container build phase and the
# following host run during this gate's own bring-up.
#
# The trap is a no-op outside the container -- ir_chown_cleanup only acts when HOST_UID is
# set, which only the wrapper does -- so registering it unconditionally is safe. No OS fork
# here, unlike build_qa.sh: this script is Linux-only by construction.
# shellcheck source=native/container_env.sh
. "${REPO_ROOT}/native/container_env.sh"
ir_chown_outputs_on_exit "${BUILD_DIR}"

# Two phases, because they need different environments and cannot share one.
#
#   build  needs gcc + jni.h. Runs happily in the pinned container, which is where the
#          instrumentation SHOULD be produced: pinned toolchain, pinned libubsan NEVRA.
#   test   needs Gradle 9.6.1 and a JDK 17 toolchain. The container CANNOT provide that --
#          its JAVA_HOME is Corretto 1.8.0_502, chosen deliberately for the oldest supported
#          jni.h -- so the JVM phase runs on the host, against the .so the build phase left
#          behind.
#
# Default is `auto`: build-only inside the pinned image, both phases outside it. The script
# knows where it is, so no caller has to remember.
MODE="${IREE_DJL_UBSAN_MODE:-auto}"
if [ "${MODE}" = "auto" ]; then
  if [ -n "${MEASLY_DJL_PINNED_IMAGE:-}" ]; then MODE=build; else MODE=all; fi
fi
case "${MODE}" in
  build|test|all) ;;
  *) echo "IREE_DJL_UBSAN_MODE must be build, test, all or auto (got '${MODE}')" >&2; exit 1 ;;
esac

# All four test tasks, not just `test`. tasks.test excludes the leak/oom/stress tags, and
# oomTest is the only task that drives the allocation-failure paths in the output
# marshalling loop. stressTest and oomTest do not
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

if [ "${MODE}" = "build" ] || [ "${MODE}" = "all" ]; then
  echo "--- Building the UBSan-instrumented shim ---"
  rm -rf "${BUILD_DIR}"
  cmake -S native -B "${BUILD_DIR}" -G "Unix Makefiles" \
    -DIREE_DJL_UBSAN=ON -DIREE_DJL_BUILD_TESTS=OFF -DCMAKE_BUILD_TYPE=Debug
  cmake --build "${BUILD_DIR}" --target iree_djl -j"${JOBS}"

  # A dynamic libubsan dependency means -static-libubsan did not apply, and System.load
  # would fail with a confusing linker error. Assert here so a failure names its own cause
  # -- the same courtesy native/build_qa.sh extends for the Windows CRT check. This matters
  # more across the phase split: the build may happen in a container and the load on a host
  # hours later, in a different job.
  if ldd "${BUILD_DIR}/libiree_djl.so" | grep -qi ubsan; then
    echo "FAIL: ${BUILD_DIR}/libiree_djl.so has a dynamic libubsan dependency; -static-libubsan did not apply" >&2
    exit 1
  fi
  echo "--- UBSan runtime is statically linked ---"
fi

if [ "${MODE}" = "build" ]; then
  echo "--- UBSan shim built at ${BUILD_DIR}/libiree_djl.so; JVM phase skipped ---"
  echo "--- Run the JVM phase where a JDK 17 lives: IREE_DJL_UBSAN_MODE=test ./native/ubsan_gate.sh ---"
  exit 0
fi

# Refuse the JVM phase rather than letting Gradle fail obscurely. The container's JAVA_HOME
# is Corretto 8; Gradle 9.6.1 and the project's JDK 17 toolchain both need 17+.
if [ -n "${MEASLY_DJL_PINNED_IMAGE:-}" ]; then
  echo "REFUSING the JVM phase inside the pinned image: JAVA_HOME is Corretto 8, and Gradle" >&2
  echo "9.6.1 with a JDK 17 toolchain cannot run there. Build here, test on the host:" >&2
  echo "  ./native/local_build_wrapper.sh native/ubsan_gate.sh   # build phase, in-container" >&2
  echo "  IREE_DJL_UBSAN_MODE=test ./native/ubsan_gate.sh        # JVM phase, on the host" >&2
  exit 1
fi

_java_major="$("${JAVA_HOME:-/usr}/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
if [ "${_java_major:-0}" -lt 17 ]; then
  echo "JAVA_HOME points at Java ${_java_major}; Gradle 9.6.1 and this project need 17+." >&2
  exit 1
fi

if [ ! -f "${BUILD_DIR}/libiree_djl.so" ]; then
  echo "no instrumented shim at ${BUILD_DIR}/libiree_djl.so -- run the build phase first" >&2
  exit 1
fi

echo "--- JVM suite against the instrumented shim (${TEST_TASKS}) ---"
# --rerun-tasks because a cached UP-TO-DATE result would report a pass for a run that
# never loaded this library.
IREE_LIBRARY_PATH="$(pwd)/${BUILD_DIR}/libiree_djl.so" \
  ./gradlew ${GRADLE_FLAGS} ${TEST_TASKS} --rerun-tasks

echo "--- UBSan gate PASS ---"
