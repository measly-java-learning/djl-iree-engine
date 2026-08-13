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

  # Sanitizer runtimes. In the pinned image these are baked in at an exact NEVRA (the image
  # publishes it as MEASLY_DJL_TOOLSET_NEVRA); a missing one means a BROKEN IMAGE, and
  # installing it here at whatever version dnf offers would silently defeat the pinning the
  # image exists to provide. So: assert inside the image, install only outside it, and never
  # silently.
  if [ -n "${MEASLY_DJL_PINNED_IMAGE:-}" ]; then
    # The marker without its companions means either a hand-set MEASLY_DJL_PINNED_IMAGE or an
    # image predating those variables. Say so: under `set -u` the bare dereference below would
    # abort with bash's "unbound variable", which is the one path through this block that would
    # NOT produce the legible message it exists to give.
    : "${MEASLY_DJL_TOOLSET_VER:?MEASLY_DJL_PINNED_IMAGE is set but MEASLY_DJL_TOOLSET_VER is not -- check the pin in .engine-build-image, or unset the marker for a host run}"
    : "${MEASLY_DJL_TOOLSET_NEVRA:?MEASLY_DJL_PINNED_IMAGE is set but MEASLY_DJL_TOOLSET_NEVRA is not -- check the pin in .engine-build-image, or unset the marker for a host run}"
    for _san in asan ubsan; do
      _pkg="gcc-toolset-${MEASLY_DJL_TOOLSET_VER}-lib${_san}-devel-${MEASLY_DJL_TOOLSET_NEVRA}"
      if ! rpm -q --quiet "${_pkg}"; then
        echo "BROKEN IMAGE: ${_pkg} is not installed at the pinned NEVRA." >&2
        echo "The image is published by measly-java-learning/base-docker-images; check the pin in" >&2
        echo ".engine-build-image rather than installing it here." >&2
        exit 1
      fi
      echo "--- ${_san} runtime present at pinned ${MEASLY_DJL_TOOLSET_NEVRA} ---"
    done
  else
    # Not the pinned image. Probe for what actually matters -- can this toolchain LINK a
    # sanitized binary -- rather than asking a package manager about a package name. The
    # probe is distro-agnostic (the previous rpm/dnf version was dead code on Ubuntu, which
    # is what both the workstation and the GitHub runner run: rpm is command-not-found and
    # `command -v dnf` is false, so the whole block was a silent no-op that had never run).
    # This script installs nothing: an install here would be unpinned by construction.
    echo "--- WARNING: not the pinned image; toolchain versions are unpinned and results are not comparable ---"
    _probe="$(mktemp -d)"
    printf 'int main(){return 0;}\n' > "${_probe}/probe.cpp"
    for _san in address undefined; do
      if ! "${CXX:-g++}" -fsanitize="${_san}" "${_probe}/probe.cpp" -o "${_probe}/probe" 2>/dev/null; then
        echo "cannot link -fsanitize=${_san} with ${CXX:-g++}; install your toolchain's sanitizer runtime" >&2
        echo "  Debian/Ubuntu: it ships with gcc (libasan/libubsan); try reinstalling g++" >&2
        echo "  RHEL family:   gcc-toolset-<N>-lib{asan,ubsan}-devel" >&2
        rm -rf "${_probe}"
        exit 1
      fi
    done
    rm -rf "${_probe}"
    echo "--- asan and ubsan runtimes link (unpinned host toolchain) ---"
  fi

  JOBS="${JOBS:-$(nproc)}"
  rm -rf native/qa
  # UBSan's default is print-and-continue; -fno-sanitize-recover (set in
  # native/CMakeLists.txt) makes it abort, and these make the abort legible.
  export UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1
  cmake -B native/qa -S native -G "Unix Makefiles" -DIREE_DJL_SANITIZE=ON -DIREE_DJL_UBSAN=ON \
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
