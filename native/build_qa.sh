#!/usr/bin/env bash
# Build + run the native QA targets (Catch2 units + ASan/LSan leak harness) against the
# resolved iree-runtime-dist runtime. NOT part of the shipping build: the QA targets are
# built with AddressSanitizer/LeakSanitizer into a distinct tree (native/qa), separate from
# the Release .so (native/build via native/build.sh).
#
# JVM-free: under a sanitizer, native/CMakeLists.txt skips the JNI shim, so NO JDK/JAVA_HOME
# is needed. In GitHub Actions, run this in the SAME manylinux_2_28 container as native/build.sh.
# TSan is intentionally absent — it needs ASLR disabled and runs as a local gate only
# (native/tsan_gate.sh).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

ITERS="${ITERS:-1000}"

# Host fork; see native/build.sh. Windows QA is Catch2-only under MSVC ASan:
# MSVC has ASan but no LeakSanitizer, so iree_leak_harness is structurally
# Linux-only and is not built or run here (accepted coverage reduction).
case "$(uname -s)" in
  MINGW*|MSYS*) IR_HOST_OS=windows ;;
  *)            IR_HOST_OS=linux ;;
esac

# shellcheck source=native/container_env.sh
. "${REPO_ROOT}/native/container_env.sh"
if [ "${IR_HOST_OS}" = "linux" ]; then
  # native/qa is this script's only output tree. Without this, wrapper-driven QA runs leave it
  # root-owned on the host — the exact gap native/build.sh's trap has always covered for builds.
  ir_chown_outputs_on_exit native/qa
fi

if [ "${IR_HOST_OS}" = "windows" ]; then
  command -v cl >/dev/null 2>&1 || { echo "cl.exe not on PATH: activate the VS dev shell first"; exit 1; }
  JOBS="${JOBS:-${NUMBER_OF_PROCESSORS:-4}}"
  rm -rf native/qa
  # No sanitizer flags here: the WIN32 branch of the sanitizer block in
  # native/CMakeLists.txt supplies /fsanitize=address and /INCREMENTAL:NO.
  # RelWithDebInfo, never Debug: Debug adds /RTC1 (incompatible with ASan) and
  # compiles against the Debug CRT flavour; the static CRT is forced globally
  # by CMAKE_MSVC_RUNTIME_LIBRARY and propagates into FetchContent'd Catch2.
  cmake -B native/qa -S native -G Ninja -DIREE_DJL_SANITIZE=ON \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo
  cmake --build native/qa --target iree_runtime_test iree_params_test -j"${JOBS}"

  # Catch2 comes in via FetchContent, so it is the one target whose CRT we do
  # not set directly — a /MD Catch2 inside this /MT test exe links with no
  # diagnostic at all, then corrupts the heap at runtime. Assert before running
  # so a failure names its own cause. No DLL argument: this tree builds no DLL.
  echo "--- CRT check: QA tree must be uniformly static (/MT) ---"
  bash native/tests/check_windows_crt.sh native/qa

  echo "--- Catch2 unit suite (ASan) ---"
  ./native/qa/iree_runtime_test.exe
  echo "--- Catch2 parameter suite (ASan) ---"
  ./native/qa/iree_params_test.exe
  echo "--- Leak harness SKIPPED: no LeakSanitizer under MSVC (Linux-only coverage) ---"
else
  # Fixtures are per-arch: .vmfb embeds an arch-specific ELF executable, so the
  # harness must load the fixture set matching this host (the Catch2 targets get
  # their paths from CMake compile definitions, which are already arch-aware;
  # the harness takes paths on argv). The .irpa files are arch-neutral data and
  # ship in both fixture dirs.
  case "$(uname -m)" in
    x86_64|amd64)  FIXTURE_DIR="src/test/resources/models" ;;
    aarch64|arm64) FIXTURE_DIR="src/test/resources/models/aarch64" ;;
    *) echo "unsupported arch: $(uname -m)" >&2; exit 1 ;;
  esac

  # QA is the only ASan consumer. The pinned toolchain image bakes the runtime in at the base
  # image's own compiler revision; this dnf call is the fallback for host runs and bare bases.
  TOOLSET_VER="$(gcc -dumpversion | cut -d. -f1)"
  if rpm -q --quiet "gcc-toolset-${TOOLSET_VER}-libasan-devel"; then
    echo "--- ASan runtime already present (gcc-toolset-${TOOLSET_VER}-libasan-devel) ---"
  elif command -v dnf >/dev/null 2>&1; then
    echo "--- Installing ASan runtime (dnf), may appear to hang ---"
    dnf install -y -q "gcc-toolset-${TOOLSET_VER}-libasan-devel" || true
  fi

  JOBS="${JOBS:-$(nproc)}"
  rm -rf native/qa
  cmake -B native/qa -S native -G "Unix Makefiles" -DIREE_DJL_SANITIZE=ON \
    -DCMAKE_BUILD_TYPE=Debug \
    -DCMAKE_CXX_FLAGS="-fsanitize=address -fno-omit-frame-pointer -g" \
    -DCMAKE_EXE_LINKER_FLAGS="-fsanitize=address"
  cmake --build native/qa --target iree_runtime_test iree_params_test iree_leak_harness -j"${JOBS}"

  echo "--- Catch2 unit suite ---"
  ./native/qa/iree_runtime_test

  echo "--- Catch2 parameter suite ---"
  ./native/qa/iree_params_test

  echo "--- ASan/LSan leak harness (${ITERS} iterations, local-sync) ---"
  ./native/qa/iree_leak_harness "${FIXTURE_DIR}/add.vmfb" "${ITERS}"

  echo "--- ASan/LSan leak harness (${ITERS} iterations, local-task worker pool) ---"
  ./native/qa/iree_leak_harness "${FIXTURE_DIR}/add.vmfb" "${ITERS}" local-task

  echo "--- ASan/LSan leak harness (${ITERS} iterations, parameter-bound, local-sync) ---"
  ./native/qa/iree_leak_harness "${FIXTURE_DIR}/scale.vmfb" "${ITERS}" local-sync \
    model="${FIXTURE_DIR}/scale_weights_zero.irpa"

  echo "--- native QA PASS ---"
fi
